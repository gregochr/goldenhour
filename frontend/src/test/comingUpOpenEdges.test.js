import { describe, it, expect } from 'vitest';
import { OPEN_WITHIN_DAYS, defaultEntryOpen, isEntryOpen } from '../utils/comingUpOpen.js';

const TODAY = '2026-11-02';

describe('defaultEntryOpen — an entry with nothing to date it', () => {
  it('starts open with no start date, whatever today is', () => {
    expect(defaultEntryOpen({ inNext4Days: false, startDate: null }, TODAY)).toBe(true);
    expect(defaultEntryOpen({ inNext4Days: false }, TODAY)).toBe(true);
    expect(defaultEntryOpen({ inNext4Days: false, startDate: '' }, TODAY)).toBe(true);
  });

  it('starts open with a start date but no today to measure from', () => {
    expect(defaultEntryOpen({ inNext4Days: false, startDate: '2030-01-01' }, '')).toBe(true);
    expect(defaultEntryOpen({ inNext4Days: false, startDate: '2030-01-01' }, undefined)).toBe(true);
  });

  it('starts open when tagged In next 4 days, however late the start date', () => {
    expect(defaultEntryOpen({ inNext4Days: true, startDate: '2030-01-01' }, TODAY)).toBe(true);
  });

  it('is inclusive at the window edge and closed one day past it', () => {
    expect(OPEN_WITHIN_DAYS).toBe(30);
    expect(defaultEntryOpen({ inNext4Days: false, startDate: '2026-12-02' }, TODAY)).toBe(true);
    expect(defaultEntryOpen({ inNext4Days: false, startDate: '2026-12-03' }, TODAY)).toBe(false);
    expect(defaultEntryOpen({ inNext4Days: false, startDate: '2026-10-01' }, TODAY)).toBe(true);
  });
});

describe('isEntryOpen — the reader\'s own press wins', () => {
  const view = { id: 'e1', inNext4Days: false, startDate: '2027-06-01' };

  it('works with no overrides object at all', () => {
    expect(isEntryOpen(view, null, TODAY)).toBe(false);
    expect(isEntryOpen(view, undefined, TODAY)).toBe(false);
    expect(isEntryOpen({ ...view, startDate: null }, undefined, TODAY)).toBe(true);
  });

  it('lets a press open a closed entry and close an open one', () => {
    expect(isEntryOpen(view, { e1: true }, TODAY)).toBe(true);
    expect(isEntryOpen({ ...view, startDate: TODAY }, { e1: false }, TODAY)).toBe(false);
  });

  it('ignores a stored value that is not a boolean', () => {
    expect(isEntryOpen(view, { e1: 'yes' }, TODAY)).toBe(false);
    expect(isEntryOpen(view, { e1: 1 }, TODAY)).toBe(false);
  });
});
