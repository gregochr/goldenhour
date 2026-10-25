/**
 * `MapView` → `MapCallout` wiring for overlay convergence C2
 * (`docs/engineering/overlay-convergence-plan.md` §3 C2): the travel-day flag the callout's notice
 * reads. The callout's own drawing is `MapCalloutFacts.test.jsx`; this file pins that the pane hands
 * it the flag, and that the flag follows the operator's travel ranges rather than being true or
 * false by default.
 *
 * What breaks if it fails: the callout never says why a travel day has no rating (the popup's
 * notice died with the overlay), or says it on every day.
 *
 * `MapLabels` is a probe button and `MapCallout` a prop probe — their own behaviour is their own
 * test files' job.
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
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
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
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => (
    <button type="button" data-testid="probe-chip" onClick={() => props.onSelect('Bamburgh-0')}>chip</button>
  ),
}));
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => (
    <div
      data-testid="callout-probe"
      data-travel={String(props.travelDay)}
      data-level={props.auroraAlertLevel ?? ''}
    />
  ),
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
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
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';

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

/** The lazy `MapLabels` resolves behind `Suspense`; wait for its probe, press it, read the callout. */
async function selectBamburgh() {
  await act(async () => {
    await new Promise((resolve) => { setTimeout(resolve, 0); });
  });
  fireEvent.click(screen.getByTestId('probe-chip'));
  return screen.findByTestId('callout-probe');
}

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('MapView — the callout\'s travel-day flag (overlay convergence C2)', () => {
  it('is true when the window\'s date falls inside a travel range', async () => {
    fetchTravelDayRanges.mockResolvedValue([{ startDate: '2026-01-14', endDate: '2026-01-16' }]);
    await renderMap();
    expect((await selectBamburgh()).dataset.travel).toBe('true');
  });

  it('is false outside every range', async () => {
    fetchTravelDayRanges.mockResolvedValue([{ startDate: '2026-02-01', endDate: '2026-02-03' }]);
    await renderMap();
    expect((await selectBamburgh()).dataset.travel).toBe('false');
  });

  it('is false with no ranges at all', async () => {
    fetchTravelDayRanges.mockResolvedValue([]);
    await renderMap();
    expect((await selectBamburgh()).dataset.travel).toBe('false');
  });

  it('hands no aurora level on a solar window', async () => {
    fetchTravelDayRanges.mockResolvedValue([]);
    await renderMap();
    expect((await selectBamburgh()).dataset.level).toBe('');
  });
});
