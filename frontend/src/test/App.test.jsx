import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  act, render, screen, waitFor, fireEvent, within,
} from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import * as scoreRamp from '../utils/scoreRamp.js';
import { ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';

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
  saveMapColourPreferences: vi.fn(),
  markComingUpSeen: vi.fn(),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
// The Map pane, stubbed so the date `App` resolves for it can be read. Every other test in this
// file asserts only that the Map TAB exists, which is `WindowFirstShell`'s doing off a non-null
// `mapPane` — so a stub pane leaves them all untouched.
const mapPaneProps = { last: null };
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: (props) => {
    mapPaneProps.last = props;
    return <div data-testid="map-pane-stub" />;
  },
}));
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

import { fetchForecasts, fetchLocations, fetchAllOutcomes } from '../api/forecastApi.js';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import {
  getSettings, getReach, getDriveTimes, lookupPostcode, saveHome, saveMapColourPreferences,
  refreshDriveTimes, markComingUpSeen,
} from '../api/settingsApi.js';
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
  mapPaneProps.last = null;
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
    // stops it being one. App's next re-render — its own settings read resolving is enough — hits
    // the throwing implementation, and the boundary wrapping the PROVIDER (not just the shell,
    // §4.1) is what is expected to catch it.
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
    // The reach fetch's two invalidation signals: without them, a first-run user who saves a home
    // postcode watches every reach line stay absent until a full reload.
    expect(lastProps.homeSettingsVersion).toBe(0);
    expect(lastProps.driveTimesVersion).toBe(0);
  });
});

// ── The reader's settings: one read on mount, then the dialog's answers ──────
//
// `useReaderSettings` reads `GET /api/user/settings` once, on mount, and after that takes the
// settings dialog's own answers — its read on opening, a saved home's response, a recalculation, a
// saved colour — never a read of its own. It hands one record to the Plan provider (the tick line's
// home, the Coming up latch) and to the Map pane (the HOME marker, rings and ⌂), and moves the two
// counters the provider's reach fetch and the masthead's light key on only when an answer changes
// what they count. These drive the real dialog and the real provider — the provider's props read
// through the passthrough spy, the Map pane's through its stub — and count the requests each read
// makes: `getSettings` (App's mount read and the dialog's own), `getReach` (the provider's) and
// `getTodaysLight` (the masthead's). Every answer a negative names is settled inside an awaited
// `act`, and each negative stands beside a control showing the answer did land.

/** A request the test settles by hand, so the test — not the scheduler — decides when it lands. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/** Settles a hand-held request inside an AWAITED act, so everything it causes has committed. */
async function land(settle) {
  await act(async () => { settle(); });
}

/** One reader's settings, before and after moving from Morpeth to Keswick. */
const SETTINGS_MORPETH = {
  role: 'PRO_USER', homePostcode: 'NE61 1AA', homePlaceName: 'Morpeth',
  homeLatitude: 55.17, homeLongitude: -1.69, driveTimesCalculatedAt: '2026-09-01T10:00:00Z',
  mapColourScale: 'temp', comingUpLastSeenDate: '2026-09-10',
};
const SETTINGS_KESWICK = {
  role: 'PRO_USER', homePostcode: 'CA12 5JR', homePlaceName: 'Keswick',
  homeLatitude: 54.6, homeLongitude: -3.13, driveTimesCalculatedAt: null,
  mapColourScale: 'temp', comingUpLastSeenDate: '2026-09-10',
};
const SETTINGS_NO_HOME = {
  role: 'PRO_USER', homePostcode: null, homePlaceName: null, homeLatitude: null,
  homeLongitude: null, driveTimesCalculatedAt: null, mapColourScale: 'temp',
  comingUpLastSeenDate: '2026-09-10',
};
const LOOKUP_MORPETH = {
  postcode: 'NE61 1AA', placeName: 'Morpeth', latitude: 55.17, longitude: -1.69,
};

/** Opens the settings dialog from the masthead cog and waits for its own settings fetch. */
async function openSettings() {
  fireEvent.click(await screen.findByRole('button', { name: 'Settings' }));
  await screen.findByTestId('settings-postcode-input');
  return screen.getByTestId('settings-modal');
}

/** The × in the dialog's header — its only control named "Close". */
function closeSettings(dialog) {
  fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
}

/** The shell mounts a pane on first selection, so the Map tab is opened to read its props. */
async function openMapPane() {
  const tab = await screen.findByRole('tab', { name: 'Map' });
  await act(async () => { fireEvent.click(tab); });
  await screen.findByTestId('map-pane-stub');
}

/** Looks a postcode up in the open dialog and presses Save. */
async function saveHomeIn(dialog, postcode) {
  fireEvent.change(within(dialog).getByTestId('settings-postcode-input'), { target: { value: postcode } });
  fireEvent.click(within(dialog).getByTestId('settings-lookup-btn'));
  fireEvent.click(await within(dialog).findByTestId('settings-save-home-btn'));
}

/** The props the provider was last rendered with. */
const providerProps = (providerSpy) => providerSpy.mock.calls.at(-1)[0];

/** Waits for the reads the page makes on mount: the provider's reach and the masthead's light. */
async function mountReadsSettled() {
  await waitFor(() => expect(getReach).toHaveBeenCalledTimes(1));
  await waitFor(() => expect(getTodaysLight).toHaveBeenCalledTimes(1));
}

describe('App — the reader\'s settings: one read on mount, then the dialog\'s answers', () => {
  beforeEach(() => {
    lookupPostcode.mockReset().mockResolvedValue(LOOKUP_MORPETH);
  });

  it('reads them once and hands one home to both the provider and the Map pane', async () => {
    getSettings.mockResolvedValue(SETTINGS_MORPETH);
    const { providerSpy } = renderApp();
    await openMapPane();

    await waitFor(() => expect(providerProps(providerSpy).homePlace).toBe('Morpeth'));
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 55.17, lon: -1.69 });
    expect(providerProps(providerSpy).comingUpLastSeenDate).toBe('2026-09-10');
    // One read. The provider used to make a second, of the same endpoint, for the tick line — and
    // either failing alone split the tick line's home from the map's.
    expect(getSettings).toHaveBeenCalledTimes(1);
  });

  it('names the home by its postcode when no place name resolved', async () => {
    // The design's slot reads `Home · <place>`; a failed geocode must not blank it.
    getSettings.mockResolvedValue({ ...SETTINGS_MORPETH, homePlaceName: null });
    const { providerSpy } = renderApp();

    await waitFor(() => expect(providerProps(providerSpy).homePlace).toBe('NE61 1AA'));
  });

  it('hands an unknown home down as unknown until the read answers — never as "no postcode"', async () => {
    const mountRead = deferred();
    getSettings.mockReturnValue(mountRead.promise);
    const { providerSpy } = renderApp();
    await openMapPane();

    // `undefined`, not null: the ⌂ answers null with "Set your home postcode", which an unanswered
    // read is no evidence for.
    expect(mapPaneProps.last.homeCoords).toBeUndefined();
    expect(providerProps(providerSpy).homePlace).toBeUndefined();

    await land(() => mountRead.resolve(SETTINGS_NO_HOME));

    expect(mapPaneProps.last.homeCoords).toBeNull();
    expect(providerProps(providerSpy).homePlace).toBeNull();
  });

  it('takes a saved postcode from the save\'s own response, named from the lookup — and reads nothing after it', async () => {
    getSettings.mockResolvedValue(SETTINGS_NO_HOME);
    const save = deferred();
    saveHome.mockReset().mockReturnValue(save.promise);
    const { providerSpy } = renderApp();
    await openMapPane();
    await mountReadsSettled();

    const dialog = await openSettings();
    await saveHomeIn(dialog, 'NE61 1AA');
    const readsBeforeTheSave = getSettings.mock.calls.length; // App's, and the dialog's own
    // Nothing moves on the press: the page follows the save's answer, not the click.
    expect(providerProps(providerSpy).homeSettingsVersion).toBe(0);

    // A save does not geocode: its response names no place.
    await land(() => save.resolve({
      ...SETTINGS_MORPETH, homePlaceName: null, driveTimesCalculatedAt: null,
    }));

    expect(providerProps(providerSpy).homePlace).toBe('Morpeth');
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 55.17, lon: -1.69 });
    // A real change: the home counter moved once, and reach and the light were asked again ...
    expect(providerProps(providerSpy).homeSettingsVersion).toBe(1);
    expect(getReach).toHaveBeenCalledTimes(2);
    expect(getTodaysLight).toHaveBeenCalledTimes(2);
    // ... but the settings were not: the save's own response is the answer, so there is no
    // follow-up read to fail and empty the home — or unmount the ⌂ that had opened the dialog.
    expect(getSettings).toHaveBeenCalledTimes(readsBeforeTheSave);
  });

  it('moves nothing when the same postcode is saved again', async () => {
    getSettings.mockResolvedValue(SETTINGS_MORPETH);
    const save = deferred();
    saveHome.mockReset().mockReturnValue(save.promise);
    const { providerSpy } = renderApp();
    await screen.findByTestId('window-first-pane-empty');
    await mountReadsSettled();

    const dialog = await openSettings();
    await saveHomeIn(dialog, 'NE61 1AA');
    await land(() => save.resolve({ ...SETTINGS_MORPETH, homePlaceName: null }));

    // Control: the save landed — the dialog shows the saved home again, not the lookup.
    expect(within(dialog).getByTestId('settings-home-current')).toHaveTextContent('Morpeth');
    // Broken, a re-save moved the counter: reach and the light were asked again for nothing, and
    // a correct answer still out would have been dropped.
    expect(providerProps(providerSpy).homeSettingsVersion).toBe(0);
    expect(getReach).toHaveBeenCalledTimes(1);
    expect(getTodaysLight).toHaveBeenCalledTimes(1);
  });

  it('asks for reach again after a recalculation, and not for the light', async () => {
    getSettings.mockResolvedValue({ ...SETTINGS_MORPETH, driveTimesCalculatedAt: null });
    const recalc = deferred();
    refreshDriveTimes.mockReset().mockReturnValue(recalc.promise);
    const { providerSpy } = renderApp();
    await screen.findByTestId('window-first-pane-empty');
    await mountReadsSettled();

    const dialog = await openSettings();
    fireEvent.click(within(dialog).getByTestId('settings-refresh-drive-btn'));
    await land(() => recalc.resolve({ locationsUpdated: 12, calculatedAt: '2026-09-15T10:00:00Z' }));

    expect(screen.getByText(/12 locations updated/)).toBeInTheDocument(); // control: it landed
    expect(providerProps(providerSpy).driveTimesVersion).toBe(1);
    expect(getReach).toHaveBeenCalledTimes(2);
    // The light cannot change with a recalculation, so it was not asked again.
    expect(providerProps(providerSpy).homeSettingsVersion).toBe(0);
    expect(getTodaysLight).toHaveBeenCalledTimes(1);
  });

  it('picks up a home changed elsewhere when the dialog opens, and asks for reach and the light again', async () => {
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH) // App's mount read
      .mockResolvedValueOnce(SETTINGS_KESWICK); // the dialog's own: moved on another device
    const { providerSpy } = renderApp();
    await openMapPane();
    await mountReadsSettled();
    expect(providerProps(providerSpy).homePlace).toBe('Morpeth');

    await openSettings();

    expect(providerProps(providerSpy).homePlace).toBe('Keswick');
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 54.6, lon: -3.13 });
    await waitFor(() => expect(getReach).toHaveBeenCalledTimes(2));
    expect(getTodaysLight).toHaveBeenCalledTimes(2);
  });

  it('moves nothing when the dialog opens on the same settings, or closes', async () => {
    getSettings.mockResolvedValue(SETTINGS_MORPETH);
    const { providerSpy } = renderApp();
    await screen.findByTestId('window-first-pane-empty');
    await mountReadsSettled();

    const dialog = await openSettings();
    // Control: the dialog's own read landed.
    expect(within(dialog).getByTestId('settings-home-current')).toHaveTextContent('Morpeth');
    closeSettings(dialog);
    await waitFor(() => expect(screen.queryByTestId('settings-modal')).toBeNull());
    await act(async () => {});

    expect(providerProps(providerSpy).homeSettingsVersion).toBe(0);
    expect(providerProps(providerSpy).driveTimesVersion).toBe(0);
    expect(getReach).toHaveBeenCalledTimes(1);
    expect(getTodaysLight).toHaveBeenCalledTimes(1);
    // The close reads nothing: App's mount read and the dialog's own, and no more.
    expect(getSettings).toHaveBeenCalledTimes(2);
  });

  it('takes a saved colour from the save\'s own response, and reads nothing after it', async () => {
    // Never chosen: the ramp's one-time "colours changed" notice is owed to this reader.
    getSettings.mockResolvedValue({ ...SETTINGS_MORPETH, mapColourScale: null });
    const colourSave = deferred();
    saveMapColourPreferences.mockReset().mockReturnValue(colourSave.promise);
    const setModeSpy = vi.spyOn(scoreRamp, 'setMode');
    const { providerSpy } = renderApp();
    await openMapPane();
    await mountReadsSettled();
    expect(mapPaneProps.last.colourScaleDefaulted).toBe(true);

    const dialog = await openSettings();
    const readsBeforeTheSave = getSettings.mock.calls.length;
    fireEvent.click(within(dialog).getByTestId('settings-map-colour-verdict'));
    // Nothing reaches the ramp on the click: it follows the saved preference, not the radio.
    expect(setModeSpy).not.toHaveBeenCalledWith('verdict');

    await land(() => colourSave.resolve({ ...SETTINGS_MORPETH, mapColourScale: 'verdict' }));

    expect(setModeSpy).toHaveBeenLastCalledWith('verdict');
    expect(mapPaneProps.last.mapColourScale).toBe('verdict');
    expect(mapPaneProps.last.colourScaleDefaulted).toBe(false);
    // No read after the save, so nothing that could fail and leave the ramp on the old scale ...
    expect(getSettings).toHaveBeenCalledTimes(readsBeforeTheSave);
    // ... and nothing asked of the home.
    expect(providerProps(providerSpy).homeSettingsVersion).toBe(0);
    expect(getReach).toHaveBeenCalledTimes(1);
  });

  it('lets the mount read land after the dialog has answered without changing anything', async () => {
    const mountRead = deferred();
    getSettings
      .mockReturnValueOnce(mountRead.promise) // App's mount read, slow
      .mockResolvedValueOnce({ // the dialog's own, made later
        ...SETTINGS_KESWICK, mapColourScale: 'verdict', comingUpLastSeenDate: '2026-09-12',
      });
    const setModeSpy = vi.spyOn(scoreRamp, 'setMode');
    const { providerSpy } = renderApp();
    await openMapPane();

    await openSettings();
    // Control: the dialog's answer is on the page.
    expect(providerProps(providerSpy).homePlace).toBe('Keswick');

    await land(() => mountRead.resolve(SETTINGS_MORPETH)); // older: made before the dialog opened

    // Broken, the older read put Morpeth back on the tick line and the map, the old ramp back on
    // every surface, and an older last-seen date back on the badge.
    expect(providerProps(providerSpy).homePlace).toBe('Keswick');
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 54.6, lon: -3.13 });
    expect(setModeSpy).toHaveBeenLastCalledWith('verdict');
    expect(providerProps(providerSpy).comingUpLastSeenDate).toBe('2026-09-12');
  });

  it('leaves the home unknown after a failed read, and opening the dialog retries what keys on it', async () => {
    getSettings
      .mockRejectedValueOnce(new Error('502 from /api/user/settings'))
      .mockResolvedValueOnce(SETTINGS_MORPETH);
    const { providerSpy } = renderApp();
    await openMapPane();
    await mountReadsSettled();
    await act(async () => {});

    // Not known — and not "no postcode": nothing on the page may tell this reader to set one.
    expect(providerProps(providerSpy).homePlace).toBeUndefined();
    expect(mapPaneProps.last.homeCoords).toBeUndefined();

    await openSettings();

    expect(providerProps(providerSpy).homePlace).toBe('Morpeth');
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 55.17, lon: -1.69 });
    // An answer while nothing was on record counts as a change: reach and the light are asked
    // again, in case the page-load failure was theirs too.
    await waitFor(() => expect(getReach).toHaveBeenCalledTimes(2));
    expect(getTodaysLight).toHaveBeenCalledTimes(2);
  });

  it('fills an unknown last-seen date from the dialog\'s read', async () => {
    getSettings
      .mockRejectedValueOnce(new Error('502 from /api/user/settings'))
      .mockResolvedValueOnce({ ...SETTINGS_MORPETH, comingUpLastSeenDate: '2026-09-12' });
    const { providerSpy } = renderApp();
    await screen.findByTestId('window-first-pane-empty');
    await act(async () => {});
    expect(providerProps(providerSpy).comingUpLastSeenDate).toBeUndefined();

    await openSettings();

    expect(providerProps(providerSpy).comingUpLastSeenDate).toBe('2026-09-12');
  });

  it('never moves a known last-seen date from the dialog\'s read', async () => {
    // The Coming up tab's own writes own the date once it is known; a read made before a `Mark
    // seen` landed must not put the older day back and bring the badge back with it.
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH) // '2026-09-10'
      .mockResolvedValueOnce({ ...SETTINGS_MORPETH, comingUpLastSeenDate: '2026-09-01' });
    const { providerSpy } = renderApp();
    await waitFor(() => expect(providerProps(providerSpy).comingUpLastSeenDate).toBe('2026-09-10'));

    const dialog = await openSettings();
    // Control: the dialog's read landed.
    expect(within(dialog).getByTestId('settings-home-current')).toHaveTextContent('Morpeth');

    expect(providerProps(providerSpy).comingUpLastSeenDate).toBe('2026-09-10');
  });

  it('hands the Coming up tab\'s own write back to the record', async () => {
    // The bootstrap write on a first visit: `null` is "never seen", and the write's echo is the
    // date the provider must carry from then on.
    getSettings.mockResolvedValue({ ...SETTINGS_MORPETH, comingUpLastSeenDate: null });
    markComingUpSeen.mockReset().mockResolvedValue({ comingUpLastSeenDate: '2026-09-15' });
    const { providerSpy } = renderApp();
    await waitFor(() => expect(providerProps(providerSpy).comingUpLastSeenDate).toBeNull());

    await act(async () => { fireEvent.click(await screen.findByRole('tab', { name: 'Coming up' })); });

    await waitFor(() => expect(providerProps(providerSpy).comingUpLastSeenDate).toBe('2026-09-15'));
    expect(markComingUpSeen).toHaveBeenCalledTimes(1);
  });

  it('drops the first of a StrictMode remount\'s two mount reads', async () => {
    // A development-only double mount: the first run's cleanup must stop its read writing.
    const first = deferred();
    const second = deferred();
    getSettings.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    localStorage.setItem('goldenhour_token', 'test-token');
    localStorage.setItem('goldenhour_role', 'PRO_USER');
    const providerSpy = vi.spyOn(briefingContext, 'WindowFirstBriefingProvider');
    render(<React.StrictMode><App /></React.StrictMode>);
    await screen.findByTestId('window-first-pane-empty');
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => second.resolve(SETTINGS_KESWICK));
    // Control: the second run's read applies.
    expect(providerProps(providerSpy).homePlace).toBe('Keswick');

    await land(() => first.resolve(SETTINGS_MORPETH));

    expect(providerProps(providerSpy).homePlace).toBe('Keswick');
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

  /**
   * Which DATE the Map pane is handed — `resolveMapDate`'s wiring, as opposed to its rule.
   *
   * <p>The rule has its own unit tests in `mapDates.test.js`. What only an App-level test can pin
   * is that the three inputs actually reach it, and one of them is easy to drop: the night in
   * progress. ⚠️ A night runs dusk-to-dawn, so between UK midnight and dawn it is YESTERDAY's date
   * — the one "past" date `App` sets deliberately (`handleAuroraViewOnMap`, so the aurora viewline
   * lands on the night the banner is about). The never-past clamp refused it in its first cut and
   * silently undid that fix; nothing in the suite noticed, because the overlay reads its own
   * `mapOverlay.date` and only the full Map tab falls through to `effectiveDate`.
   */
  describe('the date handed to the Map pane', () => {
    const YESTERDAY = ukDateStrOffset(-1);

    /** Rows on YESTERDAY as well, so the night is inside the forecast domain. */
    const pastAndFutureForecasts = () => [
      ...FORECASTS,
      ...LOCATION_META.flatMap((m) => ['SUNRISE', 'SUNSET'].map((t) => ({
        ...forecastRow(m, t), targetDate: YESTERDAY,
      }))),
    ];

    /** The shell mounts a pane on first selection, so the tab has to be opened to read its props. */
    const openMapTab = async () => {
      const tab = await screen.findByRole('tab', { name: 'Map' });
      await act(async () => { fireEvent.click(tab); });
      return screen.findByTestId('map-pane-stub');
    };

    it('defaults to a today-forward date, never a past one', async () => {
      fetchForecasts.mockResolvedValue(pastAndFutureForecasts());
      renderApp();
      await openMapTab();

      expect(mapPaneProps.last.dates).toContain(YESTERDAY);
      expect(mapPaneProps.last.selectedDate).not.toBe(YESTERDAY);
      expect(mapPaneProps.last.selectedDate >= ukDateStr()).toBe(true);
    });

    it('honours the NIGHT in progress when the aurora banner asks for it', async () => {
      // A live alert whose night is yesterday — the small-hours case. The banner's "view on map"
      // sets it as the selected date; `App` must hand that through rather than clamp it away.
      getAuroraStatus.mockResolvedValue({
        level: 'MODERATE', kpIndex: 6, currentNightDate: YESTERDAY, simulated: false,
      });
      fetchForecasts.mockResolvedValue(pastAndFutureForecasts());
      renderApp();
      await openMapTab();
      expect(mapPaneProps.last.selectedDate).not.toBe(YESTERDAY);

      // The banner's whole body is the activation surface (a click handler + `tabIndex`, not a
      // nested button), so the click goes on the banner element itself.
      const banner = await screen.findByTestId('aurora-banner');
      await act(async () => { fireEvent.click(banner); });

      expect(mapPaneProps.last.selectedDate).toBe(YESTERDAY);
    });

    it('does NOT let a stale solar pick borrow the night licence (Codex, #803)', async () => {
      // ⚠️ The overnight case. The aurora status names yesterday as the night in progress — true
      // before dawn — and the reader's own selection is yesterday too, but made from the MAP as an
      // ordinary solar date. Keying the exemption on the value alone exempted it, holding the tab
      // on a day that was over until the backend advanced `currentNightDate`; with the solar-row
      // gate live that is a persistent "No forecast" blank, i.e. this clamp defeated by its own
      // exemption. Provenance is what tells the two selections apart.
      getAuroraStatus.mockResolvedValue({
        level: 'MODERATE', kpIndex: 6, currentNightDate: YESTERDAY, simulated: false,
      });
      fetchForecasts.mockResolvedValue(pastAndFutureForecasts());
      renderApp();
      await openMapTab();

      // The pane's own `onSelectDate` — the solar route, which carries no night provenance.
      await act(async () => { mapPaneProps.last.onSelectDate(YESTERDAY); });

      expect(mapPaneProps.last.selectedDate).not.toBe(YESTERDAY);
      expect(mapPaneProps.last.selectedDate >= ukDateStr()).toBe(true);
    });

    it('clears the night licence on the NEXT selection — it is per-pick, not sticky', async () => {
      // ⚠️ The same defect one step later in the sequence, and mutation testing is what surfaced
      // it: a flag that is only ever set (never cleared) lets an ordinary solar pick made AFTER
      // the banner borrow the licence the banner earned. The pair is written through one setter
      // precisely so the flag cannot outlive the date it describes.
      getAuroraStatus.mockResolvedValue({
        level: 'MODERATE', kpIndex: 6, currentNightDate: YESTERDAY, simulated: false,
      });
      fetchForecasts.mockResolvedValue(pastAndFutureForecasts());
      renderApp();
      await openMapTab();

      // 1. The banner's night selection is honoured.
      const banner = await screen.findByTestId('aurora-banner');
      await act(async () => { fireEvent.click(banner); });
      expect(mapPaneProps.last.selectedDate).toBe(YESTERDAY);

      // 2. A subsequent SOLAR pick of the same date must not inherit it.
      await act(async () => { mapPaneProps.last.onSelectDate(YESTERDAY); });
      expect(mapPaneProps.last.selectedDate).not.toBe(YESTERDAY);
    });

    it('accepts a night the pane asks for with provenance, whatever asked for it', async () => {
      // ⚠️ End-to-end for the pane's OTHER `onSelectDate` call site — the aurora auto-jump, which
      // lands on the night in progress when the current date has no results. `MapView`'s own test
      // pins that it passes `{ isNight: true }`; this pins that doing so is enough to make the jump
      // actually land, rather than being refused as a past solar date and silently latched off.
      // Driven through the prop rather than the effect, so it covers any caller that supplies
      // provenance — the assertion is about `App`'s side of the contract.
      getAuroraStatus.mockResolvedValue({
        level: 'MODERATE', kpIndex: 6, currentNightDate: YESTERDAY, simulated: false,
      });
      fetchForecasts.mockResolvedValue(pastAndFutureForecasts());
      renderApp();
      await openMapTab();
      expect(mapPaneProps.last.selectedDate).not.toBe(YESTERDAY);

      await act(async () => { mapPaneProps.last.onSelectDate(YESTERDAY, { isNight: true }); });
      expect(mapPaneProps.last.selectedDate).toBe(YESTERDAY);
    });
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
// `useReaderSettings` is the one place App wires a settings answer into scoreRamp.setMode, so Plan
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
