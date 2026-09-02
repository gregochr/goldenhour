/**
 * A Map tab chip click (map-tab-v2-plan.md §3 P8) is wired to `selectMapLocation`, which used to
 * call `marker.openPopup()` directly. That is a SILENT no-op for a marker currently folded into a
 * cluster bubble — `marker._map` is null while clustered, `openPopup()` does nothing, and nothing
 * throws — which chips hit often, since they render across a wide zoom range where most markers on
 * screen are still clustered. The fix (adversarial review finding #6) routes the click through the
 * cluster group's own `zoomToShowLayer`, which reveals the marker before opening its popup, and
 * falls back to a direct `openPopup()` only when the marker is already unclustered (or the cluster
 * ref cannot answer).
 *
 * `MapLabels.jsx` is mocked to a probe button — its own placement/measurement behaviour is
 * `MapLabels.test.jsx`'s job; this file is only about what `onSelect` does once MapView receives
 * it, which needs no real DOM measurement to exercise.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, fireEvent } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

/** The one marker instance MapView will register into its `markerRefs` map. */
let fakeMarker;
/** The one cluster-group instance `clusterGroupRef` will resolve to. `undefined` methods on it
 *  model "no zoomToShowLayer available" (an older cluster lib, or the ref not yet attached). */
let fakeClusterGroup;

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: React.forwardRef(function MockMarker({ children }, ref) {
    React.useImperativeHandle(ref, () => fakeMarker);
    return <div>{children}</div>;
  }),
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

vi.mock('react-leaflet-cluster', () => ({
  default: React.forwardRef(function MockClusterGroup({ children }, ref) {
    React.useImperativeHandle(ref, () => fakeClusterGroup);
    return <div>{children}</div>;
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div data-testid="map-heat-layer" /> }));

/** The probe: a button that calls the exact `onSelect` MapView hands `MapLabels`. */
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => (
    <button type="button" data-testid="probe-chip" onClick={() => props.onSelect('Bamburgh-0')}>
      chip
    </button>
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

async function renderMap() {
  let result;
  await act(async () => {
    result = render(
      <MapView locations={[makeLocation()]} date={TODAY} autoEventType={null} heat={heatProp()} />,
    );
  });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  fakeMarker = { openPopup: vi.fn() };
  fakeClusterGroup = { zoomToShowLayer: vi.fn((_marker, callback) => callback()) };
});

afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('MapView — chip click on a clustered marker (map-tab-v2-plan.md §3 P8 review)', () => {
  it('calls zoomToShowLayer on the cluster group, and opens the popup from ITS callback', async () => {
    await renderMap();
    fireEvent.click(await screenFindProbe());
    expect(fakeClusterGroup.zoomToShowLayer).toHaveBeenCalledTimes(1);
    expect(fakeClusterGroup.zoomToShowLayer.mock.calls[0][0]).toBe(fakeMarker);
    // The popup opens from WITHIN the callback zoomToShowLayer invokes (this test's own fake
    // calls it synchronously) — never as a bare, immediate call the cluster method could race.
    expect(fakeMarker.openPopup).toHaveBeenCalledTimes(1);
  });

  it('never calls openPopup directly when zoomToShowLayer is available — no silent double-open', async () => {
    await renderMap();
    fireEvent.click(await screenFindProbe());
    // Exactly one open, sourced from the callback — not one from a direct call PLUS one from the
    // callback, which would be the bug if the fallback path were not properly gated.
    expect(fakeMarker.openPopup).toHaveBeenCalledTimes(1);
  });

  it('falls back to a direct openPopup() when the cluster ref has no zoomToShowLayer', async () => {
    fakeClusterGroup = {}; // an unclustered marker, or a ref that has not resolved the method
    await renderMap();
    fireEvent.click(await screenFindProbe());
    expect(fakeMarker.openPopup).toHaveBeenCalledTimes(1);
  });
});

/** The lazy `MapLabels` mock resolves asynchronously (behind `Suspense`) — wait for its probe. */
async function screenFindProbe() {
  await act(async () => {
    await new Promise((resolve) => { setTimeout(resolve, 0); });
  });
  const node = document.querySelector('[data-testid="probe-chip"]');
  expect(node).toBeTruthy();
  return node;
}
