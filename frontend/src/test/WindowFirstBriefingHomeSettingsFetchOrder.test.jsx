/**
 * `WindowFirstBriefingProvider` — the reach and the home on screen answer the newest request made
 * for each, and a request the settings dialog has since superseded writes nothing, whenever it lands.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>`GET /api/user/settings/reach` and `GET /api/user/settings` are each asked on mount and again
 * whenever `homeSettingsVersion` moves — which `App` does on every close of the settings dialog,
 * saved or not. Neither effect had a cleanup, so with two requests out at once (the mount's own and
 * a close's, or two closes', on a connection slow enough to outlast a trip through the dialog) the
 * one that LANDED last won, not the one ASKED last:
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
 * 20 s a pre-save home cannot still be out at the close that follows a save.
 *
 * <h2>Why the effect cleanup, what it costs, and which tests say so</h2>
 *
 * <p>This is the other shape of the race the polled briefing and ratings fetches have. A poll re-asks
 * the SAME question, so an older answer landing on its own is still the freshest there is, and the
 * right guard there drops only what is older than the answer already applied. Here a newer request
 * follows a close of the dialog, so the older one answers a question that may since have changed —
 * the owner's call was to treat it as superseded outright and drop it wherever it lands. The two
 * rules part company wherever the superseded request settles while nothing newer than it has been
 * applied: its answer landing FIRST, its answer landing after the newest request FAILED, and — for
 * the settings fetch, whose `.catch` writes — its failure landing first. A guard that drops only what
 * is older than the applied answer lets all three through; each has a test here.
 *
 * <p>⚠️ The cost is pinned too, by the test marked "the accepted price". The counter moves on every
 * close, saved or not, so a superseded request is not always stale: a close that saved nothing still
 * supersedes the save's own answer, and the pre-save state stands until the newest request answers.
 * The provider cannot tell the two closes apart; that test exists so that a change to the rule is a
 * decision rather than an accident.
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
 * The settings dialog closes: `App` bumps the counter and re-renders the provider WITHOUT remounting
 * it — the dialog is its sibling — so both effects re-run and each asks again.
 */
async function dialogClosed(result, version) {
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
    const closeAnswer = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(closeAnswer.promise);

    const result = await mountAt(0);
    await dialogClosed(result, 1);
    // Both requests really are out at once — without that there is no race to lose.
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => closeAnswer.resolve(REACH_FROM_MORPETH));
    // Control: the newest answer is applied.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));

    await land(() => mountAnswer.resolve(REACH_BEFORE_A_POSTCODE));

    // Still the newest. Broken, the pre-postcode answer came back and every reach line went absent
    // again — the setting "appeared to do nothing", the defect the counter exists to cure.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
  });

  it('drops the last close\'s answer when it lands after the next close\'s — the drive times are from the new home', async () => {
    // The other source of a superseded request: not the mount's, but the previous close's. The
    // reader closes the dialog, reopens it, moves home and closes it again, inside one slow round
    // trip — and the first close's answer, still measured from the old house, lands last.
    const firstClose = deferred();
    const secondClose = deferred();
    getReach
      .mockResolvedValueOnce(REACH_FROM_MORPETH)
      .mockReturnValueOnce(firstClose.promise)
      .mockReturnValueOnce(secondClose.promise);

    const result = await mountAt(0);
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
    await dialogClosed(result, 1);
    await dialogClosed(result, 2);
    expect(getReach).toHaveBeenCalledTimes(3);
    // Nothing is cleared when the counter moves: until an answer lands, the mount's figures stand.
    // A clear would blank every reach line for a round trip each time the dialog was dismissed.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));

    await land(() => secondClose.resolve(REACH_FROM_KESWICK));
    // Control: the newest answer is applied.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));

    await land(() => firstClose.resolve(REACH_FROM_MORPETH));

    // Still Keswick's. Broken, every spot's drive time — and the leave-by time a reader acts on —
    // measured a journey from the house they had just told the app they no longer start from.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('drops a superseded answer even when it lands FIRST — it answers a question the close has changed', async () => {
    // The first place the effect cleanup and a request-number guard part company, and the form
    // Chrome produces within its lock's 20 s — the dialog never asks for this URL, so nothing stops
    // a move while the mount's request is out. A number guard drops only what is older than the
    // answer already applied, so with nothing applied yet it would draw the old house's drive times
    // for as long as the newest request took.
    const mountAnswer = deferred();
    const closeAnswer = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(closeAnswer.promise);

    const result = await mountAt(0);
    await dialogClosed(result, 1); // the reader moved from Morpeth to Keswick
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => mountAnswer.resolve(REACH_FROM_MORPETH));
    // Nothing applied. Broken, Morpeth's drive times were drawn until Keswick's arrived.
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    // Control, settled through the same helper: the newest answer still lands and applies.
    await land(() => closeAnswer.resolve(REACH_FROM_KESWICK));
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('does not let a superseded answer stand in when the newest request fails', async () => {
    // The second place the two rules part company. The newest request failing applies nothing, so a
    // number guard would take the superseded answer that lands after it — the old house's drive
    // times, filling in for the new one's. With nothing on screen before, the map stays unknown
    // rather than wrong; with an earlier answer on screen it keeps that one (the provider's note on
    // the price of the cleanup).
    const mountAnswer = deferred();
    const closeAnswer = deferred();
    const nextClose = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(closeAnswer.promise)
      .mockReturnValueOnce(nextClose.promise);

    const result = await mountAt(0);
    await dialogClosed(result, 1); // the reader moved from Morpeth to Keswick
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => closeAnswer.reject(new Error('502 from /api/user/settings/reach')));
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    await land(() => mountAnswer.resolve(REACH_FROM_MORPETH));

    // Still nothing. Broken, Morpeth's drive times filled in for Keswick's.
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    // Control, settled through the same helper: the next close's answer lands and applies. Without
    // it this test could not tell a dropped answer from a helper that never let one land.
    await dialogClosed(result, 2);
    await land(() => nextClose.resolve(REACH_FROM_KESWICK));
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('keeps the newest answer when a superseded request FAILS after it', async () => {
    // ⚠️ Passes with or without the cleanup today, and that is stated rather than hidden: this
    // fetch's `.catch` writes nothing, so a late failure has nothing to undo. It is here for the
    // change that makes the catch write — clearing the map when a refetch after a home move fails
    // is a plausible one — which without the settings fetch's guard would let a superseded failure
    // wipe the newest answer. Mutation-checked against exactly that catch.
    const mountAnswer = deferred();
    const closeAnswer = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(closeAnswer.promise);

    const result = await mountAt(0);
    await dialogClosed(result, 1);
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => closeAnswer.resolve(REACH_FROM_MORPETH));
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
    const closeAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(closeAnswer.promise);

    const result = await mountAt(0);
    await dialogClosed(result, 1);
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => closeAnswer.resolve(SETTINGS_MORPETH));
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
    // dialog next closed.
    const mountAnswer = deferred();
    const closeAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(closeAnswer.promise);

    const result = await mountAt(0);
    await dialogClosed(result, 1);
    // The mount's request really is in flight — without it the rejection below would land on
    // nothing, and this test would pass having never reached a `.catch`.
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => closeAnswer.resolve(SETTINGS_MORPETH));
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
    const firstClose = deferred();
    const secondClose = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH)
      .mockReturnValueOnce(firstClose.promise)
      .mockReturnValueOnce(secondClose.promise);

    const result = await mountAt(0);
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));
    await dialogClosed(result, 1);
    await dialogClosed(result, 2);
    expect(getSettings).toHaveBeenCalledTimes(3);

    await land(() => firstClose.reject(new Error('Network Error')));

    // Still Morpeth's. Broken, both fields went to `unknown` for as long as the newest request took.
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));

    // Control, settled through the same helper: the newest answer lands and applies.
    await land(() => secondClose.resolve(SETTINGS_KESWICK));
    expect(homeShown()).toEqual(homeOf(SETTINGS_KESWICK));
  });

  it('drops a superseded answer even when it lands FIRST — it answers a question the close has changed', async () => {
    // The WebKit and Firefox form; Chrome reaches it only past its lock's 20 s, since the dialog
    // cannot save while an older request to this URL is out.
    const mountAnswer = deferred();
    const closeAnswer = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(closeAnswer.promise);

    const result = await mountAt(0);
    await dialogClosed(result, 1); // the reader moved from Morpeth to Keswick
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => mountAnswer.resolve(SETTINGS_MORPETH));
    // Nothing applied. Broken, the tick line named the home the reader had just moved away from,
    // for as long as the newest request took.
    expect(homeShown()).toEqual(UNKNOWN);

    // Control, settled through the same helper: the newest answer still lands and applies.
    await land(() => closeAnswer.resolve(SETTINGS_KESWICK));
    expect(homeShown()).toEqual(homeOf(SETTINGS_KESWICK));
  });

  it('does not let a superseded answer stand in when the newest request fails — unknown, not the home just left', async () => {
    // The newest request's own failure is silence, exactly as before this fix: `undefined` makes no
    // claim of its own — the tick line then goes by the light — where the superseded answer would
    // name a home the reader has just left. A number guard would apply that answer, landing after
    // the failure.
    const mountAnswer = deferred();
    const closeAnswer = deferred();
    const nextClose = deferred();
    getSettings
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(closeAnswer.promise)
      .mockReturnValueOnce(nextClose.promise);

    const result = await mountAt(0);
    await dialogClosed(result, 1); // the reader moved from Morpeth to Keswick
    expect(getSettings).toHaveBeenCalledTimes(2);

    await land(() => closeAnswer.reject(new Error('502 from /api/user/settings')));
    expect(homeShown()).toEqual(UNKNOWN);

    await land(() => mountAnswer.resolve(SETTINGS_MORPETH));

    // Still unknown. Broken, the pre-move answer filled in: "Home · Morpeth" for a reader who had
    // just moved to Keswick, on nothing more than a superseded answer.
    expect(homeShown()).toEqual(UNKNOWN);

    // Control, settled through the same helper: the next close's answer lands and applies.
    await dialogClosed(result, 2);
    await land(() => nextClose.resolve(SETTINGS_KESWICK));
    expect(homeShown()).toEqual(homeOf(SETTINGS_KESWICK));
  });

  it('leaves the home unknown when the NEWEST request fails, even over an answer on screen', async () => {
    // The catch body's own policy, which no guard test reaches: a failed request is no evidence the
    // home is unchanged — the close may have moved it — so both fields go to `undefined`, as they
    // always have, rather than keeping the answer before. (The reach catch keeps its figures
    // instead; which is right there is an open decision, so that one is deliberately not pinned.)
    const closeAnswer = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_MORPETH)
      .mockReturnValueOnce(closeAnswer.promise);

    const result = await mountAt(0);
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));
    await dialogClosed(result, 1);
    expect(getSettings).toHaveBeenCalledTimes(2);
    // Nothing is cleared when the counter moves: until the close's request settles, the mount's
    // answer stands.
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));

    await land(() => closeAnswer.reject(new Error('502 from /api/user/settings')));

    expect(homeShown()).toEqual(UNKNOWN);
  });

  it('⚠️ the accepted price: a close that saved nothing still supersedes the save\'s own answer', async () => {
    // The owner's design, pinned so that changing it is a decision: the counter moves on every
    // close, saved or not, and the cleanup cannot tell the two apart. A first-run reader saves a
    // postcode and closes, then reopens the dialog and dismisses it — the "Set a postcode" nudge is
    // still up, so it looks as though the save did not take — and the save's own answer, landing
    // first, is dropped. The no-home state stands until the dismissal's request answers. A
    // request-number guard, or a rule that keeps a superseded answer newer than the one on screen,
    // would show Morpeth a round trip sooner, and fails here.
    const saveAnswer = deferred();
    const dismissAnswer = deferred();
    getSettings
      .mockResolvedValueOnce(SETTINGS_NO_HOME)
      .mockReturnValueOnce(saveAnswer.promise)
      .mockReturnValueOnce(dismissAnswer.promise);

    const result = await mountAt(0);
    expect(homeShown()).toEqual(homeOf(SETTINGS_NO_HOME));
    await dialogClosed(result, 1); // saved Morpeth
    await dialogClosed(result, 2); // reopened and dismissed, nothing saved
    expect(getSettings).toHaveBeenCalledTimes(3);

    await land(() => saveAnswer.resolve(SETTINGS_MORPETH));
    // Dropped, though it was right: still the no-home answer the mount applied.
    expect(homeShown()).toEqual(homeOf(SETTINGS_NO_HOME));

    // Control, settled through the same helper: the dismissal's own request carries the same saved
    // home, and applies.
    await land(() => dismissAnswer.resolve(SETTINGS_MORPETH));
    expect(homeShown()).toEqual(homeOf(SETTINGS_MORPETH));
  });
});
