/**
 * `MapView` inside the map view (`docs/engineering/one-screen-plan.md` O1): what it publishes through
 * `MapScreenContext` (the header's served fields, the route, its Escape ladder), the one `WindowControl` portalled up into
 * the header, and the lens note that replaced the door's landing strip.
 *
 * The harness is `MapViewPlanHandoff.test.jsx`'s own: a minimal Leaflet, with the field, labels, callout and filter
 * popover mocked to probes.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';

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
  // `latLngBounds` for `FitBoundsController` (the `focusPoints` door fits through it).
  const latLngBounds = (points) => ({ points });
  const L = { icon, divIcon, point, Control, DomEvent, latLngBounds };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: (...args) => { flyToCalls.push(args); },
    fitBounds: (bounds) => { fitBoundsCalls.push(bounds); },
    addControl: () => {},
    _corner: cornerEl,
  }),
}));

/** Every `map.flyTo` call - the camera a LOCATION door flies, read off the map. */
const flyToCalls = [];

/** Every `map.fitBounds` call - what the `focusPoints` door's camera does, read off the map. */
const fitBoundsCalls = [];

/** The field, recording the points it is handed - the lens is asserted on what the kernel gets. */
const heatLayerCalls = [];
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => { heatLayerCalls.push(props); return <div data-testid="map-heat-layer" />; },
}));

/** Captures every props object handed to `MapLabels` — the filtered/visible pool, for the region
 *  scope-flip assertion (`heatArea`/`areaSpots` vs `spots`). */
const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => { mapLabelsCalls.push(props); return null; },
}));

/** The selection callout, mocked to a probe — proves a location the carried floor filters OUT of
 *  the visible pool still gets its callout, because the effect is a direct state write. */
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => (
    <div data-testid="probe-callout">
      <span data-testid="probe-callout-name">{props.location?.name ?? ''}</span>
    </div>
  ),
}));

/** Every filter/scope figure the effect writes, read straight off what `MapView` hands the REAL
 *  filter popover — never re-derived, so this file cannot disagree with the production wiring
 *  about which prop carries which state. `DRIVE_TIME_TIERS` is re-exported for the other chrome that
 *  imports it from this same module. */
const filtersPopoverCalls = [];
vi.mock('../components/map/FiltersPopover.jsx', () => ({
  default: (props) => { filtersPopoverCalls.push(props); return null; },
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
import { MapScreenContext } from '../context/MapScreenContext.jsx';
import { escapeAnswered } from '../utils/mapEscape.js';

const TODAY = '2026-01-15';

const NEAR = {
  id: 1, name: 'Near', lat: 55.61, lon: -1.71, regionName: 'North East', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    sunrise: { rating: 5, solarEventTime: `${TODAY}T08:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
  }]]),
};
const OTHER = {
  id: 2, name: 'Other', lat: 55.62, lon: -1.72, regionName: 'North East', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 50, goldenHourPotential: 50 },
  }]]),
};

function spotOf(loc, rating = 5) {
  return {
    id: loc.id, name: loc.name, lat: loc.lat, lng: loc.lon, regionName: loc.regionName,
    rid: loc.regionName, bortleClass: 4, r: [rating],
  };
}

const HEAT = {
  enabled: true,
  hasHome: false,
  spots: [spotOf(NEAR), spotOf(OTHER, 4)],
  areaSpots: [spotOf(NEAR), spotOf(OTHER, 4)],
  pointsByKey: new Map([[`${TODAY}:SUNSET`, []], [`${TODAY}:SUNRISE`, []]]),
  windows: [
    { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1 },
    { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'Tonight sunrise', time: '08:12', bestRating: 5, conf: 1 },
  ],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[53.9, -3.4], [55.7, -1.3]],
};

/** The Plan's own card for the sunset window, as the shell's `windowCards` carry it: the header names its served fields. */
const SUNSET_CARD = {
  key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', verdictLabel: 'Worth it', bestRating: 5, confidence: 'high',
  pick: null, allSpots: [], reachMeasured: false,
};

const header = vi.fn();
const route = vi.fn();
const escapeRegistered = vi.fn();

/** The view's context, with the header slot a real node so the window control has somewhere to be portalled. */
function ViewContext({ slot, children }) {
  const value = React.useMemo(() => ({
    windowSlot: slot, publishHeader: header, publishRoute: route, publishEscape: escapeRegistered,
  }), [slot]);
  return <MapScreenContext.Provider value={value}>{children}</MapScreenContext.Provider>;
}

async function renderTab(props = {}, { slot = null } = {}) {
  let result;
  await act(async () => {
    result = render(
      <ViewContext slot={slot}>
        <MapView
          locations={[NEAR, OTHER]}
          date={TODAY}
          autoEventType="SUNSET"
          forecastDates={[TODAY]}
          heat={HEAT}
          windowCardsByKey={new Map([[SUNSET_CARD.key, SUNSET_CARD]])}
          {...props}
        />
      </ViewContext>,
    );
  });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  mapLabelsCalls.length = 0;
  filtersPopoverCalls.length = 0;
  heatLayerCalls.length = 0;
  fitBoundsCalls.length = 0;
  flyToCalls.length = 0;
  header.mockClear();
  route.mockClear();
  escapeRegistered.mockClear();
  cornerEl = document.createElement('div');
  cornerEl.className = 'leaflet-bottom leaflet-right';
  document.body.appendChild(cornerEl);
});

afterEach(() => {
  localStorage.clear();
  cornerEl.remove();
  vi.clearAllMocks();
});

const door = (extra) => ({
  source: 'plan', eventType: 'SUNSET', date: TODAY, region: null, minRating: null, limitMinutes: null,
  locationName: null, nonce: 1, ...extra,
});

describe('MapView inside the map view: what it publishes', () => {
  it('names the window on screen and states its served fields, from the Plan card', async () => {
    await renderTab();
    expect(header).toHaveBeenLastCalledWith({
      title: expect.stringMatching(/sunset/i), verdictLabel: 'Worth it', bestRating: 5, confidence: 'high',
    });
  });

  it('states nothing about a window the Plan has no card for', async () => {
    await renderTab({ windowCardsByKey: new Map() });
    expect(header).toHaveBeenLastCalledWith({
      title: expect.stringMatching(/sunset/i), verdictLabel: null, bestRating: null, confidence: null,
    });
  });

  it('publishes the route: the window, and the selected place by id once one is selected', async () => {
    await renderTab({ planHandoff: door({ locationName: 'Near' }) });
    expect(route).toHaveBeenLastCalledWith({ date: TODAY, eventType: 'SUNSET', locationId: 1 });
  });

  it('publishes an Escape ladder that answers whether it closed a layer: the callout first, then nothing', async () => {
    await renderTab({ planHandoff: door({ locationName: 'Near' }) });
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
    const ladder = escapeRegistered.mock.calls.at(-1)[0];
    let closed;
    await act(async () => { closed = ladder(); });
    expect(closed).toBe(true);
    expect(screen.queryByTestId('probe-callout')).toBeNull();
    await act(async () => { closed = ladder(); });
    expect(closed).toBe(false);
  });

  it('a pane-level Escape marks the press answered only when it closed something', async () => {
    await renderTab({ planHandoff: door({ locationName: 'Near' }) });
    const pane = screen.getByTestId('wf-map-pane');
    const first = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    await act(async () => { pane.dispatchEvent(first); });
    expect(escapeAnswered(first)).toBe(true);
    const second = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    await act(async () => { pane.dispatchEvent(second); });
    expect(escapeAnswered(second)).toBe(false);
  });
});

describe('MapView inside the map view: the one window control', () => {
  it('is portalled into the header slot, once, and not drawn in the map chrome', async () => {
    const slot = document.createElement('div');
    document.body.appendChild(slot);
    await renderTab({}, { slot });
    expect(slot.querySelector('[data-testid="wf-win-control"]')).not.toBeNull();
    expect(screen.getAllByTestId('wf-win-control')).toHaveLength(1);
    expect(screen.getByTestId('wf-map-chrome-tl').querySelector('[data-testid="wf-win-control"]')).toBeNull();
    slot.remove();
  });

  it('draws in the map chrome when there is no view around it', async () => {
    await renderTab({}, { slot: null });
    expect(screen.getByTestId('wf-map-chrome-tl').querySelector('[data-testid="wf-win-control"]')).not.toBeNull();
  });
});

describe('MapView: the lens note replaces the landing strip', () => {
  it('states a named-spot lens a door applied, and Clear lifts it', async () => {
    await renderTab({ planHandoff: door({ focusNames: ['Near'], title: 'Moors', caption: '1 spot' }) });
    expect(screen.getByTestId('map-lens-note')).toHaveTextContent('Showing 1 spot');
    await act(async () => { fireEvent.click(screen.getByTestId('map-lens-note-clear')); });
    expect(screen.queryByTestId('map-lens-note')).toBeNull();
  });

  it('draws no note for a door that applied no lens, and never the old landing strip', async () => {
    await renderTab({ planHandoff: door({}) });
    expect(screen.queryByTestId('map-lens-note')).toBeNull();
    expect(screen.queryByTestId('wf-map-breadcrumb')).toBeNull();
  });
});
