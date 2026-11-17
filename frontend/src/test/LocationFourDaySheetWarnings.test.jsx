/**
 * Met Office warning chips on the four-day location sheet (weather-warnings-plan.md W2).
 *
 * <p>What breaks if these fail: a stood-down or unscored row loses its warning (the two-question rule: a warning is
 * what is happening, not a rating); a place with no stored row for a window shows no warning it is covered by; a chip
 * is drawn without its level word or without the licence's attribution; the feature leaks onto the sheet while the
 * flag is off; or a chip rewrites what the row says about the rating.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import LocationFourDaySheet from '../components/LocationFourDaySheet.jsx';
import { buildScoreIndex, buildSlotIndex } from '../utils/locationSheet.js';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';

const TODAY = '2026-08-14';
const SPOT = { id: 7, name: 'Bamburgh', regionName: 'Northumberland' };

const WINDOWS = [
  { key: '2026-08-14:SUNSET', date: '2026-08-14', targetType: 'SUNSET', dow: 'Fri', sunrise: false, label: 'Tonight Sunset', time: '20:37', verdictLabel: 'Worth it', confidence: 'high', away: false },
  { key: '2026-08-15:SUNRISE', date: '2026-08-15', targetType: 'SUNRISE', dow: 'Sat', sunrise: true, label: 'Tomorrow Sunrise', time: '05:38', verdictLabel: 'Maybe', confidence: 'high', away: false },
  { key: '2026-08-15:SUNSET', date: '2026-08-15', targetType: 'SUNSET', dow: 'Sat', sunrise: false, label: 'Tomorrow Sunset', time: '20:35', verdictLabel: 'Poor', confidence: 'high', away: false },
  { key: '2026-08-16:SUNRISE', date: '2026-08-16', targetType: 'SUNRISE', dow: 'Sun', sunrise: true, label: 'Sun Sunrise', time: '05:40', verdictLabel: 'Not forecast', confidence: null, away: true },
];

/** Friday's sunset is rated; Saturday's sunrise is stood down; Saturday's sunset has no score row at all. */
const SCORES = buildScoreIndex([
  { locationId: 7, locationName: 'Bamburgh', date: '2026-08-14', targetType: 'SUNSET', rating: 4, summary: 'Good.' },
  { locationId: 7, locationName: 'Bamburgh', date: '2026-08-15', targetType: 'SUNRISE', displayVerdict: 'STAND_DOWN' },
]);

const slot = (date, targetType, eventTime) => ({
  targetType,
  regions: [{
    regionName: 'Northumberland',
    confidence: 'medium',
    slots: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: eventTime }],
  }],
  date,
});
const SLOTS = buildSlotIndex([
  { date: '2026-08-14', eventSummaries: [slot('2026-08-14', 'SUNSET', '2026-08-14T19:41:00')] },
  { date: '2026-08-15', eventSummaries: [slot('2026-08-15', 'SUNRISE', '2026-08-15T04:38:00'), slot('2026-08-15', 'SUNSET', '2026-08-15T19:39:00')] },
]);

const YELLOW = warning({ warningId: 'y', level: 'YELLOW', weatherTypes: ['WIND'], validFrom: '2026-08-14T00:00:00Z', validTo: '2026-08-17T00:00:00Z' });
const RED = warning({ warningId: 'r', level: 'RED', weatherTypes: ['RAIN'], updated: true, validFrom: '2026-08-14T00:00:00Z', validTo: '2026-08-17T00:00:00Z' });

/** The roster record: Friday's sunset names two warnings, Saturday's sunrise (stood down) names one, and Saturday has no sunset row. */
const LOCATION = {
  id: 7,
  name: 'Bamburgh',
  forecastsByDate: new Map([
    ['2026-08-14', { sunset: { warningIds: ['y', 'r'] } }],
    ['2026-08-15', { sunrise: { warningIds: ['r'] } }],
  ]),
};

const mount = async (served, props = {}) => {
  getWarnings.mockResolvedValue(served);
  render(
    <WarningsProvider>
      <LocationFourDaySheet
        spot={SPOT}
        windows={WINDOWS}
        scoreIndex={SCORES}
        slotIndex={SLOTS}
        scoresKnown
        todayStr={TODAY}
        location={LOCATION}
        onClose={vi.fn()}
        onShowOnMap={vi.fn()}
        {...props}
      />
    </WarningsProvider>,
  );
  await act(async () => {});
};

const row = (key) => screen.getAllByTestId('location-sheet-row').find((el) => el.dataset.window === key);

describe('LocationFourDaySheet warnings', () => {
  beforeEach(() => { getWarnings.mockReset(); });

  it('draws a row\'s named warnings as chips, most severe first, with the level word printed', async () => {
    await mount(response([YELLOW, RED]));
    const chips = within(row('2026-08-14:SUNSET')).getAllByTestId('warning-chip');
    expect(chips.map((c) => c.dataset.level)).toEqual(['RED', 'YELLOW']);
    expect(chips[0]).toHaveTextContent('Red warning of rain');
    expect(chips[1]).toHaveTextContent('Yellow warning of wind');
  });

  it('labels the re-issued warning "Updated" and only that one', async () => {
    await mount(response([YELLOW, RED]));
    const chips = within(row('2026-08-14:SUNSET')).getAllByTestId('warning-chip');
    expect(within(chips[0]).getByTestId('warning-chip-updated')).toBeInTheDocument();
    expect(within(chips[1]).queryByTestId('warning-chip-updated')).toBeNull();
  });

  it('keeps a stood-down row\'s warning, and the row still says it was stood down', async () => {
    await mount(response([YELLOW, RED]));
    const stood = row('2026-08-15:SUNRISE');
    expect(within(stood).getByTestId('location-sheet-state')).toHaveTextContent('Stood down');
    expect(within(stood).getByTestId('warning-chip')).toHaveTextContent('Red warning of rain');
  });

  it('keeps an unscored row\'s warning', async () => {
    await mount(response([YELLOW, RED]), { scoreIndex: buildScoreIndex([]) });
    const unscored = row('2026-08-14:SUNSET');
    expect(within(unscored).getByTestId('location-sheet-state')).toHaveTextContent('Not scored yet');
    expect(within(unscored).getAllByTestId('warning-chip')).toHaveLength(2);
  });

  it('matches a place with no stored row for the window against the served list', async () => {
    // Saturday's sunset has no row on the roster record, so it carries no ids; the warning covers place 7 then.
    const covering = warning({ warningId: 'cover', level: 'AMBER', weatherTypes: ['SNOW'], locationIds: [7], validFrom: '2026-08-15T12:00:00Z', validTo: '2026-08-16T12:00:00Z' });
    await mount(response([covering]));
    expect(within(row('2026-08-15:SUNSET')).getByTestId('warning-chip')).toHaveTextContent('Amber warning of snow');
    // Not the windows outside its span, whose stored rows name no warning.
    expect(within(row('2026-08-15:SUNRISE')).queryByTestId('warning-chip')).toBeNull();
  });

  it('does not match a place the warning does not cover', async () => {
    const elsewhere = warning({ warningId: 'far', locationIds: [99], validFrom: '2026-08-15T12:00:00Z', validTo: '2026-08-16T12:00:00Z' });
    await mount(response([elsewhere]));
    expect(within(row('2026-08-15:SUNSET')).queryByTestId('warning-chip')).toBeNull();
  });

  it('matches by served list when the sheet has no roster record at all', async () => {
    const covering = warning({ warningId: 'cover', locationIds: [7], validFrom: '2026-08-14T00:00:00Z', validTo: '2026-08-17T00:00:00Z' });
    await mount(response([covering]), { location: null });
    expect(within(row('2026-08-14:SUNSET')).getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('draws nothing on a travel day', async () => {
    await mount(response([YELLOW, RED]));
    expect(within(row('2026-08-16:SUNRISE')).queryByTestId('warning-chip')).toBeNull();
  });

  it('keeps the chips outside the disclosure button, so a chip is never a control inside a control', async () => {
    await mount(response([YELLOW, RED]));
    const r = row('2026-08-14:SUNSET');
    const toggle = within(r).getByTestId('location-sheet-row-toggle');
    expect(within(toggle).queryByTestId('warning-chip')).toBeNull();
    expect(toggle).not.toHaveAccessibleName(/warning/i);
  });

  it('leaves the rating exactly as served beside the chips', async () => {
    await mount(response([YELLOW, RED]));
    const r = within(row('2026-08-14:SUNSET'));
    expect(r.getByTestId('location-sheet-rating')).toHaveTextContent('4★');
    expect(r.getByTestId('location-sheet-warnings')).not.toContainElement(r.getByTestId('location-sheet-rating'));
  });

  it('prints the attribution and the Met Office link once, in the sheet footer', async () => {
    await mount(response([YELLOW, RED]));
    expect(screen.getAllByTestId('warning-attribution')).toHaveLength(1);
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    expect(screen.getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
  });

  it('shows the same chips to every role (the sheet reads no role)', async () => {
    await mount(response([YELLOW, RED]), { isPro: false, scoresLocked: false });
    expect(within(row('2026-08-14:SUNSET')).getAllByTestId('warning-chip')).toHaveLength(2);
  });

  it('shows the stale line and no "none" claim when the feed is stale with no warning', async () => {
    await mount(response([], { stale: true }));
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Warnings last checked 14:05');
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
  });

  it('shows nothing at all while the feature is off', async () => {
    await mount(response([YELLOW, RED], { enabled: false, stale: true }));
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    expect(screen.queryByTestId('warning-footer')).toBeNull();
  });

  it('shows nothing when the warnings request is refused', async () => {
    await mount(null);
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    expect(screen.queryByTestId('warning-footer')).toBeNull();
  });

  it('renders as before outside any provider', () => {
    render(
      <LocationFourDaySheet
        spot={SPOT}
        windows={WINDOWS}
        scoreIndex={SCORES}
        slotIndex={SLOTS}
        scoresKnown
        todayStr={TODAY}
        location={LOCATION}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getAllByTestId('location-sheet-row')).toHaveLength(4);
    expect(screen.queryByTestId('warning-chip')).toBeNull();
  });
});
