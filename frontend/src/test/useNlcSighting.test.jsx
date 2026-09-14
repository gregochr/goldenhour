/**
 * `useNlcSighting` — answers are applied in the order their requests were made, not the order they
 * land.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The hook asks `GET /api/nlc/sighting` on mount, on a ten-minute poll and on every window focus,
 * and used to publish whatever answered. With two requests out at once — a poll and a focus, or two
 * focuses — the answer that LANDED last won, not the one ASKED last. Two requests finish in whatever
 * order their server work does, and a sighting request that finds the backend's NLCNET cache stale
 * scrapes the page before it answers, so the older one can land second:
 *
 * <ul>
 *   <li>A sighting taken before the report aged out, landing after the newer "nothing to show", put
 *       the banner back up until the next poll or focus.</li>
 *   <li>The same late sighting, landing after a NEWER report the reader had dismissed, put the
 *       banner back up too: the dismissal is keyed by `reportedAt`, so an older report reads as a
 *       report the reader has not seen.</li>
 *   <li>A "nothing to show" taken before a report arrived, landing after it, took the banner
 *       down.</li>
 * </ul>
 *
 * <h2>Why the banner</h2>
 *
 * <p>`NlcSightingBanner` is the hook's only consumer. Its output is the sighting — up for an active
 * report under clear skies, nothing otherwise — plus one piece of state of its own, the reader's
 * dismissal of a report, which a late answer could undo. So whether it is up, and which report it
 * names, is which answer is on screen. The API module is mocked at the boundary the frontend test
 * standards prescribe.
 */
import React, { StrictMode } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

vi.mock('../api/nlcApi.js', () => ({ getNlcSighting: vi.fn() }));

import NlcSightingBanner from '../components/NlcSightingBanner.jsx';
import { getNlcSighting } from '../api/nlcApi.js';

/** The hook's poll. Not exported by it; a changed cadence fails the poll test's call count. */
const POLL_INTERVAL_MS = 10 * 60 * 1000;

/** `GET /api/nlc/sighting` with nothing to show — `NlcSightingResponse.inactive()`, verbatim. */
const NOTHING_TO_SHOW = { active: false };

/** A fresh report under clear skies, as the controller serves one. */
const REPORT = {
  active: true,
  reportedAt: '2026-06-20T22:40:00Z',
  observerLocation: 'Elgin',
  region: 'Scotland',
  source: 'NLCNET',
  clearTonight: true,
  darkSkyLocationCount: 12,
  lookDirection: 'N–NW',
  hexColour: '#8E86D6',
  description: 'Noctilucent cloud reported over Scotland',
};

/** A NEWER report from somewhere else — a different banner, and a different dismissal key. */
const NEWER_REPORT = {
  ...REPORT,
  reportedAt: '2026-06-20T23:25:00Z',
  observerLocation: 'Alnwick',
  region: 'Northumberland',
  darkSkyLocationCount: 18,
  description: 'Noctilucent cloud reported over Northumberland',
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
 * assert: the hook's continuation has run and its update has committed before the test resumes, so
 * a report that is not on screen afterwards was dropped, not merely still on its way. Every positive
 * control below settles through this same helper, so a helper that stopped flushing would fail at
 * the control rather than let a negative pass (frontend test standards, "A late response is only
 * 'dropped' once it has landed").
 */
async function land(settle) {
  await act(async () => { settle(); });
}

/** Mounts the banner and lets the mount's own request — answered up front by each test — land. */
async function mount() {
  await act(async () => { render(<NlcSightingBanner />); });
}

/** The page regains focus: the hook asks for the sighting again. */
async function refocus() {
  await act(async () => { window.dispatchEvent(new Event('focus')); });
}

/** The hook's ten-minute poll comes round. */
async function poll() {
  await act(async () => { vi.advanceTimersByTime(POLL_INTERVAL_MS); });
}

const banner = () => screen.queryByRole('alert');

beforeEach(() => {
  // Intervals only: the hook's poll fires when a test says so and not otherwise. Every wait in this
  // file is an awaited `act`, never a `findBy*` or `waitFor`, whose own polling runs on
  // `setInterval`.
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  // Reset, not cleared: `mockReturnValueOnce` queues survive `vi.clearAllMocks()`, so a test that
  // failed with a request still queued would hand it to the next test's mount. Each test queues
  // every answer it expects; one it does not expect answers `undefined` and fails the call-count
  // preconditions below rather than landing silently.
  getNlcSighting.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useNlcSighting — answers are applied in the order they were asked for', () => {
  it('drops a report that lands after a newer "nothing to show" — the banner stays down', async () => {
    // The poll-and-focus race: the poll asks while the report is still fresh, it ages out, a focus
    // asks again — and the poll's answer, held up scraping NLCNET, lands last.
    const pollAnswer = deferred();
    const focusAnswer = deferred();
    getNlcSighting
      .mockResolvedValueOnce({ ...REPORT })
      .mockReturnValueOnce(pollAnswer.promise)
      .mockReturnValueOnce(focusAnswer.promise);

    await mount();
    // The mount's answer is a report, so a report that gets applied is visible.
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');

    await poll();
    await refocus();
    // Both requests really are out at once — without that there is no race to lose.
    expect(getNlcSighting).toHaveBeenCalledTimes(3);

    await land(() => focusAnswer.resolve({ ...NOTHING_TO_SHOW }));
    // Control: the newer answer, nothing to show, is applied — the banner comes down.
    expect(banner()).not.toBeInTheDocument();

    await land(() => pollAnswer.resolve({ ...REPORT }));

    // Still down. Broken, the aged-out report was published again and the banner went back up
    // until the next poll or focus.
    expect(banner()).not.toBeInTheDocument();
  });

  it('drops an older report that lands after a newer one the reader dismissed — it stays dismissed', async () => {
    // The dismissal is keyed by `reportedAt` so that a NEWER report re-shows the banner. An older
    // report landing late has a different key too, so broken, it read as one the reader had not
    // seen: the banner came back, naming a report older than the one they had just dismissed.
    const pollAnswer = deferred();
    const focusAnswer = deferred();
    getNlcSighting
      .mockResolvedValueOnce({ ...REPORT })
      .mockReturnValueOnce(pollAnswer.promise)
      .mockReturnValueOnce(focusAnswer.promise);

    await mount();
    await poll();
    await refocus();
    expect(getNlcSighting).toHaveBeenCalledTimes(3);

    await land(() => focusAnswer.resolve({ ...NEWER_REPORT }));
    // Control: the newer report is applied, and it is that one the reader dismisses.
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Northumberland');
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss noctilucent sighting banner' }));
    expect(banner()).not.toBeInTheDocument();

    await land(() => pollAnswer.resolve({ ...REPORT }));

    expect(banner()).not.toBeInTheDocument();
  });

  it('drops a "nothing to show" that lands after a newer report — the banner stays up', async () => {
    // Two focuses either side of a report arriving, the first one's answer landing last.
    const earlier = deferred();
    const later = deferred();
    getNlcSighting
      .mockResolvedValueOnce({ ...NOTHING_TO_SHOW })
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    expect(banner()).not.toBeInTheDocument();

    await refocus();
    await refocus();
    expect(getNlcSighting).toHaveBeenCalledTimes(3);

    await land(() => later.resolve({ ...REPORT }));
    // Control: the newer answer, the report, is applied — the banner goes up.
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');

    await land(() => earlier.resolve({ ...NOTHING_TO_SHOW }));

    // Still up. Broken, the pre-report answer replaced the report and took the banner down.
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');
  });

  it('applies answers that land in the order they were asked for — each one moves the report on', async () => {
    // The guard drops only what is OLDER than the answer on screen. An older answer landing while a
    // newer request is still out is applied: it is the freshest report there is so far.
    const earlier = deferred();
    const later = deferred();
    getNlcSighting
      .mockResolvedValueOnce({ ...NOTHING_TO_SHOW })
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getNlcSighting).toHaveBeenCalledTimes(3);

    await land(() => earlier.resolve({ ...REPORT }));
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');
    expect(banner()).toHaveTextContent('Elgin via NLCNET');

    await land(() => later.resolve({ ...NEWER_REPORT }));
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Northumberland');
    expect(banner()).toHaveTextContent('Alnwick via NLCNET');
  });

  it('still applies an older answer when the newer request fails — a failure blocks nothing', async () => {
    // Only an APPLIED answer moves the mark. Were every request made to move it — the rule that
    // lets only the most recent request write — the failed newer request would have the older
    // answer dropped too, leaving the banner older than it needed to be until the next poll.
    const earlier = deferred();
    const later = deferred();
    getNlcSighting
      .mockResolvedValueOnce({ ...REPORT })
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getNlcSighting).toHaveBeenCalledTimes(3);

    await land(() => later.reject(new Error('502 from /api/nlc/sighting')));
    // Control: the failure has landed, and the report on screen did not move for it. Mounted on a
    // report rather than on nothing, so a failure that cleared the banner would show here.
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');

    await land(() => earlier.resolve({ ...NEWER_REPORT }));

    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Northumberland');
  });

  it('applies a newer null over an older report — a null is an answer here, not a skipped response', async () => {
    // `getNlcSighting` answers null for a reader the banner is not for (401/403). That is an
    // answer — the banner comes down — and it moves the mark like any other, so an older report
    // landing after it is dropped. The Plan briefing treats ITS null, a 204, the other way, as no
    // answer at all; that rule ported here would put a report back up for a reader it is not for.
    const earlier = deferred();
    const later = deferred();
    getNlcSighting
      .mockResolvedValueOnce({ ...REPORT })
      .mockReturnValueOnce(earlier.promise)
      .mockReturnValueOnce(later.promise);

    await mount();
    await refocus();
    await refocus();
    expect(getNlcSighting).toHaveBeenCalledTimes(3);

    await land(() => later.resolve(null));
    // Control: the null is applied — the banner comes down.
    expect(banner()).not.toBeInTheDocument();

    await land(() => earlier.resolve({ ...REPORT }));

    expect(banner()).not.toBeInTheDocument();
  });

  it('applies a newer answer while a still newer request is out — the mark follows what was applied', async () => {
    // Three requests out at once: a poll and two focuses. The oldest lands, then the middle one,
    // which is newer than what is on screen and so is applied although the newest is still out.
    // Were the mark moved to the newest request MADE whenever an answer is applied, the oldest
    // landing would push it past the middle one, which would then be dropped — and with the newest
    // failing, the banner would sit on the oldest answer until the next poll.
    const oldest = deferred();
    const middle = deferred();
    const newest = deferred();
    getNlcSighting
      .mockResolvedValueOnce({ ...NOTHING_TO_SHOW })
      .mockReturnValueOnce(oldest.promise)
      .mockReturnValueOnce(middle.promise)
      .mockReturnValueOnce(newest.promise);

    await mount();
    await poll();
    await refocus();
    await refocus();
    expect(getNlcSighting).toHaveBeenCalledTimes(4);

    await land(() => oldest.resolve({ ...REPORT }));
    // Control: the oldest answer is still newer than the mount's, so it is applied.
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');

    await land(() => middle.resolve({ ...NEWER_REPORT }));
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Northumberland');

    await land(() => newest.reject(new Error('502 from /api/nlc/sighting')));
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Northumberland');
  });

  it('keeps the report on screen when a refresh fails — a network blip must not hide it', async () => {
    // The behaviour this fix had to keep: the failure path writes nothing.
    const failing = deferred();
    const next = deferred();
    getNlcSighting
      .mockResolvedValueOnce({ ...REPORT })
      .mockReturnValueOnce(failing.promise)
      .mockReturnValueOnce(next.promise);

    await mount();
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');

    await refocus();
    expect(getNlcSighting).toHaveBeenCalledTimes(2);
    await land(() => failing.reject(new Error('Network Error')));

    // Still the report. Broken, the failure cleared the sighting and took the banner down.
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');

    // Control, settled through the same helper: the next answer still lands and applies. Without
    // it this test could not tell a swallowed failure from a helper that never let the failure land.
    await refocus();
    await land(() => next.resolve({ ...NEWER_REPORT }));
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Northumberland');
  });

  it('under StrictMode, drops the first run\'s answer when it lands after the second run\'s', async () => {
    // Development only: StrictMode runs the hook's effect twice on mount, so every dev load makes
    // two sighting requests at once. The numbering has to span both runs — held per run, the first
    // run's answer would be judged against its own count and replace the second's.
    const firstRun = deferred();
    const secondRun = deferred();
    getNlcSighting
      .mockReturnValueOnce(firstRun.promise)
      .mockReturnValueOnce(secondRun.promise);

    await act(async () => {
      render(<StrictMode><NlcSightingBanner /></StrictMode>);
    });
    // StrictMode really did run the effect twice — without that this test proves nothing.
    expect(getNlcSighting).toHaveBeenCalledTimes(2);

    await land(() => secondRun.resolve({ ...REPORT }));
    // Control: the second run's answer is applied.
    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');

    await land(() => firstRun.resolve({ ...NOTHING_TO_SHOW }));

    expect(banner()).toHaveTextContent('Noctilucent cloud reported over Scotland');
  });
});
