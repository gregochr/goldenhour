import { describe, it, expect } from 'vitest';
import {
  countByType, describeSchedule, formatNextFire, jobMatchesFilters, jobStatusView, relativeTime, sortJobs,
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
