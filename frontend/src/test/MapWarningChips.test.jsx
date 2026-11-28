/**
 * The Map tab's warning rows (weather-warnings-plan.md W4; map-warning-row-plan.md M1-M4).
 *
 * <p>What breaks if these fail: a warning shows on a night or past window; the licence's attribution goes missing; a
 * stale or empty feed reads as an all-clear; the row shows while the flag is off; a withdrawn warning lingers until a
 * stored id refreshes instead of leaving at the next poll; a row's validity splits or its name loses "UK time"; or a
 * link ends up inside the row's button.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, within,
} from '@testing-library/react';

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
  it('prints each warning\'s compact validity in UK time on its row, and says "valid ... UK time" in its name', async () => {
    const a = warning({
      warningId: 'a', weatherTypes: ['ICE'], validFrom: `${DATE}T03:00:00Z`, validTo: `${DATE}T21:00:00Z`,
    });
    const b = warning({
      warningId: 'b', weatherTypes: ['WIND'], validFrom: `${DATE}T10:00:00Z`, validTo: '2026-06-16T08:00:00Z',
    });
    await mount(response([a, b]));
    const shown = screen.getAllByTestId('map-warning-validity').map((n) => n.textContent);
    expect(shown).toEqual(['Mon 04:00\u201322:00', 'Mon 11:00 \u2013 Tue 09:00']);
    screen.getAllByTestId('map-warning-validity').forEach((n) => expect(n).toHaveClass('whitespace-nowrap'));
    const rows = screen.getAllByTestId('map-warning-row');
    expect(rows[0]).toHaveAccessibleName('Yellow warning of ice, valid Mon 04:00\u201322:00 UK time');
    expect(rows[1]).toHaveAccessibleName('Yellow warning of wind, valid Mon 11:00 \u2013 Tue 09:00 UK time');
  });

  it('draws one full-width disclosure button per warning, with the chip as plain text (never a link in a button)', async () => {
    await mount(response([YELLOW, RED]));
    const rows = screen.getAllByTestId('map-warning-row');
    expect(rows).toHaveLength(2);
    rows.forEach((row) => {
      expect(row.tagName).toBe('BUTTON');
      expect(row).toHaveAttribute('type', 'button');
      expect(row).toHaveClass('w-full', 'min-h-[36px]');
      expect(row.querySelector('a')).toBeNull();
      expect(within(row).getByTestId('warning-chip').tagName).toBe('SPAN');
      expect(within(row).getByTestId('map-warning-chevron')).toHaveAttribute('aria-hidden', 'true');
    });
    expect(screen.getByTestId('wf-map-warnings')).toHaveClass('w-full', 'rounded-[10px]');
    expect(screen.getByTestId('wf-map-warnings')).not.toHaveClass('inline-block');
  });

  it('prints the validity even when the parent offers no toggle (the row then does nothing)', async () => {
    await mount(response([YELLOW]));
    expect(screen.getByTestId('map-warning-validity')).toHaveTextContent('Mon 01:00 \u2013 Tue 00:59');
    fireEvent.click(screen.getByTestId('map-warning-row'));
    expect(screen.getByTestId('map-warning-row')).toHaveAttribute('aria-expanded', 'false');
  });

  it('draws the window\'s warnings most severe first, level word printed, Updated for the served flag', async () => {
    await mount(response([YELLOW, RED]));
    const chips = screen.getAllByTestId('warning-chip');
    expect(chips.map((c) => c.dataset.level)).toEqual(['RED', 'YELLOW']);
    expect(chips[0]).toHaveTextContent('Red warning of rain');
    expect(chips[0]).toHaveTextContent('Updated');
    expect(screen.getAllByTestId('map-warning-row')[1]).toHaveAccessibleName(/Yellow warning of wind/);
  });

  it('is a labelled group and prints the licence line, itself the link to the Met Office', async () => {
    await mount(response([YELLOW]));
    expect(screen.getByRole('group', { name: 'Met Office weather warnings' })).toBeInTheDocument();
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    const source = screen.getByTestId('warning-source-link');
    expect(source).toHaveAttribute('href', METOFFICE_URL);
    expect(source).toHaveAccessibleName(`${ATTRIBUTION}, Met Office warnings page, opens in a new tab`);
    expect(screen.getByTestId('warning-attribution-line')).toHaveClass('justify-between');
  });

  it('opens one warning at a time in place under its row; the chevron turns and aria-controls names the region', async () => {
    const onToggle = vi.fn();
    await mount(response([YELLOW, RED]), { openId: 'r', onToggle });
    const [redRow, yellowRow] = screen.getAllByTestId('map-warning-row');
    const region = screen.getByTestId('map-warning-region');
    expect(screen.getAllByTestId('map-warning-region')).toHaveLength(1);
    expect(redRow).toHaveAttribute('aria-expanded', 'true');
    expect(redRow).toHaveAttribute('aria-controls', region.id);
    expect(redRow.parentElement).toContainElement(region);
    expect(within(redRow).getByTestId('map-warning-chevron')).toHaveClass('rotate-180');
    expect(yellowRow).toHaveAttribute('aria-expanded', 'false');
    expect(yellowRow).not.toHaveAttribute('aria-controls');
    expect(within(yellowRow).getByTestId('map-warning-chevron')).not.toHaveClass('rotate-180');
    fireEvent.click(yellowRow);
    expect(onToggle).toHaveBeenCalledWith('y', yellowRow);
  });

  it('shows the full warning in the region: UK-time validity, what changed, text, then the two Met Office links', async () => {
    const full = warning({
      warningId: 'f',
      weatherTypes: ['SNOW'],
      headline: 'Snow headline',
      whatToExpect: ['Roads will be icy'],
      updated: true,
      updateDescription: 'Extended south.',
      ...span,
    });
    await mount(response([full]), { openId: 'f', onToggle: vi.fn() });
    const region = screen.getByTestId('map-warning-region');
    expect(region).toHaveAttribute('role', 'region');
    expect(region).toHaveAccessibleName(/Yellow warning of snow/);
    expect(region).toHaveClass('max-h-[300px]', 'max-sm:max-h-[44vh]', 'overflow-y-auto');
    expect(within(region).getByTestId('map-warning-full-validity')).toHaveTextContent(/Valid Mon 15 Jun, 01:00 to .*\(UK time\)/);
    expect(within(region).getByTestId('window-warning-update')).toHaveTextContent('Extended south.');
    expect(within(region).getByText('What\'s changed')).toBeInTheDocument();
    expect(within(region).getByTestId('window-warning-headline')).toHaveTextContent('Snow headline');
    expect(within(region).getByTestId('window-warning-expect')).toHaveTextContent('Roads will be icy');
    expect(within(region).queryByTestId('warning-chip')).toBeNull();
    expect(within(region).queryByTestId('warning-attribution')).toBeNull();
    expect(within(region).getByTestId('map-warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
    expect(within(region).getByTestId('map-warning-source-link')).toHaveTextContent('Read it on the Met Office site');
    expect(within(region).getByTestId('map-warning-advice-link'))
      .toHaveAttribute('href', 'https://www.metoffice.gov.uk/weather/warnings-and-advice/seasonal-advice');
    // The one attribution on screen is the footer's.
    expect(screen.getAllByTestId('warning-attribution')).toHaveLength(1);
  });

  it('shows three rows, then "+N more warnings" that reveals the rest in place', async () => {
    const many = ['a', 'b', 'c', 'd', 'e'].map((id, i) => warning({
      warningId: id, weatherTypes: ['WIND'], ...span, validFrom: `${DATE}T0${i}:00:00Z`,
    }));
    await mount(response(many));
    expect(screen.getAllByTestId('map-warning-row')).toHaveLength(3);
    const more = screen.getByTestId('map-warning-more');
    expect(more).toHaveTextContent('+2 more warnings');
    fireEvent.click(more);
    expect(screen.getAllByTestId('map-warning-row')).toHaveLength(5);
    expect(screen.queryByTestId('map-warning-more')).toBeNull();
  });

  it('says "+1 more warning" in the singular', async () => {
    const four = ['a', 'b', 'c', 'd'].map((id, i) => warning({
      warningId: id, weatherTypes: ['WIND'], ...span, validFrom: `${DATE}T0${i}:00:00Z`,
    }));
    await mount(response(four));
    expect(screen.getByTestId('map-warning-more')).toHaveTextContent('+1 more warning');
    expect(screen.getByTestId('map-warning-more')).not.toHaveTextContent('warnings');
  });

  it('prints no "more" row for three or fewer', async () => {
    await mount(response([YELLOW, RED]));
    expect(screen.queryByTestId('map-warning-more')).toBeNull();
  });

  it('prints nothing when the server says the feature is off, even with warnings and a stale flag in the body', async () => {
    await mount(response([YELLOW], { enabled: false, stale: true }));
    expect(screen.queryByTestId('wf-map-warnings')).toBeNull();
  });

  it('draws no icon or glyph beyond the chevron: text chips only', async () => {
    await mount(response([YELLOW]));
    const box = screen.getByTestId('wf-map-warnings');
    expect(box.querySelector('img, canvas')).toBeNull();
    expect(Array.from(box.querySelectorAll('svg')).every((svg) => svg.closest('[data-testid="map-warning-chevron"]'))).toBe(true);
  });

  it('prints nothing (never an all-clear) for a window with no warning on a fresh feed', async () => {
    await mount(response([warning({ warningId: 'z', locationIds: [99], ...span })]));
    expect(screen.queryByTestId('wf-map-warnings')).toBeNull();
    expect(screen.queryByText(/no warnings|all clear/i)).toBeNull();
  });

  it('says so on a line of its own when the feed is stale, with the rows alongside', async () => {
    await mount(response([YELLOW], { stale: true, lastCheckedAt: `${DATE}T07:05:00Z` }));
    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Last checked 2 h ago. These may be out of date.');
    expect(screen.getByTestId('map-warning-row')).toBeInTheDocument();
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

  it('prints the stale line then the Met Office link, and no row, when stale and nothing covers the window', async () => {
    await mount(response([], { stale: true, lastCheckedAt: '2026-06-14T09:05:00Z' }));
    expect(screen.queryByTestId('map-warning-row')).toBeNull();
    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Last checked 24 h ago. These may be out of date.');
    expect(screen.getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
    const stale = screen.getByTestId('warning-stale');
    expect(stale.compareDocumentPosition(screen.getByTestId('warning-attribution')))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
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

  it('does not take pointer events on its row, and asks the chrome group for 24rem, no less than the group and no more than its cap', async () => {
    await mount(response([YELLOW]));
    const row = screen.getByTestId('wf-map-warnings-row');
    expect(row).toHaveClass('pointer-events-none', 'w-[24rem]', 'min-w-full', 'max-w-full');
    expect(screen.getByTestId('wf-map-warnings')).toHaveClass('pointer-events-auto');
  });
});
