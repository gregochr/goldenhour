/**
 * A Map pane constructed inside a hidden (0x0) panel: MapContainer's opening fit clamps to maxZoom, so
 * `HeatBoundsController` must not adopt it as its baseline. It replays the current framing once the pane is
 * shown and sized, never touches the view while the box is 0x0, and a map that opened sized is unchanged.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render } from '@testing-library/react';

let cornerEl;
vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });

  class Control {
    constructor(options = {}) { this.options = options; }

    addTo(map) {
      this._container = this.onAdd(map);
      this._container.classList.add('leaflet-control');
      map._corner.appendChild(this._container);
      return this;
    }

    remove() {
      this._container?.remove();
      return this;
    }
  }

  const DomEvent = {
    disableClickPropagation: () => {},
    disableScrollPropagation: () => {},
  };
  const latLngBounds = (points) => ({ points });
  const L = { icon, divIcon, point, Control, DomEvent, latLngBounds };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

/** Camera calls in the order they happened: 'invalidateSize', 'fitBounds', 'flyTo'. */
const cameraLog = [];
/** The recorded args of each fit, aligned to the 'fitBounds' entries of `cameraLog`. */
const fitArgs = [];
/** What the mock Leaflet map measures; flipped between 0x0 (hidden) and a real box per test. */
const size = { w: 0, h: 0 };
const mockMap = {
  eachLayer: () => {},
  getSize: () => ({ x: size.w, y: size.h }),
  getContainer: () => ({ clientWidth: size.w, clientHeight: size.h }),
  getZoom: () => 9,
  once: () => {},
  off: () => {},
  addControl: () => {},
  invalidateSize: () => { cameraLog.push('invalidateSize'); },
  flyTo: () => { cameraLog.push('flyTo'); },
  fitBounds: (bounds, options) => { cameraLog.push('fitBounds'); fitArgs.push([bounds, options]); },
};

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => {
    mockMap._corner = cornerEl;
    return mockMap;
  },
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: () => <div data-testid="map-heat-layer" />,
}));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => null }));
vi.mock('../components/map/MapCallout.jsx', () => ({ default: () => null }));
vi.mock('../components/map/FiltersPopover.jsx', () => ({
  default: () => null,
  DRIVE_TIME_TIERS: [[0, 'Any'], [45, '45 min'], [90, '1h 30'], [150, '2h 30']],
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
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn(() => Promise.resolve({})) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const slot = (rating) => ({
  rating, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60,
});
const NEAR = {
  id: 1, name: 'Near', lat: 55.61, lon: -1.71, regionName: 'North East', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, { sunset: slot(5) }]]),
};
const FAR = {
  id: 3, name: 'Far', lat: 54.45, lon: -3.0, regionName: 'Lake District', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, { sunset: slot(5) }]]),
};
const LOCATIONS = [NEAR, FAR];

const spotOf = (loc) => ({
  id: loc.id, name: loc.name, lat: loc.lat, lng: loc.lon, regionName: loc.regionName,
  rid: loc.regionName, bortleClass: 4, r: [5],
});

const HEAT = {
  enabled: true,
  hasHome: false,
  spots: LOCATIONS.map(spotOf),
  areaSpots: [spotOf(NEAR)],
  pointsByKey: new Map([[`${TODAY}:SUNSET`, []]]),
  windows: [
    {
      key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1,
    },
  ],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[53.9, -3.4], [55.7, -1.3]],
};

function tree(props) {
  return (
    <MapView
      locations={LOCATIONS}
      date={TODAY}
      autoEventType="SUNSET"
      forecastDates={[TODAY]}
      heat={HEAT}
      {...props}
    />
  );
}

async function renderTab(props) {
  let result;
  await act(async () => { result = render(tree(props)); });
  return result;
}

async function update(rerender, props) {
  await act(async () => { rerender(tree(props)); });
}

const fits = () => cameraLog.filter((c) => c === 'fitBounds').length;

beforeEach(() => {
  size.w = 0;
  size.h = 0;
  localStorage.clear();
  cameraLog.length = 0;
  fitArgs.length = 0;
  cornerEl = document.createElement('div');
  cornerEl.className = 'leaflet-bottom leaflet-right';
  document.body.appendChild(cornerEl);
});

afterEach(() => {
  localStorage.clear();
  cornerEl.remove();
  vi.clearAllMocks();
});

const AREA = [[54.3, -3.4], [55.7, -1.3]];

describe('MapView opened inside a hidden tab', () => {
  it('holds while 0x0, then fits the area once when shown and sized, never calling invalidateSize while 0x0', async () => {
    const { rerender } = await renderTab({ panelShown: true });
    expect(fits()).toBe(0);
    await update(rerender, { panelShown: false });
    expect(fits()).toBe(0);
    // Shown but the box is still 0x0 (panelShown only falls on the pane's first ResizeObserver tick).
    await update(rerender, { panelShown: true });
    expect(fits()).toBe(0);
    expect(cameraLog).not.toContain('invalidateSize');

    size.w = 900;
    size.h = 600;
    await update(rerender, { panelShown: false });
    await update(rerender, { panelShown: true });
    expect(fits()).toBe(1);
    expect(fitArgs[0][0]).toEqual(AREA);
    expect(fitArgs[0][1]).toEqual({ padding: [28, 28], animate: false });
    expect(cameraLog.indexOf('invalidateSize')).toBeLessThan(cameraLog.indexOf('fitBounds'));

    await update(rerender, { panelShown: false });
    await update(rerender, { panelShown: true });
    expect(fits()).toBe(1);
  });

  it('control: a map that opened sized adopts the opening fit and fits nothing on its first run', async () => {
    size.w = 900;
    size.h = 600;
    const { rerender } = await renderTab({ panelShown: true });
    expect(fits()).toBe(0);
    await update(rerender, { panelShown: false });
    await update(rerender, { panelShown: true });
    expect(fits()).toBe(0);
  });

  it('with no heat field, refits to the catalogue box at 60px padding once shown', async () => {
    const { rerender } = await renderTab({ heat: null, panelShown: false });
    expect(fits()).toBe(0);
    size.w = 900;
    size.h = 600;
    await update(rerender, { heat: null, panelShown: true });
    expect(fits()).toBe(1);
    expect(fitArgs[0][0]).toEqual(LOCATIONS.map((l) => [l.lat, l.lon]));
    expect(fitArgs[0][1]).toEqual({ padding: [60, 60], animate: false });
  });
});
