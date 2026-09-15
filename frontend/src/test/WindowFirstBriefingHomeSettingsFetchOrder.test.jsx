/**
 * `WindowFirstBriefingProvider` — the reach on screen answers the newest request made for it, and a
 * request a newer change to the home or its drive times has superseded writes nothing, whenever it
 * lands.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>`GET /api/user/settings/reach` is asked on mount and again whenever the home counter
 * (`homeSettingsVersion`) or the drive-time counter (`driveTimesVersion`) moves — which `App`'s
 * `useReaderSettings` does when the settings dialog's answers change the home or its drive times: a
 * saved postcode, a recalculation, or a change the dialog's own read finds. The effect had no
 * cleanup, so with two requests out at once (the mount's own and a change's, or two changes' — a
 * move, then a recalculation — on a slow connection) the one that LANDED last won, not the one ASKED
 * last: an older answer put drive times measured before the latest change back on every spot — or,
 * from before a first postcode was saved, took every reach line away again.
 *
 * <p>The provider used to read `GET /api/user/settings` on the same counter, for the tick line's home
 * and the Coming up latch, with the same race. That read is gone: both arrive as props from
 * `useReaderSettings`, the page's one record of the reader's settings, which reads once on mount and
 * then takes the dialog's answers (`App.test.jsx`, `useReaderSettings.test.jsx`).
 *
 * <p>⚠️ Which form a reader met depends on the engine and on how long the superseded request stayed
 * out (measured 2026-09-14 with a local probe on this path's real headers). WebKit and Firefox send
 * both requests at once and the second can overtake the first, so there the superseded answer can
 * land LAST and stay. Chromium's HTTP cache lock holds a second request to the same URL until the
 * first is answered or the second has waited 20 s, so there it lands FIRST and stands in for a round
 * trip, and can land LAST after that — which is why the "lands FIRST" test is no corner case.
 *
 * <h2>Why the effect cleanup, and which tests say so</h2>
 *
 * <p>This is the other shape of the race the polled briefing and ratings fetches have. A poll re-asks
 * the SAME question, so an older answer landing on its own is still the freshest there is, and the
 * right guard there drops only what is older than the answer already applied. Here a newer request
 * follows a change to the home or its drive times, so the older one answers a question that has since
 * changed — it is superseded outright, and dropped wherever it lands. The two rules part company
 * wherever the superseded request settles while nothing newer than it has been applied: its answer
 * landing FIRST, its answer landing after the newest request FAILED, and its failure landing first
 * (the `.catch` writes: a failed refetch empties the map, an owner decision of 2026-09-15 — it used
 * to keep the old figures, after a move the old house's). A guard that drops only what is older than
 * the applied answer lets all three through; each has a test here.
 *
 * <p>⚠️ Dropping is only right while every move of a counter is a real change, and it was not
 * always: the one counter there was used to move on every close of the dialog, saved or not, so a
 * close that saved nothing superseded a save's own correct answer. The counters now move only when an
 * answer differs from the record (pinned in `useReaderSettings.test.jsx` and `App.test.jsx`).
 *
 * <h2>Why a probe, not a real consumer</h2>
 *
 * <p>The provider has no single natural consumer, and this fetch least of all: the reach map reaches
 * the spot strip, the leave-by lines and the map pane — a whole tab behind lazy chunks. The probe
 * prints exactly the value this fetch writes, so which answer is on screen is legible without
 * rendering a tab. The API modules are mocked at the boundary the frontend test standards prescribe.
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

/** A probe rather than a real consumer — see the file comment for why. It prints the reach map. */
function Probe() {
  const { reachById } = useWindowFirstBriefing();
  return (
    <span data-testid="reach">
      {[...reachById.entries()]
        .map(([id, v]) => `${id}=${v.driveMinutes}/${v.distanceMiles}`)
        .join(' ') || 'none'}
    </span>
  );
}

const tree = ({ home = 0, drives = 0 }) => (
  <WindowFirstBriefingProvider homeSettingsVersion={home} driveTimesVersion={drives}>
    <Probe />
  </WindowFirstBriefingProvider>
);

/** Mounts the tree at the two counters' values and lets any request answered up front land. */
async function mountAt(counters = {}) {
  let result;
  await act(async () => { result = render(tree(counters)); });
  return result;
}

/**
 * `App`'s record has changed — a saved postcode moves the home counter, a recalculation the
 * drive-time counter — and it re-renders the provider WITHOUT remounting it (the settings dialog is
 * its sibling), so the reach effect re-runs and asks again.
 */
async function changed(result, counters) {
  await act(async () => { result.rerender(tree(counters)); });
}

/** What {@link Probe} prints for a reach answer. */
const reachShown = (answer) => answer.map((e) => `${e.locationId}=${e.driveMinutes}/${e.distanceMiles}`).join(' ');

beforeEach(() => {
  localStorage.clear();
  // Reset, not cleared: `mockReturnValueOnce` queues survive `vi.clearAllMocks()`, so a test that
  // failed with a request still queued would hand it to the next test's mount. Each test queues
  // every answer it expects and asserts how many requests went out, so an unexpected one fails the
  // test rather than landing silently.
  getDailyBriefing.mockReset().mockResolvedValue(null);
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getReach.mockReset();
  getSettings.mockReset();
  fetchRegions.mockReset().mockResolvedValue([]);
  fetchRegionDriveTimes.mockReset().mockResolvedValue({});
});

describe('WindowFirstBriefingProvider — reach answers the newest request, not the last to land', () => {
  it('asks again when only the drive-time counter moves — a recalculation', async () => {
    getReach
      .mockResolvedValueOnce(REACH_KESWICK_BEFORE_RECALCULATION)
      .mockResolvedValueOnce(REACH_FROM_KESWICK);

    const result = await mountAt({ home: 1 });
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_KESWICK_BEFORE_RECALCULATION));

    await changed(result, { home: 1, drives: 1 });

    // Keyed on the home counter alone, the recalculated drive times never arrived.
    expect(getReach).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('does not ask again on a re-render that moves neither counter', async () => {
    getReach.mockResolvedValue(REACH_FROM_MORPETH);
    const result = await mountAt({ home: 3, drives: 2 });

    await changed(result, { home: 3, drives: 2 });

    expect(getReach).toHaveBeenCalledTimes(1);
  });

  it('drops the mount\'s answer when it lands after a saved postcode\'s — the reach lines stay', async () => {
    // A first-run reader saves a postcode while the mount's request, asked before there was one, is
    // still out — and that one lands last.
    const mountAnswer = deferred();
    const saveAnswer = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(saveAnswer.promise);

    const result = await mountAt();
    await changed(result, { home: 1 });
    // Both requests really are out at once — without that there is no race to lose.
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => saveAnswer.resolve(REACH_FROM_MORPETH));
    // Control: the newest answer is applied.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));

    await land(() => mountAnswer.resolve(REACH_BEFORE_A_POSTCODE));

    // Still the newest. Broken, the pre-postcode answer came back and every reach line went absent
    // again — the setting "appeared to do nothing", the defect the counters exist to cure.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
  });

  it('drops the move\'s answer when it lands after the recalculation\'s — the drive times stay recalculated', async () => {
    // The other source of a superseded request: not the mount's, but an earlier change's. The reader
    // moves home and at once recalculates their drive times, inside one slow round trip — and the
    // move's answer, from before the recalculation, lands last.
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getReach
      .mockResolvedValueOnce(REACH_FROM_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt();
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
    await changed(result, { home: 1 }); // moved to Keswick
    await changed(result, { home: 1, drives: 1 }); // recalculated the drive times from it
    expect(getReach).toHaveBeenCalledTimes(3);
    // Nothing is cleared when a counter moves: until an answer lands, the mount's figures stand.
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

  it('drops a superseded answer even when it lands FIRST — it answers a question that has changed', async () => {
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

    const result = await mountAt();
    await changed(result, { home: 1 }); // the reader moved from Morpeth to Keswick
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

    const result = await mountAt();
    await changed(result, { home: 1 }); // the reader moved from Morpeth to Keswick
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => moveAnswer.reject(new Error('502 from /api/user/settings/reach')));
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    await land(() => mountAnswer.resolve(REACH_FROM_MORPETH));

    // Still nothing. Broken, Morpeth's drive times filled in for Keswick's.
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    // Control, settled through the same helper: the next change's answer lands and applies. Without
    // it this test could not tell a dropped answer from a helper that never let one land.
    await changed(result, { home: 1, drives: 1 }); // recalculated the drive times
    await land(() => recalcAnswer.resolve(REACH_FROM_KESWICK));
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('empties the map when the NEWEST request fails, even over an answer on screen', async () => {
    // A failed refetch empties rather than keeps (an owner decision, 2026-09-15): the counters move
    // only on a real change, so the figures on screen measure a journey that has changed — after a
    // move, from the old house. Empty claims no drive; they would claim one the reader no longer
    // has.
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getReach
      .mockResolvedValueOnce(REACH_FROM_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt();
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
    await changed(result, { home: 1 }); // the reader moved from Morpeth to Keswick
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => moveAnswer.reject(new Error('502 from /api/user/settings/reach')));

    // Empty. It used to keep Morpeth's drive times and leave-by lines beside the tick line's
    // Keswick.
    expect(screen.getByTestId('reach')).toHaveTextContent('none');

    // Control, settled through the same helper: the next change's answer fills the map again.
    await changed(result, { home: 1, drives: 1 }); // recalculated the drive times
    await land(() => recalcAnswer.resolve(REACH_FROM_KESWICK));
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('keeps the answer on screen when a superseded request FAILS first — only the newest failure empties', async () => {
    // The third place the rules part company. The move's request is superseded by the
    // recalculation's while both are out; its failure is not the newest request failing, so the
    // mount's figures stand until the newest settles — nothing is cleared when a counter merely
    // moves. A number guard, with nothing newer applied yet, would let it empty the map.
    const moveAnswer = deferred();
    const recalcAnswer = deferred();
    getReach
      .mockResolvedValueOnce(REACH_FROM_MORPETH)
      .mockReturnValueOnce(moveAnswer.promise)
      .mockReturnValueOnce(recalcAnswer.promise);

    const result = await mountAt();
    await changed(result, { home: 1 }); // moved to Keswick
    await changed(result, { home: 1, drives: 1 }); // recalculated the drive times from it
    expect(getReach).toHaveBeenCalledTimes(3);

    await land(() => moveAnswer.reject(new Error('Network Error')));

    // Unchanged. Broken, the map went empty while the newest request was still on its way.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));

    // Control, settled through the same helper: the newest answer lands and applies.
    await land(() => recalcAnswer.resolve(REACH_FROM_KESWICK));
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_KESWICK));
  });

  it('keeps the newest answer when a superseded request FAILS after it', async () => {
    // The guard on the `.catch`, load-bearing because it writes: a failed refetch empties the map,
    // so without the guard a superseded failure landing after the newest answer would wipe it.
    const mountAnswer = deferred();
    const saveAnswer = deferred();
    getReach
      .mockReturnValueOnce(mountAnswer.promise)
      .mockReturnValueOnce(saveAnswer.promise);

    const result = await mountAt();
    await changed(result, { home: 1 });
    expect(getReach).toHaveBeenCalledTimes(2);

    await land(() => saveAnswer.resolve(REACH_FROM_MORPETH));
    // Control: the newest answer is applied.
    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));

    await land(() => mountAnswer.reject(new Error('Network Error')));

    expect(screen.getByTestId('reach')).toHaveTextContent(reachShown(REACH_FROM_MORPETH));
  });

  it('reads no settings of its own — the home arrives from App\'s record', async () => {
    // It used to read `GET /api/user/settings` on the same counter, beside App's own read of the
    // same endpoint for the map's home — two answers to one question, which a failure of either
    // alone split.
    getReach.mockResolvedValue(REACH_FROM_MORPETH);
    const result = await mountAt();
    await changed(result, { home: 1 });

    expect(getReach).toHaveBeenCalledTimes(2); // control: the counter did reach the provider
    expect(getSettings).not.toHaveBeenCalled();
  });
});
