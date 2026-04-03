/**
 * Tests for MapView's aurora viewline event-type gating.
 *
 * Covers:
 * - Viewline hidden when Sunrise / Sunset / Astro selected
 * - Viewline visible when Aurora selected + aurora active
 * - Viewline disappears and reappears when toggling away and back to Aurora
 * - Upsell chip also gated to Aurora event type
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';

// ── Leaflet / react-leaflet stubs ────────────────────────────────────────────

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = () => ({});
  return { default: { icon, divIcon }, icon, divIcon };
});

vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
  }),
}));

vi.mock('react-leaflet-cluster', () => ({
  default: ({ children }) => <div>{children}</div>,
}));

// ── App dependencies ─────────────────────────────────────────────────────────

const mockUseAuth = vi.fn().mockReturnValue({ role: 'ADMIN' });
vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: (...args) => mockUseAuth(...args),
}));

vi.mock('../hooks/useIsMobile.js', () => ({
  useIsMobile: () => false,
}));

// Stable references — avoids infinite re-render from useEffect dependency checks.
const { stableAuroraStatus, stableViewline } = vi.hoisted(() => ({
  stableAuroraStatus: { level: 'MODERATE', kpIndex: 5.0 },
  stableViewline: {
    points: [
      { longitude: -5, latitude: 54 },
      { longitude: 0, latitude: 55 },
    ],
    summary: 'Visible as far south as northern England',
    southernmostLatitude: 54,
    forecastTime: '2026-04-01T22:00:00Z',
    active: true,
  },
}));

vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: stableAuroraStatus }),
}));

vi.mock('../hooks/useAuroraViewline.js', () => ({
  useAuroraViewline: () => ({ viewline: stableViewline }),
}));

vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue(['2026-04-01']),
}));

vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));

vi.mock('../api/settingsApi.js', () => ({
  getDriveTimes: vi.fn().mockResolvedValue({}),
}));

// Stub heavy child components
vi.mock('../components/BottomSheet.jsx', () => ({
  default: ({ children }) => <div>{children}</div>,
}));
vi.mock('../components/MarkerPopupContent.jsx', () => ({
  default: () => <div data-testid="popup-content" />,
}));
vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: ({ eventType, onChange }) => (
    <div>
      <button data-testid="type-sunrise" onClick={() => onChange('SUNRISE')}>Sunrise</button>
      <button data-testid="type-sunset" onClick={() => onChange('SUNSET')}>Sunset</button>
      <button data-testid="type-astro" onClick={() => onChange('ASTRO')}>Astro</button>
      <button data-testid="type-aurora" onClick={() => onChange('AURORA')}>Aurora</button>
      <span data-testid="current-event-type">{eventType}</span>
    </div>
  ),
}));
vi.mock('../components/InfoTip.jsx', () => ({
  default: ({ text }) => <span data-testid="infotip-text">{text}</span>,
}));

// Render-visible mock so we can assert presence/absence
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({
  default: ({ viewline }) =>
    viewline ? <div data-testid="aurora-viewline-overlay" /> : null,
}));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4\u2605', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
}));

// ── Import under test ────────────────────────────────────────────────────────

import MapView from '../components/MapView.jsx';

// ── Helpers ──────────────────────────────────────────────────────────────────

const TODAY = new Date().toLocaleDateString('en-CA');

function makeLocations() {
  const forecasts = new Map([
    [TODAY, {
      sunset: { rating: 4, solarEventTime: `${TODAY}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating: 3, solarEventTime: `${TODAY}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
    }],
  ]);
  return [
    { name: 'TestLoc', lat: 55.0, lon: -1.7, forecastsByDate: forecasts, locationType: ['LANDSCAPE'] },
  ];
}

async function renderMap(overrides = {}) {
  const props = { locations: makeLocations(), date: TODAY, autoEventType: null, ...overrides };
  let result;
  await act(async () => { result = render(<MapView {...props} />); });
  return result;
}

// ── Tests ────────────────────────────────────────────────────────────────────

describe('MapView aurora viewline event-type gating', () => {
  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
  });
  afterEach(() => { localStorage.clear(); });

  it('viewline hidden when Sunset selected', async () => {
    await renderMap();
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });

  it('viewline hidden when Sunrise selected', async () => {
    await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('type-sunrise')); });
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });

  it('viewline hidden when Astro selected', async () => {
    await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('type-astro')); });
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });

  it('viewline visible when Aurora selected', async () => {
    await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('type-aurora')); });
    expect(screen.getByTestId('aurora-viewline-overlay')).toBeInTheDocument();
  });

  it('viewline hidden on a future date even when Aurora selected', async () => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const tomorrowStr = tomorrow.toLocaleDateString('en-CA');
    const forecasts = new Map([
      [tomorrowStr, {
        sunset: { rating: 4, solarEventTime: `${tomorrowStr}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
        sunrise: { rating: 3, solarEventTime: `${tomorrowStr}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
      }],
    ]);
    const locs = [{ name: 'TestLoc', lat: 55.0, lon: -1.7, forecastsByDate: forecasts, locationType: ['LANDSCAPE'] }];
    await renderMap({ locations: locs, date: tomorrowStr });
    await act(async () => { fireEvent.click(screen.getByTestId('type-aurora')); });
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });

  it('viewline disappears and reappears when toggling away and back', async () => {
    await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('type-aurora')); });
    expect(screen.getByTestId('aurora-viewline-overlay')).toBeInTheDocument();

    await act(async () => { fireEvent.click(screen.getByTestId('type-sunset')); });
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();

    await act(async () => { fireEvent.click(screen.getByTestId('type-aurora')); });
    expect(screen.getByTestId('aurora-viewline-overlay')).toBeInTheDocument();
  });
});

describe('MapView viewline upsell chip', () => {
  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'LITE_USER' });
  });
  afterEach(() => { localStorage.clear(); });

  it('upsell chip visible for LITE user when aurora active regardless of event type', async () => {
    await renderMap();
    // LITE users cannot enter Aurora mode, but the upsell chip shows on any event type
    expect(screen.getByTestId('viewline-upsell-chip')).toBeInTheDocument();
  });
});
