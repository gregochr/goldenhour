import { describe, it, expect } from 'vitest';
import {
  describeMinutes,
  describeSolarRule,
  formatCivilDate,
  formatReferencePoint,
  formatUkDateTime,
  formatUkTime,
  isForecastCycleJob,
  nextPreviewFire,
  validateOffset,
} from '../utils/schedulerSolar.js';

describe('schedulerSolar', () => {
  it('formats instants in UK time on both sides of the clock change', () => {
    // midsummer morning trigger: 23:13Z on the 17th is 00:13 BST on the 18th
    expect(formatUkTime('2026-06-17T23:13:00Z')).toBe('00:13 UK');
    // midwinter: GMT equals UTC
    expect(formatUkTime('2026-12-29T04:17:00Z')).toBe('04:17 UK');
    expect(formatUkDateTime('2026-06-17T23:13:00Z')).toMatch(/^Thu 18 Jun 00:13 UK$/);
  });

  it('returns an empty string for missing or unparseable instants', () => {
    expect(formatUkTime('')).toBe('');
    expect(formatUkTime(null)).toBe('');
    expect(formatUkDateTime('not a date')).toBe('');
  });

  it('describes minutes and rules in words', () => {
    expect(describeMinutes(255)).toBe('4 h 15 min');
    expect(describeMinutes(240)).toBe('4 h');
    expect(describeMinutes(45)).toBe('45 min');
    expect(describeSolarRule('SUNRISE', 255)).toBe('4 h 15 min before sunrise');
    expect(describeSolarRule('SUNSET', 0)).toBe('At sunset');
    expect(describeSolarRule('', 255)).toBe('');
    expect(describeSolarRule('NOON', 255)).toBe('');
  });

  it('validates offsets as whole minutes 0 to 720', () => {
    expect(validateOffset('0')).toEqual({ ok: true, value: 0 });
    expect(validateOffset(' 720 ')).toEqual({ ok: true, value: 720 });
    expect(validateOffset('721').ok).toBe(false);
    expect(validateOffset('-1').ok).toBe(false);
    expect(validateOffset('1.5').ok).toBe(false);
    expect(validateOffset('').ok).toBe(false);
    expect(validateOffset(null).ok).toBe(false);
  });

  it('allows the switch for exactly the two forecast cycle jobs', () => {
    expect(isForecastCycleJob('near_term_batch_evaluation')).toBe(true);
    expect(isForecastCycleJob('intraday_forecast_refresh')).toBe(true);
    expect(isForecastCycleJob('tide_refresh')).toBe(false);
  });

  it('picks the first usable future fire from the preview', () => {
    const now = Date.parse('2026-10-13T12:00:00Z');
    const job = {
      today: { fireInstant: '2026-10-13T02:00:00Z', fallback: false },
      tomorrow: { fireInstant: '2026-10-14T02:00:00Z', fallback: false },
    };
    expect(nextPreviewFire(job, now)).toBe('2026-10-14T02:00:00Z');
    expect(nextPreviewFire({ ...job, tomorrow: { fireInstant: null, fallback: true } }, now)).toBeNull();
    expect(nextPreviewFire(null, now)).toBeNull();
  });

  it('formats dates and the reference point', () => {
    expect(formatCivilDate('2026-06-18')).toBe('18 Jun');
    expect(formatReferencePoint({ lat: 54.776, lon: -1.575 })).toBe('54.776 N, 1.575 W');
    expect(formatReferencePoint(null)).toBe('');
  });
});
