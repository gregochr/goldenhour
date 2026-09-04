/**
 * MapView's own selection-callout wiring (map-tab-v2-plan.md §3 P9), separate from the callout's
 * own content/anchoring (`MapCallout.test.jsx`) and from the chip-click reveal mechanics
 * (`MapViewChipSelect.test.jsx`). Covers:
 *
 * - The close ORDERING rule (README "Interactions & behaviour"): a background click or an `Esc`
 *   press closes the nearest open layer first — a popover, if one is open — and only takes the
 *   callout on a SECOND press/click, never both on one.
 * - The inbound `handoffLocationName` TAB-vs-OVERLAY branch: the tab selects the location without
 *   ever calling `marker.openPopup()` (there is no popup left to open); the overlay branch is
 *   byte-identical to before this phase.
 * - The "Open in Plan" handoff: the callout's action reaches `onOpenLocationInPlan` with the
 *   `{id, name, regionName}` shape `WindowFirstShell.jsx`'s `sheetSpotOf` already normalises onto.
 * - PR #734 review: a DELIBERATE selection outranks the pool's own rating/subject/drive/dark-sky
 *   filters. `selectedLoc` used to be resolved from `visibleLocations` (the FILTERED pool), so the
 *   every-window strip switching to a window where the selection sits below the min-stars default
 *   or is unscored dropped it out of the pool and unmounted the callout mid-interaction, and an
 *   inbound handoff to a location the reader's current filters exclude never produced a callout at
 *   all. Fixed by resolving `selectedLoc` from `locations` — the full enabled catalogue — instead.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, fireEvent, screen } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

let fakeMarker;
/** Captured from whichever `useMapEvents({ click: ... })` call registers it — only
 * `MapBackgroundClickController` uses the `click` key on this tab (`BoundsTracker`'s `moveend`/
 * `zoomend` pair and `ZoomTracker`'s bare `zoomend` never collide with it). */
let capturedBackgroundClick = null;
/** Captured from the SAME controller's `mousedown` handler — the snapshot half of the
 * close-ordering fix (`MapBackgroundClickController`'s own class doc). A real background click
 * fires BOTH, `mousedown` then `click`, so {@link clickBackground} below fires them in that order
 * rather than invoking the `click` handler alone. */
let capturedBackgroundMouseDown = null;

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: React.forwardRef(function MockMarker({ children }, ref) {
    React.useImperativeHandle(ref, () => fakeMarker);
    return <div>{children}</div>;
  }),
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    if (handlers?.click) capturedBackgroundClick = handlers.click;
    if (handlers?.mousedown) capturedBackgroundMouseDown = handlers.mousedown;
    return null;
  },
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    // `HandoffPopupController`'s overlay branch registers a one-shot `moveend` listener as its
    // "the fly animation settled" signal — never fired here, so that branch always falls through
    // to its own `setTimeout(open, 700)` fallback, which is what the overlay test below advances.
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));


vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));

/** Every props object `MapView` has ever handed `MapLabels`, in render order (`MapViewReach
 * Measured.test.jsx`'s own pattern) — PR #734 review's own "verify the joined spot data exists"
 * ask: `spots` is `labelSpots`, and `chipCandidates`' P8 "the selected location always gets its
 * chip" guarantee can only force a chip for a spot that actually appears in this array. */
const mapLabelsCalls = [];
/** The probe: a button that selects the fixture spot, exactly like `MapViewChipSelect.test.jsx`'s
 * own mock. Its own placement/measurement is not this file's concern. */
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => {
    mapLabelsCalls.push(props);
    return (
      <button type="button" data-testid="probe-chip" onClick={() => props.onSelect('Bamburgh-0')}>
        chip
      </button>
    );
  },
}));

/** The callout, mocked to a probe that exposes exactly what this file asserts on: whether it is
 * mounted at all (the selection state MapView feeds it), its own `rating` (PR #734 review — proves
 * a strip-switch shows that window's HONEST state rather than unmounting), a button that plays the
 * every-window strip's own `onSelectEv` action, and its "Open in Plan" wiring. Its own
 * content/anchoring is `MapCallout.test.jsx`'s job. */
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => (
    <div data-testid="probe-callout">
      <span data-testid="probe-callout-name">{props.location?.name ?? ''}</span>
      <span data-testid="probe-callout-rating">{JSON.stringify(props.rating ?? null)}</span>
      {(props.evRows ?? []).map((row) => (
        <button
          key={row.id}
          type="button"
          data-testid={`probe-callout-select-ev-${row.kind}`}
          onClick={() => props.onSelectEv?.(row)}
        >
          {`select ${row.kind}`}
        </button>
      ))}
      <button type="button" data-testid="probe-callout-open-in-plan" onClick={() => props.onOpenInPlan?.()}>
        open in plan
      </button>
    </div>
  ),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
/** Controllable per test (PR #734 review's strip-switch case needs a real ASTRO row to switch
 * to) — defaults to none, matching every OTHER describe block's fixture, which never mounts a
 * night row at all. */
let astroAvailableDatesResponse = [];
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn(() => Promise.resolve(astroAvailableDatesResponse)),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};

function heatProp() {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map([[`${TODAY}:SUNSET`, [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [5],
    }]]]),
    windows: [{
      key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  };
}

function makeLocation() {
  return {
    id: SPOT.id,
    name: SPOT.name,
    lat: SPOT.lat,
    lon: SPOT.lng,
    regionName: SPOT.rid,
    bortleClass: SPOT.bortleClass,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  };
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={[makeLocation()]}
        date={TODAY}
        autoEventType={null}
        heat={heatProp()}
        {...props}
      />,
    );
  });
  return result;
}

async function screenFindProbe(testId = 'probe-chip') {
  await act(async () => {
    await new Promise((resolve) => { setTimeout(resolve, 0); });
  });
  const node = document.querySelector(`[data-testid="${testId}"]`);
  expect(node).toBeTruthy();
  return node;
}

async function selectTheSpot() {
  fireEvent.click(await screenFindProbe());
}

/**
 * A genuine background click, `mousedown` then `click` — the same two events one physical click
 * fires in a real browser, and in that order (`MapBackgroundClickController`'s own class doc: the
 * close-ordering fix depends on the snapshot happening on `mousedown`, before `WindowControl`'s own
 * `document`-level `mousedown` listener can close the menu the `click` handler still needs to see
 * as "was open"). Invoking the captured `click` handler alone — this suite's own shape before the
 * fix — skipped the snapshot entirely and always read the ref's initial `null`.
 */
function clickBackground() {
  act(() => {
    capturedBackgroundMouseDown();
    capturedBackgroundClick();
  });
}

beforeEach(() => {
  localStorage.clear();
  fakeMarker = { openPopup: vi.fn() };
  capturedBackgroundClick = null;
  capturedBackgroundMouseDown = null;
  astroAvailableDatesResponse = [];
  mapLabelsCalls.length = 0;
});

afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  vi.useRealTimers();
});

describe('MapView — background click closes the NEAREST layer first', () => {
  it('a click WITH a popover open closes only the popover, leaving the callout selected', async () => {
    await renderMap();
    await selectTheSpot();
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();

    expect(capturedBackgroundClick).toBeTypeOf('function');
    clickBackground();

    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
    // The callout survives this press — the ordering rule's whole point.
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
  });

  it('a click with NO popover open closes the callout instead', async () => {
    await renderMap();
    await selectTheSpot();
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();

    expect(capturedBackgroundClick).toBeTypeOf('function');
    clickBackground();

    expect(screen.queryByTestId('probe-callout')).toBeNull();
  });

  it('two clicks in sequence close the popover, then the callout — never both on one press', async () => {
    await renderMap();
    await selectTheSpot();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();

    clickBackground();
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();

    clickBackground();
    expect(screen.queryByTestId('probe-callout')).toBeNull();
  });
});

describe('MapView — Esc closes menus, THEN the callout', () => {
  it('the first Esc closes an open menu and leaves the callout selected', async () => {
    await renderMap();
    await selectTheSpot();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();

    fireEvent.keyDown(screen.getByTestId('wf-win-pill'), { key: 'Escape' });

    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
  });

  it('a SECOND Esc, with no menu left open, closes the callout', async () => {
    await renderMap();
    await selectTheSpot();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.keyDown(screen.getByTestId('wf-win-pill'), { key: 'Escape' });
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();

    fireEvent.keyDown(screen.getByTestId('wf-win-pill'), { key: 'Escape' });

    expect(screen.queryByTestId('probe-callout')).toBeNull();
  });

  it('Esc with nothing open at all does nothing (no crash, no stray selection change)', async () => {
    await renderMap();
    fireEvent.keyDown(screen.getByTestId('wf-win-pill'), { key: 'Escape' });
    expect(screen.queryByTestId('probe-callout')).toBeNull();
  });
});

describe('MapView — "Open in Plan" reaches the shell handoff', () => {
  it('calls onOpenLocationInPlan with the sheet-spot shape', async () => {
    const onOpenLocationInPlan = vi.fn();
    await renderMap({ onOpenLocationInPlan });
    await selectTheSpot();
    fireEvent.click(screen.getByTestId('probe-callout-open-in-plan'));
    expect(onOpenLocationInPlan).toHaveBeenCalledTimes(1);
    expect(onOpenLocationInPlan).toHaveBeenCalledWith({
      id: SPOT.id, name: SPOT.name, regionName: SPOT.rid,
    });
  });
});

describe('MapView — inbound handoffLocationName, tab vs overlay (map-tab-v2-plan.md §3 P9)', () => {
  it('on the TAB, selects the location but never calls marker.openPopup — there is no popup left', async () => {
    vi.useFakeTimers();
    await renderMap({ handoffLocationName: 'Bamburgh-0', handoffNonce: 1 });
    await act(async () => { vi.advanceTimersByTime(700); });
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Bamburgh-0');
    expect(fakeMarker.openPopup).not.toHaveBeenCalled();
  });

  it('on the OVERLAY, still opens the marker\'s bound popup — byte-identical to before this phase', async () => {
    vi.useFakeTimers();
    await renderMap({ handoffLocationName: 'Bamburgh-0', handoffNonce: 1, overlayMode: true });
    await act(async () => { vi.advanceTimersByTime(700); });
    expect(fakeMarker.openPopup).toHaveBeenCalledTimes(1);
  });
});

describe('MapView — a deliberate selection outranks the pool\'s own filters (PR #734 review)', () => {
  it('a strip-switch to an UNSCORED window keeps the callout mounted, showing that window\'s honest state', async () => {
    // A real ASTRO row to switch to — `astroAvailableDatesResponse` makes `buildMapEvents` build
    // one for TODAY; `getAstroConditions` (mocked to `[]` file-wide) means NOBODY has an astro
    // rating, so this location's own is `null` — below-threshold in the sense that matters here:
    // `getRatingForLocation`'s ASTRO branch returns null, and the default filter (showUnrated
    // false) would drop a null-rated, non-wildlife location from `visibleLocations` outright.
    astroAvailableDatesResponse = [TODAY];
    await renderMap();
    await selectTheSpot();
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Bamburgh-0');
    // The SUNSET window's own served rating (5★) — the premise, not incidental: proves the
    // callout really did start on a SCORED window before the switch.
    expect(screen.getByTestId('probe-callout-rating')).toHaveTextContent('5');

    const astroButton = await screenFindProbe('probe-callout-select-ev-astro');
    await act(async () => { fireEvent.click(astroButton); });

    // The regression this test exists to catch: reading `selectedLoc` off `visibleLocations`
    // dropped this location from the pool the instant its CURRENT window's rating went null,
    // unmounting the callout mid-interaction — exactly when the reader asked "how is THIS place
    // on THAT window". The fix keeps it mounted, showing the honest (unscored) state rather than
    // vanishing or lying about a rating that does not exist for this window.
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Bamburgh-0');
    expect(screen.getByTestId('probe-callout-rating')).toHaveTextContent('null');
  });

  it('an inbound handoff to a location the current filters exclude still opens the callout', async () => {
    // Rated 1★ — below `DEFAULT_MIN_STARS` (3), so this location never appears in
    // `visibleLocations`/`labelSpots` under the map's own default filter. `handoffLocationName`
    // already resolves against `locations` (the full roster) to set `selectedLocationName`
    // correctly; the regression was `selectedLoc` — the variable the callout actually reads —
    // still coming from the filtered pool, so the callout never appeared.
    const FILTERED_OUT_SPOT = {
      id: 2, name: 'Low-rated', lat: 55.7, lon: -1.8, regionName: 'North East', bortleClass: 4,
    };
    const filteredOutLocation = {
      ...FILTERED_OUT_SPOT,
      lng: FILTERED_OUT_SPOT.lon,
      locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[TODAY, {
        sunset: { rating: 1, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 10, goldenHourPotential: 10 },
      }]]),
    };
    vi.useFakeTimers();
    await renderMap({
      locations: [makeLocation(), filteredOutLocation],
      handoffLocationName: 'Low-rated',
      handoffNonce: 1,
    });
    await act(async () => { vi.advanceTimersByTime(700); });

    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Low-rated');
    // The label-chip guarantee (P8's "the selected location always gets its chip", `chipCandidates`
    // in `utils/mapLabels.js`) can only fire for a spot that exists in `labelSpots` at all — a
    // location filtered out of `scopedVisibleLocations` never reaches it unless `MapView` appends
    // the selected location's own row. Verified directly here rather than assumed: `spots` is the
    // exact array `MapLabels`/`chipCandidates` see.
    const lastSpots = mapLabelsCalls.at(-1).spots;
    expect(lastSpots.some((s) => s.name === 'Low-rated')).toBe(true);
  });
});
