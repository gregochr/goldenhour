import { describe, it, expect } from 'vitest';
import { verdictWord, WORTH_IT_THRESHOLD, MAYBE_THRESHOLD } from '../utils/verdictWord.js';

describe('verdictWord — pins the thresholds exactly (README "Verdict thresholds")', () => {
  it('pins the thresholds exactly (README "Verdict thresholds")', () => {
    expect(WORTH_IT_THRESHOLD).toBe(3.7);
    expect(MAYBE_THRESHOLD).toBe(2.8);
  });

  it('bands a whole-star rating into Worth it / Maybe / Poor', () => {
    expect(verdictWord(5)).toBe('Worth it');
    expect(verdictWord(4)).toBe('Worth it');
    expect(verdictWord(3)).toBe('Maybe');
    expect(verdictWord(2)).toBe('Poor');
    expect(verdictWord(1)).toBe('Poor');
  });

  it('straddles the exact boundary values', () => {
    expect(verdictWord(3.7)).toBe('Worth it');
    expect(verdictWord(3.699)).toBe('Maybe');
    expect(verdictWord(2.8)).toBe('Maybe');
    expect(verdictWord(2.799)).toBe('Poor');
  });

  it('returns null for an absent or non-finite rating, never a false verdict', () => {
    expect(verdictWord(null)).toBeNull();
    expect(verdictWord(undefined)).toBeNull();
    expect(verdictWord(NaN)).toBeNull();
  });
});
