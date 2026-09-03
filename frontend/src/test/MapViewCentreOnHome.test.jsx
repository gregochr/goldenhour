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
import { act, render, screen, fireEvent } from '@testing-library/react';

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

// `heatOffered` (map-tab-v2-plan.md §3 P11's own ⌂ tests below) lazily loads this — mocked the same
// way `MapViewHeat.test.jsx` mocks it, so the field itself never has to actually paint here.
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: () => <div data-testid="map-heat-layer" />,
}));

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
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';
const HOME = { lat: 54.97, lon: -1.61 };

/**
 * Two regions for `⌂`'s own tests below (map-tab-v2-plan.md §3 P11) — "The Borders" sits outside
 * `AREA_SPOTS`, the fixture's "My area", which is what a standing region-jump override needs.
 * ⚠️ The two boxes must carry DIFFERENT numbers (`MapViewHeat.test.jsx`'s own warning): a deep-equal
 * assertion would pass even with the ternary's arms swapped.
 */
const SPOTS = [
  {
    id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, regionName: 'North East', rid: 'North East',
  },
  {
    id: 2, name: 'Kelso', lat: 56.20, lng: -2.43, regionName: 'The Borders', rid: 'The Borders',
  },
];
const AREA_SPOTS = [SPOTS[0]];
const AREA_BOUNDS = [[54.3, -3.4], [55.7, -1.3]];
const CATALOGUE_BOUNDS = [[54.3, -3.4], [56.4, -1.3]];

function heatProp(overrides = {}) {
  return {
    enabled: true,
    hasHome: true,
    spots: SPOTS,
    areaSpots: AREA_SPOTS,
    pointsByKey: new Map(),
    windows: [],
    areaBounds: AREA_BOUNDS,
    catalogueBounds: CATALOGUE_BOUNDS,
    ...overrides,
  };
}

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
    fitBounds: vi.fn(),
    _corner: corner,
  };
});

afterEach(() => {
  vi.useRealTimers();
  corner.remove();
  localStorage.clear();
});

/** Synchronous — no `heat` prop, nothing lazy to await. Used by every test that predates P11. */
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

/**
 * Async — for `⌂`'s own tests below, which pass a `heat` prop and so make `heatOffered` true: that
 * lazily loads `MapHeatLayer` (mocked above) via `Suspense`, and the resolution needs an `act`
 * boundary the same way `MapViewHeat.test.jsx`'s own `renderMap` provides one.
 */
async function renderHeatMap(overrides = {}) {
  let result;
  await act(async () => {
    result = renderMap(overrides);
  });
  return result;
}

/** Opens the Map tab's filters popover — `FiltersPopover`'s own scope segment lives behind it. */
function openFilters() {
  fireEvent.click(screen.getByTestId('wf-filters-chip'));
}

describe('centre on home', () => {
  it('mounts as its own control in the top-left stack, not inside the zoom bar', () => {
    renderMap({ homeCoords: HOME });

    const button = screen.getByTestId('centre-on-home');
    // ⚠️ State-truthful, not "Centre on home" — the accname-drift bug class this phase already
    // fixed once on `MastheadTickLine`'s origin control (adversarial review + live browser finding):
    // the name must describe what a click NOW does (reset scope to My area and refit).
    expect(button).toHaveAccessibleName('Reset to My area');
    // Its own container, in the corner Leaflet stacks controls into.
    const container = button.closest('.map-home-control');
    expect(container).not.toBeNull();
    expect(container.classList.contains('leaflet-control')).toBe(true);
    expect(container.parentElement).toBe(corner);
  });

  // ⚠️ map-tab-v2-plan.md §3 P11 retired the radius-framed `flyTo` this control used to perform on
  // click (see `CentreOnHomeControl`'s own class doc for the reconciliation) — it now resets scope
  // to My area and refits `HeatBoundsController`'s own box, `animate:false`. This test only proves
  // the OLD path is gone; the new one (below, "⌂ resets scope to My area") needs the `heat` fixture.
  it('does not call flyTo any more — clicking with a home coordinate is a scope reset now, not a camera fly', () => {
    renderMap({ homeCoords: HOME });

    fireEvent.click(screen.getByTestId('centre-on-home'));

    expect(flyTo).not.toHaveBeenCalled();
  });

  it('stays visible with no postcode, and points at where to set one', () => {
    // Hiding it would explain nothing. The disabled state IS the explanation, and the click is
    // the route — so it must remain clickable rather than carry the `disabled` attribute.
    const onOpenSettings = vi.fn();
    renderMap({ homeCoords: null, onOpenSettings });

    const button = screen.getByTestId('centre-on-home');
    expect(button).toHaveAttribute('title', 'Set your home postcode in Settings');
    // Both channels state the SAME thing a click actually does in this state — opens Settings,
    // never a scope reset that would be a no-op with no home to have measured anything from.
    expect(button).toHaveAccessibleName('Set your home postcode in Settings');
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

/**
 * `⌂` resets scope to My area and refits (map-tab-v2-plan.md §3 P11) — the reconciliation between
 * the design's own `zhome` and this control's pre-P11 radius-framed `flyTo` (see
 * `CentreOnHomeControl`'s class doc). Needs the `heat`/`fitBounds` wiring the describe block above
 * has no use for, hence `renderHeatMap`/`heatProp` rather than the plain `renderMap`.
 */
describe('centre on home — ⌂ resets scope to My area and refits (map-tab-v2-plan.md §3 P11)', () => {
  it('resets an already-flipped "Whole catalogue" scope back to My area, animate:false', async () => {
    await renderHeatMap({ homeCoords: HOME, heat: heatProp() });
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: 'Whole catalogue' }));
    mapStub.fitBounds.mockClear();

    fireEvent.click(screen.getByTestId('centre-on-home'));

    expect(mapStub.fitBounds).toHaveBeenCalledTimes(1);
    expect(mapStub.fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
    expect(screen.getByRole('button', { name: 'My area' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('clears a standing region-jump override too — never leaves the camera on a stale region box', async () => {
    await renderHeatMap({ homeCoords: HOME, heat: heatProp() });
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    fireEvent.click(screen.getAllByTestId('wf-jump-row').find((r) => r.textContent.includes('The Borders')));
    mapStub.fitBounds.mockClear();

    fireEvent.click(screen.getByTestId('centre-on-home'));

    // Had the jump's override survived, this would still name Kelso's own box (a THIRD box, never
    // equal to AREA_BOUNDS) instead of resetting to the area.
    expect(mapStub.fitBounds).toHaveBeenCalledTimes(1);
    expect(mapStub.fitBounds).toHaveBeenCalledWith(AREA_BOUNDS, { padding: [28, 28], animate: false });
  });

  it('does not touch an active filter — README/plan: "does not clear filters"', async () => {
    await renderHeatMap({ homeCoords: HOME, heat: heatProp() });
    openFilters();
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters (1)');

    fireEvent.click(screen.getByTestId('centre-on-home'));

    // `resetToMyArea` never touches `openMapMenu` (unlike a jump-row selection, which closes its own
    // menu), so the panel is still open and the toggle's own node is still on screen to check
    // directly — no second `openFilters()` press that could instead close what is already open.
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters (1)');
    expect(screen.getByTestId('dark-sky-filter-toggle')).toHaveClass('on');
  });

  it('with no home postcode, keeps opening Settings — resetting would be a genuine no-op', async () => {
    // `heatArea` reads the same box either way with no postcode (`FiltersPopover`'s own scope row is
    // withheld for the identical reason), so `⌂`'s pre-P11 fallback survives untouched here.
    const onOpenSettings = vi.fn();
    await renderHeatMap({ homeCoords: null, heat: heatProp({ hasHome: false }), onOpenSettings });

    fireEvent.click(screen.getByTestId('centre-on-home'));

    expect(onOpenSettings).toHaveBeenCalledTimes(1);
    expect(mapStub.fitBounds).not.toHaveBeenCalled();
  });
});
