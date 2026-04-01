/**
 * Tests for MapView's dark sky chip and astro mode filtering.
 *
 * Covers:
 * - Dark sky chip label text
 * - Dark sky chip visibility for LITE_USER
 * - Astro mode filters to Bortle-class locations only
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

vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: null }),
}));

vi.mock('../hooks/useAuroraViewline.js', () => ({
  useAuroraViewline: () => ({ viewline: null }),
}));

vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  // Return a date so auroraAvailable=true — prevents auto-reset from AURORA to SUNSET.
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue(['2026-04-01']),
}));

vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
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
      <button data-testid="type-astro" onClick={() => onChange('ASTRO')}>Astro</button>
      <button data-testid="type-aurora" onClick={() => onChange('AURORA')}>Aurora</button>
      <button data-testid="type-sunset" onClick={() => onChange('SUNSET')}>Sunset</button>
      <span data-testid="current-event-type">{eventType}</span>
    </div>
  ),
}));
vi.mock('../components/InfoTip.jsx', () => ({
  default: () => null,
}));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({
  default: () => null,
}));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4\u2605', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
}));

// ── Import under test ────────────────────────────────────────────────────────

import MapView from '../components/MapView.jsx';

// ── Test helpers ─────────────────────────────────────────────────────────────

const TODAY = new Date().toLocaleDateString('en-CA');

function makeForecastsByDate(rating = 4) {
  return new Map([
    [TODAY, {
      sunset: { rating, solarEventTime: `${TODAY}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating, solarEventTime: `${TODAY}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
    }],
  ]);
}

function makeLocations() {
  return [
    { name: 'DarkSite', lat: 55.0, lon: -1.7, forecastsByDate: makeForecastsByDate(4), locationType: ['LANDSCAPE'], bortleClass: 3 },
    { name: 'LightPolluted', lat: 55.1, lon: -1.6, forecastsByDate: makeForecastsByDate(3), locationType: ['LANDSCAPE'], bortleClass: null },
    { name: 'ModerateSky', lat: 55.2, lon: -1.5, forecastsByDate: makeForecastsByDate(5), locationType: ['LANDSCAPE'], bortleClass: 5 },
  ];
}

function renderMap(overrides = {}) {
  const props = {
    locations: makeLocations(),
    date: TODAY,
    autoEventType: null,
    ...overrides,
  };
  return render(<MapView {...props} />);
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('MapView dark sky chip', () => {
  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
  });
  afterEach(() => { localStorage.clear(); });

  it('darkSky_chip_label_is_Dark_sky', () => {
    renderMap();
    const chip = screen.getByTestId('dark-sky-filter-toggle');
    expect(chip.textContent.trim()).toContain('Dark sky');
  });

  it('darkSky_chip_visible_for_lite_user', () => {
    mockUseAuth.mockReturnValue({ role: 'LITE_USER' });
    renderMap();
    expect(screen.getByTestId('dark-sky-filter-toggle')).toBeInTheDocument();
  });

  it('dark sky chip is hidden in ASTRO mode', async () => {
    renderMap();
    // Initially in SUNSET mode, chip should be visible
    expect(screen.getByTestId('dark-sky-filter-toggle')).toBeInTheDocument();
    // Switch to ASTRO mode via the ForecastTypeSelector mock
    await act(async () => {
      fireEvent.click(screen.getByTestId('type-astro'));
    });
    expect(screen.queryByTestId('dark-sky-filter-toggle')).not.toBeInTheDocument();
  });

  it('dark sky chip is hidden in AURORA mode', async () => {
    // Wrap render in act so async useEffect (aurora dates fetch) settles before assertions.
    await act(async () => {
      renderMap();
    });
    expect(screen.getByTestId('dark-sky-filter-toggle')).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(screen.getByTestId('type-aurora'));
    });
    expect(screen.queryByTestId('dark-sky-filter-toggle')).not.toBeInTheDocument();
  });
});

describe('MapView astro mode filtering', () => {
  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
  });
  afterEach(() => { localStorage.clear(); });

  it('astro_mode_filters_to_bortle_locations_only', async () => {
    renderMap();
    // All 3 locations visible in default SUNSET mode
    expect(screen.getAllByTestId('marker')).toHaveLength(3);
    // Switch to ASTRO mode
    await act(async () => {
      fireEvent.click(screen.getByTestId('type-astro'));
    });
    // In ASTRO mode, only locations with bortleClass != null are rendered.
    // DarkSite (bortle 3) and ModerateSky (bortle 5) should be visible, LightPolluted (null) should not.
    const markers = screen.getAllByTestId('marker');
    expect(markers).toHaveLength(2);
  });

  it('non-astro mode shows all locations including those without bortleClass', () => {
    renderMap();
    const markers = screen.getAllByTestId('marker');
    expect(markers).toHaveLength(3);
  });
});
