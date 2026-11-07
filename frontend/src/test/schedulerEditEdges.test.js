import { describe, it, expect } from 'vitest';
import {
  cronEditorModel, delayOptions, parseClockCron, parseTimesInput, savedMessage,
} from '../utils/schedulerEdit.js';

describe('parseClockCron — edge by edge', () => {
  it('reads the digit day codes: 0 and 7 are Sunday, 1 Monday, 6 Saturday', () => {
    expect(parseClockCron('0 0 6 * * 0').days).toEqual(['SUN']);
    expect(parseClockCron('0 0 6 * * 7').days).toEqual(['SUN']);
    expect(parseClockCron('0 0 6 * * 1').days).toEqual(['MON']);
    expect(parseClockCron('0 0 6 * * 6').days).toEqual(['SAT']);
  });

  it('rejects a day-of-week part that is only digit-shaped at one end', () => {
    expect(parseClockCron('0 0 6 * * 10')).toBeNull();
    expect(parseClockCron('0 0 6 * * 1a')).toBeNull();
    expect(parseClockCron('0 0 6 * * a1')).toBeNull();
  });

  it('is null for a null, undefined or empty cron', () => {
    expect(parseClockCron(null)).toBeNull();
    expect(parseClockCron(undefined)).toBeNull();
    expect(parseClockCron('')).toBeNull();
  });

  it('tolerates repeated and surrounding whitespace', () => {
    expect(parseClockCron('0  15   6 * *  *')).toEqual({ minute: 15, hours: [6], days: null });
    expect(parseClockCron('  0 15 6 * * *  ')).toEqual({ minute: 15, hours: [6], days: null });
  });

  it('needs exactly six fields', () => {
    expect(parseClockCron('0 0 6 * *')).toBeNull();
    expect(parseClockCron('0 0 6 * * * *')).toBeNull();
  });

  it('needs second 0 and day-of-month and month to be *', () => {
    expect(parseClockCron('5 0 6 * * *')).toBeNull();
    expect(parseClockCron('0 0 6 1 * *')).toBeNull();
    expect(parseClockCron('0 0 6 * 3 *')).toBeNull();
  });

  it('anchors the minute and hour shapes at both ends', () => {
    expect(parseClockCron('0 a5 6 * * *')).toBeNull();
    expect(parseClockCron('0 5a 6 * * *')).toBeNull();
    expect(parseClockCron('0 5 a6 * * *')).toBeNull();
    expect(parseClockCron('0 5 6a * * *')).toBeNull();
    expect(parseClockCron('0 5 6, * * *')).toBeNull();
  });

  it('accepts the limits 59 and 23 and rejects 60 and 24', () => {
    expect(parseClockCron('0 59 23 * * *')).toEqual({ minute: 59, hours: [23], days: null });
    expect(parseClockCron('0 60 6 * * *')).toBeNull();
    expect(parseClockCron('0 0 24 * * *')).toBeNull();
    expect(parseClockCron('0 0 6,24 * * *')).toBeNull();
  });

  it('sorts hours numerically and drops repeats', () => {
    expect(parseClockCron('0 0 10,9,8,9 * * *').hours).toEqual([8, 9, 10]);
    expect(parseClockCron('0 0 6,6 * * *').hours).toEqual([6]);
  });
});

describe('cronEditorModel — day boundary', () => {
  it('is null when a UTC cron\'s hours land on different UK days', () => {
    const job = {
      cronExpression: '0 30 22,23 * * *', timeZone: 'UTC', nextFires: ['2026-07-01T00:00:00Z'],
    };
    expect(cronEditorModel(job)).toBeNull();
  });

  it('keeps both times when they shift to the same UK day', () => {
    const job = {
      cronExpression: '0 30 21,22 * * *', timeZone: 'UTC', nextFires: ['2026-07-01T00:00:00Z'],
    };
    expect(cronEditorModel(job)).toEqual({ times: ['22:30', '23:30'], days: null });
  });
});

describe('parseTimesInput — range limits', () => {
  it('accepts 23:00 and 10:59 and rejects 24:00 and 10:60', () => {
    expect(parseTimesInput('23:00')).toEqual({ ok: true, times: ['23:00'] });
    expect(parseTimesInput('10:59')).toEqual({ ok: true, times: ['10:59'] });
    expect(parseTimesInput('24:00')).toEqual({
      ok: false, message: '"24:00" is out of range. Times run from 00:00 to 23:59.',
    });
    expect(parseTimesInput('10:60')).toEqual({
      ok: false, message: '"10:60" is out of range. Times run from 00:00 to 23:59.',
    });
  });
});

describe('delayOptions — a stored delay that is not a whole second', () => {
  it('labels it in milliseconds', () => {
    const options = delayOptions(1500);
    expect(options.find((o) => o.value === '1500')).toEqual({ value: '1500', label: '1500 ms' });
  });

  it('labels a whole-second one in hours, minutes and seconds', () => {
    expect(delayOptions(150_000).find((o) => o.value === '150000').label).toBe('2 min 30 s');
  });
});

describe('savedMessage — a job with no nextFires list', () => {
  it('falls back to nextFireTime when nextFires is absent', () => {
    const now = new Date('2026-11-03T10:00:00Z');
    expect(savedMessage({ status: 'ACTIVE', nextFireTime: '2026-11-04T12:27:00Z' }, now))
      .toBe('Saved. Next fire Tomorrow 12:27.');
  });
});
