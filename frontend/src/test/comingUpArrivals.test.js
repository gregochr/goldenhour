import { describe, it, expect } from 'vitest';
import {
  isNewEntry, qualifyingArrivals, deriveBadge, selectSinceEntry,
} from '../utils/comingUpArrivals.js';

const BANDS = { list: 5.0, announce: 7.5, interrupt: 10.0 };

/** A wire `ComingUpEntry`, in the shape needed for the badge derivation. */
const entry = (over = {}) => ({
  id: 'supermoon:2026-08-08:2026-08-08',
  type: 'supermoon',
  kind: 'ALMANAC',
  interim: false,
  enteredWindow: '2026-08-08',
  bits: 8.2,
  title: 'Supermoon',
  scoreNote: 'Rarity alone carries it over the top contour.',
  ...over,
});

describe('isNewEntry', () => {
  it('is true when enteredWindow is strictly after the stored last-seen date', () => {
    expect(isNewEntry(entry({ enteredWindow: '2026-08-08' }), '2026-08-07')).toBe(true);
  });

  it('is false on the boundary — enteredWindow equal to the last-seen date', () => {
    expect(isNewEntry(entry({ enteredWindow: '2026-08-08' }), '2026-08-08')).toBe(false);
  });

  it('is false when enteredWindow is before the last-seen date', () => {
    expect(isNewEntry(entry({ enteredWindow: '2026-07-01' }), '2026-08-08')).toBe(false);
  });

  it('is false for a null last-seen date — never opened the tab', () => {
    expect(isNewEntry(entry({ enteredWindow: '2026-08-08' }), null)).toBe(false);
  });

  it('is false for an undefined last-seen date — not yet known', () => {
    expect(isNewEntry(entry({ enteredWindow: '2026-08-08' }), undefined)).toBe(false);
  });
});

describe('qualifyingArrivals', () => {
  it('returns nothing with no served bands', () => {
    expect(qualifyingArrivals([entry()], null, '2026-08-01')).toEqual([]);
    expect(qualifyingArrivals([entry()], undefined, '2026-08-01')).toEqual([]);
  });

  it('returns nothing for a non-array entries list', () => {
    expect(qualifyingArrivals(null, BANDS, '2026-08-01')).toEqual([]);
    expect(qualifyingArrivals(undefined, BANDS, '2026-08-01')).toEqual([]);
  });

  it('excludes an entry below the announce edge', () => {
    const entries = [entry({ bits: 6.9 })];
    expect(qualifyingArrivals(entries, BANDS, '2026-08-01')).toEqual([]);
  });

  it('includes an entry AT the announce edge — lower-inclusive (plan D4)', () => {
    const entries = [entry({ bits: 7.5 })];
    expect(qualifyingArrivals(entries, BANDS, '2026-08-01')).toHaveLength(1);
  });

  it('never includes a FORECAST-kind entry, however high its bits', () => {
    const entries = [entry({ kind: 'FORECAST', bits: 11.6 })];
    expect(qualifyingArrivals(entries, BANDS, '2026-08-01')).toEqual([]);
  });

  it('never includes an interim (unconfirmed) entry, however high its bits', () => {
    const entries = [entry({ interim: true, bits: 11.6 })];
    expect(qualifyingArrivals(entries, BANDS, '2026-08-01')).toEqual([]);
  });

  it('never includes an entry that has not arrived since the last visit', () => {
    const entries = [entry({ bits: 11.6, enteredWindow: '2026-07-01' })];
    expect(qualifyingArrivals(entries, BANDS, '2026-08-01')).toEqual([]);
  });

  it('sorts qualifying arrivals by descending bits', () => {
    const entries = [
      entry({ id: 'lower', bits: 7.6 }),
      entry({ id: 'higher', bits: 9.0 }),
    ];
    const qualifying = qualifyingArrivals(entries, BANDS, '2026-08-01');
    expect(qualifying.map((e) => e.id)).toEqual(['higher', 'lower']);
  });
});

describe('deriveBadge', () => {
  it('is null when nothing qualifies', () => {
    expect(deriveBadge([entry({ bits: 6.9 })], BANDS, '2026-08-01')).toBeNull();
  });

  it('is null with no bands or no last-seen date', () => {
    expect(deriveBadge([entry()], null, '2026-08-01')).toBeNull();
    expect(deriveBadge([entry()], BANDS, null)).toBeNull();
  });

  it('is announce with a count when the highest qualifying score is below interrupt', () => {
    const entries = [entry({ id: 'a', bits: 8.0 }), entry({ id: 'b', bits: 7.6 })];
    expect(deriveBadge(entries, BANDS, '2026-08-01')).toEqual({ band: 'announce', count: 2 });
  });

  it('is announce AT the announce edge — lower-inclusive', () => {
    expect(deriveBadge([entry({ bits: 7.5 })], BANDS, '2026-08-01'))
      .toEqual({ band: 'announce', count: 1 });
  });

  it('is interrupt with no count when the highest qualifying score clears interrupt', () => {
    const entries = [entry({ id: 'a', bits: 11.6 }), entry({ id: 'b', bits: 7.6 })];
    expect(deriveBadge(entries, BANDS, '2026-08-01')).toEqual({ band: 'interrupt', count: null });
  });

  it('is interrupt AT the interrupt edge — lower-inclusive', () => {
    expect(deriveBadge([entry({ bits: 10.0 })], BANDS, '2026-08-01'))
      .toEqual({ band: 'interrupt', count: null });
  });
});

describe('selectSinceEntry', () => {
  it('is null when nothing qualifies', () => {
    expect(selectSinceEntry([entry({ bits: 6.9 })], BANDS, '2026-08-01')).toBeNull();
  });

  it('is the single highest-bits qualifying entry', () => {
    const entries = [
      entry({ id: 'lower', bits: 7.6, title: 'Solstice' }),
      entry({ id: 'higher', bits: 9.0, title: 'King tide, on a supermoon' }),
    ];
    const since = selectSinceEntry(entries, BANDS, '2026-08-01');
    expect(since.id).toBe('higher');
    expect(since.title).toBe('King tide, on a supermoon');
  });
});
