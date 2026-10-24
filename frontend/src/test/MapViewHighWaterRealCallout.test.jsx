/**
 * `MapView`'s real props handed to the REAL `MapCallout` on the daylight High-water row
 * (hot-topics-by-day-plan §3 M3). The other sweep tests probe the callout's props; this one is the
 * unmocked seam: what a reader actually sees in the card when the pane is on the row, with the sunset's
 * 5 star forecast sitting in the same location's data and a rated past-style `ratings` object offered.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  const svg = (options) => ({ options });
  return { default: { icon, divIcon, point, svg }, icon, divIcon, point, svg };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

const VIEWPORT = { pad: () => ({ contains: () => true }) };
let currentMap = null;
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="mock-leaflet">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  Pane: ({ children }) => <div>{children}</div>,
  CircleMarker: () => null,
  useMapEvents: () => null,
  useMap: () => currentMap,
}));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => (
    <button type="button" data-testid="probe-chip" onClick={() => props.onSelect('Bamburgh-0')}>chip</button>
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
vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(),
  putRating: vi.fn(),
  fetchAllRatingsForSlot: vi.fn(),
}));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { windowKey } from '../utils/windowKeys.js';
import { FRIDAY_HW } from './bigSeasFixtures.js';

const TODAY = '2026-10-22';
const FRI = '2026-10-23';
const NE = 'North East';
const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: NE, regionName: NE, bortleClass: 4,
};

function makeMap() {
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  Object.defineProperty(container, 'offsetWidth', { value: 800, configurable: true });
  Object.defineProperty(container, 'clientHeight', { value: 500, configurable: true });
  return {
    eachLayer: () => {},
    getContainer: () => container,
    getZoom: () => 9,
    getBounds: () => VIEWPORT,
    getSize: () => ({ x: 800, y: 500 }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
    panInside() {},
    once: () => {},
    on: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    removeLayer: () => {},
  };
}

const win = (date, targetType) => ({
  key: windowKey(date, targetType), date, targetType, label: `${date} ${targetType}`,
  time: targetType === 'SUNRISE' ? '07:20' : '17:40', bestRating: 4, conf: 1,
});

const windows = [win(TODAY, 'SUNSET'), win(FRI, 'SUNRISE'), win(FRI, 'SUNSET')];
const heat = () => ({
  enabled: true,
  hasHome: false,
  spots: [SPOT],
  areaSpots: [SPOT],
  pointsByKey: new Map(windows.map((w) => [w.key, [{
    id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [4],
  }]])),
  windows,
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  tideByWindow: new Map(),
});

const LOCATIONS = [{
  id: SPOT.id,
  name: SPOT.name,
  lat: SPOT.lat,
  lon: SPOT.lng,
  regionName: NE,
  bortleClass: 4,
  locationType: ['LANDSCAPE'],
  tideType: ['HIGH'],
  forecastsByDate: new Map([[FRI, {
    sunrise: { rating: 2, solarEventTime: `${FRI}T06:20:00` },
    // The sunset the High-water row must never borrow.
    sunset: { rating: 5, solarEventTime: `${FRI}T16:40:00` },
  }]]),
}];

const ratings = {
  loaded: true,
  covers: () => true,
  canRate: true,
  mine: new Map(),
  saving: new Set(),
  saveError: new Set(),
  tagSaving: new Set(),
  tagSaveError: new Set(),
  rate: vi.fn(),
  rateTag: vi.fn(),
  clearSaveError: vi.fn(),
};

function Pane() {
  const [date, setDate] = React.useState(FRI);
  return (
    <MapView
      locations={LOCATIONS}
      date={date}
      forecastDates={[TODAY, FRI]}
      autoEventType="SUNSET"
      heat={heat()}
      paneVisible
      runId={null}
      bigSeas={[FRIDAY_HW]}
      outcomeRatings={ratings}
      onSelectDate={(d) => setDate(d)}
    />
  );
}

beforeEach(() => {
  localStorage.clear();
  currentMap = makeMap();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  currentMap = null;
  document.body.innerHTML = '';
  localStorage.clear();
});

describe('MapView with the real MapCallout on the High-water row', () => {
  it('the card says High water and Big seas, prints the lead, and carries neither the sunset\'s 5 stars nor a rating block', async () => {
    await act(async () => { render(<Pane />); });
    // Control: the sunset's own card carries the 5 star score.
    await act(async () => { fireEvent.click(screen.getByTestId('probe-chip')); });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('5★');
    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const hwRow = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === `hw:${FRI}:HIGH_WATER`);
    await act(async () => { fireEvent.click(hwRow); });
    const card = screen.getByTestId('map-callout-verdict');
    expect(card).toHaveTextContent('High water');
    expect(card).toHaveTextContent('12:40');
    expect(screen.getByTestId('map-callout-hw-word')).toHaveTextContent('Big seas');
    expect(screen.queryByTestId('map-callout-score')).toBeNull();
    expect(card.textContent).not.toMatch(/★|Not scored|Loading/);
    expect(screen.getByTestId('map-callout-hw-lead')).toHaveTextContent(
      'High water at 12:40 with 3.4 m waves from NNE. Not a light window',
    );
    expect(screen.queryByTestId('outcome-rating-block')).toBeNull();
    expect(screen.queryByTestId('map-callout-reason')).toBeNull();
  });
});
