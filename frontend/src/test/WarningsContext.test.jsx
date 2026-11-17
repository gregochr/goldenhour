import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import { getWarnings } from '../api/warningsApi.js';
import { NO_WARNINGS, useWarnings, WarningsProvider } from '../context/WarningsContext.jsx';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';

function Probe() {
  const w = useWarnings();
  return (
    <div>
      <span data-testid="enabled">{String(w.enabled)}</span>
      <span data-testid="stale">{String(w.stale)}</span>
      <span data-testid="ids">{w.warnings.map((x) => x.warningId).join(',')}</span>
      <span data-testid="by-id">{[...w.byId.keys()].join(',')}</span>
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
    expect(screen.getByTestId('by-id')).toHaveTextContent('r,y');
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
});
