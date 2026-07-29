/**
 * Tests for MapView's Subject (location type) filter, and specifically that WOODLAND
 * is one of the subjects it knows about.
 *
 * The regression this pins: LOCATION_TYPE_LABELS drives BOTH the chip row and, through
 * activeTypeFilters, the filter predicate. A type missing from that map therefore cannot
 * be filtered FOR and can never be a member of activeTypeFilters — so a location carrying
 * only that type matched nothing and vanished the moment any Subject chip was selected.
 * It read as "that location is gone", not "that location is filtered out".
 *
 * Leaflet is stubbed exactly as in MapViewStarFilter.test.jsx; one popup renders per
 * Marker, so counting popups counts visible locations.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

// ── Leaflet / react-leaflet stubs ────────────────────────────────────────────

// Capture the options of every DivIcon MapView builds — excludeFromCluster is only observable
// there, and it is what markerUtils.createClusterIcon filters on.
const divIconCalls = [];
vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => {
    divIconCalls.push(options);
    return { options };
  };
  return { default: { icon, divIcon, point: (x, y) => ({ x, y }) }, icon, divIcon };
});

vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
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

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ role: 'ADMIN' }),
}));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({
  default: () => <div data-testid="popup-content" />,
}));
vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: ({ onChange }) => (
    <button data-testid="type-sunrise" onClick={() => onChange('SUNRISE')}>Sunrise</button>
  ),
}));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
  RATING_COLOURS: { 1: '#A32D2D', 2: '#D85A30', 3: '#FAC775', 4: '#97C459', 5: '#3B6D11' },
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
  // Deliberately NOT cleared: makeMarkerIcon memoises in a module-level markerIconCache, so a
  // location's DivIcon is built once for the whole file. Resetting here would empty the capture
  // and the assertions below would silently find nothing.
});

function forecasts(rating = 4) {
  return new Map([
    [TODAY, {
      sunset: { rating, solarEventTime: `${TODAY}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating, solarEventTime: `${TODAY}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
    }],
  ]);
}

// Distinct ratings so each marker's DivIcon options are identifiable — the options carry the
// rating but not the location name, and adding a name purely for tests would be production code
// bent to the test's shape.
const LOCATIONS = [
  { name: 'Fell Top', lat: 55.0, lon: -1.7, forecastsByDate: forecasts(3), locationType: ['LANDSCAPE'] },
  { name: 'Bluebell Wood', lat: 55.1, lon: -1.7, forecastsByDate: forecasts(5), locationType: ['WOODLAND'] },
  { name: 'Wooded Clifftop', lat: 55.2, lon: -1.7, forecastsByDate: forecasts(4), locationType: ['SEASCAPE', 'WOODLAND'] },
];

function renderMap() {
  return render(<MapView locations={LOCATIONS} date={TODAY} autoEventType={null} />);
}

function openFilters() {
  fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
}

const visibleCount = () => screen.queryAllByTestId('popup-content').length;

describe('MapView Subject filter — WOODLAND', () => {
  it('offers Woodland as a Subject chip', () => {
    renderMap();
    openFilters();
    expect(screen.getByRole('button', { name: /Woodland/ })).toBeInTheDocument();
  });

  it('selecting Woodland shows woodland sites and hides the rest', () => {
    renderMap();
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: /Woodland/ }));

    // Bluebell Wood + Wooded Clifftop; Fell Top is landscape-only.
    expect(visibleCount()).toBe(2);
  });

  it('a woodland-only site is not silently dropped when another Subject is selected', () => {
    // Before WOODLAND existed in LOCATION_TYPE_LABELS this was unreachable: with Landscape
    // selected the wood disappeared, and there was no chip that could bring it back.
    renderMap();
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: /Landscape/ }));
    expect(visibleCount()).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: /Woodland/ }));
    expect(visibleCount()).toBe(3);
  });

  it('a site typed both SEASCAPE and WOODLAND matches either filter', () => {
    renderMap();
    openFilters();

    fireEvent.click(screen.getByRole('button', { name: /Seascape/ }));
    expect(visibleCount()).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: /Seascape/ }));
    fireEvent.click(screen.getByRole('button', { name: /Woodland/ }));
    expect(visibleCount()).toBe(2);
  });
});

describe('MapView cluster averages — canopy sites are excluded', () => {
  it('a woodland-only site is excluded from the sky cluster average', () => {
    // Same rule WATERFALL already has. A wood rated 5 on a flat overcast misty evening would drag
    // its cluster's grey->gold ramp toward gold on exactly the nights the sky is at its worst.
    renderMap();
    const wood = divIconCalls.find((o) => o.rating === 5);   // Bluebell Wood, WOODLAND only
    expect(wood).toBeDefined();
    expect(wood.excludeFromCluster).toBe(true);
  });

  it('a sky-typed site is still included', () => {
    renderMap();
    const fell = divIconCalls.find((o) => o.rating === 3);   // Fell Top, LANDSCAPE
    expect(fell).toBeDefined();
    expect(fell.excludeFromCluster).toBe(false);
  });
});
