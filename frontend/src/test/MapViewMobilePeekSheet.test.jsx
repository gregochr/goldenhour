/**
 * The Map tab's phone peek sheet, at the `MapView` integration level (map-mobile-sheet-plan.md
 * §3 M2). `MapPeekSheet.test.jsx`/`mapPeek.test.js` prove the new component and its pure logic in
 * isolation; this file proves the WIRING only a real `MapView` mount can: `.wf-map-chrome-tr` is
 * gone on the phone and present on desktop from the SAME fixture, the pill's phone override opens
 * the sheet's Windows section instead of the dropdown, a Windows row selects without closing the
 * sheet, the drilldown row opens the window panel and collapses the sheet by exclusivity, and a
 * Leaflet map touch collapses an open section (never a press inside the sheet itself).
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

/** Every handlers object any `useMapEvents` caller registered this render — `ZoomTracker`'s,
 * `BoundsTracker`'s and, phone-only, `SheetDismissOnMapTouch`'s — `MapViewBackgroundClick.test.jsx`'s
 * own collect-as-a-list pattern, so firing one kind of event does not depend on registration order. */
let mapEventHandlers = [];
const mockMapInstance = {
  getZoom: () => 9,
  getBounds: () => ({ pad: () => ({ contains: () => false }) }),
};
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    mapEventHandlers.push(handlers);
    return mockMapInstance;
  },
  // ⚠️ Deliberately NO `getBounds` here — `MapViewResponsivePhone.test.jsx`'s own mock omits it
  // for the identical reason: `BoundsTracker`'s effect deps are `[map, onBounds]`, and this mock
  // returns a NEW object literal every call, so a `map.getBounds()` that actually answered would
  // set state every render (a fresh bounds object each time), which — since `map`'s own identity
  // also changes every render — never stabilises and spins forever. Omitting the method makes
  // `map?.getBounds?.()` a silent no-op instead, exactly like every sibling `MapView*.test.jsx`
  // file's own mock already relies on.
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, offsetWidth: 800, parentElement: document.body }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    panInside: () => {},
    getSize: () => ({ x: 390, y: 844 }),
    latLngToContainerPoint: () => ({ x: 195, y: 400 }),
  }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));

// Mutable per-test — every describe block below flips this explicitly rather than relying on a
// fixed default, since this file's whole point is proving the SAME fixture diverges by viewport.
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

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
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
// A thin observable stub, not `() => null` — D-7's reverse-order clause ("a peek press clears the
// selection first") needs some visible proof the callout stood down, and `location` is exactly
// the prop that gates it (`!overlayMode && selectedLoc && activeMapEvent`, `MapView.jsx`).
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: ({ location }) => (location ? <div data-testid="mock-callout">{location.name}</div> : null),
}));
vi.mock('../components/map/PinsLayer.jsx', () => ({ default: () => null }));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => null }));
// The REAL `BottomSheet` is deliberately not mocked here — the Layers section's Regions/Filters
// swap-not-stack behaviour is `MapViewResponsivePhone.test.jsx`'s own claim, not this file's; a
// stub keeps this file's assertions about the PEEK SHEET itself uncluttered by that portal.
vi.mock('../components/BottomSheet.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const SPOT = {
  id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East',
};

function makeLocation() {
  return {
    id: SPOT.id,
    name: SPOT.name,
    lat: SPOT.lat,
    lon: SPOT.lng,
    regionName: SPOT.rid,
    bortleClass: 4,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  };
}

/** Two events on TODAY, so the pill's roster has more than one row and a step is meaningful. */
function heatProp() {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map(),
    windows: [
      { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'This morning', time: '08:24', bestRating: 4, conf: 1 },
      { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 4, conf: 1 },
    ],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  };
}

function mapElement(props = {}) {
  return (
    <MapView
      locations={[makeLocation()]}
      date={TODAY}
      autoEventType={null}
      heat={heatProp()}
      {...props}
    />
  );
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(mapElement(props));
  });
  return result;
}

/** Re-renders the SAME mount with new props — the shape of a prop landing after the tab is open
 * (`MapViewMobileQuietStart.test.jsx`'s own pattern), used here to simulate a Plan-tab handoff
 * selecting a location while a peek section is open. */
async function rerenderMap(result, props = {}) {
  await act(async () => {
    result.rerender(mapElement(props));
  });
}

beforeEach(() => {
  mockIsMobile = false;
  mapEventHandlers = [];
  localStorage.clear();
});
afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('MapView (tab) — `.wf-map-chrome-tr` is gone on the phone, present on desktop, same fixture (map-mobile-sheet-plan.md §3 M2 task 6)', () => {
  it('desktop: mounts `.wf-map-chrome-tr` and no peek sheet', async () => {
    mockIsMobile = false;
    await renderMap();
    expect(screen.getByTestId('wf-map-chrome-tr')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek')).not.toBeInTheDocument();
  });

  it('phone: mounts no `.wf-map-chrome-tr` at all, and the peek sheet instead', async () => {
    mockIsMobile = true;
    await renderMap();
    expect(screen.queryByTestId('wf-map-chrome-tr')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-map-peek')).toBeInTheDocument();
  });

  it('⚠️ desktop invariance: the Legend chip still mounts, unaffected by anything this phase changed', async () => {
    mockIsMobile = false;
    await renderMap();
    expect(screen.getByTestId('wf-legend-chip')).toBeInTheDocument();
  });

  it('the Legend chip does not mount on the phone (unchanged pre-existing rule, not new this phase)', async () => {
    mockIsMobile = true;
    await renderMap();
    expect(screen.queryByTestId('wf-legend-chip')).not.toBeInTheDocument();
  });
});

describe('MapView (tab) — the pill\'s phone override (map-mobile-sheet-plan.md §3 M2 task 4)', () => {
  it('phone: pressing the pill opens the peek sheet\'s Windows section, never the listbox dropdown', async () => {
    mockIsMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
    expect(screen.getByTestId('wf-map-peek').className).toContain('wf-map-peek-open');
    expect(screen.queryByTestId('wf-win-listbox')).not.toBeInTheDocument();
    // The pill itself carries no listbox popup semantics on the phone (task 4's contract).
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).not.toHaveAttribute('aria-haspopup');
    expect(pill).toHaveAttribute('aria-controls', 'wf-map-peek-body');
    expect(pill).toHaveAttribute('aria-expanded', 'true');
  });

  it('desktop: pressing the pill still opens the listbox dropdown, with its original popup semantics', async () => {
    mockIsMobile = false;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-listbox')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek')).not.toBeInTheDocument();
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).toHaveAttribute('aria-haspopup', 'listbox');
    expect(pill).toHaveAttribute('aria-controls', 'wf-win-listbox');
  });

  it('a SECOND pill press collapses the sheet again (the "OTHER WINDOWS" ⇄ "CLOSE" toggle) on the phone', async () => {
    mockIsMobile = true;
    await renderMap();
    const pill = screen.getByTestId('wf-win-pill');
    fireEvent.click(pill);
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
    fireEvent.click(pill);
    expect(screen.queryByTestId('wf-map-peek-body')).not.toBeInTheDocument();
  });
});

describe('MapView (tab) — the Windows section (map-mobile-sheet-plan.md §3 M2 task 5)', () => {
  it('a row press selects that window, and the sheet STAYS OPEN', async () => {
    mockIsMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const rows = screen.getAllByTestId('wf-map-peek-win-row');
    expect(rows.length).toBeGreaterThan(0);
    fireEvent.click(rows[0]);
    // Still open after the selection — rule for the Windows rows (unlike the desktop dropdown,
    // which closes on a row pick).
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
  });

  it('the drilldown row opens the window panel and collapses the sheet by exclusivity', async () => {
    mockIsMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const drilldown = screen.getByTestId('wf-map-peek-drilldown');
    fireEvent.click(drilldown);
    // `openMapMenu` moved to `'window-panel'`, which no longer starts with `'peek:'` — the sheet
    // collapses purely by `openMapMenu` exclusivity (D-1), with no dedicated close call.
    expect(screen.queryByTestId('wf-map-peek-body')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
  });
});

describe('MapView (tab) — map-touch collapse (map-mobile-sheet-plan.md §3 M2 task 7, README rule 4)', () => {
  it('a Leaflet `dragstart` collapses an open section', async () => {
    mockIsMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
    // `act`, not a bare loop — the handler's own `setOpenMapMenu` must flush before the assertion
    // reads the DOM, and a direct (non-`fireEvent`) call is not itself wrapped by anything.
    await act(async () => {
      for (const handlers of mapEventHandlers) handlers.dragstart?.({});
    });
    expect(screen.queryByTestId('wf-map-peek-body')).not.toBeInTheDocument();
  });

  it('a Leaflet `zoomstart` collapses an open section', async () => {
    mockIsMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    await act(async () => {
      for (const handlers of mapEventHandlers) handlers.zoomstart?.({});
    });
    expect(screen.queryByTestId('wf-map-peek-body')).not.toBeInTheDocument();
  });

  it('a press INSIDE the sheet never reaches the map listener, so it does not collapse the section', async () => {
    mockIsMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    // Firing a real DOM click on the sheet's own body — this never dispatches Leaflet's
    // `mousedown`/`dragstart`, which is the whole point: the sheet's markup lives outside
    // Leaflet's container, so a tap on it can never be misread as a map touch.
    fireEvent.mouseDown(screen.getByTestId('wf-map-peek-body'));
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
  });

  it('⚠️ D-7\'s REVERSE order: a peek press clears an existing selection FIRST, so the callout and an open sheet never coexist', async () => {
    mockIsMobile = true;
    const result = await renderMap();
    // Install a selection the same way a chip/pin/handoff would — the callout mock renders once
    // `selectedLocationName` resolves to a real location.
    await rerenderMap(result, { handoffLocationName: SPOT.name, handoffNonce: 1 });
    expect(screen.getByTestId('mock-callout')).toHaveTextContent(SPOT.name);

    // A peek press (the Layers button, so this does not depend on the Windows section's own
    // content) must clear that selection BEFORE growing the sheet — never the other way round,
    // which would place a card for the collapsed 74px under a 356px sheet a beat later.
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
  });

  it('an INSTALLED selection collapses an open section, however it arrived — a Plan-tab handoff (map-mobile-sheet-plan.md §3 M2 task 7)', async () => {
    mockIsMobile = true;
    const result = await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
    // A location handoff (`handoffLocationName`/`handoffNonce`) is one of the several writers of
    // `selectedLocationName` the plan names — chips and pins stop click propagation before the
    // map-touch listener ever sees them, so this effect is what catches THEM too, not merely this
    // one prop-driven route.
    await rerenderMap(result, { handoffLocationName: SPOT.name, handoffNonce: 1 });
    expect(screen.queryByTestId('wf-map-peek-body')).not.toBeInTheDocument();
  });

  it('does nothing on desktop — no `SheetDismissOnMapTouch` is mounted there at all', async () => {
    mockIsMobile = false;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-listbox')).toBeInTheDocument();
    await act(async () => {
      for (const handlers of mapEventHandlers) handlers.dragstart?.({});
    });
    // The desktop dropdown is untouched by a map drag — it has its own outside-dismiss/Escape
    // rules, none of which this fires.
    expect(screen.getByTestId('wf-win-listbox')).toBeInTheDocument();
  });
});
