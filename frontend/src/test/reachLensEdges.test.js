import { describe, it, expect } from 'vitest';
import {
  formatLensCount, isFarSpot, isUnroutableDropped, reachIsMeasured, verdictScopeShort, verdictScopeSpoken,
  verdictScopeText,
} from '../utils/reachLens.js';

describe('isFarSpot — an unset default limit marks nothing', () => {
  it('is false for a null or undefined default limit however long the drive', () => {
    expect(isFarSpot(70, null)).toBe(false);
    expect(isFarSpot(70, undefined)).toBe(false);
  });

  it('is false for an unknown drive time, even beside a negative limit', () => {
    expect(isFarSpot(null, -1)).toBe(false);
    expect(isFarSpot(undefined, -1)).toBe(false);
  });

  it('is strict at the boundary', () => {
    expect(isFarSpot(60, 60)).toBe(false);
    expect(isFarSpot(61, 60)).toBe(true);
  });
});

describe('verdict scope words — an absent scope is no scope', () => {
  it('returns null from every spelling for null, undefined and a scope with no minutes', () => {
    [verdictScopeText, verdictScopeShort, verdictScopeSpoken].forEach((fn) => {
      expect(fn(null)).toBeNull();
      expect(fn(undefined)).toBeNull();
      expect(fn({})).toBeNull();
      expect(fn({ tierMinutes: null })).toBeNull();
    });
  });

  it('spells a real tier three ways', () => {
    expect(verdictScopeText({ tierMinutes: 90 })).toBe('within 1h30');
    expect(verdictScopeShort({ tierMinutes: 90 })).toBe('≤1h30');
    expect(verdictScopeSpoken({ tierMinutes: 90 })).toBe('within 1h 30min');
  });
});

describe('reachIsMeasured — holes in the map', () => {
  it('is false for a map whose entries are null or have no drive time', () => {
    expect(reachIsMeasured(new Map([[1, null], [2, undefined], [3, {}], [4, { driveMinutes: null }]]))).toBe(false);
  });

  it('is true once any entry has a drive time, zero included', () => {
    expect(reachIsMeasured(new Map([[1, null], [2, { driveMinutes: 0 }]]))).toBe(true);
  });

  it('is false for a missing or non-map argument', () => {
    expect(reachIsMeasured(null)).toBe(false);
    expect(reachIsMeasured({})).toBe(false);
  });
});

describe('isUnroutableDropped — all three conditions are needed', () => {
  const unroutable = { driveMinutes: null, driveStatus: 'UNROUTABLE' };

  it('drops an unroutable spot under a finite tier for a measured reader', () => {
    expect(isUnroutableDropped(unroutable, 45, true)).toBe(true);
  });

  it('keeps it under Any (null limit), for an unmeasured reader, or when measured is merely truthy', () => {
    expect(isUnroutableDropped(unroutable, null, true)).toBe(false);
    expect(isUnroutableDropped(unroutable, undefined, true)).toBe(false);
    expect(isUnroutableDropped(unroutable, 45, false)).toBe(false);
    expect(isUnroutableDropped(unroutable, 45, 'yes')).toBe(false);
  });

  it('keeps a spot that has a drive time or another status', () => {
    expect(isUnroutableDropped({ driveMinutes: 20, driveStatus: 'UNROUTABLE' }, 45, true)).toBe(false);
    expect(isUnroutableDropped({ driveMinutes: null, driveStatus: 'PENDING' }, 45, true)).toBe(false);
    expect(isUnroutableDropped({}, 45, true)).toBe(false);
  });

  it('does not throw for a missing spot', () => {
    expect(isUnroutableDropped(undefined, 45, true)).toBe(false);
    expect(isUnroutableDropped(null, 45, true)).toBe(false);
  });
});

describe('formatLensCount — plurals of the reached count', () => {
  it('says "spot" for a single reached spot and "spots" otherwise, with and without reach wording', () => {
    expect(formatLensCount({ spotCount: 0, reachedCount: 1, windowCount: 2 }))
      .toEqual({ lead: '0', rest: 'of 1 spot within reach across 2 windows' });
    expect(formatLensCount({ spotCount: 0, reachedCount: 1, windowCount: 1, reachMeasured: false }))
      .toEqual({ lead: '0', rest: 'of 1 spot across 1 window' });
    expect(formatLensCount({ spotCount: 1, reachedCount: 5, windowCount: 3 }))
      .toEqual({ lead: '1', rest: 'of 5 spots within reach across 3 windows' });
  });
});
