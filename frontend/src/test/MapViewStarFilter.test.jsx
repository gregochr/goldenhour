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
// map-tab-v2-plan.md §3 P6 removed `ForecastTypeSelector` from the Map TAB mount (the only mount
// this file renders — no test here uses `overlayMode`), so the event-type switch its old mocked
// `type-sunrise` button drove now goes through the real `components/map/WindowControl.jsx`
// instead (see `switchToSunrise` below). Left mocked to a no-op rather than deleted: `MapView`
// still imports the real component for its overlay path, and this keeps that import inert here.
vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: () => null,
  EVENT_TYPE_LABELS: {
    SUNRISE: '☀️ Sunrise', SUNSET: '🌇 Sunset', ASTRO: '🌙 Astro', AURORA: '🌌 Aurora',
  },
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
  STAND_DOWN_COLOUR: '#501313',
}));

// ── Test helpers ─────────────────────────────────────────────────────────────

import MapView from '../components/MapView.jsx';

// Fixed "today" + a pinned noon clock so the test's date and MapView's computed today always
// agree — previously flaked near local midnight when the machine-local date diverged from the
// component's. Only Date is faked, so testing-library's real timer-based waits keep working.
const TODAY = '2026-01-15';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
});

afterEach(() => {
  vi.useRealTimers();
});

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

/**
 * Renders the Map tab and — unless {@code openFilters: false} is passed — opens the filters
 * popover immediately (map-tab-v2-plan.md §3 P7 moved every control this file exercises off the
 * always-rendered drawer and into `FiltersPopover`, which mounts its rows only while open). Nearly
 * every test below wants the panel open to reach a row; the handful that test the CHIP's own
 * open/closed state pass {@code openFilters: false} and drive it explicitly.
 */
function renderMap({ openFilters = true, ...overrides } = {}) {
  const props = {
    locations: makeLocations(),
    date: TODAY,
    // A SUNRISE row needs to exist for `switchToSunrise` below to find — every test that calls it
    // renders with the default fixture, so this is here rather than repeated per call site.
    forecastDates: [TODAY],
    autoEventType: null,
    ...overrides,
  };
  const result = render(<MapView {...props} />);
  if (openFilters) fireEvent.click(screen.getByTestId('wf-filters-chip'));
  return result;
}

/**
 * Switches to SUNRISE through the real window control — opens the pill and clicks the row whose
 * id names SUNRISE. Replaces the old mocked `ForecastTypeSelector`'s `type-sunrise` button
 * (map-tab-v2-plan.md §3 P6 removed that selector from the Map tab entirely).
 */
function switchToSunrise() {
  fireEvent.click(screen.getByTestId('wf-win-pill'));
  const row = screen.getAllByTestId('wf-win-row')
    .find((r) => r.getAttribute('data-ev-id')?.endsWith(':SUNRISE'));
  expect(row).toBeTruthy();
  fireEvent.click(row);
}

// ── Tests ─────────────────────────────────────────────────────────────────────

// ⚠️ Rewritten in full for map-tab-v2-plan.md §3 P7, which retired the Map tab's slide-down
// drawer (`advanced-filters-toggle`/`advanced-filters-panel`, `filter-summary`'s text) in favour
// of `FiltersPopover` — a click-to-open chip (`wf-filters-chip`) whose panel (`wf-filters-panel`)
// mounts its rows only while open, and whose chip shows a COUNT rather than a text summary
// (README §4: "chip `Filters (N)`"). The overlay's own drawer is untouched — see
// `MapViewOverlayContext.test.jsx`'s "Plan tab drill-down" suite, which still exercises it. Old
// pins named in-line below, each replaced rather than deleted.
describe('MapView filters popover', () => {
  beforeEach(() => { localStorage.clear(); });
  afterEach(() => { localStorage.clear(); });

  const chip = () => screen.getByTestId('wf-filters-chip');
  const chipOpen = () => chip().getAttribute('aria-expanded') === 'true';

  // Replaces "advanced filters panel is collapsed on fresh load" + "Filters toggle button is
  // present".
  it('is present and collapsed on fresh load, with no active-filter count', () => {
    renderMap({ openFilters: false });
    expect(chip()).toBeInTheDocument();
    expect(chipOpen()).toBe(false);
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
    // At the default (3★+, nothing else) the chip shows no count and no active styling — the
    // count deliberately excludes the always-a-value quality floor at ITS default, matching
    // `hasNonDefaultFilters`'s own "3★+ is never a no-op, but it is the DEFAULT" framing.
    expect(chip()).toHaveTextContent('Filters');
    expect(chip().className).not.toContain('active');
  });

  // Replaces "Filters summary shows the default threshold..." + "...reflects a non-default star
  // threshold": the chip now shows a NUMERIC COUNT, never text, and the count is what drives the
  // active-chip styling.
  it('shows an active count and styling once a non-default filter is chosen', () => {
    renderMap({ openFilters: false });
    fireEvent.click(chip());
    fireEvent.click(screen.getByTestId('star-filter-5'));
    expect(chip()).toHaveTextContent('Filters (1)');
    expect(chip().className).toContain('active');
  });

  // Replaces "clicking the toggle opens the advanced panel" + "...collapses the panel again".
  it('opens the panel on chip click and closes it on a second click', () => {
    renderMap({ openFilters: false });
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();

    fireEvent.click(chip());
    expect(chipOpen()).toBe(true);
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    fireEvent.click(chip());
    expect(chipOpen()).toBe(false);
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
  });

  it('closes on an outside click and on Escape', () => {
    renderMap({ openFilters: false });
    fireEvent.click(chip());
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    fireEvent.mouseDown(document.body);
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();

    fireEvent.click(chip());
    fireEvent.keyDown(screen.getByTestId('wf-filters-panel'), { key: 'Escape' });
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
  });

  it('opening the window control closes an open filters panel, and vice versa — the two share one exclusivity switch', () => {
    renderMap({ openFilters: false });
    fireEvent.click(chip());
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.queryByTestId('wf-filters-panel')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();

    fireEvent.click(chip());
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-filters-panel')).toBeInTheDocument();
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

    it('defaults to the 3★+ threshold when localStorage is empty (3/4/5 highlighted)', () => {
      renderMap();
      expect(screen.getByTestId('star-filter-1').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-2').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-3').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-4').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-5').className).toMatch(/\bon\b/);
    });

    it('pre-selects the saved minimum from localStorage on mount — highlights that star and all above', () => {
      localStorage.setItem('mapFilterMinStars', '3');
      renderMap();
      expect(screen.getByTestId('star-filter-1').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-2').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-3').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-4').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-5').className).toMatch(/\bon\b/);
    });

    it('ignores invalid localStorage values and falls back to the default 3★+ threshold', () => {
      localStorage.setItem('mapFilterMinStars', 'banana');
      renderMap();
      expect(screen.getByTestId('star-filter-1').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-2').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-3').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-4').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-5').className).toMatch(/\bon\b/);
    });

    it('ignores out-of-range localStorage values and falls back to the default 3★+ threshold', () => {
      localStorage.setItem('mapFilterMinStars', '9');
      renderMap();
      expect(screen.getByTestId('star-filter-1').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-2').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-3').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-4').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-5').className).toMatch(/\bon\b/);
    });

    // These three `useState` initialisers (min stars, stand-down, advanced-open) run during the
    // very first render, before any error boundary around MapView has anything mounted to catch —
    // a storage-denied browser (Safari "Block all cookies", an enterprise site-data policy) throws
    // `SecurityError` on bare `localStorage` access, and unguarded that would crash the whole app,
    // not just the map. Falls back to the same default as an empty store; the app must not care why
    // the read failed.
    it('mounts on the default threshold rather than crashing when localStorage.getItem throws', () => {
      const getItemSpy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new DOMException('The operation is insecure.', 'SecurityError');
      });
      expect(() => renderMap()).not.toThrow();
      expect(screen.getByTestId('star-filter-3').className).toMatch(/\bon\b/);
      getItemSpy.mockRestore();
    });

    // The write side: clicking a star still updates the ON-SCREEN state even though persisting it
    // fails, because the filter is still useful for the rest of this session.
    it('still updates the filter when localStorage.setItem throws', () => {
      const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('The operation is insecure.', 'SecurityError');
      });
      renderMap();
      expect(() => fireEvent.click(screen.getByTestId('star-filter-4'))).not.toThrow();
      expect(screen.getByTestId('star-filter-3').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-4').className).toMatch(/\bon\b/);
      setItemSpy.mockRestore();
    });
  });

  describe('clicking star buttons', () => {
    it('marks the clicked star and all stars above it as active', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-3'));
      expect(screen.getByTestId('star-filter-1').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-2').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-3').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-4').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-5').className).toMatch(/\bon\b/);
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
      expect(screen.getByTestId('star-filter-5').className).toMatch(/\bon\b/);
      // 1–4 should no longer be highlighted when minimum is 5
      for (let s = 1; s <= 4; s++) {
        expect(screen.getByTestId(`star-filter-${s}`).className).not.toMatch(/\bon\b/);
      }
    });

    it('keeps the minimum when the same star is clicked again (threshold no longer toggles off)', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-4'));
      fireEvent.click(screen.getByTestId('star-filter-4'));
      // Threshold stays at 4 — still persisted and still highlighted (4 and above).
      expect(localStorage.getItem('mapFilterMinStars')).toBe('4');
      expect(screen.getByTestId('star-filter-4').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-5').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-3').className).not.toMatch(/\bon\b/);
    });
  });

  describe('clear all filters button', () => {
    it('is not shown at the default threshold with no other filters active', () => {
      renderMap();
      // Default is 3★+ and nothing else → hasNonDefaultFilters is false → no Clear button.
      expect(screen.queryByTestId('clear-all-filters')).not.toBeInTheDocument();
    });

    it('appears when a non-default star threshold is active', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-5'));
      expect(screen.getByTestId('clear-all-filters')).toBeInTheDocument();
    });

    it('resets the threshold back to the default 3★+ on click', () => {
      renderMap();
      fireEvent.click(screen.getByTestId('star-filter-5'));
      fireEvent.click(screen.getByTestId('clear-all-filters'));
      // Back to the default 3★+ threshold — 3/4/5 highlighted, 1/2 not.
      expect(screen.getByTestId('star-filter-1').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-2').className).not.toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-3').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-4').className).toMatch(/\bon\b/);
      expect(screen.getByTestId('star-filter-5').className).toMatch(/\bon\b/);
    });

    it('removes mapFilterMinStars from localStorage on click', () => {
      renderMap();
      // Select a non-default threshold so the Clear button appears, then clear it.
      fireEvent.click(screen.getByTestId('star-filter-5'));
      fireEvent.click(screen.getByTestId('clear-all-filters'));
      expect(localStorage.getItem('mapFilterMinStars')).toBeNull();
    });
  });

  describe('event type change clears star filter', () => {
    it('clears localStorage when ForecastTypeSelector changes event type', () => {
      localStorage.setItem('mapFilterMinStars', '4');
      renderMap();
      // ForecastTypeSelector stub calls onChange('SUNRISE') on click
      switchToSunrise();
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
    expect(screen.getByTestId('star-filter-standdown').className).toMatch(/\bon\b/);
  });

  it('event type change clears the stand-down flag', () => {
    localStorage.setItem('mapFilterShowStandDown', '1');
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    switchToSunrise();
    expect(localStorage.getItem('mapFilterShowStandDown')).toBeNull();
  });

  // ⚠️ Rewritten after adversarial review: Clear all no longer touches the admin stand-down
  // lens at all (adjudicated — it is a debug lens, not a reader filter, and gets scope's exact
  // "present, sticky, uncounted" treatment). Clear all is also simply ABSENT here, since toggling
  // stand-down alone raises no count for it to clear — see "MapView filters chip count" below for
  // the direct proof. Toggling it back off is still how a reader turns it off.
  it('stand-down survives Clear all — a genuine filter toggled alongside it is what Clear all resets', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    fireEvent.click(screen.getByTestId('star-filter-4')); // a genuine filter, so Clear all appears
    fireEvent.click(screen.getByTestId('clear-all-filters'));
    expect(localStorage.getItem('mapFilterShowStandDown')).toBe('1');
    expect(screen.getByTestId('star-filter-standdown').className).toMatch(/\bon\b/);
  });
});

// Exact rgb each pill must render. JSDOM normalises hex to "rgb(r, g, b)"
// in element.style.backgroundColor, so these assertions catch colour swaps.
// These are the score ramp's five stops (scoreRamp.js STOPS_VERDICT) — the star-filter swatch is
// one of the map surfaces that paints from the ramp now, not v1's separate RATING_COLOURS table.
const EXPECTED_DOT_RGB = {
  1: 'rgb(176, 58, 42)',   // #B03A2A
  2: 'rgb(200, 69, 47)',   // #C8452F
  3: 'rgb(224, 165, 66)',  // #E0A542
  4: 'rgb(176, 190, 116)', // #B0BE74
  5: 'rgb(138, 174, 114)', // #8AAE72
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
    // Drop the threshold to 1★+ so all five rated locations pass, isolating the
    // additive behaviour of the stand-down toggle from the default 3★+ threshold.
    fireEvent.click(screen.getByTestId('star-filter-1'));
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

  it('briefingScore.rating survives the star-threshold filter (regression: filter only read forecast.rating)', () => {
    // Reproduces the College Valley 3★ bug: rating populated via briefingScores only
    // (i.e. came from cached_evaluation, not a forecast_evaluation row) was rendered
    // on the marker but filtered out by the 3★ threshold, leaving the map empty.
    const loc = {
      name: 'BriefingRated', lat: 55.5, lon: -1.7, id: 99,
      forecastsByDate: new Map([[TODAY, {
        sunset: { rating: null, solarEventTime: `${TODAY}T18:00:00` },
        sunrise: { rating: null, solarEventTime: `${TODAY}T06:00:00` },
      }]]),
      locationType: ['LANDSCAPE'],
    };
    const briefingScores = new Map([
      [`Region|${TODAY}|SUNSET|BriefingRated`, { rating: 3 }],
    ]);
    renderMap({ locations: [loc], briefingScores });
    // Without filter: the briefing-rated loc is visible
    expect(visibleCount()).toBe(1);
    // With 3★ threshold: it must STILL be visible (rating = 3 satisfies >= 3)
    fireEvent.click(screen.getByTestId('star-filter-3'));
    expect(visibleCount()).toBe(1);
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

  // The two score sources genuinely disagree: EvaluationViewService.mergeToView prefers a cached
  // evaluation, while /api/forecast returns the latest run per slot. So a slot can hold a live
  // cached rating AND a superseded triaged forecast row. Reading triage from both sources let the
  // stale row veto the rating — and since the veto runs before the star threshold, the marker was
  // unreachable at any quality setting while every other surface still showed it as "Worth it".
  it('a briefing rating wins over a superseded triaged forecast row', () => {
    const loc = {
      name: 'CachedScoreStaleTriage', lat: 55.5, lon: -1.7,
      id: 43,
      forecastsByDate: new Map([[TODAY, {
        sunset: { rating: null, triageReason: 'HEAVY_CLOUD', solarEventTime: `${TODAY}T18:00:00` },
        sunrise: { rating: null, triageReason: 'HEAVY_CLOUD', solarEventTime: `${TODAY}T06:00:00` },
      }]]),
      locationType: ['LANDSCAPE'],
    };
    const briefingScores = new Map([
      [`Region|${TODAY}|SUNSET|CachedScoreStaleTriage`, { rating: 4, summary: 'Worth it' }],
    ]);
    renderMap({ locations: [...makeLocations([4]), loc], briefingScores });
    expect(visibleCount()).toBe(2);
    // It is genuinely not a stand-down, so the pill has nothing to reveal.
    expect(screen.getByTestId('star-filter-standdown')).toBeDisabled();
  });

  it('the mirror case: a forecast rating wins over a triaged briefing score', () => {
    // The rule has to be symmetric. Fixing only "briefing rating beats forecast triage" would
    // leave the mirror image, and MarkerPopupContent already resolved this direction the other
    // way — which is how a scored medallion came to open a "Stand-down" popup.
    const loc = {
      name: 'ForecastScoredBriefingTriaged', lat: 55.7, lon: -1.7,
      id: 45,
      forecastsByDate: new Map([[TODAY, {
        sunset: { rating: 4, solarEventTime: `${TODAY}T18:00:00` },
        sunrise: { rating: 4, solarEventTime: `${TODAY}T06:00:00` },
      }]]),
      locationType: ['LANDSCAPE'],
    };
    const briefingScores = new Map([
      [`Region|${TODAY}|SUNSET|ForecastScoredBriefingTriaged`, { triageReason: 'HEAVY_CLOUD' }],
    ]);
    renderMap({ locations: [...makeLocations([4]), loc], briefingScores });
    expect(visibleCount()).toBe(2);
    expect(screen.getByTestId('star-filter-standdown')).toBeDisabled();
  });

  // Guards the fallback half of the rule: with no briefing score at all, the forecast row is
  // still authoritative, so a genuine triage must keep hiding the marker.
  it('still stands down on the forecast row when there is no briefing score', () => {
    renderMap({ locations: [...makeLocations([4]), makeStandDownLocation()] });
    expect(visibleCount()).toBe(1);
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    expect(visibleCount()).toBe(2);
  });
});

describe('MapView filter behaviour — unrated (non-stand-down)', () => {
  beforeEach(() => { mockRole = 'ADMIN'; localStorage.clear(); });
  afterEach(() => { localStorage.clear(); });

  it('truly unrated locations (no triage) are hidden by default and revealed by the admin unknown toggle', () => {
    renderMap({ locations: [...makeLocations([4]), makeUnratedLocation()] });
    // With the always-on default 3★+ threshold, truly-unrated non-wildlife locations
    // are hidden by default → only the 4★ is visible.
    expect(visibleCount()).toBe(1);
    // The admin "unknown" toggle reveals them.
    fireEvent.click(screen.getByTestId('star-filter-unrated'));
    expect(visibleCount()).toBe(2);
  });

  it('stand-down pill does NOT toggle truly unrated locations', () => {
    renderMap({ locations: [...makeLocations([4]), makeUnratedLocation()] });
    // Reveal the unrated loc via the unknown toggle so we have 2 visible to start.
    fireEvent.click(screen.getByTestId('star-filter-unrated'));
    expect(visibleCount()).toBe(2);
    // Toggling stand-down is independent of the unrated slot → still 2.
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
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

// ⚠️ Rewritten for map-tab-v2-plan.md §3 P7 — `filter-summary`'s text no longer exists on the tab;
// the chip's active COUNT is the replacement (see "MapView filters popover" above). Old pins named
// in-line.
//
// ⚠️ Rewritten AGAIN after adversarial review adjudicated the admin stand-down/unknown toggles:
// they are debug LENSES that widen the pool back out, not reader filters, so they get the exact
// same treatment as scope (present, sticky, uncounted) — never in the chip's `(N)`, never behind
// the `filtered` flag, never touched by Clear all. The three tests immediately below replace the
// ones that used to assert the opposite (a prior rewrite of the original "filter summary" pins).
describe('MapView filters chip count — admin stand-down/unknown are uncounted, like scope', () => {
  beforeEach(() => { mockRole = 'ADMIN'; localStorage.clear(); });
  afterEach(() => { localStorage.clear(); });

  it('the admin stand-down toggle never raises the chip\'s count or its active styling', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters');
    expect(screen.getByTestId('wf-filters-chip').className).not.toContain('active');
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters');
    expect(screen.getByTestId('wf-filters-chip').className).not.toContain('active');
  });

  it('a genuine filter still counts alongside an already-active stand-down toggle', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    fireEvent.click(screen.getByTestId('star-filter-4'));
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters (1)');
  });

  it('Clear all stays absent with only stand-down active — there is nothing for it to clear', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    expect(screen.queryByTestId('clear-all-filters')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    expect(screen.queryByTestId('clear-all-filters')).not.toBeInTheDocument();
  });

  it('Clear all, when a genuine filter makes it appear, leaves stand-down untouched', () => {
    renderMap({ locations: [...makeLocations(), makeStandDownLocation()] });
    fireEvent.click(screen.getByTestId('star-filter-standdown'));
    fireEvent.click(screen.getByTestId('star-filter-4'));
    fireEvent.click(screen.getByTestId('clear-all-filters'));
    // The genuine filter reset; the sticky admin lens did not.
    expect(screen.getByTestId('star-filter-3').className).toMatch(/\bon\b/);
    expect(screen.getByTestId('star-filter-standdown').className).toMatch(/\bon\b/);
    expect(localStorage.getItem('mapFilterShowStandDown')).toBe('1');
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
