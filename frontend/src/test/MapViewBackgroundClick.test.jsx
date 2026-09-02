/**
 * The Map tab's "click empty map closes whatever popover is open" rule (map-tab-v2-plan.md §3 P7,
 * README "Interactions & behaviour": "Click map background → Close menus"), implemented by
 * `MapBackgroundClickController` — a `useMapEvents({ click })` listener mounted only on the tab.
 *
 * Leaflet's own marker click handlers stop propagation before a marker tap ever reaches the map's
 * `click` event, so this listener only ever fires for a genuine empty-map click; that guarantee is
 * Leaflet's, not this file's to re-prove; what this file pins is that when the event DOES fire, it
 * reaches whichever of the window control or the filters popover is open, and that it never fires
 * on the Plan-tab overlay, which has no popover of its own for it to close.
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
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

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
vi.mock('react-leaflet-cluster', () => ({ default: ({ children }) => <div>{children}</div> }));
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
 * Fires a synthetic map background click on every listener registered this render — `mousedown`
 * THEN `click`, in that order, mirroring the two events one physical click actually fires
 * (`MapBackgroundClickController`'s own class doc, map-tab-v2-plan.md §3 P9: the close-ordering fix
 * snapshots `openMapMenu` on `mousedown`, before `WindowControl`/`FiltersPopover`'s own
 * `document`-level `mousedown` listener can close the menu the `click` handler still needs to see
 * as "was open"). Firing `click` alone — this file's own shape before P9 — skipped the snapshot
 * entirely and read the ref's initial `null`, which happened to still close a popover today only
 * because `openMapMenuAtMouseDownRef.current == null` takes the SAME "close it" branch a popover
 * being open is meant to; `MapViewSelectionOrdering.test.jsx` is where that ordering itself (versus
 * the callout) is actually pinned.
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

describe('MapView — clicking the map background closes an open popover (tab only)', () => {
  it('closes an open filters panel', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    clickMapBackground();
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
  });

  it('closes an open window-control dropdown', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();

    clickMapBackground();
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
  });

  it('is a no-op with nothing open — it must not throw or otherwise disturb the pane', async () => {
    await renderMap();
    expect(() => clickMapBackground()).not.toThrow();
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
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
