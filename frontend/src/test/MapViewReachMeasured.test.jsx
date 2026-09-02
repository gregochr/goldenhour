/**
 * `mapReachMeasured` (map-tab-v2-plan.md §3 P8) answers one question — "was a real drive time ever
 * measured for this reader at all" — and it must answer that question from the FETCH
 * (`userDriveTimes`), never from whichever locations the reader's own rating/subject/drive/dark-sky
 * filters currently let through (`scopedVisibleLocations`/`labelSpots`). Deriving it from the
 * filtered pool was a confirmed adversarial-review finding: filtering away every location that
 * happens to carry a measured drive time would flip the flag from true to false while nothing
 * about whether a drive time exists actually changed, silently swapping the Map tab's reach rings
 * from a duration ("45 min") back to a bare distance ("25 mi") for a reason that has nothing to do
 * with reach honesty.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));
vi.mock('react-leaflet-cluster', () => ({ default: ({ children }) => <div>{children}</div> }));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div data-testid="map-heat-layer" /> }));

/** Captures every props object `MapView` has ever handed `MapLabels`, in render order. */
const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => { mapLabelsCalls.push(props); return null; },
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
/** Controllable per test — the one input `mapReachMeasured` is supposed to answer from. */
let driveTimesResponse = {};
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn(() => Promise.resolve(driveTimesResponse)) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';
const HOME_COORDS = { lat: 55.0, lon: -1.5 };

/** Rated 1★ — below `DEFAULT_MIN_STARS` (3), so the default filter drops it from every pool that
 *  reads `scopedVisibleLocations`/`labelSpots` — but NOT from `userDriveTimes`, which is a flat
 *  fetch keyed by id and knows nothing about ratings. */
const LOW_RATED_SPOT = {
  id: 1, name: 'Filtered-out spot', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};

function heatProp() {
  return {
    enabled: true,
    hasHome: true,
    spots: [LOW_RATED_SPOT],
    areaSpots: [LOW_RATED_SPOT],
    pointsByKey: new Map([[`${TODAY}:SUNSET`, [{
      id: LOW_RATED_SPOT.id,
      name: LOW_RATED_SPOT.name,
      lat: LOW_RATED_SPOT.lat,
      lng: LOW_RATED_SPOT.lng,
      rid: LOW_RATED_SPOT.rid,
      r: [1],
    }]]]),
    windows: [{
      key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 1, conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  };
}

function makeLocation() {
  return {
    id: LOW_RATED_SPOT.id,
    name: LOW_RATED_SPOT.name,
    lat: LOW_RATED_SPOT.lat,
    lon: LOW_RATED_SPOT.lng,
    regionName: LOW_RATED_SPOT.rid,
    bortleClass: LOW_RATED_SPOT.bortleClass,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: 1, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 10, goldenHourPotential: 10 },
    }]]),
  };
}

async function renderMap() {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={[makeLocation()]}
        date={TODAY}
        autoEventType={null}
        heat={heatProp()}
        homeCoords={HOME_COORDS}
      />,
    );
  });
  // Let the async `getDriveTimes()` fetch resolve and its state update flush.
  await act(async () => {
    await new Promise((resolve) => { setTimeout(resolve, 0); });
  });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  mapLabelsCalls.length = 0;
  driveTimesResponse = {};
});

afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('MapView — mapReachMeasured reads the FETCH, never the filtered pool', () => {
  it('is true once a measured drive time exists, EVEN THOUGH the only location carrying one is filtered out by default', async () => {
    driveTimesResponse = { 1: 45 }; // LOW_RATED_SPOT's own id — filtered out of every scoped pool
    await renderMap();
    const last = mapLabelsCalls.at(-1);
    expect(last.reachMeasured).toBe(true);
    // The filter really did drop it — this is the premise, not incidental: without it, the fix
    // under test could not be distinguished from the old (filtered-pool) behaviour at all.
    expect(last.spots).toEqual([]);
  });

  it('a SUBSEQUENT re-render with the SAME fetch cannot flip it — filtering is not a dependency', async () => {
    driveTimesResponse = { 1: 45 };
    const { rerender } = await renderMap();
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(true);
    // Re-render with an even narrower catalogue (no locations at all) — still the SAME
    // `userDriveTimes` fetch result, since nothing re-fetched it.
    await act(async () => {
      rerender(
        <MapView
          locations={[]}
          date={TODAY}
          autoEventType={null}
          heat={{ ...heatProp(), spots: [], areaSpots: [] }}
          homeCoords={HOME_COORDS}
        />,
      );
    });
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(true);
  });

  it('is false with no home coordinates, even with a measured drive time on file', async () => {
    driveTimesResponse = { 1: 45 };
    let result;
    await act(async () => {
      result = render(
        <MapView locations={[makeLocation()]} date={TODAY} autoEventType={null} heat={heatProp()} />,
      );
    });
    await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 0); }); });
    void result;
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(false);
  });

  it('is false when nothing in the fetch is a finite number', async () => {
    driveTimesResponse = { 1: null };
    await renderMap();
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(false);
  });
});
