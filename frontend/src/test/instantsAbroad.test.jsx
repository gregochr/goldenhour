/**
 * Backend instants, rendered on a device that is NOT in the UK.
 *
 * <p>The sibling of `mapDatesAbroad.test.js`, and it exists for the same reason: a UK-pinned test
 * cannot tell "the UK calendar" from "the browser's calendar", because under `Europe/London` the two
 * are the same string and every assertion passes either way. The suite's own pin is UTC, which
 * separates them for the seven months of BST but not for the five of GMT — and it never separates
 * them by a whole *day*, which is the form this defect took. Only a zone genuinely west of Greenwich
 * does that.
 *
 * <p>The instants below are `Instant`s and `LocalDateTime`s straight off this app's own API. They
 * describe UK events for a UK audience — an aurora over Scotland, a terms acceptance under UK law, a
 * batch that ran at 04:00 UK — so the calendar and the clock that make them legible are the UK's,
 * not the reader's. A UK photographer abroad, or one whose device is simply on the wrong zone, was
 * being shown a different day.
 *
 * ⚠️ Pinned to `America/New_York` on purpose, and the offset (UTC−4 in summer, −5 in winter) is what
 * does the work: every fixture is chosen so the New York answer and the UK answer genuinely differ,
 * and every assertion below fails against the code as it was. Do not "harmonise" this with the other
 * date test files by moving it to Europe/London or UTC — that would delete the only coverage of the
 * defect.
 */
process.env.TZ = 'America/New_York';

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

import { formatInstantUk, parseUtcInstant } from '../utils/conversions.js';
import { formatDetectedAt } from '../components/AuroraBanner.jsx';
import { formatReportedAt } from '../components/NlcSightingBanner.jsx';
import HealthIndicator from '../components/HealthIndicator.jsx';
import HotTopicStrip from '../components/HotTopicStrip.jsx';
import UserManagementView from '../components/UserManagementView.jsx';
import WaitlistManagementView from '../components/WaitlistManagementView.jsx';
import { tsLabel } from '../components/SkyRatingEvalView.jsx';
import { getUsers } from '../api/userApi.js';
import { getWaitlist } from '../api/waitlistApi.js';

vi.mock('../api/userApi.js', () => ({
  getUsers: vi.fn(),
  createUser: vi.fn(),
  resetUserPassword: vi.fn(),
  updateUserEmail: vi.fn(),
  updateUserRole: vi.fn(),
  updateUserEnabled: vi.fn(),
  deleteUser: vi.fn(),
  resendVerification: vi.fn(),
}));

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn() }));

// SkyRatingEvalView is imported for its exported `tsLabel` only, never rendered — but importing the
// module still evaluates its API import, so the boundary is mocked as the house style requires.
vi.mock('../api/skyRatingEvalApi', () => ({
  runSkyRatingEval: vi.fn(),
  getSkyRatingEvalRuns: vi.fn(),
  getSkyRatingEvalRun: vi.fn(),
  getSkyRatingEvalTrend: vi.fn(),
}));

/**
 * Midnight UTC on 1 April 2026 — the instant from the evidence that opened this. The UK is on BST,
 * so it is 01:00 on the 1st there and 20:00 on 31 March in New York: one instant, two dates.
 */
const APRIL_FIRST_UTC_MIDNIGHT = '2026-04-01T00:00:00Z';

/**
 * 23:30 UTC on 8 April 2026, and deliberately BARE — a Jackson `LocalDateTime`, which is how
 * `submittedAt` and `runTimestamp` come off the wire even though the value was produced in UTC. UK
 * reads 00:30 on the 9th; New York reads 19:30 on the 8th.
 */
const APRIL_EIGHTH_LATE_BARE = '2026-04-08T23:30:00';

describe('the zone fixture itself', () => {
  it('really is on a zone that disagrees with the UK, or nothing below proves anything', () => {
    // Guards the guard, exactly as mapDatesAbroad does. If the TZ pin ever stops taking effect —
    // deleted, or defeated by the suite-wide default in setup.js — these tests would quietly become
    // duplicates of the UTC-pinned ones instead of failing, so the disagreement is asserted outright.
    const instant = new Date(APRIL_FIRST_UTC_MIDNIGHT);

    expect(instant.toLocaleDateString('en-CA')).toBe('2026-03-31');
    expect(formatInstantUk(instant, { day: 'numeric', month: 'short', year: 'numeric' }))
      .toBe('1 Apr 2026');
  });
});

describe('parseUtcInstant abroad', () => {
  it('reads a bare LocalDateTime as UTC, not as the device\'s wall clock', () => {
    // The trap that made this family of bugs invisible. `new Date('2026-04-08T23:30:00')` is LOCAL
    // by specification, so on this zone it is four hours later than the value the backend stored —
    // and then formatting it locally shifted it back, so the digits looked right on screen while
    // being UTC wearing the reader's clock.
    expect(parseUtcInstant(APRIL_EIGHTH_LATE_BARE).toISOString()).toBe('2026-04-08T23:30:00.000Z');
    expect(new Date(APRIL_EIGHTH_LATE_BARE).toISOString()).toBe('2026-04-09T03:30:00.000Z');
  });

  it('leaves an instant that already names its offset alone', () => {
    expect(parseUtcInstant(APRIL_FIRST_UTC_MIDNIGHT).toISOString()).toBe('2026-04-01T00:00:00.000Z');
    // The `-` in the date is not an offset. Appending a second `Z` here would give Invalid Date.
    expect(parseUtcInstant('2026-08-13T11:22:15+0100').toISOString()).toBe('2026-08-13T10:22:15.000Z');
  });

  it('returns null rather than an Invalid Date for absent or unparseable input', () => {
    expect(parseUtcInstant(null)).toBeNull();
    expect(parseUtcInstant('')).toBeNull();
    expect(parseUtcInstant('not-a-date')).toBeNull();
  });
});

describe('formatInstantUk abroad', () => {
  it('dates an instant on the UK calendar, not the device\'s', () => {
    expect(formatInstantUk(APRIL_FIRST_UTC_MIDNIGHT, { day: 'numeric', month: 'short', year: 'numeric' }))
      .toBe('1 Apr 2026');
  });

  it('clocks an instant on UK time, BST included', () => {
    expect(formatInstantUk(APRIL_EIGHTH_LATE_BARE, { hour: '2-digit', minute: '2-digit' }))
      .toBe('00:30');
  });

  it('ignores a caller trying to supply its own zone', () => {
    // The zone is not a parameter, and that is the whole point of the helper: a call site cannot
    // opt out of the UK calendar by accident, and cannot opt out of it deliberately either.
    expect(formatInstantUk(APRIL_FIRST_UTC_MIDNIGHT, {
      day: 'numeric', month: 'short', year: 'numeric', timeZone: 'America/New_York',
    })).toBe('1 Apr 2026');
  });

  it('returns null for absent or unparseable input, so callers can render their own placeholder', () => {
    expect(formatInstantUk(null, { day: 'numeric' })).toBeNull();
    expect(formatInstantUk('not-a-date', { day: 'numeric' })).toBeNull();
  });
});

describe('the terms-acceptance date abroad', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  const userWithTerms = (termsAcceptedAt) => ([{
    id: 1,
    username: 'admin',
    email: 'admin@example.com',
    role: 'ADMIN',
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    lastActiveAt: null,
    termsAcceptedAt,
    termsVersion: 'April 2026',
    homePostcode: null,
    marketingEmailOptIn: false,
  }]);

  it('dates an acceptance on the UK calendar the terms were accepted under', async () => {
    // The named defect: a legal record stored at 2026-04-01T00:00:00Z read "31 Mar 2026" to an admin
    // west of Greenwich — a day early, on the one row in this app where the date IS the record.
    //
    // The click is OUTSIDE the wait on purpose. Retrying it inside a `waitFor` re-fires the toggle
    // on every poll, so a failing assertion collapses and re-expands the row ~20 times a second
    // instead of failing — which is a hang dressed as a slow test, not a signal.
    getUsers.mockResolvedValue(userWithTerms(APRIL_FIRST_UTC_MIDNIGHT));
    render(<UserManagementView />);

    fireEvent.click(await screen.findByTestId('expand-user-1'));
    expect(screen.getByTestId('terms-accepted-1')).toHaveTextContent('1 Apr 2026');
  });

  it('still says "Not accepted" when there is no acceptance to date', async () => {
    // The degrade path, which the fix rewrote: the ternary on a truthy string became a `??` on a
    // nullable return, so a null instant has to be proved to still reach the placeholder rather
    // than rendering an empty cell or the word "null".
    getUsers.mockResolvedValue(userWithTerms(null));
    render(<UserManagementView />);

    fireEvent.click(await screen.findByTestId('expand-user-1'));
    expect(screen.getByTestId('terms-accepted-1')).toHaveTextContent('Not accepted');
  });
});

describe('the waitlist submission time abroad', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('stamps a submission with the UK date and clock', async () => {
    // Both halves were wrong here and they cancelled: a bare LocalDateTime parsed as local and then
    // formatted as local renders the stored UTC digits, so this read "8 Apr 2026, 23:30" everywhere
    // on earth — an hour behind the UK all summer, and a day behind on the far side of midnight.
    getWaitlist.mockResolvedValue([{ id: 1, email: 'a@b.com', submittedAt: APRIL_EIGHTH_LATE_BARE }]);
    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    const cell = await screen.findByTestId('waitlist-submitted-0');
    expect(cell).toHaveTextContent('9 Apr 2026, 00:30');
  });

  it('falls back to an em dash when there is no submission time', async () => {
    getWaitlist.mockResolvedValue([{ id: 1, email: 'a@b.com', submittedAt: null }]);
    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    const cell = await screen.findByTestId('waitlist-submitted-0');
    expect(cell).toHaveTextContent('—');
  });
});

describe('the aurora detection label abroad', () => {
  /** 00:00 BST on 14 August 2026 — which is still 19:00 on the 13th in New York. */
  const NOW = new Date('2026-08-13T23:00:00Z');

  it('reads the detection clock and the detection day on the same UK calendar', () => {
    // 23:34 BST on the 13th, half an hour before the UK rolls over. The old code answered "18:34":
    // the New York clock, and the New York day, for an aurora seen over Scotland.
    expect(formatDetectedAt('2026-08-13T22:34:00Z', NOW)).toBe('yesterday 23:34');
  });

  it('calls a detection on the current UK day by its clock time alone', () => {
    // 00:30 BST on the 14th — today in the UK, still the 13th in New York. The two bases put this
    // detection on different days, and only one of them is the day the aurora happened.
    expect(formatDetectedAt('2026-08-13T23:30:00Z', new Date('2026-08-14T01:00:00Z'))).toBe('00:30');
  });

  it('dates an older detection on the UK calendar too', () => {
    expect(formatDetectedAt('2026-08-11T22:34:00Z', NOW)).toBe('11 Aug 23:34');
  });

  it('returns null for absent or unparseable input', () => {
    expect(formatDetectedAt(null, NOW)).toBeNull();
    expect(formatDetectedAt('not-a-date', NOW)).toBeNull();
  });
});

describe('the NLC sighting label abroad', () => {
  /** 00:30 BST on 14 August 2026; 19:30 on the 13th in New York. */
  const NOW = new Date('2026-08-13T23:30:00Z');

  it('credits a sighting to the UK night it was seen on', () => {
    // 22:30 BST on the 13th, two hours before NOW. On the device calendar the report and "now" fall
    // on the same day, so the old code took the same-day branch and said "2h ago"; on the UK
    // calendar the day has turned, and "yesterday 22:30" is the useful answer for a display that
    // ended last night.
    expect(formatReportedAt('2026-08-13T21:30:00Z', NOW)).toBe('yesterday 22:30');
  });

  it('keeps the sub-hour bands, which are instant arithmetic and have no calendar in them', () => {
    expect(formatReportedAt('2026-08-13T23:29:45Z', NOW)).toBe('just now');
    expect(formatReportedAt('2026-08-13T22:45:00Z', NOW)).toBe('45m ago');
  });

  it('dates an older sighting on the UK calendar', () => {
    expect(formatReportedAt('2026-08-11T21:30:00Z', NOW)).toBe('11 Aug 22:30');
  });
});

describe('the health popup abroad', () => {
  it('puts the backend start time on the UK clock', () => {
    // "Backend started 31 Mar 20:00" for a container that came up at 01:00 on 1 April UK — a full
    // day out, in the one widget an admin uses to correlate the UI against container logs.
    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date(APRIL_FIRST_UTC_MIDNIGHT)}
        startedAt={APRIL_FIRST_UTC_MIDNIGHT}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));

    expect(screen.getByTestId('health-started-at')).toHaveTextContent('Backend started 01 Apr 01:00');
  });

  it('puts the probe time on the same clock as the start time beside it', () => {
    // `checkedAt` is the browser's OWN probe instant, so it was not wrong on its own terms. It moves
    // anyway: two clocks under one heading is harder to read than either being uniformly off, and an
    // admin comparing "last checked" to "backend started" is doing arithmetic between them.
    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date(APRIL_FIRST_UTC_MIDNIGHT)}
        startedAt={APRIL_FIRST_UTC_MIDNIGHT}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));

    expect(screen.getByTestId('health-checked-at')).toHaveTextContent('01:00:00');
  });

  it('dates the build commit on the UK calendar', () => {
    render(
      <HealthIndicator
        status="UP" degraded={[]} checkedAt={new Date(APRIL_FIRST_UTC_MIDNIGHT)}
        build={{ commitId: 'abc1234', branch: 'main', commitTime: APRIL_FIRST_UTC_MIDNIGHT, dirty: false }}
      />,
    );
    fireEvent.click(screen.getByTestId('health-indicator'));

    expect(screen.getByTestId('health-build').textContent).toContain('1 Apr 2026');
  });
});

describe('the hot-topic event time abroad', () => {
  it('renders a datetime event time on the UK clock, as the bare "HH:mm" form already is', () => {
    // The API normally sends a bare "HH:mm" the backend has already put on the UK clock. This
    // branch is the defensive one for a full datetime — and it rendered in the device's zone, so
    // which clock the strip showed depended on which shape happened to arrive.
    render(<HotTopicStrip hotTopics={[{
      type: 'INVERSION',
      label: 'CLOUD INVERSION',
      detail: 'Strong inversion likely at elevated locations',
      date: '2026-07-03',
      priority: 2,
      regions: ['Yorkshire Dales'],
      eventType: 'SUNRISE',
      eventTime: '2026-07-03T03:41:00Z',
    }]} />);

    expect(screen.getByTestId('topic-timing-lead-INVERSION').textContent).toContain('· 04:41');
  });
});

describe('the eval trend axis abroad', () => {
  // Asserted through the exported `tsLabel` rather than through a rendered axis, and the reason is
  // a limit of the harness rather than a preference: recharts needs a laid-out container to emit
  // tick text, jsdom has no layout, and the mocked ResponsiveContainer the sibling suite uses gets
  // the CHARTS on screen but leaves `.recharts-cartesian-axis-tick-value` empty. Verified before
  // writing this — a `findByText('20 Jun')` against the rendered view finds nothing either way, so
  // it would have been a test that could not fail.
  it('ticks a 04:00 UK batch under the UK day it ran on', () => {
    // An overnight batch at 04:00 BST on 20 June is 23:00 on the 19th in New York, so the drift
    // chart plotted it a day early — and a trend chart whose x-axis is a day out is a chart about
    // a different question from the one it is labelled with.
    expect(tsLabel('2026-06-20T03:00:00')).toBe('20 Jun');
  });

  it('ticks nothing rather than "Invalid Date" when a run has no timestamp', () => {
    expect(tsLabel(null)).toBe('');
    expect(tsLabel('not-a-date')).toBe('');
  });
});
