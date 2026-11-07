import { describe, it, expect } from 'vitest';
import {
  buildRepeatingRows,
  cadenceWords,
  relatedKeys,
  repeatingIntervalMs,
  staggerArrowPath,
  watchdogWindows,
} from '../utils/schedulerDayOverlays.js';
import { dayFire, dayRepeatingJob } from './schedulerDayFixtures.js';

const MIN = 60_000;
const cron = (cronExpression, extra = {}) => ({ scheduleType: 'CRON', cronExpression, delayMs: null, timeZone: null, ...extra });

describe('cadenceWords for cron schedules', () => {
  it('reads "every minute" and "every N minutes" cron shapes in the short form', () => {
    expect(cadenceWords(cron('0 * * * * *'))).toBe('every 1 min');
    expect(cadenceWords(cron('0 */5 * * * *'))).toBe('every 5 min');
    expect(cadenceWords(cron('0 */15 * * * *'))).toBe('every 15 min');
  });

  it.each([
    ['a seconds field that is not 0', '30 */5 * * * *'],
    ['a step with text before it', '0 x*/5 * * * *'],
    ['a step with text after it', '0 */5x * * * *'],
    ['a step of three digits', '0 */123 * * * *'],
    ['an hour that is set', '0 */5 3 * * *'],
    ['a day of month that is set', '0 */5 * 1 * *'],
    ['only five fields', '0 */5 * * *'],
  ])('does not read %s as a minute cadence', (_name, expression) => {
    expect(cadenceWords(cron(expression))).toBe(expression);
  });

  it('describes a daily cron in words, lowercased, in the zone it runs in', () => {
    expect(cadenceWords(cron('0 30 5 * * *', { timeZone: 'Europe/London' }))).toBe('daily at 05:30 UK time');
  });

  it('is empty for no schedule and for a cron with no expression', () => {
    expect(cadenceWords(null)).toBe('');
    expect(cadenceWords(cron(null))).toBe('');
  });
});

describe('cadenceWords for fixed delays', () => {
  it('reads each unit', () => {
    const delay = (delayMs) => ({ scheduleType: 'FIXED_DELAY', delayMs });
    expect(cadenceWords(delay(3_600_000))).toBe('every hour');
    expect(cadenceWords(delay(7_200_000))).toBe('every 2 hours');
    expect(cadenceWords(delay(MIN))).toBe('every 1 min');
    expect(cadenceWords(delay(300_000))).toBe('every 5 min');
    expect(cadenceWords(delay(30_000))).toBe('every 30 sec');
  });

  it('gives lower case words for a delay it cannot count', () => {
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 'soon' })).toBe('every nan seconds');
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 0 })).toBe('');
  });
});

describe('repeatingIntervalMs', () => {
  const row = (schedule, ticks = null) => ({ schedule, ticks });

  it('is a fixed delay\'s own delay, or unknown when it has none', () => {
    expect(repeatingIntervalMs(row({ scheduleType: 'FIXED_DELAY', delayMs: 300_000 }))).toBe(300_000);
    expect(repeatingIntervalMs(row({ scheduleType: 'FIXED_DELAY', delayMs: 0 }))).toBe(Infinity);
    expect(repeatingIntervalMs(row({ scheduleType: 'FIXED_DELAY', delayMs: null }))).toBe(Infinity);
  });

  it('is the median gap between served ticks, in order of size not of time', () => {
    const ticks = [0, 5 * MIN, 6 * MIN, 15 * MIN, 17 * MIN, 24 * MIN];
    // gaps 5, 1, 9, 2, 7 minutes: the median is 5
    expect(repeatingIntervalMs(row(cron('0 0 * * * *'), ticks))).toBe(5 * MIN);
  });

  it('measures each gap against the tick before it', () => {
    expect(repeatingIntervalMs(row(cron('0 0 * * * *'), [0, MIN, 10 * MIN]))).toBe(9 * MIN);
  });

  it('falls back to the cron\'s own minute step when fewer than two ticks were served', () => {
    expect(repeatingIntervalMs(row(cron('0 */5 * * * *'), []))).toBe(5 * MIN);
    expect(repeatingIntervalMs(row(cron('0 */5 * * * *'), [MIN]))).toBe(5 * MIN);
    expect(repeatingIntervalMs(row(cron('0 * * * * *')))).toBe(MIN);
    expect(repeatingIntervalMs(row(cron('0 */15 * * * *')))).toBe(15 * MIN);
  });

  it.each([
    ['a seconds field that is not 0', '30 */5 * * * *'],
    ['text before the step', '0 x*/5 * * * *'],
    ['text after the step', '0 */5x * * * *'],
    ['a three digit step', '0 */123 * * * *'],
    ['an hour that is set', '0 */5 3 * * *'],
    ['five fields', '0 */5 * * *'],
    ['a plain minute', '0 30 * * * *'],
  ])('is unknown for %s', (_name, expression) => {
    expect(repeatingIntervalMs(row(cron(expression)))).toBe(Infinity);
  });

  it('is unknown with no schedule', () => {
    expect(repeatingIntervalMs(row(null))).toBe(Infinity);
    expect(repeatingIntervalMs(row(undefined))).toBe(Infinity);
  });
});

describe('buildRepeatingRows ordering', () => {
  const entry = (jobKey, overrides = {}) => dayRepeatingJob({ jobKey, displayName: jobKey, ...overrides });

  it('puts the rarest job first, by ticks per day, then by name', () => {
    const day = {
      jobs: [
        entry('b_often', { ticks: new Array(144).fill('2026-11-03T01:00:00Z') }),
        entry('a_often', { ticks: new Array(144).fill('2026-11-03T01:00:00Z') }),
        entry('hourly', { ticks: new Array(24).fill('2026-11-03T01:00:00Z') }),
        entry('quiet', { ticks: [] }),
      ],
    };
    expect(buildRepeatingRows(day, []).map((r) => r.jobKey)).toEqual(['quiet', 'hourly', 'a_often', 'b_often']);
  });

  it('orders jobs with no ticks by their fixed delay, longest first, and cron without one as zero', () => {
    const day = {
      jobs: [
        entry('fast', { schedule: { scheduleType: 'FIXED_DELAY', delayMs: MIN }, ticks: undefined }),
        entry('slow', { schedule: { scheduleType: 'FIXED_DELAY', delayMs: 30 * MIN }, ticks: undefined }),
        entry('nodelay', { schedule: { scheduleType: 'FIXED_DELAY', delayMs: undefined }, ticks: undefined }),
        entry('cronish', { schedule: cron('0 * * * * *', { delayMs: 99 * MIN }), ticks: undefined }),
      ],
    };
    expect(buildRepeatingRows(day, []).map((r) => r.jobKey)).toEqual(['slow', 'fast', 'cronish', 'nodelay']);
  });

  it('copes with jobs that have neither ticks nor a schedule', () => {
    const day = { jobs: [entry('b', { schedule: undefined, ticks: undefined }), entry('a', { schedule: undefined, ticks: undefined })] };
    const rows = buildRepeatingRows(day, []);
    expect(rows.map((r) => r.jobKey)).toEqual(['a', 'b']);
    expect(rows[0]).toMatchObject({ schedule: null, cadence: '', ticks: null });
  });

  it('keeps the served problems that have a readable time, and defaults a missing list', () => {
    const day = {
      jobs: [
        entry('p', { problems: [{ status: 'FAILED', at: '2026-11-03T01:00:00Z', summary: 's' }, { status: 'FAILED', at: 'junk' }] }),
        { jobKey: 'q', displayName: 'q', kind: 'REPEATING' },
      ],
    };
    const [p, q] = buildRepeatingRows(day, []);
    expect(p.problems).toEqual([{ status: 'FAILED', at: '2026-11-03T01:00:00Z', summary: 's', atMs: Date.UTC(2026, 10, 3, 1) }]);
    expect(q).toMatchObject({ problems: [], problemsOmitted: 0 });
  });
});

describe('watchdogWindows edges', () => {
  const rowOf = (fires, jobKey = 'cycle') => ({ jobKey, fires });
  const solar = (overrides = {}) => ({
    jobKey: 'cycle', opensAt: '2026-11-03T05:00:00Z', closesAt: '2026-11-03T07:00:00Z', event: 'SUNRISE', ...overrides,
  });

  it('draws a window for a fire that ran exactly at the deadline', () => {
    const rows = [rowOf([dayFire({ at: '2026-11-03T07:00:00Z' })])];
    const [window] = watchdogWindows({ watchdog: [solar()] }, rows);
    expect(window).toMatchObject({ ownerAtMs: Date.UTC(2026, 10, 3, 7), deadlineMs: Date.UTC(2026, 10, 3, 7) });
  });

  it('belongs to the newest eligible fire whatever order the fires are given in', () => {
    const fires = [
      dayFire({ at: '2026-11-03T06:00:00Z' }),
      dayFire({ at: '2026-11-03T04:00:00Z' }),
      dayFire({ at: '2026-11-03T08:00:00Z' }),
    ];
    const [window] = watchdogWindows({ watchdog: [solar()] }, [rowOf(fires)]);
    expect(window.ownerAtMs).toBe(Date.UTC(2026, 10, 3, 6));
    const [reversed] = watchdogWindows({ watchdog: [solar()] }, [rowOf([...fires].reverse())]);
    expect(reversed.ownerAtMs).toBe(Date.UTC(2026, 10, 3, 6));
  });

  it('uses the served deadline, not the close, when the window has no open', () => {
    const entry = { jobKey: 'cycle', opensAt: null, closesAt: '2026-11-03T07:00:00Z', deadlineAt: '2026-11-03T09:00:00Z', budgetMinutes: 120 };
    const rows = [rowOf([dayFire({ at: '2026-11-03T04:00:00Z' })])];
    const [window] = watchdogWindows({ watchdog: [entry] }, rows);
    expect(window).toMatchObject({
      deadlineMs: Date.UTC(2026, 10, 3, 9),
      startMs: Date.UTC(2026, 10, 3, 4),
      budgetOnly: true,
      event: '2 h budget from the first batch',
    });
  });

  it('uses the served deadline, not the close, when the window has no close', () => {
    const entry = { jobKey: 'cycle', opensAt: '2026-11-03T05:00:00Z', closesAt: null, deadlineAt: '2026-11-03T09:00:00Z', event: 'SUNSET' };
    const [window] = watchdogWindows({ watchdog: [entry] }, [rowOf([dayFire({ at: '2026-11-03T04:00:00Z' })])]);
    expect(window).toMatchObject({ deadlineMs: Date.UTC(2026, 10, 3, 9), startMs: Date.UTC(2026, 10, 3, 5), budgetOnly: false });
    expect(window.event).toBe('Sunset');
  });

  it('draws nothing when no deadline is served, even for a fire at the epoch', () => {
    const entry = { jobKey: 'cycle', opensAt: null, closesAt: null, deadlineAt: null };
    const rows = [rowOf([dayFire({ at: '1970-01-01T00:00:00Z' })])];
    expect(watchdogWindows({ watchdog: [entry] }, rows)).toEqual([]);
  });

  it('draws nothing for an unknown job, no eligible fire, or no watchdog list', () => {
    expect(watchdogWindows({ watchdog: [solar({ jobKey: 'other' })] }, [rowOf([dayFire({ at: '2026-11-03T04:00:00Z' })])])).toEqual([]);
    expect(watchdogWindows({ watchdog: [solar()] }, [rowOf([dayFire({ at: '2026-11-03T04:00:00Z', status: 'SKIPPED' })])])).toEqual([]);
    expect(watchdogWindows({}, [rowOf([dayFire()])])).toEqual([]);
    expect(watchdogWindows(null, [])).toEqual([]);
  });

  it('words the window text from the UK clock', () => {
    const [window] = watchdogWindows({ watchdog: [solar()] }, [rowOf([dayFire({ at: '2026-11-03T04:00:00Z' })])]);
    expect(window.text).toBe('Watchdog window 05:00–07:00');
  });
});

describe('relatedKeys edges', () => {
  it('is null for no selection even when the keys include a null', () => {
    expect(relatedKeys(null, [], [null, 'a'])).toBeNull();
    expect(relatedKeys(undefined, [], ['a'])).toBeNull();
  });

  it('is null for a selection with no row, and the selection with its neighbours otherwise', () => {
    const links = [{ from: 'a', to: 'b' }, { from: 'c', to: 'a' }, { from: 'x', to: 'y' }];
    expect(relatedKeys('z', links, ['a', 'b', 'c'])).toBeNull();
    expect([...relatedKeys('a', links, ['a', 'b', 'c'])].sort()).toEqual(['a', 'b', 'c']);
  });
});

describe('staggerArrowPath', () => {
  it('leaves the start 24px along when the next fire is close or behind', () => {
    expect(staggerArrowPath(100, 10, 50, 20)).toBe('M104 10 C 128 10, 17 20, 41 20');
  });

  it('heads straight for the next fire when it is far away', () => {
    expect(staggerArrowPath(100, 10, 300, 20)).toBe('M104 10 C 291 10, 267 20, 291 20');
  });
});
