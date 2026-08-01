/**
 * Tests for the map's "centre on home" control.
 *
 * It is a real Leaflet control rather than a chip floated over the map, so the stub below is a
 * working miniature of `L.Control`: `addTo` calls `onAdd` and appends the returned element to a
 * corner container, exactly as Leaflet does. That is what lets these tests assert the thing the
 * user actually gets — a button in the top-left stack — rather than a React element in isolation.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

// ── Leaflet stub, with just enough Control machinery to mount a real one ─────

const flyTo = vi.fn();
const removedControls = [];

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = () => ({});

  class Control {
    constructor(options = {}) { this.options = options; }

    addTo(map) {
      this._container = this.onAdd(map);
      this._container.classList.add('leaflet-control');
      map._corner.appendChild(this._container);
      return this;
    }

    remove() {
      removedControls.push(this);
      this._container?.remove();
      return this;
    }
  }

  const DomEvent = {
    disableClickPropagation: vi.fn(),
    disableScrollPropagation: vi.fn(),
  };
  const DomUtil = { create: (tag, cls) => Object.assign(document.createElement(tag), { className: cls }) };
  const L = { icon, divIcon, Control, DomEvent, DomUtil };
  return { default: L, ...L };
});

vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

// The corner container the stubbed Control appends into. Attached to the document so the portal's
// button is findable by the usual queries.
let corner;
// One map instance per test, not one per render: react-leaflet's `useMap` returns a stable
// instance, and a fresh object each call would re-run every effect keyed on it — which would let
// this suite pass while the real component added and removed its control on every render.
let mapStub;

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="leaflet-map">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => mapStub,
}));

vi.mock('react-leaflet-cluster', () => ({ default: ({ children }) => <div>{children}</div> }));

// ── App dependencies ─────────────────────────────────────────────────────────

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
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
  RATING_COLOURS: { 1: '#A32D2D', 2: '#D85A30', 3: '#FAC775', 4: '#97C459', 5: '#3B6D11' },
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';
const HOME = { lat: 54.97, lon: -1.61 };

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
  flyTo.mockClear();
  removedControls.length = 0;
  corner = document.createElement('div');
  corner.className = 'leaflet-top leaflet-left';
  document.body.appendChild(corner);
  mapStub = {
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    addControl: () => {},
    // 900 x 500 px: the shorter axis is 500, so the radius has 250px to fit into.
    getSize: () => ({ x: 900, y: 500 }),
    getMinZoom: () => 3,
    getMaxZoom: () => 19,
    flyTo,
    _corner: corner,
  };
});

afterEach(() => {
  vi.useRealTimers();
  corner.remove();
  localStorage.clear();
});

function renderMap(overrides = {}) {
  return render(
    <MapView
      locations={[{
        name: 'Loc0',
        lat: 55,
        lon: -1.7,
        locationType: ['LANDSCAPE'],
        forecastsByDate: new Map([[TODAY, {
          sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00` },
          sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00` },
        }]]),
      }]}
      date={TODAY}
      autoEventType={null}
      {...overrides}
    />,
  );
}

describe('centre on home', () => {
  it('mounts as its own control in the top-left stack, not inside the zoom bar', () => {
    renderMap({ homeCoords: HOME });

    const button = screen.getByTestId('centre-on-home');
    expect(button).toHaveAccessibleName('Centre on home');
    // Its own container, in the corner Leaflet stacks controls into.
    const container = button.closest('.map-home-control');
    expect(container).not.toBeNull();
    expect(container.classList.contains('leaflet-control')).toBe(true);
    expect(container.parentElement).toBe(corner);
  });

  it('recentres on home, framed to the radius the user calls local', () => {
    // 30 miles = 48,280 m, fitting into 250px → 193 m/px. At 55°N the zoom-0 scale is
    // 156543 * cos(55°) = 89,796 m/px, so the zoom is floor(log2(89796 / 193)) = 8.
    renderMap({ homeCoords: HOME, homeRadiusMiles: 30 });

    fireEvent.click(screen.getByTestId('centre-on-home'));

    expect(flyTo).toHaveBeenCalledWith([HOME.lat, HOME.lon], 8, { duration: 0.6 });
  });

  it('zooms in further for a tighter radius — the frame follows the setting, not a constant', () => {
    // A user who calls 8 miles local should not be shown 30. Halving the radius twice buys two
    // zoom levels, which is exactly what a fixed HOME_ZOOM would have thrown away.
    renderMap({ homeCoords: HOME, homeRadiusMiles: 8 });

    fireEvent.click(screen.getByTestId('centre-on-home'));

    expect(flyTo).toHaveBeenCalledWith([HOME.lat, HOME.lon], 10, { duration: 0.6 });
  });

  it('falls back to 30 miles when no radius has been chosen', () => {
    renderMap({ homeCoords: HOME });

    fireEvent.click(screen.getByTestId('centre-on-home'));

    expect(flyTo).toHaveBeenCalledWith([HOME.lat, HOME.lon], 8, { duration: 0.6 });
  });

  it('stays visible with no postcode, and points at where to set one', () => {
    // Hiding it would explain nothing. The disabled state IS the explanation, and the click is
    // the route — so it must remain clickable rather than carry the `disabled` attribute.
    const onOpenSettings = vi.fn();
    renderMap({ homeCoords: null, onOpenSettings });

    const button = screen.getByTestId('centre-on-home');
    expect(button).toHaveAttribute('title', 'Set your home postcode in Settings');
    expect(button).toHaveAttribute('data-disabled', 'true');
    expect(button).not.toBeDisabled();

    fireEvent.click(button);

    expect(onOpenSettings).toHaveBeenCalledTimes(1);
    expect(flyTo).not.toHaveBeenCalled();
  });

  it('is absent from the Plan tab overlay, which is already framed on its spot', () => {
    renderMap({ homeCoords: HOME, overlayMode: true });

    expect(screen.queryByTestId('centre-on-home')).not.toBeInTheDocument();
  });

  it('takes its control off the map on unmount', () => {
    const { unmount } = renderMap({ homeCoords: HOME });
    expect(corner.children.length).toBe(1);

    unmount();

    expect(removedControls.length).toBe(1);
    expect(corner.children.length).toBe(0);
  });
});
