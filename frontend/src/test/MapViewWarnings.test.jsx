/**
 * `MapView` + the real `MapWarningChips` (weather-warnings-plan.md W4): the warning chips sit in the top-left chrome
 * group as a row of their own (never inside the hot-topics bar), follow the active window, and leave the rest of the
 * tab untouched. Mocked: Leaflet and the layers that need a real Leaflet pane.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, within,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

const VIEWPORT = { pad: () => ({ contains: () => true }) };
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
    getBounds: () => VIEWPORT,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => <div /> }));

let mockRole = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
let mockIsMobile = false;
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
vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import { windowKey } from '../utils/windowKeys.js';
import { response, warning } from './warningsFixture.js';

const TODAY = '2026-10-22';
const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};
const AMBER = warning({
  warningId: 'a',
  level: 'AMBER',
  weatherTypes: ['SNOW'],
  locationIds: [1],
  validFrom: `${TODAY}T00:00:00Z`,
  validTo: `${TODAY}T23:00:00Z`,
});

function heatProp() {
  const point = {
    id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [5],
  };
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map([[windowKey(TODAY, 'SUNSET'), [point]]]),
    windows: [{
      key: windowKey(TODAY, 'SUNSET'),
      date: TODAY,
      targetType: 'SUNSET',
      label: 'Tonight sunset',
      time: '18:12',
      bestRating: 5,
      conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    tideByWindow: new Map(),
  };
}

const LOCATIONS = [{
  id: SPOT.id,
  name: SPOT.name,
  lat: SPOT.lat,
  lon: SPOT.lng,
  regionName: SPOT.rid,
  bortleClass: SPOT.bortleClass,
  locationType: ['LANDSCAPE'],
  tideType: ['HIGH'],
  forecastsByDate: new Map([[TODAY, { sunset: { rating: 5, solarEventTime: `${TODAY}T17:12:00` } }]]),
}];

async function renderMap(served) {
  getWarnings.mockResolvedValue(served);
  await act(async () => {
    render(
      <WarningsProvider>
        <MapView locations={LOCATIONS} date={TODAY} forecastDates={[TODAY]} autoEventType="SUNSET" heat={heatProp()} />
      </WarningsProvider>,
    );
  });
}

beforeEach(() => {
  localStorage.clear();
  mockRole = 'PRO_USER';
  mockIsMobile = false;
  getWarnings.mockReset();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView warning chips', () => {
  it('draws the active window\'s warning in the chrome group, outside the hot-topics bar', async () => {
    await renderMap(response([AMBER]));
    const chrome = screen.getByTestId('wf-map-chrome-tl');
    const chip = within(chrome).getByTestId('warning-chip');
    expect(chip).toHaveTextContent('Amber warning of snow');
    expect(chrome.contains(screen.getByTestId('wf-win-pill'))).toBe(true);
    expect(chip.closest('[data-testid="wf-topics"]')).toBeNull();
  });

  it.each(['LITE_USER', 'ADMIN'])('shows to a %s reader too', async (role) => {
    mockRole = role;
    await renderMap(response([AMBER]));
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('draws the chip on the phone too, in the same group', async () => {
    mockIsMobile = true;
    await renderMap(response([AMBER]));
    expect(within(screen.getByTestId('wf-map-chrome-tl')).getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('draws nothing while the feature is off', async () => {
    await renderMap(response([], { enabled: false }));
    expect(screen.queryByTestId('wf-map-warnings')).toBeNull();
  });

  it('draws no outline, polygon or shading for a warning: the chip is the whole map presence', async () => {
    await renderMap(response([AMBER]));
    const box = screen.getByTestId('wf-map-warnings');
    expect(box.querySelector('svg, canvas, path')).toBeNull();
  });
});
