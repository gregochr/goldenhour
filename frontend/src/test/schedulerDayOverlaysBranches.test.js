import { describe, it, expect } from 'vitest';
import {
  buildLinks,
  buildRepeatingRows,
  cadenceWords,
  carriesWatchdog,
  fireTip,
  problemTip,
  relatedKeys,
  repeatingAriaLabel,
  repeatingIntervalMs,
  repeatingLayout,
  repeatingRowTip,
  rowTip,
  watchdogTip,
  watchdogWindows,
  wouldBeTip,
} from '../utils/schedulerDayOverlays.js';

const AXIS = { startMs: 0, endMs: 1000 };
const T = (hhmm) => `2026-11-03T${hhmm}:00Z`;

describe('cadenceWords', () => {
  it('is empty for no schedule', () => expect(cadenceWords(null)).toBe(''));
  it('reads fixed delays in hours, minutes and seconds', () => {
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 3_600_000 })).toBe('every hour');
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 7_200_000 })).toBe('every 2 hours');
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 300_000 })).toBe('every 5 min');
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 30_000 })).toBe('every 30 sec');
  });
  it('is empty for a fixed delay with no usable delay', () => {
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 0 })).toBe('');
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: null })).toBe('');
  });
  it('reads every-minute and stepped crons', () => {
    expect(cadenceWords({ scheduleType: 'CRON', cronExpression: '0 * * * * *' })).toBe('every 1 min');
    expect(cadenceWords({ scheduleType: 'CRON', cronExpression: '0 */5 * * * *' })).toBe('every 5 min');
  });
  it('falls back to the cron words, lower-cased, for another cron', () => {
    expect(cadenceWords({ scheduleType: 'CRON', cronExpression: '0 15 * * * *', timeZone: 'Europe/London' }))
      .toBe('every hour at :15');
  });
  it('falls back for a six-field cron whose minute is not a step', () => {
    expect(cadenceWords({ scheduleType: 'CRON', cronExpression: '0 5 * * * *' })).toBe('every hour at :05');
  });
  it('is empty for a cron with no expression', () => {
    expect(cadenceWords({ scheduleType: 'CRON' })).toBe('');
  });
});

describe('buildRepeatingRows', () => {
  it('copes with no day and no jobs', () => {
    expect(buildRepeatingRows(null, null)).toEqual([]);
    expect(buildRepeatingRows({}, undefined)).toEqual([]);
  });

  it('keeps only repeating jobs not disabled by config and fills the defaults', () => {
    const day = {
      jobs: [
        { kind: 'REPEATING', jobKey: 'a', displayName: 'A', schedule: { scheduleType: 'FIXED_DELAY', delayMs: 60_000 } },
        { kind: 'REPEATING', jobKey: 'off', displayName: 'Off' },
        { kind: 'SCHEDULED', jobKey: 's', displayName: 'S' },
      ],
    };
    const jobs = [
      { jobKey: 'a', status: 'PAUSED', catalogue: { usesClaude: true, alias: 'Poller' } },
      { jobKey: 'off', status: 'DISABLED_BY_CONFIG' },
    ];
    const rows = buildRepeatingRows(day, jobs);
    expect(rows).toEqual([{
      jobKey: 'a', name: 'A', schedule: { scheduleType: 'FIXED_DELAY', delayMs: 60_000 }, cadence: 'every 1 min',
      ticks: null, problems: [], problemsOmitted: 0, paused: true, usesClaude: true, alias: 'Poller',
    }]);
  });

  it('defaults a job with no meta and no schedule', () => {
    const [row] = buildRepeatingRows({ jobs: [{ kind: 'REPEATING', jobKey: 'x', displayName: 'X' }] }, []);
    expect(row).toMatchObject({ schedule: null, cadence: '', paused: false, usesClaude: false, alias: null });
  });

  it('sorts ticks and drops unreadable ticks and problems', () => {
    const day = {
      jobs: [{
        kind: 'REPEATING', jobKey: 'a', displayName: 'A',
        ticks: [T('10:00'), 'nonsense', T('09:00')],
        problems: [{ status: 'FAILED', at: T('09:30'), summary: 'x' }, { status: 'FAILED', at: 'nonsense' }],
        problemsOmitted: 3,
      }],
    };
    const [row] = buildRepeatingRows(day, []);
    expect(row.ticks).toEqual([Date.parse(T('09:00')), Date.parse(T('10:00'))]);
    expect(row.problems).toEqual([{ status: 'FAILED', at: T('09:30'), summary: 'x', atMs: Date.parse(T('09:30')) }]);
    expect(row.problemsOmitted).toBe(3);
  });

  it('orders the rarest first, then fixed delays by delay, then by name', () => {
    const rep = (jobKey, extra) => ({ kind: 'REPEATING', jobKey, displayName: jobKey.toUpperCase(), ...extra });
    const many = Array.from({ length: 288 }, (_, i) => `2026-11-03T${String(Math.floor(i / 12)).padStart(2, '0')}:${String((i % 12) * 5).padStart(2, '0')}:00Z`);
    const day = {
      jobs: [
        rep('b-many', { ticks: many }),
        rep('c-none', { ticks: [] }),
        rep('d-two', { ticks: [T('01:00'), T('13:00')] }),
        rep('e-delay', { schedule: { scheduleType: 'FIXED_DELAY', delayMs: 120_000 } }),
        rep('f-delay-null', { schedule: { scheduleType: 'FIXED_DELAY', delayMs: null } }),
        rep('g-cron', { schedule: { scheduleType: 'CRON', cronExpression: '0 * * * * *' } }),
        rep('a-cron', { schedule: { scheduleType: 'CRON', cronExpression: '0 * * * * *' } }),
      ],
    };
    expect(buildRepeatingRows(day, []).map((r) => r.jobKey))
      .toEqual(['c-none', 'd-two', 'b-many', 'e-delay', 'a-cron', 'f-delay-null', 'g-cron']);
  });
});

describe('repeatingLayout', () => {
  it('draws a band when the server listed no ticks', () => {
    expect(repeatingLayout({ ticks: null }, AXIS, 100)).toEqual({ shape: 'band', ticks: [] });
  });
  it('draws ticks when they are at least 3px apart, keeping only those on the axis', () => {
    expect(repeatingLayout({ ticks: [-5, 100, 500, 2000] }, AXIS, 1000)).toEqual({ shape: 'ticks', ticks: [100, 500] });
  });
  it('draws a band when the closest two ticks are under 3px apart', () => {
    expect(repeatingLayout({ ticks: [100, 102, 500] }, AXIS, 1000).shape).toBe('band');
  });
  it('draws ticks for a single visible tick', () => {
    expect(repeatingLayout({ ticks: [100] }, AXIS, 1000)).toEqual({ shape: 'ticks', ticks: [100] });
  });
});

describe('repeatingIntervalMs', () => {
  it('uses a fixed delay, or Infinity when it is not positive', () => {
    expect(repeatingIntervalMs({ schedule: { scheduleType: 'FIXED_DELAY', delayMs: 90_000 }, ticks: null })).toBe(90_000);
    expect(repeatingIntervalMs({ schedule: { scheduleType: 'FIXED_DELAY', delayMs: 0 }, ticks: null })).toBe(Infinity);
  });
  it('uses the median gap of served ticks', () => {
    expect(repeatingIntervalMs({ schedule: null, ticks: [0, 10, 20, 50] })).toBe(10);
  });
  it('reads an every-minute or stepped cron when no ticks were served', () => {
    expect(repeatingIntervalMs({ schedule: { cronExpression: '0 * * * * *' }, ticks: null })).toBe(60_000);
    expect(repeatingIntervalMs({ schedule: { cronExpression: '0 */10 * * * *' }, ticks: [5] })).toBe(600_000);
  });
  it('is Infinity for anything else', () => {
    expect(repeatingIntervalMs({ schedule: { cronExpression: '0 5 3 * * *' }, ticks: null })).toBe(Infinity);
    expect(repeatingIntervalMs({ schedule: { cronExpression: '0 5 * * * *' }, ticks: null })).toBe(Infinity);
    expect(repeatingIntervalMs({ schedule: null, ticks: null })).toBe(Infinity);
  });
});

describe('repeatingAriaLabel', () => {
  it('is just the name for a plain row', () => {
    expect(repeatingAriaLabel({ name: 'Poller', cadence: '', problems: [], problemsOmitted: 0, paused: false, usesClaude: false, alias: null }))
      .toBe('Poller');
  });
  it('lists every part', () => {
    expect(repeatingAriaLabel({
      name: 'Poller', cadence: 'every 5 min', problems: [{}], problemsOmitted: 1, paused: true, usesClaude: true, alias: 'Collector',
    })).toBe('Poller, every 5 min, 2 problems, paused, uses Claude, Collector');
  });
  it('says one problem in the singular', () => {
    expect(repeatingAriaLabel({ name: 'P', cadence: '', problems: [{}], problemsOmitted: 0, paused: false, usesClaude: false, alias: null }))
      .toBe('P, 1 problem');
  });
});

describe('carriesWatchdog', () => {
  it('lets a failed fire carry one only when a run is linked', () => {
    expect(carriesWatchdog({ status: 'FAILED', runLinked: true })).toBe(true);
    expect(carriesWatchdog({ status: 'FAILED', runLinked: false })).toBe(false);
    expect(carriesWatchdog({ status: 'FAILED' })).toBe(false);
  });
  it('lets ran and to-run fires carry one, not skipped, missed or paused', () => {
    ['SUCCEEDED', 'PARTIAL', 'RUNNING', 'SCHEDULED'].forEach((status) => expect(carriesWatchdog({ status })).toBe(true));
    ['SKIPPED', 'MISSED', 'PAUSED'].forEach((status) => expect(carriesWatchdog({ status })).toBe(false));
  });
});

describe('watchdogWindows', () => {
  const rows = [{ jobKey: 'sun', fires: [
    { at: T('05:00'), status: 'SUCCEEDED' },
    { at: T('06:00'), status: 'SUCCEEDED' },
    { at: T('07:00'), status: 'SKIPPED' },
    { at: 'nonsense', status: 'SUCCEEDED' },
  ] }];

  it('is empty for no day', () => expect(watchdogWindows(null, rows)).toEqual([]));

  it('draws a solar window from opensAt to closesAt owned by the newest eligible fire', () => {
    const day = { watchdog: [{ jobKey: 'sun', event: 'SUNRISE', opensAt: T('05:30'), closesAt: T('08:00') }] };
    expect(watchdogWindows(day, rows)).toEqual([{
      jobKey: 'sun', startMs: Date.parse(T('05:30')), deadlineMs: Date.parse(T('08:00')),
      ownerAtMs: Date.parse(T('06:00')), event: 'Sunrise', budgetOnly: false, text: 'Watchdog window 05:30–08:00',
    }]);
  });

  it('draws a budget window from the owning fire to the served deadline, in hours or minutes', () => {
    const hours = watchdogWindows({ watchdog: [{ jobKey: 'sun', budgetMinutes: 120, deadlineAt: T('08:00') }] }, rows);
    expect(hours[0]).toMatchObject({ startMs: Date.parse(T('06:00')), budgetOnly: true, event: '2 h budget from the first batch' });
    const mins = watchdogWindows({ watchdog: [{ jobKey: 'sun', budgetMinutes: 90, deadlineAt: T('08:00') }] }, rows);
    expect(mins[0].event).toBe('90 min budget from the first batch');
  });

  it('has an empty event when there is neither an event nor a budget', () => {
    const w = watchdogWindows({ watchdog: [{ jobKey: 'sun', deadlineAt: T('08:00') }] }, rows);
    expect(w[0].event).toBe('');
  });

  it('draws nothing for an unknown job, no deadline or no eligible owner', () => {
    expect(watchdogWindows({ watchdog: [{ jobKey: 'other', opensAt: T('05:30'), closesAt: T('08:00') }] }, rows)).toEqual([]);
    expect(watchdogWindows({ watchdog: [{ jobKey: 'sun', budgetMinutes: 60 }] }, rows)).toEqual([]);
    expect(watchdogWindows({ watchdog: [{ jobKey: 'sun', opensAt: T('01:00'), closesAt: T('02:00') }] }, rows)).toEqual([]);
  });

  it('uses the deadline when only one end of the window is served', () => {
    const w = watchdogWindows({ watchdog: [{ jobKey: 'sun', opensAt: T('05:30'), deadlineAt: T('08:00') }] }, rows);
    expect(w[0]).toMatchObject({ deadlineMs: Date.parse(T('08:00')), startMs: Date.parse(T('05:30')), budgetOnly: false });
  });
});

describe('buildLinks and relatedKeys', () => {
  const jobs = [
    { jobKey: 'sub', catalogue: { resultsCollectedBy: ['poll', 'missing', 'sub'], staggeredBefore: ['next'] } },
    { jobKey: 'poll', catalogue: {} },
    { jobKey: 'next' },
    { jobKey: 'norow', catalogue: { resultsCollectedBy: ['poll'] } },
  ];
  it('returns only links between jobs with rows, without self links', () => {
    expect(buildLinks(jobs, ['sub', 'poll', 'next'])).toEqual([
      { kind: 'collect', from: 'sub', to: 'poll' },
      { kind: 'stagger', from: 'sub', to: 'next' },
    ]);
  });
  it('copes with no jobs', () => expect(buildLinks(null, ['a'])).toEqual([]));

  const links = [{ from: 'a', to: 'b' }, { from: 'c', to: 'a' }, { from: 'x', to: 'y' }];
  it('relates both directions and leaves out unrelated links', () => {
    expect([...relatedKeys('a', links, ['a', 'b', 'c', 'x', 'y'])].sort()).toEqual(['a', 'b', 'c']);
  });
  it('is null when nothing is selected or the selection has no row', () => {
    expect(relatedKeys(null, links, ['a'])).toBeNull();
    expect(relatedKeys('zz', links, ['a'])).toBeNull();
  });
});

describe('tooltips', () => {
  const row = { name: 'Job', alias: 'Al', paused: false, fires: [], wouldBe: [] };
  const done = { at: T('01:00'), status: 'SUCCEEDED', startedAt: T('01:00'), completedAt: '2026-11-03T01:00:55Z', summary: 'all good' };
  const next = { at: T('09:00'), status: 'SCHEDULED' };

  it('fireTip with and without a watchdog', () => {
    expect(fireTip(row, done, { text: 'Watchdog window 05:00–06:00', event: 'Sunrise' })).toEqual({
      name: 'Job', status: { label: 'OK', tone: 'go' }, time: '01:00', duration: '55s', note: 'all good', alias: 'Al',
      watchdog: 'Watchdog window 05:00–06:00', watchdogEvent: 'Sunrise',
    });
    expect(fireTip({ ...row, alias: null }, { at: T('02:00'), status: 'MISSED' })).toEqual({
      name: 'Job', status: { label: 'Missed', tone: 'coral' }, time: '02:00', duration: '', note: '', alias: '',
      watchdog: '', watchdogEvent: '',
    });
  });

  it('wouldBeTip says the job is paused', () => {
    expect(wouldBeTip({ name: 'Job', alias: null }, { at: T('03:00') })).toEqual({
      name: 'Job', status: { label: 'Paused', tone: 'off' }, time: '03:00',
      note: 'Would have fired, but the job is paused.', alias: '',
    });
  });

  it('rowTip shows the last run with its duration and watchdog', () => {
    expect(rowTip({ ...row, fires: [done, next] }, [{ text: 'W', event: 'E' }])).toEqual({
      name: 'Job', status: { label: 'OK', tone: 'go' }, time: '01:00', duration: '55s', note: 'all good', alias: 'Al',
      watchdog: 'W', watchdogEvent: 'E',
    });
  });
  it('rowTip falls back to the next fire when nothing has run', () => {
    expect(rowTip({ ...row, alias: null, fires: [next] })).toEqual({
      name: 'Job', status: { label: 'To run', tone: 'off' }, time: '09:00', duration: '', note: '', alias: '',
      watchdog: '', watchdogEvent: '',
    });
  });
  it('rowTip lists would-be fires for a paused job with nothing else', () => {
    const tip = rowTip({ ...row, paused: true, wouldBe: [{ at: T('03:00') }, { at: T('04:00') }] });
    expect(tip.status).toEqual({ label: 'Paused', tone: 'off' });
    expect(tip.time).toBe('03:00, 04:00');
    expect(tip.duration).toBe('');
  });
  it('rowTip has no status for a job with no fires and no pause', () => {
    expect(rowTip(row).status).toBeNull();
  });

  it('repeatingRowTip words problems, pause and the day', () => {
    const base = { name: 'R', cadence: 'every 5 min', problems: [{}], problemsOmitted: 1, paused: true, alias: null };
    expect(repeatingRowTip(base)).toEqual({
      name: 'R', status: { label: 'Paused', tone: 'off' }, time: 'every 5 min', note: '2 problems today', alias: '',
    });
    expect(repeatingRowTip({ ...base, problems: [], problemsOmitted: 1, paused: false, alias: 'Al' }, false)).toEqual({
      name: 'R', status: null, time: 'every 5 min', note: '1 problem on this day', alias: 'Al',
    });
    expect(repeatingRowTip({ ...base, problems: [], problemsOmitted: 0 }).note).toBe('');
  });

  it('problemTip gives the reason, or nothing', () => {
    expect(problemTip(row, { status: 'FAILED', at: T('04:00'), summary: 'boom' })).toEqual({
      name: 'Job', status: { label: 'Failed', tone: 'coral' }, time: '04:00', note: 'boom', alias: 'Al',
    });
    expect(problemTip({ ...row, alias: null }, { status: 'PARTIAL', at: T('04:00') }).note).toBe('');
  });

  it('watchdogTip carries the window and event', () => {
    expect(watchdogTip(row, { text: 'Watchdog window 05:00–06:00', event: 'Sunset' })).toEqual({
      name: 'Job', watchdog: 'Watchdog window 05:00–06:00', watchdogEvent: 'Sunset', alias: 'Al',
    });
    expect(watchdogTip({ ...row, alias: null }, { text: 't', event: 'e' }).alias).toBe('');
  });
});
