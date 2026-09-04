import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import * as scoreRamp from '../utils/scoreRamp.js';
import { ukDateStrOffset } from '../utils/mapDates.js';

/**
 * The first App wiring test. App.jsx is the composition root — every prop the Plan shell
 * receives is wired here and, until this file, none of it was pinned: deleting
 * `locations={visibleLocations}` from the WindowFirstBriefingProvider mount emptied the v2 heat
 * field with 3,697 tests green (heat-field plan, P1 row, "Known gap"). Scope is deliberately the
 * highest-value pins, not exhaustive prop coverage: the Plan shell's mount (and its crash
 * fallback), the provider's roster, and the two panes App withholds (mapPane on data,
 * operationsPane on role).
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
import { getDailyBriefing } from '../api/briefingApi.js';
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

// Derived by calling the helper App calls, rather than a literal: App reads the wall
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
 * Seeds auth (the real AuthProvider reads these keys), installs the passthrough provider spy, and
 * renders App.
 */
const renderApp = ({ role = 'PRO_USER' } = {}) => {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', role);
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
  getDailyBriefing.mockReset().mockResolvedValue(null); // 204 — the shell keeps its empty state
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  getSettings.mockReset().mockResolvedValue({}); // no home postcode saved
  getReach.mockReset().mockResolvedValue([]);
  getDriveTimes.mockReset().mockResolvedValue({});
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getAuroraStatus.mockReset().mockResolvedValue(null);
  getNlcSighting.mockReset().mockResolvedValue(null);
  getAstroConditions.mockReset().mockResolvedValue(null);
  getAlmanac.mockReset().mockResolvedValue({ entries: [] });
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

// ── The Plan shell ────────────────────────────────────────────────────────────

describe('App — the Plan shell', () => {
  it('renders the Plan shell', async () => {
    renderApp();

    expect(await screen.findByTestId('window-first-shell')).toBeInTheDocument();
    await screen.findByTestId('window-first-pane-empty'); // provider's briefing fetch settled
  });

  it('renders the Plan fallback when the provider throws', async () => {
    const { providerSpy } = renderApp();

    // The spy is a passthrough on mount (see the harness above), so this is the one test that
    // stops it being one. App's next re-render — `loadHomeCoords` resolving is enough — hits the
    // throwing implementation, and the boundary wrapping the PROVIDER (not just the shell, §4.1)
    // is what is expected to catch it.
    providerSpy.mockImplementation(() => { throw new Error('boom'); });

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'The Plan stopped working' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });
});

// ── The masthead light rule ──────────────────────────────────────────────────

describe('App — fetches today\'s light for the masthead', () => {
  it('asks for it once the Plan shell is up', async () => {
    renderApp();

    await screen.findByTestId('window-first-pane-empty');
    await waitFor(() => expect(getTodaysLight).toHaveBeenCalled());
  });
});

// ── The WindowFirstBriefingProvider mount ────────────────────────────────────

describe('App — WindowFirstBriefingProvider wiring', () => {
  it('hands the provider the forecast roster once useForecasts resolves', async () => {
    const { providerSpy } = renderApp();

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
    renderApp();
    expect(await screen.findByRole('tab', { name: 'Map' })).toBeInTheDocument();
  });

  it('withholds the Map pane while no forecast date exists', async () => {
    // The roster still loads — only the forecast rows are empty. This pins the guard to DATES
    // specifically: §6 bans controls that open onto nothing, and a Map tab with no dates to show
    // would be exactly that.
    fetchForecasts.mockResolvedValue([]);
    renderApp();

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
    renderApp({ role: 'ADMIN' });
    expect(await screen.findByRole('tab', { name: 'Operations' })).toBeInTheDocument();
  });

  it('withholds the Operations pane from a pro user', async () => {
    renderApp();
    // Settle on the Map tab so the absence claim is made against the fully-loaded tab bar.
    await screen.findByRole('tab', { name: 'Map' });
    expect(screen.queryByRole('tab', { name: 'Operations' })).toBeNull();
  });
});

// ── The full-frame Map tab's owner outside the shell (map-tab-v2-plan.md §3 P7) ──
//
// `<main>`'s own padding is one of the plan's four named full-frame owners, and it lives in
// App.jsx — outside `WindowFirstShell` entirely — because the shell has no way to reach a sibling
// element. `WindowFirstShellTabs.test.jsx` pins the shell's own half (the `onTabChange` callback
// firing, the panel-region wrapper KEEPING its 1080px width constraint on the Map tab rather than
// releasing it — O-17, bundle rev 2, 2026-09-03, reversing what this comment used to describe as a
// release — and — since the flex recast, below — the shell root and panel-region wrapper's own
// flex classes); this is the other end of that channel.
//
// ⚠️ Re-pinned onto a flex column (adversarial review): a `calc(100dvh - …)` height chain shipped
// here first and leaked twice — most recently 16px of page scroll surviving with every banner
// suppressed, because the panel's real top sat 16px below the sum of the measured terms, an
// inter-element margin a `ResizeObserver` on element BOXES cannot see. `App`'s root and `<main>`
// now recast as a flex column instead: no height is computed anywhere, so what this file can pin
// is STRUCTURE (which classes land on which elements, and only on the Map tab) rather than a
// number — the actual "does it fill the screen" claim is the orchestrator's browser verification,
// per CLAUDE.md's UI cadence for any CSS claim.

describe('App — recasts the page as a flex column on the Map tab (map-tab-v2-plan.md §3 P7)', () => {
  // Exact-token helper, not `className.toContain` — since O-17 (bundle rev 2) the Map tab's own
  // horizontal-padding utility is `sm:px-4`, which itself CONTAINS the substring `'px-4'`. A plain
  // `.toContain('px-4')` can no longer tell "unprefixed px-4" from "sm:px-4" apart, so it would
  // pass whichever one was actually present — exactly the false-pass this suite exists to rule
  // out. Comparing tokens is the fix.
  const classTokens = (el) => el.className.split(/\s+/).filter(Boolean);

  it('drops <main>\'s VERTICAL padding unconditionally, keeps horizontal padding at sm+ (adversarial review), and gives it flex:1/min-height:0/flex-column — only on the Map tab', async () => {
    renderApp();
    // <main> has no accessible name of its own to query by name; its implicit ARIA role finds it.
    const main = screen.getByRole('main');
    expect(classTokens(main)).toContain('px-4');
    expect(classTokens(main)).not.toContain('sm:px-4');
    expect(classTokens(main)).toContain('py-6');
    expect(classTokens(main)).not.toContain('flex-1');

    const mapTab = await screen.findByRole('tab', { name: 'Map' });
    await act(async () => { mapTab.click(); });
    await screen.findByTestId('window-first-panel-map');

    // O-17 (bundle rev 2, 2026-09-03): the Map tab keeps a horizontal inset at `sm` and up — it
    // is what makes the masthead's column line up with every other tab's in that range, since
    // `WindowFirstShell`'s own panel-region wrapper caps the visible width to the SAME 1080px
    // column from `sm` up too. Only VERTICAL padding is dropped unconditionally (it would eat
    // into the flex-distributed height and reopen page scroll).
    expect(classTokens(main)).not.toContain('px-4');
    expect(classTokens(main)).toContain('sm:px-4');
    expect(classTokens(main)).not.toContain('py-6');
    expect(classTokens(main)).toContain('flex-1');
    expect(classTokens(main)).toContain('min-h-0');
    expect(classTokens(main)).toContain('flex-col');

    // Back to Plan restores it — the recast is per-tab, not a one-way switch.
    const planTab = screen.getByRole('tab', { name: 'Plan' });
    await act(async () => { planTab.click(); });
    expect(classTokens(main)).toContain('px-4');
    expect(classTokens(main)).not.toContain('sm:px-4');
    expect(classTokens(main)).toContain('py-6');
    expect(classTokens(main)).not.toContain('flex-1');
  });

  // ⚠️ Below `sm` (640px) O-17 does NOT close the residue: `sm:px-4` is a RESPONSIVE utility, so
  // the token itself is present in the markup unconditionally (Tailwind ships it as a static class
  // string; a media query in the generated CSS decides whether it paints, not JS, and jsdom
  // evaluates no media query at all — every `window.matchMedia` call in this suite is a dumb
  // polyfill, per `test/setup.js`). So there is no class-token assertion that could distinguish
  // "present but inert below 640px" from "present and active" — that is a rendered-CSS claim, and
  // CLAUDE.md's UI cadence already puts rendered-CSS claims on the browser, not on this file. What
  // is left to state in prose rather than pin in code: below `sm`, `<main>` still carries no
  // horizontal padding class that actually PAINTS (`sm:px-4` needs `sm` to apply), so the masthead
  // genuinely shifts by 32px on a Plan⇄Map switch on a phone — P12's full-bleed phone chrome
  // (index.css's edge-hugging bar insets) was measured and tuned against that genuine edge, and
  // `WindowFirstShell`'s own 1080px column cap does not bind at phone widths regardless (390px is
  // nowhere near 1080px). See this describe block's own header comment and `App.jsx`'s comment on
  // `<main>` for the full account.

  it('recasts the page root from min-h-screen to a fixed-height, non-scrolling flex column, only on the Map tab', async () => {
    renderApp();
    const main = screen.getByRole('main');
    // The root is <main>'s own parent — the outermost element App renders, with no accessible
    // role or testid of its own to query by; reached the same way as the banner block was before
    // it lost its own ref, through a DOM relationship that does not change across this recast.
    const root = main.parentElement;
    expect(root.className).toContain('min-h-screen');
    expect(root.className).not.toContain('overflow-hidden');

    const mapTab = await screen.findByRole('tab', { name: 'Map' });
    await act(async () => { mapTab.click(); });
    await screen.findByTestId('window-first-panel-map');

    expect(root.className).not.toContain('min-h-screen');
    expect(root.className).toContain('flex');
    expect(root.className).toContain('flex-col');
    expect(root.className).toContain('overflow-hidden');

    const planTab = screen.getByRole('tab', { name: 'Plan' });
    await act(async () => { planTab.click(); });
    expect(root.className).toContain('min-h-screen');
    expect(root.className).not.toContain('overflow-hidden');
  });
});

// Adversarial review, real finding #2, upgraded by a live measurement: the app-wide footer ALONE
// overflowed the full-frame page by 99px at 1280×800 — with zero banners showing. `<main>`'s own
// padding was never the whole story; the footer sitting below it is a second owner outside the
// shell's reach, and it is suppressed rather than measured, because a footer under a no-scroll
// screen whose whole point is "fills the frame" is dead space no calc term should have to reserve.
describe('App — suppresses the app-wide footer on the Map tab (adversarial review, real finding #2)', () => {
  it('renders the footer on Plan, and removes it once the Map tab is selected', async () => {
    renderApp();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();

    const mapTab = await screen.findByRole('tab', { name: 'Map' });
    await act(async () => { mapTab.click(); });
    await screen.findByTestId('window-first-panel-map');
    expect(screen.queryByRole('contentinfo')).not.toBeInTheDocument();

    // Back to Plan restores it — suppression is per-tab, not a one-way switch.
    const planTab = screen.getByRole('tab', { name: 'Plan' });
    await act(async () => { planTab.click(); });
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });
});

// ── The map colour preference reaches scoreRamp ──────────────────────────────
//
// loadHomeCoords is the one place App wires the loaded setting into scoreRamp.setMode, so Plan
// and Map can never disagree about what a colour means (heat-scale-unification-plan.md, rule 1).

describe('App — wires the loaded map colour preference into scoreRamp', () => {
  afterEach(() => {
    // The spy is a passthrough on the real singleton module — restore its module-level MODE to
    // its own raw bootstrap value ('verdict', deliberately NOT `DEFAULT_MODE` — see scoreRamp.js's
    // own comment on `MODE`) so this test cannot leak a switched ramp into any other file running
    // in the same process.
    scoreRamp.setMode('verdict');
  });

  it('a loaded \'temp\' preference reaches setMode', async () => {
    getSettings.mockResolvedValue({ mapColourScale: 'temp' });
    const setModeSpy = vi.spyOn(scoreRamp, 'setMode');
    renderApp();

    await screen.findByTestId('window-first-pane-empty');
    await waitFor(() => expect(setModeSpy).toHaveBeenCalledWith('temp'));
    expect(scoreRamp.getMode()).toBe('temp');
  });

  it('an explicit \'verdict\' preference reaches setMode and is not swept up in the flip', async () => {
    getSettings.mockResolvedValue({ mapColourScale: 'verdict' });
    const setModeSpy = vi.spyOn(scoreRamp, 'setMode');
    renderApp();

    await screen.findByTestId('window-first-pane-empty');
    await waitFor(() => expect(setModeSpy).toHaveBeenCalledWith('verdict'));
    expect(scoreRamp.getMode()).toBe('verdict');
  });

  // Stage 7: flipped from Stage 6's 'a never-chosen preference resolves to verdict' now that the
  // default has actually flipped — a reader who has never chosen gets the temperature scale.
  it('a never-chosen preference resolves to temp, the new default', async () => {
    getSettings.mockResolvedValue({ mapColourScale: null });
    renderApp();

    await screen.findByTestId('window-first-pane-empty');
    await waitFor(() => expect(getSettings).toHaveBeenCalled());
    expect(scoreRamp.getMode()).toBe('temp');
  });

  it('an unrecognised stored value resolves to verdict, not silently to the new default', async () => {
    getSettings.mockResolvedValue({ mapColourScale: 'garbled' });
    renderApp();

    await screen.findByTestId('window-first-pane-empty');
    await waitFor(() => expect(getSettings).toHaveBeenCalled());
    expect(scoreRamp.getMode()).toBe('verdict');
  });
});
