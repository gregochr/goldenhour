import { describe, it, expect } from 'vitest';
import { RUN_TYPE_RANGES, daysForRunType } from '../utils/runTypeRanges.js';

/**
 * These numbers mirror the backend's RunType.defaultDateRange and drive the admin cost
 * estimates for real Claude spend. Nothing pinned them before, which is how the Prompt Test
 * view kept quoting "T+3 to T+7"/5 days after the backend narrowed LONG_TERM to the T+5
 * horizon. If the backend horizon changes, these assertions should fail first.
 */
describe('runTypeRanges', () => {
  it('matches the backend date table', () => {
    expect(RUN_TYPE_RANGES).toEqual({
      VERY_SHORT_TERM: { days: 2, dateRange: 'T, T+1' },
      SHORT_TERM: { days: 3, dateRange: 'T to T+2' },
      LONG_TERM: { days: 3, dateRange: 'T+3 to T+5' },
    });
  });

  it('LONG_TERM stops at the T+5 forecast horizon, not T+7', () => {
    expect(RUN_TYPE_RANGES.LONG_TERM.dateRange).toBe('T+3 to T+5');
    expect(RUN_TYPE_RANGES.LONG_TERM.days).toBe(3);
  });

  it('each dateRange spans as many dates as its day count claims', () => {
    // T+3..T+5 inclusive is 3 dates; T,T+1 is 2; T..T+2 is 3.
    expect(daysForRunType('VERY_SHORT_TERM')).toBe(2);
    expect(daysForRunType('SHORT_TERM')).toBe(3);
    expect(daysForRunType('LONG_TERM')).toBe(3);
  });

  it('returns undefined for run types with no date range', () => {
    expect(daysForRunType('BRIEFING')).toBeUndefined();
  });
});
