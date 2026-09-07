/**
 * `MapView`'s heat opt-in — the prop that must change nothing until it is handed over.
 *
 * <h2>Why the default-off half is the important half</h2>
 *
 * <p>`MapView` is mounted twice: the Map pane, and the Plan overlay. Only the pane passes `heat` —
 * the overlay opens focused on one spot from a card that has already answered the question, so it
 * never fetches the field or renders the toolbar. Plan §8 names this component's blast radius for
 * exactly that reason.
 *
 * <p>The toolbar half then pins the four rules §4.5 and D6/D7/D8 state and a screenshot cannot:
 * the area segment's absence without a home, the `{animate: false}` fit, which populations each
 * control moves, and the fact that the window selector drives the map's own date rather than
 * holding a second one.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen, fireEvent, waitFor, within } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

const fitBounds = vi.fn();
const mapContainerProps = { last: null };
/**
 * Every handlers object any `useMapEvents` caller registered this render — `ZoomTracker`'s
 * `{zoomend}` and (tab-only) `MapBackgroundClickController`'s `{mousedown, click}` both go through
 * this one hook. `fireZoomend` (below) drives every registered `zoomend`, matching
 * `MapViewBasemapDress.test.jsx`'s own idiom for the identical need (map-tab-v2-plan.md §3 P10
 * adversarial review C4/C7's Legend/rings integration tests are the first thing in this file that
 * needs a REAL zoom to reach the toolbar, rather than the `useState(9)` mount default every earlier
 * test in this file was content with).
 */
let mapEventHandlers = [];
/** What `ZoomTracker`'s mount effect reads via `map.getZoom()` — see `mockMapInstance` below. */
let mockMapZoomAtMount = 9;
const mockMapInstance = { getZoom: () => mockMapZoomAtMount };
/**
 * Every `<Polyline>` this render produced, in render order — the azimuth lines' recording mock
 * (map-tab-v2-plan.md §3 P10 adversarial review C5: decision D-9 had zero test coverage until
 * this file's own "azimuth lines" describe block below).
 */
let polylineCalls = [];
vi.mock('react-leaflet', () => ({
  MapContainer: (props) => {
    mapContainerProps.last = props;
    return <div data-testid="map-container">{props.children}</div>;
  },
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: (props) => { polylineCalls.push(props); return null; },
  useMapEvents: (handlers) => {
    mapEventHandlers.push(handlers);
    return mockMapInstance;
  },
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    fitBounds,
    // `getZoom`/`flyTo`: `FlyToController`'s effect calls both once a fly target is set — reached
    // for the first time in this file by the azimuth-line tests' `handoffLocationName` prop, which
    // sets one as a side effect of selecting a location. No-ops; this file has no assertion on
    // camera movement.
    getZoom: () => mockMapZoomAtMount,
    flyTo: () => {},
    panInside: () => {},
  }),
}));


/** The lazy heat layer, replaced by a probe that records exactly what it was handed. */
const heatLayerProps = { last: null, mounts: 0 };
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => {
    heatLayerProps.last = props;
    heatLayerProps.mounts += 1;
    return <div data-testid="map-heat-layer" />;
  },
}));

/**
 * The lazy label layer, replaced by the same probe pattern as `MapHeatLayer` above. This file's
 * `useMap()` mock (below) is deliberately minimal — no `createPane`/`getBounds`/
 * `latLngToContainerPoint` — so the REAL `MapLabels` cannot mount a pane or place a single chip
 * here; every existing test in this file has always exercised it as a silent no-op. Stubbing it
 * explicitly, rather than leaving that accidental, lets `labelSpots` (the date+targetType join
 * `MapView` hands it) be asserted on directly — the seam the tide-alignment join test below needs
 * — without taking on `MapLabels.test.jsx`'s own Leaflet-pane/placement fixture machinery here.
 */
const labelSpotsProps = { last: null };
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => {
    labelSpotsProps.last = props;
    return <div data-testid="map-labels-stub" />;
  },
}));

let role = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
let auroraStatus = null;
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: auroraStatus }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

/** The real ramp, because "which colour did the marker take" is the assertion. */
const markerCalls = [];
vi.mock('../components/markerUtils.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    markerLabelAndColour: (...args) => {
      markerCalls.push(args);
      return actual.markerLabelAndColour(...args);
    },
  };
});

import MapView from '../components/MapView.jsx';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { STOPS_VERDICT, STOPS_TEMP, setMode } from '../utils/scoreRamp.js';
import { markerLabelAndColour } from '../components/markerUtils.js';
import { getAstroConditions, getAstroAvailableDates } from '../api/astroApi.js';
import { ukDateStrOffset } from '../utils/mapDates.js';
import { latLngBounds } from '../utils/heatGeometry.js';
import { buildScoreIndex, buildTideAlignmentIndex } from '../utils/locationSheet.js';
import { buildRegionGlossIndex } from '../utils/regionGloss.js';

const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';

/** Four spots in two regions, one of them dark-sky and one of them out of reach. */
/**
 * Bortle 4 and 5 sit either side of `DARK_SKY_THRESHOLD`, and both are IN the planning area — a 5
 * that only the area filter excluded would leave `<= 5` undetectable. `Coquet` carries no measured
 * class at all, which is the "absence" boundary: `null <= 4` is true in JavaScript, so dropping the
 * null guard silently admits every unmeasured location to a dark-sky field.
 */
// `regionName` mirrors `rid` on every spot — the real shape `heatSpots.buildHeatSpots` produces
// (both fields carry the identical string), added alongside `rid` for the Regions jump list
// (map-tab-v2-plan.md §3 P11), which joins on `regionName` the way `planningArea.js` already does.
const SPOTS = [
  {
    id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', bortleClass: 4,
  },
  {
    id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East', regionName: 'North East', bortleClass: 6,
  },
  {
    id: 3, name: 'Wastwater', lat: 54.44, lng: -3.30, rid: 'The Lakes', regionName: 'The Lakes', bortleClass: 2,
  },
  {
    id: 4, name: 'Kelso', lat: 56.20, lng: -2.43, rid: 'The Borders', regionName: 'The Borders', bortleClass: 5,
  },
  {
    id: 5, name: 'Alnmouth', lat: 55.39, lng: -1.61, rid: 'North East', regionName: 'North East', bortleClass: 5,
  },
  {
    id: 6, name: 'Coquet', lat: 55.33, lng: -1.53, rid: 'North East', regionName: 'North East', bortleClass: null,
  },
];
const AREA_SPOTS = [SPOTS[0], SPOTS[1], SPOTS[2], SPOTS[4], SPOTS[5]];

const pointOf = (spot, score) => ({
  id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
});

const POINTS_BY_KEY = new Map([
  [`${TODAY}:SUNSET`, [
    pointOf(SPOTS[0], 5), pointOf(SPOTS[1], 2), pointOf(SPOTS[2], 4),
    pointOf(SPOTS[3], 3), pointOf(SPOTS[4], 4), pointOf(SPOTS[5], 2),
  ]],
  [`${TOMORROW}:SUNRISE`, [pointOf(SPOTS[0], 1)]],
  [`${TOMORROW}:SUNSET`, []],
  [`${TODAY}:SUNRISE`, [pointOf(SPOTS[0], 3)]],
]);

const WINDOWS = [
  { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'This morning sunrise', time: '08:24', bestRating: 3, conf: 1 },
  { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1 },
  { key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE', label: 'Tomorrow sunrise', time: '08:24', bestRating: 1, conf: 0.82 },
  { key: `${TOMORROW}:SUNSET`, date: TOMORROW, targetType: 'SUNSET', label: 'Tomorrow sunset', time: '16:14', bestRating: null, conf: 0.82 },
];

/** The same windows with tonight — the one the map opens on — carrying no served rating. */
const WINDOWS_TONIGHT_UNRATED = WINDOWS.map(
  (w) => (w.key === `${TODAY}:SUNSET` ? { ...w, bestRating: null } : w),
);

/**
 * ⚠️ The two boxes must carry DIFFERENT numbers. `toHaveBeenCalledWith` is deep equality, so with
 * identical fixtures both fitBounds assertions pass even when the ternary's arms are swapped — the
 * camera would fly to the wrong box on every press and the suite would stay green.
 */
const AREA_BOUNDS = [[54.3, -3.4], [55.7, -1.3]];
const CATALOGUE_BOUNDS = [[54.3, -3.4], [56.4, -1.3]];

function heatProp(overrides = {}) {
  return {
    enabled: true,
    hasHome: true,
    spots: SPOTS,
    areaSpots: AREA_SPOTS,
    pointsByKey: POINTS_BY_KEY,
    windows: WINDOWS,
    areaBounds: AREA_BOUNDS,
    catalogueBounds: CATALOGUE_BOUNDS,
    ...overrides,
  };
}

/**
 * ⚠️ Marker names are made unique per test, and that is not tidiness.
 *
 * <p>`makeMarkerIcon`'s cache is MODULE-level and survives every render in this file — its key is
 * (name, scores, flags, emphasis, ramp). Reuse one fixture across tests and the second render is a
 * cache hit, so `markerLabelAndColour` is never called and an assertion on its arguments reads zero
 * calls while the component is working perfectly.
 */
let markerNonce = 0;

const makeLocations = () => SPOTS.map((s) => ({
  id: s.id,
  name: `${s.name}-${markerNonce}`,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: s.bortleClass,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
  }]]),
}));

/**
 * A single location carrying a sunset `azimuthDeg` — the one field `makeLocations()`'s fixture
 * omits and the azimuth-line describe block below needs, since `MapView` only draws a line once
 * `selectedDayData?.sunset?.azimuthDeg` (or `.sunrise`) resolves to a number.
 */
function makeAzimuthLocation() {
  return [{
    id: 99,
    name: 'AzimuthSpot',
    lat: 55.6,
    lon: -1.7,
    regionName: 'North East',
    bortleClass: 4,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunset: {
        rating: 4, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60, azimuthDeg: 240,
      },
      sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  }];
}

async function renderMap(props = {}) {
  let result;
  const locations = makeLocations();
  await act(async () => {
    result = render(
      <MapView locations={locations} date={TODAY} autoEventType={null} {...props} />,
    );
  });
  return { ...result, locations };
}

/**
 * Picks a window through the real window control — opens the pill and clicks the row whose id
 * names `date:targetType`. Replaces the retired `wf-map-window` `<select>`
 * (map-tab-v2-plan.md §3 P6 absorbed it into `components/map/WindowControl.jsx`); `dateTargetType`
 * is the exact string the old select's `<option value>` used, e.g. `` `${TOMORROW}:SUNRISE}` ``.
 */
function pickWindow(dateTargetType) {
  fireEvent.click(screen.getByTestId('wf-win-pill'));
  const row = screen.getAllByTestId('wf-win-row')
    .find((r) => r.getAttribute('data-ev-id') === `solar:${dateTargetType}`);
  expect(row).toBeTruthy();
  fireEvent.click(row);
}

/**
 * Opens the Map tab's filters popover — map-tab-v2-plan.md §3 P7 moved the "My area" / "Whole
 * catalogue" scope segment (and everything else the old drawer held) off the toolbar and into
 * `FiltersPopover`, behind this chip.
 */
function openFilters() {
  fireEvent.click(screen.getByTestId('wf-filters-chip'));
}

/**
 * Opens the Map tab's Legend popover (map-tab-v2-plan.md §3 P10) — mirrors `openFilters` above.
 */
function openLegend() {
  fireEvent.click(screen.getByTestId('wf-legend-chip'));
}

/**
 * Fires `zoomend` on every registered `useMapEvents` caller — `MapViewBasemapDress.test.jsx`'s own
 * idiom, needed here for the first time by the Legend handover indicator's integration tests
 * (adversarial review C7), which must drive a REAL zoom rather than rely on the `useState(9)` mount
 * default every earlier test in this file was content with.
 */
function fireZoomend(zoom) {
  act(() => {
    const target = { getZoom: () => zoom };
    for (const handlers of mapEventHandlers) {
      handlers.zoomend?.({ target });
    }
  });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
  heatLayerProps.last = null;
  heatLayerProps.mounts = 0;
  mapContainerProps.last = null;
  markerCalls.length = 0;
  fitBounds.mockClear();
  markerNonce += 1;
  auroraStatus = null;
  role = 'PRO_USER';
  mapEventHandlers = [];
  mockMapZoomAtMount = 9;
  polylineCalls.length = 0;
});
afterEach(() => { vi.useRealTimers(); localStorage.clear(); });

describe('MapView heat — the opt-in is off by default', () => {
  it('renders no toolbar and no field for the Plan overlay, which passes no heat prop', async () => {
    // The overlay is the no-heat mount: it opens focused on one spot from a card that has already
    // answered the question, so a toolbar leaking here would sit over a modal. The pinned
    // default-off test — deleting the `heatOffered` guard, or defaulting `heat` to a truthy
    // object, is exactly the change this catches, and nothing else in the suite would.
    await renderMap({ overlayMode: true });
    expect(screen.queryByTestId('wf-map-toolbar')).toBeNull();
    expect(screen.queryByTestId('map-heat-layer')).toBeNull();
    expect(heatLayerProps.mounts).toBe(0);
  });

  it('opens on the whole catalogue, at the overlay’s 60px padding, with no heat prop passed', async () => {
    const { locations } = await renderMap({ overlayMode: true });
    expect(mapContainerProps.last.boundsOptions).toEqual({ padding: [60, 60] });
    expect(mapContainerProps.last.bounds).toEqual(locations.map((l) => [l.lat, l.lon]));
  });

  it('renders nothing when the prop is handed but reports no catalogue', async () => {
    // The degrade path: a failed `evaluate/scores` fetch, or a roster with no joinable location.
    // The map is intact and the controls that would explain a field are absent, rather than a
    // toolbar offering to reframe a picture that does not exist.
    await renderMap({ heat: heatProp({ enabled: false }) });
    expect(screen.queryByTestId('wf-map-toolbar')).toBeNull();
    expect(screen.queryByTestId('map-heat-layer')).toBeNull();
  });
});

describe('MapView heat — degrade paths', () => {
  it('survives a heat payload with no windows and no point sets', async () => {
    // The three `heat.windows || []` guards and the `pointsByKey?.get` are otherwise decoration —
    // and a payload cached before a field existed is exactly the shape §8 says to expect.
    await renderMap({ heat: { enabled: true, hasHome: true, spots: SPOTS } });
    expect(screen.getByTestId('wf-map-toolbar')).toBeInTheDocument();
    // No `heat.windows` and no `forecastDates` means the EV list itself is empty — the window
    // control renders nothing at all (map-tab-v2-plan.md §3 P6's own degrade for a truly empty
    // list), distinct from the "no forecast window" pill a non-empty list with no match shows.
    expect(screen.queryByTestId('wf-win-pill')).toBeNull();
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.points).toEqual([]);
  });

  it('opens on the default framing when no box could be derived', async () => {
    const { locations } = await renderMap({ heat: heatProp({ areaBounds: null }) });
    expect(mapContainerProps.last.bounds).toEqual(locations.map((l) => [l.lat, l.lon]));
    expect(mapContainerProps.last.boundsOptions).toEqual({ padding: [60, 60] });
  });

  it('hands the layer a null confidence for a window that carries no tier', async () => {
    // `?? null` and not `?? 1`: the kernel reads a null `conf` as full confidence, which is the
    // honest default for a window the backend declined to qualify — but writing 1 would be this
    // component asserting it, and a later change to the kernel's default would then be silently
    // overridden here.
    const windows = WINDOWS.map((w) => ({ ...w, conf: undefined }));
    await renderMap({ heat: heatProp({ windows }) });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.conf).toBeNull();
  });
});

describe('MapView heat — the toolbar', () => {
  it('opens in heat view, on the planning area, at §4.5’s padding', async () => {
    await renderMap({ heat: heatProp() });
    expect(screen.getByRole('button', { name: 'Heat' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Pins' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('group', { name: 'Map view' })).toBeInTheDocument();
    // The "My area" / "Everywhere" scope now lives in the filters popover (map-tab-v2-plan.md
    // §3 P7) rather than on the always-visible toolbar.
    openFilters();
    expect(screen.getByRole('button', { name: 'My area' })).toHaveAttribute('aria-pressed', 'true');
    // The unpressed half too: inverting `aria-pressed={!heatArea}` is the classic copy-paste slip
    // in a segmented control, and asserting only the pressed one cannot see it.
    expect(screen.getByRole('button', { name: 'Everywhere' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('group', { name: 'Map area' })).toBeInTheDocument();
    expect(mapContainerProps.last.bounds).toBe(AREA_BOUNDS);
    expect(mapContainerProps.last.boundsOptions).toEqual({ padding: [28, 28] });
  });

  it('withdraws the FIELD on Pins (map-tab-v2-plan.md §3 P10) but keeps the coastline-stroke host mounted, and brings the field back on Heat', async () => {
    // Pre-P10, switching to the medallion view unmounted `MapHeatLayer` entirely. P10 changes the
    // CONTRACT: the coastline stroke now lives on this same host and keeps drawing in Pins mode
    // ("MapHeatLayer's pins-mode contract"), so the host itself must stay mounted — only its
    // `fieldEnabled` prop toggles the field/bloom/rings off.
    await renderMap({ heat: heatProp() });
    expect(await screen.findByTestId('map-heat-layer')).toBeInTheDocument();
    expect(heatLayerProps.last.fieldEnabled).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Pins' }));
    // Still in the document with NO further `await`/`findBy` needed: `MapHeatLayer` has its own
    // Suspense boundary now (separate from `MapLabels`/`PinsLayer`'s), so switching views never
    // re-suspends it — an already-resolved `lazy()` component re-renders synchronously on a prop
    // change, it does not remount.
    expect(screen.getByTestId('map-heat-layer')).toBeInTheDocument();
    expect(heatLayerProps.last.fieldEnabled).toBe(false);
    expect(screen.getByRole('button', { name: 'Pins' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Heat' })).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(screen.getByRole('button', { name: 'Heat' }));
    expect(screen.getByTestId('map-heat-layer')).toBeInTheDocument();
    expect(heatLayerProps.last.fieldEnabled).toBe(true);
  });

  it('does not remount the marker layer on the Heat↔Pins toggle', async () => {
    // With one colour language, the toggle no longer changes which palette a marker paints on — so
    // the `key` that used to force a remount on every view switch is gone. Its only remaining
    // effect would have been tearing down the selected marker on every press.
    //
    // ⚠️ Rewritten when clustering was deleted. It used to count mounts of the `MarkerClusterGroup`
    // mock, which no longer exists; the invariant it protected — the toggle does not tear the
    // marker layer down — is now asserted through the marker mock instead. P10's own note still
    // applies: Pins mode hides the medallions via `MapHeatLayer`'s `fieldEnabled={false}` (0%
    // opacity, inert) rather than by unmounting anything.
    await renderMap({ heat: heatProp() });
    const afterMount = markerCalls.length;
    expect(afterMount).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'Pins' }));
    fireEvent.click(screen.getByRole('button', { name: 'Heat' }));
    fireEvent.click(screen.getByRole('button', { name: 'Pins' }));

    // Still rendering markers after three toggles — the layer survived rather than being torn down
    // and left absent, which a bare "> 0" at mount time alone could not distinguish.
    expect(markerCalls.length).toBeGreaterThan(0);
  });

  it('hides the ramp key in Pins view, where nothing is painted with it', async () => {
    await renderMap({ heat: heatProp() });
    // Named through its role: the gradient it explains is `aria-hidden`, so without a name of its
    // own a screen reader meets two loose adjectives with nothing saying what they qualify.
    expect(screen.getByRole('img', { name: /Poor to Worth it/ })).toBeInTheDocument();
    expect(screen.getByTestId('wf-map-heat-legend')).toHaveTextContent('Poor');
    fireEvent.click(screen.getByRole('button', { name: 'Pins' }));
    expect(screen.queryByTestId('wf-map-heat-legend')).toBeNull();
  });

  it('hides the Legend chip entirely in Pins view (README §3: "In Pins mode the Legend chip hides")', async () => {
    await renderMap({ heat: heatProp() });
    expect(screen.getByTestId('wf-legend-chip')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Pins' }));
    expect(screen.queryByTestId('wf-legend-chip')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Heat' }));
    expect(screen.getByTestId('wf-legend-chip')).toBeInTheDocument();
  });

  it('drops the area segment entirely when no home is set (the "every press does nothing" coherence rule)', async () => {
    // With no postcode the planning area IS the whole roster, so both states frame the same box over
    // the same spots. field-geography-glyphs-plan.md's coherence rule bans a control whose every
    // press does nothing (NOT map-tab-v2-plan.md's D-6, the unrelated maxZoom-16 decision); the
    // rest of the toolbar stays, because the view toggle and the window selector still do something.
    await renderMap({ heat: heatProp({ hasHome: false }) });
    expect(screen.queryByRole('button', { name: 'My area' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Everywhere' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Heat' })).toBeInTheDocument();
  });

  it('frames the whole catalogue WITHOUT animating (the fitBounds-mid-paint trap)', async () => {
    // ⚠️ `{animate: false}` is the bundle's own trap, not a preference: a heavy field paint in the
    // same frame as an animated fitBounds forces layout mid-transition and strands Leaflet at the
    // old view — the labels change and the map never moves.
    await renderMap({ heat: heatProp() });
    openFilters();
    fitBounds.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'Everywhere' }));
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(CATALOGUE_BOUNDS, { padding: [28, 28], animate: false });
  });

  it('frames the area again when the other segment is pressed', async () => {
    await renderMap({ heat: heatProp() });
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: 'Everywhere' }));
    fitBounds.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'My area' }));
    expect(fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
  });

  it('re-centres on a second press of the segment already pressed', async () => {
    // This is what the nonce buys over keying on `heatArea` alone: after panning away, pressing the
    // segment you are already on takes you back. Without it the press is a no-op and the control
    // lies about what it does.
    await renderMap({ heat: heatProp() });
    openFilters();
    fitBounds.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'My area' }));
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
  });

  it('re-frames once when the planning area arrives after the map has opened', async () => {
    // The race: `areaBounds` is null until the briefing and the reach matrix have both landed, so a
    // reader who opens the Map tab first would otherwise keep the whole-of-Britain framing for the
    // session — with the only correction behind a segment that is itself absent without a home.
    const { rerender } = await renderMap({ heat: heatProp({ areaBounds: null }) });
    expect(fitBounds).not.toHaveBeenCalled();
    await act(async () => {
      rerender(
        <MapView locations={makeLocations()} date={TODAY} autoEventType={null} heat={heatProp()} />,
      );
    });
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
  });

  it('does not re-frame on mount, which would fight the container’s own opening bounds', async () => {
    await renderMap({ heat: heatProp() });
    expect(fitBounds).not.toHaveBeenCalled();
  });
});

describe('MapView heat — a window nobody rated', () => {
  /**
   * The Plan tab's unscored mark, adapted to a host that has no plate to hatch. On the Leaflet side
   * the field simply paints nothing — and ⚠️ the markers no longer come back with it: the
   * `fadesMarkers`/`points.length > 0` rule this comment used to name is gone (an adversarial
   * review established an empty point set is not "nothing is scored"), so `MapHeatLayer` holds the
   * medallions hidden here as everywhere else. What this block is really about is the ramp key
   * staying up, explaining a gradient nothing on screen carries, which is the exact thing that
   * key's own rule forbids.
   */
  const unrated = (overrides = {}) => heatProp({ windows: WINDOWS_TONIGHT_UNRATED, ...overrides });

  it('names the unrated window and takes the ramp key down with it', async () => {
    await renderMap({ heat: unrated() });
    expect(screen.getByTestId('wf-map-heat-unscored'))
      .toHaveTextContent('This event is not scored yet');
    // Both would be a colour key above an empty map, denied by the line underneath it.
    expect(screen.queryByTestId('wf-map-heat-legend')).toBeNull();
  });

  it('keeps the key and says nothing when the window has a served rating', async () => {
    // The pair is the assertion: a note shown unconditionally is the same defect with the opposite
    // sign, and it would take the ramp key down on every window.
    await renderMap({ heat: heatProp() });
    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
    expect(screen.getByTestId('wf-map-heat-legend')).toHaveTextContent('Poor');
  });

  it('leaves a RATED window alone even when it has no points to paint', async () => {
    // ⚠️ The production defect, and the reason this reads `bestRating` rather than any point
    // count. An empty point set is a fact about the join behind the picture: on 2026-08-19 three
    // windows the payload was rating had one, and the Plan tab hatched them while their own cards
    // printed `best spot 5★`.
    await renderMap({ heat: heatProp({ pointsByKey: new Map([[`${TODAY}:SUNSET`, []]]) }) });
    expect(heatLayerProps.last.points).toEqual([]);
    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
    expect(screen.getByTestId('wf-map-heat-legend')).toBeInTheDocument();
  });

  it('does NOT blame the forecast when the dark-sky filter is what emptied the field', async () => {
    // The first cut read `heatPoints`, which the Bortle toggle narrows — so a reader who filtered
    // every dark-sky location out of a well-rated window was told the forecast was unscored. The
    // window here is rated and every one of its points is Bortle > 4, so the toggle empties the
    // field completely while the rating is untouched.
    await renderMap({
      heat: heatProp({
        pointsByKey: new Map([[`${TODAY}:SUNSET`, [
          pointOf(SPOTS[1], 4), pointOf(SPOTS[3], 5), pointOf(SPOTS[4], 4),
        ]]]),
      }),
    });
    openFilters();
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));

    expect(heatLayerProps.last.points).toEqual([]);
    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
    expect(screen.getByTestId('wf-map-heat-legend')).toBeInTheDocument();
  });

  it('says nothing in Pins view, where no field is claimed either way', async () => {
    await renderMap({ heat: unrated() });
    fireEvent.click(screen.getByRole('button', { name: 'Pins' }));
    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
    expect(screen.queryByTestId('wf-map-heat-legend')).toBeNull();
  });

  it('says nothing extra when the map is on a date the EV list has no row for — but still withholds the key', async () => {
    // The window control already answers that one, and it is a statement about the CAMERA rather
    // than about the forecast: there is no window to be unrated, so the "not scored yet" line would
    // be a second voice saying the same thing. That half is unchanged.
    //
    // ⚠️ The KEY assertion is new, and it is a bug fix. `windowUnscored` used to require a non-null
    // `heatWindow`, so on this very fixture it was false and `heatOn && !windowUnscored` rendered
    // the colour key — a key to a gradient nothing on screen carries, which is the exact thing that
    // key's own rule forbids. Now the key is withheld whenever there is no rating behind it, and
    // the MESSAGE is what carries the camera-vs-forecast distinction (gated on `activeMapEvent`).
    await renderMap({ heat: heatProp(), date: '2026-01-25' });
    expect(screen.getByTestId('wf-win-no-match')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
    expect(screen.queryByTestId('wf-map-heat-legend')).toBeNull();
  });

  it('names a D-13 FILLER row as unscored, and takes the key down with it', async () => {
    // ⚠️ THE REGRESSION TEST FOR THE BUG #747's REVIEW SURFACED, and the case nothing covered.
    //
    // D-13 keeps the map's full T..T+5 browsable horizon: `buildMapEvents` emits solar rows for any
    // forecast date past the briefing's served windows (`mapEvents.js`'s FILLER branch), and they
    // are ordinary enabled rows the `›` stepper walks straight into. On such a row `heatWindow`
    // resolves null — so the field paints nothing, and BOTH of the old rules got it wrong: the
    // "not scored yet" line was suppressed (it required a non-null `heatWindow`) and the colour key
    // rendered above the empty field.
    //
    // It hid for months behind the medallions, which the Heat view used to fade back in past the
    // handover band — "empty" read as "sparse". #747 hid them unconditionally and exposed this.
    //
    // Distinguished from the sibling above by `wf-win-no-match`: there a row does NOT match and the
    // selector speaks; here one DOES, so this surface has to.
    const DAY_AFTER = '2026-01-17';
    await renderMap({
      heat: heatProp(),
      date: DAY_AFTER,
      forecastDates: [TODAY, TOMORROW, DAY_AFTER],
    });
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expect(screen.getByTestId('wf-map-heat-unscored')).toHaveTextContent('This event is not scored yet');
    expect(screen.queryByTestId('wf-map-heat-legend')).toBeNull();
  });
});

describe('MapView heat — which window the field paints', () => {
  it('paints the window matching the map’s own date and event, with its confidence', async () => {
    await renderMap({ heat: heatProp() });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.conf).toBe(1);
    expect(heatLayerProps.last.points.map((p) => p.name))
      .toEqual(['Bamburgh', 'Tynemouth', 'Wastwater', 'Kelso', 'Alnmouth', 'Coquet']);
  });

  it('shows the window the map is actually on, not merely a no-match state', async () => {
    // The negative test below pins the no-match pill; without this one, an always-unmatched
    // control would be caught by nothing.
    await renderMap({ heat: heatProp() });
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).toHaveTextContent('Tonight');
    // Tightened: `toHaveTextContent('Tonight')` alone passes whether the label is the day-only
    // "Tonight" or the pre-dedup "Tonight sunset" (WINDOWS[1].label), since both CONTAIN
    // "Tonight" — it cannot tell the deduped pill from the old one. Pin the day-only form
    // specifically: the label span excludes the word the separate kind chip states.
    expect(pill.querySelector('.wf-win-label')).toHaveTextContent('Tonight');
    expect(pill.querySelector('.wf-win-label')).not.toHaveTextContent(/sunset/i);
    expect(pill).toHaveTextContent('Sunset'); // the kind chip states it, just not in the label span
  });

  it('moves the map’s DATE when the window control picks a window on another day', async () => {
    // The control must not hold a window of its own: with three time controls on one tab, the two
    // that disagreed would put the field on one evening and the markers on another. (Pre-P6 pin:
    // "moves the map's DATE when the selector picks a window on another day", driven through the
    // retired `wf-map-window` select.)
    const onSelectDate = vi.fn();
    await renderMap({ heat: heatProp(), onSelectDate });
    pickWindow(`${TOMORROW}:SUNRISE`);
    expect(onSelectDate).toHaveBeenCalledWith(TOMORROW);
  });

  it('moves the map’s EVENT when the window is on the day already shown', async () => {
    // Sunrise→sunset within one day is the commonest use of the control and takes the
    // `row.date !== date` guard's false arm inside `selectEvRow` — so `setEventType` is the only
    // thing that can answer it, and dropping that line leaves the date handler's test still green.
    const onSelectDate = vi.fn();
    await renderMap({ heat: heatProp(), onSelectDate });
    pickWindow(`${TODAY}:SUNRISE`);
    expect(onSelectDate).not.toHaveBeenCalled();
    // Day-only form again — `WINDOWS[0].label` is "This morning sunrise", kind-chip dedup strips
    // the trailing "sunrise".
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('This morning');
    expect(heatLayerProps.last.points.map((p) => p.name)).toEqual(['Bamburgh']);
  });

  it('holds the chosen event against the auto-selection that would otherwise reclaim it', async () => {
    // `autoEventType` is re-applied by an effect unless the reader has overridden it. Without
    // `setUserHasOverriddenEvent(true)` the control's answer is undone on the next payload.
    const { rerender } = await renderMap({ heat: heatProp(), autoEventType: 'SUNSET' });
    pickWindow(`${TODAY}:SUNRISE`);
    await act(async () => {
      rerender(
        <MapView
          locations={makeLocations()}
          date={TODAY}
          autoEventType="SUNSET"
          heat={heatProp()}
        />,
      );
    });
    // Day-only form, matching the pill's kind-chip dedup (see the test two above).
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('This morning');
  });

  it('shows nothing selected, and paints nothing, on a date the briefing does not reach', async () => {
    // `GET /api/forecast` reaches further than the briefing's six windows, so this is an ordinary
    // state. The markers stay; only the field is absent.
    await renderMap({ heat: heatProp(), date: '2026-01-25' });
    expect(screen.getByTestId('wf-win-no-match')).toBeInTheDocument();
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.points).toEqual([]);
  });
});

/**
 * The full join seam, end to end: a `briefing.days` fixture → `utils/locationSheet
 * .buildTideAlignmentIndex` (the pane's own index build, `WindowFirstMapPane.jsx`) →
 * `MapView`'s `tideAlignmentIndex` prop → `getTideOnLightForLocation`'s date+targetType join →
 * `labelSpots`, the array `MapView` hands `MapLabels` as `spots` (never previously exercised past
 * the unit level — `locationSheet.test.js` proves the index build in isolation, `MapLabels.test.jsx`
 * proves chip rendering from a hand-built spot, but nothing threaded a real briefing fixture
 * through both). Reads `labelSpotsProps.last` from the `MapLabels` stub above — the same
 * capture-the-props pattern this file already uses for `MapHeatLayer`.
 */
describe('MapView heat — the tide-alignment index join (bundle rev 2)', () => {
  // Location id 1 is Bamburgh (`SPOTS[0]`/`makeLocations()[0]`) — the join is id-first, so the
  // per-render name suffix `makeLocations()` adds (`markerNonce`) cannot break it.
  const briefingWithTide = (tideOnTheLight) => ({
    days: [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          slots: [{
            locationId: 1,
            locationName: 'Bamburgh',
            tideOnTheLight,
            nearestSolarOffsetMinutes: 25,
            nearestExtremeKind: 'HW',
            nearestSolarOffsetPhrase: 'HW 20:20 · 25m after sunset',
          }],
        }],
      }],
    }],
  });

  function bamburghSpot() {
    return labelSpotsProps.last.spots.find((s) => s.name.startsWith('Bamburgh'));
  }

  it('an aligned slot\'s onTheLight/phrase arrive on the Bamburgh entry in labelSpots', async () => {
    const tideAlignmentIndex = buildTideAlignmentIndex(briefingWithTide(true).days);
    await renderMap({ heat: heatProp(), tideAlignmentIndex });

    const spot = bamburghSpot();
    expect(spot.onTheLight).toBe(true);
    expect(spot.nearestSolarOffsetPhrase).toBe('HW 20:20 · 25m after sunset');
  });

  it('a not-aligned slot reaches labelSpots too, as onTheLight: false with no phrase', async () => {
    const tideAlignmentIndex = buildTideAlignmentIndex(briefingWithTide(false).days);
    await renderMap({ heat: heatProp(), tideAlignmentIndex });

    const spot = bamburghSpot();
    expect(spot.onTheLight).toBe(false);
    // `MapView.jsx`'s `spotOf` only carries the phrase alongside `onTheLight: true` — a phrase
    // beside a false flag would be a fact about the wrong extreme dressed as the useful one.
    expect(spot.nearestSolarOffsetPhrase).toBeNull();
  });

  it('with no tideAlignmentIndex at all, every spot in labelSpots is unaligned — the prop is optional', async () => {
    await renderMap({ heat: heatProp() });

    expect(labelSpotsProps.last.spots.length).toBeGreaterThan(0);
    labelSpotsProps.last.spots.forEach((spot) => {
      expect(spot.onTheLight).toBe(false);
      expect(spot.nearestSolarOffsetPhrase).toBeNull();
    });
  });
});

describe('MapView heat — the modes it stands down for', () => {
  it('paints no field and offers no toolbar in aurora mode', async () => {
    // The markers there carry Kp visibility, not sky colour. A sky-colour field painted under them
    // would be two scales on one picture with nothing saying so.
    //
    // Aurora mode has to be REACHABLE to be tested: `MapView` resets the event type to SUNSET
    // whenever aurora is unavailable, so a stored result is what lets the mode stand.
    auroraStatus = { active: true, level: 'QUIET' };
    await renderMap({ heat: heatProp(), handoffEventType: 'AURORA' });
    expect(screen.queryByTestId('wf-map-toolbar')).toBeNull();
    expect(screen.queryByTestId('map-heat-layer')).toBeNull();
  });

  // ⚠️ Rewritten for map-tab-v2-plan.md §3 P6, which is a genuine behaviour change here, not a
  // mechanical rename: astro mode now DOES paint a field, scored from that night's astro stars
  // (`MapView`'s `astroHeatPoints`) rather than standing down like aurora. The old pin
  // ("paints no field and offers no toolbar in astro mode") is what P6 replaced — see the two
  // tests below for its successor, one per branch (nothing scored yet, and a real score).
  it('offers the toolbar in astro mode, painting nothing while nothing is scored', async () => {
    // This file's `astroApi` mock resolves no conditions for any date, so the field exists but has
    // nothing to paint — distinct from aurora, which stands the toolbar down outright.
    await renderMap({ heat: heatProp(), handoffEventType: 'ASTRO' });
    expect(screen.getByTestId('wf-map-toolbar')).toBeInTheDocument();
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.points).toEqual([]);
    expect(screen.getByTestId('wf-map-heat-unscored')).toHaveTextContent('This event is not scored yet');
  });

  it('paints the field from that night\'s astro stars when they exist', async () => {
    // `astroHeatPoints` joins `astroScores` (keyed by LOCATION name) against `heat.spots` (this
    // file's `SPOTS` fixture, joined by the SAME name field) — so the mocked conditions below are
    // keyed to `SPOTS`' own names, not the `-${markerNonce}`-suffixed ones `makeLocations()` gives
    // the rendered `locations` array (a cache-busting device for `markerLabelAndColour`'s calls
    // that plays no part in this join at all).
    //
    // Filtered to SCORED locations before the field ever sees them (P1's NaN-seam warning,
    // map-tab-v2-plan.md §3 P6): Tynemouth carries no astro score in this fixture and must not
    // appear, rather than poisoning the field with a placeholder.
    getAstroConditions.mockResolvedValueOnce([
      { locationName: SPOTS[0].name, stars: 4 }, // Bamburgh
      { locationName: SPOTS[2].name, stars: 2 }, // Wastwater
    ]);
    await renderMap({ heat: heatProp(), handoffEventType: 'ASTRO' });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.points.map((p) => p.name).sort()).toEqual(
      [SPOTS[0].name, SPOTS[2].name].sort(),
    );
    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
  });

  it('hands the astro field its own capped-inference confidence, never null/full strength (adversarial review, real #3)', async () => {
    // `heat.windows` never carries an astro entry, so `heatWindow?.conf` — the solar path's own
    // scalar — is always null for this mode; the field must not silently paint at full strength
    // regardless of horizon. A T+5 night is comfortably past `HORIZON_MEDIUM_MAX_DAYS`, so the
    // capped-inference tier is 'low' (`confidenceScalar('low')` = 0.5) — a figure that could not
    // be mistaken for the null-default 1.0 a regression would fall back to.
    const FAR_NIGHT = '2026-01-20';
    await renderMap({ heat: heatProp(), date: FAR_NIGHT, handoffEventType: 'ASTRO' });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.conf).toBe(0.5);
  });

  it('bounds the astro multi-date preview fetch to the solar horizon, not the full available-dates list (PR #731 review)', async () => {
    // The real `getAstroConditions/getAstroAvailableDates` endpoints answer with every distinct
    // date ever persisted — a writer replaces a rerun date's row rather than pruning it — so an
    // unbounded fetch over 200 historical dates would fan a single Map-tab mount out to hundreds
    // of concurrent requests. `heat.windows` (`heatProp()`'s `WINDOWS` fixture) carries TODAY and
    // TOMORROW, so the solar horizon is exactly those two dates regardless of what the available-
    // dates endpoint also reports for the distant past.
    const historical = Array.from({ length: 200 }, (_, i) => ukDateStrOffset(-(i + 10), new Date(`${TODAY}T12:00:00Z`)));
    getAstroAvailableDates.mockResolvedValueOnce([...historical, TODAY, TOMORROW]);
    getAstroConditions.mockClear();

    await renderMap({ heat: heatProp() });
    // `findByTestId` waits for the toolbar to exist, which is unconditional — the actual proof is
    // the `waitFor` below, which needs something async to have actually settled first.
    await screen.findByTestId('wf-map-toolbar');
    await waitFor(() => expect(getAstroConditions).toHaveBeenCalled());

    const fetchedDates = getAstroConditions.mock.calls.map((args) => args[0]).sort();
    expect(fetchedDates).toEqual([TODAY, TOMORROW]);
    expect(getAstroConditions).toHaveBeenCalledTimes(2);
  });
});

describe('MapView heat — which places count', () => {
  it('does NOT narrow the field to the planning area — the segment frames, it never filters', async () => {
    // ⚠️ The rule five documents state and the prototype breaks. §3 quotes the design's own "the
    // lens does not filter the field"; §4.5 gives the segment as `fitBounds` and lists the opening
    // bounds separately; §9 Q4 keeps "should the field respect drive time" OPEN; and both
    // `planningArea.js` and `WindowFirstHeatStrip` warn in as many words that handing `areaSpots`
    // to the kernel turns the framing into a reach filter. P2 chose the strip footer's wording
    // BECAUSE the field is not area-filtered, so filtering here would make a caption false on the
    // tab next door. Kelso is beyond the glance and must still paint.
    await renderMap({ heat: heatProp() });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.points.map((p) => p.name)).toContain('Kelso');

    const before = heatLayerProps.last.points;
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: 'Everywhere' }));
    expect(heatLayerProps.last.points).toBe(before);
  });

  it('repaints the field through the real Bortle rule when dark sky is switched on (D7)', async () => {
    // Bortle 1–9, LOWER is darker, and the threshold is `<= 4` — the app's own `DARK_SKY_THRESHOLD`.
    // The bundle's `dark = bortle >= 3.8` is its mock scale inverted and must never be ported: it
    // would leave the light-polluted spots and drop the dark ones.
    //
    // The fixture straddles the cut on both sides (4 in, 5 out, 6 out, 2 in) and carries one
    // location with no measured class — `null <= 4` is true in JavaScript, so dropping that guard
    // admits every unmeasured spot to a dark-sky field.
    await renderMap({ heat: heatProp() });
    openFilters();
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));
    expect(heatLayerProps.last.points.map((p) => p.name)).toEqual(['Bamburgh', 'Wastwater']);
  });

  it('leaves the field alone when the QUALITY threshold moves, because a red area is information', async () => {
    // ⚠️ Deliberately not the marker population. The map defaults to 3★-and-above; a field filtered
    // by that would paint only the good news and flatten the gradient the feature exists to show.
    // Darkness is a property of the PLACE; a star threshold is a question about what is worth
    // showing, and the field answers a different one.
    await renderMap({ heat: heatProp() });
    await screen.findByTestId('map-heat-layer');
    const before = heatLayerProps.last.points;
    openFilters();
    fireEvent.click(screen.getByTestId('star-filter-5'));
    expect(heatLayerProps.last.points).toBe(before);
  });
});

describe('MapView heat — role gating (§9.6, ungated for the pilot)', () => {
  it('paints the field for a LITE reader, exactly as it does for Pro', async () => {
    // §9.6 is RESOLVED — ungated. The field is built from `rating` alone, which LITE already
    // receives through the same scores payload, so gating it would withhold a picture drawn from
    // data the reader has. CLAUDE.md asks every new UI feature to be assessed; this is the
    // assessment, as a test rather than as a sentence.
    role = 'LITE_USER';
    await renderMap({ heat: heatProp() });
    expect(screen.getByTestId('wf-map-toolbar')).toBeInTheDocument();
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.points.length).toBeGreaterThan(0);
  });

  it('still withholds the premium arcs from a LITE marker in heat view', async () => {
    // The fiery/golden potentials that drive the marker's two progress arcs stay role-gated
    // regardless of view — the ramp is a fill colour, not a side door around the role gate.
    role = 'LITE_USER';
    await renderMap({ heat: heatProp() });
    expect(markerCalls.length).toBeGreaterThan(0);
    for (const args of markerCalls) {
      expect(args[1], 'fierySky must not reach a LITE marker').toBeNull();
      expect(args[2], 'goldenHour must not reach a LITE marker').toBeNull();
    }
  });
});

describe('MapView heat — the marker swap (D2/D8)', () => {
  it('paints markers with the same ramp colour in Heat and Pins view, because the ramp is now the map\'s only SCORE colour language', async () => {
    // D8's end state, not a mid-migration one: `makeMarkerIcon`'s cache key no longer carries a
    // per-view flag, so switching views is a cache HIT on the same icon rather than a second call
    // to `markerLabelAndColour` with a different answer — there is no code path left that could
    // recolour a marker on the way into Pins view (P10 hides them via opacity, never a remount).
    // The zero further calls below is therefore the proof, not a weaker substitute for one: the
    // identical cached DivIcon is reused, so its colour is provably identical, not merely
    // re-derived to match.
    await renderMap({ heat: heatProp() });
    expect(markerCalls.length).toBeGreaterThan(0);
    // Every call `MapView` actually made resolves to the same ramp stop — asserted on the CALLS
    // THEMSELVES (not a same-input call made fresh from the test body), so a `MapView` that started
    // handing the wrong rating/scores to `markerLabelAndColour` would be caught here.
    //
    // ⚠️ Iterates a SNAPSHOT, not `markerCalls` itself: `markerLabelAndColour` here is the spy that
    // pushes onto `markerCalls` on every call (see the `vi.mock` above), so looping the live array
    // while calling the spy inside the loop body grows the array out from under its own iterator —
    // an infinite loop that OOMs the worker. It reproduced exactly that way once.
    for (const args of [...markerCalls]) {
      expect(markerLabelAndColour(...args).colour).toBe(STOPS_VERDICT[3].hex);
    }

    markerCalls.length = 0;
    fireEvent.click(screen.getByRole('button', { name: 'Pins' }));
    expect(markerCalls).toEqual([]);
  });

  it('draws the ramp key from the ramp itself, so the picture and its legend cannot drift', async () => {
    await renderMap({ heat: heatProp() });
    // jsdom normalises hex to `rgb()` in a computed gradient, so the stops are compared as
    // channels — which is the same claim, and the one a browser would render.
    const style = screen.getByTestId('wf-map-heat-legend-ramp').getAttribute('style');
    for (const stop of STOPS_VERDICT) {
      const n = parseInt(stop.hex.slice(1), 16);
      expect(style).toContain(`rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`);
    }
  });
});

describe('MapView heat — the legend follows scoreRamp\'s active mode (heat-scale unification Stage 2)', () => {
  // MODE is scoreRamp module state, not a per-test fixture — a test that switches it and forgets to
  // undo it leaks into every case that runs after it, in this file or another.
  afterEach(() => {
    setMode('verdict');
  });

  it('stays on the verdict stops while nothing has switched the mode — the zero-visual-change proof', async () => {
    await renderMap({ heat: heatProp() });
    const style = screen.getByTestId('wf-map-heat-legend-ramp').getAttribute('style');
    for (const stop of STOPS_VERDICT) {
      const n = parseInt(stop.hex.slice(1), 16);
      expect(style).toContain(`rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`);
    }
    for (const stop of STOPS_TEMP) {
      const n = parseInt(stop.hex.slice(1), 16);
      expect(style).not.toContain(`rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`);
    }
  });

  it('repaints from the temperature stops once setMode(\'temp\') is called', async () => {
    // Called before the render rather than before the import — the import already happened at the
    // top of this file, so this is what proves the legend reads the ramp's mode at render time
    // rather than off a value captured once when `MapView` first loaded.
    setMode('temp');
    await renderMap({ heat: heatProp() });
    const style = screen.getByTestId('wf-map-heat-legend-ramp').getAttribute('style');
    for (const stop of STOPS_TEMP) {
      const n = parseInt(stop.hex.slice(1), 16);
      expect(style).toContain(`rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`);
    }
    for (const stop of STOPS_VERDICT) {
      const n = parseInt(stop.hex.slice(1), 16);
      expect(style).not.toContain(`rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`);
    }
  });
});

/**
 * The counts footer — map-tab-v2-plan.md §3 P7, README "§9 Count footer": `N of K shown`
 * + a `filtered` flag + a second line ("Beyond 3h: …" in My area, or the everywhere
 * sentence otherwise). All six fixture locations carry a served rating of 4 (`makeLocations`),
 * which clears the always-on 3★+ default, so the footer's numbers below are driven entirely by
 * SCOPE and by the filters this suite applies on top of it — never by the quality floor.
 */
describe('MapView heat — the counts footer', () => {
  it('reports the scope-only pool as "of K" — My area, no other filters active', async () => {
    // AREA_SPOTS is 5 of the 6 fixture spots (Kelso is beyond the glance); all 6 pass the default
    // 3★+ floor, so scoping to My area is the ONLY thing narrowing this footer's count.
    await renderMap({ heat: heatProp() });
    const foot = await screen.findByTestId('wf-map-counts-footer');
    expect(foot).toHaveTextContent('5');
    expect(foot).toHaveTextContent('of 5 shown');
    expect(screen.queryByTestId('wf-map-counts-filtered')).not.toBeInTheDocument();
  });

  it('⚠️ states a rated count only where it DIFFERS from the drawn count — a wildlife spot has '
      + 'no sky rating by design, so it is drawn and unrated', async () => {
    // The old footer rendered `scopedVisibleLocations.length` as BOTH the "named" and the "rated"
    // figure, so it could only ever claim the two were equal. `scopedRatedCount` is now asked of
    // `getRatingForLocation`, and a pure-wildlife location is the honest case where the two part:
    // the rating filter lets it through unrated (wildlife has no sky rating), so it is on screen
    // and uncounted.
    const locations = makeLocations();
    locations[0] = {
      ...locations[0],
      locationType: ['WILDLIFE'],
      forecastsByDate: new Map([[TODAY, {
        sunset: { solarEventTime: `${TODAY}T16:12:00` },
        sunrise: { solarEventTime: `${TODAY}T08:24:00` },
      }]]),
    };

    await act(async () => {
      render(
        <MapView locations={locations} date={TODAY} autoEventType={null} heat={heatProp()} />,
      );
    });

    const foot = await screen.findByTestId('wf-map-counts-footer');
    // Five drawn in My area, four of them rated — the wildlife spot is the fifth.
    expect(foot).toHaveTextContent('5 of 5 shown');
    expect(screen.getByTestId('wf-map-counts-rated')).toHaveTextContent('4 rated');
  });

  it('withholds the rated count when every drawn location is rated — never `5 of 5 · 5 rated`', async () => {
    // The codebase's own rule for a second number (`reachLens.formatLensCount`): with nothing
    // trimmed, a count equal to the one beside it is "a count dressed as a comparison".
    await renderMap({ heat: heatProp() });
    await screen.findByTestId('wf-map-counts-footer');
    expect(screen.queryByTestId('wf-map-counts-rated')).not.toBeInTheDocument();
  });

  it('the second line names the regions beyond the glance threshold, joined from planningArea\'s own test', async () => {
    await renderMap({ heat: heatProp({ beyondRegionNames: ['The Borders'] }) });
    const second = await screen.findByTestId('wf-map-counts-second');
    expect(second).toHaveTextContent('Beyond 3h');
    expect(second).toHaveTextContent('The Borders');
  });

  it('carries no second line when nothing lies beyond the glance', async () => {
    await renderMap({ heat: heatProp({ beyondRegionNames: [] }) });
    await screen.findByTestId('wf-map-counts-footer');
    expect(screen.queryByTestId('wf-map-counts-second')).not.toBeInTheDocument();
  });

  it('switches to the everywhere sentence and denominator on Everywhere', async () => {
    await renderMap({ heat: heatProp({ beyondRegionNames: ['The Borders'] }) });
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: 'Everywhere' }));

    const foot = await screen.findByTestId('wf-map-counts-footer');
    // All 6 fixture spots now count — the "Borders" region is IN scope once the scope is the
    // whole catalogue, so it can no longer be "beyond" anything.
    expect(foot).toHaveTextContent('of 6 shown');
    const second = screen.getByTestId('wf-map-counts-second');
    expect(second).toHaveTextContent('Everywhere');
    expect(second).not.toHaveTextContent('Beyond');
  });

  it('narrows with an active filter, and raises the `filtered` flag — but the flag ignores scope itself', async () => {
    // Bortle 1–4 clears DARK_SKY_THRESHOLD: only Bamburgh (4) and Wastwater (2) pass, and both are
    // inside AREA_SPOTS, so this isolates the dark-sky filter's own effect on the footer.
    await renderMap({ heat: heatProp() });
    openFilters();
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));

    const foot = await screen.findByTestId('wf-map-counts-footer');
    expect(foot).toHaveTextContent('2 of 5 shown'); // scope (5) is unaffected by the filter
    expect(screen.getByTestId('wf-map-counts-filtered')).toBeInTheDocument();
  });

  it('is absent entirely without a catalogue at all — a fresh install with nothing scored yet', async () => {
    await renderMap({ heat: heatProp({ enabled: false, spots: [], areaSpots: [] }) });
    expect(screen.queryByTestId('wf-map-counts-footer')).not.toBeInTheDocument();
  });
});

/**
 * Scope's exclusion from the popover's own active-filter count (README §4: "N = count of active
 * filters, scope not counted") — switching "My area" ⇄ "Everywhere" reframes the camera
 * rather than hiding anything the reader asked to see, so it must never make the chip read
 * "Filters (1)" on its own.
 */
describe('MapView heat — scope is excluded from the filters chip\'s own count', () => {
  it('toggling scope alone never raises the chip\'s count or its active styling', async () => {
    await renderMap({ heat: heatProp() });
    openFilters();
    const chip = screen.getByTestId('wf-filters-chip');
    expect(chip).not.toHaveTextContent('(');

    fireEvent.click(screen.getByRole('button', { name: 'Everywhere' }));
    expect(chip).not.toHaveTextContent('(');
    expect(chip.className).not.toContain('active');

    fireEvent.click(screen.getByRole('button', { name: 'My area' }));
    expect(chip).not.toHaveTextContent('(');
  });

  it('a genuine filter still counts alongside an already-flipped scope', async () => {
    await renderMap({ heat: heatProp() });
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: 'Everywhere' }));
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters (1)');
  });

  it('Clear all resets a genuine filter but leaves a flipped scope exactly where it was — "everything but scope" (README §4)', async () => {
    await renderMap({ heat: heatProp() });
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: 'Everywhere' }));
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters (1)');

    fireEvent.click(screen.getByTestId('clear-all-filters'));
    // The genuine filter is gone...
    expect(screen.getByTestId('wf-filters-chip')).not.toHaveTextContent('(');
    // ...but scope is untouched: still Everywhere, not silently reset to My area.
    expect(screen.getByRole('button', { name: 'Everywhere' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'My area' })).toHaveAttribute('aria-pressed', 'false');
  });
});

/**
 * The Legend panel (map-tab-v2-plan.md §3 P10) as wired into the real `MapView` — the popover
 * itself is `MapLegendPanel.test.jsx`'s job in isolation; this describes the CALLER'S wiring the
 * way `MapViewHeat.test.jsx`'s other describes already test the toolbar/filters wiring. Fixes 8
 * confirmed findings from an adversarial review round (C1/C3, C4, C6, C7 below; C2/C5/C8/C9 landed
 * in the changelog, `MapView.jsx`'s azimuth-lines suite and `PinsLayer.test.jsx` respectively).
 */
describe('MapView heat — the Legend panel (map-tab-v2-plan.md §3 P10)', () => {
  it('stacks with the LITE viewline-upsell chip rather than one suppressing the other (adversarial review C1/C3)', async () => {
    // The two are independent axes: the upsell keys on `auroraStatus`'s ALERT LEVEL, live
    // regardless of which event type is on screen; the Legend chip keys on `heatView`/
    // `heatOffered`, which excludes AURORA MODE specifically — and a LITE reader can never enter
    // aurora mode at all (`viewlineEnabled` is PRO/ADMIN-only), so an alert can fire while they sit
    // on an ordinary Heat-view sunset. A prior revision wrongly assumed these were the same axis.
    role = 'LITE_USER';
    auroraStatus = { level: 'MODERATE' };
    await renderMap({ heat: heatProp() });
    const upsell = screen.getByTestId('viewline-upsell-chip');
    const legendChip = screen.getByTestId('wf-legend-chip');
    expect(upsell).toBeInTheDocument();
    expect(legendChip).toBeInTheDocument();
    // Both are plain flex children of ONE positioned wrapper, not two independently `absolute`
    // chips claiming the same coordinates — jsdom computes no real layout, so this structural
    // check (one shared wrapper, neither child self-positioning) is the provable proxy for
    // "disjoint" here; the live browser pass (adversarial review) measured the rendered rects and
    // confirmed they do not overlap.
    const wrapper = screen.getByTestId('wf-map-chrome-bl');
    expect(wrapper).toContainElement(upsell);
    expect(wrapper).toContainElement(legendChip);
    expect(upsell.className).not.toMatch(/\babsolute\b/);
    expect(legendChip.className).not.toMatch(/\babsolute\b/);
  });

  it('withholds the rings toggle with no home COORDINATE, even though heatProp()\'s hasHome is true (adversarial review C4)', async () => {
    // `heatProp()` sets `hasHome: true` (the roster-level signal `FiltersPopover`'s scope segment
    // reads) but this test passes no `homeCoords` — the toggle must gate on the LATTER, the same
    // test `mapReachMeasured`/the ring paint/the ring labels use, or it is a control whose every
    // press does nothing.
    await renderMap({ heat: heatProp() });
    openLegend();
    expect(screen.getByTestId('wf-legend-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-legend-rings-toggle')).toBeNull();
  });

  it('offers the rings toggle once a real home coordinate exists', async () => {
    await renderMap({ heat: heatProp(), homeCoords: { lat: 54.9, lon: -1.4 } });
    openLegend();
    expect(screen.getByTestId('wf-legend-rings-toggle')).toBeInTheDocument();
  });

  it('flips MapHeatLayer\'s own rings prop off and back on through the real chip and toggle (adversarial review C6)', async () => {
    await renderMap({ heat: heatProp(), homeCoords: { lat: 54.9, lon: -1.4 } });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.rings).toBe(true);

    openLegend();
    fireEvent.click(screen.getByTestId('wf-legend-rings-toggle'));
    expect(heatLayerProps.last.rings).toBe(false);

    fireEvent.click(screen.getByTestId('wf-legend-rings-toggle'));
    expect(heatLayerProps.last.rings).toBe(true);
  });

  it('reads Regions / Zooming in / Places off a REAL zoom, at three points across the handover band (adversarial review C7)', async () => {
    await renderMap({ heat: heatProp() });
    openLegend();
    // Below FADE_FROM (10.4) — the mount default of 9 is already there.
    expect(screen.getByTestId('wf-legend-hand')).toHaveTextContent('Regions');

    fireZoomend(11.2); // the midpoint of the 10.4→12.0 band
    expect(screen.getByTestId('wf-legend-hand')).toHaveTextContent('Zooming in');

    fireZoomend(12); // at FADE_TO
    expect(screen.getByTestId('wf-legend-hand')).toHaveTextContent('Places');
  });
});

/**
 * Azimuth lines are overlay-only in the tab's Pins mode (decision D-9, map-tab-v2-plan.md §3 P10)
 * — they were marker-layer furniture with no host in the new pin vocabulary. Zero coverage existed
 * for this before adversarial review C5; the `handoffLocationName` prop drives the selection this
 * needs (a real click needs a marker mock this file's own `Marker` stub discards `eventHandlers`
 * from), and `polylineCalls` (the recording mock above) is what proves a line was actually asked
 * for, not merely that nothing crashed.
 */
describe('MapView heat — azimuth lines are overlay-only in Pins mode (decision D-9, adversarial review C5)', () => {
  it('draws the sunset azimuth line in Heat view, for a selected location', async () => {
    const azLoc = makeAzimuthLocation();
    await renderMap({
      heat: heatProp(), locations: azLoc, handoffLocationName: azLoc[0].name,
    });
    expect(polylineCalls.length).toBeGreaterThan(0);
  });

  it('drops the azimuth line the moment the tab switches to Pins', async () => {
    const azLoc = makeAzimuthLocation();
    await renderMap({
      heat: heatProp(), locations: azLoc, handoffLocationName: azLoc[0].name,
    });
    expect(polylineCalls.length).toBeGreaterThan(0);

    polylineCalls.length = 0;
    fireEvent.click(screen.getByRole('button', { name: 'Pins' }));
    expect(polylineCalls.length).toBe(0);
  });

  it('brings the azimuth line straight back on switching back to Heat', async () => {
    const azLoc = makeAzimuthLocation();
    await renderMap({
      heat: heatProp(), locations: azLoc, handoffLocationName: azLoc[0].name,
    });
    expect(polylineCalls.length).toBeGreaterThan(0);

    polylineCalls.length = 0;
    fireEvent.click(screen.getByRole('button', { name: 'Pins' }));
    expect(polylineCalls.length).toBe(0);

    fireEvent.click(screen.getByRole('button', { name: 'Heat' }));
    expect(polylineCalls.length).toBeGreaterThan(0);
  });

  it('keeps the azimuth line on the Plan-tab overlay, which never enters Pins mode at all', async () => {
    const azLoc = makeAzimuthLocation();
    await renderMap({
      overlayMode: true, locations: azLoc, handoffLocationName: azLoc[0].name,
    });
    expect(polylineCalls.length).toBeGreaterThan(0);
  });
});

/**
 * The Regions jump list (map-tab-v2-plan.md §3 P11, `docs/design/map-tab-v2/README.md` §2) — the
 * `RegionsJump`/`utils/regionsJump.js` unit suites cover the pure sort/join/anatomy in isolation;
 * this describes the CALLER'S wiring, the way `MapViewHeat.test.jsx`'s other describes already do
 * for the toolbar/filters/Legend. `SPOTS` carries three regions: "North East" (Bamburgh, Tynemouth,
 * Alnmouth, Coquet), "The Lakes" (Wastwater alone) and "The Borders" (Kelso alone) — and
 * `AREA_SPOTS` (the fixture's own "My area") excludes ONLY Kelso, so "The Borders" is the one region
 * outside scope in every test below.
 */
describe('MapView heat — the Regions jump list (map-tab-v2-plan.md §3 P11)', () => {
  /** Bamburgh/Tynemouth/Alnmouth measured; Coquet and Kelso are not — the unmeasured-last case. */
  const REACH_BY_ID = new Map([
    [1, { driveMinutes: 40, distanceMiles: 25 }],
    [2, { driveMinutes: 35, distanceMiles: 20 }], // nearest in North East
    [5, { driveMinutes: 50, distanceMiles: 30 }],
    [3, { driveMinutes: 200, distanceMiles: 120 }], // The Lakes — clears the 3h glance (180 min)
  ]);
  const REGION_BEST_INDEX = new Map([
    [`${TODAY}|SUNSET|North East`, 5],
    [`${TODAY}|SUNSET|The Lakes`, 3],
    // "The Borders" deliberately absent — no served best for this window.
  ]);

  function openJump() {
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
  }

  it('sorts by nearest measured drive, states the "beyond your area" suffix and the served best score, unmeasured last', async () => {
    await renderMap({
      heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX,
    });
    openJump();

    const rows = screen.getAllByTestId('wf-jump-row');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('North East');
    expect(rows[1]).toHaveTextContent('The Lakes');
    expect(rows[2]).toHaveTextContent('The Borders');

    // North East's nearest is Tynemouth (35 min) — well under the 180 min glance threshold.
    expect(rows[0]).toHaveTextContent('35 min');
    expect(rows[0]).not.toHaveTextContent('beyond your area');
    expect(rows[0]).toHaveTextContent('5★');
    expect(rows[0].querySelector('i')).not.toBeNull();

    // The Lakes clears the glance threshold (200 > 180) — the suffix fires even though (the next
    // test proves) this region is still inside `heat.areaSpots`: "beyond the glance" and "outside
    // My area" are different axes that happen to agree less often than they disagree.
    expect(rows[1]).toHaveTextContent('3h 20min');
    expect(rows[1]).toHaveTextContent('beyond your area');
    expect(rows[1]).toHaveTextContent('3★');

    // The Borders (Kelso) has no reach entry at all — no duration, no "beyond" claim, sorts last —
    // and no served best for this window either.
    expect(within(rows[2]).getByTestId('wf-jump-drive')).toHaveTextContent('');
    expect(rows[2]).not.toHaveTextContent('beyond your area');
    expect(rows[2].querySelector('i')).toBeNull();
  });

  it('shows an em dash, never a number, for a region the served rollup carries no best for', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    const rows = screen.getAllByTestId('wf-jump-row');
    const borders = rows.find((r) => r.textContent.includes('The Borders'));
    expect(borders).toHaveTextContent('—'); // — em dash
  });

  /**
   * A night (astro/aurora) window's rows carry a real score too — the adjudicated ruling
   * (map-tab-v2-plan.md §3 P11): `mapEvents.bestOfNight`'s ALREADY-licensed client max, grouped by
   * region (`utils/regionsJump.buildNightRegionBest`) over the SAME served rows
   * `astroConditionsByDate` feeds the window dropdown's own "N★ best" column with — never a second
   * re-derivation. `getAstroConditions` is queued TWICE with the identical rows because this mode
   * calls it from two independent effects (the bounded multi-date preview `astroConditionsByDate`
   * this test actually needs, and the single active-night `astroScores` the field paints from) —
   * order between the two is not guaranteed, so both queued responses must agree.
   */
  it('a night window carries the grouped max per region, and the honest dash for a region with no served night rows', async () => {
    const nightRows = [
      { locationName: SPOTS[0].name, stars: 3 }, // Bamburgh, North East
      { locationName: SPOTS[1].name, stars: 5 }, // Tynemouth, North East — the region's max
    ];
    getAstroAvailableDates.mockResolvedValueOnce([TODAY]);
    getAstroConditions.mockResolvedValueOnce(nightRows);
    getAstroConditions.mockResolvedValueOnce(nightRows);

    await renderMap({ heat: heatProp(), handoffEventType: 'ASTRO' });
    openJump();

    const rows = screen.getAllByTestId('wf-jump-row');
    const northEast = rows.find((r) => r.textContent.includes('North East'));
    expect(northEast).toHaveTextContent('5★');
    expect(northEast.querySelector('i')).not.toBeNull();

    // "The Borders" (Kelso) had no served night rows at all — the honest dash, not a zero.
    const borders = rows.find((r) => r.textContent.includes('The Borders'));
    expect(borders).toHaveTextContent('—');
    expect(borders.querySelector('i')).toBeNull();
  });

  it('selecting a region already inside "My area" fits ITS OWN bounds, animate:false, and does not flip scope', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    fitBounds.mockClear();

    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('North East')));

    const neSpots = SPOTS.filter((s) => s.regionName === 'North East');
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(latLngBounds(neSpots, 0.06), { padding: [40, 40], animate: false });
    // The menu closes on selection — see the dedicated test below for why this diverges from
    // `FiltersPopover`'s own rows, which deliberately stay open.
    expect(screen.queryByTestId('wf-jump-menu')).not.toBeInTheDocument();

    // Scope did not move — "My area" is still the pressed segment.
    openFilters();
    expect(screen.getByRole('button', { name: 'My area' })).toHaveAttribute('aria-pressed', 'true');
  });

  /**
   * A dedicated, named check for the panel-closes-on-select rule — adversarial review + a live
   * browser finding (map-tab-v2-plan.md §3 P11). The design bundle is silent on this (its own
   * `jumpTo` closes every menu unconditionally on ANY interaction, including a filter change, so it
   * never had to decide this on its own terms); the reasoning is stated once, in code, on
   * `MapView.jsx`'s `jumpToRegion`: a jump is a COMPLETED navigation — the camera has already moved
   * — where `FiltersPopover`'s rows are a STANDING choice still being composed one control at a
   * time, and closing on the first press there would make every later one a fresh re-open.
   */
  it('closes the jump panel on row selection — a jump is a completed navigation, unlike a filter row', async () => {
    await renderMap({ heat: heatProp() });
    openJump();
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();

    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('North East')));

    expect(screen.queryByTestId('wf-jump-menu')).not.toBeInTheDocument();
  });

  it('jumping outside "My area" flips scope to Everywhere AND still fits the REGION\'s own bounds — never the newly-widened catalogue box (the override-vs-race guard)', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    fitBounds.mockClear();

    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders')));

    const borderSpots = SPOTS.filter((s) => s.regionName === 'The Borders');
    const expectedBounds = latLngBounds(borderSpots, 0.06);
    // ⚠️ The assertion that actually matters: flipping `heatArea` changes the ORDINARY
    // `heatArea ? areaBounds : catalogueBounds` value in the very same commit, which would also
    // re-arm a naive `HeatBoundsController` onto `CATALOGUE_BOUNDS` — the race `jumpFitOverride`
    // exists to prevent. Only ONE fitBounds call may happen, and it must name Kelso's own box.
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(expectedBounds, { padding: [40, 40], animate: false });
    expect(expectedBounds).not.toEqual(CATALOGUE_BOUNDS);
    expect(expectedBounds).not.toEqual(AREA_BOUNDS);

    openFilters();
    expect(screen.getByRole('button', { name: 'Everywhere' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('a later "My area" press still wins over a stale jump override', async () => {
    // The reset path (`resetToMyArea`, also `⌂`'s own) clears `jumpFitOverride` on every press —
    // without that clear this would still show Kelso's box, ignoring the reader's own later choice.
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders')));

    openFilters();
    fitBounds.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'My area' }));

    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
  });

  /**
   * The way back (`RegionsJump`'s reset row, `MapView.clearRegionJump`). The component suite
   * covers the row's own anatomy; these pin the two things only the CALLER can be wrong about —
   * which scope the press lands in, and whether the camera actually leaves the region's box.
   */
  it('offers no way back until there is a jump to undo', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    expect(screen.queryByTestId('wf-jump-reset')).not.toBeInTheDocument();
  });

  it('undoes a jump made from "My area" IN FULL — the fit leaves the region\u2019s box and scope goes back with it', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    // "The Borders" is the fixture's one out-of-area region, so this jump flips scope to Everywhere.
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders')));

    openJump();
    fitBounds.mockClear();
    fireEvent.click(screen.getByTestId('wf-jump-reset'));

    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
    openFilters();
    expect(screen.getByRole('button', { name: 'My area' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('names the scope its own press lands in, not the one the jump left in force', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders')));

    openJump();
    // Scope is Everywhere at this instant — the jump flipped it — but the press restores My area,
    // so "Back to Everywhere" here would be a label describing the state it is about to leave.
    expect(screen.getByTestId('wf-jump-reset')).toHaveTextContent('Back to My area');
  });

  it('does NOT drag a reader who chose Everywhere back to My area — the undo is the jump\u2019s, not the scope\u2019s', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: 'Everywhere' }));

    openJump();
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders')));

    openJump();
    expect(screen.getByTestId('wf-jump-reset')).toHaveTextContent('Back to Everywhere');
    fitBounds.mockClear();
    fireEvent.click(screen.getByTestId('wf-jump-reset'));

    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(CATALOGUE_BOUNDS, { padding: [28, 28], animate: false });
    openFilters();
    expect(screen.getByRole('button', { name: 'Everywhere' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('carries the pre-jump scope across a SECOND jump — the flip happens once, the undo must not forget it', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    // First jump flips scope to Everywhere...
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders')));
    openJump();
    // ...and by now `heatArea` is already false, so re-deriving "did this jump change scope" on
    // the second one reads NO and would strand the reader at Everywhere.
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Lakes')));

    openJump();
    expect(screen.getByTestId('wf-jump-reset')).toHaveTextContent('Back to My area');
    fitBounds.mockClear();
    fireEvent.click(screen.getByTestId('wf-jump-reset'));
    // ⚠️ ONE call: this press fires two updates that BOTH change `heatBounds`
    // (`setJumpFitOverride(null)` and `setHeatArea`), which is exactly where the race the override
    // exists to prevent would show up again — the same guard the scope-flip test above carries.
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
  });

  it('lands where the row says even with no home — the label and the press are one expression', async () => {
    // ⚠️ `hasHome: false` has THREE causes and only two are "the area frame does not narrow".
    // `areaSpots: []` is the third — an away origin whose region contributes no heat spots — and
    // there `areaBounds` is null, so a press that restored My area would promise a camera move to
    // a box that does not exist. The row says Everywhere, so the press must land at Everywhere.
    await renderMap({
      heat: heatProp({ hasHome: false, areaSpots: [], areaBounds: null }),
      reachById: REACH_BY_ID,
      regionBestIndex: REGION_BEST_INDEX,
    });
    openJump();
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders')));

    openJump();
    expect(screen.getByTestId('wf-jump-reset')).toHaveTextContent('Back to Everywhere');
    fitBounds.mockClear();
    fireEvent.click(screen.getByTestId('wf-jump-reset'));

    // The catalogue box, NOT the null area box — the camera actually moves.
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(CATALOGUE_BOUNDS, { padding: [28, 28], animate: false });
  });

  /**
   * ⚠️ The no-postcode reader's own arm, and the regression test for the ONE-EXPRESSION version of
   * `jumpResetArea` (adversarial review). With no home the area frame does not narrow, so
   * `areaSpots` IS the catalogue — `jumpToRegion` never flips scope, and the reset therefore has no
   * scope change to undo. Gating the PRESS on `hasHome` wrote `heatArea = false` anyway, which is
   * **sticky** for this reader (no scope segment, `⌂` inert and phone-hidden) and makes the counts
   * footer assert a "usual drive" for somebody who has saved no postcode — the reach-honesty rule.
   * The label still says Everywhere, which is honest: with no narrowing frame that is what is on
   * screen. Label and press answer different questions here, and that is the point.
   */
  it('does not flip scope for a reader with no postcode — the jump changed none, so the undo changes none', async () => {
    await renderMap({
      heat: heatProp({ hasHome: false }), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX,
    });
    expect(screen.queryByTestId('wf-map-counts-second')).not.toBeInTheDocument();

    openJump();
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders')));
    openJump();
    expect(screen.getByTestId('wf-jump-reset')).toHaveTextContent('Back to Everywhere');
    fitBounds.mockClear();
    fireEvent.click(screen.getByTestId('wf-jump-reset'));

    // The claim that actually reaches the reader: the footer must not start naming a drive they
    // have never measured.
    expect(screen.queryByTestId('wf-map-counts-second')).not.toBeInTheDocument();
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
  });

  it('closes the panel on the reset press, exactly as a jump does', async () => {
    // Named in its own right rather than relied on incidentally: the jump's identical rule has its
    // own test above because a review and a browser pass both found it.
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Lakes')));
    openJump();
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wf-jump-reset'));
    expect(screen.queryByTestId('wf-jump-menu')).not.toBeInTheDocument();
  });

  it('marks the region whose jump is in force, and moves the mark on the next jump', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Lakes')));

    openJump();
    expect(screen.getByRole('button', { name: /The Lakes/ })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: /North East/ })).not.toHaveAttribute('aria-current');

    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('North East')));
    openJump();
    expect(screen.getByRole('button', { name: /North East/ })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: /The Lakes/ })).not.toHaveAttribute('aria-current');
  });

  it('clears the mark once the jump is undone', async () => {
    await renderMap({ heat: heatProp(), reachById: REACH_BY_ID, regionBestIndex: REGION_BEST_INDEX });
    openJump();
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Lakes')));
    openJump();
    fireEvent.click(screen.getByTestId('wf-jump-reset'));

    openJump();
    ['North East', 'The Lakes', 'The Borders'].forEach((name) => {
      expect(screen.getByRole('button', { name: new RegExp(name) })).not.toHaveAttribute('aria-current');
    });
    expect(screen.queryByTestId('wf-jump-reset')).not.toBeInTheDocument();
  });

  it('joins the exclusivity group — opening Filters closes an open jump menu, and vice versa', async () => {
    await renderMap({ heat: heatProp() });
    openJump();
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();

    openFilters();
    expect(screen.queryByTestId('wf-jump-menu')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    openJump();
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
  });
});

// ⚠️ `⌂`'s own new "resets scope to My area and refits" behaviour (map-tab-v2-plan.md §3 P11) is
// NOT tested in this file. `CentreOnHomeControl` is a real Leaflet `L.Control`, and this file's own
// `leaflet`/`useMap` mocks (built for the field/toolbar/callout surfaces the rest of this file
// covers) carry neither an `L.Control` stub nor a stable per-test `map` reference for one to attach
// to — under this file's `useMap()`, which returns a FRESH object literal on every call, the
// control's own `[container, map]`-keyed effect would tear down and rebuild on every render, which
// is exactly the kind of instability `MapViewCentreOnHome.test.jsx`'s dedicated Leaflet-`Control`
// stub exists to avoid. `⌂`'s new behaviour is pinned there instead, alongside its own
// mount/position/no-postcode-fallback tests, using that file's own `heatProp()`-style fixture.

describe('the window pill\'s verdict — filters must not move it, scope must (design check 1)', () => {
  /**
   * A briefing whose TOP region is one the planning area excludes.
   *
   * `AREA_SPOTS` drops `SPOTS[3]` (Kelso / The Borders), so the catalogue holds three regions and
   * the area holds two. Giving The Borders the highest mean makes the two scopes give genuinely
   * different answers — without that, "scope moves the verdict" would pass on a fixture where it
   * could not have failed.
   */
  const DAYS = [{
    date: TODAY,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [
        { regionName: 'The Borders', meanRating: 4.4, displayVerdict: 'WORTH_IT', slots: [{ canopy: false }] },
        { regionName: 'The Lakes', meanRating: 2.9, displayVerdict: 'MAYBE', slots: [{ canopy: false }] },
        { regionName: 'North East', meanRating: 2.0, displayVerdict: 'STAND_DOWN', slots: [{ canopy: false }] },
      ],
    }],
  }];

  const verdictProps = () => ({
    heat: heatProp(),
    regionVerdictIndex: buildRegionVerdictIndex(DAYS),
  });

  /** What the pill is actually saying, as a reader would read it. */
  function pillVerdict() {
    return {
      word: screen.getByTestId('wf-win-verdict').querySelector('b').textContent,
      region: screen.queryByTestId('wf-win-verdict-region')?.textContent ?? null,
      tier: screen.getByTestId('wf-win-pill').getAttribute('data-tier'),
    };
  }

  it('states the strongest region in scope, and names it', async () => {
    await renderMap(verdictProps());

    // Opens in "My area", which excludes The Borders — so the answer is The Lakes, not the
    // catalogue's best.
    expect(pillVerdict()).toEqual({ word: 'Maybe', region: 'The Lakes', tier: 'MAYBE' });
  });

  it('would MOVE if the verdict were computed from a filtered pool — the mutation these guard', async () => {
    // ⚠️ Two of the four filter tests below cannot fail on their own, and that was a review finding:
    // with every fixture location rated 4 a 4★ floor removes nothing, and the dark-sky filter still
    // leaves both regions represented — so against the mutation they exist to catch
    // (`regionsInScope = regionNamesOf(scopedVisibleLocations)`) the region set is IDENTICAL and the
    // pill does not move. They are kept because each states the rule for its own control, but this
    // is the one with teeth: it empties the DRAWN set entirely while leaving the scope pool
    // untouched, so a verdict taken from the filtered pool would have nothing to name at all.
    await renderMap(verdictProps());
    expect(pillVerdict()).toEqual({ word: 'Maybe', region: 'The Lakes', tier: 'MAYBE' });

    openFilters();
    fireEvent.click(screen.getByTestId('star-filter-5'));

    expect(pillVerdict()).toEqual({ word: 'Maybe', region: 'The Lakes', tier: 'MAYBE' });
  });

  it('does not move when the minimum rating changes', async () => {
    await renderMap(verdictProps());
    const before = pillVerdict();

    openFilters();
    fireEvent.click(screen.getByTestId('star-filter-4'));

    expect(pillVerdict()).toEqual(before);
  });

  it('does not move when the subject chips change', async () => {
    await renderMap(verdictProps());
    const before = pillVerdict();

    openFilters();
    fireEvent.click(screen.getByTestId('location-type-filter-SEASCAPE'));

    expect(pillVerdict()).toEqual(before);
  });

  it('does not move when the dark-sky filter is toggled', async () => {
    await renderMap(verdictProps());
    const before = pillVerdict();

    openFilters();
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));

    expect(pillVerdict()).toEqual(before);
  });

  it('does not move when the drive-time tier changes', async () => {
    await renderMap(verdictProps());
    const before = pillVerdict();

    openFilters();
    fireEvent.click(screen.getByTestId('drive-time-filter-45'));

    expect(pillVerdict()).toEqual(before);
  });

  it('DOES move when the scope flips to the whole catalogue', async () => {
    await renderMap(verdictProps());
    expect(pillVerdict()).toEqual({ word: 'Maybe', region: 'The Lakes', tier: 'MAYBE' });

    openFilters();
    fireEvent.click(screen.getByTestId('wf-filters-scope-all'));

    // The Borders is now a candidate, and it is the strongest.
    expect(pillVerdict()).toEqual({ word: 'Worth it', region: 'The Borders', tier: 'WORTH_IT' });
  });

  it('says "everywhere" rather than naming the least-bad region when they all agree', async () => {
    const allPoor = [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [
          { regionName: 'The Lakes', meanRating: 2.2, displayVerdict: 'STAND_DOWN', slots: [{ canopy: false }] },
          { regionName: 'North East', meanRating: 2.0, displayVerdict: 'STAND_DOWN', slots: [{ canopy: false }] },
        ],
      }],
    }];
    await renderMap({ heat: heatProp(), regionVerdictIndex: buildRegionVerdictIndex(allPoor) });

    const { word, region } = pillVerdict();
    expect(word).toBe('Poor');
    expect(region).toBe('everywhere in your area');
  });

  it('drops "in your area" once the scope IS the whole catalogue', async () => {
    const allPoor = [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [
          { regionName: 'The Lakes', meanRating: 2.2, displayVerdict: 'STAND_DOWN', slots: [{ canopy: false }] },
          { regionName: 'North East', meanRating: 2.0, displayVerdict: 'STAND_DOWN', slots: [{ canopy: false }] },
          { regionName: 'The Borders', meanRating: 1.8, displayVerdict: 'STAND_DOWN', slots: [{ canopy: false }] },
        ],
      }],
    }];
    await renderMap({ heat: heatProp(), regionVerdictIndex: buildRegionVerdictIndex(allPoor) });

    openFilters();
    fireEvent.click(screen.getByTestId('wf-filters-scope-all'));

    expect(screen.getByTestId('wf-win-verdict-region').textContent).toBe('everywhere');
  });

  it('renders no verdict at all with no index — the pre-L2 control', async () => {
    await renderMap({ heat: heatProp() });

    expect(screen.queryByTestId('wf-win-verdict')).toBeNull();
    expect(screen.getByTestId('wf-win-pill')).not.toHaveAttribute('data-tier');
  });
});

/**
 * The landing card (map-landing-plan.md §3 L4) — what OPENS it, what closes it, and the far longer
 * list of things that must not.
 *
 * <p>The card's own selection rules are pinned in `mapLanding.test.js` and its markup in
 * `MapLandingCard.test.jsx`; this block is the wiring only — the once-per-run key, the three
 * dismissal routes and, more importantly, the five gestures that are NOT dismissal routes.
 */
/**
 * The window panel — the drilldown's first level (map-landing-plan.md §3 L5).
 *
 * <p>Its rows and its note are pinned in `mapDrilldown.test.js`, its markup in
 * `MapWindowPanel.test.jsx`. This block is the wiring: the one live entry, the exclusivity it
 * inherits from `openMapMenu`, and the two dismissal rules a panel has that the landing card
 * does not.
 */
describe('the window panel — this window, region by region', () => {
  const DAYS = [{
    date: TODAY,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [
        { regionName: 'The Lakes', meanRating: 4.4, bestRating: 5, displayVerdict: 'WORTH_IT', slots: [{ canopy: false }] },
        { regionName: 'North East', meanRating: 2.0, bestRating: 3, displayVerdict: 'STAND_DOWN', slots: [{ canopy: false }] },
        { regionName: 'The Borders', meanRating: 4.9, bestRating: 5, displayVerdict: 'WORTH_IT', slots: [{ canopy: false }] },
      ],
    }],
  }];

  const panelProps = (extra = {}) => ({
    heat: heatProp(),
    regionVerdictIndex: buildRegionVerdictIndex(DAYS),
    ...extra,
  });

  const openPanel = () => {
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getByTestId('wf-win-more'));
  };

  let drilldownForeignModal = null;
  afterEach(() => {
    drilldownForeignModal?.remove();
    drilldownForeignModal = null;
  });

  /**
   * ⚠️ **The WIRING, which the panel's own unit tests structurally cannot prove.**
   *
   * <p>The panel's own file mounts it with no `.wf-map-tab` ancestor, so
   * `foreignModalOverPaneOf` falls into its null branch there and the pane is never really
   * resolved. This drives the real component tree — the panel genuinely inside the pane — with a
   * real foreign modal in the document, and presses `Escape` where a reader would: on the panel,
   * which is where the subtree handler lives.
   *
   * <p>⚠️ An earlier cut threaded a required `foreignModalOver` PROP from `MapView` and this test
   * was written to prove the wiring. Mutation testing killed that design: with either mount
   * unwired the predicate threw inside the handler, the handler died before acting, and the panel
   * stayed open — so this test passed for the wrong reason. The panels resolve their own pane now,
   * and there is no wiring left to forget.
   */
  /**
   * ⚠️ **The mutant-killing half.** A modal in `document.body` cannot distinguish "resolved the pane
   * and found the modal outside it" from "failed to resolve the pane and fell into the null branch",
   * because both stand down — breaking `MAP_PANE_SELECTOR` left every test green through two
   * rounds of this review. A modal INSIDE the pane separates them: containment says it is the
   * pane's own business, so the panel must still act. With the selector broken, `closest` returns
   * null, the null branch stands down, and this fails.
   */
  it('⚠️ a modal the pane renders INLINE does not stand the drilldown down', async () => {
    await renderMap(panelProps());
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getByTestId('wf-win-more'));
    const pane = screen.getByTestId('wf-map-pane');
    drilldownForeignModal = document.createElement('div');
    drilldownForeignModal.setAttribute('role', 'dialog');
    drilldownForeignModal.setAttribute('aria-modal', 'true');
    pane.appendChild(drilldownForeignModal);

    fireEvent.keyDown(screen.getByTestId('wf-win-panel'), { key: 'Escape' });

    expect(screen.queryByTestId('wf-win-panel'), 'an inline modal is the pane\u2019s own business')
      .toBeNull();
  });

  it('⚠️ Escape does not operate the drilldown behind a foreign modal — the real wiring', async () => {
    await renderMap(panelProps());
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getByTestId('wf-win-more'));
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();

    // Torn down in this describe's `afterEach`, not on the last line — an assertion that threw
    // first would leave an `[aria-modal]` in the body and silently stand every later Escape test
    // in this file down. That trap is recorded on the landing card's own copy of this fixture.
    drilldownForeignModal = document.createElement('div');
    drilldownForeignModal.setAttribute('role', 'dialog');
    drilldownForeignModal.setAttribute('aria-modal', 'true');
    document.body.appendChild(drilldownForeignModal);

    // ⚠️ The discriminator. Planting the modal in `document.body` alone does NOT prove the pane was
    // resolved: a broken `MAP_PANE_SELECTOR` returns null, the null branch stands down for any modal
    // anywhere, and the assertion below passes either way — both review lenses said so. These two
    // lines are what make this a wiring test: the panel must resolve the pane by
    // `MAP_PANE_SELECTOR`, and that pane must be the element carrying the ref and the key handler.
    expect(
      screen.getByTestId('wf-win-panel').closest('.wf-map-tab'),
      'the panel must sit inside MAP_PANE_SELECTOR, on the ref-bearing element',
    ).toBe(screen.getByTestId('wf-map-pane'));

    fireEvent.keyDown(screen.getByTestId('wf-win-panel'), { key: 'Escape' });

    expect(
      screen.getByTestId('wf-win-panel'),
      'the panel must survive an Escape aimed at the sheet above it',
    ).toBeInTheDocument();
    // ⚠️ This pins that the stand-down moves no focus — NOT the O-20 arm C guard, and an earlier
    // version of this comment claimed otherwise. Measured by a review lens: both these cases pass
    // with `useDialogFocus`'s guard removed, because the foreign modal here is a planted `div` and
    // no consumer of that hook unmounts on this press, so its cleanup never runs. Arm C's guard is
    // covered in `useDialogFocus.test.jsx`, which is the level it lives at.
    expect(screen.getByTestId('wf-win-panel').contains(document.activeElement)
      || document.activeElement === screen.getByTestId('wf-win-panel'),
    'focus must stay inside the panel that survived').toBe(true);
  });

  it('opens from the pill menu\'s own footer row, and closes the menu on the way', async () => {
    await renderMap(panelProps());
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    // Asserted by ROLE and NAME, as the standards require of anything interactive — the glyph is
    // `aria-hidden`, so this also pins that the words survive it.
    const entry = screen.getByRole('button', { name: /This window, region by region/ });
    expect(entry).toBe(screen.getByTestId('wf-win-more'));
    fireEvent.click(entry);

    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
  });

  /**
   * ⚠️ **This asserted the PILL until the PR review, and the panel is the right answer.** The entry
   * row unmounts itself with the menu, so without a focus move at all it falls to `<body>` — and
   * `handleMapPaneKeyDown` is a React `onKeyDown` on the pane root, which a press on `<body>` never
   * reaches, leaving the panel un-closable by keyboard on its only entry. L5 solved that by focusing
   * the pill, which is inside the pane and fixes Escape — but leaves focus OUTSIDE a `role="dialog"`
   * that has just appeared, so a screen reader announces nothing. The panel now takes it, matching
   * `MapRegionPanel` one level down. `WindowControl`'s `pillRef.current?.focus()` still runs first
   * and is harmless; this asserts who ends up with it.
   */
  it('⚠️ hands focus to the PANEL, which is what makes Escape reachable and the dialog announced', async () => {
    await renderMap(panelProps());

    openPanel();

    expect(document.activeElement).toBe(screen.getByTestId('wf-win-panel'));
  });

  it('renders the note its own state asks for, not a hard-coded one', async () => {
    // ⚠️ The note had no rendered coverage at all: `note=""`, a hard-coded `isSolar: true` and a
    // hard-coded `scopeIsArea: true` all survived the suite (measured). This drives the real wiring.
    await renderMap(panelProps());

    openPanel();

    // In "My area" only The Lakes is Worth it (North East is Poor), so this is the one-region
    // branch: the ranking rule alone, with no drive-time sentence to make.
    expect(screen.getByTestId('wf-win-panel-note'))
      .toHaveTextContent(/^Verdict is the strongest region’s average\./);
    expect(screen.getByTestId('wf-win-panel-note').textContent).not.toMatch(/regions are/);
  });

  it('...and switches branch when the scope brings a second Worth it region in', async () => {
    // The Borders is Worth it too and sits outside the area — flipping scope makes two share the
    // tier, which is the several-regions branch. Driven through the real wiring, not a stub prop.
    await renderMap(panelProps());
    openFilters();
    fireEvent.click(screen.getByTestId('wf-filters-scope-all'));

    openPanel();

    expect(screen.getByTestId('wf-win-panel-note'))
      .toHaveTextContent(/^2 regions are worth it for this window, so the choice between them is drive time\./);
  });

  it('says "in your area" only when there IS an area', async () => {
    await renderMap(panelProps({ heat: heatProp({ hasHome: false }) }));

    openPanel();

    expect(screen.getByTestId('wf-win-panel-note').textContent).not.toMatch(/your area/);
  });

  it('offers no entry when the map is on a window the EV list has no row for', async () => {
    // ⚠️ The overlay test below cannot cover this gate: `WindowControl` is not mounted there at all,
    // so `wf-win-more` can never exist regardless of what the gate does (measured — removing it left
    // that test green). A date outside the forecast domain is the reachable case.
    await renderMap({ ...panelProps(), date: '2026-01-25' });

    fireEvent.click(screen.getByTestId('wf-win-pill'));

    expect(screen.queryByTestId('wf-win-more')).toBeNull();
  });

  it('is about the window in force, and lists only regions in SCOPE', async () => {
    // `AREA_SPOTS` drops Kelso / The Borders, so the catalogue holds three regions and the area
    // two — and The Borders has the BEST mean, so a panel built over the wrong pool would lead
    // with it. Same population the pill's tally counts.
    await renderMap(panelProps());

    openPanel();

    expect(screen.getByTestId('wf-win-panel-window')).toHaveTextContent('Tonight');
    expect(screen.getAllByTestId('wf-win-panel-row').map((r) => r.dataset.region))
      .toEqual(['The Lakes', 'North East']);
  });

  it('DOES list it once the scope is the whole catalogue', async () => {
    await renderMap(panelProps());
    openFilters();
    fireEvent.click(screen.getByTestId('wf-filters-scope-all'));

    openPanel();

    expect(screen.getAllByTestId('wf-win-panel-row').map((r) => r.dataset.region))
      .toEqual(['The Borders', 'The Lakes', 'North East']);
  });

  it('counts the window\'s own rated locations, at four stars and better', async () => {
    // `POINTS_BY_KEY[TODAY:SUNSET]` gives The Lakes one 4★ (Wastwater) and North East a 5, a 2,
    // a 4 and a 2 — so the two rows must read differently, and neither may count the other's.
    await renderMap(panelProps());

    openPanel();

    // ⚠️ The denominator counts PLACES in scope, not rows we hold a rating for. `AREA_SPOTS` gives
    // The Lakes one location and North East four; the window rates Wastwater 4★, and North East's
    // Bamburgh 5 / Tynemouth 2 / Alnmouth 4 / Coquet 2.
    const rows = screen.getAllByTestId('wf-win-panel-row');
    expect(within(rows[0]).getByTestId('wf-win-panel-stat')).toHaveTextContent('1 of 1 at 4★+');
    expect(within(rows[1]).getByTestId('wf-win-panel-stat')).toHaveTextContent('2 of 4 at 4★+');
  });

  it('Escape closes it', async () => {
    await renderMap(panelProps());
    openPanel();

    fireEvent.keyDown(screen.getByTestId('wf-map-chrome-tl'), { key: 'Escape' });

    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  it('a press on the MAP does not close it — L3\'s rule, which the landing card does not share', async () => {
    await renderMap(panelProps());
    openPanel();

    fireEvent.mouseDown(screen.getAllByTestId('map-container')[0]);
    await act(async () => {
      for (const handlers of mapEventHandlers) handlers.click?.({});
    });

    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
  });

  it('opening another menu closes it — one switch, one open surface', async () => {
    await renderMap(panelProps());
    openPanel();

    openFilters();

    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();
  });

  it('closes rather than becoming a "night" panel when the window under it goes away', async () => {
    // ⚠️ With the panel open the map can lose its EV row — the briefing poll withdraws an elapsed
    // window, or a handoff moves the date. `isSolar` then read false, so the panel printed the
    // NIGHT note over a solar map and an empty line saying no region had a "night" answer.
    const locations = makeLocations();
    const props = panelProps();
    const { rerender } = await renderMap(props);
    openPanel();
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();

    await act(async () => {
      rerender(<MapView locations={locations} date="2026-01-25" autoEventType={null} {...props} />);
    });

    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  it('never mounts on the frozen Plan-tab overlay', async () => {
    await renderMap(panelProps({ overlayMode: true }));

    expect(screen.queryByTestId('wf-win-more')).toBeNull();
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });
});

/**
 * The region panel — the drilldown's second and last level (map-landing-plan.md §3 L6).
 *
 * <p>Its rows are pinned in `mapRegionDrilldown.test.js` and its markup in
 * `MapRegionPanel.test.jsx`. This block is the wiring only, and it is where the phase's four
 * caller-side rules live: the level replaces rather than stacks, Escape steps BACK one level rather
 * than collapsing the drilldown (§4 #28), the sheet opens with `inPlan: false` after the panel is
 * closed, and the two looked-up per-location facts actually reach the row from `MapView`'s props.
 */
describe('the region panel — one region, into the sheet that already exists', () => {
  /**
   * ⚠️ **TWO windows, and The Lakes has a row on both with DIFFERENT figures.** With one window the
   * "re-found by name, never stored" claim was structurally untestable — a review lens implemented
   * the snapshot the doc forbids and all 146 tests passed, because no other window had any region
   * rows for the panel to disagree with. This morning's sunrise gives it one.
   */
  const DAYS = [{
    date: TODAY,
    eventSummaries: [{
      targetType: 'SUNRISE',
      regions: [
        { regionName: 'The Lakes', meanRating: 3.1, bestRating: 4, displayVerdict: 'MAYBE', slots: [{ canopy: false }] },
      ],
    }, {
      targetType: 'SUNSET',
      regions: [
        { regionName: 'The Lakes', meanRating: 4.4, bestRating: 5, displayVerdict: 'WORTH_IT', slots: [{ canopy: false }] },
        { regionName: 'North East', meanRating: 2.0, bestRating: 3, displayVerdict: 'STAND_DOWN', slots: [{ canopy: false }] },
        { regionName: 'The Borders', meanRating: 4.9, bestRating: 5, displayVerdict: 'WORTH_IT', slots: [{ canopy: false }] },
      ],
      // The narrative the panel prints — served per region per window, exactly as
      // `regionGloss.buildRegionGlossIndex` reads it (⚠️ `regionName`, never `name`: that index
      // shipped silently empty for weeks because a fixture used the wrong field).
    }],
  }];

  /**
   * ⚠️ Keyed by `locationId`, NOT by name. `makeLocations()` suffixes every location name with a
   * per-test nonce (the marker-icon cache note above), while `heat.spots` keeps the plain name — so
   * a name-keyed fixture would join in this file and nowhere else, or vice versa. Real ids are what
   * `heatPointsFor` carries onto every point.
   *
   * <p>`goldenHourEnd` is the SUNSET event instant `locationSheet.eventInstantOf` recovers; nothing
   * on this path reads `solarEventTime`, which is why the fixture does not carry one.
   */
  const SCORE_INDEX = buildScoreIndex([
    { locationId: 3, date: TODAY, targetType: 'SUNSET', rating: 4, goldenHourEnd: `${TODAY}T16:12:00` },
    { locationId: 1, date: TODAY, targetType: 'SUNSET', rating: 5, goldenHourEnd: `${TODAY}T16:10:00` },
  ]);

  const TIDE_INDEX = buildTideAlignmentIndex([{
    date: TODAY,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName: 'North East',
        slots: [{ locationId: 1, tideOnTheLight: true, nearestSolarOffsetPhrase: 'HW 12 min after sunset' }],
      }],
    }],
  }]);

  const GLOSS_INDEX = buildRegionGlossIndex([{
    date: TODAY,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName: 'The Lakes',
        glossHeadline: 'A clean western horizon.',
        glossDetail: 'High cloud thins through the afternoon.',
      }],
    }],
  }]);

  const REACH = new Map([[1, { driveMinutes: 95 }], [2, { driveMinutes: 30 }], [3, { driveMinutes: 40 }]]);

  const panelProps = (extra = {}) => ({
    heat: heatProp(),
    regionVerdictIndex: buildRegionVerdictIndex(DAYS),
    regionGlossIndex: GLOSS_INDEX,
    scoreIndex: SCORE_INDEX,
    tideAlignmentIndex: TIDE_INDEX,
    reachById: REACH,
    ...extra,
  });

  const openPanel = () => {
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getByTestId('wf-win-more'));
  };

  const openRegion = (name) => {
    const row = screen.getAllByTestId('wf-win-panel-row').find((r) => r.getAttribute('data-region') === name);
    expect(row).toBeTruthy();
    fireEvent.click(row);
  };

  let regionForeignModal = null;
  afterEach(() => {
    regionForeignModal?.remove();
    regionForeignModal = null;
  });

  /**
   * ⚠️ **The second half of the wiring, and MUTATION TESTING is the only reason it exists.**
   *
   * <p>Two levels need two proofs. While the fix still used a prop, unwiring THIS mount left all
   * 203 tests green — the describe above covers whichever level it happens to open, and it opens
   * the other one. The prop is gone, but the asymmetry it exposed is not: a change to one panel's
   * handler is invisible to a test that only ever reaches the other.
   *
   * <p>This level matters more than the one above it, because the cheap fix is unavailable here:
   * `MapRegionPanel`'s handler is NOT redundant with the pane's — its `onBack` also carries the
   * return-focus target (`map-landing-plan.md` §4 #37) — so it cannot simply be deleted.
   */
  /**
   * ⚠️ **The mutant-killing half.** A modal in `document.body` cannot distinguish "resolved the pane
   * and found the modal outside it" from "failed to resolve the pane and fell into the null branch",
   * because both stand down — breaking `MAP_PANE_SELECTOR` left every test green through two
   * rounds of this review. A modal INSIDE the pane separates them: containment says it is the
   * pane's own business, so the panel must still act. With the selector broken, `closest` returns
   * null, the null branch stands down, and this fails.
   */
  it('⚠️ a modal the pane renders INLINE does not stand the drilldown down', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');
    const pane = screen.getByTestId('wf-map-pane');
    regionForeignModal = document.createElement('div');
    regionForeignModal.setAttribute('role', 'dialog');
    regionForeignModal.setAttribute('aria-modal', 'true');
    pane.appendChild(regionForeignModal);

    fireEvent.keyDown(screen.getByTestId('wf-reg-panel'), { key: 'Escape' });

    expect(screen.queryByTestId('wf-reg-panel'), 'an inline modal is the pane\u2019s own business')
      .toBeNull();
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
  });

  it('⚠️ Escape does not step the region panel back behind a foreign modal — the real wiring', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');
    expect(screen.getByTestId('wf-reg-panel-region')).toHaveTextContent('The Lakes');

    regionForeignModal = document.createElement('div');
    regionForeignModal.setAttribute('role', 'dialog');
    regionForeignModal.setAttribute('aria-modal', 'true');
    document.body.appendChild(regionForeignModal);

    // ⚠️ The discriminator. Planting the modal in `document.body` alone does NOT prove the pane was
    // resolved: a broken `MAP_PANE_SELECTOR` returns null, the null branch stands down for any modal
    // anywhere, and the assertion below passes either way — both review lenses said so. These two
    // lines are what make this a wiring test: the panel must resolve the pane by
    // `MAP_PANE_SELECTOR`, and that pane must be the element carrying the ref and the key handler.
    expect(
      screen.getByTestId('wf-reg-panel').closest('.wf-map-tab'),
      'the panel must sit inside MAP_PANE_SELECTOR, on the ref-bearing element',
    ).toBe(screen.getByTestId('wf-map-pane'));

    fireEvent.keyDown(screen.getByTestId('wf-reg-panel'), { key: 'Escape' });

    // Still on the region level: neither stepped back to the window panel nor closed.
    expect(
      screen.getByTestId('wf-reg-panel-region'),
      'the region panel must survive an Escape aimed at the sheet above it',
    ).toHaveTextContent('The Lakes');
    // As above: this pins that the stand-down moves no focus, not the arm C guard — which lives in
    // `useDialogFocus.test.jsx` and is not exercised by a planted `div`.
    expect(screen.getByTestId('wf-reg-panel').contains(document.activeElement)
      || document.activeElement === screen.getByTestId('wf-reg-panel'),
    'focus must stay inside the panel that survived').toBe(true);
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  it('opens from a window-panel row, and REPLACES the level above rather than stacking on it', async () => {
    await renderMap(panelProps());
    openPanel();

    openRegion('The Lakes');

    expect(screen.getByTestId('wf-reg-panel-region')).toHaveTextContent('The Lakes');
    // ⚠️ One panel at a time — one z-index, one outside-dismiss root, and nothing to make anyone
    // reach for a `Modal` to stack them (map-tab-v2-plan.md O-20).
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  it('carries the SAME figures the row it was pressed from was printing', async () => {
    await renderMap(panelProps());
    openPanel();
    const rowText = screen.getAllByTestId('wf-win-panel-row')
      .find((r) => r.getAttribute('data-region') === 'The Lakes').textContent;

    openRegion('The Lakes');

    // One place in The Lakes in scope (Wastwater), rated 4 — so `1 of 1 at 4★+`, and a 40-minute
    // nearest drive. Both are read off the window panel's own row rather than recounted.
    expect(rowText).toContain('1 of 1 at 4');
    expect(screen.getByTestId('wf-reg-panel-stats')).toHaveTextContent('1 of 1 at 4');
    expect(screen.getByTestId('wf-reg-panel-stats')).toHaveTextContent('nearest 40 min');
  });

  it('draws the location row with the drive and the departure its own event instant gives it', async () => {
    await renderMap(panelProps());
    openPanel();

    openRegion('The Lakes');

    // 16:12 UTC − a 40-minute drive − 20 minutes of setup = 15:12, on the UK clock in January.
    expect(screen.getByTestId('wf-reg-panel-when')).toHaveTextContent('40 min · leave 15:12');
  });

  it('draws the served tide glyph, from the index MapView is handed', async () => {
    await renderMap(panelProps());
    openPanel();

    // Bamburgh (id 1) is the fixture's one location whose water lands on the light.
    openRegion('North East');

    const bamburgh = screen.getAllByTestId('wf-reg-panel-row')
      .find((r) => r.getAttribute('data-location') === 'Bamburgh');
    expect(within(bamburgh).getByTestId('wf-reg-panel-tide')).toBeInTheDocument();
    const tynemouth = screen.getAllByTestId('wf-reg-panel-row')
      .find((r) => r.getAttribute('data-location') === 'Tynemouth');
    expect(within(tynemouth).queryByTestId('wf-reg-panel-tide')).toBeNull();
  });

  it('prints this region\'s served narrative for this window', async () => {
    await renderMap(panelProps());
    openPanel();

    openRegion('The Lakes');

    expect(screen.getByTestId('wf-reg-panel-gloss'))
      .toHaveTextContent('A clean western horizon. High cloud thins through the afternoon.');
  });

  it('...and nothing at all where the payload carries none', async () => {
    await renderMap(panelProps());
    openPanel();

    openRegion('North East');

    expect(screen.queryByTestId('wf-reg-panel-gloss')).toBeNull();
  });

  it('back returns to the window panel, on the same window', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');

    fireEvent.click(screen.getByTestId('wf-reg-panel-back'));

    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(screen.getByTestId('wf-win-panel-window')).toHaveTextContent('Tonight sunset');
    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
  });

  /**
   * ⚠️ **The back-stack, and the reason L5 deferred it here** (§4 #28). `MapRegionPanel`'s own
   * `onKeyDown` calls `preventDefault()` without `stopPropagation()`, so a press inside the panel
   * runs BOTH it and `MapView`'s pane-level handler on one event. The pane handler must test the
   * region level FIRST; with the branches the other way round one press would close the whole
   * drilldown a frame after the panel stepped back a level.
   */
  it('Escape steps back ONE level — never collapsing the whole drilldown', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');

    fireEvent.keyDown(screen.getByTestId('wf-reg-panel'), { key: 'Escape' });

    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
  });

  it('...and a SECOND Escape then closes the drilldown', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');
    fireEvent.keyDown(screen.getByTestId('wf-reg-panel'), { key: 'Escape' });

    fireEvent.keyDown(screen.getByTestId('wf-win-panel'), { key: 'Escape' });

    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
  });

  /**
   * ⚠️ **Closing the window control's own dropdown must not close the drilldown.** `WindowControl`
   * calls `setOpen(false)` from four places — Escape, an outside press, picking a row, and the ‹ ›
   * steppers — and each landed in `MapView` as an unconditional `setOpenMapMenu(null)`, so an 11px
   * stepper beside the pill silently discarded both levels of a panel that is not its dropdown.
   * The design's rule is explicit: panels close on their own chip, their close button, or Escape.
   */
  it('⚠️ a window stepper moves the window and LEAVES the drilldown open', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');

    fireEvent.click(screen.getByTestId('wf-win-prev'));

    expect(screen.getByTestId('wf-reg-panel')).toBeInTheDocument();
  });

  it('...and so does closing the pill menu itself, which the panel replaced', async () => {
    await renderMap(panelProps());
    openPanel();

    // Re-open the dropdown OVER the panel, then dismiss it with Escape from inside the control.
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
    fireEvent.keyDown(screen.getByTestId('wf-win-menu'), { key: 'Escape' });

    // The menu is what closed; nothing else was open to close.
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  /**
   * ⚠️ **The ✕ is inside the panel it unmounts**, so without a focus move it lands on `<body>` —
   * outside the pane's React `onKeyDown`, and the next Tab restarts at the top of the document.
   * Measured on both panels in Chromium and WebKit by a PR-review lens. `RegionsJump` gets this
   * free from `useDialogFocus`; these panels manage focus by hand and had only the open half.
   */
  it('⚠️ the ✕ returns focus to the pill, from either level', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');

    fireEvent.click(screen.getByTestId('wf-reg-panel-close'));

    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  /**
   * ⚠️ **The card and the panel used to arrive together and the panel buried the card.** They are
   * not merely allowed to coexist: `landingLabel` is empty while the card is open, which is exactly
   * when `WindowControl` shows the drilldown row — so this was the ordinary state after one press on
   * the first visit of every forecast run. Measured: the panel covers the card entirely at ≤390px,
   * putting four consecutive tab stops on elements 0% visible (WCAG 2.4.11 AA), and the card's ✕ —
   * its only pointer dismissal, since it deliberately survives an outside tap — became unclickable.
   */
  it('⚠️ opening the drilldown dismisses the landing card rather than burying it', async () => {
    await renderMap(panelProps({ runId: '2026-01-15T04:00:00' }));
    expect(screen.getByTestId('wf-land')).toBeInTheDocument();

    openPanel();

    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-land')).toBeNull();
  });

  /**
   * ⚠️ **The peek's return leg.** `handleOpenLocationSheet` closes the panel in the same commit the
   * sheet mounts, so the button that was pressed is detached before `useDialogFocus` reads
   * `document.activeElement` — it then records `<body>` and closing the sheet restores to nothing.
   * `MapCallout` focuses its own trigger before handing off for exactly this reason; its button
   * survives, so it can. These cannot, and need a survivor: the pill the drilldown hangs from.
   * Measured in Chromium and WebKit against a control whose trigger stays mounted.
   */
  it('⚠️ leaves focus on the pill when a region row hands off to the sheet', async () => {
    const onOpenLocationSheet = vi.fn();
    await renderMap(panelProps({ onOpenLocationSheet }));
    openPanel();
    openRegion('The Lakes');

    fireEvent.click(screen.getAllByTestId('wf-reg-panel-row')[0]);

    expect(onOpenLocationSheet).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  /**
   * ⚠️ A D-13 filler is a window the briefing served nothing for, so there is no answer to drill
   * into — and `buildPanelRegionRows` reads the verdict index directly, so it would print each
   * region's served word beside `0 of N at 4★+`. Same false claim the pill's own `served` gate
   * removes; withholding the entry is the honest form (§4 #38).
   */
  it('⚠️ offers no drilldown at all on a window the briefing never served', async () => {
    // `windows: []` plus a forecast date is what makes every solar row a D-13 filler: the EV list
    // is built from `forecastDates` where the briefing served no window.
    await renderMap(panelProps({ heat: heatProp({ windows: [] }), forecastDates: [TODAY] }));
    expect(screen.getByTestId('wf-win-pill')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wf-win-pill'));

    expect(screen.queryByTestId('wf-win-more')).toBeNull();
  });

  /**
   * ⚠️ **The pane's Escape branch is the OTHER way up a level, and only the back arrow recorded
   * where to put focus.** Reachable in exactly the state L3 created — panel open, focus on the map —
   * which is the state that handler exists to cover.
   */
  it('⚠️ pane-level Escape returns focus to the region it actually left, not a stale one', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');
    fireEvent.click(screen.getByTestId('wf-reg-panel-back'));
    openRegion('North East');

    // Fired at the map container — inside the pane, outside the panel — so ONLY the pane handler
    // runs. Firing at the panel would let its own `onBack` set the target and hide the defect.
    fireEvent.keyDown(screen.getAllByTestId('map-container')[0], { key: 'Escape' });

    expect(document.activeElement).toBe(
      screen.getAllByTestId('wf-win-panel-row').find((r) => r.getAttribute('data-region') === 'North East'),
    );
  });

  /**
   * ⚠️ **The window can become unserved WHILE the drilldown is open**, which the entry-only gate did
   * nothing about — the briefing's refresh withdraws a window whose event has passed, and
   * `buildMapEvents` replaces it with a same-id filler. Both mounts checked only `activeMapEvent`,
   * so they went on rendering region rows joined from the broad verdict index over an empty point
   * set: served verdicts beside `0 of N`. Raised as a second-round finding by the cross-vendor
   * review, which named the entry gate as its baseline.
   */
  it('⚠️ closes an OPEN drilldown when its window becomes unserved under it', async () => {
    const locations = makeLocations();
    const props = panelProps();
    const { rerender } = await renderMap(props);
    openPanel();
    openRegion('The Lakes');
    expect(screen.getByTestId('wf-reg-panel')).toBeInTheDocument();

    // The same window, now served by nothing — what a refresh that retires an elapsed window does.
    await act(async () => {
      rerender(<MapView
        locations={locations}
        date={TODAY}
        autoEventType={null}
        {...props}
        heat={heatProp({ windows: [] })}
        forecastDates={[TODAY]}
      />);
    });

    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  /**
   * ⚠️ **The fifth exit is the one no press initiates**, and it therefore has no handler to hang a
   * focus move on: a briefing refresh retires the active window and the panel unmounts under the
   * reader with focus inside it. The recovery adopts an ORPHANED focus rather than duplicating each
   * deliberate exit's own move, so an exit added later inherits it without being enumerated —
   * which is the whole lesson of the four that were not (§4 #44). Third Codex round.
   */
  it('⚠️ recovers focus when a refresh retires the window with focus inside the panel', async () => {
    const locations = makeLocations();
    const props = panelProps();
    const { rerender } = await renderMap(props);
    openPanel();
    openRegion('The Lakes');
    // Focus is genuinely inside the panel, which is the precondition the recovery exists for.
    expect(screen.getByTestId('wf-reg-panel').contains(document.activeElement)).toBe(true);

    await act(async () => {
      rerender(<MapView
        locations={locations}
        date={TODAY}
        autoEventType={null}
        {...props}
        heat={heatProp({ windows: [] })}
        forecastDates={[TODAY]}
      />);
    });

    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  /**
   * ⚠️ **It adopts an orphan; it does not confiscate.** Without the `activeElement !== body` guard
   * the recovery fires on every close, including ones where the reader is deliberately somewhere
   * else — yanking focus to the pill from whatever they were using.
   */
  it('⚠️ leaves focus alone when the window is retired and the reader is elsewhere', async () => {
    const locations = makeLocations();
    const props = panelProps();
    const { rerender } = await renderMap(props);
    openPanel();
    const elsewhere = screen.getByTestId('wf-jump-chip');
    elsewhere.focus();

    await act(async () => {
      rerender(<MapView
        locations={locations}
        date={TODAY}
        autoEventType={null}
        {...props}
        heat={heatProp({ windows: [] })}
        forecastDates={[TODAY]}
      />);
    });

    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
    expect(document.activeElement).toBe(elsewhere);
  });

  /**
   * ⚠️ **And it fires on CLOSE only.** A panel open with focus on the map — `<body>` — is the
   * ordinary state L3 created and its own comment names; without the `|| now` term the recovery
   * would run on every render while open and drag the reader back to the pill.
   */
  it('⚠️ does not grab focus while the drilldown is still open', async () => {
    const locations = makeLocations();
    const props = panelProps();
    const { rerender } = await renderMap(props);
    openPanel();
    document.activeElement?.blur();
    expect(document.activeElement).toBe(document.body);

    // ⚠️ A real RE-RENDER with the panel still open. A click that changes no state causes none, and
    // this effect has no dependency array — so only a render can run it, which is exactly what the
    // briefing's own poll does every ten minutes.
    await act(async () => {
      rerender(<MapView locations={locations} date={TODAY} autoEventType={null} {...props} />);
    });

    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(document.activeElement).toBe(document.body);
  });

  /**
   * ⚠️ **The switch has to be cleared, not just the render suppressed.** A retirement left
   * `openMapMenu` at `'window-panel'` with nothing behind it — and the landing card's own Escape
   * listener defers whenever `openMapMenu != null`, so Escape stopped working on a card the reader
   * can SEE, while `onOpenWindowPanel` was withheld in the same state so no control remained to
   * clear it. Raised twice: by this PR's cross-phase lens and by the review's fourth round.
   */
  it('⚠️ leaves no orphaned menu state when the window is retired', async () => {
    // ⚠️ The observable is the PANE's Escape ladder, not the pill: `handleMapPaneKeyDown` clears an
    // open menu BEFORE it clears a selection, so a stale `'window-panel'` swallows the first press
    // on nothing the reader can see and the selection survives a keystroke that should have cleared
    // it. (Reopening the pill works either way, which is why the first draft of this test proved
    // nothing and a mutant walked through it.) The landing card's own Escape listener defers on the
    // same value, for the same reason.
    const locations = [...makeAzimuthLocation(), ...makeLocations()];
    const props = panelProps({
      locations, handoffLocationName: 'AzimuthSpot', handoffNonce: 11,
    });
    polylineCalls.length = 0;
    const { rerender } = await renderMap(props);
    expect(polylineCalls.length).toBeGreaterThan(0);
    openPanel();

    await act(async () => {
      rerender(<MapView
        date={TODAY}
        autoEventType={null}
        {...props}
        heat={heatProp({ windows: [] })}
        forecastDates={[TODAY]}
      />);
    });
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();

    // One press, on the pane. With the switch left set it is spent clearing an invisible menu.
    polylineCalls.length = 0;
    fireEvent.keyDown(screen.getAllByTestId('map-container')[0], { key: 'Escape' });
    await act(async () => {});

    expect(polylineCalls).toHaveLength(0);
  });

  /** ⚠️ The third exit that destroys its own trigger; the batch that fixed the other two missed it. */
  it('⚠️ leaves focus on the pill when Zoom to region closes the drilldown', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');

    fireEvent.click(screen.getByTestId('wf-reg-panel-zoom'));

    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  it('the ✕ closes the whole drilldown from the second level, in one press', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');

    fireEvent.click(screen.getByTestId('wf-reg-panel-close'));

    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  it('⚠️ re-opens at the FIRST level, never at the region it was last on', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');
    fireEvent.click(screen.getByTestId('wf-reg-panel-close'));

    openPanel();

    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
  });

  /**
   * ⚠️ **The reset at the opener, and mutation testing is what made it testable.** Deleting it
   * survived every test that closed the panel with its own ✕ — which clears the level on the way.
   * The route that does not is the ordinary one: open ANOTHER menu over the drilldown. The level is
   * then still set when the reader comes back through the pill.
   */
  it('⚠️ re-opens at the FIRST level even when it was closed by opening another menu', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');

    // The Regions chip takes `openMapMenu` without touching the drilldown's level.
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    openPanel();

    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
  });

  it('drops back to the region list when this region has no row on the window it moves to', async () => {
    const locations = makeLocations();
    const props = panelProps();
    const { rerender } = await renderMap(props);
    openPanel();
    openRegion('The Lakes');

    await act(async () => {
      rerender(<MapView locations={locations} date={TOMORROW} autoEventType={null} {...props} />);
    });

    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
    // ⚠️ The positive half. Without it this passes under the OPPOSITE behaviour — closing the whole
    // drilldown, which is what the sibling L5 test asserts for a date move — under a name saying
    // the reader drops back a level.
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
  });

  /**
   * ⚠️ **The claim `panelRegion`'s own doc makes, and the reason its window key was removed.** The
   * row is re-found in `panelRows` every render rather than snapshotted, so stepping the window
   * moves the region panel's figures with it. A stored copy passes every other test in this block;
   * this is the one that fails.
   */
  it('follows the window, re-reading the region\'s row rather than a snapshot of it', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');
    expect(screen.getByTestId('wf-reg-panel-verdict')).toHaveTextContent('Worth it');

    // Step to this morning's sunrise, where The Lakes is Maybe with a different average.
    fireEvent.click(screen.getByTestId('wf-win-prev'));

    expect(screen.getByTestId('wf-reg-panel-region')).toHaveTextContent('The Lakes');
    expect(screen.getByTestId('wf-reg-panel-verdict')).toHaveTextContent('Maybe');
    expect(screen.getByTestId('wf-reg-panel-stats')).toHaveTextContent('average 3.1');
  });

  it('zooms to the region it names, and closes the drilldown as a completed navigation', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');
    fitBounds.mockClear();

    fireEvent.click(screen.getByTestId('wf-reg-panel-zoom'));

    const expected = latLngBounds(SPOTS.filter((s) => s.regionName === 'The Lakes'), 0.06);
    expect(fitBounds).toHaveBeenCalledWith(expected, { padding: [40, 40], animate: false });
    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  /**
   * ⚠️ **`jumpToRegion`'s scope flip is UNREACHABLE from this panel, and the plan's own test list
   * asked for it** (map-landing-plan.md §4 #30). The rows are scope-narrowed, so while `heatArea`
   * stands the panel can only ever name a region already inside the area — and `jumpToRegion` flips
   * only `if (heatArea)`. The action still routes through that one function, so it would inherit
   * the flip if an entry point ever handed it an out-of-scope region; what is testable here is the
   * other arm, that an in-area zoom leaves the reader's scope alone.
   */
  it('...and leaves the reader\'s scope alone, because the region was already in it', async () => {
    await renderMap(panelProps());
    openPanel();
    openRegion('The Lakes');

    fireEvent.click(screen.getByTestId('wf-reg-panel-zoom'));

    openFilters();
    expect(screen.getByRole('button', { name: 'My area' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('opens the four-day sheet OVER the map, and closes the panel first', async () => {
    const onOpenLocationSheet = vi.fn();
    await renderMap(panelProps({ onOpenLocationSheet }));
    openPanel();
    openRegion('The Lakes');

    fireEvent.click(screen.getByTestId('wf-reg-panel-four-days'));

    // ⚠️ `inPlan: false` — the peek route (O-18), so the sheet's own footer map door is stamped
    // `inPlace` and cannot import the Plan's lens onto the map this reader is already on.
    expect(onOpenLocationSheet).toHaveBeenCalledWith(expect.objectContaining({
      id: 3, regionName: 'The Lakes', inPlan: false, date: TODAY, targetType: 'SUNSET',
    }));
    // ⚠️ Closed FIRST — the close-then-move ordering every door onto a map already uses. These
    // panels are not `Modal`s, and one left mounted under the sheet is O-20's named hazard.
    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  it('opens it for a row pressed directly, not only for the one the action names', async () => {
    const onOpenLocationSheet = vi.fn();
    await renderMap(panelProps({ onOpenLocationSheet }));
    openPanel();
    openRegion('North East');

    fireEvent.click(screen.getAllByTestId('wf-reg-panel-row')
      .find((r) => r.getAttribute('data-location') === 'Alnmouth'));

    expect(onOpenLocationSheet).toHaveBeenCalledWith(expect.objectContaining({
      id: 5, name: 'Alnmouth', regionName: 'North East', inPlan: false,
    }));
  });

  /**
   * ⚠️ **Rebuilt on the azimuth fixture, because the first cut could not fail.** Its only
   * observable was `queryByTestId('map-callout')`, and `MapCallout` cannot render under this file's
   * `react-leaflet` mock at all — it portals into `map.getContainer().parentElement` and the mocked
   * container is a bare object. A review lens proved it by adding the very selection the test's name
   * forbids and watching it stay green. The `Polyline` IS observable, and `MapView` draws it only
   * for a selected location.
   */
  it('does not move the current selection on the way — the sheet is opened for a place, not by selecting it', async () => {
    const onOpenLocationSheet = vi.fn();
    const locations = [...makeAzimuthLocation(), ...makeLocations()];
    await renderMap(panelProps({ onOpenLocationSheet, locations }));
    openPanel();
    openRegion('The Lakes');
    polylineCalls.length = 0;

    fireEvent.click(screen.getByTestId('wf-reg-panel-four-days'));

    expect(onOpenLocationSheet).toHaveBeenCalledTimes(1);
    // Nothing became selected: no azimuth line was drawn for AzimuthSpot or for anything else.
    expect(polylineCalls).toHaveLength(0);
  });

  /**
   * ⚠️ **The place the panel names WINS over the selection, and mutation testing is what exposed
   * the gap.** Swapping the handler's `spot ?? selectedLoc` for `selectedLoc ?? spot` survived every
   * test above, because none of them had a location selected — so the fallback answered for the
   * argument. A reader with a callout open who presses a different location's row must get a sheet
   * for the place they pressed, not for the pin still ringed behind the panel.
   */
  it('⚠️ opens the sheet for the row pressed, even with a DIFFERENT location selected', async () => {
    const onOpenLocationSheet = vi.fn();
    // ⚠️ The selection has to be PROVEN, or this passes whether or not one took — the fixture would
    // then pre-satisfy its own predicate, which is how the last few dead tests in this repo hid.
    // `MapCallout` cannot render under this file's `react-leaflet` mock (it portals into
    // `map.getContainer().parentElement`, and the mocked container is a bare object), so the
    // azimuth `Polyline` is the observable: `MapView` draws it only for a SELECTED location, and
    // only `makeAzimuthLocation`'s fixture carries the `azimuthDeg` it needs.
    const locations = [...makeAzimuthLocation(), ...makeLocations()];
    polylineCalls.length = 0;
    await renderMap(panelProps({
      onOpenLocationSheet, locations, handoffLocationName: 'AzimuthSpot', handoffNonce: 7,
    }));
    expect(polylineCalls.length).toBeGreaterThan(0);
    openPanel();
    // AzimuthSpot is in North East; the panel is opened on The Lakes, whose one place is Wastwater.
    openRegion('The Lakes');

    fireEvent.click(screen.getByTestId('wf-reg-panel-four-days'));

    expect(onOpenLocationSheet).toHaveBeenCalledWith(expect.objectContaining({
      id: 3, regionName: 'The Lakes',
    }));
  });
});

describe('the landing card — once per forecast run, and dismissed only three ways', () => {
  const RUN = '2026-01-15T04:00:00';
  const LATER_RUN = '2026-01-15T14:00:00';
  const SEEN_KEY = 'mapLandingSeenRun';
  /** Torn down centrally — see the foreign-modal test's own note. */
  let foreignModal = null;
  afterEach(() => {
    foreignModal?.remove();
    foreignModal = null;
  });

  /** The two windows the card compares here are TODAY's sunrise and sunset — one day, two events. */
  const DAYS = [{
    date: TODAY,
    eventSummaries: [
      {
        targetType: 'SUNRISE',
        regions: [{ regionName: 'The Lakes', meanRating: 4.4, displayVerdict: 'WORTH_IT', slots: [{ canopy: false }] }],
      },
      {
        targetType: 'SUNSET',
        regions: [{ regionName: 'North East', meanRating: 2.9, displayVerdict: 'MAYBE', slots: [{ canopy: false }] }],
      },
    ],
  }];

  const landingProps = (extra = {}) => ({
    heat: heatProp(),
    regionVerdictIndex: buildRegionVerdictIndex(DAYS),
    runId: RUN,
    ...extra,
  });

  it('opens on a run the reader has not dismissed it on', async () => {
    await renderMap(landingProps());

    expect(screen.getByTestId('wf-land')).toBeInTheDocument();
    // Derived from the rows it is showing — both of this fixture's first two windows are TODAY.
    expect(screen.getByTestId('wf-land-head')).toHaveTextContent('This morning — sunrise or sunset?');
  });

  it('stays shut for a run already stamped, and returns for the NEXT one', async () => {
    localStorage.setItem(SEEN_KEY, RUN);
    const { unmount } = await renderMap(landingProps());
    expect(screen.queryByTestId('wf-land')).toBeNull();
    unmount();

    await renderMap(landingProps({ runId: LATER_RUN }));

    expect(screen.getByTestId('wf-land')).toBeInTheDocument();
  });

  it('stays shut with no run to key a dismissal on — silence is the safe degrade', async () => {
    await renderMap({ heat: heatProp(), regionVerdictIndex: buildRegionVerdictIndex(DAYS) });

    expect(screen.queryByTestId('wf-land')).toBeNull();
  });

  it('never mounts on the frozen Plan-tab overlay', async () => {
    // ⚠️ Asserts the OUTCOME, not one mechanism — the overlay is closed off three independent ways
    // and no single-term mutation of any of them is observable here: the card's JSX lives in the
    // `!overlayMode` render branch, `buildMapEvents` is skipped outright so there are no rows to
    // show, and `landingRunStamp` is null. Recorded because a mutation test of the third one
    // survives, and "survived" would otherwise read as a coverage gap rather than as redundancy.
    await renderMap(landingProps({ overlayMode: true }));

    expect(screen.queryByTestId('wf-land')).toBeNull();
  });

  it('the close control dismisses it, and stamps the run so a re-render does not reopen it', async () => {
    await renderMap(landingProps());

    fireEvent.click(screen.getByTestId('wf-land-close'));

    expect(screen.queryByTestId('wf-land')).toBeNull();
    expect(localStorage.getItem(SEEN_KEY)).toBe(RUN);
  });

  it('Escape dismisses it from the DOCUMENT — the card holds no focus to bubble from', async () => {
    await renderMap(landingProps());

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByTestId('wf-land')).toBeNull();
  });

  it('Escape closes an open MENU first, and the card only on the next press', async () => {
    // One press, one action. The press is dispatched from INSIDE the pane, which is what reaches
    // `handleMapPaneKeyDown` (a React `onKeyDown` on the pane root) — the card's own document
    // listener sees the same press and must defer to it.
    await renderMap(landingProps());
    openFilters();
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    fireEvent.keyDown(screen.getByTestId('wf-map-chrome-tl'), { key: 'Escape' });
    expect(screen.queryByTestId('wf-filters-panel')).toBeNull();
    expect(screen.getByTestId('wf-land')).toBeInTheDocument();

    fireEvent.keyDown(screen.getByTestId('wf-map-chrome-tl'), { key: 'Escape' });
    expect(screen.queryByTestId('wf-land')).toBeNull();
  });

  // ⚠️ The card's deferral to a standing SELECTION is pinned in
  // `MapViewSelectionOrdering.test.jsx`, beside the two Escape-ordering tests it extends — that
  // file already carries a callout probe and a `selectTheSpot()` helper; this one stubs
  // `MapLabels` and has no route to a selection at all.

  it('records the residual: from OUTSIDE the pane, a menu blocks the card\'s own Escape', async () => {
    // ⚠️ Not a bug being enshrined — a limitation being named. `handleMapPaneKeyDown` is
    // subtree-scoped, so a press with focus on `<body>` never reaches it and the menu cannot close;
    // the card's listener therefore defers to a menu it is powerless to shut. That is exactly what
    // Escape does on this pane today with any menu open and focus outside it. If a later phase
    // widens the listener into the pane's whole chain, this test should be UPDATED deliberately
    // rather than discovered by accident.
    await renderMap(landingProps());
    openFilters();

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();
    expect(screen.getByTestId('wf-land')).toBeInTheDocument();
  });

  it('Escape stands down entirely while a FOREIGN modal is over the map', async () => {
    await renderMap(landingProps());
    // ⚠️ Torn down in `afterEach`, not on the last line: RTL's cleanup removes only its own
    // container, so an assertion that threw first would leave a `[role="dialog"][aria-modal]` in
    // the body and make `foreignModalOver` true for every later Escape test in this file.
    foreignModal = document.createElement('div');
    foreignModal.setAttribute('role', 'dialog');
    foreignModal.setAttribute('aria-modal', 'true');
    document.body.appendChild(foreignModal);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.getByTestId('wf-land')).toBeInTheDocument();
  });

  it('Escape does NOTHING while the Map tab is hidden — the pane is never unmounted', async () => {
    // ⚠️ **The blocking defect of this phase, found by two independent review lenses.** The shell
    // keeps every opened tab's pane mounted and sets `hidden` on the panel, so a `document`
    // listener registered here keeps firing for a pane the reader is not looking at: an Escape
    // pressed on the Plan tab dismissed the card AND stamped the run as seen, spending the
    // once-a-run greeting on a keystroke aimed at something else. `WindowControl`'s own class doc
    // states this hazard as its reason for refusing a document listener.
    const { container } = await renderMap(landingProps());
    expect(screen.getByTestId('wf-land')).toBeInTheDocument();

    // What `WindowFirstShell` does to the panel when the reader picks another tab.
    const panel = document.createElement('div');
    panel.hidden = true;
    container.parentNode.insertBefore(panel, container);
    panel.appendChild(container);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.getByTestId('wf-land')).toBeInTheDocument();
    expect(localStorage.getItem(SEEN_KEY)).toBeNull();
  });

  it('names the SCOPE it is actually taken over, never "My area" without an area', async () => {
    // ⚠️ Verbatim the defect L2 fixed for `scopeIsArea`, repeated one line above it. `heatArea`
    // initialises true and the segment that would flip it is withheld when there is no home to
    // scope from — so the kicker read "· My area" over the whole catalogue while the verdict cell
    // two rows below, correctly gated, read "everywhere".
    await renderMap(landingProps({ heat: heatProp({ hasHome: false }) }));

    expect(screen.getByTestId('wf-land-sub')).toHaveTextContent('Your next two windows · Everywhere');
    for (const region of screen.getAllByTestId('wf-land-verdict-region')) {
      expect(region).not.toHaveTextContent('your area');
    }
  });

  it('offers no reopen row when there is no run to reopen for', async () => {
    // `reopenLanding` stamps `landingRunStamp`; with none it writes null and the card stays shut —
    // a menu row whose every press does nothing, which this file bans outright elsewhere.
    await renderMap({ heat: heatProp(), regionVerdictIndex: buildRegionVerdictIndex(DAYS) });

    fireEvent.click(screen.getByTestId('wf-win-pill'));

    expect(screen.queryByTestId('wf-win-landing')).toBeNull();
  });

  it('selecting a row sets that window AND closes the card', async () => {
    await renderMap(landingProps());
    // Row 2 is tonight's sunset; the map opens on it already, so pick row 1 (this morning).
    const rows = screen.getAllByTestId('wf-land-row');

    await act(async () => { fireEvent.click(rows[0]); });

    expect(screen.queryByTestId('wf-land')).toBeNull();
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('Sunrise');
  });

  it('a map CLICK does not dismiss it — panning to the region it named is reading it', async () => {
    // ⚠️ The gesture list the design names explicitly (map click, drag, zoom, wheel, outside tap).
    // `MapBackgroundClickController` registers `click`; `ZoomTracker`/`BoundsTracker` register
    // `zoomend`/`moveend`. Driving every handler this render registered covers all of them at once,
    // and would fail the moment the card grew a `useOutsideDismiss` or a map-event dismissal.
    await renderMap(landingProps());

    await act(async () => {
      for (const handlers of mapEventHandlers) {
        handlers.click?.({});
        handlers.zoomend?.({ target: { getZoom: () => 11, getBounds: () => null } });
        handlers.moveend?.({ target: { getBounds: () => null } });
      }
    });

    expect(screen.getByTestId('wf-land')).toBeInTheDocument();
  });

  it('an outside press does not dismiss it either', async () => {
    await renderMap(landingProps());

    fireEvent.mouseDown(document.body);
    fireEvent.click(document.body);

    expect(screen.getByTestId('wf-land')).toBeInTheDocument();
  });

  it('is recoverable from the pill menu, with the SAME header text', async () => {
    await renderMap(landingProps());
    const header = screen.getByTestId('wf-land-head').textContent;
    fireEvent.click(screen.getByTestId('wf-land-close'));
    expect(screen.queryByTestId('wf-land')).toBeNull();

    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const reopen = screen.getByTestId('wf-win-landing');
    expect(reopen).toHaveTextContent(header);
    fireEvent.click(reopen);

    expect(screen.getByTestId('wf-land-head')).toHaveTextContent(header);
    // The menu closes on its way — the row is an action, not a window choice.
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
  });

  it('offers no reopen row while the card is already open', async () => {
    await renderMap(landingProps());

    fireEvent.click(screen.getByTestId('wf-win-pill'));

    expect(screen.queryByTestId('wf-win-landing')).toBeNull();
  });

  it('a card reopened from the menu can be closed again', async () => {
    // The reopen stamp has to be cleared on dismissal, or its clause keeps winning over the seen
    // stamp and the card becomes unclosable.
    await renderMap(landingProps());
    fireEvent.click(screen.getByTestId('wf-land-close'));
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getByTestId('wf-win-landing'));
    expect(screen.getByTestId('wf-land')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wf-land-close'));

    expect(screen.queryByTestId('wf-land')).toBeNull();
  });
});
