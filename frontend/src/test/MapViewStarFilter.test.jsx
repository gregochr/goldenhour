/**
 * Tests for MapView's min-stars filter and localStorage persistence.
 *
 * MapView mounts Leaflet, which needs heavy mocking in JSDOM. Everything map-related
 * is stubbed here; tests focus solely on the star filter UI behaviour.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

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

vi.mock('../hooks/useIsMobile.js', () => ({
  useIsMobile: () => false,
}));

vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: null }),
}));

vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));

// Stub heavy child components to keep render fast
vi.mock('../components/BottomSheet.jsx', () => ({
  default: ({ children }) => <div>{children}</div>,
}));
vi.mock('../components/MarkerPopupContent.jsx', () => ({
  default: () => <div data-testid="popup-content" />,
}));
vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: ({ onChange }) => (
    <button data-testid="type-sunrise" onClick={() => onChange('SUNRISE')}>Sunrise</button>
  ),
}));
vi.mock('../components/InfoTip.jsx', () => ({
  default: () => null,
}));
vi.mock('./markerUtils.js', () => ({}), { spy: false });
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
}));

// ── Test helpers ─────────────────────────────────────────────────────────────

import MapView from '../components/MapView.jsx';

const TODAY = new Date().toLocaleDateString('en-CA');

function makeForecastsByDate(rating = 4) {
  return new Map([
    [TODAY, {
      sunset: { rating, solarEventTime: `${TODAY}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating, solarEventTime: `${TODAY}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
    }],
  ]);
}

function makeLocations(ratings = [4, 3, 2]) {
  return ratings.map((r, i) => ({
    name: `Loc${i}`,
    lat: 55 + i * 0.1,
    lon: -1.7,
    forecastsByDate: makeForecastsByDate(r),
    locationType: ['LANDSCAPE'],
  }));
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

describe('MapView advanced filters toggle', () => {
  beforeEach(() => { localStorage.clear(); });
  afterEach(() => { localStorage.clear(); });

  it('advanced filters panel is collapsed on fresh load', () => {
    renderMap();
    expect(screen.getByTestId('advanced-filters-panel').className).toMatch(/max-h-0/);
    expect(screen.getByTestId('advanced-filters-panel').className).not.toMatch(/max-h-96/);
  });

  it('Filters toggle button is present', () => {
    renderMap();
    expect(screen.getByTestId('advanced-filters-toggle')).toBeInTheDocument();
  });

  it('Filters button shows no badge when no advanced filters are active', () => {
    renderMap();
    const btn = screen.getByTestId('advanced-filters-toggle');
    expect(btn.textContent).not.toMatch(/\(/);
  });

  it('Filters button shows badge count when a star filter is active', () => {
    localStorage.setItem('mapFilterMinStars', '3');
    renderMap();
    expect(screen.getByTestId('advanced-filters-toggle').textContent).toMatch(/\(1\)/);
  });

  it('clicking the toggle opens the advanced panel', () => {
    renderMap();
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    expect(screen.getByTestId('advanced-filters-panel').className).toMatch(/max-h-96/);
  });

  it('clicking the toggle twice collapses the panel again', () => {
    renderMap();
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    expect(screen.getByTestId('advanced-filters-panel').className).toMatch(/max-h-0/);
  });
});

describe('MapView star filter — localStorage persistence', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  describe('initialisation', () => {
    it('renders all five star buttons', () => {
      renderMap();
      for (let s = 1; s <= 5; s++) {
        expect(screen.getByTestId(`star-filter-${s}`)).toBeInTheDocument();
      }
    });

    it('no star button is active when localStorage is empty', () => {
      renderMap();
      for (let s = 1; s <= 5; s++) {
        expect(screen.getByTestId(`star-filter-${s}`).className).not.toMatch(/plex-gold/);
      }
    });

    it('pre-selects the saved minimum from localStorage on mount', () => {
      localStorage.setItem('mapFilterMinStars', '3');
      renderMap();
      expect(screen.getByTestId('star-filter-3').className).toMatch(/plex-gold/);
    });

    it('ignores invalid localStorage values', () => {
      localStorage.setItem('mapFilterMinStars', 'banana');
      renderMap();
      for (let s = 1; s <= 5; s++) {
        expect(screen.getByTestId(`star-filter-${s}`).className).not.toMatch(/plex-gold/);
      }
    });

    it('ignores out-of-range localStorage values', () => {
      localStorage.setItem('mapFilterMinStars', '9');
      renderMap();
      for (let s = 1; s <= 5; s++) {
        expect(screen.getByTestId(`star-filter-${s}`).className).not.toMatch(/plex-gold/);
      }
    });
  });

  describe('clicking star buttons', () => {
    it('marks the clicked star as active', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-3'));
      expect(screen.getByTestId('star-filter-3').className).toMatch(/plex-gold/);
    });

    it('saves the clicked star to localStorage', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-4'));
      expect(localStorage.getItem('mapFilterMinStars')).toBe('4');
    });

    it('replaces an existing minimum when a different star is clicked', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-2'));
      fireEvent.click(screen.getByTestId('star-filter-5'));
      expect(localStorage.getItem('mapFilterMinStars')).toBe('5');
      expect(screen.getByTestId('star-filter-5').className).toMatch(/plex-gold/);
      expect(screen.getByTestId('star-filter-2').className).not.toMatch(/plex-gold/);
    });

    it('clears the active minimum when the same star is clicked again (toggle off)', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-3'));
      fireEvent.click(screen.getByTestId('star-filter-3'));
      expect(localStorage.getItem('mapFilterMinStars')).toBeNull();
      expect(screen.getByTestId('star-filter-3').className).not.toMatch(/plex-gold/);
    });
  });

  describe('clear all filters button', () => {
    it('is not shown when no filters are active', () => {
      renderMap();
      expect(screen.queryByTestId('clear-all-filters')).not.toBeInTheDocument();
    });

    it('appears when a star filter is active', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-3'));
      expect(screen.getByTestId('clear-all-filters')).toBeInTheDocument();
    });

    it('removes the active star filter on click', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-4'));
      fireEvent.click(screen.getByTestId('clear-all-filters'));
      expect(screen.getByTestId('star-filter-4').className).not.toMatch(/plex-gold/);
    });

    it('removes mapFilterMinStars from localStorage on click', () => {
      localStorage.setItem('mapFilterMinStars', '3');
      renderMap();
      // Clear button should appear because minStars=3 is loaded from localStorage
      fireEvent.click(screen.getByTestId('clear-all-filters'));
      expect(localStorage.getItem('mapFilterMinStars')).toBeNull();
    });
  });

  describe('event type change clears star filter', () => {
    it('clears localStorage when ForecastTypeSelector changes event type', () => {
      localStorage.setItem('mapFilterMinStars', '4');
      renderMap();
      // ForecastTypeSelector stub calls onChange('SUNRISE') on click
      fireEvent.click(screen.getByTestId('type-sunrise'));
      expect(localStorage.getItem('mapFilterMinStars')).toBeNull();
    });
  });
});
