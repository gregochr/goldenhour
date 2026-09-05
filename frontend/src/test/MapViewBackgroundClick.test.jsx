/**
 * The Map tab's ground-press rule — **inverted at map-landing L3** (`map-landing-plan.md` §3 L3,
 * `docs/design/map-landing/README.md` §5 "Panels do not close when you click the map").
 *
 * <p>It used to be "click empty map closes whatever popover is open". It is now: a press on the map
 * closes **nothing**. The week menu, the Regions list, Filters and the Legend are all *about* the
 * map, so reading "Thursday is Poor" and then panning to see where is one action, not two — and
 * losing the list halfway through made the panel feel like something to be got rid of. What a
 * ground press still does is **deselect a location**, because that is a selection rather than a
 * panel; that is the one distinction the design draws.
 *
 * <p>⚠️ The rule has TWO halves and this file pins both, because pinning either alone proves
 * nothing. `MapBackgroundClickController`'s Leaflet `click` is one; the four panels' own
 * `document`-level `mousedown` listeners are the other, and they fire — and commit — first. Editing
 * only the controller leaves the behaviour unchanged, which is exactly the trap
 * `MapBackgroundClickController`'s class doc records having been caught by in the browser rather
 * than by any test.
 *
 * <p>Leaflet's own marker click handlers stop propagation before a marker tap reaches the map's
 * `click` event, so that listener only ever fires for genuine empty ground; that guarantee is
 * Leaflet's, not this file's to re-prove.
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

/**
 * Every handlers object any `useMapEvents` caller registered this render — `ZoomTracker`'s
 * `{zoomend}` and, tab-only, `MapBackgroundClickController`'s `{click}`. Collected as a list (not
 * "last wins") so firing one kind of event does not depend on registration order.
 */
let mapEventHandlers = [];
const mockMapInstance = { getZoom: () => 9 };
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    mapEventHandlers.push(handlers);
    return mockMapInstance;
  },
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    fitBounds: () => {},
  }),
}));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div data-testid="map-heat-layer" /> }));

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

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const LOCATIONS = [{
  name: 'Bamburgh', lat: 55.61, lon: -1.71, locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00` },
    sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00` },
  }]]),
}];

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView locations={LOCATIONS} date={TODAY} forecastDates={[TODAY]} autoEventType={null} {...props} />,
    );
  });
  return result;
}

/**
 * Fires Leaflet's own map-background events on every listener registered this render.
 *
 * <p>⚠️ **The `mousedown?.()` line looks dead and is a REVERT CANARY — do not delete it.** Since
 * map-landing L3 the controller registers `click` alone: the branch that needed a trustworthy
 * pre-`document` snapshot of `openMapMenu` went with the popover-closing it guarded. The pre-L3
 * implementation registered both, and it is `handlers.mousedown` being ABSENT that the assertion
 * below turns into a failure if anyone restores it. The optional call keeps this helper usable
 * either way rather than throwing on the shape it is watching for.
 *
 * <p>⚠️ **It dispatches no DOM event**, so no panel's own `document` listener runs. That is the
 * other half of the rule and it needs a real `fireEvent.mouseDown` — see `pressOn` below, and the
 * combined test that fires both in one sequence. A file that only called this helper would report
 * the rule as held while three of its five call sites were untouched, which is the exact shape of
 * test that let the original ordering regression reach the browser.
 */
function clickMapBackground() {
  act(() => {
    for (const handlers of mapEventHandlers) handlers.mousedown?.({});
    for (const handlers of mapEventHandlers) handlers.click?.({});
  });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  mapEventHandlers = [];
});
afterEach(() => { vi.useRealTimers(); });

describe('MapView — a press on the map closes no panel (map-landing-plan.md §3 L3)', () => {
  it('leaves an open filters panel open', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    clickMapBackground();
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();
  });

  it('leaves an open window-control dropdown open', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();

    clickMapBackground();
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
  });

  it('leaves an open Regions jump menu open', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();

    clickMapBackground();
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
  });

  it('is a no-op with nothing open — it must not throw or otherwise disturb the pane', async () => {
    await renderMap();
    expect(() => clickMapBackground()).not.toThrow();
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
  });

  it('registers NO mousedown handler — the snapshot went with the branch that read it', async () => {
    // ⚠️ Pins the removal itself. Without this, restoring the pre-L3 wiring verbatim
    // (`onMouseDown` snapshotting `openMapMenu`, `onBackgroundClick` closing the popover) is
    // invisible to `MapViewSelectionOrdering.test.jsx`, whose mock no longer captures `mousedown`
    // at all — so the ref would stay at its initial `null`, the handler would fall through to
    // deselecting, and that file's assertions would pass against a full revert.
    await renderMap();

    expect(mapEventHandlers.length).toBeGreaterThan(0);
    for (const handlers of mapEventHandlers) {
      expect(handlers.mousedown).toBeUndefined();
    }
  });

  it('never registers on the Plan-tab overlay — nothing there for it to close', async () => {
    await renderMap({ overlayMode: true });
    // Only `ZoomTracker` (and, on the overlay, `BoundsTracker`) register — neither carries `click`.
    expect(mapEventHandlers.length).toBeGreaterThan(0);
    for (const handlers of mapEventHandlers) {
      expect(handlers.click).toBeUndefined();
    }
  });
});

describe('the other half of the rule — the panels\' own document listeners', () => {
  /**
   * A REAL `mousedown` on a real node, which is what the four panels listen for.
   *
   * ⚠️ `clickMapBackground()` above cannot exercise this: it invokes Leaflet's handlers with an
   * empty object, so no `document` event is dispatched and no panel listener runs. A file that only
   * called it would report the rule as held while three of the five call sites were untouched.
   */
  function pressOn(node) {
    act(() => { fireEvent.mouseDown(node); });
  }

  /**
   * The map frame — the PRODUCTION one.
   *
   * ⚠️ This file's `react-leaflet` mock gives its own `MapContainer` stub the same `map-container`
   * test-id the real `MapView` puts on the frame around it, so two nodes match here and exactly one
   * matches in the app. Taking `[0]` on document order is not enough on its own: **delete the
   * production attribute and `[0]` silently becomes the mock's div**, every press still resolves
   * inside "a" frame, and a full L3 revert reads as green. So the count is asserted, and the outer
   * node is proved to contain the inner one — a rename now fails here rather than passing quietly.
   */
  function mapFrame() {
    const frames = screen.getAllByTestId('map-container');
    expect(frames, 'expected the production frame plus this file\'s MapContainer stub').toHaveLength(2);
    expect(frames[0]).toContainElement(frames[1]);
    return frames[0];
  }

  it('a press inside the map frame dismisses nothing, and still SAYS so', async () => {
    // ⚠️ `aria-expanded` as well as presence. A change that left the panel mounted while flipping
    // the chip to `false` would tell a screen-reader user it had closed while it visibly had not —
    // which is precisely the failure a persistence rule invites, and presence alone cannot see it.
    await renderMap();
    const chip = screen.getByRole('button', { name: /filters/i });
    fireEvent.click(chip);
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();
    expect(chip).toHaveAttribute('aria-expanded', 'true');

    pressOn(mapFrame());

    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();
    expect(chip).toHaveAttribute('aria-expanded', 'true');
  });

  it('a press OUTSIDE the map frame still dismisses — leaving the map is not reading the map', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    pressOn(document.body);

    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
  });

  it('holds for the window control', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    pressOn(mapFrame());

    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
  });

  it('holds for the Regions list', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-jump-chip'));

    pressOn(mapFrame());

    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
  });

  // The Legend is the fourth panel and is covered at the component level instead
  // (`MapLegendPanel.test.jsx`) — it mounts here only behind `heatOffered && !isMobile`, and this
  // file's `renderMap` supplies no `heat`, so wiring it up here would test the fixture more than
  // the rule.

  it('holds for a REAL press sequence — mousedown then click, in one gesture', async () => {
    // ⚠️ **The two halves of the rule, fired together.** Everything above exercises one or the
    // other: `clickMapBackground` dispatches no DOM event so no panel listener runs, and `pressOn`
    // never fires the Leaflet `click`. One physical press fires both, in this order, and the panels'
    // `document` listeners commit BEFORE the `click` arrives — which is the timeline that let the
    // original ordering regression reach the browser past a green suite
    // (`MapBackgroundClickController`'s class doc; map-landing-plan.md §3 L3 asks for this pair by
    // name). Both mechanisms must stand down for the panel to survive.
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    pressOn(mapFrame());
    clickMapBackground();

    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();
  });
});

describe('the way out the rule now depends on', () => {
  it('Escape closes an open panel even when focus has moved to the map', async () => {
    // ⚠️ The panels' own `Escape` handlers are scoped to their own subtree, so once a press on the
    // map no longer closes them — and no longer moves focus back — that route is gone. `MapView`'s
    // pane-level handler used to STAND DOWN whenever a panel was open, which would have left the
    // panel closable only by re-finding its chip.
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    act(() => { fireEvent.keyDown(screen.getAllByTestId('map-container')[0], { key: 'Escape' }); });

    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
  });

  it('the chip still toggles its own panel shut', async () => {
    await renderMap();
    const chip = screen.getByTestId('wf-filters-chip');
    fireEvent.click(chip);
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    fireEvent.click(chip);
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
  });
});
