/**
 * Tests for MapView's aurora viewline event-type gating.
 *
 * Covers:
 * - Viewline hidden when Sunrise / Sunset / Astro selected
 * - Viewline visible when Aurora selected + aurora active
 * - Viewline disappears and reappears when toggling away and back to Aurora
 * - Upsell chip also gated to Aurora event type
 *
 * ⚠️ Rewritten for map-tab-v2-plan.md §3 P6, which removed `ForecastTypeSelector` from the Map TAB
 * mount. The old mocked selector's buttons were a PURE kind switch — they never touched `date` —
 * and this file's whole point is isolating the kind axis (the future-date test explicitly holds
 * `date` fixed while toggling kind). The real replacement, `components/map/WindowControl.jsx`, has
 * no such pure switch: every dropdown row NAMES a date, so picking one is a combined kind+date
 * choice by design — exercising that coupling is `WindowControl.test.jsx`'s job, not this one's.
 * `handoffEventType` — a real, pre-existing `MapView` prop that already sets `eventType` with no
 * date implication (Plan-tab event-type handoffs use it the same way) — is the faithful stand-in.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';

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
// Not driven any more (see the file header) — the Map tab this file renders no longer mounts it
// at all. Kept as a harmless no-op so `MapView`'s own (unconditional) import of it stays inert.
vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: () => null,
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
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4\u2605', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
  STAND_DOWN_COLOUR: '#501313',
}));

// ── Import under test ────────────────────────────────────────────────────────

import MapView from '../components/MapView.jsx';

// ── Helpers ──────────────────────────────────────────────────────────────────

// The clock is frozen for every test in this file (see the file-scope `beforeEach` below) and the
// fixture dates are literals against that instant, so nothing here reads the wall clock.
//
// It has to be frozen rather than merely self-consistent. MapView gates the viewline on
// `date === resolveAuroraNight(auroraStatus)`, and the mocked status carries no `currentNightDate`,
// so that falls back to the *UK* calendar date — while a fixture built from the runner's own zone
// (setup.js pins TZ=UTC) is a *UTC* one. Under BST those two name different days between 23:00 and
// 00:00 UTC, the hour after UK midnight, and three of these tests went red for an hour every night.
// Reproduced on a clean origin/main at 00:18 BST on 2026-08-18. A 2027 instant can never collide
// with the real date, so this file's correctness no longer depends on when it runs.
const FROZEN_NOW = new Date('2027-05-12T12:00:00Z'); // 13:00 BST — one date on both calendars
const TODAY = '2027-05-12';
const TOMORROW = '2027-05-13';

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

/**
 * Renders `MapView` and returns a `rerender`-with-the-same-base-props helper alongside it — see
 * `selectEventType` below, which needs to reapply every prop the test already set, not merely the
 * one it changes (`rerender` replaces the whole element rather than merging into it).
 */
async function renderMap(overrides = {}) {
  const props = { locations: makeLocations(), date: TODAY, autoEventType: null, ...overrides };
  let result;
  await act(async () => { result = render(<MapView {...props} />); });
  const withProps = async (nextOverrides) => {
    await act(async () => { result.rerender(<MapView {...props} {...nextOverrides} />); });
  };
  return { ...result, props, withProps };
}

/**
 * Switches event type — a pure kind switch, exactly what the removed mocked selector's buttons
 * did. `handoffEventType` sets `eventType` with no date implication (see the file header), which
 * is what every test below needs isolated.
 */
async function selectEventType(rendered, eventType) {
  await rendered.withProps({ handoffEventType: eventType });
}

// Applies to every test in the file: MapView asks "is this the current night?" on each render, and
// that question needs a fixed answer whatever time the suite runs at. Only `Date` is faked — real
// timers and microtasks still drive `act`.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(FROZEN_NOW);
});
afterEach(() => {
  vi.useRealTimers();
});

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
    const rendered = await renderMap();
    await selectEventType(rendered, 'SUNRISE');
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });

  it('viewline hidden when Astro selected', async () => {
    const rendered = await renderMap();
    await selectEventType(rendered, 'ASTRO');
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });

  it('viewline visible when Aurora selected', async () => {
    const rendered = await renderMap();
    await selectEventType(rendered, 'AURORA');
    expect(screen.getByTestId('aurora-viewline-overlay')).toBeInTheDocument();
  });

  it('viewline hidden on a future date even when Aurora selected', async () => {
    const forecasts = new Map([
      [TOMORROW, {
        sunset: { rating: 4, solarEventTime: `${TOMORROW}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
        sunrise: { rating: 3, solarEventTime: `${TOMORROW}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
      }],
    ]);
    const locs = [{ name: 'TestLoc', lat: 55.0, lon: -1.7, forecastsByDate: forecasts, locationType: ['LANDSCAPE'] }];
    const rendered = await renderMap({ locations: locs, date: TOMORROW });
    await selectEventType(rendered, 'AURORA');
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });

  it('viewline disappears and reappears when toggling away and back', async () => {
    const rendered = await renderMap();
    await selectEventType(rendered, 'AURORA');
    expect(screen.getByTestId('aurora-viewline-overlay')).toBeInTheDocument();

    await selectEventType(rendered, 'SUNSET');
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();

    await selectEventType(rendered, 'AURORA');
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
