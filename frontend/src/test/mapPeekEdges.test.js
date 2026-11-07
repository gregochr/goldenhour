import { describe, it, expect } from 'vitest';
import { otherWindow, tideSummary } from '../utils/mapPeek.js';

describe('mapPeek edges', () => {
  it('names no other window for a missing list, an empty list or a single row', () => {
    expect(otherWindow(null, 0, null)).toBeNull();
    expect(otherWindow([], 0, null)).toBeNull();
    expect(otherWindow([{ id: 'a', kind: 'solar' }], 0, null)).toBeNull();
  });

  it('names the next row after the active one', () => {
    const events = [{ id: 'a', kind: 'solar' }, { id: 'b', kind: 'solar' }];
    expect(otherWindow(events, 0, null).id).toBe('b');
    expect(otherWindow(events, 1, null).id).toBe('a');
  });

  it('puts no arrow on a mid tide with no direction', () => {
    expect(tideSummary({ state: 'MID' })).toBe('Mid');
    expect(tideSummary({ state: 'MID', direction: 'SIDEWAYS' }, 2)).toBe('Mid · 2 dim');
  });

  it('puts an arrow only on a mid tide, by direction', () => {
    expect(tideSummary({ state: 'MID', direction: 'RISING' })).toBe('Mid ↑');
    expect(tideSummary({ state: 'MID', direction: 'FALLING' }, 1)).toBe('Mid ↓ · 1 dim');
    expect(tideSummary({ state: 'HIGH', direction: 'RISING' })).toBe('High');
    expect(tideSummary({ state: 'LOW' }, 0)).toBe('Low');
    expect(tideSummary({ state: 'BOGUS' })).toBeNull();
    expect(tideSummary(null)).toBeNull();
  });
});
