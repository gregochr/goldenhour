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
 * Leaflet is stubbed exactly as in MapViewStarFilter.test.jsx; the counts footer's N counts the
 * visible locations.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

// ── Leaflet / react-leaflet stubs ────────────────────────────────────────────

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  return { default: { icon, divIcon, point: (x, y) => ({ x, y }) }, icon, divIcon };
});

vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
  }),
}));


// ── App dependencies ─────────────────────────────────────────────────────────

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ role: 'ADMIN' }),
}));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
// `MapLabels` needs a real Leaflet pane to place a chip (its own test's job); the counts footer, which
// reads the same filtered set, is what this file counts. Stubbed so a heat field can be offered.
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => <div data-testid="map-labels-stub" /> }));

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
});

function forecasts(rating = 4) {
  return new Map([
    [TODAY, {
      sunset: { rating, solarEventTime: `${TODAY}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating, solarEventTime: `${TODAY}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
    }],
  ]);
}

const LOCATIONS = [
  { name: 'Fell Top', lat: 55.0, lon: -1.7, forecastsByDate: forecasts(3), locationType: ['LANDSCAPE'] },
  { name: 'Bluebell Wood', lat: 55.1, lon: -1.7, forecastsByDate: forecasts(5), locationType: ['WOODLAND'] },
  { name: 'Wooded Clifftop', lat: 55.2, lon: -1.7, forecastsByDate: forecasts(4), locationType: ['SEASCAPE', 'WOODLAND'] },
];

/** A roster join over the rendered locations, so the heat field is offered and the counts footer mounts. */
function heatFor(locations) {
  const spots = locations.map((l) => ({ id: l.id, name: l.name, lat: l.lat, lng: l.lon, rid: l.regionName ?? 'North East' }));
  return {
    enabled: true,
    hasHome: false,
    spots,
    areaSpots: spots,
    pointsByKey: new Map(),
    windows: [],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
  };
}

/**
 * How many locations the map draws: the N of the counts footer's "N of M shown" (the filtered set).
 */
function visibleCount() {
  const match = /(\d+) of \d+ shown/.exec(screen.getByTestId('wf-map-counts-footer').textContent);
  return match ? Number(match[1]) : NaN;
}

const HEAT = heatFor(LOCATIONS);

function renderMap() {
  return render(<MapView locations={LOCATIONS} date={TODAY} forecastDates={[TODAY]} autoEventType={null} heat={HEAT} />);
}

// map-tab-v2-plan.md §3 P7 moved the Subject chips off the tab's old always-rendered drawer and
// into `FiltersPopover`, whose panel mounts only while open, behind this chip.
function openFilters() {
  fireEvent.click(screen.getByTestId('wf-filters-chip'));
}

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
