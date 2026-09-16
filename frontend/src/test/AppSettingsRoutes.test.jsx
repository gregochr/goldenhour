import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  act, render, screen, fireEvent, within, waitFor,
} from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStrOffset } from '../utils/mapDates.js';

/**
 * Every route into the settings dialog leaves it the ONLY modal on the page.
 *
 * <p>`UserSettingsModal` is a SIBLING of the Plan shell in `App`, so it is outside every mechanism
 * the shell has for ordering its own layers: it is not a `Modal` the shell renders, the shell's
 * `stackedOverPopup` cannot see it, and it takes no `stacked` opt-in. It cannot be ordered against a
 * shell dialog — it can only be arrived at with none of them open. "At most one dialog may claim to
 * be the modal" is held route by route (v1-retirement §4.3), and M5 closed the cog's. This file
 * holds the other two routes into the same dialog, through the real `App`, so the count is taken
 * against the real settings dialog rather than against a handler that was merely called:
 *
 * <ul>
 *   <li>the masthead tick line's "set a postcode" nudge, which `App` wires straight to its own
 *       handler — reachable by Tab from an open window popup, because the popup is not a trap and
 *       the tick line keeps its tab stops while only the popup is up;</li>
 *   <li>the Map tab's ⌂ in its no-postcode state, which reaches `App` through the map pane rather
 *       than through the shell at all — reachable by Tab from the four-day sheet the callout's
 *       `Four days here ›` opens OVER the map (map-tab-v2-plan.md O-18; the Tab-out itself is O-20
 *       arm A).</li>
 * </ul>
 *
 * <p>The count is filtered off the dialog ROLE rather than a raw `[aria-modal]` selector, as
 * `WindowFirstShellSheet.test.jsx`'s own count is: it fails naming which dialogs are on screen.
 *
 * <p>Mocked at the API-module boundary as `App.test.jsx` is; the shell's context is stubbed the way
 * every shell suite stubs it (the derivations have their own files), so the matrix has a window to
 * open. The Map pane is a stub carrying the two controls this file needs from it — the real ⌂'s
 * own rule (it calls `onOpenSettings` only while there is no home and no origin) is
 * `MapViewCentreOnHome.test.jsx`'s to pin; what is App's, and this file's, is where that call goes.
 */

vi.mock('../api/forecastApi.js', () => ({
  fetchForecasts: vi.fn(),
  fetchLocations: vi.fn(),
  fetchAllOutcomes: vi.fn(),
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
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
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
// Stubbed for the reason every shell suite gives: mounting the doors fires an astro request per
// visible date, and this file is about neither.
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

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
  default: ({ onOpenSettings, onOpenLocationSheet }) => (
    <div data-testid="map-pane-stub">
      <button type="button" data-testid="stub-four-days" onClick={() => onOpenLocationSheet?.(PEEK)}>
        Four days here
      </button>
      <button type="button" data-testid="stub-home-control" onClick={() => onOpenSettings?.()}>
        Set your home postcode in Settings
      </button>
    </div>
  ),
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
 */
async function settingsSettled() {
  const dialog = await screen.findByTestId('settings-modal');
  await within(dialog).findByTestId('settings-postcode-input');
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
  fetchAllOutcomes.mockReset().mockResolvedValue([]);
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
  subscribeToRunNotifications.mockReset().mockReturnValue(() => {});
  getTodaysLight.mockReset().mockResolvedValue(null);
  // Holds `useAfterFirstPaint` at false, so the two SSE streams never reach for an EventSource
  // jsdom does not have — `App.test.jsx`'s reasoning, unchanged.
  window.requestIdleCallback = () => 1;
  window.cancelIdleCallback = () => {};
});

afterEach(() => {
  vi.restoreAllMocks();
  delete window.requestIdleCallback;
  delete window.cancelIdleCallback;
});

// ── The Plan tab ─────────────────────────────────────────────────────────────

describe('App — the settings dialog is the only modal, whichever route opened it', () => {
  describe('from the Plan tab, with a window popup open', () => {
    it('the cog: the control case — the harness reads the real settings dialog as the one modal', async () => {
      // Proves the harness first: the real settings dialog counts as a modal here, and the route M5
      // closed leaves it alone. Every negative below rests on this reading right.
      //
      // ⚠️ In `App` each shell route into settings is covered TWICE — by its own close and by
      // `settingsOpen` — so none of these App-level cases can tell which one held. That is the
      // design, not a gap: `WindowFirstShellSheet.test.jsx` renders the shell without `App` and pins
      // the cog's and the nudge's own closes; the ⌂'s route below has only `settingsOpen`.
      renderApp();
      await openPopup();

      await press(screen.getByRole('button', { name: 'Settings' }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(modals()[0]).toHaveAccessibleName('Settings');
      expect(screen.queryByTestId('window-sheet')).toBeNull();
    });

    it('⚠️ the postcode nudge: the popup goes before settings opens', async () => {
      // Before the fix: the popup AND settings, both `aria-modal="true"`, neither inert — and the
      // popup's own Escape listener still armed underneath, so one press closed the dialog the
      // reader could not see while the one they were in stayed up (M5's measurement of the cog).
      renderApp();
      await openPopup();

      await press(screen.getByRole('button', { name: NUDGE }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(modals()[0]).toHaveAccessibleName('Settings');
      expect(screen.queryByTestId('window-sheet')).toBeNull();
    });

    it('the postcode nudge still lands on the postcode field', async () => {
      // The nudge's whole reason for being a different handler from the cog. Closing the popup
      // first must not cost it: the dialog opens on the field the nudge names.
      renderApp();
      await openPopup();

      await press(screen.getByRole('button', { name: NUDGE }));
      const dialog = await settingsSettled();

      await waitFor(() => expect(within(dialog).getByTestId('settings-postcode-input')).toHaveFocus());
    });

    /**
     * Where the reader lands when settings closes — never `<body>`, and not the nudge.
     *
     * <p>The popup and the settings dialog change places in ONE commit, and the popup's
     * `useDialogFocus` cleanup runs first: the nudge sits outside the settings dialog, which by then
     * claims the modal, so the cleanup counts focus as stranded and restores it to the popup's own
     * trigger. The settings dialog then records THAT as its opener. The cog has done exactly this
     * since M5; pinned so a change to either end is a decision rather than a drift.
     */
    it('closing settings from that route lands on the card the popup was opened from', async () => {
      renderApp();
      await openPopup();
      const card = screen.getAllByTestId('wf-heat-card')[0];

      await press(screen.getByRole('button', { name: NUDGE }));
      await closeSettings(await settingsSettled());

      expect(card).toHaveFocus();
    });

    it('with nothing open behind it, closing settings returns to the nudge itself', async () => {
      // The control for the case above: no dialog of the shell's closes, so nothing moves focus
      // before the settings dialog records its opener, and the opener is the control pressed.
      renderApp();
      await screen.findByTestId('wf-heat-strip');
      const nudge = screen.getByRole('button', { name: NUDGE });

      await press(nudge);
      await closeSettings(await settingsSettled());

      expect(nudge).toHaveFocus();
    });
  });

  // ── The Map tab ────────────────────────────────────────────────────────────

  describe('from the Map tab, with the four-day sheet open over the map', () => {
    it('the ⌂ with nothing over the map: the control case', async () => {
      renderApp();
      await openMapTab();

      await press(screen.getByTestId('stub-home-control'));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(modals()[0]).toHaveAccessibleName('Settings');
    });

    it('⚠️ the ⌂: the sheet goes before settings opens', async () => {
      // The route O-18 made: the sheet opens OVER the map with the tab unmoved, and the map under
      // it is a whole interactive pane — so a keyboard reader Tabs out onto the ⌂ (O-20 arm A) and
      // presses it. Before the fix the sheet stayed up under settings, both claiming the modal, and
      // its Escape listener — armed, since nothing is stacked over it in the shell's own count —
      // took the sheet down behind the settings dialog on the next press.
      renderApp();
      await openMapTab();
      await openPeek();

      await press(screen.getByRole('button', { name: HOME_CONTROL }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(modals()[0]).toHaveAccessibleName('Settings');
      expect(screen.queryByTestId('location-sheet')).toBeNull();
    });

    it('and the tab does not move — the peek\'s own rule, kept', async () => {
      // O-18: the peek leaves the reader on the map so they can back-track to the selection they
      // opened it from. Settings is not a destination either, so taking the sheet down must not
      // take the map with it — the cog, reached from the same sheet, moves no tab.
      renderApp();
      const mapTab = await openMapTab();
      await openPeek();

      await press(screen.getByRole('button', { name: HOME_CONTROL }));
      await settingsSettled();

      expect(mapTab).toHaveAttribute('aria-selected', 'true');
      expect(screen.getByTestId('map-pane-stub')).toBeVisible();
    });

    it('closing settings from that route lands on the control the peek was opened from', async () => {
      // The peek's own back-track target (O-18): the sheet's cleanup restores focus to its trigger
      // in the commit settings opens in, so that is the opener settings records — the same shape as
      // the popup's route on the Plan tab.
      renderApp();
      await openMapTab();
      await openPeek();

      await press(screen.getByRole('button', { name: HOME_CONTROL }));
      await closeSettings(await settingsSettled());

      expect(screen.getByTestId('stub-four-days')).toHaveFocus();
    });
  });
});
