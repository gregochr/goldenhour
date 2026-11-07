import { describe, it, expect } from 'vitest';
import { ukHour } from '../utils/mapDates.js';

describe('ukHour — the hour on the UK clock', () => {
  it('reads GMT in winter and BST in summer', () => {
    expect(ukHour(new Date('2026-01-15T23:30:00Z'))).toBe(23);
    expect(ukHour(new Date('2026-07-15T23:30:00Z'))).toBe(0);
    expect(ukHour(new Date('2026-07-15T12:05:00Z'))).toBe(13);
  });

  it('reads midnight as 0, not 24', () => {
    expect(ukHour(new Date('2026-01-15T00:00:00Z'))).toBe(0);
  });
});
