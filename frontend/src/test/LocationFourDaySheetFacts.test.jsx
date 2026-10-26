/**
 * The location sheet's forecast-detail block (overlay convergence C2,
 * `docs/engineering/overlay-convergence-plan.md` §3 C2, owner decision D3): the conditions badges,
 * wind and comfort rows and generated-at footer the Plan-tab overlay's popup showed, now under each
 * expanded window row.
 *
 * What breaks if these fail: a window's open row shows less than the popup did, shows a PRO-only
 * fact to a LITE reader, fetches detail for rows nobody opened, or — the regression the block must
 * never cause — a location with no detail to show draws a different sheet than it did before.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
import LocationFourDaySheet from '../components/LocationFourDaySheet.jsx';
import { buildScoreIndex } from '../utils/locationSheet.js';
import { clearForecastDetailCache, getForecastDetail } from '../api/forecastApi.js';

vi.mock('../api/forecastApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getForecastDetail: vi.fn(),
}));

const TODAY = '2026-08-14';
const SPOT = { id: 7, name: 'Bamburgh', regionName: 'Northumberland' };
const WINDOWS = [
  { key: '2026-08-14:SUNSET', date: '2026-08-14', targetType: 'SUNSET', dow: 'Fri', sunrise: false, label: 'Tonight Sunset', time: '20:37', verdictLabel: 'Worth it', confidence: 'high', away: false },
  { key: '2026-08-15:SUNRISE', date: '2026-08-15', targetType: 'SUNRISE', dow: 'Sat', sunrise: true, label: 'Tomorrow Sunrise', time: '05:38', verdictLabel: 'Maybe', confidence: 'high', away: false },
  { key: '2026-08-16:SUNRISE', date: '2026-08-16', targetType: 'SUNRISE', dow: 'Sun', sunrise: true, label: 'Sun Sunrise', time: '05:40', verdictLabel: 'Not forecast', confidence: null, away: true },
];
const SCORES = buildScoreIndex([
  { locationId: 7, locationName: 'Bamburgh', date: '2026-08-14', targetType: 'SUNSET', rating: 3, summary: 'High cloud thins after eight.' },
  { locationId: 7, locationName: 'Bamburgh', date: '2026-08-15', targetType: 'SUNRISE', rating: 5, summary: 'A clear eastern horizon.' },
]);

const SUNSET_ROW = {
  id: 501,
  targetDate: '2026-08-14',
  targetType: 'SUNSET',
  solarEventTime: '2026-08-14T19:41:00',
  forecastRunAt: '2026-08-14T05:00:00',
  temperatureCelsius: 12.4,
  apparentTemperatureCelsius: 9.6,
  windSpeed: 5,
  windDirection: 270,
  precipitationProbabilityPercent: 30,
};
const SUNRISE_ROW = {
  id: 502,
  targetDate: '2026-08-15',
  targetType: 'SUNRISE',
  solarEventTime: '2026-08-15T04:38:00',
  forecastRunAt: '2026-08-14T05:00:00',
  temperatureCelsius: 9,
  windSpeed: 3,
  precipitationProbabilityPercent: 0,
};
const DETAIL_502 = {
  ...SUNRISE_ROW,
  lowCloud: 10,
  evaluationModel: 'HAIKU',
  aerosolOpticalDepth: 0.5,
  inversionPotential: 'STRONG',
  bluebellScore: 3,
  bluebellSummary: 'Carpets in the glen',
  nextHighTideTime: '2026-08-15T05:10:00',
  seaState: 'Slight',
  significantWaveHeightMetres: 0.6,
  surgeTotalMetres: 0.7,
  surgeRiskLevel: 'HIGH',
};

const locationWith = (...rows) => ({
  id: 7,
  name: 'Bamburgh',
  locationType: ['SEASCAPE'],
  forecastsByDate: new Map(rows.map((r) => [r.targetDate, {
    sunrise: r.targetType === 'SUNRISE' ? r : null,
    sunset: r.targetType === 'SUNSET' ? r : null,
    hourly: [],
  }])),
});

const setup = (props = {}) => render(
  <LocationFourDaySheet
    spot={SPOT}
    windows={WINDOWS}
    scoreIndex={SCORES}
    scoresKnown
    todayStr={TODAY}
    location={locationWith(SUNSET_ROW, SUNRISE_ROW)}
    isPro
    onClose={vi.fn()}
    onShowOnMap={vi.fn()}
    {...props}
  />,
);

const rowEl = (key) => screen.getAllByTestId('location-sheet-row').find((el) => el.dataset.window === key);

beforeEach(() => {
  clearForecastDetailCache();
  getForecastDetail.mockReset();
  getForecastDetail.mockImplementation(async (id) => (id === 502 ? DETAIL_502 : { ...SUNSET_ROW }));
});

describe('LocationFourDaySheet — the forecast detail block', () => {
  it('fetches detail for the row that opens on arrival, and for no other', async () => {
    setup();
    await waitFor(() => expect(getForecastDetail).toHaveBeenCalledWith(502));
    expect(getForecastDetail).toHaveBeenCalledTimes(1);
  });

  it('fetches a later row only when the reader opens it', async () => {
    setup();
    await screen.findByTestId('location-sheet-fact-surge');
    fireEvent.click(within(rowEl('2026-08-14:SUNSET')).getByTestId('location-sheet-row-toggle'));
    await waitFor(() => expect(getForecastDetail).toHaveBeenCalledWith(501));
  });

  it('shows a skeleton while the detail loads, the slim-row facts straight away, then the badges', async () => {
    let release;
    getForecastDetail.mockImplementation(() => new Promise((resolve) => { release = resolve; }));
    setup();
    const body = within(rowEl('2026-08-15:SUNRISE'));
    expect(await body.findByTestId('location-sheet-facts-skeleton')).toBeInTheDocument();
    // The comfort rows and the footer come from the slim row — no waiting on the fetch.
    expect(body.getByTestId('location-sheet-comfort')).toHaveTextContent('9°C · feels like 9°C');
    expect(body.queryByTestId('location-sheet-fact-surge')).not.toBeInTheDocument();
    release(DETAIL_502);
    expect(await body.findByTestId('location-sheet-fact-surge')).toBeInTheDocument();
    expect(body.queryByTestId('location-sheet-facts-skeleton')).not.toBeInTheDocument();
  });

  it('shows every PRO fact the popup showed, in its order', async () => {
    setup();
    const facts = await screen.findByTestId('location-sheet-facts');
    const list = within(facts).getByRole('list', { name: 'Conditions' });
    const texts = within(list).getAllByRole('listitem').map((li) => li.textContent.trim());
    expect(texts).toEqual([
      expect.stringContaining('Elevated dust (e.g. Saharan dust)'),
      expect.stringContaining('Strong cloud inversion'),
      expect.stringContaining('Bluebell: Carpets in the glen'),
      expect.stringMatching(/Rising tide — high at \d\d:\d\d \(32 min after sunrise\)/),
      expect.stringContaining('Seas 0.6 m · Slight'),
      expect.stringContaining('Surge 0.7 m above normal'),
    ]);
    expect(within(facts).getByTestId('location-sheet-comfort'))
      .toHaveTextContent('9°C · feels like 9°C. 6.7 mph. 0% rain chance.');
    expect(within(facts).getByTestId('location-sheet-generated'))
      .toHaveTextContent('Forecast generated: 14 Aug 2026 06:00 by Haiku 4.5');
  });

  it('gives a LITE reader the popup\'s LITE reading: plain aerosols, no inversion, a locked bluebell', async () => {
    setup({ isPro: false });
    const facts = await screen.findByTestId('location-sheet-facts');
    await within(facts).findByTestId('location-sheet-fact-surge');
    expect(within(facts).getByTestId('location-sheet-fact-dust')).toHaveTextContent('High aerosols');
    expect(within(facts).queryByTestId('location-sheet-fact-inversion')).not.toBeInTheDocument();
    const bluebell = within(facts).getByTestId('location-sheet-fact-bluebell');
    expect(bluebell).toHaveTextContent('Bluebell conditions — Upgrade to Pro');
    expect(bluebell).toHaveAttribute('data-locked', 'true');
    // Sea state and surge carry no gate in the popup and none here. The rising-tide badge relates the
    // tide to the light, so it is Pro and Admin (tide-light-pro-plan.md F1).
    expect(within(facts).getByTestId('location-sheet-fact-sea-state')).toBeInTheDocument();
    expect(within(facts).queryByTestId('location-sheet-fact-rising-tide')).not.toBeInTheDocument();
  });

  it('shows the rising-tide badge to a Pro or Admin reader (both reach the sheet as isPro)', async () => {
    setup({ isPro: true });
    const facts = await screen.findByTestId('location-sheet-facts');
    expect(await within(facts).findByTestId('location-sheet-fact-rising-tide')).toBeInTheDocument();
  });

  it('words a tide stand-down generically for a Lite reader and with the tide for Pro', async () => {
    // The sunrise row is rated (it opens on arrival); the sunset row is the unrated, triaged one.
    const tideTriaged = { ...SUNSET_ROW, triageReason: 'TIDE_MISALIGNED' };
    const rated = buildScoreIndex([
      { locationId: 7, locationName: 'Bamburgh', date: '2026-08-15', targetType: 'SUNRISE', rating: 5, summary: 'Clear.' },
    ]);
    const props = { location: locationWith(tideTriaged, SUNRISE_ROW), scoreIndex: rated };
    const footerOf = async () => {
      await screen.findByTestId('location-sheet-fact-surge');
      fireEvent.click(within(rowEl('2026-08-14:SUNSET')).getByTestId('location-sheet-row-toggle'));
      return within(rowEl('2026-08-14:SUNSET')).findByTestId('location-sheet-generated');
    };
    const lite = setup({ isPro: false, ...props });
    const liteFooter = await footerOf();
    expect(liteFooter).toHaveTextContent('regional conditions predicted poor');
    expect(liteFooter).not.toHaveTextContent(/tide/i);
    lite.unmount();
    setup({ isPro: true, ...props });
    expect(await footerOf()).toHaveTextContent('tide not aligned with location preference');
  });

  it('degrades to the slim-row facts when the detail fetch fails — no badges, no error', async () => {
    getForecastDetail.mockRejectedValue(new Error('500'));
    setup();
    const body = within(rowEl('2026-08-15:SUNRISE'));
    await waitFor(() => expect(body.queryByTestId('location-sheet-facts-skeleton')).not.toBeInTheDocument());
    expect(body.queryByRole('list', { name: 'Conditions' })).not.toBeInTheDocument();
    expect(body.getByTestId('location-sheet-comfort')).toBeInTheDocument();
    expect(body.getByTestId('location-sheet-generated')).toHaveTextContent('Forecast generated: 14 Aug 2026 06:00');
  });

  it('suppresses the "not evaluated by Claude" clause when the row carries a live rating', async () => {
    const triaged = { ...SUNRISE_ROW, triageReason: 'PRECIPITATION' };
    setup({ location: locationWith(triaged) });
    expect(await screen.findByTestId('location-sheet-generated')).not.toHaveTextContent('not evaluated');
  });

  it('draws nothing for a window with no forecast row', async () => {
    setup({ location: locationWith(SUNRISE_ROW) });
    await screen.findByTestId('location-sheet-facts');
    fireEvent.click(within(rowEl('2026-08-14:SUNSET')).getByTestId('location-sheet-row-toggle'));
    expect(within(rowEl('2026-08-14:SUNSET')).queryByTestId('location-sheet-facts')).not.toBeInTheDocument();
    expect(getForecastDetail).toHaveBeenCalledTimes(1);
  });

  it('draws nothing on an AWAY day even when the roster holds a forecast row for it', async () => {
    const AWAY_ROW = { ...SUNRISE_ROW, id: 503, targetDate: '2026-08-16' };
    const location = locationWith(SUNSET_ROW, SUNRISE_ROW, AWAY_ROW);
    // Control: the same row on a window that is NOT away draws its block.
    const view = setup({
      location,
      windows: WINDOWS.map((w) => (w.key === '2026-08-16:SUNRISE' ? { ...w, away: false } : w)),
    });
    fireEvent.click(within(rowEl('2026-08-16:SUNRISE')).getByTestId('location-sheet-row-toggle'));
    expect(await within(rowEl('2026-08-16:SUNRISE')).findByTestId('location-sheet-facts')).toBeInTheDocument();
    view.unmount();
    getForecastDetail.mockClear();
    setup({ location });
    fireEvent.click(within(rowEl('2026-08-16:SUNRISE')).getByTestId('location-sheet-row-toggle'));
    expect(within(rowEl('2026-08-16:SUNRISE')).queryByTestId('location-sheet-facts')).not.toBeInTheDocument();
    expect(getForecastDetail).not.toHaveBeenCalledWith(503);
  });

  it('draws no block, and makes no request, for a location with none of the new facts', () => {
    setup({ location: { id: 7, name: 'Bamburgh', locationType: ['SEASCAPE'], forecastsByDate: new Map() } });
    expect(screen.queryByTestId('location-sheet-facts')).not.toBeInTheDocument();
    setup({ location: null });
    expect(screen.queryByTestId('location-sheet-facts')).not.toBeInTheDocument();
    expect(getForecastDetail).not.toHaveBeenCalled();
  });

  it('draws nothing under a collapsed row', async () => {
    setup();
    await screen.findByTestId('location-sheet-facts');
    fireEvent.click(within(rowEl('2026-08-15:SUNRISE')).getByTestId('location-sheet-row-toggle'));
    expect(screen.queryByTestId('location-sheet-facts')).not.toBeInTheDocument();
  });
});
