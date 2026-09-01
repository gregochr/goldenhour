/**
 * `MapView`'s basemap dress (map-tab-v2-plan.md §3 P3) — the warm CSS filters, the zoom-gated
 * reference (place-name) layer, and fractional zoom on the tab only.
 *
 * <h2>What this file pins</h2>
 *
 * <p>Three claims the plan makes and a screenshot cannot: the base tile always carries
 * `wf-basemap-warm`; the reference tile is UNMOUNTED (not merely hidden) below zoom 11.8 and
 * carries `wf-basemap-ref` + `opacity: 0.6` once it mounts; and `zoomSnap` is `0` on the tab's
 * `MapContainer` and Leaflet's own default (`1`) on the Plan overlay's — the one deliberate
 * overlay diff this phase makes, everything else (the tile classes, the reference gate) reaching
 * both mounts on purpose.
 *
 * <p>What resolves the CLASS NAMES to actual `filter` values is a separate, browser-adjacent
 * claim — see `basemapDressCascade.test.jsx`, which injects a slice of the real `index.css` and
 * asserts `getComputedStyle`. This file only proves the right props reach the right element.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

const mapContainerProps = { last: null };
/** Every `TileLayer` mount this render produced, in render order — cleared each test. */
let tileLayerCalls = [];
/**
 * Every handlers object ANY `useMapEvents` caller registered this render — both `ZoomTracker`
 * (`{zoomend}`, mounted unconditionally) and, on the overlay, `BoundsTracker` (`{moveend,
 * zoomend}`) register through this one hook, and the overlay mounts both. Kept as a list rather
 * than "the last one wins" so `fireZoomend` can drive every registered `zoomend`, not just
 * whichever component happened to call the hook last.
 */
let mapEventHandlers = [];
vi.mock('react-leaflet', () => ({
  MapContainer: (props) => {
    mapContainerProps.last = props;
    return <div data-testid="map-container">{props.children}</div>;
  },
  TileLayer: (props) => {
    tileLayerCalls.push(props);
    return null;
  },
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    mapEventHandlers.push(handlers);
    return null;
  },
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    fitBounds: () => {},
  }),
}));

vi.mock('react-leaflet-cluster', () => ({
  default: ({ children }) => <div>{children}</div>,
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: () => <div data-testid="map-heat-layer" />,
}));

let role = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role }) }));
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

const makeLocations = () => [
  {
    id: 1,
    name: 'Bamburgh',
    lat: 55.61,
    lon: -1.71,
    regionName: 'North East',
    bortleClass: 4,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  },
];

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView locations={makeLocations()} date={TODAY} autoEventType={null} {...props} />,
    );
  });
  return result;
}

/**
 * Fires `zoomend` on every registered `useMapEvents` caller — `ZoomTracker` on both mounts, plus
 * `BoundsTracker` on the overlay. The stub target answers both `getZoom` (what `ZoomTracker`
 * reads) and `getBounds` (what `BoundsTracker` reads), so whichever handler runs first does not
 * throw on the other's missing method.
 */
function fireZoomend(zoom) {
  act(() => {
    const target = {
      getZoom: () => zoom,
      // `BoundsTracker.handleBounds` reads the Leaflet `LatLngBounds` shape, not a plain array.
      getBounds: () => ({
        getSouth: () => 0, getWest: () => 0, getNorth: () => 1, getEast: () => 1,
      }),
    };
    for (const handlers of mapEventHandlers) {
      handlers.zoomend?.({ target });
    }
  });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
  mapContainerProps.last = null;
  tileLayerCalls = [];
  mapEventHandlers = [];
  role = 'PRO_USER';
});
afterEach(() => { vi.useRealTimers(); localStorage.clear(); });

describe('MapView basemap dress — zoomSnap is the one sanctioned overlay diff', () => {
  it('passes zoomSnap 0 on the tab’s MapContainer', async () => {
    await renderMap();
    expect(mapContainerProps.last.zoomSnap).toBe(0);
  });

  it('passes zoomSnap 1 (Leaflet’s own default) on the Plan overlay’s MapContainer, never 0', async () => {
    // Explicit `1`, not an omitted prop: `L.Util.setOptions` copies an own `undefined` key onto the
    // map's options same as any other value, and Leaflet's `_limitZoom` treats `zoomSnap` as a bare
    // truthy check — so `undefined` is exactly as falsy as `0` and would have put the overlay into
    // fractional zoom too. The explicit default is the only value that is provably NOT `0`.
    await renderMap({ overlayMode: true });
    expect(mapContainerProps.last.zoomSnap).toBe(1);
    expect(mapContainerProps.last.zoomSnap).not.toBe(0);
  });
});

describe('MapView basemap dress — the warm filter classes reach both TileLayers, in both mounts', () => {
  it('carries wf-basemap-warm on the base tile on the tab', async () => {
    await renderMap();
    const base = tileLayerCalls.find((p) => p.url.includes('World_Dark_Gray_Base'));
    expect(base.className).toBe('wf-basemap-warm');
  });

  it('carries wf-basemap-warm on the base tile on the Plan overlay too — pure tile dress, not gated on overlayMode', async () => {
    await renderMap({ overlayMode: true });
    const base = tileLayerCalls.find((p) => p.url.includes('World_Dark_Gray_Base'));
    expect(base.className).toBe('wf-basemap-warm');
  });

  it('carries wf-basemap-ref and opacity 0.6 on the reference tile once it mounts, on the tab', async () => {
    await renderMap();
    fireZoomend(12);
    const ref = tileLayerCalls.find((p) => p.url.includes('World_Dark_Gray_Reference'));
    expect(ref.className).toBe('wf-basemap-ref');
    expect(ref.opacity).toBe(0.6);
  });

  it('carries wf-basemap-ref on the reference tile on the overlay too, once it mounts', async () => {
    await renderMap({ overlayMode: true });
    fireZoomend(12);
    const ref = tileLayerCalls.find((p) => p.url.includes('World_Dark_Gray_Reference'));
    expect(ref.className).toBe('wf-basemap-ref');
    expect(ref.opacity).toBe(0.6);
  });

  it('keeps maxZoom at 16 on both tiles (D-6) — the port is dress, not a zoom-depth change', async () => {
    await renderMap();
    fireZoomend(12);
    for (const props of tileLayerCalls) {
      expect(props.maxZoom).toBe(16);
    }
  });
});

describe('MapView basemap dress — the reference layer is UNMOUNTED below zoom 11.8, not merely hidden', () => {
  it('is absent at the tab’s initial zoom (9), which is the whole point of the glance view', async () => {
    await renderMap();
    expect(mapContainerProps.last).toBeTruthy();
    expect(tileLayerCalls.some((p) => p.url.includes('World_Dark_Gray_Reference'))).toBe(false);
  });

  it('stays absent one tick below the threshold (11.7)', async () => {
    await renderMap();
    fireZoomend(11.7);
    expect(tileLayerCalls.some((p) => p.url.includes('World_Dark_Gray_Reference'))).toBe(false);
  });

  it('mounts exactly at the threshold (11.8)', async () => {
    await renderMap();
    fireZoomend(11.8);
    expect(tileLayerCalls.some((p) => p.url.includes('World_Dark_Gray_Reference'))).toBe(true);
  });

  it('mounts above the threshold (12) and unmounts again on zooming back out — a live mid-session crossing, not a one-way latch', async () => {
    await renderMap();
    fireZoomend(12);
    expect(tileLayerCalls.some((p) => p.url.includes('World_Dark_Gray_Reference'))).toBe(true);

    tileLayerCalls = [];
    fireZoomend(9);
    expect(tileLayerCalls.some((p) => p.url.includes('World_Dark_Gray_Reference'))).toBe(false);
  });
});
