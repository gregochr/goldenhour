import { describe, it, expect } from 'vitest';
import {
  formatCivilDayShort, formatJoinedPhrase, formatSentWhen, matchesSearch,
} from '../utils/userAdmin.js';

describe('userAdmin edges', () => {
  it('reads only an exact civil date', () => {
    expect(formatCivilDayShort('2026-11-25')).toBe('25 Nov');
    expect(formatCivilDayShort('2026-11-25T10:00')).toBeNull();
    expect(formatCivilDayShort('2026-11-25x')).toBeNull();
    expect(formatCivilDayShort('x2026-11-25')).toBeNull();
    expect(formatCivilDayShort('26-11-25')).toBeNull();
    expect(formatCivilDayShort(20261125)).toBeNull();
    expect(formatCivilDayShort(null)).toBeNull();
  });

  it('a blank search matches a user with no username and no email', () => {
    expect(matchesSearch({}, '   ')).toBe(true);
    expect(matchesSearch({}, 'a')).toBe(false);
  });

  it('words a join as a date when the reference instant is unreadable', () => {
    expect(formatJoinedPhrase('2026-11-07T10:00:00Z', new Date(NaN))).toBe('7 Nov 2026');
  });

  it('words a sent time as a date and time when the reference instant is unreadable', () => {
    expect(formatSentWhen('2026-11-07T08:02:00Z', new Date(NaN))).toBe('7 Nov at 08:02');
  });
});
