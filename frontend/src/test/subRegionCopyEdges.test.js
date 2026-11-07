import { describe, it, expect, beforeEach, vi } from 'vitest';

/**
 * Edge cases for `utils/subRegionCopy.js`: the number-word boundaries, the strict civil-date
 * reader, and the status guards that must not fire on the wrong status.
 *
 * <p>The module is loaded afresh in each test so its number-word table is read during a test.
 */

let copy;

beforeEach(async () => {
  vi.resetModules();
  copy = await import('../utils/subRegionCopy.js');
});

describe('limitAsWord', () => {
  it('spells the whole range one to ten', () => {
    const words = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((n) => copy.limitAsWord(n));
    expect(words).toEqual(['one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten']);
  });

  it('keeps digits for zero, negatives and fractions rather than reaching into the word table', () => {
    expect(copy.limitAsWord(0)).toBe('0');
    expect(copy.limitAsWord(-1)).toBe('-1');
    expect(copy.limitAsWord(1.5)).toBe('1.5');
    expect(copy.limitAsWord(11)).toBe('11');
  });
});

describe('formatCivilDay', () => {
  it('reads only a whole YYYY-MM-DD string', () => {
    expect(copy.formatCivilDay('12026-11-02')).toBeNull();
    expect(copy.formatCivilDay('on 2026-11-02')).toBeNull();
    expect(copy.formatCivilDay('2026-11-02T00:00:00')).toBeNull();
    expect(copy.formatCivilDay('2026-11-02')).toBe('2 November');
  });

  it('does not accept something that merely prints as a date', () => {
    expect(copy.formatCivilDay(['2026-11-02'])).toBeNull();
    expect(copy.formatCivilDay({ toString: () => '2026-11-02' })).toBeNull();
  });
});

describe('isLockedAdd', () => {
  const locked = (over = {}) => ({ status: 'LOCKED', overLimit: false, picks: ['a'], limit: 3, ...over });

  it('is true for a locked reader under the limit', () => {
    expect(copy.isLockedAdd(locked())).toBe(true);
    expect(copy.isLockedAdd(locked({ overLimit: undefined }))).toBe(true);
  });

  it('is false when the reader is over the limit, even if the pick count looks low', () => {
    expect(copy.isLockedAdd(locked({ overLimit: true }))).toBe(false);
  });

  it('is false at the limit and when not locked', () => {
    expect(copy.isLockedAdd(locked({ picks: ['a', 'b', 'c'] }))).toBe(false);
    expect(copy.isLockedAdd(locked({ status: 'OPEN' }))).toBe(false);
  });
});

describe('lockedAddLine', () => {
  it('names how many more can be added and when removal unlocks', () => {
    expect(copy.lockedAddLine({ nextChangeOn: '2026-12-01', picks: ['a'], limit: 3 }))
      .toBe('You can add up to 2 more now. Removing one waits until 1 December.');
  });

  it('says nothing without a usable unlock date', () => {
    expect(copy.lockedAddLine({ nextChangeOn: null, picks: ['a'], limit: 3 })).toBeNull();
    expect(copy.lockedAddLine({ nextChangeOn: 'soon', picks: ['a'], limit: 3 })).toBeNull();
  });
});

describe('savedToast', () => {
  const PLAIN = 'Sub-regions saved.';

  it('adds the unlock date only for a locked reader who has one', () => {
    expect(copy.savedToast({ status: 'LOCKED', nextChangeOn: '2026-12-01' }))
      .toBe('Sub-regions saved. You can next change them on 1 December.');
    expect(copy.savedToast({ status: 'LOCKED', nextChangeOn: null })).toBe(PLAIN);
  });

  it('ignores an unlock date on a status that is not locked', () => {
    expect(copy.savedToast({ status: 'OPEN', nextChangeOn: '2026-12-01' })).toBe(PLAIN);
  });

  it('adds the grace and lock dates only on the free-week status and only when both are usable', () => {
    const dates = { freeUntil: '2026-11-09', nextChangeOnIfSavedToday: '2026-12-09' };
    expect(copy.savedToast({ status: 'FREE_UNTIL', ...dates }))
      .toBe('Sub-regions saved. After 9 November you can next change them on 9 December.');
    expect(copy.savedToast({ status: 'FREE_UNTIL', freeUntil: '2026-11-09', nextChangeOnIfSavedToday: null })).toBe(PLAIN);
    expect(copy.savedToast({ status: 'FREE_UNTIL', freeUntil: null, nextChangeOnIfSavedToday: '2026-12-09' })).toBe(PLAIN);
    expect(copy.savedToast({ status: 'OPEN', ...dates })).toBe(PLAIN);
  });
});
