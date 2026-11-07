import { describe, it, expect } from 'vitest';
import { buildBriefingScoreIndex, lookupBriefingScore } from '../utils/briefingScoreIndex.js';

describe('buildBriefingScoreIndex — keys with no leading field', () => {
  it('skips a key with fewer than four fields, because the scan\'s suffix could never match it', () => {
    const index = buildBriefingScoreIndex(new Map([
      ['2026-11-02|SUNSET|Whitby', { rating: 1 }],
      ['SUNSET|Whitby', { rating: 2 }],
      ['North|2026-11-02|SUNSET|Whitby', { rating: 4 }],
    ]));
    expect([...index.keys()]).toEqual(['2026-11-02|SUNSET|Whitby']);
    expect(index.get('2026-11-02|SUNSET|Whitby')).toEqual({ rating: 4 });
    expect(lookupBriefingScore(index, 'Whitby', '2026-11-02', 'SUNSET')).toEqual({ rating: 4 });
  });

  it('finds nothing at all when every key is too short', () => {
    const index = buildBriefingScoreIndex(new Map([['2026-11-02|SUNSET|Whitby', { rating: 1 }]]));
    expect(index.size).toBe(0);
    expect(lookupBriefingScore(index, 'Whitby', '2026-11-02', 'SUNSET')).toBeNull();
  });

  it('keeps the first entry when two keys share a tail, and takes the last three fields past a pipe in the region', () => {
    const index = buildBriefingScoreIndex(new Map([
      ['A|B|2026-11-02|SUNSET|Whitby', { rating: 3 }],
      ['Other|2026-11-02|SUNSET|Whitby', { rating: 5 }],
    ]));
    expect(lookupBriefingScore(index, 'Whitby', '2026-11-02', 'SUNSET')).toEqual({ rating: 3 });
  });
});

describe('lookupBriefingScore', () => {
  it('is null for a missing or empty index and for a miss', () => {
    expect(lookupBriefingScore(null, 'Whitby', '2026-11-02', 'SUNSET')).toBeNull();
    expect(lookupBriefingScore(new Map(), 'Whitby', '2026-11-02', 'SUNSET')).toBeNull();
    const index = buildBriefingScoreIndex(new Map([['N|2026-11-02|SUNSET|Whitby', { rating: 4 }]]));
    expect(lookupBriefingScore(index, 'Whitby', '2026-11-03', 'SUNSET')).toBeNull();
  });
});
