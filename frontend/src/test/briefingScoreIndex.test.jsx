/**
 * Proves the O(1) briefing-score index returns exactly what the original O(n) linear scan did
 * (identity-preserving), across a representative matrix of queries — the correctness contract for
 * the map-view performance pass that replaced the per-marker scan with a prebuilt index.
 */
import { describe, it, expect } from 'vitest';
import {
  getBriefingScore,
  buildBriefingScoreIndex,
  lookupBriefingScore,
} from '../utils/briefingScoreIndex.js';

const D1 = '2026-07-24';
const D2 = '2026-07-25';

// Representative map: multiple regions, dates, events and locations. Region and location names
// carry spaces (as real data does) but never the reserved '|' delimiter.
function makeScores() {
  return new Map([
    ['The North Yorkshire Coast|2026-07-24|SUNRISE|Sandsend', { rating: 4, tag: 'a' }],
    ['The North Yorkshire Coast|2026-07-24|SUNSET|Sandsend', { rating: 2, tag: 'b' }],
    ['The North Yorkshire Coast|2026-07-24|SUNRISE|Whitby', { triageReason: 'HIGH_CLOUD', tag: 'c' }],
    ['The North Yorkshire Coast|2026-07-25|SUNSET|Whitby', { rating: 5, tag: 'd' }],
    ['The Cheviots|2026-07-24|SUNSET|College Valley', { rating: 3, tag: 'e' }],
  ]);
}

// The full cartesian query set the map view would ever issue over this data.
const LOCATIONS = ['Sandsend', 'Whitby', 'College Valley', 'Nowhere'];
const DATES = [D1, D2];
const EVENTS = ['SUNRISE', 'SUNSET'];

describe('briefingScoreIndex — index matches the linear scan', () => {
  it('returns the identical object (or null) for every location/date/event query', () => {
    const scores = makeScores();
    const index = buildBriefingScoreIndex(scores);

    for (const name of LOCATIONS) {
      for (const date of DATES) {
        for (const event of EVENTS) {
          const linear = getBriefingScore(scores, name, date, event);
          const indexed = lookupBriefingScore(index, name, date, event);
          // Identity (toBe), not deep-equal — the map view relies on the exact object reference.
          expect(indexed).toBe(linear);
        }
      }
    }
  });

  it('resolves a known hit to the exact stored object', () => {
    const scores = makeScores();
    const index = buildBriefingScoreIndex(scores);
    const stored = scores.get('The North Yorkshire Coast|2026-07-24|SUNSET|Sandsend');
    expect(lookupBriefingScore(index, 'Sandsend', D1, 'SUNSET')).toBe(stored);
  });

  it('returns null for a non-matching location/date/event', () => {
    const index = buildBriefingScoreIndex(makeScores());
    expect(lookupBriefingScore(index, 'Sandsend', D2, 'SUNSET')).toBeNull(); // wrong date
    expect(lookupBriefingScore(index, 'Nowhere', D1, 'SUNRISE')).toBeNull(); // wrong location
    expect(lookupBriefingScore(index, 'Whitby', D1, 'SUNSET')).toBeNull(); // wrong event for date
  });

  it('preserves first-inserted-wins on a duplicate tail (mirrors the scan returning on first match)', () => {
    const first = { rating: 1, tag: 'first' };
    const second = { rating: 5, tag: 'second' };
    const scores = new Map([
      ['RegionA|2026-07-24|SUNSET|Dupe', first],
      ['RegionB|2026-07-24|SUNSET|Dupe', second],
    ]);
    const index = buildBriefingScoreIndex(scores);
    // The linear scan returns `first` (earlier insertion order); the index must agree.
    expect(getBriefingScore(scores, 'Dupe', D1, 'SUNSET')).toBe(first);
    expect(lookupBriefingScore(index, 'Dupe', D1, 'SUNSET')).toBe(first);
  });

  it('matches the scan even when the region name itself contains the delimiter', () => {
    // Regions are user-created, so a name containing "|" is possible. The scan matches on the
    // "|date|event|location" suffix regardless of what precedes it, so the index must key off the
    // LAST three fields — keying off "everything after the first pipe" would silently miss here.
    const stored = { rating: 4, tag: 'piped-region' };
    const scores = new Map([['North|East|2026-07-24|SUNSET|Sandsend', stored]]);
    const index = buildBriefingScoreIndex(scores);

    expect(getBriefingScore(scores, 'Sandsend', D1, 'SUNSET')).toBe(stored);
    expect(lookupBriefingScore(index, 'Sandsend', D1, 'SUNSET')).toBe(stored);
  });

  it('handles empty and nullish inputs like the linear scan (null, never throws)', () => {
    expect(buildBriefingScoreIndex(undefined).size).toBe(0);
    expect(buildBriefingScoreIndex(null).size).toBe(0);
    expect(buildBriefingScoreIndex(new Map()).size).toBe(0);
    expect(lookupBriefingScore(buildBriefingScoreIndex(new Map()), 'Sandsend', D1, 'SUNRISE')).toBeNull();
    expect(lookupBriefingScore(null, 'Sandsend', D1, 'SUNRISE')).toBeNull();
  });
});
