import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  act, render, screen, waitFor, fireEvent, within,
} from '@testing-library/react';
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
 * <p>Everything is mocked at the API-module boundary, with the exceptions named here; auth is
 * seeded through localStorage so the real AuthProvider runs. The provider's `locations` prop has no
 * rendered consumer yet — the heat field's surfaces arrive at P2/P4 — so it is observed with a
 * PASSTHROUGH spy on the provider export (the real provider still runs; the spy stubs nothing).
 * When a P2 surface renders from `heatPointSets`, that assertion can move onto the DOM. Both maps
 * App mounts are stubbed — the Map tab's pane and the overlay's `MapView`, each for the reason
 * beside its mock — and `useAuroraViewline` is mocked to answer no line, so the aurora banner makes
 * no viewline read.
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
// The map inside the overlay the aurora banner opens, stubbed too — but unlike the pane above it
// records nothing: no assertion in this file reads that map, and `MapView`'s own suites mount it,
// with Leaflet stubbed. Kept real, it would have this file mock the map's own reads and fixture its
// aurora-mode rules, and it would put Leaflet's first load inside `pressAuroraBanner`'s wait, which
// then took 2.8–3.8 s against its 4 s ceiling under the suite's load reproduction. See "Do not open
// a lazy subtree" in `docs/engineering/frontend-test-standards.md`.
vi.mock('../components/MapView.jsx', () => ({
  default: () => <div data-testid="overlay-map-stub" />,
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
const LOOKUP_KESWICK = {
  postcode: 'CA12 5JR', placeName: 'Keswick', latitude: 54.6, longitude: -3.13,
};
/** What `PUT /api/user/settings/home` answers for the move to Keswick: no place name — it does not geocode. */
const SAVED_KESWICK = { ...SETTINGS_KESWICK, homePlaceName: null };

/** Opens the settings dialog from the masthead cog and waits for its own settings fetch. */
async function openSettings() {
  fireEvent.click(await screen.findByRole('button', { name: 'Settings' }));
  await screen.findByTestId('settings-postcode-input');
  return screen.getByTestId('settings-modal');
}

/** Opens the settings dialog and returns it while its own read is still out — its form not yet drawn. */
async function openSettingsStillLoading() {
  fireEvent.click(await screen.findByRole('button', { name: 'Settings' }));
  const dialog = await screen.findByTestId('settings-modal');
  expect(within(dialog).queryByTestId('settings-postcode-input')).toBeNull();
  return dialog;
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
    await openMapPane();
    await mountReadsSettled();
    const homeBefore = mapPaneProps.last.homeCoords;

    const dialog = await openSettings();
    fireEvent.click(within(dialog).getByTestId('settings-refresh-drive-btn'));
    await land(() => recalc.resolve({ locationsUpdated: 12, calculatedAt: '2026-09-15T10:00:00Z' }));

    expect(screen.getByText(/12 locations updated/)).toBeInTheDocument(); // control: it landed
    expect(providerProps(providerSpy).driveTimesVersion).toBe(1);
    expect(getReach).toHaveBeenCalledTimes(2);
    // The light cannot change with a recalculation, so it was not asked again.
    expect(providerProps(providerSpy).homeSettingsVersion).toBe(0);
    expect(getTodaysLight).toHaveBeenCalledTimes(1);
    // The same object, not an equal one: the map is `React.memo`'d and its label and pin layers
    // repaint on this object's identity.
    expect(mapPaneProps.last.homeCoords).toBe(homeBefore);
  });

  it('does not take a recalculation for a move — a save that lands under its spinner stands', async () => {
    // A recalculation changes the drive times and nothing else. Reported as the dialog's copy of
    // the home, it put back a home the reader had just moved away from, when a postcode save was
    // still out as they pressed Refresh.
    getSettings.mockResolvedValue({ ...SETTINGS_MORPETH, driveTimesCalculatedAt: null });
    lookupPostcode.mockResolvedValue(LOOKUP_KESWICK);
    const save = deferred();
    saveHome.mockReset().mockReturnValue(save.promise);
    const recalc = deferred();
    refreshDriveTimes.mockReset().mockReturnValue(recalc.promise);
    const { providerSpy } = renderApp();
    await openMapPane();
    await mountReadsSettled();

    const dialog = await openSettings();
    await saveHomeIn(dialog, 'CA12 5JR'); // still out ...
    fireEvent.click(within(dialog).getByTestId('settings-refresh-drive-btn')); // ... as Refresh is pressed

    await land(() => save.resolve(SAVED_KESWICK));
    expect(providerProps(providerSpy).homePlace).toBe('Keswick'); // control: the move landed
    await land(() => recalc.resolve({ locationsUpdated: 12, calculatedAt: '2026-09-15T10:00:00Z' }));

    expect(screen.getByText(/12 locations updated/)).toBeInTheDocument(); // control: it landed
    expect(providerProps(providerSpy).homePlace).toBe('Keswick');
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 54.6, lon: -3.13 });
    expect(providerProps(providerSpy).homeSettingsVersion).toBe(1);
  });

  it('does not take one drive-time stamp, read back at the database\'s precision, for a change', async () => {
    // The server hands a recalculation's stamp back from its clock (nanoseconds on Linux) and
    // stores it to the microsecond, so every later answer spells the same instant differently.
    getSettings
      .mockResolvedValueOnce({ ...SETTINGS_MORPETH, driveTimesCalculatedAt: null })
      .mockResolvedValueOnce({ ...SETTINGS_MORPETH, driveTimesCalculatedAt: null })
      .mockResolvedValueOnce({ ...SETTINGS_MORPETH, driveTimesCalculatedAt: '2026-09-15T10:00:12.123457Z' });
    const recalc = deferred();
    refreshDriveTimes.mockReset().mockReturnValue(recalc.promise);
    const { providerSpy } = renderApp();
    await screen.findByTestId('window-first-pane-empty');
    await mountReadsSettled();

    const dialog = await openSettings();
    fireEvent.click(within(dialog).getByTestId('settings-refresh-drive-btn'));
    await land(() => recalc.resolve({ locationsUpdated: 12, calculatedAt: '2026-09-15T10:00:12.123456789Z' }));
    expect(getReach).toHaveBeenCalledTimes(2); // control: the recalculation moved the counter
    fireEvent.click(screen.getByTestId('settings-refresh-dismiss')); // "Back to settings"
    closeSettings(screen.getByTestId('settings-modal'));
    await waitFor(() => expect(screen.queryByTestId('settings-modal')).toBeNull());

    const reopened = await openSettings(); // the stored stamp, microseconds
    expect(within(reopened).getByTestId('settings-drive-calc-time')).toBeInTheDocument(); // control
    await act(async () => {});

    // Broken, the reopening moved the drive-time counter and asked for reach a third time.
    expect(providerProps(providerSpy).driveTimesVersion).toBe(1);
    expect(getReach).toHaveBeenCalledTimes(2);
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

  it('hands the map a home that moved in longitude alone', async () => {
    // The coordinates object is memoised on both coordinates, so a home re-geocoded due east or
    // west must still move the map's marker and rings.
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH) // App's mount read
      .mockResolvedValueOnce({ ...SETTINGS_MORPETH, homeLongitude: -1.7 }); // the dialog's own
    const { providerSpy } = renderApp();
    await openMapPane();
    await mountReadsSettled();
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 55.17, lon: -1.69 });

    await openSettings();

    expect(providerProps(providerSpy).homeSettingsVersion).toBe(1); // control: the move counted
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 55.17, lon: -1.7 });
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

  it('picks up a change on every opening of the dialog, not just the first', async () => {
    // The dialog is mounted afresh each time it opens, and each opening reads the server.
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH) // App's mount read
      .mockResolvedValueOnce(SETTINGS_MORPETH) // the first opening: nothing changed
      .mockResolvedValueOnce(SETTINGS_KESWICK); // the second: moved on another device since
    const { providerSpy } = renderApp();
    await screen.findByTestId('window-first-pane-empty');
    await mountReadsSettled();

    closeSettings(await openSettings());
    await waitFor(() => expect(screen.queryByTestId('settings-modal')).toBeNull());
    expect(providerProps(providerSpy).homePlace).toBe('Morpeth');

    await openSettings();

    expect(providerProps(providerSpy).homePlace).toBe('Keswick');
  });

  it('drops the read of a dialog closed before it answered, once a newer answer has landed', async () => {
    // A slow read — the server geocodes the postcode on every GET — outlives the dialog that made
    // it. Closed and reopened, the dialog reads again, and a save made there is newer than it.
    const firstRead = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH) // App's mount read
      .mockReturnValueOnce(firstRead.promise) // the first opening's, slow
      .mockResolvedValueOnce(SETTINGS_MORPETH); // the second opening's
    lookupPostcode.mockResolvedValue(LOOKUP_KESWICK);
    const save = deferred();
    saveHome.mockReset().mockReturnValue(save.promise);
    const { providerSpy } = renderApp();
    await openMapPane();
    await mountReadsSettled();

    closeSettings(await openSettingsStillLoading());
    await waitFor(() => expect(screen.queryByTestId('settings-modal')).toBeNull());
    const dialog = await openSettings();
    await saveHomeIn(dialog, 'CA12 5JR');
    await land(() => save.resolve(SAVED_KESWICK));
    expect(providerProps(providerSpy).homePlace).toBe('Keswick'); // control: the save landed

    await land(() => firstRead.resolve(SETTINGS_MORPETH));

    // Broken, the older read put Morpeth back on the tick line and the map, and moved the home
    // counter again, so reach and the light answered for Keswick under Morpeth's name.
    expect(providerProps(providerSpy).homePlace).toBe('Keswick');
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 54.6, lon: -3.13 });
    expect(providerProps(providerSpy).homeSettingsVersion).toBe(1);
  });

  it('drops the read of a dialog reopened while a save was out, once the save has landed', async () => {
    // The second route to the same harm: the save is still out when the reader closes the dialog
    // and opens it again, and the server answers the new dialog's read before the save commits.
    const reopenedRead = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH) // App's mount read
      .mockResolvedValueOnce(SETTINGS_MORPETH) // the first opening's
      .mockReturnValueOnce(reopenedRead.promise); // the reopened dialog's
    lookupPostcode.mockResolvedValue(LOOKUP_KESWICK);
    const save = deferred();
    saveHome.mockReset().mockReturnValue(save.promise);
    const { providerSpy } = renderApp();
    await openMapPane();
    await mountReadsSettled();

    const dialog = await openSettings();
    await saveHomeIn(dialog, 'CA12 5JR');
    closeSettings(dialog);
    await waitFor(() => expect(screen.queryByTestId('settings-modal')).toBeNull());
    await openSettingsStillLoading();

    await land(() => save.resolve(SAVED_KESWICK));
    expect(providerProps(providerSpy).homePlace).toBe('Keswick'); // control: the save landed
    await land(() => reopenedRead.resolve(SETTINGS_MORPETH)); // answered before the save committed

    expect(providerProps(providerSpy).homePlace).toBe('Keswick');
    expect(mapPaneProps.last.homeCoords).toEqual({ lat: 54.6, lon: -3.13 });
    expect(providerProps(providerSpy).homeSettingsVersion).toBe(1);
  });

  it('drops the read of a dialog reopened while a colour save was out, once the save has landed', async () => {
    // The colour's own route to the same harm: the reader picks a scale, closes the dialog and
    // opens it again before the save lands, and the server answers the new read first.
    const reopenedRead = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH) // App's mount read
      .mockResolvedValueOnce(SETTINGS_MORPETH) // the first opening's
      .mockReturnValueOnce(reopenedRead.promise); // the reopened dialog's
    const colourSave = deferred();
    saveMapColourPreferences.mockReset().mockReturnValue(colourSave.promise);
    const setModeSpy = vi.spyOn(scoreRamp, 'setMode');
    renderApp();
    await openMapPane();
    await mountReadsSettled();

    const dialog = await openSettings();
    fireEvent.click(within(dialog).getByTestId('settings-map-colour-verdict'));
    closeSettings(dialog);
    await waitFor(() => expect(screen.queryByTestId('settings-modal')).toBeNull());
    await openSettingsStillLoading();

    await land(() => colourSave.resolve({ ...SETTINGS_MORPETH, mapColourScale: 'verdict' }));
    expect(mapPaneProps.last.mapColourScale).toBe('verdict'); // control: the save landed
    // Answered before the save committed, so it still carries the old scale.
    await land(() => reopenedRead.resolve(SETTINGS_MORPETH));

    // Broken, the older read put the old ramp back on every surface while the server held Verdict.
    expect(setModeSpy).toHaveBeenLastCalledWith('verdict');
    expect(mapPaneProps.last.mapColourScale).toBe('verdict');
  });

  it('drops the older of two dialogs\' reads when the newer has already answered', async () => {
    // Between reads, the newest ASKED wins, whichever lands last: a remote change the newer read
    // found must not be put back by one that set out before it.
    const firstRead = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH) // App's mount read
      .mockReturnValueOnce(firstRead.promise) // the first opening's, slow
      .mockResolvedValueOnce(SETTINGS_KESWICK); // the second's: moved on another device
    const { providerSpy } = renderApp();
    await screen.findByTestId('window-first-pane-empty');
    await mountReadsSettled();

    closeSettings(await openSettingsStillLoading());
    await waitFor(() => expect(screen.queryByTestId('settings-modal')).toBeNull());
    await openSettings();
    expect(providerProps(providerSpy).homePlace).toBe('Keswick'); // control: the newer read landed

    await land(() => firstRead.resolve(SETTINGS_MORPETH));

    expect(providerProps(providerSpy).homePlace).toBe('Keswick');
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
    const mountRead = deferred();
    getSettings
      .mockReturnValueOnce(mountRead.promise)
      .mockResolvedValueOnce(SETTINGS_MORPETH);
    const setModeSpy = vi.spyOn(scoreRamp, 'setMode');
    const { providerSpy } = renderApp();
    await openMapPane();
    await mountReadsSettled();

    await land(() => mountRead.reject(new Error('502 from /api/user/settings')));

    // Not known — and not "no postcode": nothing on the page may tell this reader to set one.
    expect(providerProps(providerSpy).homePlace).toBeUndefined();
    expect(mapPaneProps.last.homeCoords).toBeUndefined();
    // Nor a colour: the ramp keeps the default it started with, and no "colours changed" notice
    // is owed to a reader nobody has heard from.
    expect(setModeSpy).not.toHaveBeenCalled();
    expect(mapPaneProps.last.colourScaleDefaulted).toBe(false);

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
    // Not today's date: the shell falls back to today when the echo carries none, so a date that
    // could be today could not tell the echo from the fallback.
    markComingUpSeen.mockReset().mockResolvedValue({ comingUpLastSeenDate: '2026-09-02' });
    const { providerSpy } = renderApp();
    await waitFor(() => expect(providerProps(providerSpy).comingUpLastSeenDate).toBeNull());

    await act(async () => { fireEvent.click(await screen.findByRole('tab', { name: 'Coming up' })); });

    await waitFor(() => expect(providerProps(providerSpy).comingUpLastSeenDate).toBe('2026-09-02'));
    expect(markComingUpSeen).toHaveBeenCalledTimes(1);
  });

  it('drops the first of a StrictMode remount\'s two mount reads', async () => {
    // A development-only double mount: the second run's read is the newer, whichever lands last.
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
   *
   * <p>⚠️ <b>The whole block runs on a frozen clock.</b> `App` reads `ukDateStr()` fresh on every
   * render, so a fixture date computed once — as a module-load `YESTERDAY` used to be — goes stale
   * the moment a run crosses UK midnight between file load and whichever millisecond these tests
   * happen to execute: `isNightOver` then measures the picked night against a `todayStr` that has
   * moved on since, and refuses a night that is still genuinely in progress. Freezing `Date`
   * removes the question rather than narrowing it — every read of "now", in the fixtures below and
   * inside `App` alike, answers from the same frozen instant no matter when the suite actually
   * runs. The instant is `mapDates.js`'s own documented example (BST, the hour after UK midnight),
   * so it also keeps the UK and UTC calendars naming different days here — the exact case the
   * never-past clamp exists for.
   */
  describe('the date handed to the Map pane', () => {
    const NOW_ISO = '2026-08-13T23:30:00Z'; // 00:30 BST on the 14th
    const TODAY = '2026-08-14';
    const YESTERDAY = '2026-08-13';
    const LATER = '2026-08-21'; // Any today-forward date works; a week out avoids edge overlap.

    beforeEach(() => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      vi.setSystemTime(new Date(NOW_ISO));
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    const forecastsOn = (date) => LOCATION_META.flatMap((m) => ['SUNRISE', 'SUNSET'].map((t) => ({
      ...forecastRow(m, t), targetDate: date,
    })));

    /**
     * Rows on YESTERDAY, so the night is inside the forecast domain, and on LATER, so a
     * today-forward fallback has somewhere to land once it is refused. Built from the frozen clock
     * above rather than the file's own module-level `FORECASTS`/`TOMORROW`, which are computed
     * once at real wall-clock load time — mixing a real calendar date into a block pinned to a
     * fictional one is exactly the staleness this fixture exists to avoid.
     */
    const pastAndFutureForecasts = () => [...forecastsOn(LATER), ...forecastsOn(YESTERDAY)];

    /** The shell mounts a pane on first selection, so the tab has to be opened to read its props. */
    const openMapTab = async () => {
      const tab = await screen.findByRole('tab', { name: 'Map' });
      await act(async () => { fireEvent.click(tab); });
      return screen.findByTestId('map-pane-stub');
    };

    /**
     * Presses the aurora banner, and waits for the map overlay it opens and the map inside it.
     *
     * <p>⚠️ The wait is what keeps these tests off whatever the tests before them loaded. The
     * banner opens `MapOverlay` framing `MapView`, both behind `React.lazy` in `App.jsx`; a lazy
     * chunk stays loaded for the rest of the file, and `MapView`'s is requested only when
     * `MapOverlay` renders. A banner test that asserted and ended straight after its press
     * therefore left the overlay on its fallback — unless an earlier banner test had still been
     * mounted when `MapOverlay` loaded, and `MapView` had loaded since, in which case the real map
     * mounted inside the press and threw on a read this file never mocked. The default order never
     * did that; a shuffle could, and whether it did was a race.
     *
     * <p>With the map stubbed, deleting this wait fails nothing. Keep it: it runs every order
     * through the same code, and it is what makes deleting the stub fail both banner tests in every
     * order, naming the map's first unmocked read, rather than in some orders only.
     */
    const pressAuroraBanner = async () => {
      // The banner's whole body is the activation surface (a click handler + `tabIndex`, not a
      // nested button), so the click goes on the banner element itself.
      const banner = await screen.findByTestId('aurora-banner');
      await act(async () => { fireEvent.click(banner); });
      // The map inside the dialog as well as the dialog: they share one Suspense boundary today,
      // and a boundary of the map's own would let the dialog show before the map had mounted.
      const dialog = await screen.findByRole('dialog', { name: 'Aurora tonight' });
      await within(dialog).findByTestId('overlay-map-stub');
    };

    it('defaults to a today-forward date, never a past one', async () => {
      fetchForecasts.mockResolvedValue(pastAndFutureForecasts());
      renderApp();
      await openMapTab();

      expect(mapPaneProps.last.dates).toContain(YESTERDAY);
      expect(mapPaneProps.last.selectedDate).not.toBe(YESTERDAY);
      expect(mapPaneProps.last.selectedDate >= TODAY).toBe(true);
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

      await pressAuroraBanner();

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
      expect(mapPaneProps.last.selectedDate >= TODAY).toBe(true);
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
      await pressAuroraBanner();
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

    it('does not honour a night past the end its status gives it — a status still held after dawn', async () => {
      // Codex, on #841: the provider keeps its last status when a later fetch fails, so a status taken
      // before dawn is still the one in hand after it. App took its night at its word and kept the map
      // on a night that had ended. The case above, with no end on the status, is the control.
      getAuroraStatus.mockResolvedValue({
        level: 'MODERATE', kpIndex: 6, currentNightDate: YESTERDAY, simulated: false,
        currentNightEndsAt: new Date(Date.now() - 60 * 1000).toISOString(),
      });
      fetchForecasts.mockResolvedValue(pastAndFutureForecasts());
      renderApp();
      await openMapTab();

      await act(async () => { mapPaneProps.last.onSelectDate(YESTERDAY, { isNight: true }); });

      expect(mapPaneProps.last.selectedDate).not.toBe(YESTERDAY);
      expect(mapPaneProps.last.selectedDate >= TODAY).toBe(true);
    });

    it('moves the map off the night when its end passes with no new status — the provider re-renders App', async () => {
      // The same failure over time, through the real provider. Nothing about App changes when the end
      // passes — the status object is the one it already had — so App re-reads the night only because
      // the provider re-renders then. An App that remembered the night per status object would stay.
      let land;
      getAuroraStatus.mockReturnValueOnce(new Promise((resolve) => { land = resolve; }));
      fetchForecasts.mockResolvedValue(pastAndFutureForecasts());
      renderApp();
      await openMapTab();
      // Picked before any status has landed, so refused for now — but kept as the reader's choice,
      // so the status landing is what admits it.
      await act(async () => { mapPaneProps.last.onSelectDate(YESTERDAY, { isNight: true }); });
      expect(mapPaneProps.last.selectedDate).not.toBe(YESTERDAY);

      // From here the timing has to be exact, so the clock stops tracking real time — every later
      // step moves only as far as an explicit `advanceTimersByTimeAsync` takes it. Vitest carries
      // the frozen instant across the re-install (the new clock reads its start time from the
      // `Date` the old one already faked), so this does not restart the night at NOW_ISO.
      vi.useFakeTimers({ shouldAdvanceTime: false });
      await act(async () => {
        land({
          level: 'MODERATE', kpIndex: 6, currentNightDate: YESTERDAY, simulated: false,
          currentNightEndsAt: new Date(Date.now() + 1500).toISOString(),
        });
        await vi.advanceTimersByTimeAsync(0); // settles the resolved status without moving the clock
      });
      // Control: while the end is ahead, the night is honoured — and this genuinely does not race
      // the clock, since nothing from here on waits for real time to pass to prove it.
      expect(mapPaneProps.last.selectedDate).toBe(YESTERDAY);

      // Deliberately past the end, in one jump, rather than a `waitFor` racing the same 1.5s
      // against however loaded the machine happens to be.
      await act(async () => { await vi.advanceTimersByTimeAsync(1500); });
      expect(mapPaneProps.last.selectedDate).not.toBe(YESTERDAY);
      expect(mapPaneProps.last.selectedDate >= TODAY).toBe(true);
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

// ── One line of colour saves for the page, across the dialog's openings ────────────────────
//
// The colour radios stay live while a save is out — a radio in a fieldset disabled mid-save drops
// the focus of a reader arrowing between the scales — so choices overlap, and a line sends them
// one at a time and writes the newest last. The dialog unmounts on close and its saves do not stop,
// so a line per opening let a closed dialog's waiting choice go out AFTER a newer one made in the
// reopened dialog, leaving the server and the map on the older scale (found by review). `App` owns
// one line and hands it to every opening; these drive the real dialog through `App`.

describe('App — one line of colour saves for the page, across the dialog\'s openings', () => {
  /** Every colour save the page sends, in the order sent, each held until the test lands it. */
  function holdColourSaves() {
    const sent = [];
    saveMapColourPreferences.mockReset().mockImplementation((scale) => {
      const request = deferred();
      sent.push({ scale, ...request });
      return request.promise;
    });
    return sent;
  }

  const scalesSent = (sent) => sent.map(({ scale }) => scale);

  /** The server's settings, on `scale`. */
  const savedOn = (scale) => ({ ...SETTINGS_MORPETH, mapColourScale: scale });

  /** Chooses a scale in an open dialog, with a click on its radio. */
  const choose = (dialog, scale) => fireEvent.click(within(dialog).getByTestId(`settings-map-colour-${scale}`));

  async function closeAndWait(dialog) {
    closeSettings(dialog);
    await waitFor(() => expect(screen.queryByTestId('settings-modal')).toBeNull());
  }

  beforeEach(() => {
    getSettings.mockResolvedValue(savedOn('verdict'));
  });

  it('⚠️ writes a reopened dialog\'s choice LAST, after the waiting choice of the dialog closed before it', async () => {
    const sent = holdColourSaves();
    const setModeSpy = vi.spyOn(scoreRamp, 'setMode');
    renderApp();
    await openMapPane();
    await mountReadsSettled();

    const first = await openSettings();
    choose(first, 'temp'); // out at once
    choose(first, 'verdict'); // waits behind it, and outlives the dialog
    await closeAndWait(first);
    const reopened = await openSettings();
    expect(within(reopened).getByTestId('settings-map-colour-verdict'), 'it opens on the choice still in the line')
      .toBeChecked();
    expect(within(reopened).getByTestId('settings-colour-status')).toHaveTextContent('Saving…');
    choose(reopened, 'temp'); // the reader's newest choice

    expect(scalesSent(sent), 'nothing goes out beside the save in flight').toEqual(['temp']);

    await land(() => sent[0].resolve(savedOn('temp')));
    // verdict's turn came, overtaken by the reopened dialog's temp: skipped.
    expect(scalesSent(sent)).toEqual(['temp', 'temp']);
    await land(() => sent[1].resolve(savedOn('temp')));

    expect(scalesSent(sent), 'no older choice written after the newest').toEqual(['temp', 'temp']);
    expect(setModeSpy).toHaveBeenLastCalledWith('temp');
    expect(mapPaneProps.last.mapColourScale).toBe('temp');
    expect(within(reopened).getByTestId('settings-map-colour-temp')).toBeChecked();
    expect(within(reopened).getByTestId('settings-colour-status')).toHaveTextContent('');
  });

  it('still writes a closed dialog\'s waiting choice when nothing newer comes after it', async () => {
    const sent = holdColourSaves();
    renderApp();
    await openMapPane();
    await mountReadsSettled();

    const dialog = await openSettings();
    choose(dialog, 'temp');
    choose(dialog, 'verdict');
    await closeAndWait(dialog);

    await land(() => sent[0].resolve(savedOn('temp')));
    expect(scalesSent(sent), 'the waiting choice goes out after the close').toEqual(['temp', 'verdict']);
    await land(() => sent[1].resolve(savedOn('verdict')));

    expect(mapPaneProps.last.mapColourScale).toBe('verdict');
  });

  it('⚠️ sends nothing a signed-out reader left waiting — the next account signed in must not get it', async () => {
    // From review (Codex, on #859): the line outlives the dialog, and a choice waiting in it went out
    // when its turn came even after the reader had signed out. The axios interceptor reads the token
    // as each request starts, so with another account signed in by then, the old reader's choice
    // was written to THAT account.
    const sent = holdColourSaves();
    const setModeSpy = vi.spyOn(scoreRamp, 'setMode');
    renderApp();
    await mountReadsSettled();

    const dialog = await openSettings();
    choose(dialog, 'temp'); // out at once
    choose(dialog, 'verdict'); // waits behind it
    await closeAndWait(dialog);
    await act(async () => { fireEvent.click(screen.getByTestId('window-first-signout')); });
    await screen.findByTestId('login-username'); // control: signed out
    localStorage.setItem('goldenhour_token', 'the-next-account'); // whoever signs in next
    const rampCallsAtSignOut = setModeSpy.mock.calls.length;

    await land(() => sent[0].resolve(savedOn('temp')));

    expect(scalesSent(sent), 'the waiting choice was never sent').toEqual(['temp']);
    // And the save already out reports to no one: the page it belonged to has gone, and the ramp is
    // module state the next page reads.
    expect(setModeSpy.mock.calls.slice(rampCallsAtSignOut)).toEqual([]);
  });

  it('⚠️ reopens on a save that landed while the reopened dialog\'s read was out, not on the read\'s older answer', async () => {
    // The likelier order: the server geocodes on every GET, so the save lands first, and a read
    // taken before the save committed answers with the scale it replaced.
    const reopenedRead = deferred();
    getSettings
      .mockResolvedValueOnce(savedOn('verdict')) // App's mount read
      .mockResolvedValueOnce(savedOn('verdict')) // the first opening's
      .mockReturnValueOnce(reopenedRead.promise); // the reopened dialog's, slow
    const sent = holdColourSaves();
    renderApp();
    await openMapPane();
    await mountReadsSettled();

    const first = await openSettings();
    choose(first, 'temp');
    await closeAndWait(first);
    const reopened = await openSettingsStillLoading();
    await land(() => sent[0].resolve(savedOn('temp')));

    await land(() => reopenedRead.resolve(savedOn('verdict')));

    expect(await within(reopened).findByTestId('settings-map-colour-temp')).toBeChecked();
    expect(within(reopened).getByTestId('settings-map-colour-verdict')).not.toBeChecked();
    expect(mapPaneProps.last.mapColourScale, 'the map agrees with the dialog').toBe('temp');
  });
});

// ── The settings dialog hands focus back to the tick line's origin slot, not to <body> ──────
//
// From an accessibility review of #842. On the Map tab the "set a postcode" nudge is REPLACED by
// the statement ("Home · Keswick — drive times from here") once a home is known: a `<span>` for a
// `<button>`, so the node the settings dialog recorded as its opener is destroyed. Two orders
// reach that, each held by its own mechanism, so each gets a test through the real shell, tick
// line and dialog:
//   · the swap BEFORE the close — the ordinary save: `onHomeSaved` updates the reader's record while
//     the dialog is still open. Its recorded opener is detached by the close, and `App`'s
//     `restoreFocusFallback` asks the tick line what stands in the nudge's place;
//   · the swap AFTER the close — the dialog's own settings read answering once it has closed: the
//     dialog hands focus back to the nudge, and the tick line hands it on when the nudge is swapped.

describe('App — the settings dialog hands focus back to the tick line\'s origin slot', () => {
  beforeEach(() => {
    lookupPostcode.mockReset();
    saveHome.mockReset();
  });

  /** The Map tab's tick line, showing the "set a postcode" nudge. */
  async function mapTabNudge() {
    await openMapPane();
    return screen.findByRole('button', { name: 'Set a postcode for light and drive times' });
  }

  /** Presses the nudge as a keyboard reader does — focused first, which `fireEvent.click` never does. */
  function pressNudge(nudge) {
    nudge.focus();
    fireEvent.click(nudge);
  }

  it('⚠️ lands the reader on the statement after a save replaced the nudge under the open dialog', async () => {
    getSettings.mockResolvedValue(SETTINGS_NO_HOME);
    lookupPostcode.mockResolvedValue(LOOKUP_KESWICK);
    const save = deferred();
    saveHome.mockReturnValue(save.promise);
    renderApp();
    const nudge = await mapTabNudge();
    pressNudge(nudge);
    const dialog = await screen.findByTestId('settings-modal');
    const field = await within(dialog).findByTestId('settings-postcode-input');
    await waitFor(() => expect(field).toHaveFocus()); // the dialog's own landing is spent
    await saveHomeIn(dialog, 'CA12 5JR');

    await land(() => save.resolve(SAVED_KESWICK));

    expect(nudge.isConnected, 'precondition: the save replaced the nudge while the dialog was open')
      .toBe(false);
    const statement = screen.getByTestId('window-first-origin-statement');
    expect(statement).toHaveTextContent('Home · Keswick');
    expect(statement, 'precondition: the dialog still holds the reader').not.toHaveFocus();

    closeSettings(dialog);

    await waitFor(() => expect(statement).toHaveFocus());
  });

  it('⚠️ hands focus on to the statement when the dialog\'s own read replaces the nudge after the close', async () => {
    const dialogRead = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_NO_HOME) // App's mount read: no home, so the nudge shows
      .mockReturnValueOnce(dialogRead.promise); // the dialog's own read, answered once it has closed
    renderApp();
    const nudge = await mapTabNudge();
    pressNudge(nudge);
    const dialog = await screen.findByTestId('settings-modal');
    // Still loading, so nothing inside takes focus and the dialog's own frame lands on its root.
    await waitFor(() => expect(dialog).toHaveFocus());

    closeSettings(dialog);

    await waitFor(() => expect(nudge, 'precondition: the close handed focus back to the nudge').toHaveFocus());

    // A home saved elsewhere — another device — is what the late read brings back.
    await land(() => dialogRead.resolve(SETTINGS_KESWICK));

    expect(nudge.isConnected, 'precondition: the read replaced the nudge after the close').toBe(false);
    const statement = screen.getByTestId('window-first-origin-statement');
    expect(statement).toHaveTextContent('Home · Keswick');
    expect(statement).toHaveFocus();
  });

  it('⚠️ forgets the nudge\'s return address on close, so a later opening from the cog cannot use it', async () => {
    getSettings.mockResolvedValue(SETTINGS_NO_HOME);
    renderApp();
    const nudge = await mapTabNudge();
    pressNudge(nudge);
    const nudgeDialog = await screen.findByTestId('settings-modal');
    await waitFor(() => expect(within(nudgeDialog).getByTestId('settings-postcode-input')).toHaveFocus());
    closeSettings(nudgeDialog);
    await waitFor(() => expect(nudge, 'control: the close restored to the nudge').toHaveFocus());

    // A pointer press on the cog — which macOS Safari does not focus — opens it with focus nowhere.
    nudge.blur();
    expect(document.activeElement, 'precondition: focus is nowhere').toBe(document.body);
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }));
    const cogDialog = await screen.findByTestId('settings-modal');
    await within(cogDialog).findByTestId('settings-postcode-input');
    await waitFor(() => expect(cogDialog).toHaveFocus()); // its own landing is spent

    closeSettings(cogDialog);
    await waitFor(() => expect(screen.queryByTestId('settings-modal')).toBeNull());

    expect(nudge.isConnected, 'precondition: a stale return address would still have somewhere to send focus')
      .toBe(true);
    expect(document.activeElement, 'the cog\'s opening had no return address — nothing moves focus')
      .toBe(document.body);
  });
});
