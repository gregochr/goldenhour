import React, { useEffect } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import { getWarnings } from '../api/warningsApi.js';
import {
  NO_WARNINGS, useWarnings, WARNINGS_CLIENT_STALE_MS, WARNINGS_POLL_MS, WARNINGS_POLL_OFF_MS, WarningsProvider,
} from '../context/WarningsContext.jsx';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';

function Probe() {
  const w = useWarnings();
  return (
    <div>
      <span data-testid="enabled">{String(w.enabled)}</span>
      <span data-testid="stale">{String(w.stale)}</span>
      <span data-testid="ids">{w.warnings.map((x) => x.warningId).join(',')}</span>
      <span data-testid="url">{w.metOfficeUrl}</span>
      <span data-testid="attr">{w.attribution}</span>
      <span data-testid="checked">{String(w.lastCheckedAt)}</span>
    </div>
  );
}

const mount = async () => {
  render(<WarningsProvider><Probe /></WarningsProvider>);
  await act(async () => {});
};

describe('WarningsProvider', () => {
  beforeEach(() => { getWarnings.mockReset(); });
  afterEach(() => { vi.useRealTimers(); });

  it('reads NO_WARNINGS outside a provider, so every consumer shows nothing', () => {
    render(<Probe />);
    expect(screen.getByTestId('enabled')).toHaveTextContent('false');
    expect(screen.getByTestId('ids')).toBeEmptyDOMElement();
    expect(NO_WARNINGS.enabled).toBe(false);
  });

  it('shares the served warnings in the style guide order, by id', async () => {
    getWarnings.mockResolvedValue(response([
      warning({ warningId: 'y' }),
      warning({ warningId: 'r', level: 'RED' }),
    ], { stale: true }));
    await mount();
    expect(screen.getByTestId('enabled')).toHaveTextContent('true');
    expect(screen.getByTestId('stale')).toHaveTextContent('true');
    expect(screen.getByTestId('ids')).toHaveTextContent('r,y');
    expect(screen.getByTestId('url')).toHaveTextContent(METOFFICE_URL);
    expect(screen.getByTestId('attr')).toHaveTextContent(ATTRIBUTION);
    expect(screen.getByTestId('checked')).toHaveTextContent('2026-08-14T13:05:00Z');
  });

  it('falls back to the default link and attribution when the body omits them, and to a null check time', async () => {
    getWarnings.mockResolvedValue({ enabled: true, warnings: [] });
    await mount();
    expect(screen.getByTestId('url')).toHaveTextContent('https://www.metoffice.gov.uk/public/weather/warnings/');
    expect(screen.getByTestId('attr')).toHaveTextContent('Met Office Weather Warnings');
    expect(screen.getByTestId('checked')).toHaveTextContent('null');
    expect(screen.getByTestId('stale')).toHaveTextContent('false');
  });

  it('reads as nothing when the feature is off, whatever else the body says', async () => {
    getWarnings.mockResolvedValue(response([warning()], { enabled: false, stale: true }));
    await mount();
    expect(screen.getByTestId('enabled')).toHaveTextContent('false');
    expect(screen.getByTestId('ids')).toBeEmptyDOMElement();
  });

  it('reads as nothing for a refusal (null) or a non-object', async () => {
    getWarnings.mockResolvedValue(null);
    await mount();
    expect(screen.getByTestId('enabled')).toHaveTextContent('false');
  });

  it('reads as nothing for a body that is not an object', async () => {
    getWarnings.mockResolvedValue('oops');
    await mount();
    expect(screen.getByTestId('enabled')).toHaveTextContent('false');
  });

  it('keeps the last answer when a later poll fails', async () => {
    vi.useFakeTimers();
    getWarnings.mockResolvedValueOnce(response([warning({ warningId: 'keep' })]));
    getWarnings.mockRejectedValue(new Error('network'));
    render(<WarningsProvider><Probe /></WarningsProvider>);
    await act(async () => {});
    expect(screen.getByTestId('ids')).toHaveTextContent('keep');
    await act(async () => { await vi.advanceTimersByTimeAsync(2 * 60 * 1000 + 10); });
    expect(getWarnings.mock.calls.length).toBeGreaterThan(1);
    expect(screen.getByTestId('ids')).toHaveTextContent('keep');
  });

  it('never lets an older answer land over a newer one', async () => {
    vi.useFakeTimers();
    let resolveFirst;
    getWarnings.mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }));
    getWarnings.mockResolvedValueOnce(response([warning({ warningId: 'newer' })]));
    render(<WarningsProvider><Probe /></WarningsProvider>);
    await act(async () => {});
    await act(async () => { await vi.advanceTimersByTimeAsync(2 * 60 * 1000 + 10); });
    expect(screen.getByTestId('ids')).toHaveTextContent('newer');
    await act(async () => { resolveFirst(response([warning({ warningId: 'older' })])); });
    expect(screen.getByTestId('ids')).toHaveTextContent('newer');
  });

  it('calls its own data stale, with its last good reply time, once polls have failed for ten minutes', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-14T13:00:00Z'));
    getWarnings.mockResolvedValueOnce(response([warning({ warningId: 'keep' })], { lastCheckedAt: '2026-08-14T12:59:00Z' }));
    getWarnings.mockRejectedValue(new Error('network'));
    render(<WarningsProvider><Probe /></WarningsProvider>);
    await act(async () => {});
    expect(screen.getByTestId('stale')).toHaveTextContent('false');
    // One failed poll inside the bound changes nothing.
    await act(async () => { await vi.advanceTimersByTimeAsync(WARNINGS_POLL_MS + 10); });
    expect(screen.getByTestId('stale')).toHaveTextContent('false');
    // Failures continuing past the bound flip it, still showing the warning, with our own last good time.
    await act(async () => { await vi.advanceTimersByTimeAsync(WARNINGS_CLIENT_STALE_MS); });
    expect(screen.getByTestId('stale')).toHaveTextContent('true');
    expect(screen.getByTestId('ids')).toHaveTextContent('keep');
    expect(screen.getByTestId('checked')).toHaveTextContent('2026-08-14T13:00:00.000Z');
  });

  it('clears the client stale flag when a poll succeeds again', async () => {
    vi.useFakeTimers();
    getWarnings.mockResolvedValueOnce(response([]));
    getWarnings.mockRejectedValueOnce(new Error('network'));
    getWarnings.mockRejectedValueOnce(new Error('network'));
    getWarnings.mockRejectedValueOnce(new Error('network'));
    getWarnings.mockRejectedValueOnce(new Error('network'));
    getWarnings.mockRejectedValueOnce(new Error('network'));
    getWarnings.mockRejectedValueOnce(new Error('network'));
    getWarnings.mockResolvedValue(response([]));
    render(<WarningsProvider><Probe /></WarningsProvider>);
    await act(async () => {});
    await act(async () => { await vi.advanceTimersByTimeAsync(WARNINGS_CLIENT_STALE_MS + WARNINGS_POLL_MS); });
    expect(screen.getByTestId('stale')).toHaveTextContent('true');
    await act(async () => { await vi.advanceTimersByTimeAsync(WARNINGS_POLL_MS); });
    expect(screen.getByTestId('stale')).toHaveTextContent('false');
  });

  it('polls far less often while the feature is off', async () => {
    vi.useFakeTimers();
    getWarnings.mockResolvedValue(response([], { enabled: false }));
    render(<WarningsProvider><Probe /></WarningsProvider>);
    await act(async () => {});
    const settled = getWarnings.mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(WARNINGS_POLL_MS * 5); });
    expect(getWarnings.mock.calls.length).toBe(settled);
    await act(async () => { await vi.advanceTimersByTimeAsync(WARNINGS_POLL_OFF_MS); });
    expect(getWarnings.mock.calls.length).toBeGreaterThan(settled);
  });

  describe('refresh', () => {
    let latest;
    function Grab() {
      const w = useWarnings();
      useEffect(() => { latest = w; });
      return null;
    }
    const mountGrab = async () => {
      render(<WarningsProvider><Grab /></WarningsProvider>);
      await act(async () => {});
    };

    it('fetches now and applies the reply, resolving true', async () => {
      getWarnings.mockResolvedValue(response([warning({ warningId: 'a' })]));
      await mountGrab();
      getWarnings.mockResolvedValue(response([warning({ warningId: 'a' }), warning({ warningId: 'b' })]));

      let ok;
      await act(async () => { ok = await latest.refresh(); });

      expect(ok).toBe(true);
      expect(latest.warnings.map((w) => w.warningId).sort()).toEqual(['a', 'b']);
    });

    it('shares one request with any already out, so the poll and a refresh never double up', async () => {
      let release;
      getWarnings.mockImplementation(() => new Promise((resolve) => { release = resolve; }));
      render(<WarningsProvider><Grab /></WarningsProvider>);
      await act(async () => {});
      const callsBefore = getWarnings.mock.calls.length;

      let first;
      let second;
      act(() => { first = latest.refresh(); second = latest.refresh(); });
      expect(getWarnings.mock.calls.length).toBe(callsBefore);
      await act(async () => { release(response([])); await Promise.all([first, second]); });

      expect(await first).toBe(true);
      expect(await second).toBe(true);
    });

    it('resolves false when the request fails and keeps the last list', async () => {
      getWarnings.mockResolvedValue(response([warning({ warningId: 'a' })]));
      await mountGrab();
      getWarnings.mockRejectedValue(new Error('offline'));

      let ok;
      await act(async () => { ok = await latest.refresh(); });

      expect(ok).toBe(false);
      expect(latest.warnings.map((w) => w.warningId)).toEqual(['a']);
    });

    it('is a harmless no-op outside a provider', async () => {
      function Outside() {
        const w = useWarnings();
        useEffect(() => { latest = w; });
        return null;
      }
      render(<Outside />);
      await act(async () => {});

      await expect(latest.refresh()).resolves.toBeNull();
    });

    it('is available before the first reply, and with the feature off', async () => {
      getWarnings.mockResolvedValue(response([], { enabled: false }));
      await mountGrab();

      expect(typeof latest.refresh).toBe('function');
      expect(latest.enabled).toBe(false);
    });
  });
});
