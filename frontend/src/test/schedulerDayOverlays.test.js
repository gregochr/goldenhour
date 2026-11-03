import { describe, it, expect } from 'vitest';
import { buildRows, closeUpAxis, dayAxis, gridlines, pictureAxis, readZoom, rowAriaLabel, writeZoom, ZOOM_CLOSE_UP } from '../utils/schedulerDay.js';
import {
  buildLinks,
  buildRepeatingRows,
  cadenceWords,
  collectArrowPath,
  relatedKeys,
  repeatingAriaLabel,
  repeatingIntervalMs,
  repeatingLayout,
  repeatingRowTip,
  staggerArrowPath,
  watchdogWindows,
} from '../utils/schedulerDayOverlays.js';
import { dayData, dayFire, dayJob, dayRepeatingJob } from './schedulerDayFixtures.js';
import { catalogue, job, pollingJob } from './schedulerFixtures.js';

const iso = (ms) => new Date(ms).toISOString();
const NORMAL = { dayStart: '2026-11-03T00:00:00Z', dayEnd: '2026-11-04T00:00:00Z' };
const SPRING = { dayStart: '2026-03-29T00:00:00Z', dayEnd: '2026-03-29T23:00:00Z' };
const AUTUMN = { dayStart: '2026-10-24T23:00:00Z', dayEnd: '2026-10-26T00:00:00Z' };

describe('the close-up axis', () => {
  it('is 02:00 to 05:00 UK wall-clock on a normal day, with a line every 30 minutes', () => {
    const axis = closeUpAxis(NORMAL);
    expect([iso(axis.startMs), iso(axis.endMs)]).toEqual(['2026-11-03T02:00:00.000Z', '2026-11-03T05:00:00.000Z']);
    const { grid } = pictureAxis(NORMAL, ZOOM_CLOSE_UP);
    expect(grid.map((l) => l.label)).toEqual(['02:00', '02:30', '03:00', '03:30', '04:00', '04:30', '05:00']);
    expect(iso(grid[1].ms)).toBe('2026-11-03T02:30:00.000Z');
    expect(iso(grid[6].ms)).toBe('2026-11-03T05:00:00.000Z');
  });

  it('is 02:00 to 05:00 BST on the 23-hour spring day (01:00Z to 04:00Z), still three hours long', () => {
    const axis = closeUpAxis(SPRING);
    expect([iso(axis.startMs), iso(axis.endMs)]).toEqual(['2026-03-29T01:00:00.000Z', '2026-03-29T04:00:00.000Z']);
    expect(pictureAxis(SPRING, ZOOM_CLOSE_UP).grid.map((l) => l.label)).toEqual(['02:00', '02:30', '03:00', '03:30', '04:00', '04:30', '05:00']);
  });

  it('is 02:00 to 05:00 GMT on the 25-hour autumn day (02:00Z to 05:00Z)', () => {
    const axis = closeUpAxis(AUTUMN);
    expect([iso(axis.startMs), iso(axis.endMs)]).toEqual(['2026-10-25T02:00:00.000Z', '2026-10-25T05:00:00.000Z']);
  });

  it('is null for a day with no axis, and the whole-day picture keeps its 3-hour lines', () => {
    expect(closeUpAxis({})).toBeNull();
    expect(pictureAxis({}, ZOOM_CLOSE_UP)).toBeNull();
    expect(pictureAxis(NORMAL, 'day').grid).toHaveLength(9);
    expect(gridlines(dayAxis(NORMAL), 3)).toHaveLength(9);
  });
});

describe('the remembered zoom', () => {
  it('reads the whole day when nothing, or something unknown, is stored', () => {
    window.localStorage.removeItem('photocast.scheduler.dayZoom');
    expect(readZoom()).toBe('day');
    window.localStorage.setItem('photocast.scheduler.dayZoom', 'sideways');
    expect(readZoom()).toBe('day');
  });

  it('remembers the close-up', () => {
    writeZoom('closeup');
    expect(readZoom()).toBe('closeup');
    writeZoom('day');
    expect(readZoom()).toBe('day');
  });

  it('survives a storage that throws', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', { configurable: true, get() { throw new Error('blocked'); } });
    try {
      expect(readZoom()).toBe('day');
      expect(() => writeZoom('closeup')).not.toThrow();
    } finally {
      Object.defineProperty(window, 'localStorage', original);
    }
  });
});

describe('cadenceWords', () => {
  it('says "every 1 min" and "every 5 min" for fixed delays and minute crons', () => {
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 60_000 })).toBe('every 1 min');
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 300_000 })).toBe('every 5 min');
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 3_600_000 })).toBe('every hour');
    expect(cadenceWords({ scheduleType: 'FIXED_DELAY', delayMs: 30_000 })).toBe('every 30 sec');
    expect(cadenceWords({ scheduleType: 'CRON', cronExpression: '0 * * * * *' })).toBe('every 1 min');
    expect(cadenceWords({ scheduleType: 'CRON', cronExpression: '0 */5 * * * *' })).toBe('every 5 min');
  });

  it('falls back to the cron words, or the raw cron it cannot read', () => {
    expect(cadenceWords({ scheduleType: 'CRON', cronExpression: '0 20 * * * *' })).toBe('every hour at :20');
    expect(cadenceWords({ scheduleType: 'CRON', cronExpression: '0 0 */2 * * *' })).toBe('0 0 */2 * * *');
    expect(cadenceWords(null)).toBe('');
  });
});

describe('buildRepeatingRows', () => {
  const poll = dayRepeatingJob({ ticks: null, schedule: { scheduleType: 'FIXED_DELAY', cronExpression: null, delayMs: 60_000, timeZone: null } });
  const hourly = dayRepeatingJob({
    jobKey: 'hourly', displayName: 'Hourly', ticks: ['2026-11-03T01:00:00Z', '2026-11-03T00:00:00Z'],
    schedule: { scheduleType: 'CRON', cronExpression: '0 0 * * * *', delayMs: null, timeZone: 'UTC' },
  });

  it('keeps only REPEATING jobs, ticks as sorted ms (null stays null), the rarest first', () => {
    const rows = buildRepeatingRows(dayData({ jobs: [dayJob(), poll, hourly] }), []);
    expect(rows.map((r) => r.jobKey)).toEqual(['hourly', 'aurora_polling']);
    expect(rows[0].ticks).toEqual([Date.parse('2026-11-03T00:00:00Z'), Date.parse('2026-11-03T01:00:00Z')]);
    expect(rows[1].ticks).toBeNull();
    expect(rows[1].cadence).toBe('every 1 min');
  });

  it('takes paused, alias and £ from the jobs list and drops a job that is off in config', () => {
    const jobs = [
      pollingJob({ status: 'PAUSED', catalogue: catalogue({ alias: 'The weather watcher', usesClaude: true }) }),
      job({ jobKey: 'hourly', status: 'DISABLED_BY_CONFIG' }),
    ];
    const rows = buildRepeatingRows(dayData({ jobs: [poll, hourly] }), jobs);
    expect(rows.map((r) => r.jobKey)).toEqual(['aurora_polling']);
    expect(rows[0]).toMatchObject({ paused: true, alias: 'The weather watcher', usesClaude: true });
    expect(repeatingAriaLabel(rows[0])).toBe('Aurora Polling, every 1 min, paused, uses Claude, The weather watcher');
  });

  it('counts the problems, including the ones the server left out, in the accessible name', () => {
    const rows = buildRepeatingRows(dayData({
      jobs: [dayRepeatingJob({
        problems: [{ status: 'FAILED', at: '2026-11-03T04:00:00Z', summary: 'x' }], problemsOmitted: 2,
      })],
    }), []);
    expect(repeatingAriaLabel(rows[0])).toContain('3 problems');
  });
});

describe('repeatingLayout', () => {
  const axis = dayAxis(NORMAL);
  const row = (ticks) => ({ ticks });
  const every = (minutes) => Array.from({ length: Math.floor(1440 / minutes) }, (_, i) => Date.parse(NORMAL.dayStart) + i * minutes * 60_000);

  it('draws the served ticks when they are at least 3px apart, and a band when the closest pair is closer', () => {
    expect(repeatingLayout(row(every(60)), axis, 360)).toMatchObject({ shape: 'ticks' });
    expect(repeatingLayout(row(every(60)), axis, 360).ticks).toHaveLength(24);
    // 10 minutes across 360px is 2.5px.
    expect(repeatingLayout(row(every(10)), axis, 360).shape).toBe('band');
    // The same ticks, 900px wide, are 6.25px apart.
    expect(repeatingLayout(row(every(10)), axis, 900).shape).toBe('ticks');
  });

  it('draws a band when the server listed none (null ticks), and no ticks for an empty list', () => {
    expect(repeatingLayout(row(null), axis, 900).shape).toBe('band');
    expect(repeatingLayout(row([]), axis, 900)).toEqual({ shape: 'ticks', ticks: [] });
  });

  it('counts only the ticks on the axis: the close-up of an hourly job is three or four ticks', () => {
    const closeUp = closeUpAxis(NORMAL);
    expect(repeatingLayout(row(every(60)), closeUp, 900).ticks).toHaveLength(4);
  });
});

describe('watchdogWindows', () => {
  const NIGHT = 'near_term_batch_evaluation';
  const rowsOf = (jobKey, fires) => buildRows(dayData({ jobs: [dayJob({ jobKey, fires })] }), []);
  const SOLAR_ENTRY = {
    jobKey: NIGHT, event: 'SUNRISE', eventAt: '2026-11-03T07:03:00Z', opensAt: '2026-11-03T06:43:00Z', closesAt: '2026-11-03T08:03:00Z',
    startGraceMinutes: 30, budgetMinutes: null, deadlineAt: null,
  };
  const EVENING_ENTRY = {
    jobKey: 'evening_sunrise_refresh', event: null, eventAt: null, opensAt: null, closesAt: null, startGraceMinutes: 30,
    budgetMinutes: 90, deadlineAt: '2026-11-03T18:25:00Z',
  };
  const evening = (fires) => rowsOf('evening_sunrise_refresh', fires);

  it('is the served opensAt to closesAt for a solar cycle, with its event, owned by the fire just before it', () => {
    const windows = watchdogWindows(dayData({ watchdog: [SOLAR_ENTRY] }), rowsOf(NIGHT, [dayFire({ at: '2026-11-03T03:00:00Z' })]));
    expect(windows).toHaveLength(1);
    expect(windows[0]).toMatchObject({ jobKey: NIGHT, event: 'Sunrise', budgetOnly: false, text: 'Watchdog window 06:43–08:03' });
    expect(iso(windows[0].deadlineMs)).toBe('2026-11-03T08:03:00.000Z');
    expect(iso(windows[0].ownerAtMs)).toBe('2026-11-03T03:00:00.000Z');
  });

  it('belongs to one fire: the newest eligible fire at or before the window closes, not every fire on the row', () => {
    const rows = rowsOf(NIGHT, [
      dayFire({ at: '2026-11-03T03:00:00Z' }),
      dayFire({ at: '2026-11-03T07:00:00Z' }),
      dayFire({ at: '2026-11-03T09:00:00Z' }),
    ]);
    const [owned] = watchdogWindows(dayData({ watchdog: [SOLAR_ENTRY] }), rows);
    expect(iso(owned.ownerAtMs)).toBe('2026-11-03T07:00:00.000Z');
  });

  it('draws the evening refresh deadline from the served deadlineAt only, never fire plus budget', () => {
    const windows = watchdogWindows(dayData({ watchdog: [EVENING_ENTRY] }), evening([dayFire({ at: '2026-11-03T17:00:00Z' })]));
    expect(windows).toHaveLength(1);
    // 17:00 plus 90 minutes would be 18:30; the watchdog's own deadline (first batch plus budget) is 18:25.
    expect(iso(windows[0].deadlineMs)).toBe('2026-11-03T18:25:00.000Z');
    expect(windows[0]).toMatchObject({ budgetOnly: true, event: '90 min budget from the first batch', text: 'Watchdog window 17:00–18:25' });
  });

  it('says "2 h" for a budget of whole hours, and draws nothing when no deadlineAt was served', () => {
    const fires = [dayFire({ at: '2026-11-03T17:00:00Z' })];
    const [hours] = watchdogWindows(dayData({ watchdog: [{ ...EVENING_ENTRY, budgetMinutes: 120 }] }), evening(fires));
    expect(hours.event).toBe('2 h budget from the first batch');
    expect(watchdogWindows(dayData({ watchdog: [{ ...EVENING_ENTRY, deadlineAt: null }] }), evening(fires))).toEqual([]);
  });

  it('draws none for an event that does not occur, or a cycle with no row', () => {
    const none = dayData({
      watchdog: [{ ...SOLAR_ENTRY, opensAt: null, closesAt: null, eventAt: null }, { ...SOLAR_ENTRY, jobKey: 'phantom' }],
    });
    expect(watchdogWindows(none, rowsOf(NIGHT, [dayFire()]))).toEqual([]);
  });

  it('draws none on SKIPPED, MISSED or PAUSED fires, nor on a FAILED fire with no run; a FAILED fire with a run owns one', () => {
    const entry = dayData({ watchdog: [SOLAR_ENTRY] });
    ['SKIPPED', 'MISSED', 'PAUSED'].forEach((status) => {
      expect(watchdogWindows(entry, rowsOf(NIGHT, [dayFire({ at: '2026-11-03T03:00:00Z', status })]))).toEqual([]);
    });
    expect(watchdogWindows(entry, rowsOf(NIGHT, [dayFire({ at: '2026-11-03T03:00:00Z', status: 'FAILED', runLinked: false })]))).toEqual([]);
    expect(watchdogWindows(entry, rowsOf(NIGHT, [dayFire({ at: '2026-11-03T03:00:00Z', status: 'FAILED', runLinked: true })]))).toHaveLength(1);
    expect(watchdogWindows(entry, rowsOf(NIGHT, [dayFire({ at: '2026-11-03T03:00:00Z', status: 'SCHEDULED', startedAt: null, completedAt: null })]))).toHaveLength(1);
  });
});

describe('buildLinks and relatedKeys', () => {
  const jobs = [
    job({ jobKey: 'near', catalogue: catalogue({ resultsCollectedBy: ['poll', 'retired'], staggeredBefore: [] }) }),
    job({ jobKey: 'drive', catalogue: catalogue({ staggeredBefore: ['region'] }) }),
    job({ jobKey: 'poll', catalogue: catalogue({ collectsResultsFrom: ['near'], resultsCollectedBy: [] }) }),
    job({ jobKey: 'region', catalogue: catalogue({ staggeredAfter: ['drive'] }) }),
    job({ jobKey: 'lonely', catalogue: null }),
  ];

  it('returns only the links the catalogue declares, in the forward direction, between jobs that have rows', () => {
    const links = buildLinks(jobs, ['near', 'poll', 'drive', 'region', 'lonely']);
    expect(links).toEqual([
      { kind: 'collect', from: 'near', to: 'poll' },
      { kind: 'stagger', from: 'drive', to: 'region' },
    ]);
  });

  it('leaves a link out when either job has no row, and invents none for a job with no catalogue', () => {
    expect(buildLinks(jobs, ['near', 'drive', 'lonely'])).toEqual([]);
    expect(buildLinks(null, ['near'])).toEqual([]);
  });

  it('relates the selected job to the jobs it is linked to either way, and nothing when it has no row', () => {
    const links = buildLinks(jobs, ['near', 'poll', 'drive', 'region']);
    expect([...relatedKeys('poll', links, ['near', 'poll', 'drive', 'region'])].sort()).toEqual(['near', 'poll']);
    expect([...relatedKeys('near', links, ['near', 'poll', 'drive', 'region'])].sort()).toEqual(['near', 'poll']);
    expect(relatedKeys('elsewhere', links, ['near', 'poll'])).toBeNull();
    expect(relatedKeys(null, links, ['near'])).toBeNull();
  });
});

describe('arrow paths', () => {
  it('curves a collect arrow from the foot of the fire to the top of the collecting row', () => {
    expect(collectArrowPath(100, 50, 150, 130)).toBe('M100 57 C 100 104.85, 130 113.55, 130 144');
  });

  it('runs a stagger arrow from just past the first fire to just before the second', () => {
    expect(staggerArrowPath(100, 50, 200, 74)).toBe('M104 50 C 191 50, 167 74, 191 74');
  });
});

describe('repeatingIntervalMs and repeatingRowTip', () => {
  const hours = (list) => list.map((h) => Date.parse(`2026-11-03T${String(h).padStart(2, '0')}:00:00Z`));

  it('is a fixed delay\'s own interval, a cron\'s median tick gap, or its minute step; unknown is Infinity', () => {
    expect(repeatingIntervalMs({ schedule: { scheduleType: 'FIXED_DELAY', delayMs: 300_000 } })).toBe(300_000);
    expect(repeatingIntervalMs({ schedule: { scheduleType: 'CRON' }, ticks: hours([1, 2, 3, 6]) })).toBe(3_600_000);
    expect(repeatingIntervalMs({ schedule: { scheduleType: 'CRON', cronExpression: '0 */5 * * * *' }, ticks: null })).toBe(300_000);
    expect(repeatingIntervalMs({ schedule: { scheduleType: 'CRON', cronExpression: '0 * * * * *' }, ticks: null })).toBe(60_000);
    expect(repeatingIntervalMs({ schedule: { scheduleType: 'CRON', cronExpression: '0 0 */2 * * *' }, ticks: null })).toBe(Infinity);
  });

  it('says "today" in a repeating row\'s tooltip only on today, else "on this day"', () => {
    const row = { name: 'Poll', cadence: 'every 1 min', paused: false, alias: null, problems: [{}, {}], problemsOmitted: 0 };
    expect(repeatingRowTip(row, true).note).toBe('2 problems today');
    expect(repeatingRowTip(row, false).note).toBe('2 problems on this day');
    expect(repeatingRowTip(row).note).toBe('2 problems today');
  });
});

describe('a row with would-be fires', () => {
  it('lists the would-be times in its accessible name, and keeps them out of fires', () => {
    const rows = buildRows(
      dayData({ jobs: [dayJob({ fires: [dayFire({ at: '2026-11-03T10:15:00Z', status: 'PAUSED', startedAt: null, completedAt: null })] })] }),
      [job({ status: 'PAUSED' })],
    );
    expect(rows[0].fires).toEqual([]);
    expect(rows[0].wouldBe).toHaveLength(1);
    expect(rowAriaLabel(rows[0])).toBe('Nightly batch evaluation, paused, would-be fire at 10:15');
  });
});
