/**
 * `WindowFirstBriefingProvider` — the briefing and the ratings are each applied in the order their
 * requests were made, not the order they land.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The provider asks `GET /api/briefing` and `GET /api/briefing/evaluate/scores` on mount, on a
 * ten-minute poll and on every window focus, and used to publish whatever answered. With two
 * requests out at once — a poll and a focus, or two focuses — the answer that LANDED last won, not
 * the one ASKED last. Two requests finish in whatever order their server work does, and the
 * briefing's is real work (assembled at serve time: hot topics recomputed live, the cached ratings
 * re-enriched), so the older one can land second:
 *
 * <ul>
 *   <li>An older briefing put the older windows back on every card — a "Worth it" the forecast has
 *       since withdrawn, or a "Poor" it has since lifted — and was WRITTEN TO THE SWR CACHE, so the
 *       next cold start painted it first.</li>
 *   <li>Older ratings put the older rows back under the heat field: a re-scored window back at its
 *       old rating, or a window the batch had just rated blank again.</li>
 * </ul>
 *
 * <h2>Why a probe, not a real consumer</h2>
 *
 * <p>The provider has no single natural consumer: the shell, the map pane, the regional panel and
 * the doors each read a different slice of it, and the shell is a whole tab behind lazy chunks. The
 * probe prints the slices the ordering guards protect — the briefing (its build time, and the
 * verdict words the heat strip derives from it) and both shapes of the ratings (the name-keyed map
 * the map handoff and the grid read, and the raw rows the heat field is joined from) — so which answer
 * is on screen is legible without rendering a tab. It leaves out `loading` and `scoresLoaded`, which
 * the same fetches write: a dropped answer can only find those already settled, by the newer answer
 * that was applied. The API modules are mocked at the boundary the frontend test standards prescribe,
 * and the SWR cache is the real one.
 *
 * <p>Its own file, like the map's fetch-race tests, rather than more of
 * `WindowFirstBriefingContext.test.jsx`: that file's clock advances with real time
 * (`shouldAdvanceTime`), where these tests want intervals that fire only when a test says so.
 */
import React, { StrictMode } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
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
import { clearSwrCache, readSwrCache } from '../utils/swrCache.js';

/** The provider's poll. Not exported by it; a changed cadence fails the poll tests' call counts. */
const POLL_INTERVAL_MS = 10 * 60 * 1000;

/** The SWR entry a PRO reader's briefing is cached under — `briefing:<role>`. */
const CACHE_KEY = 'briefing:PRO_USER';

/**
 * The instant every test runs at. The fixture's one window is this date's 20:11Z sunset, so it is
 * unambiguously ahead and the strip draws it; a wall clock past 20:41Z would have the provider's
 * stale-cache pastness filter drop it, and every verdict below would read `none`.
 */
const NOW = new Date('2026-08-04T09:00:00Z');

/** The London date of {@link NOW}, stated rather than read from a clock. */
const TODAY = '2026-08-04';

/** The one region both payloads describe — the briefing's windows and the ratings' rows. */
const REGION = 'Northumberland & Tyneside';

/**
 * A briefing as `GET /api/briefing` serves one — built at `builtAt`, its one window carrying
 * `verdict`. The build time is how a test names the answer on screen; the verdict is what a reader
 * would act on.
 *
 * @param {string} builtAt time of day, `HH:MM`
 * @param {string} verdict the window's served `DisplayVerdict`
 */
function briefing(builtAt, verdict) {
  const eventTime = `${TODAY}T20:11:00`;
  return {
    generatedAt: `${TODAY}T${builtAt}:00`,
    days: [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: REGION,
          displayVerdict: verdict,
          verdict: 'GO',
          summary: '',
          slots: [{
            locationId: 7, locationName: 'Bamburgh', solarEventTime: eventTime, claudeRating: 4,
          }],
        }],
        unregioned: [],
        window: { verdict, badges: [], eventTime },
      }],
    }],
    renderedEvents: [{ date: TODAY, targetType: 'SUNSET' }],
  };
}

/**
 * One row of `GET /api/briefing/evaluate/scores` — a location's rating for one window.
 *
 * @param {number} rating the batch rating
 * @param {object} [window] which window it rates; today's sunset unless stated
 */
function row(rating, { date = TODAY, targetType = 'SUNSET' } = {}) {
  return {
    regionName: REGION,
    locationId: 7,
    locationName: 'Bamburgh',
    date,
    targetType,
    rating,
    fierySkyPotential: rating * 20,
    goldenHourPotential: rating * 20,
    summary: `Rated ${rating}`,
  };
}

/** A request the test settles by hand, so the test — not the scheduler — decides which lands first. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Settles a hand-held request inside an AWAITED `act`, which is what makes a negative safe to
 * assert: the provider's continuation has run and its update has committed before the test resumes,
 * so an answer that is not on screen (or in the cache) afterwards was dropped, not merely still on
 * its way. Every positive control below settles through this same helper, so a helper that stopped
 * flushing would fail at the control rather than let a negative pass (frontend test standards, "A
 * late response is only 'dropped' once it has landed").
 */
async function land(settle) {
  await act(async () => { settle(); });
}

/**
 * A probe rather than a real consumer — see the file comment for why. It prints the slices the
 * ordering guards protect, and nothing else.
 */
function Probe() {
  const {
    briefing: shown, heatStripCards, evaluationScores, scoreRows,
  } = useWindowFirstBriefing();
  return (
    <div>
      <span data-testid="built">{shown?.generatedAt ?? 'none'}</span>
      <span data-testid="verdicts">{heatStripCards.map((c) => c.verdictLabel).join('|') || 'none'}</span>
      <span data-testid="ratings">
        {[...evaluationScores.entries()].map(([key, v]) => `${key}=${v.rating}`).join(' ') || 'none'}
      </span>
      <span data-testid="rows">
        {scoreRows.map((r) => `${r.date}:${r.targetType}=${r.rating}`).join(' ') || 'none'}
      </span>
    </div>
  );
}

const tree = () => (
  <WindowFirstBriefingProvider>
    <Probe />
  </WindowFirstBriefingProvider>
);

/** Mounts the tree and lets the mount's own requests — answered up front by each test — land. */
async function mount() {
  await act(async () => { render(tree()); });
}

/** The page regains focus: the provider asks for the briefing and the ratings again. */
async function refocus() {
  await act(async () => { window.dispatchEvent(new Event('focus')); });
}

/** The provider's ten-minute poll comes round — the same two requests again. */
async function poll() {
  await act(async () => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
}

/** The briefing on screen: which build, and the verdict word the strip prints for its window. */
const briefingShown = () => ({
  built: screen.getByTestId('built').textContent,
  verdicts: screen.getByTestId('verdicts').textContent,
});

/** The build time of the briefing the SWR cache holds — what the next cold start would paint. */
const cachedBuild = () => readSwrCache(CACHE_KEY)?.generatedAt ?? 'none';

/** The ratings on screen, in both of the shapes the provider publishes them in. */
const ratingsShown = () => ({
  ratings: screen.getByTestId('ratings').textContent,
  rows: screen.getByTestId('rows').textContent,
});

/** What {@link ratingsShown} reads for a set of rows — one entry per window rated. */
const expected = (...rows) => ({
  ratings: rows.map((r) => `${REGION}|${r.date}|${r.targetType}|Bamburgh=${r.rating}`).join(' '),
  rows: rows.map((r) => `${r.date}:${r.targetType}=${r.rating}`).join(' '),
});

beforeEach(() => {
  localStorage.clear();
  // Intervals and the clock only: the poll fires when a test says so and not otherwise, and the
  // clock is pinned. Every wait in this file is an awaited `act`, never a `findBy*` or `waitFor`,
  // whose own polling runs on `setInterval`.
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
  vi.setSystemTime(NOW);
  // Reset, not cleared: `mockReturnValueOnce` queues survive `vi.clearAllMocks()`, so a test that
  // failed with a request still queued would hand it to the next test's mount. Each test queues
  // every answer it expects from the fetch under test; one it does not expect answers `undefined`
  // and fails the call-count preconditions below rather than landing silently.
  getDailyBriefing.mockReset();
  getAllEvaluationScores.mockReset();
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getReach.mockReset().mockResolvedValue([]);
  getSettings.mockReset().mockResolvedValue({ homePostcode: null, homePlaceName: null });
  fetchRegions.mockReset().mockResolvedValue([]);
  fetchRegionDriveTimes.mockReset().mockResolvedValue({});
});

afterEach(() => {
  vi.useRealTimers();
});

describe('WindowFirstBriefingProvider — briefings are applied in the order they were asked for', () => {
  beforeEach(() => {
    // The ratings are not under test here: every ratings request answers at once, with no rows.
    getAllEvaluationScores.mockResolvedValue([]);
  });

  it('drops an older briefing that lands after a newer one — a withdrawn "Worth it" stays withdrawn', async () => {
    // The poll-and-focus race: the poll asks while the window reads Worth it, the next build stands
    // it down, a focus asks again — and the poll's answer, held up in the serve-time assembly,
    // lands last.
    const pollAnswer = deferred();
    const focusAnswer = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(pollAnswer.promise)
      .mockReturnValueOnce(focusAnswer.promise);

    await mount();
    expect(briefingShown()).toEqual({ built: `${TODAY}T06:00:00`, verdicts: 'Maybe' });

    await poll();
    await refocus();
    // Both requests really are out at once — without that there is no race to lose.
    expect(getDailyBriefing).toHaveBeenCalledTimes(3);

    await land(() => focusAnswer.resolve(briefing('18:00', 'STAND_DOWN')));
    // Control: the newer briefing is applied.
    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Poor' });

    await land(() => pollAnswer.resolve(briefing('12:00', 'WORTH_IT')));

    // Still the newer one. Broken, the withdrawn "Worth it" came back on the card until the next
    // poll or focus — an evening out on a window the forecast had already stood down.
    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Poor' });
  });

  it('drops an older briefing that lands after a newer one — a lifted "Poor" stays lifted', async () => {
    // The other direction, from two focuses: the first asks before a build lifts the window, the
    // second after, and the first one's answer lands last.
    const earlier = deferred();
    const later = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(3);

    await land(() => later.resolve(briefing('18:00', 'WORTH_IT')));
    // Control: the newer briefing is applied.
    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Worth it' });

    await land(() => earlier.resolve(briefing('12:00', 'STAND_DOWN')));

    // Still the newer one. Broken, the card went back to "Poor" — a reader stood down from an
    // evening the forecast had since said was worth it.
    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Worth it' });
  });

  it('never writes a dropped briefing to the SWR cache — the next cold start paints the newer one', async () => {
    // The half of the defect that outlived the page: the late briefing was cached as well as
    // shown, and the next mount paints the cache before its own request lands.
    const pollAnswer = deferred();
    const focusAnswer = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(pollAnswer.promise)
      .mockReturnValueOnce(focusAnswer.promise);

    await mount();
    expect(cachedBuild()).toBe(`${TODAY}T06:00:00`);

    await poll();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(3);

    await land(() => focusAnswer.resolve(briefing('18:00', 'STAND_DOWN')));
    // Control: an APPLIED briefing is cached — the guard must not cost the cache its writes.
    expect(cachedBuild()).toBe(`${TODAY}T18:00:00`);

    await land(() => pollAnswer.resolve(briefing('12:00', 'WORTH_IT')));

    // Still the newer build. Broken, the late one replaced it in the cache.
    expect(cachedBuild()).toBe(`${TODAY}T18:00:00`);
  });

  it('applies briefings that land in the order they were asked for — each one replaces the last, in the cache too', async () => {
    // The guard drops only what is OLDER than the answer on screen. An older answer landing while a
    // newer request is still out is applied: it is the freshest briefing there is so far.
    const earlier = deferred();
    const later = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(3);

    await land(() => earlier.resolve(briefing('12:00', 'WORTH_IT')));
    expect(briefingShown()).toEqual({ built: `${TODAY}T12:00:00`, verdicts: 'Worth it' });
    expect(cachedBuild()).toBe(`${TODAY}T12:00:00`);

    await land(() => later.resolve(briefing('18:00', 'STAND_DOWN')));
    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Poor' });
    expect(cachedBuild()).toBe(`${TODAY}T18:00:00`);
  });

  it('still applies an older briefing when the newer request fails — a failure blocks nothing', async () => {
    // Only an APPLIED answer moves the mark. Were every request made to move it — the rule that
    // lets only the most recent request write — the failed newer request would have the older
    // answer dropped too, leaving the page on a briefing older than it needed to be.
    const earlier = deferred();
    const later = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(3);

    await land(() => later.reject(new Error('502 from /api/briefing')));
    // Control: the failure has landed, and nothing on screen or in the cache moved for it.
    expect(briefingShown()).toEqual({ built: `${TODAY}T06:00:00`, verdicts: 'Maybe' });
    expect(cachedBuild()).toBe(`${TODAY}T06:00:00`);

    await land(() => earlier.resolve(briefing('12:00', 'WORTH_IT')));

    expect(briefingShown()).toEqual({ built: `${TODAY}T12:00:00`, verdicts: 'Worth it' });
    expect(cachedBuild()).toBe(`${TODAY}T12:00:00`);
  });

  it('still applies an older briefing when the newer answer is empty — a 204 is not an answer to apply', async () => {
    // `/api/briefing` answers 204 while nothing is built server-side, and the provider ignores that
    // null rather than storing it. So a null is not APPLIED, and must not move the mark either: an
    // older real briefing landing after it is still the freshest briefing there is.
    const earlier = deferred();
    const later = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(3);

    await land(() => later.resolve(null));
    // Control: the null has landed, and — as before this fix — it blanked nothing and cached nothing.
    expect(briefingShown()).toEqual({ built: `${TODAY}T06:00:00`, verdicts: 'Maybe' });
    expect(cachedBuild()).toBe(`${TODAY}T06:00:00`);

    await land(() => earlier.resolve(briefing('12:00', 'WORTH_IT')));

    expect(briefingShown()).toEqual({ built: `${TODAY}T12:00:00`, verdicts: 'Worth it' });
    expect(cachedBuild()).toBe(`${TODAY}T12:00:00`);
  });

  it('drops an older serve of the same build — request order decides, not build time', async () => {
    // Two serves of one build can differ: the window projection, the picks and each region's
    // confidence are derived per request, which is why the provider's memos key on the briefing
    // object and not on `generatedAt`. So `generatedAt` cannot order two answers, and the guard does
    // not try: both answers here carry the 18:00 build, and the older request's, landing last, is
    // dropped.
    const earlier = deferred();
    const later = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(3);

    await land(() => later.resolve(briefing('18:00', 'STAND_DOWN')));
    // Control: the newer serve is applied.
    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Poor' });

    await land(() => earlier.resolve(briefing('18:00', 'WORTH_IT')));

    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Poor' });
  });

  it('applies a newer briefing while a still newer request is out — the mark follows what was applied', async () => {
    // Three requests out at once: a poll and two focuses. The oldest lands, then the middle one,
    // which is newer than what is on screen and so is applied — and cached — although the newest is
    // still out. Were the mark moved to the newest request MADE whenever an answer is applied, the
    // oldest landing would push it past the middle one, which would then be dropped; and with the
    // newest failing, the page would sit on the oldest briefing until the next poll.
    const oldest = deferred();
    const middle = deferred();
    const newest = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(oldest.promise)
      .mockReturnValueOnce(middle.promise)
      .mockReturnValueOnce(newest.promise);

    await mount();
    await poll();
    await refocus();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(4);

    await land(() => oldest.resolve(briefing('12:00', 'WORTH_IT')));
    // Control: the oldest answer is still newer than the mount's, so it is applied.
    expect(briefingShown()).toEqual({ built: `${TODAY}T12:00:00`, verdicts: 'Worth it' });

    await land(() => middle.resolve(briefing('15:00', 'STAND_DOWN')));
    expect(briefingShown()).toEqual({ built: `${TODAY}T15:00:00`, verdicts: 'Poor' });
    expect(cachedBuild()).toBe(`${TODAY}T15:00:00`);

    await land(() => newest.reject(new Error('502 from /api/briefing')));
    expect(briefingShown()).toEqual({ built: `${TODAY}T15:00:00`, verdicts: 'Poor' });
    expect(cachedBuild()).toBe(`${TODAY}T15:00:00`);
  });

  it('keeps the briefing it has, cache included, when a refresh fails', async () => {
    // The behaviour this fix had to keep: the failure path writes nothing.
    const failing = deferred();
    const next = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'WORTH_IT'))
      .mockReturnValueOnce(failing.promise)
      .mockReturnValueOnce(next.promise);

    await mount();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(2);
    await land(() => failing.reject(new Error('Network Error')));

    // Still the briefing it had, in both places.
    expect(briefingShown()).toEqual({ built: `${TODAY}T06:00:00`, verdicts: 'Worth it' });
    expect(cachedBuild()).toBe(`${TODAY}T06:00:00`);

    // Control, settled through the same helper: the next answer still lands and applies. Without
    // it this test could not tell a swallowed failure from a helper that never let the failure land.
    await refocus();
    await land(() => next.resolve(briefing('18:00', 'STAND_DOWN')));
    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Poor' });
    expect(cachedBuild()).toBe(`${TODAY}T18:00:00`);
  });

  it('under StrictMode, drops the first run\'s briefing when it lands after the second run\'s', async () => {
    // Development only: StrictMode runs the fetch effect twice on mount, so every dev load makes two
    // briefing requests at once. The numbering has to span both runs — held per run, the first
    // run's answer would be judged against its own count and replace the second's.
    const firstRun = deferred();
    const secondRun = deferred();
    getDailyBriefing
      .mockReturnValueOnce(firstRun.promise)
      .mockReturnValueOnce(secondRun.promise);

    await act(async () => {
      render(<StrictMode>{tree()}</StrictMode>);
    });
    // StrictMode really did run the effect twice — without that this test proves nothing.
    expect(getDailyBriefing).toHaveBeenCalledTimes(2);

    await land(() => secondRun.resolve(briefing('18:00', 'STAND_DOWN')));
    // Control: the second run's answer is applied and cached.
    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Poor' });
    expect(cachedBuild()).toBe(`${TODAY}T18:00:00`);

    await land(() => firstRun.resolve(briefing('12:00', 'WORTH_IT')));

    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Poor' });
    expect(cachedBuild()).toBe(`${TODAY}T18:00:00`);
  });

  it('never caches a briefing that was in flight across a logout — the ordering leaves that rule alone', async () => {
    // The provider's logout-race rule, which the ordering guard had to compose with rather than
    // replace: the cache generation is taken BEFORE the await, so a briefing requested before
    // `logout` swept the cache is refused by it even though it is the newest answer there is.
    const inFlight = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(inFlight.promise);

    await mount();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(2);

    clearSwrCache(); // `logout`'s sweep, while the refresh is still out
    expect(cachedBuild()).toBe('none');

    await land(() => inFlight.resolve(briefing('18:00', 'STAND_DOWN')));

    // Control: it landed, and as the newest answer it was applied to the page — which `logout`
    // unmounts a moment later, so that costs nothing.
    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Poor' });
    // But it belongs to the session that just ended, so it is never written back.
    expect(cachedBuild()).toBe('none');
  });
});

describe('WindowFirstBriefingProvider — ratings are applied in the order they were asked for', () => {
  beforeEach(() => {
    // The briefing is not under test here: every briefing request answers at once, with one build.
    getDailyBriefing.mockResolvedValue(briefing('06:00', 'MAYBE'));
  });

  it('drops older ratings that land after newer ones — a re-scored window keeps its new rating', async () => {
    // The poll-and-focus race again, on the ratings request the same beat sends.
    const pollAnswer = deferred();
    const focusAnswer = deferred();
    getAllEvaluationScores
      .mockResolvedValueOnce([row(3)])
      .mockReturnValueOnce(pollAnswer.promise)
      .mockReturnValueOnce(focusAnswer.promise);

    await mount();
    expect(ratingsShown()).toEqual(expected(row(3)));

    await poll();
    await refocus();
    expect(getAllEvaluationScores).toHaveBeenCalledTimes(3);

    await land(() => focusAnswer.resolve([row(2)]));
    // Control: the newer rating is applied, in both shapes.
    expect(ratingsShown()).toEqual(expected(row(2)));

    await land(() => pollAnswer.resolve([row(4)]));

    // Still the newer rating. Broken, the window went back to its old 4 under the field and in the
    // map handoff until the next poll or focus.
    expect(ratingsShown()).toEqual(expected(row(2)));
  });

  it('drops an answer from before a window was rated — the newly rated window keeps its rows', async () => {
    // The other direction, from two focuses: the batch rates tomorrow's sunrise between them, and
    // the first one's answer — which predates that — lands last. This is the heat field's own
    // failure shape: a card saying `best spot 5★` over a blank thumbnail.
    const earlier = deferred();
    const later = deferred();
    const tomorrowSunrise = row(5, { date: '2026-08-05', targetType: 'SUNRISE' });
    getAllEvaluationScores
      .mockResolvedValueOnce([row(3)])
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getAllEvaluationScores).toHaveBeenCalledTimes(3);

    await land(() => later.resolve([row(3), tomorrowSunrise]));
    // Control: the newer answer is applied — both windows rated.
    expect(ratingsShown()).toEqual(expected(row(3), tomorrowSunrise));

    await land(() => earlier.resolve([row(3)]));

    // Still both. Broken, tomorrow's sunrise lost its rating again until the next poll or focus.
    expect(ratingsShown()).toEqual(expected(row(3), tomorrowSunrise));
  });

  it('applies ratings that land in the order they were asked for — each moves the rows on', async () => {
    const earlier = deferred();
    const later = deferred();
    getAllEvaluationScores
      .mockResolvedValueOnce([row(3)])
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getAllEvaluationScores).toHaveBeenCalledTimes(3);

    await land(() => earlier.resolve([row(4)]));
    expect(ratingsShown()).toEqual(expected(row(4)));

    await land(() => later.resolve([row(2)]));
    expect(ratingsShown()).toEqual(expected(row(2)));
  });

  it('still applies older ratings when the newer request fails — a failure blocks nothing', async () => {
    const earlier = deferred();
    const later = deferred();
    getAllEvaluationScores
      .mockResolvedValueOnce([row(3)])
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getAllEvaluationScores).toHaveBeenCalledTimes(3);

    await land(() => later.reject(new Error('502 from /api/briefing/evaluate/scores')));
    // Control: the failure has landed, and the rows did not move for it.
    expect(ratingsShown()).toEqual(expected(row(3)));

    await land(() => earlier.resolve([row(4)]));

    expect(ratingsShown()).toEqual(expected(row(4)));
  });

  it('still applies older ratings when the newer answer is empty — an empty answer is not applied, so it blocks nothing', async () => {
    // An empty response leaves the rows in place — "a dropped request is not evidence that the
    // ratings went away" — so it is not APPLIED to them, and must not move the mark either. Were it
    // to, the order the two answers landed in would decide the rows: empty-then-older would keep
    // the rows from before both, older-then-empty the older answer's.
    const earlier = deferred();
    const later = deferred();
    getAllEvaluationScores
      .mockResolvedValueOnce([row(3)])
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getAllEvaluationScores).toHaveBeenCalledTimes(3);

    await land(() => later.resolve([]));
    // Control: the empty answer has landed, and withdrew nothing.
    expect(ratingsShown()).toEqual(expected(row(3)));

    await land(() => earlier.resolve([row(4)]));

    expect(ratingsShown()).toEqual(expected(row(4)));
  });

  it('applies newer ratings while a still newer request is out — the mark follows what was applied', async () => {
    // The three-request case again, on the ratings' own numbering: the middle answer is newer than
    // the rows on screen, so it is applied although the newest request is still out, and it stands
    // when that request fails.
    const oldest = deferred();
    const middle = deferred();
    const newest = deferred();
    getAllEvaluationScores
      .mockResolvedValueOnce([row(3)])
      .mockReturnValueOnce(oldest.promise)
      .mockReturnValueOnce(middle.promise)
      .mockReturnValueOnce(newest.promise);

    await mount();
    await poll();
    await refocus();
    await refocus();
    expect(getAllEvaluationScores).toHaveBeenCalledTimes(4);

    await land(() => oldest.resolve([row(4)]));
    // Control: the oldest answer is still newer than the mount's, so it is applied.
    expect(ratingsShown()).toEqual(expected(row(4)));

    await land(() => middle.resolve([row(2)]));
    expect(ratingsShown()).toEqual(expected(row(2)));

    await land(() => newest.reject(new Error('502 from /api/briefing/evaluate/scores')));
    expect(ratingsShown()).toEqual(expected(row(2)));
  });

  it('keeps the rows when a refresh fails', async () => {
    const failing = deferred();
    const next = deferred();
    getAllEvaluationScores
      .mockResolvedValueOnce([row(3)])
      .mockReturnValueOnce(failing.promise)
      .mockReturnValueOnce(next.promise);

    await mount();
    await refocus();
    expect(getAllEvaluationScores).toHaveBeenCalledTimes(2);
    await land(() => failing.reject(new Error('Network Error')));

    expect(ratingsShown()).toEqual(expected(row(3)));

    // Control, settled through the same helper: the next answer still lands and applies.
    await refocus();
    await land(() => next.resolve([row(2)]));
    expect(ratingsShown()).toEqual(expected(row(2)));
  });

  it('under StrictMode, drops the first run\'s ratings when they land after the second run\'s', async () => {
    // Each of StrictMode's two mount runs asks for the ratings once its own briefing has landed, so
    // a dev load has two ratings requests out at once as well.
    const firstRun = deferred();
    const secondRun = deferred();
    getAllEvaluationScores
      .mockReturnValueOnce(firstRun.promise)
      .mockReturnValueOnce(secondRun.promise);

    await act(async () => {
      render(<StrictMode>{tree()}</StrictMode>);
    });
    expect(getAllEvaluationScores).toHaveBeenCalledTimes(2);

    await land(() => secondRun.resolve([row(2)]));
    // Control: the second run's ratings are applied.
    expect(ratingsShown()).toEqual(expected(row(2)));

    await land(() => firstRun.resolve([row(4)]));

    expect(ratingsShown()).toEqual(expected(row(2)));
  });
});

describe('WindowFirstBriefingProvider — the briefing and the ratings are ordered separately', () => {
  it('applies a briefing whose ratings, asked for in the same refresh, landed first', async () => {
    // Two requests, two numberings. The two go out together on every refresh and land in either
    // order; were they numbered as one, the ratings landing first would count as a newer answer
    // than the briefing asked for alongside them, and that briefing would be dropped.
    const briefingAnswer = deferred();
    const ratingsAnswer = deferred();
    getDailyBriefing
      .mockResolvedValueOnce(briefing('06:00', 'MAYBE'))
      .mockReturnValueOnce(briefingAnswer.promise);
    getAllEvaluationScores
      .mockResolvedValueOnce([row(3)])
      .mockReturnValueOnce(ratingsAnswer.promise);

    await mount();
    await refocus();
    expect(getDailyBriefing).toHaveBeenCalledTimes(2);
    expect(getAllEvaluationScores).toHaveBeenCalledTimes(2);

    await land(() => ratingsAnswer.resolve([row(5)]));
    // Control: the ratings are applied.
    expect(ratingsShown()).toEqual(expected(row(5)));

    await land(() => briefingAnswer.resolve(briefing('18:00', 'WORTH_IT')));

    expect(briefingShown()).toEqual({ built: `${TODAY}T18:00:00`, verdicts: 'Worth it' });
    expect(cachedBuild()).toBe(`${TODAY}T18:00:00`);
  });
});
