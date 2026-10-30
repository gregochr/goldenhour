/**
 * MapView's render economy (performance-review-2026-10 P4d, P4e).
 *
 * <p>P4d: a pan that changes nothing the tide readers derive from the viewport does not re-render
 * `MapView`; a pan that does (the phone cue gate, the strip's in-view set) still does, and the pulse's
 * null-then-first-report seed is intact. P4e: `mapEvents`/`evVerdicts` keep their identity across
 * renders with identical inputs, and the clock is an input, so an elapsed window retires on the next
 * render.
 *
 * <p>Harness copied from `MapViewMobileTideRule.test.jsx`; `MapLabels` counts MapView renders and
 * `WindowControl` hands back the `events`/`verdicts` props the memos produce.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { setRewind } from '../utils/rewind.js';
import { tideIndexOf } from './tideFactsFixture.js';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

/** Every handlers object any `useMapEvents` caller registered this render — the same
 * collect-as-a-list pattern `MapViewMobilePeekSheet.test.jsx`/`MapViewBackgroundClick.test.jsx`
 * already use. `BoundsTracker`'s own handlers object is the one carrying `moveend` — `ZoomTracker`'s
 * own `zoomend`-only object is never mistaken for it, since a `moveend` fired at ZoomTracker's
 * handlers would dereference a `getZoom` that is not there. */
let mapEventHandlers = [];
/** In-view by default: `.pad(...).contains(...)` always true, so the fixture's one coastal spot
 * starts inside the padded viewport — the pulse's own "armed on the first bounds-backed
 * evaluation" rule needs a REAL (non-null) bounds report to have landed before any transition can
 * be measured, and this is that first report. */
const IN_VIEW_BOUNDS = { pad: () => ({ contains: () => true }) };
const AWAY_BOUNDS = { pad: () => ({ contains: () => false }) };
let mockMapBounds = IN_VIEW_BOUNDS;
const mockMapInstance = {
  getZoom: () => 9,
  getBounds: () => mockMapBounds,
};
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    mapEventHandlers.push(handlers);
    return mockMapInstance;
  },
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, offsetWidth: 800, parentElement: document.body }),
    getZoom: () => 9,
    getBounds: () => mockMapBounds,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    panInside: () => {},
    getSize: () => ({ x: 390, y: 844 }),
    latLngToContainerPoint: () => ({ x: 195, y: 400 }),
  }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));

let mockIsMobile = true;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
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
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: ({ location }) => (location ? <div data-testid="mock-callout">{location.name}</div> : null),
}));
vi.mock('../components/map/MapPeekTideSection.jsx', () => ({ default: () => <div data-testid="mock-tide-section" /> }));
// The gate's own claim, surfaced directly: `spot.tideTier`, never a stub's own guess at one.
const counters = { labels: 0, events: [], verdicts: [] };
vi.mock('../components/map/WindowControl.jsx', () => ({
  default: (props) => {
    counters.events.push(props.events);
    counters.verdicts.push(props.verdicts);
    return <div data-testid="probe-window-control" />;
  },
}));
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => {
    counters.labels += 1;
    return (
      <div data-testid="probe-labels">
        {(props.spots || []).map((s) => (
          <div key={s.name} data-testid="probe-spot" data-tide={s.tideTier ?? undefined}>{s.name}</div>
        ))}
      </div>
    );
  },
}));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const SPOT = {
  id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East',
};

function makeLocation() {
  return {
    id: SPOT.id,
    name: SPOT.name,
    lat: SPOT.lat,
    lon: SPOT.lng,
    regionName: SPOT.rid,
    bortleClass: 4,
    locationType: ['LANDSCAPE'],
    // Coastal-and-tidal (`isCoastalTidalLocation`): a non-empty `tideType` set.
    tideType: ['HIGH'],
    forecastsByDate: new Map([[TODAY, {
      sunrise: { rating: 1, solarEventTime: `${TODAY}T08:24:00`, fierySkyPotential: 20, goldenHourPotential: 15 },
      sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  };
}

/** The one `days` fixture both `buildRegionVerdictIndex` and `buildTideAlignmentIndex` read — a
 * Poor sunrise and a Worth-it sunset, both for the SAME coastal location, both served ALIGNED —
 * so a Poor window's own `data-tide` disappearing is provably the tide-visibility rule's doing,
 * never a coincidental "nothing to show" from the alignment fact itself. */
const DAYS = [{
  date: TODAY,
  eventSummaries: [
    {
      targetType: 'SUNRISE',
      regions: [{
        regionName: SPOT.rid,
        meanRating: 1.2,
        displayVerdict: 'STAND_DOWN',
        slots: [{
          canopy: false, locationId: SPOT.id, locationName: SPOT.name, tideState: 'HIGH', tideAligned: true,
        }],
      }],
    },
    {
      targetType: 'SUNSET',
      regions: [{
        regionName: SPOT.rid,
        meanRating: 4.4,
        displayVerdict: 'WORTH_IT',
        slots: [{
          canopy: false, locationId: SPOT.id, locationName: SPOT.name, tideState: 'HIGH', tideAligned: true,
        }],
      }],
    },
  ],
}];

function heatProp() {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map(),
    windows: [
      {
        key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'This morning', time: '08:24',
        bestRating: 1, conf: 1, tide: { state: 'HIGH', direction: null, locationName: SPOT.name },
      },
      {
        key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12',
        bestRating: 4, conf: 1, tide: { state: 'HIGH', direction: null, locationName: SPOT.name },
      },
    ],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  };
}

/** Props App holds stable between renders; fresh ones per render would defeat any memo. */
const STABLE = {
  locations: [makeLocation()],
  verdictIndex: buildRegionVerdictIndex(DAYS),
  tideIndex: tideIndexOf(DAYS),
};

function mapElement(props = {}) {
  return (
    <MapView
      locations={STABLE.locations}
      date={TODAY}
      autoEventType="SUNSET"
      heat={heatProp()}
      regionVerdictIndex={STABLE.verdictIndex}
      tideAlignmentIndex={STABLE.tideIndex}
      {...props}
    />
  );
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(mapElement(props));
  });
  return result;
}

async function rerenderMap(result, props = {}) {
  await act(async () => {
    result.rerender(mapElement(props));
  });
}

/** Fires `moveend` at BoundsTracker's own handlers object (the one carrying `moveend`), reporting
 * the given bounds — the same shape a real Leaflet pan/zoom report takes. Also updates the shared
 * `mockMapBounds` the `useMap()` mock's own `getBounds` reads: `BoundsTracker`'s mount effect keys
 * on `[map, onBounds]`, and the mock's `useMap()` returns a fresh object every render, so that
 * effect re-fires on every subsequent render and would otherwise silently REPORT THE OLD BOUNDS
 * AGAIN, undoing this call's own `moveend` a render later. */
async function reportBounds(bounds) {
  mockMapBounds = bounds;
  const handlers = mapEventHandlers.find((h) => typeof h.moveend === 'function');
  await act(async () => {
    handlers.moveend({ target: { getBounds: () => bounds } });
  });
}

beforeEach(() => {
  mockIsMobile = true;
  mockMapBounds = IN_VIEW_BOUNDS;
  mapEventHandlers = [];
  counters.labels = 0;
  counters.events = [];
  counters.verdicts = [];
  localStorage.clear();
});
afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

/** A different viewport object that answers every `contains` the same way as IN_VIEW_BOUNDS. */
const ALSO_IN_VIEW_BOUNDS = { pad: () => ({ contains: () => true }) };

describe('P4d: a pan that changes nothing derived does not re-render MapView', () => {
  it('a moveend with a new bounds object but the same in-view answer renders nothing', async () => {
    await renderMap({ mapTideMode: 'auto' });
    const before = counters.labels;
    await reportBounds(ALSO_IN_VIEW_BOUNDS);
    await reportBounds({ pad: () => ({ contains: () => true }) });
    expect(counters.labels).toBe(before);
  });

  it('a phone pan inland (no coastal place in view) turns the tide cues off, and back on', async () => {
    await renderMap({ mapTideMode: 'auto' });
    expect(screen.getByTestId('probe-spot')).toHaveAttribute('data-tide', 'match');
    const before = counters.labels;
    await reportBounds(AWAY_BOUNDS);
    expect(counters.labels).toBeGreaterThan(before);
    expect(screen.queryByTestId('wf-map-peek-btn-tide')).not.toBeInTheDocument();
    expect(screen.getByTestId('probe-spot')).not.toHaveAttribute('data-tide');
    await reportBounds(IN_VIEW_BOUNDS);
    expect(screen.getByTestId('probe-spot')).toHaveAttribute('data-tide', 'match');
  });

  it('a render for another reason after a skipped pan reads the latest bounds', async () => {
    const result = await renderMap({ mapTideMode: 'auto' });
    await reportBounds(ALSO_IN_VIEW_BOUNDS);
    await rerenderMap(result, { mapTideMode: 'off' });
    expect(screen.queryByTestId('wf-map-peek-btn-tide')).not.toBeInTheDocument();
    await rerenderMap(result, { mapTideMode: 'auto' });
    expect(screen.getByTestId('wf-map-peek-btn-tide')).toBeInTheDocument();
  });

  it('desktop: a same-answer pan renders nothing, and chips keep their tide tier whatever the viewport', async () => {
    mockIsMobile = false;
    await renderMap({ mapTideMode: 'auto' });
    const before = counters.labels;
    await reportBounds(ALSO_IN_VIEW_BOUNDS);
    expect(counters.labels).toBe(before);
    await reportBounds(AWAY_BOUNDS);
    expect(screen.getByTestId('probe-spot')).toHaveAttribute('data-tide', 'match');
  });

  it('a pool change and a synchronous refit report in the same commit is judged against the NEW pools', async () => {
    // The scope switch: the pool changes AND the map refits (a `moveend` from a child passive effect,
    // which the mock reproduces by re-reporting `getBounds()` on every render). The old viewport covered
    // the place; the refit one does not, and the place has moved out of it as the pool changed.
    const NORTH_ONLY = { pad: () => ({ contains: ([lat]) => lat >= 55 }) };
    const result = await renderMap({ mapTideMode: 'auto' });
    expect(screen.getByTestId('wf-map-peek-btn-tide')).toBeInTheDocument();
    mockMapBounds = NORTH_ONLY;
    const moved = [{ ...makeLocation(), lat: 50 }];
    await rerenderMap(result, { mapTideMode: 'auto', locations: moved });
    expect(screen.queryByTestId('wf-map-peek-btn-tide')).not.toBeInTheDocument();
    expect(screen.getByTestId('probe-spot')).not.toHaveAttribute('data-tide');
  });

  it('the peek seed still fires on the first report: no pulse then, one on a later false to true', async () => {
    await renderMap({ mapTideMode: 'auto' });
    expect(screen.getByTestId('wf-map-peek-btn-tide').className).not.toContain('wf-map-peek-btn-pulse');
    await reportBounds(ALSO_IN_VIEW_BOUNDS);
    expect(screen.getByTestId('wf-map-peek-btn-tide').className).not.toContain('wf-map-peek-btn-pulse');
    await reportBounds(AWAY_BOUNDS);
    expect(screen.queryByTestId('wf-map-peek-btn-tide')).not.toBeInTheDocument();
    await reportBounds(IN_VIEW_BOUNDS);
    expect(screen.getByTestId('wf-map-peek-btn-tide').className).toContain('wf-map-peek-btn-pulse');
  });
});

describe('P4e: the event memos hit, and time is an input', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  function latest(list) {
    return list[list.length - 1];
  }

  it('identical props keep Object.is on mapEvents and evVerdicts across re-renders', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${TODAY}T07:00:00Z`));
    const heat = heatProp();
    const result = await renderMap({ heat });
    const events1 = latest(counters.events);
    const verdicts1 = latest(counters.verdicts);
    expect(events1.length).toBeGreaterThan(0);
    const rendersBefore = counters.events.length;
    await rerenderMap(result, { heat });
    await reportBounds(AWAY_BOUNDS);
    expect(counters.events.length).toBeGreaterThan(rendersBefore);
    expect(Object.is(latest(counters.events), events1)).toBe(true);
    expect(Object.is(latest(counters.verdicts), verdicts1)).toBe(true);
  });

  describe('the pastness tick', () => {
    // The 08:24 sunrise is over by the afterglow rule at 08:54:00. No served sunrise window; a past
    // window entry for it, so the row exists only once the elapsed test is true.
    const BOUNDARY = `${TODAY}T08:54:00Z`;
    const PAST = [{ date: TODAY, targetType: 'SUNRISE', earliestEventTime: `${TODAY}T08:24:00` }];
    const DATES = [TODAY];

    function propsNoSunrise() {
      const heat = heatProp();
      heat.windows = heat.windows.filter((w) => w.targetType === 'SUNSET');
      return { heat, pastSolarWindows: PAST, forecastDates: DATES };
    }

    function sunriseRow() {
      return latest(counters.events).find((r) => r.eventType === 'SUNRISE' && r.date === TODAY);
    }

    function useClock(iso) {
      vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
      vi.setSystemTime(new Date(iso));
    }

    it('rows flip to past exactly when the timer fires: same object just before, rebuilt just after', async () => {
      useClock(`${TODAY}T08:30:00Z`);
      const props = propsNoSunrise();
      await renderMap(props);
      const before = latest(counters.events);
      expect(sunriseRow()?.past).not.toBe(true);

      await act(async () => { vi.advanceTimersByTime(Date.parse(BOUNDARY) - Date.now() - 1000); });
      await reportBounds(AWAY_BOUNDS); // a render with identical inputs
      expect(Object.is(latest(counters.events), before)).toBe(true);
      expect(sunriseRow()?.past).not.toBe(true);

      await act(async () => { vi.advanceTimersByTime(1000 + 100); });
      expect(Object.is(latest(counters.events), before)).toBe(false);
      expect(sunriseRow()?.past).toBe(true);
    });

    it('renders between boundaries keep Object.is however much time passes', async () => {
      useClock(`${TODAY}T08:30:00Z`);
      await renderMap(propsNoSunrise());
      const before = latest(counters.events);
      await act(async () => { vi.advanceTimersByTime(10 * 60 * 1000); });
      await reportBounds(AWAY_BOUNDS);
      await reportBounds(IN_VIEW_BOUNDS);
      expect(Object.is(latest(counters.events), before)).toBe(true);
    });

    it('a 24-minute gap is waited out in 30-second slices (no bump), the last one landing on the boundary', async () => {
      useClock(`${TODAY}T08:30:00Z`);
      const setSpy = vi.spyOn(globalThis, 'setTimeout');
      await renderMap(propsNoSunrise());
      const before = latest(counters.events);
      const slice = setSpy.mock.calls.filter((c) => c[1] === 30000);
      expect(slice.length).toBeGreaterThan(0);
      await act(async () => { vi.advanceTimersByTime(20 * 60 * 1000); });
      await reportBounds(AWAY_BOUNDS);
      expect(Object.is(latest(counters.events), before)).toBe(true);
      // The final slice is the remainder, not a full 30 s: the flip lands on boundary + 50 ms.
      await act(async () => { vi.advanceTimersByTime(4 * 60 * 1000 - 1000); });
      expect(sunriseRow()?.past).not.toBe(true);
      await act(async () => { vi.advanceTimersByTime(1100); });
      expect(sunriseRow()?.past).toBe(true);
    });

    it('a simulated sleep (clock jumps past the boundary, no timer fired) flips on the next slice', async () => {
      useClock(`${TODAY}T08:30:00Z`);
      await renderMap(propsNoSunrise());
      expect(sunriseRow()?.past).not.toBe(true);
      vi.setSystemTime(new Date(`${TODAY}T09:30:00Z`));
      await act(async () => { vi.advanceTimersByTime(30000); });
      expect(sunriseRow()?.past).toBe(true);
    });

    it('a tab becoming visible after the boundary flips at once', async () => {
      useClock(`${TODAY}T08:30:00Z`);
      await renderMap(propsNoSunrise());
      vi.setSystemTime(new Date(`${TODAY}T09:30:00Z`));
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
      try {
        await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
        expect(sunriseRow()?.past).toBe(true);
      } finally {
        delete document.visibilityState;
      }
    });

    it('clears its timers on unmount', async () => {
      useClock(`${TODAY}T08:30:00Z`);
      const idle = vi.getTimerCount();
      const result = await renderMap(propsNoSunrise());
      result.unmount();
      await act(async () => { vi.advanceTimersByTime(60 * 60 * 1000); });
      expect(vi.getTimerCount()).toBeLessThanOrEqual(idle);
    });

    it('arms no pastness timer while a Rewind is set', async () => {
      useClock(`${TODAY}T08:30:00Z`);
      setRewind(`${TODAY}T08:30:00Z`);
      try {
        const setSpy = vi.spyOn(globalThis, 'setTimeout');
        await renderMap(propsNoSunrise());
        await act(async () => { vi.advanceTimersByTime(60 * 60 * 1000); });
        // The frozen clock never reaches the 08:54 boundary, so the row never flips.
        expect(sunriseRow()?.past).not.toBe(true);
        expect(setSpy.mock.calls.some((c) => c[1] === 30000)).toBe(false);
      } finally {
        setRewind(null);
      }
    });
  });
});

