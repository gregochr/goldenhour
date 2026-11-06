import { describe, it, expect } from 'vitest';
import {
  buildRows,
  dayAxis,
  dayInRange,
  fireDurationSeconds,
  fireStartMs,
  fireStatusStyle,
  fireWidthPx,
  formatFireDuration,
  isFuture,
  lastHappened,
  markShape,
  needsALook,
  nightBands,
  onAxis,
  rowAriaLabel,
  summaryCounts,
  ukWallInstant,
} from '../utils/schedulerDay.js';
import { dayFire, dayJob, dayRepeatingJob } from './schedulerDayFixtures.js';

const AXIS = () => ({ startMs: 1_000_000, endMs: 2_000_000 });
const iso = (ms) => new Date(ms).toISOString();

describe('dayAxis edges', () => {
  it('is null when only the start is missing, even for a valid end', () => {
    expect(dayAxis({ dayStart: null, dayEnd: '2026-11-04T00:00:00Z' })).toBeNull();
  });

  it('is null when only the end is missing, even for a start before the epoch', () => {
    expect(dayAxis({ dayStart: '1960-01-01T00:00:00Z', dayEnd: null })).toBeNull();
    expect(dayAxis({ dayStart: '1960-01-01T00:00:00Z' })).toBeNull();
  });

  it('is null when the end equals the start', () => {
    expect(dayAxis({ dayStart: '2026-11-03T00:00:00Z', dayEnd: '2026-11-03T00:00:00Z' })).toBeNull();
  });

  it('is the span for a valid day', () => {
    expect(dayAxis({ dayStart: '2026-11-03T00:00:00Z', dayEnd: '2026-11-04T00:00:00Z' })).toEqual({
      startMs: Date.UTC(2026, 10, 3),
      endMs: Date.UTC(2026, 10, 4),
    });
  });
});

describe('onAxis edges', () => {
  it('is false for a missing instant, even on an axis that contains zero', () => {
    expect(onAxis(null, { startMs: 0, endMs: 10 })).toBe(false);
    expect(onAxis(undefined, { startMs: -5, endMs: 10 })).toBe(false);
  });

  it('includes both ends of the axis and nothing outside', () => {
    const axis = { startMs: 10, endMs: 20 };
    expect(onAxis(10, axis)).toBe(true);
    expect(onAxis(20, axis)).toBe(true);
    expect(onAxis(9, axis)).toBe(false);
    expect(onAxis(21, axis)).toBe(false);
  });
});

describe('ukWallInstant on a clock-change day', () => {
  it('resolves a summer wall hour one hour before its UTC reading', () => {
    expect(ukWallInstant('2026-07-01', 12)).toBe(Date.UTC(2026, 6, 1, 11, 0));
  });

  it('resolves a wall time that does not exist (spring forward) to the instant an hour later in UTC terms', () => {
    // 01:30 on 28 March 2027 never happens; the answer is the instant 01:30 GMT.
    expect(ukWallInstant('2027-03-28', 1, 30)).toBe(Date.UTC(2027, 2, 28, 1, 30));
  });

  it('resolves a wall time after the spring change using BST', () => {
    expect(ukWallInstant('2027-03-28', 3)).toBe(Date.UTC(2027, 2, 28, 2, 0));
  });
});

describe('nightBands edges', () => {
  it('has no morning band when sunrise is missing, even on an axis that starts before the epoch', () => {
    const axis = { startMs: -1000, endMs: 5000 };
    expect(nightBands(axis, null, null)).toEqual([]);
  });

  it('has no morning band when sunrise is at or before the axis start', () => {
    const axis = AXIS();
    expect(nightBands(axis, iso(axis.startMs), null)).toEqual([]);
    expect(nightBands(axis, iso(axis.startMs - 1000), null)).toEqual([]);
  });

  it('has no evening band when sunset is at or after the axis end', () => {
    const axis = AXIS();
    expect(nightBands(axis, null, iso(axis.endMs))).toEqual([]);
    expect(nightBands(axis, null, iso(axis.endMs + 1000))).toEqual([]);
  });

  it('draws both bands from the served sun events', () => {
    const axis = AXIS();
    expect(nightBands(axis, iso(1_200_000), iso(1_800_000))).toEqual([
      { startMs: 1_000_000, endMs: 1_200_000 },
      { startMs: 1_800_000, endMs: 2_000_000 },
    ]);
  });

  it('clamps a sunrise beyond the end and a sunset before the start to the axis', () => {
    const axis = AXIS();
    expect(nightBands(axis, iso(5_000_000), null)).toEqual([{ startMs: 1_000_000, endMs: 2_000_000 }]);
    expect(nightBands(axis, null, iso(100_000))).toEqual([{ startMs: 1_000_000, endMs: 2_000_000 }]);
  });
});

describe('markShape edges', () => {
  it('is not a tick when the previous mark is exactly 3px away', () => {
    expect(markShape({ widthPx: 10, gapPrevPx: 3 })).toBe('bar');
    expect(markShape({ widthPx: 10, gapPrevPx: 2.9 })).toBe('tick');
  });

  it('is not a tick when the next mark is exactly as far as its own bar or 3px', () => {
    expect(markShape({ widthPx: 10, gapNextPx: 10 })).toBe('bar');
    expect(markShape({ widthPx: 10, gapNextPx: 9.9 })).toBe('tick');
    expect(markShape({ widthPx: 1, gapNextPx: 3 })).toBe('diamond');
  });

  it('is a tick when the previous fire\'s bar reaches it', () => {
    expect(markShape({ widthPx: 10, gapPrevPx: 19, prevWidthPx: 20 })).toBe('tick');
    expect(markShape({ widthPx: 10, gapPrevPx: 20, prevWidthPx: 20 })).toBe('bar');
  });

  it('is a diamond from under 6px and a bar from 6px', () => {
    expect(markShape({ widthPx: 5.9 })).toBe('diamond');
    expect(markShape({ widthPx: 6 })).toBe('bar');
  });
});

describe('fire durations', () => {
  it('is null when either end is missing, including a start at the epoch', () => {
    expect(fireDurationSeconds({ startedAt: '2026-11-03T01:00:00Z', completedAt: null })).toBeNull();
    expect(fireDurationSeconds({ startedAt: null, completedAt: '2026-11-03T01:00:55Z' })).toBeNull();
    expect(fireDurationSeconds({ startedAt: null, completedAt: null })).toBeNull();
    expect(fireDurationSeconds({ startedAt: '1970-01-01T00:00:00Z', completedAt: null })).toBeNull();
    expect(fireDurationSeconds(undefined)).toBeNull();
    expect(fireDurationSeconds(null)).toBeNull();
  });

  it('is null when the fire ends before it starts and zero when it ends the instant it starts', () => {
    expect(fireDurationSeconds({ startedAt: '2026-11-03T01:00:01Z', completedAt: '2026-11-03T01:00:00Z' })).toBeNull();
    expect(fireDurationSeconds({ startedAt: '2026-11-03T01:00:00Z', completedAt: '2026-11-03T01:00:00Z' })).toBe(0);
  });

  it('formats under a second as "<1s" and exactly a second as "1s"', () => {
    expect(formatFireDuration({ startedAt: '2026-11-03T01:00:00Z', completedAt: '2026-11-03T01:00:00Z' })).toBe('<1s');
    expect(formatFireDuration({ startedAt: '2026-11-03T01:00:00Z', completedAt: '2026-11-03T01:00:01Z' })).toBe('1s');
    expect(formatFireDuration({ startedAt: '2026-11-03T01:00:00Z' })).toBe('');
  });
});

describe('fireStartMs and fireWidthPx for fires that are not simple', () => {
  const axis = () => ({ startMs: 0, endMs: 1000 * 1000 });
  const running = (overrides = {}) => dayFire({
    status: 'RUNNING', at: iso(100_000), startedAt: iso(100_000), completedAt: null, ...overrides,
  });

  it('has no start for a missing fire', () => {
    expect(fireStartMs(undefined)).toBeNull();
    expect(fireStartMs(null)).toBeNull();
  });

  it('starts a running fire where it started, falling back to its slot, and any other fire at its slot', () => {
    expect(fireStartMs(running({ at: iso(50_000) }))).toBe(100_000);
    expect(fireStartMs(running({ at: iso(50_000), startedAt: null }))).toBe(50_000);
    expect(fireStartMs(dayFire({ status: 'SUCCEEDED', at: iso(50_000), startedAt: iso(100_000) }))).toBe(50_000);
  });

  it('is 0 wide for a missing fire even on today', () => {
    expect(fireWidthPx(undefined, axis(), 1000, { nowMs: 500_000, isToday: true })).toBe(0);
  });

  it('grows a running fire to now on today', () => {
    expect(fireWidthPx(running(), axis(), 1000, { nowMs: 300_000, isToday: true })).toBe(200);
  });

  it('does not grow a running fire on another day', () => {
    expect(fireWidthPx(running(), axis(), 1000, { nowMs: 300_000, isToday: false })).toBe(0);
  });

  it('does not grow a fire that is not running', () => {
    const fire = dayFire({ status: 'SUCCEEDED', startedAt: iso(100_000), completedAt: null });
    expect(fireWidthPx(fire, axis(), 1000, { nowMs: 300_000, isToday: true })).toBe(0);
  });

  it('does not grow a running fire that has no start', () => {
    expect(fireWidthPx(running({ startedAt: null }), axis(), 1000, { nowMs: 300_000, isToday: true })).toBe(0);
  });

  it('draws a running fire that already has an end by its duration, not to now', () => {
    const fire = running({ completedAt: iso(160_000) });
    expect(fireWidthPx(fire, axis(), 1000, { nowMs: 900_000, isToday: true })).toBe(60);
  });

  it('never grows to a now that is before the start', () => {
    expect(fireWidthPx(running(), axis(), 1000, { nowMs: 0, isToday: true })).toBe(0);
  });

  it('takes the clock as not-today by default', () => {
    const early = running({ startedAt: '1969-12-31T00:00:00Z' });
    expect(fireWidthPx(early, { startMs: -86_400_000, endMs: 86_400_000 }, 1000)).toBe(0);
  });

  it('draws a finished fire by its duration', () => {
    expect(fireWidthPx(dayFire(), { startMs: 0, endMs: 55_000 * 10 }, 1000)).toBe(100);
  });
});

describe('fireStatusStyle fallbacks', () => {
  it('reads an unknown status as itself, hollow and quiet', () => {
    expect(fireStatusStyle('WEIRD')).toMatchObject({ label: 'WEIRD', tone: 'off', hollow: true });
  });

  it('reads a missing status as a dash', () => {
    expect(fireStatusStyle(undefined).label).toBe('—');
    expect(fireStatusStyle('').label).toBe('—');
  });
});

describe('isFuture', () => {
  it('is true only for a fire still to run, and false for a missing fire', () => {
    expect(isFuture(dayFire({ status: 'SCHEDULED' }))).toBe(true);
    expect(isFuture(dayFire({ status: 'RUNNING' }))).toBe(false);
    expect(isFuture(undefined)).toBe(false);
    expect(isFuture(null)).toBe(false);
  });
});

describe('lastHappened', () => {
  it('is null for a row with nothing that has happened', () => {
    const row = { fires: [dayFire({ status: 'SCHEDULED' }), dayFire({ status: 'MISSED' })] };
    expect(lastHappened(row)).toBeNull();
    expect(lastHappened({ fires: [] })).toBeNull();
  });

  it('is the newest fire that has happened, ignoring those to run or missed', () => {
    const early = dayFire({ at: '2026-11-03T01:00:00Z', status: 'FAILED' });
    const late = dayFire({ at: '2026-11-03T03:00:00Z', status: 'SUCCEEDED' });
    const row = { fires: [early, late, dayFire({ at: '2026-11-03T05:00:00Z', status: 'SCHEDULED' })] };
    expect(lastHappened(row)).toBe(late);
  });
});

describe('buildRows edges', () => {
  const pausedJob = (overrides = {}) => ({
    jobKey: 'paused_cron', displayName: 'Paused cron', status: 'PAUSED', scheduleType: 'CRON', ...overrides,
  });

  it('has no rows at all without a day, even for paused jobs', () => {
    expect(buildRows(null, [pausedJob()])).toEqual([]);
    expect(buildRows(undefined, undefined)).toEqual([]);
  });

  it('keeps a row for a paused job the day does not list, and not for other jobs it does not list', () => {
    const jobs = [
      pausedJob(),
      pausedJob({ jobKey: 'paused_delay', displayName: 'Paused delay', scheduleType: 'FIXED_DELAY' }),
      { jobKey: 'active', displayName: 'Active', status: 'ACTIVE', scheduleType: 'CRON' },
    ];
    const rows = buildRows({ jobs: [] }, jobs);
    expect(rows.map((r) => r.jobKey)).toEqual(['paused_cron']);
    expect(rows[0]).toMatchObject({ name: 'Paused cron', fires: [], wouldBe: [], paused: true, scheduleType: 'CRON' });
  });

  it('does not duplicate a paused job the day does list', () => {
    const entry = dayJob({ jobKey: 'paused_cron', displayName: 'Paused cron', fires: [dayFire()] });
    const rows = buildRows({ jobs: [entry] }, [pausedJob()]);
    expect(rows).toHaveLength(1);
    expect(rows[0].fires).toHaveLength(1);
  });

  it('builds the missing paused row for a day with no jobs list at all', () => {
    expect(buildRows({}, [pausedJob()]).map((r) => r.jobKey)).toEqual(['paused_cron']);
  });

  it('drops a scheduled entry with no fires that is not paused, including one with no fires list', () => {
    const noList = { jobKey: 'a', displayName: 'A', kind: 'SCHEDULED' };
    const empty = dayJob({ jobKey: 'b', displayName: 'B', fires: [] });
    expect(buildRows({ jobs: [noList, empty] }, [])).toEqual([]);
  });

  it('ignores repeating entries', () => {
    expect(buildRows({ jobs: [dayRepeatingJob()] }, [])).toEqual([]);
  });

  it('takes the job facts from the list, and defaults them without one', () => {
    const entry = dayJob({ jobKey: 'j', displayName: 'J' });
    const withMeta = buildRows({ jobs: [entry] }, [{
      jobKey: 'j', status: 'ACTIVE', scheduleType: 'SOLAR_OFFSET', catalogue: { usesClaude: true, alias: 'Sky eval' },
    }])[0];
    expect(withMeta).toMatchObject({ scheduleType: 'SOLAR_OFFSET', paused: false, usesClaude: true, alias: 'Sky eval' });
    const bare = buildRows({ jobs: [entry] }, undefined)[0];
    expect(bare).toMatchObject({ scheduleType: null, paused: false, usesClaude: false, alias: null });
    const noCatalogue = buildRows({ jobs: [entry] }, [{ jobKey: 'j', status: 'ACTIVE' }])[0];
    expect(noCatalogue).toMatchObject({ usesClaude: false, alias: null });
    const notClaude = buildRows({ jobs: [entry] }, [{ jobKey: 'j', catalogue: { usesClaude: 'yes' } }])[0];
    expect(notClaude.usesClaude).toBe(false);
  });

  it('sorts a row\'s fires by time, an unreadable time first, and splits off the paused would-be fires', () => {
    const entry = dayJob({
      fires: [
        dayFire({ at: '2026-11-03T05:00:00Z' }),
        dayFire({ at: 'not a time' }),
        dayFire({ at: '2026-11-03T02:00:00Z', status: 'PAUSED' }),
        dayFire({ at: '2026-11-03T03:00:00Z' }),
        dayFire({ at: '2026-11-03T01:00:00Z', status: 'PAUSED' }),
      ],
    });
    const [row] = buildRows({ jobs: [entry] }, []);
    expect(row.fires.map((f) => f.at)).toEqual(['not a time', '2026-11-03T03:00:00Z', '2026-11-03T05:00:00Z']);
    expect(row.wouldBe.map((f) => f.at)).toEqual(['2026-11-03T01:00:00Z', '2026-11-03T02:00:00Z']);
  });

  it('orders rows by first fire, a would-be fire counting, then no fire last, then by name', () => {
    const at = (time, status = 'SUCCEEDED') => [dayFire({ at: time, status })];
    const jobs = [
      dayJob({ jobKey: 'zeta', displayName: 'Zeta', fires: at('2026-11-03T04:00:00Z') }),
      dayJob({ jobKey: 'alpha', displayName: 'Alpha', fires: at('2026-11-03T04:00:00Z') }),
      dayJob({ jobKey: 'wb', displayName: 'Would be', fires: at('2026-11-03T02:00:00Z', 'PAUSED') }),
      dayJob({ jobKey: 'none', displayName: 'Nothing', fires: [] }),
      dayJob({ jobKey: 'early', displayName: 'Early', fires: at('2026-11-03T01:00:00Z') }),
      dayJob({ jobKey: 'junk', displayName: 'Junk time', fires: at('garbage') }),
    ];
    const rows = buildRows({ jobs }, [{ jobKey: 'none', status: 'PAUSED', scheduleType: 'CRON' }]);
    // 'garbage' reads as time 0 when sorting fires but as Infinity for the row, so the row sorts last.
    expect(rows.map((r) => r.jobKey)).toEqual(['early', 'wb', 'alpha', 'zeta', 'junk', 'none']);
  });
});

describe('rowAriaLabel edges', () => {
  const row = (overrides = {}) => ({
    name: 'Nightly', fires: [], wouldBe: [], paused: false, usesClaude: false, alias: null, ...overrides,
  });

  it('says no fires for a row with nothing, and nothing of that for a paused one', () => {
    expect(rowAriaLabel(row())).toBe('Nightly, no fires');
    expect(rowAriaLabel(row({ paused: true }))).toBe('Nightly, paused');
  });

  it('gives the next fire for a row with only fires to come', () => {
    expect(rowAriaLabel(row({ fires: [dayFire({ status: 'SCHEDULED', at: '2026-11-03T04:00:00Z' })] })))
      .toBe('Nightly, next fire 04:00');
  });

  it('says a lone missed fire was missed, never "no fires"', () => {
    expect(rowAriaLabel(row({ fires: [dayFire({ status: 'MISSED', at: '2026-11-03T04:00:00Z' })] })))
      .toBe('Nightly, missed at 04:00');
  });

  it('gives the last run with its time and how long it took', () => {
    expect(rowAriaLabel(row({ fires: [dayFire()] }))).toBe('Nightly, last run ok at 01:00, took 55s');
  });

  it('gives the last run without a duration when it has none', () => {
    const fire = dayFire({ status: 'RUNNING', completedAt: null });
    expect(rowAriaLabel(row({ fires: [fire] }))).toBe('Nightly, last run running at 01:00');
  });

  it('lists several missed fires, a run and the flags in order', () => {
    const fires = [
      dayFire({ at: '2026-11-03T01:00:00Z' }),
      dayFire({ at: '2026-11-03T02:00:00Z', status: 'MISSED' }),
      dayFire({ at: '2026-11-03T03:00:00Z', status: 'MISSED' }),
    ];
    expect(rowAriaLabel(row({ fires, paused: true, usesClaude: true, alias: 'Sky eval' })))
      .toBe('Nightly, last run ok at 01:00, took 55s, missed at 02:00 and 03:00, paused, uses Claude, Sky eval');
  });

  it('describes would-be fires in the singular and the plural, and none when there are none', () => {
    const one = row({ wouldBe: [dayFire({ at: '2026-11-03T04:00:00Z', status: 'PAUSED' })], paused: true });
    expect(rowAriaLabel(one)).toBe('Nightly, paused, would-be fire at 04:00');
    const two = row({
      wouldBe: [dayFire({ at: '2026-11-03T04:00:00Z' }), dayFire({ at: '2026-11-03T05:00:00Z' })], paused: true,
    });
    expect(rowAriaLabel(two)).toBe('Nightly, paused, would-be fires at 04:00 and 05:00');
  });

  it('copes with a row that has no would-be list', () => {
    expect(rowAriaLabel({ name: 'Nightly', fires: [], paused: true })).toBe('Nightly, paused');
  });
});

describe('summaryCounts edges', () => {
  const zero = { succeeded: 0, partial: 0, failed: 0, missed: 0, running: 0, skipped: 0, toRun: 0 };

  it('counts every status, and nothing else', () => {
    const fires = ['SUCCEEDED', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'MISSED', 'RUNNING', 'SKIPPED', 'SCHEDULED']
      .map((status) => dayFire({ status }));
    expect(summaryCounts({ jobs: [dayJob({ fires })] })).toEqual({
      succeeded: 2, partial: 1, failed: 1, missed: 1, running: 1, skipped: 1, toRun: 1,
    });
  });

  it('ignores a fire with a status it does not count, and repeating jobs', () => {
    const fires = [dayFire({ status: 'PAUSED' }), dayFire({ status: 'WEIRD' }), dayFire({ status: undefined })];
    expect(summaryCounts({ jobs: [dayJob({ fires }), dayRepeatingJob()] })).toEqual(zero);
  });

  it('does not count fires carried by a repeating entry', () => {
    const repeating = dayRepeatingJob({ fires: [dayFire({ status: 'FAILED' }), dayFire({ status: 'SUCCEEDED' })] });
    expect(summaryCounts({ jobs: [repeating] })).toEqual(zero);
  });

  it('is all zero for no day, no jobs, or an entry with no fires list', () => {
    expect(summaryCounts(null)).toEqual(zero);
    expect(summaryCounts({})).toEqual(zero);
    expect(summaryCounts({ jobs: [{ jobKey: 'a', kind: 'SCHEDULED' }] })).toEqual(zero);
  });
});

describe('needsALook edges', () => {
  it('is empty for no day, no jobs, or entries with no fires or problems', () => {
    const empty = { items: [], total: 0, more: 0 };
    expect(needsALook(null)).toEqual(empty);
    expect(needsALook({})).toEqual(empty);
    expect(needsALook({ jobs: [{ jobKey: 'a', kind: 'SCHEDULED' }, { jobKey: 'b', kind: 'REPEATING' }] })).toEqual(empty);
  });

  it('lists failed, partial and missed fires only', () => {
    const fires = ['SUCCEEDED', 'FAILED', 'PARTIAL', 'MISSED', 'RUNNING', 'SKIPPED', 'SCHEDULED']
      .map((status, i) => dayFire({ status, at: `2026-11-03T0${i}:00:00Z` }));
    const { items } = needsALook({ jobs: [dayJob({ fires })] });
    expect(items.map((i) => i.status)).toEqual(['FAILED', 'PARTIAL', 'MISSED']);
  });

  it('keeps summaries and gives an empty one when none is served', () => {
    const day = {
      jobs: [
        dayJob({ fires: [dayFire({ status: 'FAILED', summary: 'boom', at: '2026-11-03T01:00:00Z' })] }),
        dayJob({ jobKey: 'b', fires: [dayFire({ status: 'FAILED', summary: undefined, at: '2026-11-03T02:00:00Z' })] }),
        dayRepeatingJob({ problems: [
          { status: 'FAILED', at: '2026-11-03T03:00:00Z', summary: 'poll down' },
          { status: 'PARTIAL', at: '2026-11-03T04:00:00Z' },
        ] }),
      ],
    };
    const { items } = needsALook(day);
    expect(items.map((i) => [i.summary, i.repeating])).toEqual([
      ['boom', false], ['', false], ['poll down', true], ['', true],
    ]);
  });

  it('counts the problems the server left out and shows the newest few, oldest of those first', () => {
    const fires = [1, 2, 3, 4, 5].map((h) => dayFire({ status: 'FAILED', at: `2026-11-03T0${h}:00:00Z` }));
    const day = { jobs: [dayJob({ fires }), dayRepeatingJob({ problemsOmitted: 4 })] };
    const result = needsALook(day, 3);
    expect(result.items.map((i) => i.at)).toEqual(['2026-11-03T03:00:00Z', '2026-11-03T04:00:00Z', '2026-11-03T05:00:00Z']);
    expect(result.total).toBe(9);
    expect(result.more).toBe(6);
  });

  it('shows everything when under the limit and treats a missing omitted count as none', () => {
    const day = { jobs: [dayJob({ fires: [dayFire({ status: 'FAILED' })] }), { jobKey: 'r', kind: 'REPEATING', problems: [] }] };
    const result = needsALook(day, 3);
    expect(result.items).toHaveLength(1);
    expect(result.total).toBe(1);
    expect(result.more).toBe(0);
  });

  it('sorts by time, an unreadable time first', () => {
    const fires = [
      dayFire({ status: 'FAILED', at: '2026-11-03T05:00:00Z' }),
      dayFire({ status: 'FAILED', at: 'junk' }),
      dayFire({ status: 'FAILED', at: '2026-11-03T02:00:00Z' }),
    ];
    expect(needsALook({ jobs: [dayJob({ fires })] }).items.map((i) => i.at))
      .toEqual(['junk', '2026-11-03T02:00:00Z', '2026-11-03T05:00:00Z']);
  });
});

describe('dayInRange edges', () => {
  it('includes exactly seven days either side of today and no more', () => {
    expect(dayInRange('2026-11-10', '2026-11-03')).toBe(true);
    expect(dayInRange('2026-11-11', '2026-11-03')).toBe(false);
    expect(dayInRange('2026-10-27', '2026-11-03')).toBe(true);
    expect(dayInRange('2026-10-26', '2026-11-03')).toBe(false);
  });
});
