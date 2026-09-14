/**
 * `AuroraStatusProvider` — the status every reader sees answers the newest request made, whatever
 * order the answers land in.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The provider asks `GET /api/aurora/status` on mount, on a five-minute poll and on every window
 * focus, and used to publish whatever answered. With two requests out at once — a poll and a focus,
 * or two focuses — the answer that LANDED last won, not the one ASKED last, and since each status
 * request can wait on live NOAA calls, the older one landing second is ordinary:
 *
 * <ul>
 *   <li>An alert answer taken before the alert ended, landing after the all-clear, put the banner
 *       back up — and with it the viewline, whose endpoint does not check the alert state — until
 *       the next poll or focus.</li>
 *   <li>An all-clear taken before an alert began, landing after the alert, took the banner down
 *       mid-alert, cleared the map's live scores and, with no stored run keeping aurora mode
 *       available, bounced the Map tab to Sunset.</li>
 * </ul>
 *
 * <h2>Why the banner</h2>
 *
 * <p>`AuroraBanner` is the real consumer whose whole output is a function of the status — up for
 * MODERATE or STRONG, nothing otherwise — so whether it is up, and which alert it names, IS which
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
import { getAuroraStatus, getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';

/** The provider's poll. Not exported by it; a changed cadence fails the poll test's call count. */
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
};

/**
 * The same endpoint once the alert is over: the machine drops to IDLE, which the controller serves
 * as QUIET with `active` false, and CLEAR zeroes the location counts. CLEAR resets neither
 * `triggerType` nor the trigger Kp served as `forecastKp`, so a real all-clear still carries the
 * ended alert's.
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
  // every status answer it expects; one it does not expect answers `undefined` and fails the
  // call-count preconditions below rather than landing silently.
  getAuroraStatus.mockReset();
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
    // The mount's answer is an alert, so an alert answer that gets applied is visible.
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');

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

  it('applies answers that land in the order they were asked for — each one moves the status on', async () => {
    // The guard drops only what is OLDER than the answer on screen. An older answer landing while a
    // newer request is still out is applied: it is the freshest status there is so far.
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

    await land(() => earlier.resolve({ ...ALERT }));
    expect(banner()).toHaveTextContent('Moderate storm — aurora possible from northern England');
    expect(banner()).toHaveTextContent('Kp 5');

    await land(() => later.resolve({ ...ESCALATED }));
    expect(banner()).toHaveTextContent('Strong storm — aurora likely across the UK');
    expect(banner()).toHaveTextContent('Kp 7');
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
    // Control: the failure has landed, and nothing on screen moved for it.
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
    // it this test could not tell a swallowed failure from a helper that never let the failure land.
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
