/**
 * The "Today" range filter on the job-metrics cards, read from a device that is NOT in the UK.
 *
 * <p>Two separate defects live on the one line this file guards, and the suite's default UTC pin
 * can see neither:
 *
 * <ul>
 *   <li><b>Two calendars in one comparison.</b> The filter took the device's date and compared it
 *       with {@code startedAt.slice(0, 10)}, which is the UTC date of a backend instant. Those
 *       agree on a UK dev machine in winter and nowhere else, so an admin four hours west saw the
 *       toggle empty a list that plainly had runs in it.</li>
 *   <li><b>A bare instant read as local time.</b> {@code startedAt} is a {@code LocalDateTime} and
 *       arrives without a zone, so {@code new Date("2026-08-13T06:00:00")} is four hours adrift
 *       here — which is what the 7-day branch did.</li>
 * </ul>
 *
 * <p>⚠️ Under {@code TZ=UTC} both bugs are invisible: the device date IS the UTC date, and a bare
 * instant read as local IS the right instant. That is why this file exists rather than more cases
 * in `MetricsSummary.test.jsx`.
 */
process.env.TZ = 'America/New_York';

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import MetricsSummary from '../components/MetricsSummary.jsx';

/**
 * 21:00 UK on 13 August 2026 (BST) — 16:00 in New York, 20:00 UTC. An ordinary UK evening, when an
 * admin would plausibly be looking at this, and the three calendars still agree on the date.
 */
const UK_EVENING = '2026-08-13T20:00:00Z';

/**
 * 00:30 UK on 14 August (BST). New York is on the 13th, UTC is on the 13th, the UK is on the 14th
 * — the hour where the old device-vs-UTC comparison and the correct answer disagree outright.
 */
const UK_SMALL_HOURS = '2026-08-13T23:30:00Z';

/** A run started at 22:00 UK on the 13th — 21:00 UTC, so its bare instant reads `2026-08-13T21:00:00`. */
const RUN_ON_THE_13TH = {
  id: 1,
  jobName: 'SHORT_TERM',
  runType: 'SHORT_TERM',
  startedAt: '2026-08-13T21:00:00',
  durationMs: 5000,
  succeeded: 100,
  failed: 0,
  totalCostMicroDollars: 50000,
  exchangeRateGbpPerUsd: 0.79,
};

/** A run started at 00:10 UK on the 14th — 23:10 UTC on the 13th. The date-straddling case. */
const RUN_JUST_AFTER_UK_MIDNIGHT = {
  ...RUN_ON_THE_13TH,
  id: 2,
  startedAt: '2026-08-13T23:10:00',
};

const noop = () => {};

function freeze(iso) {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  vi.useRealTimers();
});

function renderToday(runs) {
  render(<MetricsSummary runs={runs} apiCalls={[]} range="today" onRangeChange={noop} />);
}

describe('the zone fixture itself', () => {
  it('is pinned west of the UK, or neither defect below can show', () => {
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/New_York');
  });

  it('sits on an instant where the device and the UK disagree about the date', () => {
    freeze(UK_SMALL_HOURS);

    expect(new Date().toLocaleDateString('en-CA')).toBe('2026-08-13');
    expect(new Date().toISOString().slice(0, 10)).toBe('2026-08-13');
  });
});

describe('the Today filter, from a device four hours west of the UK', () => {
  it('counts a UK-evening run as today while the device is on the same date', () => {
    // The control: 21:00 UK on the 13th, and the run started an hour earlier that UK day.
    freeze(UK_EVENING);
    renderToday([RUN_ON_THE_13TH]);

    expect(screen.getByText('100 succeeded, 0 failed')).toBeInTheDocument();
  });

  it('counts a run started after UK midnight as today, once the UK day has rolled', () => {
    // 00:30 UK on the 14th. The run began at 00:10 UK on the 14th, so it IS today's — but its
    // UTC date is the 13th and so is the device's, which is why the old comparison dropped it.
    freeze(UK_SMALL_HOURS);
    renderToday([RUN_JUST_AFTER_UK_MIDNIGHT]);

    expect(screen.getByText('100 succeeded, 0 failed')).toBeInTheDocument();
  });

  it('drops a run from the previous UK day once the UK day has rolled', () => {
    // The other side of the same boundary, and the half a "count everything" bug would pass: the
    // 22:00 run belongs to the 13th, and at 00:30 on the 14th it is no longer today's.
    freeze(UK_SMALL_HOURS);
    renderToday([RUN_ON_THE_13TH]);

    expect(screen.getByText('No job runs today')).toBeInTheDocument();
  });

  it('separates the two runs at the boundary rather than lumping them', () => {
    freeze(UK_SMALL_HOURS);
    // Both counted would read "200 succeeded"; neither, the empty message.
    renderToday([RUN_ON_THE_13TH, RUN_JUST_AFTER_UK_MIDNIGHT]);

    expect(screen.getByText('100 succeeded, 0 failed')).toBeInTheDocument();
  });
});

describe('the 7-day filter, from a device four hours west of the UK', () => {
  it('keeps a bare-instant run inside the window rather than shifting it out', () => {
    // `new Date('2026-08-13T21:00:00')` on this zone is 01:00Z on the 14th — a four-hour shift, and
    // the direction that matters at a window edge.
    freeze(UK_EVENING);
    render(<MetricsSummary runs={[RUN_ON_THE_13TH]} apiCalls={[]} range="7d" onRangeChange={noop} />);

    expect(screen.getByText('100 succeeded, 0 failed')).toBeInTheDocument();
  });

  it('excludes a run that only a mis-parsed instant would drag inside the window', () => {
    // The edge is where the four hours actually decide something, and a fixture in the middle of
    // the window proves nothing: with `now` at 20:00Z the cutoff is 2026-08-06T20:00Z, so a run at
    // 18:00Z that day is two hours OUTSIDE it — but read as New York local time it becomes 22:00Z,
    // two hours inside, and the card silently gains a run that is older than the range it claims.
    freeze(UK_EVENING);
    const justOutside = { ...RUN_ON_THE_13TH, id: 3, startedAt: '2026-08-06T18:00:00' };
    render(<MetricsSummary runs={[justOutside]} apiCalls={[]} range="7d" onRangeChange={noop} />);

    expect(screen.getByText('No job runs the last 7 days')).toBeInTheDocument();
  });

  it('still keeps a run just inside the window', () => {
    // The other side of the same edge, so the test above cannot pass by excluding everything.
    freeze(UK_EVENING);
    const justInside = { ...RUN_ON_THE_13TH, id: 4, startedAt: '2026-08-06T22:00:00' };
    render(<MetricsSummary runs={[justInside]} apiCalls={[]} range="7d" onRangeChange={noop} />);

    expect(screen.getByText('100 succeeded, 0 failed')).toBeInTheDocument();
  });
});
