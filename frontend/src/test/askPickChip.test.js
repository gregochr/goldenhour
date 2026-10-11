import { describe, it, expect } from 'vitest';
import {
  isMultiRank, rankBadge, rankOnPress, ranksOf, ranksPhrase,
} from '../utils/askPickChip.js';

/** What one Ask chip says when its place won more than one pick (`utils/askPickChip.js`). */

describe('a single-pick chip (no `ranks`) is what it always was', () => {
  const pick = { rank: 3 };

  it('has the one rank, a plain badge and no multi mark, and a press chooses that rank', () => {
    expect(ranksOf(pick)).toEqual([3]);
    expect(rankBadge(pick)).toBe('3');
    expect(isMultiRank(pick)).toBe(false);
    expect(rankOnPress({ ...pick, selectedRank: 3 })).toBe(3);
  });

  it('treats an empty `ranks` as absent', () => {
    expect(ranksOf({ rank: 2, ranks: [] })).toEqual([2]);
  });
});

describe('a multi-rank chip', () => {
  const two = { rank: 1, ranks: [1, 2], selectedRank: null };

  it('badges every rank in order and is marked multi', () => {
    expect(rankBadge(two)).toBe('1·2');
    expect(rankBadge({ rank: 1, ranks: [1, 3, 4] })).toBe('1·3·4');
    expect(isMultiRank(two)).toBe(true);
  });

  it('says its ranks in words for an accessible name', () => {
    expect(ranksPhrase([1])).toBe('1');
    expect(ranksPhrase([1, 2])).toBe('1 and 2');
    expect(ranksPhrase([1, 2, 4])).toBe('1, 2 and 4');
  });

  it('chooses the lowest on a first press, then the next, then wraps', () => {
    expect(rankOnPress(two)).toBe(1);
    expect(rankOnPress({ ...two, selectedRank: 1 })).toBe(2);
    expect(rankOnPress({ ...two, selectedRank: 2 })).toBe(1);
    expect(rankOnPress({ rank: 1, ranks: [1, 2, 3], selectedRank: 2 })).toBe(3);
  });
});
