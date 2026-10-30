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
 * `autoEventType` — a real, pre-existing `MapView` prop that already sets `eventType` with no
 * date implication (Plan-tab event-type handoffs use it the same way) — is the faithful stand-in.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';

// ── Leaflet / react-leaflet stubs ────────────────────────────────────────────

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = () => ({});
  return { default: { icon, divIcon }, icon, divIcon };
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

const mockUseAuth = vi.fn().mockReturnValue({ role: 'ADMIN' });
vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: (...args) => mockUseAuth(...args),
}));

vi.mock('../hooks/useIsMobile.js', () => ({
  useIsMobile: () => false,
}));

// Stable references — avoids infinite re-render from useEffect dependency checks.
const { stableAuroraStatus, stableViewline, viewlineHookCalls } = vi.hoisted(() => ({
  // Forecast-triggered with a Kp, so the wiring test below can see that NEITHER reaches the viewline
  // hook any more: the hook fetches both endpoints itself. Nothing else in this file reads them.
  stableAuroraStatus: { level: 'MODERATE', kpIndex: 5.0, triggerType: 'forecast', forecastKp: 6.67 },
  viewlineHookCalls: [],
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

// The Pro-shaped status, and the LITE reader's role-free alert (null unless a test sets it).
let mockLiteAlert = null;
vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: stableAuroraStatus, liteAlert: mockLiteAlert }),
}));

vi.mock('../hooks/useAuroraViewline.js', () => ({
  // Records what it is handed. Every other consumer test mocks this hook and ignores its arguments,
  // so what MapView passes it was pinned nowhere.
  useAuroraViewline: (...args) => {
    viewlineHookCalls.push(args);
    return { viewline: stableViewline };
  },
}));

vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
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
vi.mock('../components/InfoTip.jsx', () => ({
  default: ({ text }) => <span data-testid="infotip-text">{text}</span>,
}));

// Render-visible mock so we can assert presence/absence
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({
  default: ({ viewline }) =>
    viewline ? <div data-testid="aurora-viewline-overlay" /> : null,
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
 * did. `autoEventType` sets `eventType` with no date implication (see the file header), which
 * is what every test below needs isolated.
 */
async function selectEventType(rendered, eventType) {
  await rendered.withProps({ autoEventType: eventType });
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

describe('what MapView hands the viewline hook', () => {
  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
    viewlineHookCalls.length = 0;
  });
  afterEach(() => { localStorage.clear(); });

  it('hands the hook only whether to poll — it asks both endpoints itself, so no trigger and no Kp', async () => {
    await renderMap();
    // The status is forecast-triggered at Kp 6.67 (see the hoisted fixture). The forecast endpoint
    // reads NOAA's live forecast per request, so the alert's trigger and Kp are not the hook's input.
    expect(viewlineHookCalls.at(-1)).toEqual([true]);
  });
});

describe('MapView viewline upsell door', () => {
  const ALERT = {
    level: 'MODERATE', active: true, triggerType: 'forecast', allOvercast: false, currentNightEndsAt: null,
  };
  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'LITE_USER' });
    mockLiteAlert = { ...ALERT };
  });
  afterEach(() => { localStorage.clear(); mockLiteAlert = null; });

  it('shows a Viewline Pro door for a LITE reader on an alert, on any event type', async () => {
    await renderMap();
    // LITE users cannot enter Aurora mode, but the door shows on any event type
    expect(screen.getByTestId('viewline-upsell-chip')).toBeInTheDocument();
    expect(screen.getByTestId('viewline-upsell-trigger')).toHaveTextContent('Viewline');
    expect(screen.getByTestId('viewline-upsell-chip').textContent).not.toMatch(/upgrade/i);
  });

  it('opens a popover with the approved aurora copy and no upgrade wording', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('viewline-upsell-trigger'));
    expect(screen.getByTestId('viewline-upsell-heading')).toHaveTextContent('Aurora forecasts');
    expect(screen.getByTestId('viewline-upsell-popover').textContent).not.toMatch(/upgrade/i);
  });

  it('Escape closes only the popover and leaves the door', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('viewline-upsell-trigger'));
    fireEvent.keyDown(screen.getByTestId('viewline-upsell-popover'), { key: 'Escape' });
    expect(screen.queryByTestId('viewline-upsell-popover')).not.toBeInTheDocument();
    expect(screen.getByTestId('viewline-upsell-trigger')).toBeInTheDocument();
  });

  it('the cross dismisses the whole door', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('viewline-upsell-close'));
    expect(screen.queryByTestId('viewline-upsell-chip')).not.toBeInTheDocument();
  });

  it('a PRO reader never sees it', async () => {
    mockUseAuth.mockReturnValue({ role: 'PRO_USER' });
    await renderMap();
    expect(screen.queryByTestId('viewline-upsell-chip')).not.toBeInTheDocument();
  });

  it.each([
    ['no alert', null],
    ['quiet', { ...ALERT, level: 'QUIET' }],
    ['inactive', { ...ALERT, active: false }],
    ['ended', { ...ALERT, currentNightEndsAt: '2020-01-01T00:00:00Z' }],
    ['overcast, not STRONG', { ...ALERT, allOvercast: true }],
  ])('a LITE reader with %s sees nothing', async (_name, alert) => {
    mockLiteAlert = alert;
    await renderMap();
    expect(screen.queryByTestId('viewline-upsell-chip')).not.toBeInTheDocument();
  });

  it('a STRONG alert shows even when every dark-sky site is overcast', async () => {
    mockLiteAlert = { ...ALERT, level: 'STRONG', allOvercast: true };
    await renderMap();
    expect(screen.getByTestId('viewline-upsell-chip')).toBeInTheDocument();
  });

  it('when the alert ends with focus inside the open popover, focus lands on the map pane root, not <body>', async () => {
    const rendered = await renderMap();
    fireEvent.click(screen.getByTestId('viewline-upsell-trigger'));
    expect(screen.getByTestId('viewline-upsell-popover').contains(document.activeElement)).toBe(true);
    mockLiteAlert = null;
    await rendered.withProps({ autoEventType: 'SUNRISE' });
    expect(screen.queryByTestId('viewline-upsell-chip')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(document.querySelector('.wf-map-tab'));
  });

  it('pressing the cross moves focus to the map pane, not <body>', async () => {
    await renderMap();
    screen.getByTestId('viewline-upsell-close').focus();
    fireEvent.click(screen.getByTestId('viewline-upsell-close'));
    expect(screen.queryByTestId('viewline-upsell-chip')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(document.querySelector('.wf-map-tab'));
  });
});
