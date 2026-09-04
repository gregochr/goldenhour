/**
 * `MapView`'s phone chrome (map-tab-v2-plan.md §3 P12, `docs/design/map-tab-v2/README.md`
 * "Responsive" table) — the integration-level half of the phone build. `FiltersPopover.test.jsx`/
 * `RegionsJump.test.jsx` already prove each menu's own sheet-vs-popover branch in isolation; this
 * file proves what only a real `MapView` mount can: the `.wf-map-tab` scoping class lands on the
 * TAB and never on the OVERLAY, the Legend is withheld entirely on the phone via a real mount (not
 * merely a prop assertion), the counts footer's second-line class exists for the CSS media query to
 * hide, and — the sharpest claim — that opening one bottom sheet while another is open SWAPS rather
 * than stacks, driven by the SAME `openMapMenu` exclusivity that already governs the popovers
 * (map-tab-v2-plan.md §3 P7), with the real `BottomSheet` component (not mocked) doing the portalling
 * so a genuine double-mount would actually show up as two `bottom-sheet` nodes.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';

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
  Marker: React.forwardRef(function MockMarker({ children }, ref) {
    React.useImperativeHandle(ref, () => ({ openPopup: () => {} }));
    return <div>{children}</div>;
  }),
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, offsetWidth: 800, parentElement: document.body }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    panInside: () => {},
    getSize: () => ({ x: 800, y: 500 }),
    latLngToContainerPoint: () => ({ x: 400, y: 250 }),
  }),
}));


// The heavier canvas/label/callout layers are irrelevant to this file's own claims (the chrome
// re-arrangement and the sheet exclusivity) — stubbed to inert probes, `MapViewSelectionOrdering.
// test.jsx`'s own pattern.
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => null }));
vi.mock('../components/map/MapCallout.jsx', () => ({ default: () => null }));
vi.mock('../components/map/PinsLayer.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
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

// Deliberately NOT mocking `BottomSheet` — this file's whole point is proving what the REAL
// component does when `openMapMenu` swaps between menus, so the portal has to be real too.

// Mutable per-test — the phone describe blocks flip this; everything else runs at the
// desktop/tablet default, matching how every pre-P12 `MapView*.test.jsx` file mocks this hook.
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};

function heatProp(overrides = {}) {
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
    ...overrides,
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

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={[makeLocation()]}
        date={TODAY}
        autoEventType={null}
        heat={heatProp()}
        {...props}
      />,
    );
  });
  return result;
}

beforeEach(() => {
  mockIsMobile = false;
  localStorage.clear();
});
afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('MapView (tab) — the `.wf-map-tab` scoping class (map-tab-v2-plan.md §3 P12)', () => {
  it('carries `wf-map-tab` on the tab mount, desktop and phone alike', async () => {
    const { container: desktopContainer } = await renderMap();
    expect(desktopContainer.querySelector('.wf-map-tab')).not.toBeNull();

    mockIsMobile = true;
    const { container: phoneContainer } = await renderMap();
    expect(phoneContainer.querySelector('.wf-map-tab')).not.toBeNull();
  });

  it('is ABSENT on the overlay mount — the plan\'s "overlay untouched" rule, made structural', async () => {
    const { container } = await renderMap({ overlayMode: true, heat: null });
    expect(container.querySelector('.wf-map-tab')).toBeNull();
  });
});

describe('MapView (tab) — Legend hidden entirely on the phone (README "Responsive" table)', () => {
  it('mounts the Legend chip on desktop/tablet', async () => {
    await renderMap();
    expect(screen.getByTestId('wf-legend-chip')).toBeInTheDocument();
  });

  it('mounts NO legend chip, panel, or root at all on the phone', async () => {
    mockIsMobile = true;
    await renderMap();
    expect(screen.queryByTestId('wf-legend-chip')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-legend')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-legend-panel')).not.toBeInTheDocument();
  });
});

describe('MapView (tab) — the counts footer\'s second line carries a CSS hook for the phone media query', () => {
  it('the second line ("Beyond 3h…") carries the `wf-map-counts-second` class', async () => {
    await renderMap({ heat: heatProp({ hasHome: false }) });
    // With no home, `heat.hasHome` false, `beyondRegionNames` defaults empty — force a second line
    // via an explicit override so this test does not depend on `planningArea`'s own derivation.
    const { unmount } = await renderMap({ heat: heatProp({ beyondRegionNames: ['The Borders'] }) });
    const second = await screen.findByTestId('wf-map-counts-second');
    expect(second).toHaveClass('wf-map-counts-second');
    unmount();
  });
});

describe('MapView (tab) — overlay never carries a sheet-capable chip at all, on any viewport', () => {
  it('overlayMode renders none of the tab-only chrome (window control, filters, regions, legend)', async () => {
    mockIsMobile = true;
    await renderMap({ overlayMode: true, heat: null });
    expect(screen.queryByTestId('wf-win-control')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-filters')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-jump')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-legend')).not.toBeInTheDocument();
    // No `BottomSheet` can therefore ever be raised by the overlay's own mount, on any viewport —
    // the structural half of the double-BottomSheet hazard re-check (map-tab-v2-plan.md §3 P12):
    // the ONLY sheets the phone tab can raise come from components the overlay never mounts at all.
    expect(screen.queryByTestId('bottom-sheet')).not.toBeInTheDocument();
  });
});

describe('MapView (tab) — phone sheet exclusivity: swap, not stack (map-tab-v2-plan.md §3 P7/P12)', () => {
  beforeEach(() => { mockIsMobile = true; });

  it('opening Filters while Regions is open SWAPS — never more than one bottom sheet at a time', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.getByTestId('bottom-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    // Exactly ONE `bottom-sheet` node in the whole document — not two stacked portals.
    expect(screen.getAllByTestId('bottom-sheet')).toHaveLength(1);
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-jump-menu')).not.toBeInTheDocument();
  });

  it('the reverse swap — Filters open, then Regions — behaves identically', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.getAllByTestId('bottom-sheet')).toHaveLength(1);
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
  });

  it('closing the open sheet via its own backdrop leaves no sheet mounted at all', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('bottom-sheet')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('bottom-sheet-overlay'));
    expect(screen.queryByTestId('bottom-sheet')).not.toBeInTheDocument();
  });
});
