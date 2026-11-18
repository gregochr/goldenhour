import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

let served;
vi.mock('../context/WarningsContext.jsx', () => ({ useWarnings: () => served }));

import AskWarnings from '../components/ask/AskWarnings.jsx';

const NOW = '2026-10-05T12:00:00Z';
const FRESH = '2026-10-05T11:58:00Z';
const OLD = '2026-10-05T11:45:00Z';

const answer = (checkedAt, over = {}, itemOver = {}) => ({
  stale: false,
  attribution: 'Met Office Weather Warnings © Crown Copyright 2026',
  url: 'https://www.metoffice.gov.uk/public/weather/warnings/',
  checkedAt,
  items: [{
    warningId: 'W1', warningVersion: '2.0', level: 'AMBER', weatherTypes: ['SNOW'], validFrom: null, validTo: null,
    headline: 'Snow', updated: false, ...itemOver,
  }],
  ...over,
});

const live = (version = '2.0') => ({ warningId: 'W1', warningVersion: version, level: 'AMBER', weatherTypes: ['SNOW'] });

/** The provider as the component sees it, before any row overrides it. */
const provider = (over = {}) => ({
  enabled: true, stale: false, lastCheckedAt: null, warnings: [], lastAttemptFailed: false,
  metOfficeUrl: 'https://www.metoffice.gov.uk/public/weather/warnings/', attribution: 'x',
  refresh: vi.fn(() => Promise.resolve(true)),
  ...over,
});

const shown = () => {
  if (screen.queryByTestId('ask-warning-retired')) return screen.getByTestId('ask-warning-retired').dataset.state;
  return screen.queryByTestId('ask-warning') ? 'current' : 'none';
};

describe('AskWarnings — the whole state machine', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(NOW));
  });
  afterEach(() => { vi.useRealTimers(); });

  it.each([
    ['list never arrived, fresh answer', { enabled: false }, FRESH, {}, 'current', false, false],
    ['list never arrived, answer past ten minutes', { enabled: false }, OLD, {}, 'current', true, false],
    ['initial failure, fresh answer', { enabled: false, lastAttemptFailed: true }, FRESH, {}, 'current', true, false],
    ['list read but never checked', { lastCheckedAt: null }, FRESH, {}, 'current', false, false],
    ['older list, fresh answer', { lastCheckedAt: '2026-10-05T11:50:00Z' }, FRESH, {}, 'current', false, true],
    ['older list, answer past ten minutes', { lastCheckedAt: '2026-10-05T11:40:00Z' }, OLD, {}, 'current', true, true],
    ['older list, refresh failed', { lastCheckedAt: '2026-10-05T11:50:00Z', lastAttemptFailed: true }, FRESH, {},
      'current', true, true],
    ['newer list lacking the id', { lastCheckedAt: '2026-10-05T11:59:00Z' }, FRESH, {}, 'gone', false, false],
    ['newer list lacking the id, old answer', { lastCheckedAt: '2026-10-05T11:59:00Z' }, OLD, {}, 'gone', false, false],
    ['newer list holding it', { lastCheckedAt: '2026-10-05T11:59:00Z', warnings: [live()] }, FRESH, {},
      'current', false, false],
    ['list as new as the answer', { lastCheckedAt: FRESH, warnings: [live()] }, FRESH, {}, 'current', false, false],
    ['newer list, higher version', { lastCheckedAt: '2026-10-05T11:59:00Z', warnings: [live('3.0')] }, FRESH, {},
      'updated', false, false],
    ['newer list, older version', { lastCheckedAt: '2026-10-05T11:59:00Z', warnings: [live('1.0')] }, FRESH, {},
      'current', false, false],
    ['newer list, unreadable versions', { lastCheckedAt: '2026-10-05T11:59:00Z', warnings: [live('x')] }, FRESH, {},
      'current', false, false],
    ['newer list, provider stale', { lastCheckedAt: '2026-10-05T11:59:00Z', warnings: [live()], stale: true }, FRESH, {},
      'current', true, false],
    ['newer list, last poll failed since', { lastCheckedAt: '2026-10-05T11:59:00Z', warnings: [live()],
      lastAttemptFailed: true }, FRESH, {}, 'current', false, false],
    ['server said stale', { lastCheckedAt: '2026-10-05T11:59:00Z', warnings: [live()] }, FRESH, { stale: true },
      'current', true, false],
    ['answer with no check time, list read', { lastCheckedAt: '2026-10-05T11:59:00Z' }, null, {}, 'gone', false, false],
    ['answer with no check time, never arrived', { enabled: false }, null, {}, 'current', false, false],
  ])('%s', (_name, over, checkedAt, answerOver, state, stale, refreshes) => {
    served = provider(over);

    render(<AskWarnings warnings={answer(checkedAt, answerOver)} />);

    expect(shown()).toBe(state);
    expect(Boolean(screen.queryByTestId('ask-warnings-stale'))).toBe(stale);
    expect(served.refresh).toHaveBeenCalledTimes(refreshes ? 1 : 0);
  });

  it('says so by itself when an answer left open crosses the ten-minute bound', () => {
    served = provider({ enabled: false });
    render(<AskWarnings warnings={answer('2026-10-05T11:51:00Z')} />);
    expect(screen.queryByTestId('ask-warnings-stale')).toBeNull();

    act(() => { vi.advanceTimersByTime(60 * 1000); });
    expect(screen.queryByTestId('ask-warnings-stale')).toBeNull();
    act(() => { vi.advanceTimersByTime(2 * 1000); });

    expect(screen.getByTestId('ask-warnings-stale')).toBeInTheDocument();
    expect(screen.getByTestId('ask-warning')).toBeInTheDocument();
  });

  it('does not run its timer once a live list confirms the facts, or after it unmounts', () => {
    served = provider({ enabled: false });
    const { rerender, unmount } = render(<AskWarnings warnings={answer('2026-10-05T11:51:00Z')} />);
    served = provider({ lastCheckedAt: '2026-10-05T11:59:00Z', warnings: [live()] });
    rerender(<AskWarnings warnings={answer('2026-10-05T11:51:00Z')} />);

    act(() => { vi.advanceTimersByTime(30 * 60 * 1000); });
    expect(screen.queryByTestId('ask-warnings-stale')).toBeNull();

    served = provider({ enabled: false });
    rerender(<AskWarnings warnings={answer('2026-10-05T11:55:00Z')} />);
    unmount();
    expect(() => act(() => { vi.advanceTimersByTime(30 * 60 * 1000); })).not.toThrow();
  });

  it('recovers when a live list finally arrives: the stale line clears and retire/update logic applies', () => {
    served = provider({ enabled: false, lastAttemptFailed: true });
    const { rerender } = render(<AskWarnings warnings={answer(OLD)} />);
    expect(screen.getByTestId('ask-warnings-stale')).toBeInTheDocument();
    expect(shown()).toBe('current');

    served = provider({ lastCheckedAt: '2026-10-05T11:59:00Z', warnings: [live('3.0')] });
    rerender(<AskWarnings warnings={answer(OLD)} />);

    expect(screen.queryByTestId('ask-warnings-stale')).toBeNull();
    expect(shown()).toBe('updated');
  });

  it('judges a replacement answer afresh: a newer answer clears what the older one earned', () => {
    served = provider({ enabled: false });
    const { rerender } = render(<AskWarnings warnings={answer(OLD)} />);
    expect(screen.getByTestId('ask-warnings-stale')).toBeInTheDocument();

    rerender(<AskWarnings warnings={answer(FRESH)} />);

    expect(screen.queryByTestId('ask-warnings-stale')).toBeNull();
  });

  it('refreshes again only when a different older list arrives, never for the same one', () => {
    served = provider({ lastCheckedAt: '2026-10-05T11:50:00Z' });
    const { rerender } = render(<AskWarnings warnings={answer(FRESH)} />);
    rerender(<AskWarnings warnings={answer(FRESH)} />);
    expect(served.refresh).toHaveBeenCalledTimes(1);

    const refresh = served.refresh;
    served = provider({ lastCheckedAt: '2026-10-05T11:52:00Z', refresh });
    rerender(<AskWarnings warnings={answer(FRESH)} />);

    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('survives unmounting while a refresh is out', async () => {
    let finish;
    served = provider({
      lastCheckedAt: '2026-10-05T11:50:00Z',
      refresh: vi.fn(() => new Promise((resolve) => { finish = resolve; })),
    });
    const { unmount } = render(<AskWarnings warnings={answer(FRESH)} />);
    unmount();

    await act(async () => { finish(true); });

    expect(served.refresh).toHaveBeenCalledTimes(1);
  });

  it('draws nothing for an answer with no warnings, whatever the provider says', () => {
    served = provider({ enabled: false, lastAttemptFailed: true });
    const { container } = render(<AskWarnings warnings={null} />);

    expect(container).toBeEmptyDOMElement();
    expect(served.refresh).not.toHaveBeenCalled();
  });
});
