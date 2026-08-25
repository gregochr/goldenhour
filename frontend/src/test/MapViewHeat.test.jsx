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
import { act, render, screen, fireEvent } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  // The real `createClusterIcon` runs in this file, and its answer IS the html — so the stub has to
  // hand it back rather than swallowing it.
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

const fitBounds = vi.fn();
const mapContainerProps = { last: null };
vi.mock('react-leaflet', () => ({
  MapContainer: (props) => {
    mapContainerProps.last = props;
    return <div data-testid="map-container">{props.children}</div>;
  },
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    fitBounds,
  }),
}));

const clusterIconCalls = [];
/** Mount count for the cluster group — a `key` change on the real component would remount it. */
const clusterGroupMounts = { count: 0 };
function MockMarkerClusterGroup({ children, iconCreateFunction }) {
  clusterIconCalls.push(iconCreateFunction);
  React.useEffect(() => { clusterGroupMounts.count += 1; }, []);
  return <div>{children}</div>;
}
vi.mock('react-leaflet-cluster', () => ({
  default: (props) => MockMarkerClusterGroup(props),
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
import { RAMP_STOPS } from '../utils/scoreRamp.js';
import { markerLabelAndColour } from '../components/markerUtils.js';

const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';

/** Four spots in two regions, one of them dark-sky and one of them out of reach. */
/**
 * Bortle 4 and 5 sit either side of `DARK_SKY_THRESHOLD`, and both are IN the planning area — a 5
 * that only the area filter excluded would leave `<= 5` undetectable. `Coquet` carries no measured
 * class at all, which is the "absence" boundary: `null <= 4` is true in JavaScript, so dropping the
 * null guard silently admits every unmeasured location to a dark-sky field.
 */
const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4 },
  { id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East', bortleClass: 6 },
  { id: 3, name: 'Wastwater', lat: 54.44, lng: -3.30, rid: 'The Lakes', bortleClass: 2 },
  { id: 4, name: 'Kelso', lat: 56.20, lng: -2.43, rid: 'The Borders', bortleClass: 5 },
  { id: 5, name: 'Alnmouth', lat: 55.39, lng: -1.61, rid: 'North East', bortleClass: 5 },
  { id: 6, name: 'Coquet', lat: 55.33, lng: -1.53, rid: 'North East', bortleClass: null },
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
  { key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE', label: 'Tomorrow sunrise', time: '08:24', bestRating: 1, conf: 0.72 },
  { key: `${TOMORROW}:SUNSET`, date: TOMORROW, targetType: 'SUNSET', label: 'Tomorrow sunset', time: '16:14', bestRating: null, conf: 0.72 },
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

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
  heatLayerProps.last = null;
  heatLayerProps.mounts = 0;
  mapContainerProps.last = null;
  clusterIconCalls.length = 0;
  markerCalls.length = 0;
  fitBounds.mockClear();
  markerNonce += 1;
  auroraStatus = null;
  role = 'PRO_USER';
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
    expect(screen.getByRole('combobox', { name: 'Forecast window' })).toHaveValue('');
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
    expect(screen.getByRole('button', { name: 'My area' })).toHaveAttribute('aria-pressed', 'true');
    // The unpressed halves too: inverting `aria-pressed={!heatArea}` is the classic copy-paste slip
    // in a segmented control, and asserting only the pressed one cannot see it.
    expect(screen.getByRole('button', { name: 'Whole catalogue' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'Medallions' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('group', { name: 'Map view' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Map area' })).toBeInTheDocument();
    expect(mapContainerProps.last.bounds).toBe(AREA_BOUNDS);
    expect(mapContainerProps.last.boundsOptions).toEqual({ padding: [28, 28] });
  });

  it('withdraws the field on Medallions and brings it back on Heat', async () => {
    await renderMap({ heat: heatProp() });
    expect(await screen.findByTestId('map-heat-layer')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Medallions' }));
    expect(screen.queryByTestId('map-heat-layer')).toBeNull();
    expect(screen.getByRole('button', { name: 'Medallions' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Heat' })).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(screen.getByRole('button', { name: 'Heat' }));
    expect(await screen.findByTestId('map-heat-layer')).toBeInTheDocument();
  });

  it('does not remount the marker/cluster layer on the Heat↔Medallions toggle, so an open popup survives it', async () => {
    // With one colour language, the toggle no longer changes which palette a cluster bubble paints
    // on — so the `key` that used to force a remount on every view switch is gone. Its only
    // remaining effect would have been tearing down (and losing) an open popup, a spiderfied
    // cluster and the selected marker on every press. Pinned here via mount count, since the
    // `Marker`/`Popup` mocks in this file are too shallow to assert an actual open popup surviving.
    clusterGroupMounts.count = 0;
    await renderMap({ heat: heatProp() });
    expect(clusterGroupMounts.count).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: 'Medallions' }));
    fireEvent.click(screen.getByRole('button', { name: 'Heat' }));
    fireEvent.click(screen.getByRole('button', { name: 'Medallions' }));

    // Exactly 1, not merely "still &gt;= 1": a monotonic counter alone can't tell "never remounted"
    // apart from "unmounted and never came back". The re-render check below rules out the latter —
    // the mock is still live and still being asked to build a cluster icon after the toggles.
    expect(clusterGroupMounts.count).toBe(1);
    expect(clusterIconCalls.length).toBeGreaterThan(1);
  });

  it('hides the ramp key in medallion view, where nothing is painted with it', async () => {
    await renderMap({ heat: heatProp() });
    // Named through its role: the gradient it explains is `aria-hidden`, so without a name of its
    // own a screen reader meets two loose adjectives with nothing saying what they qualify.
    expect(screen.getByRole('img', { name: /Poor to Worth it/ })).toBeInTheDocument();
    expect(screen.getByTestId('wf-map-heat-legend')).toHaveTextContent('Poor');
    fireEvent.click(screen.getByRole('button', { name: 'Medallions' }));
    expect(screen.queryByTestId('wf-map-heat-legend')).toBeNull();
  });

  it('drops the area segment entirely when no home is set (D6)', async () => {
    // With no postcode the planning area IS the whole roster, so both states frame the same box over
    // the same spots. §6 bans a control whose every press does nothing; the rest of the toolbar
    // stays, because the view toggle and the window selector still do something.
    await renderMap({ heat: heatProp({ hasHome: false }) });
    expect(screen.queryByRole('button', { name: 'My area' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Whole catalogue' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Heat' })).toBeInTheDocument();
  });

  it('frames the whole catalogue WITHOUT animating (the fitBounds-mid-paint trap)', async () => {
    // ⚠️ `{animate: false}` is the bundle's own trap, not a preference: a heavy field paint in the
    // same frame as an animated fitBounds forces layout mid-transition and strands Leaflet at the
    // old view — the labels change and the map never moves.
    await renderMap({ heat: heatProp() });
    fitBounds.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'Whole catalogue' }));
    expect(fitBounds).toHaveBeenCalledTimes(1);
    expect(fitBounds).toHaveBeenCalledWith(CATALOGUE_BOUNDS, { padding: [28, 28], animate: false });
  });

  it('frames the area again when the other segment is pressed', async () => {
    await renderMap({ heat: heatProp() });
    fireEvent.click(screen.getByRole('button', { name: 'Whole catalogue' }));
    fitBounds.mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'My area' }));
    expect(fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
  });

  it('re-centres on a second press of the segment already pressed', async () => {
    // This is what the nonce buys over keying on `heatArea` alone: after panning away, pressing the
    // segment you are already on takes you back. Without it the press is a no-op and the control
    // lies about what it does.
    await renderMap({ heat: heatProp() });
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
   * The Plan tab's unscored mark, adapted to a host that has no plate to hatch. On the Leaflet
   * side the field simply paints nothing and the markers keep full opacity (`MapHeatLayer`'s own
   * `fadesMarkers` rule), which is a reasonable fallback — but the ramp key stays up, explaining a
   * gradient nothing on screen carries, which is the exact thing that key's own rule forbids.
   */
  const unrated = (overrides = {}) => heatProp({ windows: WINDOWS_TONIGHT_UNRATED, ...overrides });

  it('names the unrated window and takes the ramp key down with it', async () => {
    await renderMap({ heat: unrated() });
    expect(screen.getByTestId('wf-map-heat-unscored'))
      .toHaveTextContent('This window is not scored');
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
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));

    expect(heatLayerProps.last.points).toEqual([]);
    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
    expect(screen.getByTestId('wf-map-heat-legend')).toBeInTheDocument();
  });

  it('says nothing in medallion view, where no field is claimed either way', async () => {
    await renderMap({ heat: unrated() });
    fireEvent.click(screen.getByRole('button', { name: 'Medallions' }));
    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
    expect(screen.queryByTestId('wf-map-heat-legend')).toBeNull();
  });

  it('is a different state from the map being on a date the briefing does not reach', async () => {
    // The selector already answers that one, and it is a statement about the CAMERA rather than
    // about the forecast: there is no window to be unrated.
    await renderMap({ heat: heatProp(), date: '2026-01-25' });
    expect(screen.getByTestId('wf-map-window')).toHaveValue('');
    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
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

  it('shows the window the map is actually on, not merely an empty value', async () => {
    // The negative test below pins `''`; without this one, `value={heatWindow?.key ?? ''}` degrading
    // to always-empty would be caught by nothing.
    await renderMap({ heat: heatProp() });
    expect(screen.getByRole('combobox', { name: 'Forecast window' }))
      .toHaveValue(`${TODAY}:SUNSET`);
  });

  it('moves the map’s DATE when the selector picks a window on another day', async () => {
    // The selector must not hold a window of its own: with three time controls on one tab, the two
    // that disagreed would put the field on one evening and the markers on another.
    const onSelectDate = vi.fn();
    await renderMap({ heat: heatProp(), onSelectDate });
    fireEvent.change(screen.getByRole('combobox', { name: 'Forecast window' }), {
      target: { value: `${TOMORROW}:SUNRISE` },
    });
    expect(onSelectDate).toHaveBeenCalledWith(TOMORROW);
  });

  it('moves the map’s EVENT when the window is on the day already shown', async () => {
    // Sunrise→sunset within one day is the commonest use of the control and takes the `next.date
    // !== date` guard's false arm — so `setEventType` is the only thing that can answer it, and
    // dropping that line leaves the date handler's test still green.
    const onSelectDate = vi.fn();
    await renderMap({ heat: heatProp(), onSelectDate });
    fireEvent.change(screen.getByRole('combobox', { name: 'Forecast window' }), {
      target: { value: `${TODAY}:SUNRISE` },
    });
    expect(onSelectDate).not.toHaveBeenCalled();
    expect(screen.getByRole('combobox', { name: 'Forecast window' }))
      .toHaveValue(`${TODAY}:SUNRISE`);
    expect(heatLayerProps.last.points.map((p) => p.name)).toEqual(['Bamburgh']);
  });

  it('holds the chosen event against the auto-selection that would otherwise reclaim it', async () => {
    // `autoEventType` is re-applied by an effect unless the reader has overridden it. Without
    // `setUserHasOverriddenEvent(true)` the selector's answer is undone on the next payload.
    const { rerender } = await renderMap({ heat: heatProp(), autoEventType: 'SUNSET' });
    fireEvent.change(screen.getByRole('combobox', { name: 'Forecast window' }), {
      target: { value: `${TODAY}:SUNRISE` },
    });
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
    expect(screen.getByRole('combobox', { name: 'Forecast window' }))
      .toHaveValue(`${TODAY}:SUNRISE`);
  });

  it('shows nothing selected, and paints nothing, on a date the briefing does not reach', async () => {
    // `GET /api/forecast` reaches further than the briefing's six windows, so this is an ordinary
    // state. The markers stay; only the field is absent.
    await renderMap({ heat: heatProp(), date: '2026-01-25' });
    expect(screen.getByRole('combobox', { name: 'Forecast window' })).toHaveValue('');
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.points).toEqual([]);
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

  it('paints no field and offers no toolbar in astro mode', async () => {
    // Same rule, different quantity: astro markers carry observing quality.
    await renderMap({ heat: heatProp(), handoffEventType: 'ASTRO' });
    expect(screen.queryByTestId('wf-map-toolbar')).toBeNull();
    expect(screen.queryByTestId('map-heat-layer')).toBeNull();
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
    fireEvent.click(screen.getByRole('button', { name: 'Whole catalogue' }));
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
  it('paints markers with the same ramp colour in Heat and Medallions view, because the ramp is now the map\'s only colour language', async () => {
    // D8's end state, not a mid-migration one: `makeMarkerIcon`'s cache key no longer carries a
    // per-view flag, so switching views is a cache HIT on the same icon rather than a second call
    // to `markerLabelAndColour` with a different answer — there is no code path left that could
    // recolour a marker on the way into medallion view. The zero further calls below is therefore
    // the proof, not a weaker substitute for one: the identical cached DivIcon is reused, so its
    // colour is provably identical, not merely re-derived to match.
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
      expect(markerLabelAndColour(...args).colour).toBe(RAMP_STOPS[3].hex);
    }

    markerCalls.length = 0;
    fireEvent.click(screen.getByRole('button', { name: 'Medallions' }));
    expect(markerCalls).toEqual([]);
  });

  it('paints cluster medallions on the ramp stop, in both Heat and Medallions view', async () => {
    // The cluster icon is built by a callback rather than at render, so this is driven through the
    // real `createClusterIcon`, which no longer takes a view/ramp argument at all — so checking
    // both views is a belt-and-braces re-run of the same assertion rather than a distinct claim:
    // there is no code path left that could paint the two views differently.
    await renderMap({ heat: heatProp() });
    const cluster = {
      getChildCount: () => 3,
      getAllChildMarkers: () => [5, 5, 5].map((rating) => ({
        options: { icon: { options: { rating, fierySky: null, goldenHour: null } } },
      })),
    };
    const inHeat = clusterIconCalls.at(-1)(cluster).options.html;
    expect(inHeat).toContain(RAMP_STOPS[4].hex);

    fireEvent.click(screen.getByRole('button', { name: 'Medallions' }));
    const inMedallions = clusterIconCalls.at(-1)(cluster).options.html;
    expect(inMedallions).toContain(RAMP_STOPS[4].hex);
  });

  it('draws the ramp key from the ramp itself, so the picture and its legend cannot drift', async () => {
    await renderMap({ heat: heatProp() });
    // jsdom normalises hex to `rgb()` in a computed gradient, so the stops are compared as
    // channels — which is the same claim, and the one a browser would render.
    const style = screen.getByTestId('wf-map-heat-legend-ramp').getAttribute('style');
    for (const stop of RAMP_STOPS) {
      const n = parseInt(stop.hex.slice(1), 16);
      expect(style).toContain(`rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`);
    }
  });
});
