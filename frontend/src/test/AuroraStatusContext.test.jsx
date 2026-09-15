/**
 * `AuroraStatusProvider` — the status every reader sees never steps back to an answer older than
 * the one already on screen, whatever order the answers land in.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The provider asks `GET /api/aurora/status` on mount, on a five-minute poll and on every window
 * focus, and used to publish whatever answered. With two requests out at once — a poll and a focus,
 * or two focuses — the answer that LANDED last won, not the one ASKED last, and nothing makes the
 * older one land first: each status request can wait on live NOAA fetches of its own.
 *
 * <ul>
 *   <li>An alert answer taken before the alert ended, landing after the all-clear, put the banner
 *       back up — and switched the viewline back on, whose endpoint does not check the alert
 *       state — until the next poll or focus.</li>
 *   <li>An all-clear taken before an alert began, landing after the alert, took the banner down
 *       mid-alert, cleared the map's live scores and, with no stored run keeping aurora mode
 *       available, bounced the Map tab to Sunset.</li>
 * </ul>
 *
 * <h2>Why the banner</h2>
 *
 * <p>`AuroraBanner` is the real consumer whose output follows the status most directly: up for
 * MODERATE or STRONG and nothing otherwise, given — as every fixture here gives it — a clear
 * location to point at and no dismissal. So whether it is up, and which alert it names, IS which
 * answer is on screen. Its viewline hook is real too, gated on the same alert level as the map's
 * own, so a status that switches the viewline back on shows up as another `getAuroraViewline`
 * request. The API module is mocked at the boundary the frontend test standards prescribe.
 */
import React, { StrictMode } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../api/auroraApi.js', () => ({
  getAuroraStatus: vi.fn(),
  getAuroraViewline: vi.fn(),
  getAuroraForecastViewline: vi.fn(),
}));

import AuroraBanner from '../components/AuroraBanner.jsx';
import { AuroraStatusProvider } from '../context/AuroraStatusContext.jsx';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { resolveAuroraNight } from '../utils/mapDates.js';
import { getAuroraStatus, getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';

/**
 * The provider's poll interval, which it does not export. Advancing by it fires one poll: it is how
 * a test polls, not a pin on the cadence.
 */
const POLL_INTERVAL_MS = 5 * 60 * 1000;

/** A running alert, as `GET /api/aurora/status` serves one: MODERATE, the state machine ACTIVE. */
const ALERT = {
  level: 'MODERATE',
  hexColour: '#ff9900',
  description: 'Moderate storm — aurora possible from northern England',
  active: true,
  darkSkyLocationCount: 12,
  clearLocationCount: 7,
  kp: 5.3,
  forecastKp: 5.3,
  triggerType: 'realtime',
  gScale: 'G1',
};

/**
 * The same endpoint once the alert is over: the machine drops to IDLE, which the controller serves
 * as QUIET with `active` false, and CLEAR zeroes the location counts. CLEAR resets neither
 * `triggerType` nor the trigger Kp served as `forecastKp`, so a real all-clear still carries the
 * ended alert's — and the `G1` the controller derives from that Kp.
 */
const ALL_CLEAR = {
  level: 'QUIET',
  hexColour: '#33ff33',
  description: 'No significant activity',
  active: false,
  darkSkyLocationCount: 0,
  clearLocationCount: null,
  kp: 2.7,
  forecastKp: 5.3,
  triggerType: 'realtime',
  gScale: 'G1',
};

/** The alert escalated: STRONG, still ACTIVE, on a higher Kp — a different banner from `ALERT`. */
const ESCALATED = {
  level: 'STRONG',
  hexColour: '#ff0000',
  description: 'Strong storm — aurora likely across the UK',
  active: true,
  darkSkyLocationCount: 12,
  clearLocationCount: 9,
  kp: 7.0,
  forecastKp: 7.0,
  triggerType: 'realtime',
  gScale: 'G3',
};

/**
 * A request the test settles by hand, so the test — not the scheduler — decides which lands first.
 */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Settles a hand-held request inside an AWAITED `act`, which is what makes a negative safe to
 * assert: the provider's continuation has run and its update has committed before the test resumes,
 * so an answer that is not on screen afterwards was dropped, not merely still on its way. Every
 * positive control below settles through this same helper, so a helper that stopped flushing would
 * fail at the control rather than let a negative pass (frontend test standards, "A late response is
 * only 'dropped' once it has landed").
 */
async function land(settle) {
  await act(async () => { settle(); });
}

/** The provider, with its real consumer under it. */
const tree = () => (
  <AuroraStatusProvider>
    <AuroraBanner />
  </AuroraStatusProvider>
);

/** Mounts the tree and lets the mount's own request — answered up front by each test — land. */
async function mount() {
  await act(async () => { render(tree()); });
}

/** The page regains focus: the provider asks for the status again. */
async function refocus() {
  await act(async () => { window.dispatchEvent(new Event('focus')); });
}

/** The provider's five-minute poll comes round. */
async function poll() {
  await act(async () => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
}

const banner = () => screen.queryByRole('alert');

beforeEach(() => {
  // Intervals only: the provider's poll, and the banner's viewline poll, fire when a test says so
  // and not otherwise. Every wait in this file is an awaited `act`, never a `findBy*` or `waitFor`,
  // whose own polling runs on `setInterval`.
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  // Reset, not cleared: `mockReturnValueOnce` queues survive `vi.clearAllMocks()`, so a test that
  // failed with a request still queued would hand it to the next test's mount. Each test queues
  // every status answer it expects. One it does not expect is refused, and a refused request writes
  // nothing, so it can never be what satisfies a negative; the call-count preconditions catch any
  // that come before them. (Left answering `undefined`, it would be APPLIED as the newest answer —
  // and take the banner down, which is exactly what several negatives below assert.)
  getAuroraStatus.mockReset();
  getAuroraStatus.mockRejectedValue(new Error('unexpected getAuroraStatus call'));
  getAuroraViewline.mockReset();
  getAuroraViewline.mockResolvedValue({ active: false });
  getAuroraForecastViewline.mockReset();
  getAuroraForecastViewline.mockResolvedValue({ active: false });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('AuroraStatusProvider — answers are applied in the order they were asked for', () => {
  it('drops an alert answer that lands after a newer all-clear — the banner stays down', async () => {
    // The poll-and-focus race: the poll asks while the alert is still running, the alert ends, a
    // focus asks again — and the poll's answer, held up on its NOAA calls, lands last.
    const pollAnswer = deferred();
    const focusAnswer = deferred();
    getAuroraStatus
      .mockResolvedValueOnce({ ...ALERT })
      .mockReturnValueOnce(pollAnswer.promise)
      .mockReturnValueOnce(focusAnswer.promise);

    await mount();
    // Control for both negatives below: an alert answer that IS applied shows — on the banner, and
    // as a request for the viewline.
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');
    expect(getAuroraViewline).toHaveBeenCalledTimes(1);

    await poll();
    await refocus();
    // Both requests really are out at once — without that there is no race to lose.
    expect(getAuroraStatus).toHaveBeenCalledTimes(3);

    await land(() => focusAnswer.resolve({ ...ALL_CLEAR }));
    // Control: the newer answer, the all-clear, is applied — the banner comes down.
    expect(banner()).not.toBeInTheDocument();
    const viewlineRequests = getAuroraViewline.mock.calls.length;

    await land(() => pollAnswer.resolve({ ...ALERT }));

    // Still down. Broken, the ended alert's status was published again: the banner back up until
    // the next poll or focus, and the viewline switched back on and asked for again.
    expect(banner()).not.toBeInTheDocument();
    expect(getAuroraViewline).toHaveBeenCalledTimes(viewlineRequests);
  });

  it('drops an all-clear that lands after a newer alert answer — the banner stays up', async () => {
    // Two focuses either side of an alert beginning, the first one's answer landing last. On the
    // map this was the worse direction: the live scores cleared mid-alert and, with no stored run
    // keeping aurora mode available, the tab bounced to Sunset.
    const earlier = deferred();
    const later = deferred();
    getAuroraStatus
      .mockResolvedValueOnce({ ...ALL_CLEAR })
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    expect(banner()).not.toBeInTheDocument();

    await refocus();
    await refocus();
    expect(getAuroraStatus).toHaveBeenCalledTimes(3);

    await land(() => later.resolve({ ...ALERT }));
    // Control: the newer answer, the alert, is applied — the banner goes up.
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');

    await land(() => earlier.resolve({ ...ALL_CLEAR }));

    // Still up. Broken, the pre-alert all-clear replaced the alert and took the banner down.
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');
  });

  it('drops an alert that lands after a newer "no access" answer — a 401 or 403 is ordered too', async () => {
    // `getAuroraStatus` answers null for a 401 or 403 — the account can no longer see aurora, say
    // after a role change mid-session — and the banner has no role gate of its own, so that null is
    // the only thing that takes it down. It is an answer, not a failure: it applies, and it moves
    // the mark like any other.
    const earlier = deferred();
    const later = deferred();
    getAuroraStatus
      .mockResolvedValueOnce({ ...ALERT })
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');

    await refocus();
    await refocus();
    expect(getAuroraStatus).toHaveBeenCalledTimes(3);

    await land(() => later.resolve(null));
    // Control: the newer answer, "no access", is applied — the banner comes down.
    expect(banner()).not.toBeInTheDocument();

    await land(() => earlier.resolve({ ...ALERT }));

    // Still down. Broken, the alert from before access was withdrawn put the banner back up.
    expect(banner()).not.toBeInTheDocument();
  });

  it('applies answers that land in the order they were asked for — each one moves the status on', async () => {
    // The guard drops only what is OLDER than the answer on screen. An answer landing while newer
    // requests are still out is applied: it is the freshest status there is so far. Three are out,
    // not two, because the mark must be the landed answer's OWN number. Set to the newest number
    // made instead, the first answer would push it past a sibling still out and drop that one when
    // it landed — which no race with only two requests out can show.
    const first = deferred();
    const second = deferred();
    const third = deferred();
    getAuroraStatus
      .mockResolvedValueOnce({ ...ALL_CLEAR })
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
      .mockReturnValueOnce(third.promise);

    await mount();
    await refocus();
    await refocus();
    await refocus();
    expect(getAuroraStatus).toHaveBeenCalledTimes(4);

    await land(() => first.resolve({ ...ALERT }));
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');
    expect(banner()).toHaveTextContent('Kp 5');

    await land(() => second.resolve({ ...ESCALATED }));
    expect(banner()).toHaveTextContent('Strong storm — aurora likely across the UK');
    expect(banner()).toHaveTextContent('Kp 7');

    await land(() => third.resolve({ ...ALL_CLEAR }));
    expect(banner()).not.toBeInTheDocument();
  });

  it('drops every answer older than the one on screen, not just the first — a drop never moves the mark', async () => {
    // Three requests out, and the NEWEST lands first; then the two older ones, oldest first. Both
    // are stale, and the second is the one that matters: had dropping the first lowered the mark to
    // its own number, the second would then look newer than the mark and put the ended alert back
    // up — the defect this provider exists to stop, arriving one answer late.
    const oldest = deferred();
    const middle = deferred();
    const newest = deferred();
    getAuroraStatus
      .mockResolvedValueOnce({ ...ALERT })
      .mockReturnValueOnce(oldest.promise)
      .mockReturnValueOnce(middle.promise)
      .mockReturnValueOnce(newest.promise);

    await mount();
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');

    await refocus();
    await refocus();
    await refocus();
    expect(getAuroraStatus).toHaveBeenCalledTimes(4);

    await land(() => newest.resolve({ ...ALL_CLEAR }));
    // Control: the newest answer, the all-clear, is applied — the banner comes down.
    expect(banner()).not.toBeInTheDocument();

    await land(() => oldest.resolve({ ...ALERT }));
    expect(banner()).not.toBeInTheDocument();

    await land(() => middle.resolve({ ...ESCALATED }));

    // Still down. Broken, dropping the oldest answer lowered the mark, and the middle one — also
    // taken before the all-clear — came in over it and put the banner back up.
    expect(banner()).not.toBeInTheDocument();
  });

  it('still applies an older answer when the newer request fails — a failure blocks nothing', async () => {
    // Only an APPLIED answer moves the mark. Were every request made to move it — the rule that
    // lets only the most recent request write — the failed newer request would have the older
    // answer dropped too, leaving the status older than it needed to be until the next poll.
    const earlier = deferred();
    const later = deferred();
    getAuroraStatus
      .mockResolvedValueOnce({ ...ALL_CLEAR })
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getAuroraStatus).toHaveBeenCalledTimes(3);

    await land(() => later.reject(new Error('502 from /api/aurora/status')));
    // The failure has landed — the awaited act saw to that — and moved nothing: the mount's
    // all-clear is still on screen. This line cannot prove the landing by itself, since the banner
    // was down before it too; the assertion below, settled through the same helper, is what fails
    // if the helper stops flushing.
    expect(banner()).not.toBeInTheDocument();

    await land(() => earlier.resolve({ ...ALERT }));

    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');
  });

  it('keeps the status on screen when a refresh fails — a network blip must not end an alert', async () => {
    // The behaviour this fix had to keep: the failure path writes nothing.
    const failing = deferred();
    const next = deferred();
    getAuroraStatus
      .mockResolvedValueOnce({ ...ALERT })
      .mockReturnValueOnce(failing.promise)
      .mockReturnValueOnce(next.promise);

    await mount();
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');

    await refocus();
    expect(getAuroraStatus).toHaveBeenCalledTimes(2);
    await land(() => failing.reject(new Error('Network Error')));

    // Still the alert. Broken, the failure cleared the status and took the banner down mid-alert.
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');

    // Control, settled through the same helper: the next answer still lands and applies. Without
    // it, this test could not tell a swallowed failure from a helper that never let one land.
    await refocus();
    await land(() => next.resolve({ ...ESCALATED }));
    expect(banner()).toHaveTextContent('Strong storm — aurora likely across the UK');
  });

  it('under StrictMode, drops the first run\'s answer when it lands after the second run\'s', async () => {
    // Development only: StrictMode runs the provider's effect twice on mount, so every dev load
    // makes two status requests at once. The numbering has to span both runs — held per run, the
    // first run's answer would be judged against its own count and replace the second's.
    const firstRun = deferred();
    const secondRun = deferred();
    getAuroraStatus
      .mockReturnValueOnce(firstRun.promise)
      .mockReturnValueOnce(secondRun.promise);

    await act(async () => {
      render(<StrictMode>{tree()}</StrictMode>);
    });
    // StrictMode really did run the effect twice — without that this test proves nothing.
    expect(getAuroraStatus).toHaveBeenCalledTimes(2);

    await land(() => secondRun.resolve({ ...ALERT }));
    // Control: the second run's answer is applied.
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');

    await land(() => firstRun.resolve({ ...ALL_CLEAR }));

    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');
  });
});

/**
 * The night in progress as `App` and `MapView` both read it — the real status hook under the real
 * resolver. A stand-in for those two because the claim here is WHEN they are asked, not what they
 * draw; `MapViewPastNights.test.jsx` covers what the map draws once asked.
 */
function NightInProgress() {
  const { status } = useAuroraStatus();
  return <output data-testid="night-in-progress">{resolveAuroraNight(status)}</output>;
}

describe('AuroraStatusProvider — the page re-reads the night in progress when that night ends', () => {
  /** 02:00 BST on Friday, when the status is taken. */
  const TAKEN_AT = '2026-08-14T01:00:00Z';
  /**
   * Nautical dawn, 04:07:30 BST, where Thursday's night ends. Off the whole-minute grid the provider
   * looks on — first at 02:00:00, then every minute — on purpose: its last look before the end then
   * finds thirty seconds left and has to wait them out. A provider that re-rendered on any look
   * within a minute of the end would do so while the night still stood, and never look again.
   */
  const NIGHT_ENDS_AT = '2026-08-14T03:07:30Z';
  const THURSDAY_NIGHT = { ...ALL_CLEAR, currentNightDate: '2026-08-13', currentNightEndsAt: NIGHT_ENDS_AT };

  const night = () => screen.getByTestId('night-in-progress');
  /**
   * The promise under test — "within a minute of waking" — written out rather than read from the
   * provider, so that loosening the provider's look interval fails here instead of moving the test.
   */
  const A_MINUTE = 60 * 1000;

  beforeEach(() => {
    // The timeout the provider arms and the clock that it and the resolver read, besides the poll.
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date'] });
    vi.setSystemTime(new Date(TAKEN_AT));
  });

  it('re-renders at the night\'s end with every poll since failing — the night moves on with no new status', async () => {
    // Codex, on #841, one layer down. `resolveAuroraNight` stops believing a status at the end of its
    // night, but it answers only when something renders, and with the polls failing nothing had to:
    // a failed poll changes no state, and the memoised map re-reads the night only when something
    // re-renders it. So its list could go on offering Thursday until the reader touched something.
    getAuroraStatus
      .mockRejectedValue(new Error('502 from /api/aurora/status'))
      .mockResolvedValueOnce({ ...THURSDAY_NIGHT });

    await act(async () => {
      render(<AuroraStatusProvider><NightInProgress /></AuroraStatusProvider>);
    });
    expect(night()).toHaveTextContent('2026-08-13');

    // A second short of the end, with every poll on the way failed: still Thursday's — the failures
    // took nothing away. The control for the next step; the resolver's own boundary is pinned in
    // `mapDates.test.js`.
    const untilJustBefore = Date.parse(NIGHT_ENDS_AT) - Date.parse(TAKEN_AT) - 1000;
    await act(async () => { vi.advanceTimersByTime(untilJustBefore); });
    expect(getAuroraStatus).toHaveBeenCalledTimes(1 + Math.floor(untilJustBefore / POLL_INTERVAL_MS));
    expect(night()).toHaveTextContent('2026-08-13');

    await act(async () => { vi.advanceTimersByTime(1000); });

    // Friday's: Thursday's night is over, though no status has landed since 02:00.
    expect(night()).toHaveTextContent('2026-08-14');
  });

  it('re-reads the night within a minute of waking from a sleep that spanned its end', async () => {
    // Browser timers count on a clock that stops while the device sleeps, so one timer armed at 02:00
    // for a 04:07 dawn fires two hours after the lid opens again — and with the polls failing, nothing
    // else re-renders the map meanwhile. So the provider looks at the wall clock at least once a
    // minute. `vi.setSystemTime` moves the wall clock without firing a timer, which is what a sleep
    // does. The sleep starts after the wait has re-armed, so the cap is held on the re-arm as well as
    // on the first look.
    getAuroraStatus
      .mockRejectedValue(new Error('502 from /api/aurora/status'))
      .mockResolvedValueOnce({ ...THURSDAY_NIGHT });

    await act(async () => {
      render(<AuroraStatusProvider><NightInProgress /></AuroraStatusProvider>);
    });
    await act(async () => { vi.advanceTimersByTime(A_MINUTE * 1.5); });
    expect(night()).toHaveTextContent('2026-08-13');

    vi.setSystemTime(new Date('2026-08-14T07:00:00Z')); // the lid opens at 08:00 BST
    // Control: nothing has rendered since — the sleep alone moves nothing on screen.
    expect(night()).toHaveTextContent('2026-08-13');

    await act(async () => { vi.advanceTimersByTime(A_MINUTE); });

    expect(night()).toHaveTextContent('2026-08-14');
  });

  it('re-reads the night even when it ends between the render and the provider\'s first look', async () => {
    // The consumers read the clock when they render; the provider's effect runs after they commit.
    // A night ending in that gap left the effect finding its end already past — which it took to mean
    // the render had seen it, so it armed nothing and the page believed the ended night until
    // something else rendered. Modelled by moving the clock to the end from inside the render that
    // first reads the status.
    function NightInProgressAsDawnBreaks() {
      const { status } = useAuroraStatus();
      const nightNow = resolveAuroraNight(status);
      // eslint-disable-next-line react-hooks/purity -- impure on purpose: dawn arrives mid-commit
      if (status && Date.now() < Date.parse(NIGHT_ENDS_AT)) vi.setSystemTime(new Date(NIGHT_ENDS_AT));
      return <output data-testid="night-in-progress">{nightNow}</output>;
    }
    getAuroraStatus
      .mockRejectedValue(new Error('502 from /api/aurora/status'))
      .mockResolvedValueOnce({ ...THURSDAY_NIGHT });

    await act(async () => {
      render(<AuroraStatusProvider><NightInProgressAsDawnBreaks /></AuroraStatusProvider>);
    });
    // Control: the render that read the status did so before the end, and believed Thursday.
    expect(night()).toHaveTextContent('2026-08-13');

    await act(async () => { vi.advanceTimersByTime(0); });

    expect(night()).toHaveTextContent('2026-08-14');
  });
});
