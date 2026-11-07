import { describe, it, expect } from 'vitest';
import {
  countByType,
  describeCron,
  describeFixedDelay,
  fireTook,
  formatNextFire,
  jobMatchesFilters,
  jobMatchesSearch,
  relativeTime,
  sortJobs,
} from '../utils/schedulerView.js';

const NOW = new Date('2026-11-03T12:00:00Z');
const ago = (seconds) => new Date(NOW.getTime() - seconds * 1000).toISOString();
const ahead = (seconds) => new Date(NOW.getTime() + seconds * 1000).toISOString();

describe('describeCron leaves what it cannot read as it was', () => {
  it('returns a cron with fewer than six fields untouched', () => {
    expect(describeCron('0 30 5 * *')).toBe('0 30 5 * *');
  });

  it.each([
    ['a seconds field that is not 0', '5 0 3 * * *'],
    ['a day-of-month that is set', '0 0 3 1 * *'],
    ['a month that is set', '0 0 3 * 6 *'],
    ['a minutes field that is a step', '0 */5 3 * * *'],
  ])('returns a cron with %s as it was', (_name, cron) => {
    expect(describeCron(cron)).toBe(cron);
  });

  it('still describes the plain daily shape', () => {
    expect(describeCron('0 30 5 * * *', 'Europe/London')).toBe('Daily at 05:30 UK time');
  });
});

describe('describeFixedDelay', () => {
  it.each([
    [undefined, ''],
    [null, ''],
    [0, ''],
    [-5, ''],
    [-5000, ''],
  ])('reads %s as no delay', (ms, expected) => {
    expect(describeFixedDelay(ms)).toBe(expected);
  });

  it('says "hour" for exactly one hour and counts hours beyond it', () => {
    expect(describeFixedDelay(3_600_000)).toBe('Every hour');
    expect(describeFixedDelay(7_200_000)).toBe('Every 2 hours');
  });

  it('switches from seconds to minutes at exactly 60 seconds', () => {
    expect(describeFixedDelay(60_000)).toBe('Every minute');
    expect(describeFixedDelay(59_000)).toBe('Every 59 seconds');
  });

  it('counts minutes beyond one', () => {
    expect(describeFixedDelay(300_000)).toBe('Every 5 minutes');
  });

  it('reads a sub-minute delay in seconds', () => {
    expect(describeFixedDelay(5000)).toBe('Every 5 seconds');
  });
});

describe('relativeTime boundaries', () => {
  it('reads a past time at each unit boundary', () => {
    expect(relativeTime(ago(59), { now: NOW })).toBe('59s ago');
    expect(relativeTime(ago(60), { now: NOW })).toBe('1 min ago');
    expect(relativeTime(ago(59 * 60), { now: NOW })).toBe('59 min ago');
    expect(relativeTime(ago(60 * 60), { now: NOW })).toBe('1h ago');
    expect(relativeTime(ago(23 * 3600), { now: NOW })).toBe('23h ago');
    expect(relativeTime(ago(24 * 3600), { now: NOW })).toBe('1d ago');
  });

  it('reads this very instant as 0s ago, and a time ahead of now as just now', () => {
    expect(relativeTime(NOW.toISOString(), { now: NOW })).toBe('0s ago');
    expect(relativeTime(ahead(1), { now: NOW })).toBe('just now');
  });

  it('reads a future time at each unit boundary', () => {
    expect(relativeTime(ahead(59), { future: true, now: NOW })).toBe('in 59s');
    expect(relativeTime(ahead(60), { future: true, now: NOW })).toBe('in 1 min');
    expect(relativeTime(ahead(59 * 60), { future: true, now: NOW })).toBe('in 59 min');
    expect(relativeTime(ahead(60 * 60), { future: true, now: NOW })).toBe('in 1h');
    expect(relativeTime(ahead(23 * 3600), { future: true, now: NOW })).toBe('in 23h');
    expect(relativeTime(ahead(24 * 3600), { future: true, now: NOW })).toBe('in 1d');
  });

  it('reads this very instant, and a past one, as just now when looking ahead', () => {
    expect(relativeTime(NOW.toISOString(), { future: true, now: NOW })).toBe('just now');
    expect(relativeTime(ago(5), { future: true, now: NOW })).toBe('just now');
  });
});

describe('formatNextFire reach', () => {
  it('gives only the weekday up to six days ahead and the date from seven', () => {
    expect(formatNextFire('2026-11-09T06:00:00Z', NOW)).toBe('Mon 06:00');
    expect(formatNextFire('2026-11-10T06:00:00Z', NOW)).toBe('Tue 10 Nov 06:00');
  });

  it('gives the full date for a fire that is in the past', () => {
    expect(formatNextFire('2026-11-02T06:00:00Z', NOW)).toBe('Mon 2 Nov 06:00');
  });
});

describe('type chips', () => {
  const jobs = () => [
    { jobKey: 'a', scheduleType: 'CRON', status: 'ACTIVE' },
    { jobKey: 'b', scheduleType: 'CRON', status: 'ACTIVE' },
    { jobKey: 'c', scheduleType: 'SOLAR_OFFSET', status: 'ACTIVE' },
    { jobKey: 'd', scheduleType: 'FIXED_DELAY', status: 'ACTIVE' },
  ];

  it('counts and filters cron jobs by their own type only', () => {
    expect(countByType(jobs(), 'CRON')).toBe(2);
    expect(countByType(jobs(), 'all')).toBe(4);
    const [cron, solar] = [jobs()[0], jobs()[2]];
    expect(jobMatchesFilters(cron, { type: 'CRON', status: null })).toBe(true);
    expect(jobMatchesFilters(solar, { type: 'CRON', status: null })).toBe(false);
  });

  it('counts and filters fixed-delay and sun-relative jobs by their own type only', () => {
    expect(countByType(jobs(), 'FIXED_DELAY')).toBe(1);
    expect(countByType(jobs(), 'SOLAR_OFFSET')).toBe(1);
    const [cron, , solar, delay] = jobs();
    expect(jobMatchesFilters(delay, { type: 'FIXED_DELAY', status: null })).toBe(true);
    expect(jobMatchesFilters(cron, { type: 'FIXED_DELAY', status: null })).toBe(false);
    expect(jobMatchesFilters(solar, { type: 'SOLAR_OFFSET', status: null })).toBe(true);
    expect(jobMatchesFilters(delay, { type: 'SOLAR_OFFSET', status: null })).toBe(false);
  });
});

describe('jobMatchesSearch', () => {
  it('finds a Claude job by the word "claude" even when its text does not say it', () => {
    const job = { displayName: 'Nightly', catalogue: { usesClaude: true } };
    expect(jobMatchesSearch(job, 'claude')).toBe(true);
  });

  it('does not let a Claude job match an unrelated search', () => {
    const job = { displayName: 'Nightly', catalogue: { usesClaude: true } };
    expect(jobMatchesSearch(job, 'zzz')).toBe(false);
  });

  it('does not match "claude" for a job with no catalogue or one that does not use Claude', () => {
    expect(jobMatchesSearch({ displayName: 'Nightly' }, 'claude')).toBe(false);
    expect(jobMatchesSearch({ displayName: 'Nightly', catalogue: { usesClaude: false } }, 'claude')).toBe(false);
  });

  it('matches blank text', () => {
    expect(jobMatchesSearch({ displayName: 'Nightly' }, '   ')).toBe(true);
  });
});

describe('sortJobs ties', () => {
  const active = (name, next) => ({ displayName: name, status: 'ACTIVE', nextFireTime: next });

  it('keeps jobs of the same name in their given order whichever way the name sort runs', () => {
    const make = () => [
      { displayName: 'Same', jobKey: 'first' },
      { displayName: 'Same', jobKey: 'second' },
      { displayName: 'Same', jobKey: 'third' },
    ];
    expect(sortJobs(make(), { key: 'name', dir: 'asc' }).map((j) => j.jobKey)).toEqual(['first', 'second', 'third']);
    expect(sortJobs(make(), { key: 'name', dir: 'desc' }).map((j) => j.jobKey)).toEqual(['first', 'second', 'third']);
  });

  it('breaks a tie on next fire by name, in either direction', () => {
    const make = () => [
      active('Zulu', '2026-11-03T13:00:00Z'),
      active('Alpha', '2026-11-03T13:00:00Z'),
      active('Mike', '2026-11-03T13:00:00Z'),
    ];
    expect(sortJobs(make(), { key: 'next', dir: 'asc' }).map((j) => j.displayName)).toEqual(['Alpha', 'Mike', 'Zulu']);
    expect(sortJobs(make(), { key: 'next', dir: 'desc' }).map((j) => j.displayName)).toEqual(['Alpha', 'Mike', 'Zulu']);
  });

  it('orders by next fire, soonest first or last first', () => {
    const make = () => [active('B', '2026-11-03T15:00:00Z'), active('A', '2026-11-03T13:00:00Z')];
    expect(sortJobs(make(), { key: 'next', dir: 'asc' }).map((j) => j.displayName)).toEqual(['A', 'B']);
    expect(sortJobs(make(), { key: 'next', dir: 'desc' }).map((j) => j.displayName)).toEqual(['B', 'A']);
  });
});

describe('fireTook', () => {
  it('is empty when either end is missing or the fire ends before it starts', () => {
    expect(fireTook({ firedAt: '2026-11-03T01:00:00Z', completedAt: null })).toBe('');
    expect(fireTook({ firedAt: null, completedAt: '2026-11-03T01:00:55Z' })).toBe('');
    expect(fireTook({ firedAt: null, completedAt: null })).toBe('');
    expect(fireTook(undefined)).toBe('');
    expect(fireTook({ firedAt: '2026-11-03T01:00:55Z', completedAt: '2026-11-03T01:00:00Z' })).toBe('');
  });

  it('is empty for a fire that started at the epoch and has no end', () => {
    expect(fireTook({ firedAt: '1970-01-01T00:00:00Z', completedAt: null })).toBe('');
  });

  it('reads sub-second as "<1s" and exactly one second as "1s"', () => {
    expect(fireTook({ firedAt: '2026-11-03T01:00:00.000Z', completedAt: '2026-11-03T01:00:00.200Z' })).toBe('<1s');
    expect(fireTook({ firedAt: '2026-11-03T01:00:00Z', completedAt: '2026-11-03T01:00:01Z' })).toBe('1s');
    expect(fireTook({ firedAt: '2026-11-03T01:00:00Z', completedAt: '2026-11-03T01:00:00Z' })).toBe('<1s');
  });
});
