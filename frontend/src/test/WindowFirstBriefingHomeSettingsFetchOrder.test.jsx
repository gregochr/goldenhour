/**
 * `WindowFirstBriefingProvider` — the reach and the home on screen answer the newest request made
 * for each, and a request a newer home save has superseded writes nothing, whenever it lands.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>`GET /api/user/settings/reach` and `GET /api/user/settings` are each asked on mount and again
 * whenever `homeSettingsVersion` moves — which `App` does each time the settings dialog saves a
 * change to the home: a new postcode, or a drive-time recalculation. Neither effect had a cleanup,
 * so with two requests out at once (the mount's own and a save's, or two saves' — a move, then a
 * recalculation — on a slow connection) the one that LANDED last won, not the one ASKED last:
 *
 * <ul>
 *   <li>An older reach answer put drive times measured before the latest save back on every spot —
 *       or, from before a first postcode was saved, took every reach line away again.</li>
 *   <li>An older settings answer put the older home back: from before a first postcode it is
 *       `null`, and the tick line asked the reader who had just set one to set one. An older
 *       settings FAILURE put both fields back to `undefined` — the place gone from the tick line and
 *       the Coming up badge gone with it.</li>
 * </ul>
 *
 * <p>⚠️ Which form a reader met depends on the engine and on how long the superseded request stayed
 * out (measured 2026-09-14 with a local probe on these paths' real headers). WebKit and Firefox send
 * both requests at once and the second can overtake the first, so there the superseded answer can
 * land LAST and stay. Chromium's HTTP cache lock holds a second request to the same URL until the
 * first is answered or the second has waited 20 s, so there it lands FIRST and stands in for a round
 * trip, and can land LAST after that — which is why the reach "lands FIRST" test is no corner case.
 * The settings fetch's are narrower in Chrome: the dialog's own `GET /api/user/settings` queues
 * behind any request to that URL still out, and nothing can be saved until it answers, so within the
 * 20 s a pre-save home cannot still be out when a save moves the counter.
 *
 * <h2>Why the effect cleanup, and which tests say so</h2>
 *
 * <p>This is the other shape of the race the polled briefing and ratings fetches have. A poll re-asks
 * the SAME question, so an older answer landing on its own is still the freshest there is, and the
 * right guard there drops only what is older than the answer already applied. Here a newer request
 * follows a save that changed the home, so the older one answers a question that has since changed
 * — it is superseded outright, and dropped wherever it lands. The two rules part company wherever the
 * superseded request settles while nothing newer than it has been applied: its answer landing FIRST,
 * its answer landing after the newest request FAILED, and — since both fetches' `.catch` write —
 * its failure landing first. A guard that drops only what is older than the applied answer lets all
 * three through; each has a test here, for each fetch.
 *
 * <p>Both `.catch`es empty what they write rather than keep the answer from before: the settings
 * fetch always has, and the reach fetch does since an owner decision on 2026-09-15 (it used to keep
 * the old figures — after a move, the old house's drive times).
 *
 * <p>⚠️ Dropping is only right while every move of the counter is a real change, and it was not
 * always: the counter used to move on every close of the dialog, saved or not, so a close that saved
 * nothing superseded a save's own correct answer. `App` now moves it on a save alone (pinned in
 * `App.test.jsx` and `UserSettingsModal.test.jsx`). One narrow case is left, and the test marked "the
 * remaining price" pins it: a recalculation moves the counter too and does not change the settings
 * answer.
 *
 * <h2>Why a probe, not a real consumer</h2>
 *
 * <p>The provider has no single natural consumer, and these two fetches least of all: the reach map
 * reaches the spot strip, the leave-by lines and the map pane; the home reaches the tick line and the
 * map pane; the last-seen date reaches the shell's badge — a whole tab behind lazy chunks. The probe
 * prints exactly the three values these fetches write, so which answer is on screen is legible
 * without rendering a tab. The API modules are mocked at the boundary the frontend test standards
 * prescribe.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({
  fetchRegions: vi.fn(),
  fetchRegionDriveTimes: vi.fn(),
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));

import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegions, fetchRegionDriveTimes } from '../api/regionApi.js';

/** The one location every reach answer below measures (Bamburgh). */
const LOCATION_ID = 7;

/**
 * A reach answer as `GET /api/user/settings/reach` serves one: every enabled location present, with
 * null figures while the reader has no home postcode saved.
 */
const reachAnswer = (driveMinutes, distanceMiles) => [
  { locationId: LOCATION_ID, driveMinutes, distanceMiles },
];

/**
 * Before the reader has saved a home postcode — the normal first run. Only the landing-LAST test uses
 * it: every consumer reads a null figure exactly as it reads a missing entry, so as a superseded
 * answer that lands first, or fills in for a failure, it would draw nothing either way and could not
 * show the harm. Those tests move house instead, Morpeth to Keswick.
 */
const REACH_BEFORE_A_POSTCODE = reachAnswer(null, null);
const REACH_FROM_MORPETH = reachAnswer(41, 19);
/** Just after a move: the backend clears stored drive times, so only the distance is known. */
const REACH_KESWICK_BEFORE_RECALCULATION = reachAnswer(null, 104);
const REACH_FROM_KESWICK = reachAnswer(138, 104);

/**
 * `GET /api/user/settings` answers for one reader at three moments. The last-seen date differs
 * between every pair (a Coming up visit between two reads, on any device, moves it) so that each
 * test can see which answer BOTH fields hold — one check guards the two writes, and a check that
 * covered only the first would otherwise pass.
 */
const SETTINGS_NO_HOME = {
  homePostcode: null, homePlaceName: null, comingUpLastSeenDate: '2026-09-01',
};
const SETTINGS_MORPETH = {
  homePostcode: 'NE61 1AA', homePlaceName: 'Morpeth', comingUpLastSeenDate: '2026-09-10',
};
const SETTINGS_KESWICK = {
  homePostcode: 'CA12 5JR', homePlaceName: 'Keswick', comingUpLastSeenDate: '2026-09-12',
};

/** A request the test settles by hand, so the test — not the scheduler — decides which lands first. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Settles a hand-held request inside an AWAITED `act`, which is what makes a negative safe to
 * assert: the provider's `.then` — or its `.catch`, a link further down the chain — has run and its
 * update has committed before the test resumes, so an answer that is not on screen afterwards was
 * dropped, not merely still on its way. Every positive control below settles through this same
 * helper, so a helper that stopped flushing would fail at the control rather than let a negative
 * pass (frontend test standards, "A late response is only 'dropped' once it has landed").
 */
async function land(settle) {
  await act(async () => { settle(); });
}

/**
 * A probe rather than a real consumer — see the file comment for why. It prints the three values
 * the fetches under test write, and nothing else. `undefined` prints as `unknown`, because the
 * provider's three states are the point: `null` is an answer ("no home saved"), `undefined` is the
 * absence of one.
 */
function Probe() {
  const { reachById, homePlace, comingUpLastSeenDate } = useWindowFirstBriefing();
  const state = (v) => (v === undefined ? 'unknown' : String(v));
  return (
    <div>
      <span data-testid="reach">
        {[...reachById.entries()]
          .map(([id, v]) => `${id}=${v.driveMinutes}/${v.distanceMiles}`)
          .join(' ') || 'none'}
      </span>
      <span data-testid="home">{state(homePlace)}</span>
      <span data-testid="last-seen">{state(comingUpLastSeenDate)}</span>
    </div>
  );
}

const tree = (version) => (
  <WindowFirstBriefingProvider homeSettingsVersion={version}>
    <Probe />
  </WindowFirstBriefingProvider>
);

/** Mounts the tree at a counter value and lets any request answered up front land. */
async function mountAt(version) {
  let result;
  await act(async () => { result = render(tree(version)); });
  return result;
}

/**
 * The settings dialog saves a change to the home — a new postcode, or a drive-time recalculation:
 * `App` bumps the counter and re-renders the provider WITHOUT remounting it — the dialog is its
 * sibling — so both effects re-run and each asks again.
 */
async function homeSaved(result, version) {
  await act(async () => { result.rerender(tree(version)); });
}

/** What {@link Probe} prints for a reach answer. */
const reachShown = (answer) => answer.map((e) => `${e.locationId}=${e.driveMinutes}/${e.distanceMiles}`).join(' ');

/** The two values the settings fetch writes, as the probe prints them. */
const homeShown = () => ({
  home: screen.getByTestId('home').textContent,
  lastSeen: screen.getByTestId('last-seen').textContent,
});

/** What {@link homeShown} reads once a settings answer is applied. */
const homeOf = (settings) => ({
  home: String(settings.homePlaceName || settings.homePostcode || null),
  lastSeen: String(settings.comingUpLastSeenDate ?? null),
});

/** What {@link homeShown} reads with no answer applied — the provider's `undefined`. */
const UNKNOWN = { home: 'unknown', lastSeen: 'unknown' };

beforeEach(() => {
  localStorage.clear();
  // Reset, not cleared: `mockReturnValueOnce` queues survive `vi.clearAllMocks()`, so a test that
  // failed with a request still queued would hand it to the next test's mount. Each test queues
  // every answer it expects from the fetch under test and asserts how many requests went out, so
  // an unexpected one fails the test rather than landing silently.
  getDailyBriefing.mockReset().mockResolvedValue(null);
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getReach.mockReset();
  getSettings.mockReset();
  fetchRegions.mockReset().mockResolvedValue([]);
  fetchRegionDriveTimes.mockReset().mockResolvedValue({});
});

describe('WindowFirstBriefingProvider — reach answers the newest request, not the last to land', () => {
  beforeEach(() => {
    // The settings fetch is not under test here: every request answers at once, with a home.
    getSettings.mockResolvedValue(SETTINGS_MORPETH);
  });

  it('drops the mount\'s answer when it lands after a saved postcode\'s — the reach lines stay', async () => {
    // A first-run reader saves a postcode while the mount's request, asked before there was one, is
    // still out — and that one lands last.
    const mountAnswer = deferred();
    const saveAnswer = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(saveAnswer.promise);

    const result = await mountAt(0);
    await homeSaved(result, 1);
    // Both requests really are out at once — without that there is no race to lose.
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => saveAnswer.resolve(REACH_FROM_MORPETH));
    // Control: the newest answer is applied.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));

    await land(() => mountAnswer.resolve(REACH_BEFORE_A_POSTCODE));

    // Still the newest. Broken, the pre-postcode answer came back and every reach line went absent
    // again — the setting "appeared to do nothing", the defect the counter exists to cure.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
  });

  it('drops the move\'s answer when it lands after the recalculation\'s — the drive times stay recalculated', async () => {
    // The other source of a superseded request: not the mount's, but an earlier save's. The reader
    // moves home and at once recalculates their drive times, inside one slow round trip — and the
    // move's answer, from before the recalculation, lands last.
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getReach
      .mockResolvedValueOnce(REACH_FROM_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt(0);
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
    await homeSaved(result, 1); // moved to Keswick
    await homeSaved(result, 2); // recalculated the drive times from it
    expect(getReach).toHaveBeenCalledTimes(3);
    // Nothing is cleared when the counter moves: until an answer lands, the mount's figures stand.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));

    await land(() => recalcAnswer.resolve(REACH_FROM_KESWICK));
    // Control: the newest answer is applied.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));

    await land(() => moveAnswer.resolve(REACH_KESWICK_BEFORE_RECALCULATION));

    // Still the recalculated figures. Broken, the move's answer — distances only, no drive times —
    // replaced them, and every drive time and leave-by line the reader had just paid a
    // recalculation for went absent again.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('drops a superseded answer even when it lands FIRST — it answers a question the save has changed', async () => {
    // The first place the effect cleanup and a request-number guard part company, and the form
    // Chrome produces within its lock's 20 s — the dialog never asks for this URL, so nothing stops
    // a move while the mount's request is out. A number guard drops only what is older than the
    // answer already applied, so with nothing applied yet it would draw the old house's drive times
    // for as long as the newest request took.
    const mountAnswer = deferred();
    const moveAnswer = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(moveAnswer.promise);

    const result = await mountAt(0);
    await homeSaved(result, 1); // the reader moved from Morpeth to Keswick
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => mountAnswer.resolve(REACH_FROM_MORPETH));
    // Nothing applied. Broken, Morpeth's drive times were drawn until Keswick's answer arrived.
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    // Control, settled through the same helper: the newest answer still lands and applies.
    await land(() => moveAnswer.resolve(REACH_KESWICK_BEFORE_RECALCULATION));
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_KESWICK_BEFORE_RECALCULATION));
  });

  it('does not let a superseded answer stand in when the newest request fails', async () => {
    // The second place the two rules part company. The newest request failing leaves the map empty,
    // so a number guard would take the superseded answer that lands after it — the old house's
    // drive times, filling in for the new one's. Empty is the right answer here whether or not
    // anything was on screen before (the next test).
    const mountAnswer = deferred();
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt(0);
    await homeSaved(result, 1); // the reader moved from Morpeth to Keswick
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => moveAnswer.reject(new Error('502 from /api/user/settings/reach')));
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    await land(() => mountAnswer.resolve(REACH_FROM_MORPETH));

    // Still nothing. Broken, Morpeth's drive times filled in for Keswick's.
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    // Control, settled through the same helper: the next save's answer lands and applies. Without
    // it this test could not tell a dropped answer from a helper that never let one land.
    await homeSaved(result, 2); // recalculated the drive times
    await land(() => recalcAnswer.resolve(REACH_FROM_KESWICK));
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('empties the map when the NEWEST request fails, even over an answer on screen', async () => {
    // A failed refetch empties rather than keeps (an owner decision, 2026-09-15): the counter moves
    // only on a save, so the figures on screen measure a journey the save has changed — after a
    // move, from the old house. Empty claims no drive; they would claim one the reader no longer
    // has.
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getReach
      .mockResolvedValueOnce(REACH_FROM_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt(0);
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
    await homeSaved(result, 1); // the reader moved from Morpeth to Keswick
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => moveAnswer.reject(new Error('502 from /api/user/settings/reach')));

    // Empty. It used to keep Morpeth's drive times and leave-by lines beside the tick line's
    // Keswick.
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    // Control, settled through the same helper: the next save's answer fills the map again.
    await homeSaved(result, 2); // recalculated the drive times
    await land(() => recalcAnswer.resolve(REACH_FROM_KESWICK));
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('keeps the answer on screen when a superseded request FAILS first — only the newest failure empties', async () => {
    // The third place the rules part company, now that this `.catch` writes. The move's request is
    // superseded by the recalculation's while both are out; its failure is not the newest request
    // failing, so the mount's figures stand until the newest settles — nothing is cleared when the
    // counter merely moves. A number guard, with nothing newer applied yet, would let it empty the
    // map.
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getReach
      .mockResolvedValueOnce(REACH_FROM_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt(0);
    await homeSaved(result, 1); // moved to Keswick
    await homeSaved(result, 2); // recalculated the drive times from it
    expect(getReach).toHaveBeenCalledTimes(3);

    await land(() => moveAnswer.reject(new Error('Network Error')));

    // Unchanged. Broken, the map went empty while the newest request was still on its way.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));

    // Control, settled through the same helper: the newest answer lands and applies.
    await land(() => recalcAnswer.resolve(REACH_FROM_KESWICK));
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('keeps the newest answer when a superseded request FAILS after it', async () => {
    // The guard on the `.catch` is load-bearing now that it writes: a failed refetch empties the
    // map, so without the guard a superseded failure landing after the newest answer would wipe it.
    // (Before the catch wrote, this passed with or without the cleanup; it was kept for this
    // change.)
    const mountAnswer = deferred();
    const saveAnswer = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(saveAnswer.promise);

    const result = await mountAt(0);
    await homeSaved(result, 1);
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => saveAnswer.resolve(REACH_FROM_MORPETH));
    // Control: the newest answer is applied.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));

    await land(() => mountAnswer.reject(new Error('Network Error')));

    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
  });
});

describe('WindowFirstBriefingProvider — the home answers the newest request, not the last to land', () => {
  beforeEach(() => {
    // The reach fetch is not under test here: every request answers at once, with figures.
    getReach.mockResolvedValue(REACH_FROM_MORPETH);
  });

  it('drops the mount\'s answer when it lands after a saved postcode\'s — the reader is not asked to set one', async () => {
    const mountAnswer = deferred();
    const saveAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(saveAnswer.promise);

    const result = await mountAt(0);
    await homeSaved(result, 1);
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => saveAnswer.resolve(SETTINGS_MORPETH));
    // Control: the newest answer is applied, both fields.
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));

    await land(() => mountAnswer.resolve(SETTINGS_NO_HOME));

    // Still the newest, both fields. Broken, the home went back to `null` — the tick line's "Set a
    // postcode" in front of the reader who had just set one — and the last-seen date went back to
    // an older day, so the badge counted arrivals the reader had already seen as new.
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));
  });

  it('drops a superseded FAILURE that lands after the newest answer — the home stays known', async () => {
    // ⚠️ The `.catch` half of the cleanup, which the late-answer test cannot reach: the catch writes
    // `undefined` to both fields, so unguarded a superseded failure wiped a good answer back to
    // unknown — a bare "Home" on the tick line where it had just said "Home · Morpeth" (or "Set a
    // postcode", while the light still held a pre-save `null`), and no Coming up badge until the
    // next settings fetch.
    const mountAnswer = deferred();
    const saveAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(saveAnswer.promise);

    const result = await mountAt(0);
    await homeSaved(result, 1);
    // The mount's request really is in flight — without it the rejection below would land on
    // nothing, and this test would pass having never reached a `.catch`.
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => saveAnswer.resolve(SETTINGS_MORPETH));
    // Control: the newest answer is applied.
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));

    await land(() => mountAnswer.reject(new Error('Network Error')));

    // Still known, both fields. Broken, both went back to `unknown`.
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));
  });

  it('drops a superseded FAILURE that lands first — the answer on screen is not blanked while the newest is out', async () => {
    // The same `.catch`, landing first. The superseded request is newer than the answer on screen —
    // the mount's — so a guard that drops only what is older than the applied answer would let this
    // failure clear it. The mount's answer stands until the newest replaces it.
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt(0);
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));
    await homeSaved(result, 1); // moved to Keswick
    await homeSaved(result, 2); // recalculated the drive times from it
    expect(getSettings).toHaveBeenCalledTimes(3);

    await land(() => moveAnswer.reject(new Error('Network Error')));

    // Still Morpeth's. Broken, both fields went to `unknown` for as long as the newest request took.
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));

    // Control, settled through the same helper: the newest answer lands and applies.
    await land(() => recalcAnswer.resolve(SETTINGS_KESWICK));
    expect(homeShown()).toEqual(homeOf(SETTINGS_KESWICK));
  });

  it('drops a superseded answer even when it lands FIRST — it answers a question the save has changed', async () => {
    // The WebKit and Firefox form; Chrome reaches it only past its lock's 20 s, since the dialog
    // cannot save while an older request to this URL is out.
    const mountAnswer = deferred();
    const moveAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(moveAnswer.promise);

    const result = await mountAt(0);
    await homeSaved(result, 1); // the reader moved from Morpeth to Keswick
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => mountAnswer.resolve(SETTINGS_MORPETH));
    // Nothing applied. Broken, the tick line named the home the reader had just moved away from,
    // for as long as the newest request took.
    expect(homeShown()).toEqual(UNKNOWN);

    // Control, settled through the same helper: the newest answer still lands and applies.
    await land(() => moveAnswer.resolve(SETTINGS_KESWICK));
    expect(homeShown()).toEqual(homeOf(SETTINGS_KESWICK));
  });

  it('does not let a superseded answer stand in when the newest request fails — unknown, not the home just left', async () => {
    // The newest request's own failure is silence, exactly as before this fix: `undefined` makes no
    // claim of its own — the tick line then goes by the light — where the superseded answer would
    // name a home the reader has just left. A number guard would apply that answer, landing after
    // the failure.
    const mountAnswer = deferred();
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt(0);
    await homeSaved(result, 1); // the reader moved from Morpeth to Keswick
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => moveAnswer.reject(new Error('502 from /api/user/settings')));
    expect(homeShown()).toEqual(UNKNOWN);

    await land(() => mountAnswer.resolve(SETTINGS_MORPETH));

    // Still unknown. Broken, the pre-move answer filled in: "Home · Morpeth" for a reader who had
    // just moved to Keswick, on nothing more than a superseded answer.
    expect(homeShown()).toEqual(UNKNOWN);

    // Control, settled through the same helper: the next save's answer lands and applies.
    await homeSaved(result, 2); // recalculated the drive times
    await land(() => recalcAnswer.resolve(SETTINGS_KESWICK));
    expect(homeShown()).toEqual(homeOf(SETTINGS_KESWICK));
  });

  it('leaves the home unknown when the NEWEST request fails, even over an answer on screen', async () => {
    // The catch body's own policy, which no guard test reaches: a failed request is no evidence the
    // home is unchanged — the save may have moved it — so both fields go to `undefined`, as they
    // always have, rather than keeping the answer before. (The reach catch now empties its map the
    // same way — an owner decision, 2026-09-15 — pinned in the reach block above.)
    const saveAnswer = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH)
      .mockReturnValueOnce(saveAnswer.promise);

    const result = await mountAt(0);
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));
    await homeSaved(result, 1);
    expect(getSettings).toHaveBeenCalledTimes(2);
    // Nothing is cleared when the counter moves: until the save's request settles, the mount's
    // answer stands.
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));

    await land(() => saveAnswer.reject(new Error('502 from /api/user/settings')));

    expect(homeShown()).toEqual(UNKNOWN);
  });

  it('⚠️ the remaining price: a drive-time recalculation supersedes the postcode save\'s own settings answer', async () => {
    // A recalculation moves the counter too, and does not change this answer. So when one completes
    // while the postcode save's settings request is still out, that correct answer is dropped and
    // the pre-save state stands until the recalculation's request answers. Narrow — the save's
    // request has to outlast a server-side recalculation — and closable only with a counter per
    // question. Pinned so that closing it is a decision: so is a rule that keeps a superseded answer
    // newer than the one on screen, which would show Morpeth a round trip sooner and fails here.
    const saveAnswer = deferred();
    const recalcAnswer = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_NO_HOME)
      .mockReturnValueOnce(saveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt(0);
    expect(homeShown()).toEqual(homeOf(SETTINGS_NO_HOME));
    await homeSaved(result, 1); // saved Morpeth
    await homeSaved(result, 2); // recalculated the drive times from it
    expect(getSettings).toHaveBeenCalledTimes(3);

    await land(() => saveAnswer.resolve(SETTINGS_MORPETH));
    // Dropped, though it was right: still the no-home answer the mount applied.
    expect(homeShown()).toEqual(homeOf(SETTINGS_NO_HOME));

    // Control, settled through the same helper: the recalculation's request carries the same home,
    // and applies.
    await land(() => recalcAnswer.resolve(SETTINGS_MORPETH));
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));
  });
});
