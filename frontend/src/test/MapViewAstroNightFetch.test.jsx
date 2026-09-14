/**
 * `MapView` — astro scores answer for the night they were fetched for, and no other night.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The single-night astro fetch had the same race #814 fixed in the stored-aurora fetch beside it,
 * line for line. The scores answer for ONE night, and every astro reader takes them as the night on
 * screen's — the rating accessor and everything drawn from it, the astro heat points, both overlay
 * popups. One night's stars could stand in for another's two ways:
 *
 * <ul>
 *   <li><b>A stale window.</b> Switch night A → B and, until B's request resolved, A's scores were
 *       still on hand and were drawn as B's.</li>
 *   <li><b>A late response.</b> With no cancellation, A's request finishing after B's wrote A's
 *       stars in as B's, and they stayed there until the next selection.</li>
 * </ul>
 *
 * <p>Modelled on `MapViewAuroraLiveNight.test.jsx`'s race block. Every negative here is a MARKER
 * COUNT, never a `markerLabelAndColour` spy: `makeMarkerIcon`'s cache is module-level and keyed on
 * the location and its rating among other things, so a stale 4★ on a place that already showed a
 * real 4★ reuses that icon and the spy is never called — the aurora file's first stale-window test
 * passed with its fix deleted for exactly that reason.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';

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
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({ eachLayer: () => {}, getContainer: () => ({ clientHeight: 500, clientWidth: 800 }) }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn(),
  getAstroAvailableDates: vi.fn(),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div data-testid="popup-content" /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '★', colour: '#E5A00D' }),
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';
import { getAstroConditions, getAstroAvailableDates } from '../api/astroApi.js';

/** A frozen clock, so the two nights below sit where this file says rather than where CI runs. */
const NOW = '2026-08-14T12:00:00Z';
const NIGHT_A = '2026-08-16';
const NIGHT_B = '2026-08-17';

/**
 * ⚠️ Both Bortle-classed, and that is the precondition for every assertion here. Astro mode draws
 * only locations with a `bortleClass`, so without one nothing is drawn on EITHER night and each "no
 * stale marker" negative would pass for nothing. The positive control in each test is what proves it.
 */
const LOCATIONS = [
  { name: 'Kielder', lat: 55.23, lon: -2.58, forecastsByDate: new Map(), locationType: ['LANDSCAPE'], bortleClass: 2 },
  { name: 'Cheviot', lat: 55.48, lon: -2.15, forecastsByDate: new Map(), locationType: ['LANDSCAPE'], bortleClass: 3 },
];
/**
 * Night A rates BOTH places above the default 3★ floor; night B rates only one. So A's scores
 * standing in for B's show up as a marker count — two where B's own answer draws one — which no
 * icon cache can hide.
 */
const A_SCORES = [{ locationName: 'Kielder', stars: 4 }, { locationName: 'Cheviot', stars: 4 }];
const B_SCORES = [{ locationName: 'Kielder', stars: 4 }];

/** A request this test settles by hand, so it — not the scheduler — decides which lands first. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Settles a hand-held request inside an ASYNC `act` — which is what makes a negative safe to assert.
 *
 * <p>⚠️ An async `act` does not return when its callback does: React flushes, then waits a
 * macrotask and flushes again until no update is left, so the component's `.then` — and the `.catch`
 * a link further down its chain — has run and committed by the time this returns. A late response
 * that simply had not landed YET looks exactly like one that was dropped. Measured with the effect's
 * guards deleted: through this helper each guard's own test fails, but with a synchronous
 * `act(() => settle())` both late tests passed anyway — the late write lands after `act` has
 * returned and is never committed before the assertion reads the DOM.
 */
async function land(settle) {
  await act(async () => { settle(); });
}

const mapOn = (night) => (
  <MapView locations={LOCATIONS} date={night} autoEventType={null} handoffEventType="ASTRO" />
);

async function renderNight(night) {
  let result;
  await act(async () => { result = render(mapOn(night)); });
  return result;
}

async function moveTo(result, night) {
  await act(async () => { result.rerender(mapOn(night)); });
}

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
  // Re-asserted per test: `mockImplementation` outlives the test that installed it.
  getAstroConditions.mockReset();
  getAstroConditions.mockResolvedValue([]);
  // No available dates, so the window control's multi-night PREVIEW fetch never calls
  // `getAstroConditions` — every call below is the single-night effect under test.
  getAstroAvailableDates.mockResolvedValue([]);
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('astro scores answer for the night they were fetched for', () => {
  it('drops a LATE response for a night the reader has already left', async () => {
    // ⚠️ The out-of-order case. Night A's request is still in flight when the reader moves to B,
    // and A's finishes LAST. Without cancellation it wrote A's stars in as B's, and they stayed.
    const a = deferred();
    getAstroConditions.mockImplementation((night) => (
      night === NIGHT_A ? a.promise : Promise.resolve(B_SCORES)
    ));
    const result = await renderNight(NIGHT_A);
    expect(getAstroConditions).toHaveBeenCalledWith(NIGHT_A);

    await moveTo(result, NIGHT_B);
    // B's own answer is on screen: one place rated. Also the control that astro mode is live.
    expect(await screen.findAllByTestId('marker')).toHaveLength(1);

    // Now A's stale response lands — after B's.
    await land(() => a.resolve(A_SCORES));

    // Still B's one. Broken, A's answer replaced B's and drew both places.
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
  });

  it('does not let the previous night\'s scores stand in while the new night loads', async () => {
    // ⚠️ The stale-window case. A has resolved; the reader moves to B, whose request has NOT yet
    // resolved. Without the clear-on-change, A's scores were still on hand and answered for B.
    const b = deferred();
    getAstroConditions.mockImplementation((night) => (
      night === NIGHT_A ? Promise.resolve(A_SCORES) : b.promise
    ));
    const result = await renderNight(NIGHT_A);
    // Night A's own answer — both places. The control that proves anything is drawn at all.
    expect(await screen.findAllByTestId('marker')).toHaveLength(2);

    // Cleared BEFORE the move, so this sees only the move's own requests: exactly one, for B.
    getAstroConditions.mockClear();
    await moveTo(result, NIGHT_B);
    expect(getAstroConditions.mock.calls).toEqual([[NIGHT_B]]);

    // B is loading, so NOTHING is rated for it yet — and nothing may be drawn. Broken, A's two stand in.
    expect(screen.queryAllByTestId('marker')).toHaveLength(0);

    // ...and once B's own answer lands, that is what shows.
    await land(() => b.resolve(B_SCORES));
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
  });

  it('drops a LATE failure too — a night the reader has left cannot blank the one on screen', async () => {
    // ⚠️ The `.catch` half of the cancellation, which the late-response test cannot reach: A's
    // request FAILS after B's has answered. Unguarded, the failure handler cleared the scores — B's —
    // and the map showed nothing rated for a night that had a forecast, until the next selection.
    const a = deferred();
    getAstroConditions.mockImplementation((night) => (
      night === NIGHT_A ? a.promise : Promise.resolve(B_SCORES)
    ));
    const result = await renderNight(NIGHT_A);
    // A's request really is in flight — without it the rejection below would land on nothing, and
    // this test would pass having never reached a `.catch`.
    expect(getAstroConditions).toHaveBeenCalledWith(NIGHT_A);
    await moveTo(result, NIGHT_B);
    expect(await screen.findAllByTestId('marker')).toHaveLength(1);

    await land(() => a.reject(new Error('night A timed out')));

    // Still B's one. Broken, A's failure cleared B's scores and nothing was drawn.
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
  });
});
