import { describe, it, expect } from 'vitest';
import {
  MAX_OFFSET_MINUTES, MIN_OFFSET_MINUTES, formatReferencePoint, formatUkDateTime, nextPreviewFire, validateOffset,
} from '../utils/schedulerSolar.js';

describe('formatUkDateTime — absent input is empty, not the epoch', () => {
  it('is an empty string for null, undefined and an empty string', () => {
    expect(formatUkDateTime(null)).toBe('');
    expect(formatUkDateTime(undefined)).toBe('');
    expect(formatUkDateTime('')).toBe('');
  });

  it('is an empty string for an unparseable instant, and the UK date and time for a real one', () => {
    expect(formatUkDateTime('not a date')).toBe('');
    expect(formatUkDateTime('2026-10-13T02:00:00Z')).toBe('Tue 13 Oct 03:00 UK');
  });
});

describe('validateOffset — the bounds', () => {
  it('accepts both ends of the range', () => {
    expect(validateOffset(String(MIN_OFFSET_MINUTES))).toEqual({ ok: true, value: MIN_OFFSET_MINUTES });
    expect(validateOffset(String(MAX_OFFSET_MINUTES))).toEqual({ ok: true, value: MAX_OFFSET_MINUTES });
  });

  it('refuses one past the top, with the range in the message', () => {
    expect(validateOffset(String(MAX_OFFSET_MINUTES + 1))).toEqual({
      ok: false,
      message: `Offset must be between ${MIN_OFFSET_MINUTES} and ${MAX_OFFSET_MINUTES} minutes.`,
    });
    expect(validateOffset(100000).ok).toBe(false);
  });

  it('refuses text that is not a whole number', () => {
    ['', ' ', '-5', '1.5', 'abc', null, undefined].forEach((raw) => {
      expect(validateOffset(raw)).toEqual({ ok: false, message: 'Enter a whole number of minutes.' });
    });
  });
});

describe('nextPreviewFire — the instant being exactly now', () => {
  const NOW = Date.parse('2026-11-02T10:00:00Z');

  it('does not offer a fire that is happening at this very millisecond', () => {
    const job = { today: { fireInstant: '2026-11-02T10:00:00Z' }, tomorrow: { fireInstant: '2026-11-03T10:00:00Z' } };
    expect(nextPreviewFire(job, NOW)).toBe('2026-11-03T10:00:00Z');
    expect(nextPreviewFire({ today: { fireInstant: '2026-11-02T10:00:00Z' } }, NOW)).toBeNull();
  });

  it('offers a fire a millisecond ahead, skips a fallback day, and is null for nothing', () => {
    expect(nextPreviewFire({ today: { fireInstant: '2026-11-02T10:00:00.001Z' } }, NOW))
      .toBe('2026-11-02T10:00:00.001Z');
    expect(nextPreviewFire({
      today: { fireInstant: '2026-11-02T11:00:00Z', fallback: true },
      tomorrow: { fireInstant: '2026-11-03T11:00:00Z' },
    }, NOW)).toBe('2026-11-03T11:00:00Z');
    expect(nextPreviewFire(undefined, NOW)).toBeNull();
  });
});

describe('formatReferencePoint — the equator and the meridian', () => {
  it('reads latitude 0 as north and longitude 0 as east', () => {
    expect(formatReferencePoint({ lat: 0, lon: 0 })).toBe('0.000 N, 0.000 E');
  });

  it('reads negatives as south and west, with the magnitude', () => {
    expect(formatReferencePoint({ lat: -33.8688, lon: -1.5751 })).toBe('33.869 S, 1.575 W');
    expect(formatReferencePoint({ lat: 54.776, lon: 1.575 })).toBe('54.776 N, 1.575 E');
  });

  it('is empty for a missing or partial point', () => {
    expect(formatReferencePoint(null)).toBe('');
    expect(formatReferencePoint({ lat: 54 })).toBe('');
  });
});
