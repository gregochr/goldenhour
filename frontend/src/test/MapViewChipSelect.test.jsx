/**
 * A Map tab chip click (map-tab-v2-plan.md §3 P8) is wired to `selectMapLocation`, and this file
 * pins what that function does — which is now exactly one thing: set `selectedLocationName`.
 *
 * <p>⚠️ It used to do two more, and both are gone. Through P8 it opened the marker's own Leaflet
 * popup; P9 removed the popup from the tab entirely (`MapCallout` reads `selectedLocationName`
 * reactively and needs no imperative nudge). And until clustering was deleted it called the cluster
 * group's `zoomToShowLayer` to reveal a marker folded into a bubble — justified here, and in the
 * source, as a prerequisite for the selection ring landing on a visible point. That justification
 * was false from P9 onward: `MapCallout` anchors off the location's own coordinates
 * (`latLngToContainerPoint`), never a marker ref. With `disableClusteringAtZoom` at 13, its real
 * effect was to jump the camera on EVERY chip click below street level, to reveal a marker the tab
 * does not paint.
 *
 * <p>So the two tests that pinned the reveal are deleted rather than inverted — there is no
 * behaviour left to assert, only its absence, which the surviving tests cover: the selection lands,
 * and no popup is opened from any path.
 *
 * `MapLabels.jsx` is mocked to a probe button — its own placement/measurement behaviour is
 * `MapLabels.test.jsx`'s job; this file is only about what `onSelect` does once MapView receives
 * it, which needs no real DOM measurement to exercise.
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

/** The one marker instance MapView will register into its `markerRefs` map. */
let fakeMarker;
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


vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div data-testid="map-heat-layer" /> }));

/** The probe: a button that calls the exact `onSelect` MapView hands `MapLabels`, plus a readout of
 * the `selectedName` prop so a test can prove the selection landed WITHOUT needing a popup to have
 * opened (map-tab-v2-plan.md §3 P9 — there is no popup left on this tab to open). */
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => (
    <div>
      <button type="button" data-testid="probe-chip" onClick={() => props.onSelect('Bamburgh-0')}>
        chip
      </button>
      <span data-testid="probe-selected">{props.selectedName ?? ''}</span>
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
});

afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('MapView — what a chip click does (map-tab-v2-plan.md §3 P8 review, P9 update)', () => {
  it('selects the location, and that is the whole of it', async () => {
    await renderMap();
    fireEvent.click(await screenFindProbe());
    expect(screen.getByTestId('probe-selected')).toHaveTextContent('Bamburgh-0');
  });

  it('never calls openPopup — the tab has no Leaflet popup left to open (map-tab-v2-plan.md §3 P9)', async () => {
    await renderMap();
    fireEvent.click(await screenFindProbe());
    expect(fakeMarker.openPopup).not.toHaveBeenCalled();
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
