/**
 * The shared module mocks for the tests that render the real `MapView` (Leaflet, the layers that need a real Leaflet
 * pane, auth, the viewport hook, and the network modules the tab touches on mount).
 *
 * <p>Import this file FIRST in a test (before `MapView` and anything that imports it): its `vi.mock` calls are
 * registered when it runs, so the later imports see the mocks. The two knobs a test turns are on
 * {@link mapViewState}; reset them in `beforeEach`.
 *
 * <p>The factories are built once inside `vi.hoisted` and handed to `vi.mock` by name, so each mock is defined in one
 * place and this file stays a short list of registrations rather than a copy of the inline mock blocks older map tests
 * carry.
 */
import { vi } from 'vitest';

const kit = vi.hoisted(() => {
  const state = { role: 'PRO_USER', isMobile: false, realSheets: false };
  const settled = (value) => vi.fn().mockResolvedValue(value);
  const nothing = () => ({ default: () => null });
  const viewport = { pad: () => ({ contains: () => true }) };
  const noop = () => {};
  const geometryNode = (h) => () => h('div', { 'data-testid': 'leaflet-geometry' });
  return {
    state,
    leaflet: () => {
      const stubs = {
        icon: () => ({}),
        divIcon: (options) => ({ options }),
        point: (x, y) => ({ x, y }),
      };
      return { default: stubs, ...stubs };
    },
    reactLeaflet: async () => {
      const { createElement: h } = await import('react');
      const geometry = geometryNode(h);
      return {
        MapContainer: ({ children }) => h('div', null, children),
        TileLayer: () => null,
        Popup: ({ children }) => h('div', null, children),
        Polyline: () => null,
        // Any geometry layer drawn through react-leaflet shows up as a node, so a test can assert none is.
        Polygon: geometry,
        GeoJSON: geometry,
        Rectangle: geometry,
        Circle: geometry,
        useMapEvents: () => null,
        useMap: () => ({
          eachLayer: noop,
          getContainer: () => ({ clientHeight: 500 }),
          getZoom: () => 9,
          getBounds: () => viewport,
          once: noop,
          off: noop,
          flyTo: noop,
          fitBounds: noop,
        }),
      };
    },
    blankComponent: async () => {
      const { createElement: h } = await import('react');
      return { default: () => h('div') };
    },
    passThrough: async () => {
      const { createElement: h } = await import('react');
      return { default: ({ children }) => h('div', null, children) };
    },
    nothing,
    auth: () => ({ useAuth: () => ({ role: state.role }) }),
    isMobile: () => ({ useIsMobile: () => state.isMobile }),
    auroraStatus: () => ({ useAuroraStatus: () => ({ status: null }) }),
    auroraViewline: () => ({ useAuroraViewline: () => ({ viewline: null }) }),
    auroraApi: () => ({
      getAuroraLocations: settled([]),
      getAuroraNights: settled({ nights: [], currentNightEndsAt: null }),
      getAuroraForecastAvailableDates: settled([]),
      getAuroraForecastResults: settled([]),
    }),
    astroApi: () => ({ getAstroAvailableDates: settled([]), getAstroConditions: settled([]) }),
    drivesApi: () => ({ getDriveTimes: settled({}) }),
    travelApi: () => ({ fetchTravelDayRanges: settled([]) }),
  };
});

/** The reader's role and the phone flag, read by the mocked `useAuth` and `useIsMobile`. */
export const mapViewState = kit.state;

vi.mock('leaflet', kit.leaflet);
vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('react-leaflet', kit.reactLeaflet);
vi.mock('../components/MapHeatLayer.jsx', kit.blankComponent);
vi.mock('../components/map/MapLabels.jsx', kit.blankComponent);
// `realSheets` is read when `BottomSheet` is first imported: a test that wants the real sheet sets it before it
// dynamically imports `MapView` (see `MapViewRealSheets.test.jsx`). Every other test gets the passthrough.
vi.mock('../components/BottomSheet.jsx', async (importOriginal) => (kit.state.realSheets ? importOriginal() : kit.passThrough()));
vi.mock('../components/InfoTip.jsx', kit.nothing);
vi.mock('../components/AuroraViewlineOverlay.jsx', kit.nothing);
vi.mock('../context/AuthContext.jsx', kit.auth);
vi.mock('../hooks/useIsMobile.js', kit.isMobile);
vi.mock('../hooks/useAuroraStatus.js', kit.auroraStatus);
vi.mock('../hooks/useAuroraViewline.js', kit.auroraViewline);
vi.mock('../api/auroraApi.js', kit.auroraApi);
vi.mock('../api/astroApi.js', kit.astroApi);
vi.mock('../api/settingsApi.js', kit.drivesApi);
vi.mock('../api/travelDayApi.js', kit.travelApi);
