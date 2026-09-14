/**
 * `MapView` — the live aurora scores answer for the latest alert state, and for no superseded one.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>`auroraScores` comes from `getAuroraLocations()`, fetched by an effect keyed on the shared
 * aurora status. That status is a FRESH object whenever `AuroraStatusProvider` applies an answer —
 * every successful 5-minute poll and window focus, bar one it drops for being older than an answer
 * already applied — so the effect re-runs and re-requests on each. With no cancellation, every one
 * of those requests could land whenever it liked:
 *
 * <ul>
 *   <li><b>After the alert ended.</b> A poll saying the alert is over clears the scores; a request
 *       the previous poll made, landing after that, wrote the ended alert's stars straight back —
 *       into the rating's live fallback, the medallions, the overlay popup and the "🏆 best
 *       location" card — where they stood until the next poll or focus.</li>
 *   <li><b>Out of order.</b> Two refreshes close together each make a request, and the older one
 *       landing last replaced the newer answer with its own.</li>
 * </ul>
 *
 * <h2>What must NOT change</h2>
 *
 * <p>Unlike the per-night fetches beside it, this one deliberately does not clear before it
 * refetches, and a failed refetch keeps the last answer. It is the backend's live cache, and
 * nothing on the status marks a change in it that a clear could key on without blanking every
 * reader of the live scores for nothing (the effect's own comment gives the reasons). Two tests pin
 * those two decisions, so a tidy-up that "completes" the per-night shape here fails loudly.
 *
 * <h2>Why the real provider</h2>
 *
 * <p>The sibling files' ref-backed `useAuroraStatus` stub is read only when `MapView` happens to
 * re-render for some other reason — a prop change, a fetch landing — because the map is
 * `React.memo`'d and nothing tells it the ref moved. So a "re-poll" through the stub is whatever
 * render the harness happens to cause, never the route production uses. The real
 * `AuroraStatusProvider` publishes through context, which `memo` does not block, and a window
 * `focus` is the real refresh trigger — so these tests reach the effect exactly as production does.
 * The status itself comes through the API module's `getAuroraStatus`, mocked at the boundary the
 * frontend test standards prescribe; the rest are the standard `MapView` mocks the sibling files use.
 *
 * <p>Every negative here is a MARKER COUNT or the best card's text, never a `markerLabelAndColour`
 * spy: `makeMarkerIcon`'s cache is module-level and keyed on the location and its rating among other
 * things, so a stale rating that matches one already drawn reuses that icon and never reaches the spy.
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
// ⚠️ `useAuroraStatus` and `AuroraStatusContext` are deliberately NOT mocked — see the file header.
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraStatus: vi.fn(),
  getAuroraLocations: vi.fn(),
  getAuroraForecastResults: vi.fn(),
  getAuroraForecastAvailableDates: vi.fn(),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
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
import { AuroraStatusProvider } from '../context/AuroraStatusContext.jsx';
import {
  getAuroraStatus, getAuroraLocations, getAuroraForecastResults, getAuroraForecastAvailableDates,
} from '../api/auroraApi.js';

/** 01:00 UK on the 14th — so the night in progress, the one the live state describes, is the 13th. */
const SMALL_HOURS = '2026-08-14T00:00:00Z';
const THE_NIGHT = '2026-08-13';

/** A running alert, as `GET /api/aurora/status` reports one. */
const ALERT = { level: 'MODERATE', active: true, currentNightDate: THE_NIGHT };
/**
 * The same endpoint once the alert is over: the state machine drops to IDLE (`currentLevel` null,
 * which the controller serves as QUIET) and `active` goes false — the level and flag a real poll
 * delivers, not convenient ones. A real poll would also still carry the ended alert's `triggerType`
 * and `forecastKp`, which CLEAR resets neither of; nothing in this file reads them.
 */
const ALL_CLEAR = { level: 'QUIET', active: false, currentNightDate: THE_NIGHT };

const LOCATIONS = [
  { name: 'Kielder', lat: 55.23, lon: -2.58, forecastsByDate: new Map(), locationType: ['LANDSCAPE'] },
  { name: 'Cheviot', lat: 55.48, lon: -2.15, forecastsByDate: new Map(), locationType: ['LANDSCAPE'] },
  { name: 'Hexham', lat: 54.97, lon: -2.10, forecastsByDate: new Map(), locationType: ['LANDSCAPE'] },
];

const live = (name, stars, summary) => {
  const { lat, lon } = LOCATIONS.find((l) => l.name === name);
  return { location: { name, lat, lon }, stars, summary };
};
/** An earlier live answer: one place clears the default 3★ floor, and it is the best. */
const EARLIER = [live('Kielder', 4, 'Clear to the north')];
/**
 * A later one: both places clear the floor, and the best has moved. So the two answers differ in
 * the marker COUNT (1 against 2) and in the name on the best card — neither of which a cache hides.
 */
const LATER = [live('Kielder', 4, 'Clear to the north'), live('Cheviot', 5, 'Arc overhead')];

/** What `GET /api/aurora/status` answers with right now — each test moves it. */
let serverStatus;

/** A request this test settles by hand, so it — not the scheduler — decides which lands first. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Settles a hand-held request inside an AWAITED `act`, which is what makes a negative safe to assert.
 *
 * <p>⚠️ An awaited `act` does not return when its callback does: React flushes, then waits a
 * macrotask and flushes again until no update is left, so the component's `.then` — and a `.catch`
 * a link further down its chain — has run and committed before the test resumes. The `await` is
 * the load-bearing part, not the async callback — measured in this file with the `.then` guard
 * deleted: `await act(() => settle())` still fails the alert-ended test, while an un-awaited
 * `act(...)` with either kind of callback lets it PASS, its late write landing after `act` has
 * returned and never committed before the assertion reads the DOM. (The out-of-order test fails
 * under an un-awaited `act` either way, at its positive control, because the newer answer never
 * commits either — which is why that control settles through this same helper.)
 */
async function land(settle) {
  await act(async () => { settle(); });
}

const map = () => (
  <MapView locations={LOCATIONS} date={THE_NIGHT} autoEventType={null} handoffEventType="AURORA" />
);

/**
 * The provider first, and the map only once the status has landed. A map mounted in the same
 * commit renders with `status: null`, finds aurora mode unavailable, and bounces the aurora handoff
 * to SUNSET before the status arrives — so every assertion below would be about the wrong mode.
 */
async function openMap() {
  let result;
  await act(async () => { result = render(<AuroraStatusProvider>{null}</AuroraStatusProvider>); });
  await act(async () => { result.rerender(<AuroraStatusProvider>{map()}</AuroraStatusProvider>); });
  return result;
}

/** The page regains focus: the provider re-fetches the status and publishes a FRESH object. */
async function refocus() {
  await act(async () => { window.dispatchEvent(new Event('focus')); });
}

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(SMALL_HOURS));
  serverStatus = ALERT;
  // Re-asserted per test: an implementation outlives the test that installed it, and every
  // `getAuroraLocations` answer below is queued by the test that expects it. Reset leaves that mock
  // answering `undefined`, so an unexpected extra request fails loudly rather than landing silently.
  getAuroraStatus.mockReset();
  // A new object per call, as a parsed response body is — the property the whole race rests on.
  getAuroraStatus.mockImplementation(() => Promise.resolve({ ...serverStatus }));
  getAuroraLocations.mockReset();
  getAuroraForecastResults.mockReset();
  getAuroraForecastResults.mockResolvedValue([]);
  getAuroraForecastAvailableDates.mockReset();
  getAuroraForecastAvailableDates.mockResolvedValue([]);
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('the live aurora scores answer for the latest alert state', () => {
  it('drops a late response once the alert has ended — it wrote the ended alert\'s stars back', async () => {
    // Tonight also has a stored run, rating a third place. That keeps aurora mode on after the alert
    // ends — as a stored run does in production — and its one marker is the control that every
    // "nothing live is drawn" below is aurora mode drawing nothing live, not a tab that bounced to
    // Sunset and would draw nothing either way.
    getAuroraForecastAvailableDates.mockResolvedValue([THE_NIGHT]);
    getAuroraForecastResults.mockImplementation((night) => Promise.resolve(
      night === THE_NIGHT ? [{ locationName: 'Hexham', stars: 3 }] : [],
    ));
    const inFlight = deferred();
    getAuroraLocations
      .mockResolvedValueOnce(LATER)
      .mockReturnValueOnce(inFlight.promise);

    await openMap();
    // Control: tonight's live answer is drawn — both its places beside the stored one — and named.
    expect(await screen.findAllByTestId('marker')).toHaveLength(3);
    expect(screen.getByTestId('aurora-best-location-card')).toHaveTextContent('Cheviot');

    // The same alert, re-polled: this is the request that will still be out when the alert ends.
    await refocus();
    expect(getAuroraLocations).toHaveBeenCalledTimes(2);

    serverStatus = ALL_CLEAR;
    await refocus();
    // The alert is over: no new request, and the live answer is cleared — the stored place alone.
    expect(getAuroraLocations).toHaveBeenCalledTimes(2);
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
    expect(screen.queryByTestId('aurora-best-location-card')).not.toBeInTheDocument();

    // Now the ended alert's request lands.
    await land(() => inFlight.resolve(LATER));

    // Still the stored place alone. Broken, the ended alert's stars were written back after the
    // clear: both live places drawn again and the card naming Cheviot, until the next poll or focus.
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
    expect(screen.queryByTestId('aurora-best-location-card')).not.toBeInTheDocument();
  });

  it('keeps the newer answer when an older request lands after it', async () => {
    // ⚠️ The out-of-order case: two refreshes close together (a poll and a focus, or two focuses),
    // each with its own request out, and the OLDER one finishing last.
    const older = deferred();
    const newer = deferred();
    getAuroraLocations
      .mockReturnValueOnce(older.promise)
      .mockReturnValueOnce(newer.promise);

    await openMap();
    await refocus();
    // Both requests really are out at once — without that there is no race to lose.
    expect(getAuroraLocations).toHaveBeenCalledTimes(2);

    await land(() => newer.resolve(LATER));
    // Control: the newer answer is drawn — two places, Cheviot the best.
    expect(screen.queryAllByTestId('marker')).toHaveLength(2);
    expect(screen.getByTestId('aurora-best-location-card')).toHaveTextContent('Cheviot');

    await land(() => older.resolve(EARLIER));

    // Still the newer answer. Broken, the older one replaced it: one place, and the card naming
    // Kielder, until the next poll or focus.
    expect(screen.queryAllByTestId('marker')).toHaveLength(2);
    expect(screen.getByTestId('aurora-best-location-card')).toHaveTextContent('Cheviot');
  });

  it('keeps the current answer on screen while a re-poll of the same alert is out', async () => {
    // ⚠️ Pins the clear this effect deliberately does NOT make. The per-night fetches clear on every
    // change because the reader's selection has moved and the old value no longer answers for it;
    // here a re-run is the same alert re-polled, so a clear would blank every reader of the live
    // scores for a round trip every five minutes and on every focus.
    const refresh = deferred();
    getAuroraLocations
      .mockResolvedValueOnce(EARLIER)
      .mockReturnValueOnce(refresh.promise);

    await openMap();
    expect(await screen.findAllByTestId('marker')).toHaveLength(1);

    await refocus();
    // The refresh really is out...
    expect(getAuroraLocations).toHaveBeenCalledTimes(2);
    // ...and the alert's last answer is still drawn for the round trip. Cleared, both would be gone.
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
    expect(screen.getByTestId('aurora-best-location-card')).toHaveTextContent('Kielder');

    // And the refresh's own answer replaces it when it lands — the request is live, not dropped.
    await land(() => refresh.resolve(LATER));
    expect(screen.queryAllByTestId('marker')).toHaveLength(2);
    expect(screen.getByTestId('aurora-best-location-card')).toHaveTextContent('Cheviot');
  });

  it('keeps the last answer when a refresh fails — a network blip must not blank the alert', async () => {
    // ⚠️ Pins the other half of that decision: the `.catch` writes nothing. A failed refetch of a
    // running alert leaves its last answer standing, as the status provider and the viewline both
    // do for their own failed polls.
    const refresh = deferred();
    getAuroraLocations
      .mockResolvedValueOnce(EARLIER)
      .mockReturnValueOnce(refresh.promise);

    await openMap();
    expect(await screen.findAllByTestId('marker')).toHaveLength(1);

    await refocus();
    expect(getAuroraLocations).toHaveBeenCalledTimes(2);
    await land(() => refresh.reject(new Error('503 from /api/aurora/locations')));

    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
    expect(screen.getByTestId('aurora-best-location-card')).toHaveTextContent('Kielder');
  });
});
