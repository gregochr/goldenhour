import { describe, it, expect } from 'vitest';
import {
  countByType, describeCron, describeSchedule, fireDuration, fireStatusView, formatClock, formatFireLabel, formatNextFire,
  groupJobs, jobMatchesFilters, jobMatchesSearch, jobStatusView, relativeTime, sortJobs,
} from '../utils/schedulerView.js';
import { isCycleJob, isForecastCycleJob } from '../utils/schedulerSolar.js';

// Saturday 31 October 2026, noon UTC (GMT: British Summer Time ended on 25 October).
const NOW = new Date('2026-10-31T12:00:00Z');

describe('formatNextFire', () => {
  it('reads today as the clock alone', () => {
    expect(formatNextFire('2026-10-31T17:30:00Z', NOW)).toBe('17:30');
  });

  it('adds the weekday for the next six days', () => {
    expect(formatNextFire('2026-11-01T02:30:00Z', NOW)).toBe('Sun 02:30');
    expect(formatNextFire('2026-11-06T09:00:00Z', NOW)).toBe('Fri 09:00');
  });

  it('adds the date from the seventh day', () => {
    expect(formatNextFire('2026-11-07T06:00:00Z', NOW)).toBe('Sat 7 Nov 06:00');
  });

  it('reads the UK day, not the UTC day: 23:30Z in BST is already tomorrow', () => {
    const summer = new Date('2026-07-10T12:00:00Z');
    expect(formatNextFire('2026-07-10T23:30:00Z', summer)).toBe('Sat 00:30');
  });

  it('is empty for no instant', () => {
    expect(formatNextFire('', NOW)).toBe('');
    expect(formatNextFire(null, NOW)).toBe('');
  });
});

describe('relativeTime', () => {
  it('counts back in seconds, minutes, hours and days', () => {
    expect(relativeTime('2026-10-31T11:59:30Z', { now: NOW })).toBe('30s ago');
    expect(relativeTime('2026-10-31T11:57:00Z', { now: NOW })).toBe('3 min ago');
    expect(relativeTime('2026-10-31T09:00:00Z', { now: NOW })).toBe('3h ago');
    expect(relativeTime('2026-10-28T12:00:00Z', { now: NOW })).toBe('3d ago');
  });

  it('counts forward with future, and never says "just now" for a time ahead', () => {
    expect(relativeTime('2026-10-31T12:03:00Z', { future: true, now: NOW })).toBe('in 3 min');
    expect(relativeTime('2026-11-01T02:00:00Z', { future: true, now: NOW })).toBe('in 14h');
    expect(relativeTime('2026-11-07T12:00:00Z', { future: true, now: NOW })).toBe('in 7d');
    expect(relativeTime('2026-10-31T11:00:00Z', { future: true, now: NOW })).toBe('just now');
  });

  it('reads an empty value as Never', () => {
    expect(relativeTime('', { now: NOW })).toBe('Never');
  });
});

describe('describeSchedule', () => {
  it('words a sun-relative rule, a cron, a fixed delay, and names the type under each', () => {
    expect(describeSchedule({ scheduleType: 'SOLAR_OFFSET', solarEvent: 'SUNRISE', solarOffsetMinutes: 255 }))
      .toEqual({ words: '4 h 15 min before sunrise', type: 'Sun-relative', recognised: true });
    expect(describeSchedule({ scheduleType: 'CRON', cronExpression: '0 0 4 1 * *' }))
      .toEqual({ words: '0 0 4 1 * *', type: 'Cron', recognised: true });
    expect(describeSchedule({ scheduleType: 'FIXED_DELAY', fixedDelayMs: 300000 }))
      .toEqual({ words: 'Every 5 minutes', type: 'Fixed delay', recognised: true });
  });

  it('flags an unknown or empty type as unrecognised', () => {
    expect(describeSchedule({ scheduleType: 'LUNAR_PHASE' })).toMatchObject({ recognised: false, type: 'LUNAR_PHASE' });
    expect(describeSchedule({ scheduleType: '' })).toMatchObject({ recognised: false, type: '(none)' });
  });

  it('says an incomplete sun-relative rule is incomplete', () => {
    expect(describeSchedule({ scheduleType: 'SOLAR_OFFSET', solarEvent: '', solarOffsetMinutes: 0 }).words)
      .toBe('Sun-relative rule incomplete');
  });
});

describe('jobStatusView', () => {
  it('names the three statuses, "Off in config" for the config-disabled one', () => {
    expect(jobStatusView('ACTIVE')).toEqual({ label: 'Active', tone: 'go' });
    expect(jobStatusView('PAUSED')).toEqual({ label: 'Paused', tone: 'amber' });
    expect(jobStatusView('DISABLED_BY_CONFIG')).toEqual({ label: 'Off in config', tone: 'off' });
  });

  it('reads an unknown status as itself in the quiet ink', () => {
    expect(jobStatusView('RETIRED')).toEqual({ label: 'RETIRED', tone: 'off' });
  });
});

describe('filters and counts', () => {
  const jobs = [
    { jobKey: 'a', scheduleType: 'CRON', status: 'ACTIVE' },
    { jobKey: 'b', scheduleType: 'CRON', status: 'PAUSED' },
    { jobKey: 'c', scheduleType: 'SOLAR_OFFSET', status: 'ACTIVE' },
    { jobKey: 'd', scheduleType: 'WEEKLY_MAGIC', status: 'DISABLED_BY_CONFIG' },
  ];

  it('ANDs the type chip with the status chip', () => {
    const keys = (filters) => jobs.filter((j) => jobMatchesFilters(j, filters)).map((j) => j.jobKey);
    expect(keys({ type: 'all', status: null })).toEqual(['a', 'b', 'c', 'd']);
    expect(keys({ type: 'CRON', status: null })).toEqual(['a', 'b']);
    expect(keys({ type: 'CRON', status: 'PAUSED' })).toEqual(['b']);
    expect(keys({ type: 'SOLAR_OFFSET', status: 'PAUSED' })).toEqual([]);
    expect(keys({ type: 'all', status: 'DISABLED_BY_CONFIG' })).toEqual(['d']);
  });

  it('counts by type, an unrecognised type being only in All', () => {
    expect(countByType(jobs, 'all')).toBe(4);
    expect(countByType(jobs, 'CRON')).toBe(2);
    expect(countByType(jobs, 'SOLAR_OFFSET')).toBe(1);
    expect(countByType(jobs, 'FIXED_DELAY')).toBe(0);
  });
});

describe('sortJobs', () => {
  const at = (hours) => new Date(NOW.getTime() + hours * 3_600_000).toISOString();
  const jobs = [
    { jobKey: 'late', displayName: 'Late', status: 'ACTIVE', nextFireTime: at(30) },
    { jobKey: 'paused', displayName: 'Aardvark paused', status: 'PAUSED', nextFireTime: at(1) },
    { jobKey: 'soon', displayName: 'Soon', status: 'ACTIVE', nextFireTime: at(2) },
    { jobKey: 'none', displayName: 'No next', status: 'ACTIVE', nextFireTime: '' },
    { jobKey: 'off', displayName: 'Off', status: 'DISABLED_BY_CONFIG', nextFireTime: '' },
  ];
  const keys = (sort) => sortJobs(jobs, sort).map((j) => j.jobKey);

  it('puts active jobs by next fire first and everything else after, by name', () => {
    expect(keys({ key: 'next', dir: 'asc' })).toEqual(['soon', 'late', 'paused', 'none', 'off']);
  });

  it('reverses the active jobs when descending but keeps the rest last', () => {
    expect(keys({ key: 'next', dir: 'desc' })).toEqual(['late', 'soon', 'paused', 'none', 'off']);
  });

  it('sorts by name either way, and never mutates the input', () => {
    const before = jobs.map((j) => j.jobKey);
    expect(keys({ key: 'name', dir: 'asc' })).toEqual(['paused', 'late', 'none', 'off', 'soon']);
    expect(keys({ key: 'name', dir: 'desc' })).toEqual(['soon', 'off', 'none', 'late', 'paused']);
    expect(jobs.map((j) => j.jobKey)).toEqual(before);
  });
});

describe('the cycle job lists', () => {
  it('knows all three cycle jobs, and that only two may be sun-relative', () => {
    expect(isCycleJob('near_term_batch_evaluation')).toBe(true);
    expect(isCycleJob('intraday_forecast_refresh')).toBe(true);
    expect(isCycleJob('evening_sunrise_refresh')).toBe(true);
    expect(isCycleJob('tide_refresh')).toBe(false);
    expect(isForecastCycleJob('evening_sunrise_refresh')).toBe(false);
  });
});

describe('describeCron', () => {
  it('reads an hourly cron as "Every hour at :MM"', () => {
    expect(describeCron('0 20 * * * *')).toBe('Every hour at :20');
    expect(describeCron('0 5 * * * *')).toBe('Every hour at :05');
  });

  it('keeps the single-day weekly form', () => {
    expect(describeCron('0 0 2 * * MON')).toMatch(/^Every Monday at 0\d:00 (GMT|BST)$/);
  });

  it('keeps the daily form for a plain hour list', () => {
    expect(describeCron('0 30 3,15 * * *', 'Europe/London')).toBe('Daily at 03:30, 15:30 UK time');
  });

  it('reads a numeric single day by name', () => {
    expect(describeCron('0 0 3 * * 1', 'Europe/London')).toBe('Every Monday at 03:00 UK time');
  });

  it.each([
    '0 */5 * * * *',
    '0 0,30 3 * * *',
    '0 0-30 3 * * *',
    '0 0 */2 * * *',
    '0 0 2-5 * * *',
    '0 0 3 * * MON-FRI',
    '0 0 3 * * 1-5',
    '0 0 3 * * MON,FRI',
    '0 20 * * * MON',
  ])('returns %s unchanged', (cron) => {
    expect(describeCron(cron)).toBe(cron);
  });

  it('never outputs Invalid or NaN', () => {
    const fields = ['*', '0', '5', '*/5', '0,30', '2-5', '*/2', 'MON', 'MON-FRI', '1-5', 'x'];
    for (const min of fields) {
      for (const hour of fields) {
        for (const dow of fields) {
          const out = describeCron(`0 ${min} ${hour} * * ${dow}`);
          expect(out).not.toMatch(/Invalid|NaN/);
        }
      }
    }
  });
});

describe('groupJobs', () => {
  const j = (jobKey, scheduleType, nextFireTime = '') => ({ jobKey, displayName: jobKey, scheduleType, status: 'ACTIVE', nextFireTime });

  it('orders the groups Sun-relative, Cron, Fixed delay, Other, sorts each by next fire and drops empty ones', () => {
    const groups = groupJobs([
      j('d', 'FIXED_DELAY', '2026-10-31T12:01:00Z'),
      j('c2', 'CRON', '2026-11-01T01:00:00Z'),
      j('c1', 'CRON', '2026-10-31T13:00:00Z'),
      j('x', 'MYSTERY'),
    ]);
    expect(groups.map((g) => g.type)).toEqual(['CRON', 'FIXED_DELAY', 'OTHER']);
    expect(groups[0].jobs.map((x) => x.jobKey)).toEqual(['c1', 'c2']);
    expect(groups[0].subtitle).toBe('fixed clock times');
  });
});

describe('jobMatchesFilters: the Claude and failed chips', () => {
  const base = { type: 'all', status: null, claude: false, failed: false };

  it('keeps only jobs that use Claude when that chip is on', () => {
    expect(jobMatchesFilters({ catalogue: { usesClaude: true } }, { ...base, claude: true })).toBe(true);
    expect(jobMatchesFilters({ catalogue: { usesClaude: false } }, { ...base, claude: true })).toBe(false);
    expect(jobMatchesFilters({ catalogue: null }, { ...base, claude: true })).toBe(false);
  });

  it('keeps only jobs whose last fire failed when that chip is on', () => {
    expect(jobMatchesFilters({ lastFire: { status: 'FAILED' } }, { ...base, failed: true })).toBe(true);
    expect(jobMatchesFilters({ lastFire: { status: 'PARTIAL' } }, { ...base, failed: true })).toBe(false);
    expect(jobMatchesFilters({ lastFire: null }, { ...base, failed: true })).toBe(false);
  });

  it('passes a filter set with neither flag (the old shape) as before', () => {
    expect(jobMatchesFilters({ scheduleType: 'CRON', status: 'ACTIVE' }, { type: 'CRON', status: null })).toBe(true);
  });
});

describe('jobMatchesSearch', () => {
  const job = { displayName: 'Tide Refresh', catalogue: { alias: 'The tide filler', what: 'Fetches tides.', purpose: 'Places' } };

  it('matches the name, alias, what and purpose, ignoring case, and everything for blank text', () => {
    expect(['refresh', 'FILLER', 'fetches', 'places', '  ', ''].map((t) => jobMatchesSearch(job, t))).toEqual(Array(6).fill(true));
    expect(jobMatchesSearch(job, 'sunrise')).toBe(false);
  });

  it('searches the name alone for a job with no catalogue', () => {
    expect(jobMatchesSearch({ displayName: 'Mail', catalogue: null }, 'mail')).toBe(true);
    expect(jobMatchesSearch({ displayName: 'Mail', catalogue: null }, 'tides')).toBe(false);
  });
});

describe('fire formatting', () => {
  it('formats the UK clock of an instant, empty for none', () => {
    expect(formatClock('2026-07-10T14:15:00Z')).toBe('15:15');
    expect(formatClock('')).toBe('');
  });

  it('labels a fire Today, Tomorrow or by weekday and date', () => {
    expect(formatFireLabel('2026-10-31T17:30:00Z', NOW)).toBe('Today 17:30');
    expect(formatFireLabel('2026-11-01T12:27:00Z', NOW)).toBe('Tomorrow 12:27');
    expect(formatFireLabel('2026-11-04T12:25:00Z', NOW)).toBe('Wed 4 Nov 12:25');
    expect(formatFireLabel(null, NOW)).toBe('');
  });

  it.each([
    ['SUCCEEDED', 'OK', 'go'], ['PARTIAL', 'Partial', 'amber'], ['FAILED', 'Failed', 'coral'],
    ['SKIPPED', 'Skipped', 'off'], ['RUNNING', 'Running', 'tide'], ['WEIRD', 'WEIRD', 'off'],
  ])('reads fire status %s as %s in the %s tone', (status, label, tone) => {
    expect(fireStatusView(status)).toMatchObject({ label, tone });
  });

  it.each([
    [0, '<1s'], [55, '55s'], [125, '2m 05s'], [3720, '1h 02m'],
  ])('reads a %i second fire as %s', (seconds, text) => {
    const firedAt = '2026-10-31T12:00:00Z';
    expect(fireDuration({ firedAt, completedAt: new Date(Date.parse(firedAt) + seconds * 1000).toISOString() })).toBe(text);
  });

  it('has no duration for a fire still running or with an end before its start', () => {
    expect(fireDuration({ firedAt: '2026-10-31T12:00:00Z', completedAt: null })).toBe('');
    expect(fireDuration({ firedAt: '2026-10-31T12:00:00Z', completedAt: '2026-10-31T11:00:00Z' })).toBe('');
    expect(fireDuration(null)).toBe('');
  });
});
