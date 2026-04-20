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

let mockRole = 'ADMIN';
vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ role: mockRole }),
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
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
  RATING_COLOURS: { 1: '#A32D2D', 2: '#D85A30', 3: '#FAC775', 4: '#97C459', 5: '#3B6D11' },
  STAND_DOWN_COLOUR: '#501313',
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

    it('pre-selects the saved minimum from localStorage on mount — highlights that star and all above', () => {
      localStorage.setItem('mapFilterMinStars', '3');
      renderMap();
      expect(screen.getByTestId('star-filter-1').className).not.toMatch(/plex-gold/);
      expect(screen.getByTestId('star-filter-2').className).not.toMatch(/plex-gold/);
      expect(screen.getByTestId('star-filter-3').className).toMatch(/plex-gold/);
      expect(screen.getByTestId('star-filter-4').className).toMatch(/plex-gold/);
      expect(screen.getByTestId('star-filter-5').className).toMatch(/plex-gold/);
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
    it('marks the clicked star and all stars above it as active', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-3'));
      expect(screen.getByTestId('star-filter-1').className).not.toMatch(/plex-gold/);
      expect(screen.getByTestId('star-filter-2').className).not.toMatch(/plex-gold/);
      expect(screen.getByTestId('star-filter-3').className).toMatch(/plex-gold/);
      expect(screen.getByTestId('star-filter-4').className).toMatch(/plex-gold/);
      expect(screen.getByTestId('star-filter-5').className).toMatch(/plex-gold/);
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
      // 1–4 should no longer be highlighted when minimum is 5
      for (let s = 1; s <= 4; s++) {
        expect(screen.getByTestId(`star-filter-${s}`).className).not.toMatch(/plex-gold/);
      }
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

// ── Stand-down pill + coloured dots + admin ? pill ──────────────────────────

function makeStandDownLocation(triageReason = 'HEAVY_CLOUD') {
  return {
    name: 'StandDownLoc',
    lat: 55.5,
    lon: -1.7,
    forecastsByDate: new Map([
      [TODAY, {
        sunset: { rating: null, triageReason, solarEventTime: `${TODAY}T18:00:00` },
        sunrise: { rating: null, triageReason, solarEventTime: `${TODAY}T06:00:00` },
      }],
    ]),
    locationType: ['LANDSCAPE'],
  };
}

describe('MapView stand-down filter pill', () => {
  beforeEach(() => {
    mockRole = 'ADMIN';
    localStorage.clear();
  });
  afterEach(() => {
    localStorage.clear();
  });

  it('renders the stand-down pill', () => {
    renderMap();
    expect(screen.getByTestId('star-filter-standdown')).toBeInTheDocument();
  });

  it('stand-down pill is disabled when no stand-down locations exist', () => {
    renderMap();
    expect(screen.getByTestId('star-filter-standdown')).toBeDisabled();
  });

  it('stand-down pill is enabled when at least one stand-down location exists', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    expect(screen.getByTestId('star-filter-standdown')).not.toBeDisabled();
  });

  it('clicking the stand-down pill persists to localStorage', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    expect(localStorage.getItem('mapFilterShowStandDown')).toBe('1');
  });

  it('clicking again clears the localStorage flag', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    const pill = screen.getByTestId('star-filter-standdown');
    fireEvent.click(pill);
    fireEvent.click(pill);
    expect(localStorage.getItem('mapFilterShowStandDown')).toBeNull();
  });

  it('stand-down pill reads initial state from localStorage', () => {
    localStorage.setItem('mapFilterShowStandDown', '1');
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    expect(screen.getByTestId('star-filter-standdown').className).toMatch(/plex-gold/);
  });

  it('event type change clears the stand-down flag', () => {
    localStorage.setItem('mapFilterShowStandDown', '1');
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    fireEvent.click(screen.getByTestId('type-sunrise'));
    expect(localStorage.getItem('mapFilterShowStandDown')).toBeNull();
  });

  it('Clear button resets the stand-down flag', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    fireEvent.click(screen.getByTestId('clear-all-filters'));
    expect(localStorage.getItem('mapFilterShowStandDown')).toBeNull();
  });
});

// Exact rgb each pill must render. JSDOM normalises hex to "rgb(r, g, b)"
// in element.style.backgroundColor, so these assertions catch colour swaps.
const EXPECTED_DOT_RGB = {
  1: 'rgb(163, 45, 45)',   // #A32D2D
  2: 'rgb(216, 90, 48)',   // #D85A30
  3: 'rgb(250, 199, 117)', // #FAC775
  4: 'rgb(151, 196, 89)',  // #97C459
  5: 'rgb(59, 109, 17)',   // #3B6D11
};
const STAND_DOWN_RGB = 'rgb(80, 19, 19)';  // #501313

function getDot(pill) {
  return pill.querySelector('span[aria-hidden="true"]');
}

describe('MapView rating pill coloured dots — exact medallion colours', () => {
  beforeEach(() => {
    mockRole = 'ADMIN';
    localStorage.clear();
  });

  it.each([1, 2, 3, 4, 5])('star pill %s has exact medallion dot colour', (star) => {
    renderMap();
    const dot = getDot(screen.getByTestId(`star-filter-${star}`));
    expect(dot).not.toBeNull();
    expect(dot.style.backgroundColor).toBe(EXPECTED_DOT_RGB[star]);
    expect(dot.style.width).toBe('8px');
    expect(dot.style.height).toBe('8px');
  });

  it('stand-down pill dot is the exact stand-down colour (#501313)', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    const dot = getDot(screen.getByTestId('star-filter-standdown'));
    expect(dot.style.backgroundColor).toBe(STAND_DOWN_RGB);
    expect(dot.style.width).toBe('8px');
    expect(dot.style.height).toBe('8px');
  });

  it('admin ? unknown pill dot is transparent with dashed outline', () => {
    renderMap({
      locations: [
        ...makeLocations([4]),
        { name: 'Unknown', lat: 55.9, lon: -1.7,
          forecastsByDate: new Map([[TODAY, { sunset: { rating: null }, sunrise: { rating: null } }]]),
          locationType: ['LANDSCAPE'] },
      ],
    });
    const dot = getDot(screen.getByTestId('star-filter-unrated'));
    expect(dot.style.backgroundColor).toBe('transparent');
    // React serialises border shorthand into one inline style; assert it contains dashed
    const style = dot.getAttribute('style') ?? '';
    expect(style).toMatch(/1px dashed/);
  });

  it('star pill label text is the star number followed by ★', () => {
    renderMap();
    for (let s = 1; s <= 5; s++) {
      expect(screen.getByTestId(`star-filter-${s}`).textContent).toContain(`${s}★`);
    }
  });

  it('stand-down pill text is "— stand-down" (em dash + word)', () => {
    renderMap();
    // \u2014 = em dash. textContent collapses whitespace but keeps the dash char.
    expect(screen.getByTestId('star-filter-standdown').textContent).toContain('\u2014');
    expect(screen.getByTestId('star-filter-standdown').textContent.toLowerCase())
      .toContain('stand-down');
  });
});

describe('MapView admin-gated ? unknown pill', () => {
  beforeEach(() => {
    localStorage.clear();
  });
  afterEach(() => {
    mockRole = 'ADMIN';
    localStorage.clear();
  });

  it('admin sees the ? unknown pill', () => {
    mockRole = 'ADMIN';
    renderMap();
    expect(screen.getByTestId('star-filter-unrated')).toBeInTheDocument();
  });

  it('PRO user does not see the ? unknown pill', () => {
    mockRole = 'PRO_USER';
    renderMap();
    expect(screen.queryByTestId('star-filter-unrated')).not.toBeInTheDocument();
  });

  it('LITE user does not see the ? unknown pill', () => {
    mockRole = 'LITE_USER';
    renderMap();
    expect(screen.queryByTestId('star-filter-unrated')).not.toBeInTheDocument();
  });
});

// ── Filter behaviour: count rendered markers to verify hide/show semantics ──
// The MarkerPopupContent mock emits one <div data-testid="popup-content" />
// per rendered Marker, so counting popups = counting visible locations.

function makeUnratedLocation(name = 'Unknown') {
  return {
    name, lat: 56.0, lon: -1.7,
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: null, solarEventTime: `${TODAY}T18:00:00` },
      sunrise: { rating: null, solarEventTime: `${TODAY}T06:00:00` },
    }]]),
    locationType: ['LANDSCAPE'],
  };
}

function visibleCount() {
  return screen.queryAllByTestId('popup-content').length;
}

describe('MapView filter behaviour — stand-down hide/show', () => {
  beforeEach(() => { mockRole = 'ADMIN'; localStorage.clear(); });
  afterEach(() => { localStorage.clear(); });

  it('stand-down locations are hidden by default (pill unselected)', () => {
    renderMap({ locations: [
      ...makeLocations([4, 3]),
      makeStandDownLocation(),
    ]});
    // 3 total locations, 1 stand-down → only 2 rated are visible
    expect(visibleCount()).toBe(2);
  });

  it('stand-down locations become visible when the pill is selected', () => {
    renderMap({ locations: [
      ...makeLocations([4, 3]),
      makeStandDownLocation(),
    ]});
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    expect(visibleCount()).toBe(3);
  });

  it('toggling stand-down off again hides them', () => {
    renderMap({ locations: [
      ...makeLocations([4, 3]),
      makeStandDownLocation(),
    ]});
    const pill = screen.getByTestId('star-filter-standdown');
    fireEvent.click(pill);
    expect(visibleCount()).toBe(3);
    fireEvent.click(pill);
    expect(visibleCount()).toBe(2);
  });

  it('selecting stand-down does not hide rated locations (additive filter)', () => {
    renderMap({ locations: [
      ...makeLocations([5, 4, 3, 2, 1]),
      makeStandDownLocation(),
    ]});
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    // All 5 rated + 1 stand-down visible
    expect(visibleCount()).toBe(6);
  });

  it('star threshold filter hides stand-down even when a star pill is active', () => {
    renderMap({ locations: [
      ...makeLocations([5, 3]),
      makeStandDownLocation(),
    ]});
    fireEvent.click(screen.getByTestId('star-filter-4'));
    // Only 5★ survives the 4★-and-above threshold; stand-down stays hidden
    expect(visibleCount()).toBe(1);
  });

  it('star threshold + stand-down combine (both filters satisfied)', () => {
    renderMap({ locations: [
      ...makeLocations([5, 3]),
      makeStandDownLocation(),
    ]});
    fireEvent.click(screen.getByTestId('star-filter-4'));
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    // 5★ rated + 1 stand-down
    expect(visibleCount()).toBe(2);
  });

  it('briefingScore.triageReason also triggers stand-down classification', () => {
    const loc = {
      name: 'BriefingStandDown', lat: 55.5, lon: -1.7,
      id: 42,
      forecastsByDate: new Map([[TODAY, {
        sunset: { rating: null, solarEventTime: `${TODAY}T18:00:00` },
        sunrise: { rating: null, solarEventTime: `${TODAY}T06:00:00` },
      }]]),
      locationType: ['LANDSCAPE'],
    };
    const briefingScores = new Map([
      [`Region|${TODAY}|SUNSET|BriefingStandDown`, { triageReason: 'BRIEFING_STAND_DOWN' }],
    ]);
    renderMap({ locations: [...makeLocations([4]), loc], briefingScores });
    // Default: the briefing-stand-down loc is hidden → 1 visible (the 4★)
    expect(visibleCount()).toBe(1);
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    expect(visibleCount()).toBe(2);
  });
});

describe('MapView filter behaviour — unrated (non-stand-down)', () => {
  beforeEach(() => { mockRole = 'ADMIN'; localStorage.clear(); });
  afterEach(() => { localStorage.clear(); });

  it('truly unrated locations (no triage) are shown when no filter is active', () => {
    renderMap({ locations: [...makeLocations([4]), makeUnratedLocation()] });
    // Legacy behaviour: null-rating non-triaged locations are visible by default
    expect(visibleCount()).toBe(2);
  });

  it('stand-down pill does NOT toggle truly unrated locations', () => {
    renderMap({ locations: [...makeLocations([4]), makeUnratedLocation()] });
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    // Still 2 — toggling stand-down is independent of the unrated slot
    expect(visibleCount()).toBe(2);
  });

  it('setting a star threshold hides truly unrated locations', () => {
    renderMap({ locations: [...makeLocations([4]), makeUnratedLocation()] });
    fireEvent.click(screen.getByTestId('star-filter-3'));
    // Only the 4★ survives
    expect(visibleCount()).toBe(1);
  });

  it('admin ? pill restores unrated locations when a star threshold is active', () => {
    renderMap({ locations: [...makeLocations([4]), makeUnratedLocation()] });
    fireEvent.click(screen.getByTestId('star-filter-3'));
    expect(visibleCount()).toBe(1);
    fireEvent.click(screen.getByTestId('star-filter-unrated'));
    expect(visibleCount()).toBe(2);
  });

  it('hasUnrated disables ? pill when the only null-rating loc is actually stand-down', () => {
    // The stand-down location has null rating, but it should NOT count toward "unrated"
    // because triageReason is set. So with only a stand-down + rated locs, ? pill is disabled.
    renderMap({ locations: [...makeLocations([4]), makeStandDownLocation()] });
    expect(screen.getByTestId('star-filter-unrated')).toBeDisabled();
  });
});

describe('MapView advanced filter count badge', () => {
  beforeEach(() => { mockRole = 'ADMIN'; localStorage.clear(); });
  afterEach(() => { localStorage.clear(); });

  it('showStandDown contributes 1 to the filter count badge', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    // Fresh — no badge
    expect(screen.getByTestId('advanced-filters-toggle').textContent).not.toMatch(/\(/);
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    expect(screen.getByTestId('advanced-filters-toggle').textContent).toMatch(/\(1\)/);
  });

  it('stand-down + star threshold combine to count = 2', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    fireEvent.click(screen.getByTestId('star-filter-4'));
    expect(screen.getByTestId('advanced-filters-toggle').textContent).toMatch(/\(2\)/);
  });

  it('Clear button appears when only stand-down is active', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    expect(screen.queryByTestId('clear-all-filters')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    expect(screen.getByTestId('clear-all-filters')).toBeInTheDocument();
  });
});

describe('MapView isStandDownLocation edge cases', () => {
  beforeEach(() => { mockRole = 'ADMIN'; localStorage.clear(); });

  it('pure wildlife location with triageReason is NOT treated as stand-down', () => {
    const wildlife = {
      name: 'WildlifeWithTriage', lat: 55.5, lon: -1.7,
      forecastsByDate: new Map([[TODAY, {
        sunset: { rating: null, triageReason: 'HEAVY_CLOUD' },
        sunrise: { rating: null, triageReason: 'HEAVY_CLOUD' },
      }]]),
      locationType: ['WILDLIFE'],
    };
    renderMap({ locations: [...makeLocations([4]), wildlife] });
    // Wildlife loc should render regardless: 2 visible (1 rated + 1 wildlife)
    expect(visibleCount()).toBe(2);
    // Stand-down pill has no effect on wildlife (still 2)
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    expect(visibleCount()).toBe(2);
    // And the pill is disabled because there are zero true stand-downs
    expect(screen.getByTestId('star-filter-standdown')).toBeDisabled();
  });
});
