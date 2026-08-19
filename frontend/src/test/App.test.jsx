import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { PLAN_LAYOUT_KEY, PLAN_V2 } from '../hooks/usePlanLayout.js';
import { ukDateStrOffset } from '../utils/mapDates.js';

/**
 * The first App wiring test. App.jsx is the composition root — every prop the two Plan arms
 * receive is wired here and, until this file, none of it was pinned: deleting
 * `locations={visibleLocations}` from the WindowFirstBriefingProvider mount emptied the v2 heat
 * field with 3,697 tests green (heat-field plan, P1 row, "Known gap"). Scope is deliberately the
 * highest-value pins, not exhaustive prop coverage: the flag branch, the provider's roster, and
 * the two panes App withholds (mapPane on data, operationsPane on role).
 *
 * <p>Everything is mocked at the API-module boundary; auth is seeded through localStorage so the
 * real AuthProvider runs. The one seam that is not a fetch: the provider's `locations` prop has no
 * rendered consumer yet — the heat field's surfaces arrive at P2/P4 — so it is observed with a
 * PASSTHROUGH spy on the provider export (the real provider still runs; nothing is stubbed). When
 * a P2 surface renders from `heatPointSets`, that assertion can move onto the DOM.
 */

vi.mock('../api/forecastApi.js', () => ({
  fetchForecasts: vi.fn(),
  fetchLocations: vi.fn(),
  fetchAllOutcomes: vi.fn(),
  // AuthContext imports this for its logout sweep.
  clearForecastDetailCache: vi.fn(),
}));
vi.mock('../api/briefingApi.js', () => ({
  getDailyBriefing: vi.fn(),
  getCloseToHome: vi.fn(),
}));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({
  getSettings: vi.fn(),
  getReach: vi.fn(),
  getDriveTimes: vi.fn(),
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  refreshDriveTimes: vi.fn(),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/auroraApi.js', () => ({ getAuroraStatus: vi.fn() }));
vi.mock('../api/nlcApi.js', () => ({ getNlcSighting: vi.fn() }));
vi.mock('../api/astroApi.js', () => ({ getAstroConditions: vi.fn() }));
vi.mock('../api/almanacApi.js', () => ({ getAlmanac: vi.fn(), ALMANAC_DAYS: 90 }));
vi.mock('../api/runProgressApi.js', () => ({ subscribeToRunNotifications: vi.fn() }));
vi.mock('../api/lightApi.js', () => ({ getTodaysLight: vi.fn() }));
vi.mock('../api/authApi.js', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
  refreshAccessToken: vi.fn(),
  register: vi.fn(),
  resendVerification: vi.fn(),
  verifyEmail: vi.fn(),
  setPasswordForNewUser: vi.fn(),
  submitWaitlist: vi.fn(),
}));

import { fetchForecasts, fetchLocations, fetchAllOutcomes } from '../api/forecastApi.js';
import { getDailyBriefing, getCloseToHome } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getSettings, getReach, getDriveTimes } from '../api/settingsApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAuroraStatus } from '../api/auroraApi.js';
import { getNlcSighting } from '../api/nlcApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getAlmanac } from '../api/almanacApi.js';
import { subscribeToRunNotifications } from '../api/runProgressApi.js';
import { getTodaysLight } from '../api/lightApi.js';

// ── Fixtures ─────────────────────────────────────────────────────────────────

// Derived by calling the helper App calls (the DailyBriefing.test.jsx rule): App reads the wall
// clock through `ukDateStr()`, so a literal date here would drift off "today" the day after it was
// written. Rows sit on TOMORROW only, so `computeAutoSelection` — which looks up TODAY's sunset
// against the real clock — resolves null and no assertion depends on the time of day the suite runs.
const TOMORROW = ukDateStrOffset(1);

const LOCATION_META = [
  {
    id: 7, name: 'Bamburgh', lat: 55.608, lon: -1.719, enabled: true,
    locationType: ['SEASCAPE'], tideType: ['HIGH'], solarEventType: ['SUNRISE', 'SUNSET'],
    bortleClass: 4, region: { name: 'Northumberland' },
  },
  {
    id: 9, name: 'Whitby', lat: 54.486, lon: -0.615, enabled: true,
    locationType: ['SEASCAPE'], tideType: ['HIGH'], solarEventType: ['SUNRISE', 'SUNSET'],
    bortleClass: 5, region: { name: 'Yorkshire Coast' },
  },
];

const forecastRow = (meta, targetType) => ({
  locationName: meta.name,
  locationLat: String(meta.lat),
  locationLon: String(meta.lon),
  targetDate: TOMORROW,
  targetType,
  forecastRunAt: '2026-01-01T06:00:00',
  rating: 3,
});

const FORECASTS = LOCATION_META.flatMap((m) => [forecastRow(m, 'SUNRISE'), forecastRow(m, 'SUNSET')]);

// ── Harness ──────────────────────────────────────────────────────────────────

/**
 * Seeds auth (the real AuthProvider reads these keys), optionally seeds the Plan-layout flag the
 * way usePlanLayout writes it (JSON), installs the passthrough provider spy, and renders App.
 */
const renderApp = ({ role = 'PRO_USER', layout = null } = {}) => {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', role);
  if (layout) localStorage.setItem(PLAN_LAYOUT_KEY, JSON.stringify(layout));
  const providerSpy = vi.spyOn(briefingContext, 'WindowFirstBriefingProvider');
  render(<App />);
  return { providerSpy };
};

beforeEach(() => {
  localStorage.clear();
  window.location.hash = '';
  // Implementations re-asserted per test, not inherited: `vi.clearAllMocks` clears calls, not
  // implementations, so a value resolved in one test would otherwise leak into the next.
  fetchForecasts.mockReset().mockResolvedValue(FORECASTS);
  fetchLocations.mockReset().mockResolvedValue(LOCATION_META);
  fetchAllOutcomes.mockReset().mockResolvedValue([]);
  getDailyBriefing.mockReset().mockResolvedValue(null); // 204 — both arms keep their empty states
  getCloseToHome.mockReset().mockResolvedValue(null);
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  getSettings.mockReset().mockResolvedValue({}); // no home postcode saved
  getReach.mockReset().mockResolvedValue([]);
  getDriveTimes.mockReset().mockResolvedValue({});
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getAuroraStatus.mockReset().mockResolvedValue(null);
  getNlcSighting.mockReset().mockResolvedValue(null);
  getAstroConditions.mockReset().mockResolvedValue(null);
  getAlmanac.mockReset().mockResolvedValue([]);
  subscribeToRunNotifications.mockReset().mockReturnValue(() => {});
  getTodaysLight.mockReset().mockResolvedValue(null); // 204 — no home postcode saved
  // Hold `useAfterFirstPaint` at false for the whole test: an idle callback that never fires keeps
  // the two long-lived SSE streams (health status, run notifications) off, so no test ever reaches
  // for an EventSource jsdom does not have. The 200ms setTimeout fallback would let a slow test
  // open them mid-run.
  window.requestIdleCallback = () => 1;
  window.cancelIdleCallback = () => {};
});

afterEach(() => {
  vi.restoreAllMocks();
  delete window.requestIdleCallback;
  delete window.cancelIdleCallback;
});

// ── The Plan-layout flag branch ──────────────────────────────────────────────

describe('App — the Plan-layout flag branch', () => {
  it('renders the v1 arm by default and never mounts the window-first provider', async () => {
    const { providerSpy } = renderApp();

    expect(await screen.findByTestId('view-toggle')).toBeInTheDocument();
    expect(screen.getByTestId('settings-cog-btn')).toBeInTheDocument();

    // Settle the v1 briefing fetch before the negative claims, so they are made against the
    // loaded page rather than a frame that has not branched yet.
    await screen.findByTestId('daily-briefing-empty');
    expect(screen.queryByTestId('window-first-shell')).toBeNull();
    // Not merely unrendered — never mounted. App's own comment says the provider is inside the
    // flag branch so v1 users never pay its /api/briefing poll; hoisting it would pass every
    // DOM assertion here while doubling the fetch load for every v1 session.
    expect(providerSpy).not.toHaveBeenCalled();
  });

  it('renders the window-first arm when the stored flag says v2, suppressing the v1 header', async () => {
    renderApp({ layout: PLAN_V2 });

    expect(await screen.findByTestId('window-first-shell')).toBeInTheDocument();
    await screen.findByTestId('window-first-pane-empty'); // provider's briefing fetch settled

    // The v1 chrome must be gone, not merely covered: App suppresses its <header> for this arm
    // because the shell carries its own masthead — both at once stacks two wordmarks.
    expect(screen.queryByTestId('view-toggle')).toBeNull();
    expect(screen.queryByTestId('settings-cog-btn')).toBeNull();
  });
});

// ── The masthead light rule's arm gate ───────────────────────────────────────

describe('App — today\'s light is fetched for one arm only', () => {
  // `useTodaysLight`'s own test proves it obeys `enabled=false` when it is given. What it cannot
  // see is the ARGUMENT App passes, which is the whole gate: change
  // `useTodaysLight(planLayout === PLAN_V2, …)` to `useTodaysLight(true, …)` and every v1 reader —
  // the pilot's frozen comparison control — issues a request per load for a band their arm does not
  // render, with the entire suite still green. An adversarial review filed exactly that; it was
  // declined at the time for want of a harness, which #556 has since supplied.

  it('asks for no light on the v1 arm, which renders no band', async () => {
    renderApp();

    await screen.findByTestId('daily-briefing-empty'); // v1 settled, so this is not an early frame
    expect(getTodaysLight).not.toHaveBeenCalled();
  });

  it('asks for it on the window-first arm, which does', async () => {
    // The other half, and the reason it is here: a gate stuck closed would pass the test above.
    renderApp({ layout: PLAN_V2 });

    await screen.findByTestId('window-first-pane-empty');
    await waitFor(() => expect(getTodaysLight).toHaveBeenCalled());
  });
});

// ── The WindowFirstBriefingProvider mount ────────────────────────────────────

describe('App — WindowFirstBriefingProvider wiring', () => {
  it('hands the provider the forecast roster once useForecasts resolves', async () => {
    const { providerSpy } = renderApp({ layout: PLAN_V2 });

    // The Map tab exists only once `allDates` is non-empty, which is derived from the same
    // `visibleLocations` render that re-hands the provider its roster — so once the tab is on
    // screen, the provider's latest props are the post-fetch ones.
    await screen.findByRole('tab', { name: 'Map' });

    const lastProps = providerSpy.mock.calls.at(-1)[0];
    // The roster, with the fields the heat join is built on (id-first join, lat/lon geometry,
    // byte-identical region name, bortle for P4's dark-sky filter). This is the assertion that
    // fails when `locations={visibleLocations}` is deleted: the briefing and scores payloads
    // carry no geography, so without this prop the field has no points at all.
    expect(lastProps.locations.map((l) => l.name)).toEqual(['Bamburgh', 'Whitby']);
    expect(lastProps.locations[0]).toEqual(expect.objectContaining({
      id: 7, lat: 55.608, lon: -1.719, regionName: 'Northumberland', bortleClass: 4,
    }));
    // The reach fetch's only invalidation signal: without it, a first-run user who saves a home
    // postcode watches every reach line stay absent until a full reload.
    expect(lastProps.homeSettingsVersion).toBe(0);
  });
});

// ── Panes handed to WindowFirstShell ─────────────────────────────────────────
//
// The shell renders a tab per pane it is handed and holds no role or data state of its own, so
// these panes ARE App's gates: withholding the pane withholds the tab.

describe('App — panes handed to WindowFirstShell', () => {
  it('hands over the Map pane once forecast dates exist', async () => {
    renderApp({ layout: PLAN_V2 });
    expect(await screen.findByRole('tab', { name: 'Map' })).toBeInTheDocument();
  });

  it('withholds the Map pane while no forecast date exists', async () => {
    // The roster still loads — only the forecast rows are empty. This pins the guard to DATES
    // specifically: a tab onto an empty map is what the v1 arm answers with its "No forecasts
    // loaded yet" card, and §6 bans controls that open onto nothing.
    fetchForecasts.mockResolvedValue([]);
    renderApp({ layout: PLAN_V2 });

    await screen.findByTestId('window-first-pane-empty');
    // The forecast chain resolving [] changes no DOM, so there is nothing to findBy for it; one
    // flush drains its remaining microtasks. The mutation this guards against (mapPane passed
    // unconditionally) renders the tab synchronously, so it cannot slip past this assertion on
    // timing.
    await act(async () => {});

    expect(screen.queryByRole('tab', { name: 'Map' })).toBeNull();
    // The arm itself is intact — the missing tab is a withheld pane, not a broken shell.
    expect(screen.getByRole('tab', { name: 'Plan' })).toBeInTheDocument();
  });

  it('hands the Operations pane to an admin', async () => {
    renderApp({ role: 'ADMIN', layout: PLAN_V2 });
    expect(await screen.findByRole('tab', { name: 'Operations' })).toBeInTheDocument();
  });

  it('withholds the Operations pane from a pro user', async () => {
    renderApp({ layout: PLAN_V2 });
    // Settle on the Map tab so the absence claim is made against the fully-loaded tab bar.
    await screen.findByRole('tab', { name: 'Map' });
    expect(screen.queryByRole('tab', { name: 'Operations' })).toBeNull();
  });
});
