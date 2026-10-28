/**
 * The Map pane is mounted once and only hidden between visits, so Leaflet caches a 0x0 size. A door's
 * camera move made before it re-measures zooms to the cap (NaN -> Infinity -> `maxZoom`). The three
 * door controllers (`FitBoundsController`, `FlyToController`, `HeatBoundsController`) therefore hold
 * their move while `panelShown` is false and re-measure (`invalidateSize`) right before moving.
 *
 * The harness is a trimmed copy of `MapViewPlanHandoff.test.jsx`'s: a stable recording map, so the
 * order of `invalidateSize` / `fitBounds` / `flyTo` can be asserted.
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
const mockMap = {
  eachLayer: () => {},
  getContainer: () => ({ clientHeight: 500 }),
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
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
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

const DOOR_BASE = {
  source: 'plan', eventType: 'SUNSET', date: TODAY, region: null,
  minRating: null, limitMinutes: null, locationName: null,
};
const PINS = [[55.61, -1.71], [54.45, -3.0]];

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
const flies = () => cameraLog.filter((c) => c === 'flyTo').length;

beforeEach(() => {
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

describe('MapView door camera - held while the Map pane is hidden', () => {
  it('a focusPoints fit waits for panelShown, re-measures first, then fits once with the door options', async () => {
    const door = { ...DOOR_BASE, focusPoints: PINS, nonce: 1 };
    const { rerender } = await renderTab({ planHandoff: door, panelShown: false });
    expect(fits()).toBe(0);

    cameraLog.length = 0;
    await update(rerender, { planHandoff: door, panelShown: true });
    expect(fits()).toBe(1);
    expect(fitArgs[0][0].points).toEqual(PINS);
    expect(fitArgs[0][1]).toEqual({ padding: [60, 60], maxZoom: 12, animate: true });
    expect(cameraLog.indexOf('invalidateSize')).toBeGreaterThanOrEqual(0);
    expect(cameraLog.indexOf('invalidateSize')).toBeLessThan(cameraLog.indexOf('fitBounds'));
  });

  it('a pane visible throughout fits once; a plain rerender or hide/show with the same nonce does not refit', async () => {
    const door = { ...DOOR_BASE, focusPoints: PINS, nonce: 1 };
    const { rerender } = await renderTab({ planHandoff: door, panelShown: true });
    expect(fits()).toBe(1);

    await update(rerender, { planHandoff: door, panelShown: true });
    await update(rerender, { planHandoff: door, panelShown: false });
    await update(rerender, { planHandoff: door, panelShown: true });
    expect(fits()).toBe(1);
  });

  it('a door arriving while hidden fits on return, and a repeat door (new nonce) fits again', async () => {
    const { rerender } = await renderTab({ planHandoff: null, panelShown: false });
    await update(rerender, { planHandoff: { ...DOOR_BASE, focusPoints: PINS, nonce: 1 }, panelShown: false });
    expect(fits()).toBe(0);
    await update(rerender, { planHandoff: { ...DOOR_BASE, focusPoints: PINS, nonce: 1 }, panelShown: true });
    expect(fits()).toBe(1);
    await update(rerender, { planHandoff: { ...DOOR_BASE, focusPoints: PINS, nonce: 2 }, panelShown: true });
    expect(fits()).toBe(2);
  });

  it('the hold follows panelShown, not paneVisible: window blur alone does not hold a fit', async () => {
    await renderTab({
      planHandoff: { ...DOOR_BASE, focusPoints: PINS, nonce: 1 },
      panelShown: true,
      paneVisible: false,
    });
    expect(fits()).toBe(1);
  });

  it('a locationName door flies only once the pane is shown, after a re-measure', async () => {
    const door = { ...DOOR_BASE, locationName: 'Near', nonce: 1 };
    const { rerender } = await renderTab({ planHandoff: door, panelShown: false });
    expect(flies()).toBe(0);

    cameraLog.length = 0;
    await update(rerender, { planHandoff: door, panelShown: true });
    expect(flies()).toBe(1);
    expect(cameraLog.indexOf('invalidateSize')).toBeLessThan(cameraLog.indexOf('flyTo'));
  });

  it('a region jump (HeatBoundsController) made while hidden fits only on becoming shown, once', async () => {
    const { rerender } = await renderTab({ planHandoff: null, panelShown: false });
    expect(fits()).toBe(0);

    const door = { ...DOOR_BASE, region: 'Lake District', nonce: 1 };
    await update(rerender, { planHandoff: door, panelShown: false });
    expect(fits()).toBe(0);

    cameraLog.length = 0;
    await update(rerender, { planHandoff: door, panelShown: true });
    expect(fits()).toBe(1);
    expect(cameraLog.indexOf('invalidateSize')).toBeLessThan(cameraLog.indexOf('fitBounds'));
    expect(fitArgs.at(-1)[1].animate).toBe(false);

    await update(rerender, { planHandoff: door, panelShown: false });
    await update(rerender, { planHandoff: door, panelShown: true });
    expect(fits()).toBe(1);
  });
});
