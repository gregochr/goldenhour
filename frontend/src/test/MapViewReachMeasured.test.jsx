/**
 * `mapReachMeasured` (map-tab-v2-plan.md §3 P8) answers one question — "was a real drive time ever
 * measured for this reader/origin at all" — and it must answer that question from the MAP IN FORCE
 * (`reachById` at home, `heat.driveOverrideById` away — D1, plan-to-map-doors-plan.md §3), never
 * from whichever locations the reader's own rating/subject/drive/dark-sky filters currently let
 * through (`scopedVisibleLocations`/`labelSpots`). Deriving it from the filtered pool was a
 * confirmed adversarial-review finding: filtering away every location that happens to carry a
 * measured drive time would flip the flag from true to false while nothing about whether a drive
 * time exists actually changed, silently swapping the Map tab's reach rings from a duration
 * ("45 min") back to a bare distance ("25 mi") for a reason that has nothing to do with reach
 * honesty.
 *
 * Before D1 the home path read a separate `userDriveTimes` fetch (`getDriveTimes()`); it now reads
 * the SAME `reachById` prop the Plan cards read, so this file's home-path fixtures pass `reachById`
 * directly rather than mocking the fetch. D1 also added the away case: under an origin, the flag
 * must read true from a measured `heat.driveOverrideById` matrix EVEN WITH NO HOME COORDINATE SET —
 * the reader is planning from a base, not from home, so a missing HOME coordinate must not gate a
 * screen whose drives are measured from somewhere else entirely.
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
// Overlay-only since D1 — unused by this file's tab-mode renders, but MapView still imports it.
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn(() => Promise.resolve({})) }));
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
const AWAY_ORIGIN = { id: 'lakes', name: 'Lake District', baseName: 'Keswick' };

/** Rated 1★ — below `DEFAULT_MIN_STARS` (3), so the default filter drops it from every pool that
 *  reads `scopedVisibleLocations`/`labelSpots` — but NOT from the reach map, which is a flat map
 *  keyed by id and knows nothing about ratings. */
const LOW_RATED_SPOT = {
  id: 1, name: 'Filtered-out spot', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};

function heatProp(driveOverrideById) {
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
    ...(driveOverrideById !== undefined ? { driveOverrideById } : {}),
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

beforeEach(() => {
  localStorage.clear();
  mapLabelsCalls.length = 0;
});

afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('MapView — mapReachMeasured (home) reads the reachById MAP IN FORCE, never the filtered pool', () => {
  it('is true once a measured drive time exists, EVEN THOUGH the only location carrying one is filtered out by default', async () => {
    // LOW_RATED_SPOT's own id — filtered out of every scoped pool by the default 3★ floor.
    const reachById = new Map([[1, { driveMinutes: 45, distanceMiles: 20 }]]);
    await renderMap({ homeCoords: HOME_COORDS, reachById });
    const last = mapLabelsCalls.at(-1);
    expect(last.reachMeasured).toBe(true);
    // The filter really did drop it — this is the premise, not incidental: without it, the fix
    // under test could not be distinguished from the old (filtered-pool) behaviour at all.
    expect(last.spots).toEqual([]);
  });

  it('a SUBSEQUENT re-render with the SAME reachById cannot flip it — filtering is not a dependency', async () => {
    const reachById = new Map([[1, { driveMinutes: 45, distanceMiles: 20 }]]);
    let rerender;
    await act(async () => {
      ({ rerender } = render(
        <MapView
          locations={[makeLocation()]}
          date={TODAY}
          autoEventType={null}
          heat={heatProp()}
          homeCoords={HOME_COORDS}
          reachById={reachById}
        />,
      ));
    });
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(true);
    // Re-render with an even narrower catalogue (no locations at all) — still the SAME
    // `reachById` map reference, since nothing re-fetched it.
    await act(async () => {
      rerender(
        <MapView
          locations={[]}
          date={TODAY}
          autoEventType={null}
          heat={{ ...heatProp(), spots: [], areaSpots: [] }}
          homeCoords={HOME_COORDS}
          reachById={reachById}
        />,
      );
    });
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(true);
  });

  it('is false with no home coordinates, even with a measured drive time on file', async () => {
    const reachById = new Map([[1, { driveMinutes: 45, distanceMiles: 20 }]]);
    await renderMap({ reachById }); // no homeCoords, no origin
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(false);
  });

  it('is false when nothing in the map is a finite number', async () => {
    const reachById = new Map([[1, { driveMinutes: null, distanceMiles: 20 }]]);
    await renderMap({ homeCoords: HOME_COORDS, reachById });
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(false);
  });
});

describe('MapView — mapReachMeasured (away) reads heat.driveOverrideById, and needs no home coordinate at all (D1)', () => {
  it('is true under an origin with a measured region-base matrix, even with NO home postcode saved', async () => {
    const driveOverrideById = new Map([[1, { driveMinutes: 30, distanceMiles: null }]]);
    await renderMap({ origin: AWAY_ORIGIN, heat: heatProp(driveOverrideById) }); // no homeCoords
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(true);
  });

  it('is false under an origin whose region-base matrix has no finite entry (empty matrix)', async () => {
    const driveOverrideById = new Map();
    await renderMap({ origin: AWAY_ORIGIN, heat: heatProp(driveOverrideById) });
    expect(mapLabelsCalls.at(-1).reachMeasured).toBe(false);
  });
});
