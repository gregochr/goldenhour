import { describe, it, expect } from 'vitest';
import { gateSpotsByRating } from '../utils/ratingLens.js';

describe('gateSpotsByRating — an unrated spot never passes a floor', () => {
  const spots = [
    { id: 'null', rating: null },
    { id: 'undef' },
    { id: 'zero', rating: 0 },
    { id: 'three', rating: 3 },
    { id: 'five', rating: 5 },
  ];

  it('drops null and missing ratings even against a floor of zero', () => {
    expect(gateSpotsByRating(spots, 0).map((s) => s.id)).toEqual(['zero', 'three', 'five']);
  });

  it('is inclusive at the floor', () => {
    expect(gateSpotsByRating(spots, 3).map((s) => s.id)).toEqual(['three', 'five']);
  });

  it('returns the input untouched for no floor', () => {
    expect(gateSpotsByRating(spots, null)).toBe(spots);
    expect(gateSpotsByRating(spots, undefined)).toBe(spots);
  });
});
