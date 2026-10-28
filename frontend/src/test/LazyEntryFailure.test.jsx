import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  act, render, screen, fireEvent, within, waitFor,
} from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStrOffset } from '../utils/mapDates.js';
import { foreignModalOver } from '../utils/mapForeignModal.js';

/**
 * P2d: a settings chunk that fails to load (a stale tab after a deploy, an offline PWA) shows the
 * `ChunkErrorBoundary` over the page instead of unmounting the root, and its Close leaves the Plan
 * as it was. The App harness is `AppSettingsRoutes.test.jsx`'s.
 */

const g = vi.hoisted(() => {
  let reject;
  const promise = new Promise((res, rej) => { reject = rej; });
  promise.catch(() => {});
  return { promise, reject };
});
vi.mock('../utils/settingsModalChunk.js', () => ({
  loadSettingsModal: () => g.promise,
  warmSettingsModal: () => {},
}));

vi.mock('../api/forecastApi.js', () => ({
  fetchForecasts: vi.fn(),
  fetchLocations: vi.fn(),
  clearForecastDetailCache: vi.fn(),
}));
vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({
  getSettings: vi.fn(),
  getReach: vi.fn(),
  getDriveTimes: vi.fn(),
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  refreshDriveTimes: vi.fn(),
  saveMapColourPreferences: vi.fn(),
  markComingUpSeen: vi.fn(),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/accountApi.js', () => ({ deleteAccount: vi.fn() }));
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn().mockResolvedValue({ enforced: false, limit: 3, picks: [], options: [], suggested: [], status: 'FIRST_CHOICE' }), saveSubRegions: vi.fn() }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
// The banner asks for per-location scores whenever a real alert is up (aurora-banner-plan.md B2);
// empty answers keep it on its pending slot, which is all this suite needs of it.
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraStatus: vi.fn(),
  getAuroraAlert: vi.fn().mockResolvedValue(null),
  getAuroraLocations: vi.fn(() => Promise.resolve([])),
  getAuroraForecastResults: vi.fn(() => Promise.resolve([])),
}));
vi.mock('../api/nlcApi.js', () => ({ getNlcSighting: vi.fn() }));
vi.mock('../api/astroApi.js', () => ({ getAstroConditions: vi.fn() }));
vi.mock('../api/almanacApi.js', () => ({ getAlmanac: vi.fn(), ALMANAC_DAYS: 90 }));
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
// Plan-first, as `App.test.jsx` pins it: jsdom's `matchMedia` answers "no match", which the real
// `resolveInitialTab()` reads as a desktop and opens on Map, and the banner's door is judged by the
// tab in force when it is pressed.
vi.mock('../utils/initialTab.js', () => ({ resolveInitialTab: () => 'plan' }));
/** What the callout's `Four days here ›` hands `App` — the peek, which does not move the tab. */
const PEEK = {
  id: 1, name: 'Derwentwater', regionName: 'Lake District',
  date: '2026-08-14', targetType: 'SUNSET', inPlan: false,
};

/**
 * The Map pane, cut down to the two controls this file drives. Each carries the accessible name
 * its real counterpart has, so the test reads as the reader's route; the test-ids say they are
 * stand-ins.
 */
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: ({ onOpenSettings, onOpenLocationSheet, handoff }) => (
    <div data-testid="map-pane-stub">
      {/* What `App` handed the pane as its door, so a test reads the payload straight off it. */}
      <output data-testid="stub-handoff">{handoff ? JSON.stringify(handoff) : ''}</output>
      <button type="button" data-testid="stub-four-days" onClick={() => onOpenLocationSheet?.(PEEK)}>
        Four days here
      </button>
      <button type="button" data-testid="stub-home-control" onClick={() => onOpenSettings?.()}>
        Set your home postcode in Settings
      </button>
    </div>
  ),
}));

import { fetchForecasts, fetchLocations } from '../api/forecastApi.js';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getSettings, getReach, getDriveTimes } from '../api/settingsApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { deleteAccount } from '../api/accountApi.js';
import { getAuroraStatus } from '../api/auroraApi.js';
import { getNlcSighting } from '../api/nlcApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getAlmanac } from '../api/almanacApi.js';
import { getTodaysLight } from '../api/lightApi.js';

// ── Fixtures ─────────────────────────────────────────────────────────────────

// The roster and the rows only have to make `App` hand the shell a Map pane: `allDates` is built
// from the forecast rows, and a Map tab onto no dates is withheld. TOMORROW, by `App`'s own helper,
// so no assertion depends on the time of day the suite runs (`App.test.jsx` gives the reasoning).
const TOMORROW = ukDateStrOffset(1);
const LOCATION_META = [{
  id: 1, name: 'Derwentwater', lat: 54.58, lon: -3.14, enabled: true,
  locationType: ['LANDSCAPE'], tideType: [], solarEventType: ['SUNRISE', 'SUNSET'],
  bortleClass: 3, region: { name: 'Lake District' },
}];
const FORECASTS = ['SUNRISE', 'SUNSET'].map((targetType) => ({
  locationName: 'Derwentwater', locationLat: '54.58', locationLon: '-3.14',
  targetDate: TOMORROW, targetType, forecastRunAt: '2026-01-01T06:00:00', rating: 3,
}));

/** No postcode saved — the one state in which the nudge and the ⌂'s settings branch exist. */
const SETTINGS_NO_HOME = {
  role: 'PRO_USER', homePostcode: null, homePlaceName: null, homeLatitude: null,
  homeLongitude: null, driveTimesCalculatedAt: null, mapColourScale: 'temp',
  comingUpLastSeenDate: null,
};

/** A live alert the banner shows, so its Map door can be pressed. */
const AURORA_ALERT = {
  level: 'MODERATE', kpIndex: 6, active: true, currentNightDate: TOMORROW, simulated: false,
};

const CARD = {
  key: '2026-08-14:SUNSET',
  date: '2026-08-14',
  targetType: 'SUNSET',
  lead: true,
  kicker: 'Tonight',
  when: 'Sunset',
  time: '20:37',
  verdict: 'WORTH_IT',
  verdictLabel: 'Worth it',
  bestRating: 4,
  confidence: 'high',
  badges: [],
  allBadges: [],
  rows: [],
  pick: null,
  spots: [{
    key: '1', locationId: 1, locationName: 'Derwentwater', regionName: 'Lake District',
    solarEventTime: '2026-08-14T19:41:00', rating: 4, driveMinutes: 12,
  }],
  allSpots: [],
  reachTotal: 1,
  reachedTotal: 1,
};

const LENS = {
  tier: { id: '45', limitMinutes: 45, label: '45 min' },
  tierId: '45',
  defaultTier: { id: '45', limitMinutes: 45, label: '45 min' },
  defaultTierId: '45',
  weekend: false,
  overridden: false,
  locked: false,
  selectTier: vi.fn(),
  resetToDefault: vi.fn(),
};

/**
 * The shell's context: one window the matrix can open, one place the four-day sheet can describe,
 * and — the point — no home, so the tick line carries the nudge and not the origin button.
 */
const ctx = () => ({
  briefing: {
    generatedAt: '2026-08-14T12:00:00',
    days: [{
      date: '2026-08-14',
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'Lake District', displayVerdict: 'WORTH_IT', meanRating: 4.2, bestRating: 4,
          confidence: 'high',
          slots: [{
            canopy: false, locationId: 1, locationName: 'Derwentwater',
            solarEventTime: '2026-08-14T19:41:00',
          }],
        }],
        unregioned: [],
      }],
    }],
  },
  loading: false,
  windowCards: [CARD],
  paneItems: [{ kind: 'card', key: CARD.key, card: CARD }],
  upcomingEvents: [],
  travelDayDates: new Set(),
  reachById: new Map(),
  effectiveReachById: new Map(),
  isPro: true,
  isLiteUser: false,
  evaluationScores: new Map(),
  scoresLoaded: true,
  scoreRows: [{
    locationId: 1, locationName: 'Derwentwater', date: '2026-08-14', targetType: 'SUNSET',
    rating: 4, summary: 'Broken cloud clearing from the west.',
  }],
  scoreIndex: new Map(),
  heatStripCards: [{
    key: CARD.key, date: CARD.date, targetType: CARD.targetType, dow: 'Fri', sunrise: false,
    label: 'Tonight Sunset', time: CARD.time, verdict: CARD.verdict,
    verdictLabel: CARD.verdictLabel, pickKind: null, away: false, confidence: 'high',
  }],
  heatSpots: [{
    id: 1, name: 'Derwentwater', lat: 54.58, lng: -3.14, regionName: 'Lake District',
    rid: 'Lake District', skySubject: true, bortleClass: 3, scores: [4],
  }],
  heatPointSets: new Map(),
  regionSeries: new Map(),
  todayStr: '2026-08-14',
  tomorrowStr: '2026-08-15',
  reachLens: LENS,
  ratingLens: {
    floor: { id: 'any', min: null, label: 'Any rating' }, floorId: 'any', minRating: null,
    selectFloor: vi.fn(),
  },
  homePlace: null,
  origin: null,
  setOrigin: vi.fn(),
  regions: [{ id: 7, name: 'Lake District', baseName: 'Keswick', baseLat: 54.6, baseLon: -3.1 }],
  comingUpLastSeenDate: '2026-08-14',
  setComingUpLastSeenAt: vi.fn(),
});

const NUDGE = 'Set a postcode for light and drive times';
const HOME_CONTROL = 'Set your home postcode in Settings';
/** The postcode field's accessible name — the settings dialog's "Home location" heading. */
const POSTCODE_FIELD = 'Home location';

// ── Harness ──────────────────────────────────────────────────────────────────

const renderApp = () => {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', 'PRO_USER');
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  render(<App />);
};

/** The dialogs claiming to be THE modal — the property this file exists for is that there is one. */
const modals = () => screen.queryAllByRole('dialog')
  .filter((dialog) => dialog.getAttribute('aria-modal') === 'true');

/**
 * Presses a control the way a keyboard reader does — focus first, then the activation — so each
 * dialog's own focus bookkeeping records the element that was really pressed. A bare
 * `fireEvent.click` moves no focus in jsdom, and every dialog would record `<body>` as its opener.
 */
async function press(control) {
  act(() => { control.focus(); });
  await act(async () => { fireEvent.click(control); });
}

/**
 * Waits for a dialog to take focus on opening, which `useDialogFocus` does a frame after mount.
 *
 * <p>⚠️ Spent before the next press rather than left pending: an unspent mount frame can land in
 * the middle of a later step and supply a focus assertion's expected value itself.
 */
async function tookFocus(dialog) {
  await waitFor(() => expect(dialog).toHaveFocus());
  return dialog;
}

/**
 * Opens the first window's popup. Awaits the matrix's lazy boundary and then the popup's, so the
 * first test in the file behaves like the rest (frontend-test-standards, "The first test in a file
 * is not like the others").
 */
async function openPopup() {
  await screen.findByTestId('wf-heat-strip');
  await press(screen.getAllByTestId('wf-heat-card')[0]);
  return tookFocus(await screen.findByTestId('window-sheet'));
}

/** Selects the Map tab — the pane mounts on first selection — and waits for the stub pane. */
async function openMapTab() {
  const tab = await screen.findByRole('tab', { name: 'Map' });
  await act(async () => { fireEvent.click(tab); });
  await screen.findByTestId('map-pane-stub');
  return tab;
}

/** Opens the four-day sheet OVER the map, the way the callout's `Four days here ›` does. */
async function openPeek() {
  await press(screen.getByTestId('stub-four-days'));
  return tookFocus(await screen.findByTestId('location-sheet'));
}

/** Closes the settings dialog from its own ×, and lets a frame pass so nothing is still pending. */
async function closeSettings(dialog) {
  await press(within(dialog).getByRole('button', { name: 'Close' }));
  await act(async () => {
    await new Promise((resolve) => { requestAnimationFrame(() => requestAnimationFrame(resolve)); });
  });
  expect(screen.queryByTestId('settings-modal')).toBeNull();
}

/**
 * Waits for the settings dialog's own read, so nothing is still landing when a test asserts — and
 * for its mount frame, so a later focus assertion cannot be answered by it.
 *
 * <p>⚠️ And checks the Plan did not crash behind it. `PlanErrorBoundary` wraps the shell while the
 * settings dialog is its sibling, so a shell that threw on the press — a render-phase loop in the
 * close, say — still leaves "one modal, named Settings, and the Plan dialog gone": every count in
 * this file would pass against a dead Plan.
 */
async function settingsSettled() {
  const dialog = await screen.findByTestId('settings-modal');
  expect(screen.queryByTestId('plan-error')).toBeNull();
  await within(dialog).findByRole('textbox', { name: POSTCODE_FIELD });
  await act(async () => {
    await new Promise((resolve) => { requestAnimationFrame(() => requestAnimationFrame(resolve)); });
  });
  return dialog;
}

beforeEach(() => {
  localStorage.clear();
  window.location.hash = '';
  fetchForecasts.mockReset().mockResolvedValue(FORECASTS);
  fetchLocations.mockReset().mockResolvedValue(LOCATION_META);
  getDailyBriefing.mockReset().mockResolvedValue(null);
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  getSettings.mockReset().mockResolvedValue(SETTINGS_NO_HOME);
  getReach.mockReset().mockResolvedValue([]);
  getDriveTimes.mockReset().mockResolvedValue({});
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getAuroraStatus.mockReset().mockResolvedValue(null);
  getNlcSighting.mockReset().mockResolvedValue(null);
  getAstroConditions.mockReset().mockResolvedValue(null);
  getAlmanac.mockReset().mockResolvedValue({ entries: [] });
  getTodaysLight.mockReset().mockResolvedValue(null);
  // Holds `useAfterFirstPaint` at false, so the two SSE streams never open a real `fetch` stream
  // (`setup.js` makes an unmocked `fetch` throw) — `App.test.jsx`'s reasoning, unchanged.
  window.requestIdleCallback = () => 1;
  window.cancelIdleCallback = () => {};
});

afterEach(() => {
  vi.restoreAllMocks();
  delete window.requestIdleCallback;
  delete window.cancelIdleCallback;
});


describe('the settings dialog when its chunk fails', () => {
  it('shows the error boundary, keeps the Plan, and closes cleanly', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    renderApp();
    await screen.findByTestId('wf-heat-strip');
    await press(screen.getByRole('button', { name: 'Settings' }));
    await screen.findByTestId('settings-fallback');

    await act(async () => { g.reject(new Error('Failed to fetch dynamically imported module')); });
    const failure = await screen.findByTestId('chunk-error');
    expect(screen.getByRole('dialog', { name: 'Settings' })).toContainElement(failure);
    expect(screen.getByTestId('chunk-error-reload')).toBeInTheDocument();
    expect(screen.queryByTestId('plan-error')).toBeNull();
    expect(screen.getByTestId('wf-heat-strip')).toBeInTheDocument();

    await press(screen.getByTestId('chunk-error-close'));
    await waitFor(() => expect(screen.queryByTestId('chunk-error')).toBeNull());
    expect(screen.getByTestId('wf-heat-strip')).toBeInTheDocument();
  });
});