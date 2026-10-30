/**
 * `MapView` — a Map tab with no solar forecast (overlay convergence C3,
 * `docs/engineering/overlay-convergence-plan.md` §3 C3).
 *
 * <p>The window control's night rows come from STORED results (D-14), so a REAL alert whose scoring
 * run has stored nothing yet had a live field and no row to select it by, and on a map with no
 * sunrise or sunset at all the control held nothing. C3 adds the night in progress as a LIVE aurora
 * row (its best is `bestOfNight` over the live `auroraScores`) and opens a solar-less map on it.
 *
 * <p>Each negative here has a control beside it that proves the row can appear at all. And the one
 * property that must not move is pinned last: with solar windows present, their rows and the map's
 * event are exactly what they were.
 */
process.env.TZ = 'Europe/London';

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({ eachLayer: () => {}, getContainer: () => ({ clientHeight: 500, clientWidth: 800 }) }),
}));

const { auroraStatusRef, roleRef, mobileRef } = vi.hoisted(() => ({
  auroraStatusRef: { current: null },
  roleRef: { current: 'ADMIN' },
  mobileRef: { current: false },
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: roleRef.current }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mobileRef.current }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: auroraStatusRef.current }) }));
const { stableViewline } = vi.hoisted(() => ({ stableViewline: { points: [], active: true } }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: stableViewline }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({
  default: ({ viewline }) => (viewline ? <div data-testid="aurora-viewline-overlay" /> : null),
}));
// `MapLabels` needs a real Leaflet pane to place a chip (its own test's job). What THIS file reads is
// the `spots` array `MapView` hands it: one spot per place drawn on the map.
const labelSpotsSeen = { last: null };
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: ({ spots }) => {
    labelSpotsSeen.last = spots;
    return <div data-testid="map-labels-stub" />;
  },
}));

import MapView from '../components/MapView.jsx';
import {
  getAuroraLocations, getAuroraForecastResults, getAuroraForecastAvailableDates,
} from '../api/auroraApi.js';

/** 22:00 UK on 13 August: the night that began that evening is the night in progress. */
const NOW = '2026-08-13T21:00:00Z';
const THE_NIGHT = '2026-08-13';

let nonce = 0;
let LOC = '';

const liveScore = (stars = 5) => ({
  location: { name: LOC, lat: 55.0, lon: -1.7 }, stars, summary: 'Live: clear to the north',
});

const noSolar = () => [
  { name: LOC, lat: 55.0, lon: -1.7, forecastsByDate: new Map(), locationType: ['LANDSCAPE'] },
];
const withSolar = () => [{
  name: LOC, lat: 55.0, lon: -1.7, locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[THE_NIGHT, {
    sunset: { rating: 4, solarEventTime: `${THE_NIGHT}T19:30:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    sunrise: { rating: 3, solarEventTime: `${THE_NIGHT}T05:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
  }]]),
}];

const hidesOnly = () => [{
  name: LOC, lat: 55.0, lon: -1.7, locationType: ['WILDLIFE'],
  forecastsByDate: new Map([[THE_NIGHT, {
    sunrise: null, sunset: null,
    hourly: [{ solarEventTime: `${THE_NIGHT}T09:00:00`, temperatureCelsius: 8 }],
  }]]),
}];

const REAL = { level: 'STRONG', kpIndex: 7, currentNightDate: THE_NIGHT, active: true };

async function mount(extra = {}) {
  let view;
  await act(async () => {
    view = render(
      <MapView locations={noSolar()} date={THE_NIGHT} autoEventType={null} {...extra} />,
    );
  });
  await act(async () => { await Promise.resolve(); });
  return view;
}

/** Lets every fetch the mount started answer (all mocked, all resolved), so an ABSENT control is a verdict and not a race. */
async function settled() {
  for (let i = 0; i < 6; i += 1) {
    await act(async () => { await Promise.resolve(); });
  }
}

async function openRows() {
  fireEvent.click(await screen.findByTestId('wf-win-pill'));
  return screen.findAllByTestId('wf-win-row');
}

beforeEach(() => {
  nonce += 1;
  LOC = `Bamburgh-${nonce}`;
  localStorage.clear();
  roleRef.current = 'ADMIN';
  labelSpotsSeen.last = null;
  mobileRef.current = false;
  getAuroraLocations.mockReset().mockResolvedValue([liveScore(5)]);
  getAuroraForecastResults.mockReset().mockResolvedValue([]);
  getAuroraForecastAvailableDates.mockReset().mockResolvedValue([]);
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
  auroraStatusRef.current = REAL;
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('a real live alert, no solar forecast and no stored run', () => {
  it('opens the map on the aurora night, in aurora mode, with the live stars on the pill row', async () => {
    await mount();
    const pill = await screen.findByTestId('wf-win-pill');
    await waitFor(() => expect(pill).toHaveTextContent('Aurora'));
    expect(within(pill).queryByTestId('wf-win-no-match')).not.toBeInTheDocument();
    const rows = await openRows();
    // The window control holds the night row alone.
    expect(rows).toHaveLength(1);
    expect(rows[0].getAttribute('data-ev-id')).toBe(`aur:${THE_NIGHT}:AURORA`);
    // bestOfNight over the live scores: the same best the field paints.
    expect(rows[0]).toHaveTextContent('5★');
  });

  it('draws the live place on the map (the field and the chips work on the live row)', async () => {
    // A roster join, so the field is offered and the label layer (the drawn places) is mounted.
    const spots = [{ name: LOC, lat: 55.0, lng: -1.7, rid: 'North East' }];
    await mount({
      heat: {
        enabled: true,
        hasHome: false,
        spots,
        areaSpots: spots,
        pointsByKey: new Map(),
        windows: [],
        areaBounds: [[54.3, -3.4], [55.7, -1.3]],
        catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
      },
    });
    await waitFor(() => expect(labelSpotsSeen.last?.map((s) => s.name)).toEqual([LOC]));
    expect(labelSpotsSeen.last[0].rating).toBe(5);
  });

  it('draws the viewline on the live night, as it does on any live aurora window', async () => {
    await mount();
    expect(await screen.findByTestId('aurora-viewline-overlay')).toBeInTheDocument();
  });

  it('on a phone the peek sheet\'s Windows section lists the live night alone', async () => {
    mobileRef.current = true;
    await mount();
    fireEvent.click(await screen.findByTestId('wf-map-peek-btn-win'));
    const rows = await screen.findAllByTestId('wf-map-peek-win-row');
    expect(rows).toHaveLength(1);
    expect(rows[0].getAttribute('data-ev-id')).toBe(`aur:${THE_NIGHT}:AURORA`);
  });

  it('gives the only control a name that says what it is: kind, night and best star', async () => {
    await mount();
    const pill = await screen.findByTestId('wf-win-pill');
    await waitFor(() => expect(pill).toHaveAccessibleName(/^Aurora, Tonight.*best 5 stars$/));
  });

  it('says why both steppers are dead: there is one window', async () => {
    await mount();
    const prev = await screen.findByTestId('wf-win-prev');
    const next = screen.getByTestId('wf-win-next');
    expect(prev).toBeDisabled();
    expect(next).toBeDisabled();
    expect(prev).toHaveAccessibleDescription('Only one window');
    expect(next).toHaveAccessibleDescription('Only one window');
  });

  it('does not enter aurora when the forecast named an event of its own and no solar row exists', async () => {
    // No solar rows, but `autoEventType` SUNSET: the pane stays in SUNSET (the pill has no match for it)
    // rather than being pulled into aurora - the live row is there to be chosen, not imposed.
    await mount({ autoEventType: 'SUNSET' });
    await screen.findByTestId('wf-win-pill');
    await settled();
    expect(screen.getByTestId('wf-win-no-match')).toBeInTheDocument();
    expect(screen.getByTestId('wf-win-pill')).not.toHaveTextContent('Aurora');
  });

  it('keeps a hides-only forecast out of aurora: the comfort sentence stands, a night row does not evict it', async () => {
    await mount({ locations: hidesOnly() });
    await screen.findByTestId('wf-win-pill');
    await settled();
    // Not entered: the pill has no match for the unchosen SUNSET.
    expect(screen.getByTestId('wf-win-no-match')).toBeInTheDocument();
    // `hidesOnlyForecast` tests "no SOLAR event", so the live night row does not turn it off.
    expect(screen.getByText(/Wildlife hides still carry a comfort forecast/)).toBeInTheDocument();
  });

  it('does not flash the live row for a night whose stored run is still on its way', async () => {
    let resolveDates;
    getAuroraForecastAvailableDates.mockReturnValue(new Promise((r) => { resolveDates = r; }));
    getAuroraForecastResults.mockResolvedValue([
      { locationName: LOC, stars: 3, nightStart: `${THE_NIGHT}T21:30:00` },
    ]);
    await mount();
    await settled();
    // The dates list has not answered: no row yet, so no live row for a night about to have a stored one.
    expect(screen.queryByTestId('wf-win-pill')).not.toBeInTheDocument();
    await act(async () => { resolveDates([THE_NIGHT]); });
    await settled();
    const rows = await openRows();
    expect(rows).toHaveLength(1);
    await waitFor(() => expect(rows[0]).toHaveTextContent('3★'));
  });

  it('on a phone the Windows section has a heading even with no solar rows to name one', async () => {
    mobileRef.current = true;
    await mount();
    fireEvent.click(await screen.findByTestId('wf-map-peek-btn-win'));
    const section = await screen.findByTestId('wf-map-peek-windows');
    expect(within(section).getByRole('heading')).toHaveTextContent('Tonight');
  });

});

describe('where the live row must NOT appear', () => {
  it('a quiet alert (inactive) adds no row — the control has nothing to hold', async () => {
    auroraStatusRef.current = { ...REAL, active: false, level: 'QUIET' };
    await mount();
    await settled();
    expect(screen.queryByTestId('wf-win-pill')).not.toBeInTheDocument();
  });

  it('a SIMULATED alert adds no row: the simulation is isolated from every user-facing surface', async () => {
    auroraStatusRef.current = { ...REAL, simulated: true };
    await mount();
    await settled();
    expect(screen.queryByTestId('wf-win-pill')).not.toBeInTheDocument();
  });

  it('a LITE account is unchanged: no aurora row, however the status looks', async () => {
    roleRef.current = 'LITE_USER';
    await mount();
    await settled();
    expect(screen.queryByTestId('wf-win-pill')).not.toBeInTheDocument();
  });

  it('a night with a STORED run keeps its stored row: the live row is only for a night with none', async () => {
    getAuroraForecastAvailableDates.mockResolvedValue([THE_NIGHT]);
    getAuroraForecastResults.mockResolvedValue([
      { locationName: LOC, stars: 3, nightStart: `${THE_NIGHT}T21:30:00` },
    ]);
    await mount();
    const rows = await openRows();
    const auroraRows = rows.filter((r) => r.getAttribute('data-ev-id') === `aur:${THE_NIGHT}:AURORA`);
    expect(auroraRows).toHaveLength(1);
    // A STORED run opens the aurora-only tab in aurora mode too (the selected-night fetch that gives the
    // row its best is made only in aurora mode), and the pill's name carries the time the row knows.
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('Aurora');
    await waitFor(() => expect(screen.getByTestId('wf-win-pill'))
      .toHaveAccessibleName(/^Aurora, Tonight, \d{2}:\d{2}, best 3 stars$/));
    await waitFor(() => expect(auroraRows[0]).toHaveTextContent('3★'));
    expect(auroraRows[0]).not.toHaveTextContent('5★');
  });
});

describe('with solar windows present, nothing about them moves', () => {
  it('keeps the map on its solar event and its solar rows, adding only the night row', async () => {
    await mount({ locations: withSolar(), autoEventType: 'SUNSET', forecastDates: [THE_NIGHT] });
    const pill = await screen.findByTestId('wf-win-pill');
    // The map stays on the forecast's own event: it is not pulled into aurora mode.
    expect(pill).not.toHaveTextContent('Aurora');
    const ids = (await openRows()).map((r) => r.getAttribute('data-ev-id'));
    expect(ids).toEqual(expect.arrayContaining([
      `solar:${THE_NIGHT}:SUNRISE`, `solar:${THE_NIGHT}:SUNSET`, `aur:${THE_NIGHT}:AURORA`,
    ]));
    // The solar rows keep their order and their ids, and the aurora row is APPENDED on its night date,
    // after that date's sunset - exactly where a stored night's row already goes.
    expect(ids).toEqual([
      `solar:${THE_NIGHT}:SUNRISE`, `solar:${THE_NIGHT}:SUNSET`, `aur:${THE_NIGHT}:AURORA`,
    ]);
  });

  it('does not pull a map that has solar rows into aurora mode even when the forecast named no event', async () => {
    // `autoEventType` null removes the other guard, so only "a solar row exists" keeps the pane where it was.
    await mount({ locations: withSolar(), autoEventType: null, forecastDates: [THE_NIGHT] });
    const pill = await screen.findByTestId('wf-win-pill');
    await settled();
    expect(pill).not.toHaveTextContent('Aurora');
  });

  it('with no live alert, the rows are exactly the solar rows (no night row appears)', async () => {
    auroraStatusRef.current = null;
    await mount({ locations: withSolar(), autoEventType: 'SUNSET', forecastDates: [THE_NIGHT] });
    const ids = (await openRows()).map((r) => r.getAttribute('data-ev-id'));
    expect(ids.every((id) => id.startsWith('solar:'))).toBe(true);
  });
});
