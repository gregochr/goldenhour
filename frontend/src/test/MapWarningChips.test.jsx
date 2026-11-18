/**
 * The Map tab's warning chips (weather-warnings-plan.md W4).
 *
 * <p>What breaks if these fail: a warning shows on a night or past window; the licence's attribution goes missing; a
 * stale or empty feed reads as an all-clear; the chip shows while the flag is off; or a withdrawn warning lingers
 * until a stored id refreshes instead of leaving at the next poll.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider, WARNINGS_POLL_MS } from '../context/WarningsContext.jsx';
import MapWarningChips from '../components/map/MapWarningChips.jsx';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';

const DATE = '2026-06-15';
const EVENT = {
  kind: 'solar', eventType: 'SUNSET', date: DATE, past: false,
};
const LOCATIONS = [{
  id: 7,
  forecastsByDate: new Map([[DATE, { sunset: { solarEventTime: `${DATE}T20:10:00` } }]]),
}];
const span = { validFrom: `${DATE}T00:00:00Z`, validTo: `${DATE}T23:59:00Z` };
const YELLOW = warning({ warningId: 'y', weatherTypes: ['WIND'], ...span });
const RED = warning({
  warningId: 'r', level: 'RED', weatherTypes: ['RAIN'], updated: true, ...span,
});

async function mount(served, props = {}) {
  getWarnings.mockResolvedValue(served);
  await act(async () => {
    render(
      <WarningsProvider>
        <MapWarningChips event={EVENT} locations={LOCATIONS} {...props} />
      </WarningsProvider>,
    );
  });
}

beforeEach(() => {
  getWarnings.mockReset();
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.setSystemTime(new Date(`${DATE}T10:00:00Z`));
});
afterEach(() => { vi.useRealTimers(); });

describe('MapWarningChips', () => {
  it('draws the window\'s warnings most severe first, level word printed, Updated for the served flag', async () => {
    await mount(response([YELLOW, RED]));
    const chips = screen.getAllByTestId('warning-chip');
    expect(chips.map((c) => c.dataset.level)).toEqual(['RED', 'YELLOW']);
    expect(chips[0]).toHaveTextContent('Red warning of rain');
    expect(chips[0]).toHaveTextContent('Updated');
    expect(chips[1]).toHaveAccessibleName(/Yellow warning of wind/);
  });

  it('is a labelled group of Met Office links and prints the attribution and source link', async () => {
    await mount(response([YELLOW]));
    expect(screen.getByRole('group', { name: 'Met Office weather warnings' })).toBeInTheDocument();
    expect(screen.getByTestId('warning-chip')).toHaveAttribute('href', METOFFICE_URL);
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    expect(screen.getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
  });

  it('prints nothing when the server says the feature is off, even with warnings and a stale flag in the body', async () => {
    await mount(response([YELLOW], { enabled: false, stale: true }));
    expect(screen.queryByTestId('wf-map-warnings')).toBeNull();
  });

  it('draws no icon or glyph: text chips only', async () => {
    await mount(response([YELLOW]));
    expect(screen.getByTestId('wf-map-warnings').querySelector('svg, img, canvas')).toBeNull();
  });

  it('prints nothing (never an all-clear) for a window with no warning on a fresh feed', async () => {
    await mount(response([warning({ warningId: 'z', locationIds: [99], ...span })]));
    expect(screen.queryByTestId('wf-map-warnings')).toBeNull();
    expect(screen.queryByText(/no warnings|all clear/i)).toBeNull();
  });

  it('says so when the feed is stale, with the chips alongside', async () => {
    await mount(response([YELLOW], { stale: true, lastCheckedAt: `${DATE}T09:05:00Z` }));
    expect(screen.getByTestId('warning-stale')).toHaveTextContent(/Warnings last checked/);
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('prints only the stale line, with its date, when stale and nothing covers the window', async () => {
    await mount(response([], { stale: true, lastCheckedAt: '2026-06-14T09:05:00Z' }));
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    expect(screen.getByTestId('warning-stale')).toHaveTextContent(/Warnings last checked Sun 14 Jun/);
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
  });

  it.each([
    ['a night', { ...EVENT, kind: 'astro' }],
    ['High water', { ...EVENT, kind: 'hw' }],
    ['a past window', { ...EVENT, past: true }],
    ['no window', null],
  ])('prints nothing for %s, even on a stale feed', async (_n, event) => {
    await mount(response([YELLOW], { stale: true }), { event });
    expect(screen.queryByTestId('wf-map-warnings')).toBeNull();
  });

  it('follows the window: a warning not in force at another day\'s instant is not drawn', async () => {
    await mount(response([YELLOW]), { event: { ...EVENT, date: '2026-06-16' } });
    expect(screen.queryByTestId('warning-chip')).toBeNull();
  });

  it('lets a withdrawn warning go at the next poll', async () => {
    await mount(response([YELLOW]));
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
    getWarnings.mockResolvedValue(response([]));
    await act(async () => { await vi.advanceTimersByTimeAsync(WARNINGS_POLL_MS + 1000); });
    expect(screen.queryByTestId('warning-chip')).toBeNull();
  });

  it('does not take pointer events on its row, and adds no width of its own to the chrome group', async () => {
    await mount(response([YELLOW]));
    const row = screen.getByTestId('wf-map-warnings-row');
    expect(row).toHaveClass('pointer-events-none', 'w-0', 'min-w-full');
    expect(screen.getByTestId('wf-map-warnings')).toHaveClass('pointer-events-auto');
  });
});
