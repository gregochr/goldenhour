import { describe, it, expect } from 'vitest';
import {
  NO_DATE, daysSince, formatDataDate, formatDataDateTime, formatPlainDate, formatWaiting,
} from '../utils/dataDates.js';

// A fixed "now" (31 Oct 2026, noon UK, after the clocks went back), passed in so no test reads the wall clock.
const NOW = new Date('2026-10-31T12:00:00Z');

describe('dataDates', () => {
  describe('formatDataDate', () => {
    it('spells a past day as "2 Oct 2026"', () => {
      expect(formatDataDate('2026-10-02T07:14:00', NOW)).toBe('2 Oct 2026');
    });

    it('says "Today, 07:48" for an instant on the UK today', () => {
      expect(formatDataDate('2026-10-31T07:48:00', NOW)).toBe('Today, 07:48');
    });

    it('reads a bare server instant as UTC, not as the reader\'s clock', () => {
      // 23:30 UTC on 3 Oct is 00:30 on the 4th in the UK (BST): the UK day is the 4th.
      expect(formatDataDate('2026-10-03T23:30:00', NOW)).toBe('4 Oct 2026');
    });

    it('is an em dash for a missing or unparseable instant', () => {
      expect(formatDataDate(null, NOW)).toBe(NO_DATE);
      expect(formatDataDate('not a date', NOW)).toBe(NO_DATE);
    });
  });

  describe('formatDataDateTime', () => {
    it('adds the UK clock time to a past day', () => {
      // 07:14 UTC on 2 Oct is 08:14 BST.
      expect(formatDataDateTime('2026-10-02T07:14:00', NOW)).toBe('2 Oct 2026, 08:14');
    });

    it('says "Today, 07:48" for the UK today', () => {
      expect(formatDataDateTime('2026-10-31T07:48:00Z', NOW)).toBe('Today, 07:48');
    });

    it('decides "today" on the UK calendar, not the UTC one', () => {
      // 4 Oct 00:30 BST is 3 Oct 23:30 UTC; with "now" on 4 Oct (UK) it is today.
      const now = new Date('2026-10-04T10:00:00Z');
      expect(formatDataDateTime('2026-10-03T23:30:00Z', now)).toBe('Today, 00:30');
    });

    it('is an em dash for a missing instant', () => {
      expect(formatDataDateTime(undefined, NOW)).toBe(NO_DATE);
    });
  });

  describe('formatPlainDate', () => {
    it('never says Today, even for an instant on today', () => {
      expect(formatPlainDate('2026-10-31T07:48:00Z')).toBe('31 Oct 2026');
    });

    it('is an em dash when there is no instant', () => {
      expect(formatPlainDate(null)).toBe(NO_DATE);
    });
  });

  describe('waiting', () => {
    it('counts whole UK calendar days', () => {
      expect(daysSince('2026-10-02T07:14:00', NOW)).toBe(29);
      expect(formatWaiting('2026-10-02T07:14:00', NOW)).toBe('29 days');
    });

    it('says "Today" for 0 days and "1 day" for one', () => {
      expect(formatWaiting('2026-10-31T00:05:00', NOW)).toBe('Today');
      expect(formatWaiting('2026-10-30T23:59:00', NOW)).toBe('1 day');
    });

    it('never goes negative when the instant is after "now"', () => {
      expect(daysSince('2026-11-02T09:00:00', NOW)).toBe(0);
    });

    it('is null / an em dash without an instant', () => {
      expect(daysSince(null, NOW)).toBeNull();
      expect(formatWaiting(null, NOW)).toBe(NO_DATE);
    });
  });
});
