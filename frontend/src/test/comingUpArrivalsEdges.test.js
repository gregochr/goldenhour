import { describe, it, expect } from 'vitest';
import {
  deriveBadge, isNewEntry, qualifyingArrivals, selectSinceEntry,
} from '../utils/comingUpArrivals.js';

const entry = (bits, extra = {}) => ({
  kind: 'ALMANAC', interim: false, bits, enteredWindow: '2026-11-02', title: `E${bits}`, ...extra,
});

describe('isNewEntry', () => {
  it('is false when the reader has no stored date, empty string included', () => {
    expect(isNewEntry(entry(5), null)).toBe(false);
    expect(isNewEntry(entry(5), undefined)).toBe(false);
    expect(isNewEntry(entry(5), '')).toBe(false);
  });

  it('is strictly after the stored date', () => {
    expect(isNewEntry(entry(5), '2026-11-01')).toBe(true);
    expect(isNewEntry(entry(5), '2026-11-02')).toBe(false);
  });
});

describe('qualifyingArrivals — what counts as badge-worthy', () => {
  const bands = { announce: 5, interrupt: 10 };

  it('needs a numeric announce edge, not a string one', () => {
    expect(qualifyingArrivals([entry(8)], { announce: '5' }, '2026-11-01')).toEqual([]);
    expect(qualifyingArrivals([entry(8)], {}, '2026-11-01')).toEqual([]);
  });

  it('needs numeric bits, not a numeric-looking string', () => {
    expect(qualifyingArrivals([entry('7')], bands, '2026-11-01')).toEqual([]);
    expect(qualifyingArrivals([entry(undefined)], { announce: 0 }, '2026-11-01')).toEqual([]);
    expect(qualifyingArrivals([entry(null)], { announce: 0 }, '2026-11-01')).toEqual([]);
  });

  it('is lower-inclusive at the edge, drops interim and forecast entries, and sorts highest first', () => {
    const out = qualifyingArrivals(
      [entry(4.9), entry(5), entry(9), entry(7, { interim: true }), entry(8, { kind: 'FORECAST' }), entry(6)],
      bands,
      '2026-11-01',
    );
    expect(out.map((e) => e.bits)).toEqual([9, 6, 5]);
  });

  it('is empty without entries or bands', () => {
    expect(qualifyingArrivals(undefined, bands, '2026-11-01')).toEqual([]);
    expect(qualifyingArrivals([entry(8)], null, '2026-11-01')).toEqual([]);
  });
});

describe('deriveBadge', () => {
  it('is announce with a count when no interrupt edge is served, however high the bits', () => {
    expect(deriveBadge([entry(99), entry(6)], { announce: 5 }, '2026-11-01')).toEqual({ band: 'announce', count: 2 });
  });

  it('is announce for a non-numeric interrupt edge', () => {
    expect(deriveBadge([entry(99)], { announce: 5, interrupt: '10' }, '2026-11-01'))
      .toEqual({ band: 'announce', count: 1 });
  });

  it('is interrupt, with no count, once the top arrival reaches the edge', () => {
    expect(deriveBadge([entry(10), entry(6)], { announce: 5, interrupt: 10 }, '2026-11-01'))
      .toEqual({ band: 'interrupt', count: null });
    expect(deriveBadge([entry(9.9), entry(6)], { announce: 5, interrupt: 10 }, '2026-11-01'))
      .toEqual({ band: 'announce', count: 2 });
  });

  it('is null when nothing qualifies', () => {
    expect(deriveBadge([entry(2)], { announce: 5 }, '2026-11-01')).toBeNull();
  });
});

describe('selectSinceEntry', () => {
  it('is the highest-bits arrival, or null', () => {
    expect(selectSinceEntry([entry(6), entry(9)], { announce: 5 }, '2026-11-01').bits).toBe(9);
    expect(selectSinceEntry([entry(2)], { announce: 5 }, '2026-11-01')).toBeNull();
  });
});
