import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  act, render, screen, fireEvent, within, waitFor,
} from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStrOffset } from '../utils/mapDates.js';

/**
 * Every route into the settings dialog takes down the dialog it would otherwise open over.
 *
 * <p>`UserSettingsModal` is a SIBLING of the Plan shell in `App`, so it is outside every mechanism
 * the shell has for ordering its own layers: it is not a `Modal` the shell renders, the shell's
 * `stackedOverPopup` cannot see it, and it takes no `stacked` opt-in. It cannot be ordered against a
 * shell dialog — it can only be arrived at with none of them open. "At most one dialog may claim to
 * be the modal" is held route by route (v1-retirement §4.3), and M5 closed the cog's. This file
 * holds the other two routes into the same dialog, and `App`'s own map overlay, through the real
 * `App`, so the count is taken against the real settings dialog rather than against a handler that
 * was merely called:
 *
 * <ul>
 *   <li>the masthead tick line's "set a postcode" nudge, which `App` wires straight to its own
 *       handler — reachable by Tab from an open window popup, because the popup is not a trap and
 *       the tick line keeps its tab stops while only the popup is up;</li>
 *   <li>the Map tab's ⌂ in its no-postcode state, which reaches `App` through the map pane rather
 *       than through the shell at all — reachable by Tab from the four-day sheet the callout's
 *       `Four days here ›` opens OVER the map (map-tab-v2-plan.md O-18; the Tab-out itself is O-20
 *       arm A).</li>
 *   <li>`App`'s map overlay, which the shell cannot close at all: it is `App` state, `aria-modal`,
 *       no trap, and painted over settings (`zIndex: 200` against `Modal`'s `z-50`).</li>
 * </ul>
 *
 * <p>⚠️ <b>Not "the only modal on the page", and the file does not claim it.</b> An Operations-tab
 * admin `Modal` is left open under settings on purpose — it can hold data a close would lose — and
 * a dialog opened behind settings after it opened (the reverse route) is not answered by any of
 * this. Both are named in the changelog entry.
 *
 * <p>⚠️ <b>In `App`, the cog's and the nudge's routes are covered twice</b> — by the shell's own
 * close on the press and by the `settingsOpen` edge — so no test here can tell which one held.
 * `WindowFirstShellSheet.test.jsx` renders the shell without `App` and pins each. The ⌂'s route has
 * only the edge, which is why its second opening is tested here.
 *
 * <p>The count is filtered off the dialog ROLE rather than a raw `[aria-modal]` selector, as
 * `WindowFirstShellSheet.test.jsx`'s own count is: it fails naming which dialogs are on screen.
 *
 * <p>Mocked at the API-module boundary as `App.test.jsx` is; the shell's context is stubbed the way
 * every shell suite stubs it (the derivations have their own files), so the matrix has a window to
 * open. The Map pane is a stub carrying the two controls this file needs from it — the real ⌂'s
 * own rule (it calls `onOpenSettings` only while there is no home and no origin) is
 * `MapViewCentreOnHome.test.jsx`'s to pin; what is App's, and this file's, is where that call goes.
 * The map inside the overlay is stubbed for `App.test.jsx`'s reasons, given beside the mock.
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
// The map inside the overlay the aurora banner opens, stubbed as `App.test.jsx` stubs it and for
// the same reasons: no assertion here reads that map, `MapView`'s own suites mount it, and kept real
// it would put Leaflet's first load inside `openOverlay`'s wait. See "Do not open a lazy subtree" in
// `docs/engineering/frontend-test-standards.md`.
vi.mock('../components/MapView.jsx', () => ({
  default: () => <div data-testid="overlay-map-stub" />,
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

/** A live alert the banner shows, so it can open the map overlay. */
const AURORA_ALERT = {
  level: 'MODERATE', kpIndex: 6, currentNightDate: TOMORROW, simulated: false,
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

/**
 * Presses the aurora banner and waits for the overlay it opens AND the map inside it — the wait
 * `App.test.jsx`'s `pressAuroraBanner` keeps, for the reason recorded there: both are behind
 * `React.lazy`, and a test that left before they mounted would run whatever earlier tests loaded.
 */
async function openOverlay() {
  await press(await screen.findByTestId('aurora-banner'));
  const overlay = await screen.findByRole('dialog', { name: 'Aurora tonight' });
  await within(overlay).findByTestId('overlay-map-stub');
  return overlay;
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

// ── The routes ───────────────────────────────────────────────────────────────

describe('App — every route into settings takes down the dialog it would open over', () => {
  describe('the masthead cog and postcode nudge, on the Plan tab', () => {
    it('the cog over a window popup — the control case: the harness reads the real settings dialog as the one modal', async () => {
      // Proves the harness first: the real settings dialog counts as a modal here, and the route M5
      // closed leaves it alone. Every negative below rests on this reading right.
      renderApp();
      await openPopup();

      await press(screen.getByRole('button', { name: 'Settings' }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(modals()[0]).toHaveAccessibleName('Settings');
      expect(screen.queryByTestId('window-sheet')).toBeNull();
    });

    it('⚠️ the nudge over a window popup: the popup is gone in the render settings opens in', async () => {
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

    it('the nudge over a window popup still opens settings on the postcode field', async () => {
      // The nudge's whole reason for being a different handler from the cog. Closing the popup
      // first must not cost it: the dialog opens on the field the nudge names.
      renderApp();
      await openPopup();

      await press(screen.getByRole('button', { name: NUDGE }));
      const dialog = await settingsSettled();

      await waitFor(() => expect(within(dialog).getByRole('textbox', { name: POSTCODE_FIELD })).toHaveFocus());
    });

    /**
     * Where the reader lands when settings closes: the covered dialog's own recorded opener, not the
     * control pressed.
     *
     * <p>The popup and the settings dialog change places in ONE commit, and the popup's
     * `useDialogFocus` cleanup runs first: the pressed control sits outside the settings dialog,
     * which by then claims the modal, so the cleanup counts focus as stranded and restores it to the
     * popup's opener. The settings dialog then records THAT as its own. For a popup opened from its
     * matrix card, that is the card. Pinned for both masthead routes, so a change at either end is a
     * decision rather than a drift.
     *
     * <p>⚠️ Two limits, stated rather than implied. If that opener can no longer take focus by the
     * time settings closes — a window that passed while settings stood — the restore falls to the
     * route's `restoreFocusFallback`: the nudge's names its origin slot, and the cog passes none, so
     * there focus stays where the browser put it (both pinned below). And these are jsdom pins of the ORDER:
     * jsdom focuses a node a browser refuses (inside a `hidden` panel, or under `inert`), so they
     * cannot speak for a landing that depends on the opener still being focusable.
     */
    it('the nudge over a window popup: closing settings lands on the card the popup was opened from', async () => {
      renderApp();
      await openPopup();
      const card = screen.getAllByTestId('wf-heat-card')[0];

      await press(screen.getByRole('button', { name: NUDGE }));
      await closeSettings(await settingsSettled());

      expect(card).toHaveFocus();
    });

    it('the cog over a window popup lands on the same card — the landing it has had since M5', async () => {
      renderApp();
      await openPopup();
      const card = screen.getAllByTestId('wf-heat-card')[0];

      await press(screen.getByRole('button', { name: 'Settings' }));
      await closeSettings(await settingsSettled());

      expect(card).toHaveFocus();
    });

    it('⚠️ the nudge over a window popup: with that card unable to take focus by the close, it lands on the nudge', async () => {
      // A window that passes while settings stands takes its card with it. `disabled` stands in for
      // the removal — `useDialogFocus` treats an opener that refuses focus as one that has gone —
      // because removing a node React still owns would break its next render of the strip.
      renderApp();
      await openPopup();
      const card = screen.getAllByTestId('wf-heat-card')[0];
      const nudge = screen.getByRole('button', { name: NUDGE });

      await press(nudge);
      const dialog = await settingsSettled();
      act(() => { card.disabled = true; });
      await closeSettings(dialog);

      expect(nudge, 'the nudge\'s fallback: the origin slot it stands in').toHaveFocus();
    });

    it('the cog over a window popup, likewise: it passes no fallback, so focus is left where the browser put it', async () => {
      // The control for the case above: the same lost card, a route with no successor to name.
      renderApp();
      await openPopup();
      const card = screen.getAllByTestId('wf-heat-card')[0];

      await press(screen.getByRole('button', { name: 'Settings' }));
      const dialog = await settingsSettled();
      act(() => { card.disabled = true; });
      await closeSettings(dialog);

      expect(document.activeElement).toBe(document.body);
    });

    it('the nudge with nothing open: closing settings returns to the nudge itself', async () => {
      // The control for the two cases above: no dialog of the shell's closes, so nothing moves focus
      // before the settings dialog records its opener, and the opener is the control pressed.
      renderApp();
      await screen.findByTestId('wf-heat-strip');
      const nudge = screen.getByRole('button', { name: NUDGE });

      await press(nudge);
      await closeSettings(await settingsSettled());

      expect(nudge).toHaveFocus();
    });
  });

  describe('the map ⌂, on the Map tab', () => {
    it('with nothing over the map — the control case', async () => {
      renderApp();
      await openMapTab();

      await press(screen.getByRole('button', { name: HOME_CONTROL }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(modals()[0]).toHaveAccessibleName('Settings');
    });

    it('opens settings on the postcode field — the one control the ⌂ exists to land a reader on', async () => {
      // The nudge's landing is pinned above; the ⌂ reaches `App` by its own handler, through the
      // map pane, and nothing pinned where that one lands.
      renderApp();
      await openMapTab();

      await press(screen.getByRole('button', { name: HOME_CONTROL }));
      const dialog = await settingsSettled();

      await waitFor(() => expect(within(dialog).getByRole('textbox', { name: POSTCODE_FIELD })).toHaveFocus());
    });

    it('⚠️ over the four-day sheet: the sheet is gone in the render settings opens in', async () => {
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

    it('over the four-day sheet: the tab does not move — the peek\'s own rule, kept', async () => {
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

    it('over the four-day sheet: closing settings lands on the control the peek was opened from', async () => {
      // The sheet's own recorded opener, where its cleanup restores focus in the commit settings
      // opens in — the same shape as the popup's route on the Plan tab, and the limits recorded
      // there apply. Here the opener is the callout's `Four days here ›` stand-in; a peek opened
      // from the region panel records the window pill instead (`MapView`'s `handleOpenLocationSheet`
      // focuses it before the handoff), and would land there.
      renderApp();
      await openMapTab();
      await openPeek();

      await press(screen.getByRole('button', { name: HOME_CONTROL }));
      await closeSettings(await settingsSettled());

      expect(screen.getByTestId('stub-four-days')).toHaveFocus();
    });

    it('the ⚙ cog over the four-day sheet takes it down too, and moves no tab either', async () => {
      // The masthead is on every tab, and the cog is not taken out of the tab order under a sheet,
      // so it is the Map tab's other route into settings over the peek. It closes through the same
      // `selectTab` naming the tab in force — the peek's own rule (O-18) is that nothing here moves
      // the reader off the map.
      renderApp();
      const mapTab = await openMapTab();
      await openPeek();

      await press(screen.getByRole('button', { name: 'Settings' }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(screen.queryByTestId('location-sheet')).toBeNull();
      expect(mapTab).toHaveAttribute('aria-selected', 'true');
    });

    it('the postcode nudge on the Map tab opens settings where the reader is, moving no tab', async () => {
      // The nudge renders on every tab while no home is saved. Its close names the tab in force, so
      // pressing it from the map must not carry the reader back to the Plan.
      renderApp();
      const mapTab = await openMapTab();

      await press(screen.getByRole('button', { name: NUDGE }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(mapTab).toHaveAttribute('aria-selected', 'true');
    });

    it('⚠️ over the four-day sheet a SECOND time: the close re-arms when settings closes', async () => {
      // The ⌂'s route has nothing but the edge, and every other test opens settings once — so an
      // edge that latched on the first opening and never reset passed them all. The route back to
      // this state is the one the test above lands the reader on: closing settings returns them to
      // `Four days here ›`, one press from the peek, and the ⌂ is where they just were.
      renderApp();
      await openMapTab();
      await openPeek();
      await press(screen.getByRole('button', { name: HOME_CONTROL }));
      await closeSettings(await settingsSettled());

      await openPeek();
      await press(screen.getByRole('button', { name: HOME_CONTROL }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(modals()[0]).toHaveAccessibleName('Settings');
      expect(screen.queryByTestId('location-sheet')).toBeNull();
    });
  });

  describe('App\'s own map overlay', () => {
    beforeEach(() => {
      getAuroraStatus.mockResolvedValue(AURORA_ALERT);
    });

    it('⚠️ the cog over the map overlay: the overlay is gone in the render settings opens in, not painted over it', async () => {
      // The overlay is `aria-modal`, no trap, and `zIndex: 200` against settings' `z-50`: before the
      // fix a reader who Tabbed out of it onto ⚙ got settings UNDER the overlay, holding focus in a
      // dialog they could not see, and the overlay's Escape listener still armed. The shell cannot
      // close it — it is `App` state — so `App` does, on the same edge.
      renderApp();
      await screen.findByTestId('wf-heat-strip');
      await openOverlay();

      await press(screen.getByRole('button', { name: 'Settings' }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(modals()[0]).toHaveAccessibleName('Settings');
      expect(screen.queryByRole('dialog', { name: 'Aurora tonight' })).toBeNull();
    });

    it('⚠️ over the map overlay a SECOND time: `App`\'s close re-arms when settings closes', async () => {
      // `App`'s edge, like the shell's, has to reset when settings closes, and a test that opens
      // settings once cannot see it latch. Closing settings returns the reader to the banner, one
      // press from the overlay again.
      renderApp();
      await screen.findByTestId('wf-heat-strip');
      await openOverlay();
      await press(screen.getByRole('button', { name: 'Settings' }));
      await closeSettings(await settingsSettled());

      await openOverlay();
      await press(screen.getByRole('button', { name: 'Settings' }));
      await settingsSettled();

      expect(modals()).toHaveLength(1);
      expect(modals()[0]).toHaveAccessibleName('Settings');
      expect(screen.queryByRole('dialog', { name: 'Aurora tonight' })).toBeNull();
    });

    it('the cog over the map overlay: closing settings lands on the control the overlay was opened from', async () => {
      // The overlay's own recorded opener — the banner — by the same one-commit swap the Plan's
      // dialogs make, and with the same limits.
      renderApp();
      await screen.findByTestId('wf-heat-strip');
      await openOverlay();

      await press(screen.getByRole('button', { name: 'Settings' }));
      await closeSettings(await settingsSettled());

      expect(screen.getByTestId('aurora-banner')).toHaveFocus();
    });
  });
});
