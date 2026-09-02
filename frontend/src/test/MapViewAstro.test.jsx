/**
 * Tests for MapView's dark sky chip and astro mode filtering.
 *
 * Covers:
 * - Dark sky chip label text
 * - Dark sky chip visibility for LITE_USER
 * - Astro mode filters to Bortle-class locations only
 *
 * ⚠️ Rewritten for map-tab-v2-plan.md §3 P6, which removed `ForecastTypeSelector` from the Map
 * TAB mount (it survives unchanged on the Plan-tab overlay). The mode switches this file used to
 * drive by clicking the selector's mocked buttons (`type-astro`, `type-aurora`, `type-sunset`) now
 * go through the real `components/map/WindowControl.jsx` dropdown instead — opening the pill and
 * clicking the row for the wanted event type. Every pin this file held before P6 is preserved
 * exactly; only the mechanism for entering a mode changed.
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

// `vi.mock` factories are hoisted above every other statement in the file, so `TODAY` (below,
// as an ordinary `const`) is not yet initialised when these run — `vi.hoisted` is what makes a
// value available to a factory at all.
const { TODAY } = vi.hoisted(() => ({ TODAY: new Date().toLocaleDateString('en-CA') }));

vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([{ locationName: 'DarkSite', stars: 3, nightStart: `${TODAY}T21:00:00` }]),
  // A date so auroraAvailable=true (prevents the auto-reset from AURORA to SUNSET) AND so the
  // window control's dropdown carries an AURORA row for `dark sky chip is hidden in AURORA mode`.
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([TODAY]),
}));

// Astro scores ≥ 3 for the two Bortle-classed locations so they survive the
// default 3★+ quality threshold; this isolates the astro-mode bortle filter
// (the behaviour under test) from the threshold. LightPolluted has no bortle
// class, so it is excluded by the astro filter regardless of its score.
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([
    { locationName: 'DarkSite', stars: 4, nightStart: `${TODAY}T21:00:00` },
    { locationName: 'ModerateSky', stars: 3, nightStart: `${TODAY}T21:00:00` },
  ]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([TODAY]),
}));

// Stub heavy child components
vi.mock('../components/BottomSheet.jsx', () => ({
  default: ({ children }) => <div>{children}</div>,
}));
vi.mock('../components/MarkerPopupContent.jsx', () => ({
  default: () => <div data-testid="popup-content" />,
}));
vi.mock('../components/InfoTip.jsx', () => ({
  default: ({ text }) => <span data-testid="infotip-text">{text}</span>,
}));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({
  default: () => null,
}));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
  STAND_DOWN_COLOUR: '#501313',
}));

// ── Import under test ────────────────────────────────────────────────────────

import MapView from '../components/MapView.jsx';

// ── Test helpers ─────────────────────────────────────────────────────────────
// `TODAY` is declared above (`vi.hoisted`), before the API mocks that need it.

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
    forecastDates: [TODAY],
    autoEventType: null,
    ...overrides,
  };
  return render(<MapView {...props} />);
}

/**
 * Switches the map to the given event type through the real window control — opens the pill's
 * dropdown and clicks the row whose id names that type. Replaces the old mocked
 * `ForecastTypeSelector`'s `type-astro`/`type-aurora`/`type-sunset` buttons, which no longer exist
 * on the tab (map-tab-v2-plan.md §3 P6).
 */
async function enterMode(eventType) {
  await act(async () => {
    fireEvent.click(screen.getByTestId('wf-win-pill'));
  });
  const row = screen.getAllByTestId('wf-win-row')
    .find((r) => r.getAttribute('data-ev-id')?.endsWith(`:${eventType}`));
  expect(row).toBeTruthy();
  await act(async () => {
    fireEvent.click(row);
  });
}

/**
 * Opens the Map tab's filters popover — map-tab-v2-plan.md §3 P7 moved the dark-sky chip (and
 * everything else the old drawer held) off the tab's always-rendered drawer and into
 * `FiltersPopover`, which mounts its rows only while open. ⚠️ Also the reason every "hidden in
 * ASTRO/AURORA mode" test below re-opens it AFTER `enterMode`: the window control and the filters
 * popover share one exclusivity switch (opening either closes the other), so `enterMode`'s own
 * `wf-win-pill` click already closed a popover that was open — re-opening it here is what makes
 * "the chip is gone" a real assertion about mode gating rather than an accident of it being closed
 * for an unrelated reason.
 */
function openFilters() {
  fireEvent.click(screen.getByTestId('wf-filters-chip'));
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
    openFilters();
    const chip = screen.getByTestId('dark-sky-filter-toggle');
    expect(chip.textContent.trim()).toContain('Dark sky');
  });

  it('darkSky_chip_visible_for_lite_user', () => {
    mockUseAuth.mockReturnValue({ role: 'LITE_USER' });
    renderMap();
    openFilters();
    expect(screen.getByTestId('dark-sky-filter-toggle')).toBeInTheDocument();
  });

  it('dark sky chip is hidden in ASTRO mode', async () => {
    renderMap();
    openFilters();
    // Initially in SUNRISE or SUNSET mode (whichever `getNextEventType` picks for "now"), chip
    // visible either way.
    expect(screen.getByTestId('dark-sky-filter-toggle')).toBeInTheDocument();
    await enterMode('ASTRO');
    // `enterMode` closed the popover via the window control's own opening (the two share one
    // exclusivity switch) — re-open it so the absence below is about mode gating, not that.
    openFilters();
    expect(screen.queryByTestId('dark-sky-filter-toggle')).not.toBeInTheDocument();
  });

  it('dark sky chip is hidden in AURORA mode', async () => {
    // Wrap render in act so async useEffects (aurora/astro dates fetch) settle before assertions.
    await act(async () => {
      renderMap();
    });
    openFilters();
    expect(screen.getByTestId('dark-sky-filter-toggle')).toBeInTheDocument();
    await enterMode('AURORA');
    openFilters();
    expect(screen.queryByTestId('dark-sky-filter-toggle')).not.toBeInTheDocument();
  });

  it('dark sky tooltip shows admin instruction for ADMIN', () => {
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
    renderMap();
    openFilters();
    const tips = screen.getAllByTestId('infotip-text');
    const darkSkyTip = tips.find(t => t.textContent.includes('light pollution'));
    expect(darkSkyTip).toBeDefined();
    expect(darkSkyTip.textContent).toContain('Refresh Light Pollution');
  });

  it('dark sky tooltip hides admin instruction for PRO_USER', () => {
    mockUseAuth.mockReturnValue({ role: 'PRO_USER' });
    renderMap();
    openFilters();
    const tips = screen.getAllByTestId('infotip-text');
    const darkSkyTip = tips.find(t => t.textContent.includes('light pollution'));
    expect(darkSkyTip).toBeDefined();
    expect(darkSkyTip.textContent).not.toContain('Refresh Light Pollution');
  });

  it('dark sky tooltip hides admin instruction for LITE_USER', () => {
    mockUseAuth.mockReturnValue({ role: 'LITE_USER' });
    renderMap();
    openFilters();
    const tips = screen.getAllByTestId('infotip-text');
    const darkSkyTip = tips.find(t => t.textContent.includes('light pollution'));
    expect(darkSkyTip).toBeDefined();
    expect(darkSkyTip.textContent).not.toContain('Refresh Light Pollution');
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
    // All 3 locations visible in default SUNRISE/SUNSET mode
    expect(screen.getAllByTestId('marker')).toHaveLength(3);
    // Switch to ASTRO mode
    await enterMode('ASTRO');
    // In ASTRO mode, only locations with bortleClass != null are rendered.
    // DarkSite (bortle 3, astro 4★) and ModerateSky (bortle 5, astro 3★) survive
    // both the astro bortle filter and the default 3★+ threshold; LightPolluted
    // (bortleClass null) is excluded by the astro filter.
    const markers = await screen.findAllByTestId('marker');
    expect(markers).toHaveLength(2);
  });

  it('non-astro mode shows all locations including those without bortleClass', () => {
    renderMap();
    const markers = screen.getAllByTestId('marker');
    expect(markers).toHaveLength(3);
  });
});
