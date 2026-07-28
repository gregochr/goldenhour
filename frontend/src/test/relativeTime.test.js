import { describe, it, expect } from 'vitest';
import { formatRelativeAge } from '../utils/relativeTime.js';

/** Fixed reference instant, so every case is deterministic. */
const NOW = Date.parse('2026-07-28T12:00:00Z');

/** An ISO string the given number of minutes before NOW. */
function minutesAgo(mins) {
  return new Date(NOW - mins * 60000).toISOString();
}

describe('formatRelativeAge', () => {
  describe('tiers', () => {
    it('says "just now" under a minute', () => {
      expect(formatRelativeAge(minutesAgo(0), { now: NOW })).toBe('just now');
      expect(formatRelativeAge(minutesAgo(0.4), { now: NOW })).toBe('just now');
    });

    it('counts minutes under an hour', () => {
      expect(formatRelativeAge(minutesAgo(5), { now: NOW })).toBe('5m ago');
      expect(formatRelativeAge(minutesAgo(59), { now: NOW })).toBe('59m ago');
    });

    it('counts hours under a day', () => {
      expect(formatRelativeAge(minutesAgo(60), { now: NOW })).toBe('1h ago');
      expect(formatRelativeAge(minutesAgo(60 * 8), { now: NOW })).toBe('8h ago');
      expect(formatRelativeAge(minutesAgo(60 * 23), { now: NOW })).toBe('23h ago');
    });

    // The whole point of the change. Before this, both call sites terminated at hours, so a
    // drive-time stamp left alone for a month read "744h ago".
    it('switches to days at 24 hours, unbounded', () => {
      expect(formatRelativeAge(minutesAgo(60 * 24), { now: NOW })).toBe('1d ago');
      expect(formatRelativeAge(minutesAgo(60 * 24 * 31), { now: NOW })).toBe('31d ago');
      expect(formatRelativeAge(minutesAgo(60 * 24 * 400), { now: NOW })).toBe('400d ago');
    });

    // floor, not round: an elapsed-time stamp must never claim more time has passed than has.
    it('floors days rather than rounding up', () => {
      expect(formatRelativeAge(minutesAgo(60 * 35), { now: NOW })).toBe('1d ago');
      expect(formatRelativeAge(minutesAgo(60 * 47), { now: NOW })).toBe('1d ago');
      expect(formatRelativeAge(minutesAgo(60 * 48), { now: NOW })).toBe('2d ago');
    });
  });

  describe('styles', () => {
    // Both were pinned by existing tests and neither is wrong; the point of the shared helper is
    // that the TIERS are common, not that the wording was standardised away.
    it('verbose matches the Settings modal wording', () => {
      expect(formatRelativeAge(minutesAgo(0), { verbose: true, now: NOW })).toBe('Just now');
      expect(formatRelativeAge(minutesAgo(5), { verbose: true, now: NOW })).toBe('5 min ago');
    });

    it('compact matches the briefing wording', () => {
      expect(formatRelativeAge(minutesAgo(0), { now: NOW })).toBe('just now');
      expect(formatRelativeAge(minutesAgo(5), { now: NOW })).toBe('5m ago');
    });

    it('hours and days are worded identically in both styles', () => {
      for (const mins of [60 * 3, 60 * 24 * 9]) {
        expect(formatRelativeAge(minutesAgo(mins), { now: NOW }))
          .toBe(formatRelativeAge(minutesAgo(mins), { verbose: true, now: NOW }));
      }
    });
  });

  describe('input handling', () => {
    it('accepts instants with or without a trailing Z, treating both as UTC', () => {
      const withZ = '2026-07-28T09:00:00Z';
      const withoutZ = '2026-07-28T09:00:00';
      expect(formatRelativeAge(withZ, { now: NOW })).toBe('3h ago');
      expect(formatRelativeAge(withoutZ, { now: NOW })).toBe('3h ago');
    });

    it('returns null for nothing to format, rather than a misleading string', () => {
      expect(formatRelativeAge(null, { now: NOW })).toBeNull();
      expect(formatRelativeAge(undefined, { now: NOW })).toBeNull();
      expect(formatRelativeAge('', { now: NOW })).toBeNull();
    });

    it('returns null for an unparseable instant', () => {
      expect(formatRelativeAge('not-a-date', { now: NOW })).toBeNull();
    });

    it('defaults to the current time when no clock is supplied', () => {
      expect(formatRelativeAge(new Date().toISOString())).toBe('just now');
    });
  });
});
