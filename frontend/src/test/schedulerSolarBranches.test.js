import { describe, it, expect } from 'vitest';
import {
  isCycleJob, isForecastCycleJob, formatUkTime, formatUkDateTime, formatCivilDate, describeMinutes,
  describeSeconds, describeSolarRule, describeSolarRuleCompact, validateOffset, nextPreviewFire,
  formatReferencePoint, MAX_OFFSET_MINUTES,
} from '../utils/schedulerSolar.js';

describe('schedulerSolar job classification', () => {
  it('knows the three cycle jobs but only two may be sun-relative', () => {
    expect(isCycleJob('evening_sunrise_refresh')).toBe(true);
    expect(isForecastCycleJob('evening_sunrise_refresh')).toBe(false);
    expect(isForecastCycleJob('near_term_batch_evaluation')).toBe(true);
    expect(isCycleJob('something_else')).toBe(false);
  });
});

describe('schedulerSolar time formatting', () => {
  it('formats a summer instant on the UK clock', () => {
    expect(formatUkTime('2026-06-18T02:00:00Z')).toBe('03:00 UK');
    expect(formatUkDateTime('2026-06-18T02:00:00Z')).toBe('Thu 18 Jun 03:00 UK');
  });

  it('formats a winter instant without the summer-time shift', () => {
    expect(formatUkTime('2026-12-01T02:00:00Z')).toBe('02:00 UK');
  });

  it('returns an empty string for absent or unparseable instants', () => {
    expect(formatUkTime(null)).toBe('');
    expect(formatUkTime('not a date')).toBe('');
    expect(formatUkDateTime(undefined)).toBe('');
    expect(formatUkDateTime('not a date')).toBe('');
  });

  it('formats a civil date, passing through garbage and blanks', () => {
    expect(formatCivilDate('2026-06-18')).toBe('18 Jun');
    expect(formatCivilDate('garbage')).toBe('garbage');
    expect(formatCivilDate(null)).toBe('');
  });
});

describe('schedulerSolar durations and rules', () => {
  it('describes minutes as hours, hours and minutes, or minutes', () => {
    expect(describeMinutes(255)).toBe('4 h 15 min');
    expect(describeMinutes(240)).toBe('4 h');
    expect(describeMinutes(45)).toBe('45 min');
    expect(describeMinutes(0)).toBe('0 min');
  });

  it('rounds seconds to the minute', () => {
    expect(describeSeconds(150)).toBe('3 min');
    expect(describeSeconds(3600)).toBe('1 h');
  });

  it('describes the rule, and nothing for an unknown event or non-numeric offset', () => {
    expect(describeSolarRule('SUNRISE', 255)).toBe('4 h 15 min before sunrise');
    expect(describeSolarRule('SUNSET', 90)).toBe('1 h 30 min before sunset');
    expect(describeSolarRule('SUNRISE', 0)).toBe('At sunrise');
    expect(describeSolarRule('NOON', 10)).toBe('');
    expect(describeSolarRule('SUNSET', 'abc')).toBe('');
  });

  it('describes the compact rule with only the parts that are non-zero', () => {
    expect(describeSolarRuleCompact('SUNRISE', 255)).toBe('4h 15m before sunrise');
    expect(describeSolarRuleCompact('SUNSET', 240)).toBe('4h before sunset');
    expect(describeSolarRuleCompact('SUNSET', 45)).toBe('45m before sunset');
    expect(describeSolarRuleCompact('SUNSET', 0)).toBe('At sunset');
    expect(describeSolarRuleCompact('NOON', 10)).toBe('');
    expect(describeSolarRuleCompact('SUNRISE', 'abc')).toBe('');
  });
});

describe('schedulerSolar validateOffset', () => {
  it('accepts whole minutes in range, including both bounds', () => {
    expect(validateOffset(' 255 ')).toEqual({ ok: true, value: 255 });
    expect(validateOffset(0)).toEqual({ ok: true, value: 0 });
    expect(validateOffset(String(MAX_OFFSET_MINUTES))).toEqual({ ok: true, value: 720 });
  });

  it('rejects non-numeric input', () => {
    expect(validateOffset('4.5')).toEqual({ ok: false, message: 'Enter a whole number of minutes.' });
    expect(validateOffset(null)).toEqual({ ok: false, message: 'Enter a whole number of minutes.' });
    expect(validateOffset('-5').ok).toBe(false);
  });

  it('rejects a value above the maximum', () => {
    expect(validateOffset('721')).toEqual({ ok: false, message: 'Offset must be between 0 and 720 minutes.' });
  });
});

describe('schedulerSolar nextPreviewFire', () => {
  const NOW = Date.parse('2026-06-18T12:00:00Z');

  it('returns today when its fire is still ahead', () => {
    const job = { today: { fireInstant: '2026-06-18T13:00:00Z' }, tomorrow: { fireInstant: '2026-06-19T13:00:00Z' } };
    expect(nextPreviewFire(job, NOW)).toBe('2026-06-18T13:00:00Z');
  });

  it('skips a past or fallback day and takes tomorrow', () => {
    const past = { today: { fireInstant: '2026-06-18T05:00:00Z' }, tomorrow: { fireInstant: '2026-06-19T05:00:00Z' } };
    expect(nextPreviewFire(past, NOW)).toBe('2026-06-19T05:00:00Z');
    const fallback = {
      today: { fallback: true, fireInstant: '2026-06-18T13:00:00Z' },
      tomorrow: { fireInstant: '2026-06-19T05:00:00Z' },
    };
    expect(nextPreviewFire(fallback, NOW)).toBe('2026-06-19T05:00:00Z');
  });

  it('skips days with no instant or an unparseable one, and returns null when nothing is usable', () => {
    expect(nextPreviewFire({ today: { fireInstant: 'bad' }, tomorrow: {} }, NOW)).toBeNull();
    expect(nextPreviewFire({ today: null }, NOW)).toBeNull();
    expect(nextPreviewFire(undefined, NOW)).toBeNull();
  });
});

describe('schedulerSolar formatReferencePoint', () => {
  it('formats hemispheres from the signs', () => {
    expect(formatReferencePoint({ lat: 54.7761, lon: -1.5733 })).toBe('54.776 N, 1.573 W');
    expect(formatReferencePoint({ lat: -33.8688, lon: 151.2093 })).toBe('33.869 S, 151.209 E');
  });

  it('returns an empty string for a missing or non-finite point', () => {
    expect(formatReferencePoint(null)).toBe('');
    expect(formatReferencePoint({ lat: NaN, lon: 1 })).toBe('');
    expect(formatReferencePoint({ lat: 1 })).toBe('');
  });
});
