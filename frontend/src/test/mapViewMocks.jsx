/**
 * The shared module mocks for the tests that render the real `MapView` (Leaflet, the layers that need a real Leaflet
 * pane, auth, the viewport hook, and the network modules the tab touches on mount).
 *
 * <p>Import this file FIRST in a test (before `MapView` and anything that imports it): its `vi.mock` calls are
 * registered when it runs, so the later imports see the mocks. The two knobs a test turns are on
 * {@link mapViewState}; reset them in `beforeEach`.
 */
import React from 'react';
import { vi } from 'vitest';

/** The reader's role and the phone flag, read by the mocked `useAuth` and `useIsMobile`. */
const state = vi.hoisted(() => ({ role: 'PRO_USER', isMobile: false }));
export const mapViewState = state;

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

const VIEWPORT = { pad: () => ({ contains: () => true }) };
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  // Any geometry layer drawn through react-leaflet shows up as a marker node, so a test can assert none is.
  Polygon: () => <div data-testid="leaflet-geometry" />,
  GeoJSON: () => <div data-testid="leaflet-geometry" />,
  Rectangle: () => <div data-testid="leaflet-geometry" />,
  Circle: () => <div data-testid="leaflet-geometry" />,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    getBounds: () => VIEWPORT,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => <div /> }));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: state.role }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => state.isMobile }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
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
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
