import { describe, it, expect, vi } from 'vitest';
import { gateSpotsByRating } from '../utils/ratingLens.js';

/**
 * The module is re-imported inside each table test so its module-level tables execute under that test (a
 * table built once at file load is attributed to no test by mutation testing).
 */
async function load() {
  vi.resetModules();
  return import('../utils/ratingLens.js');
}

describe('the floor tables', () => {
  it('lists every floor the product speaks, loosest first, with the ceiling reading 5★ and not 5★+', async () => {
    const { RATING_FLOORS } = await load();
    expect(RATING_FLOORS).toEqual([
      { id: 'any', min: null, label: 'Any rating' },
      { id: '3', min: 3, label: '3★+' },
      { id: '4', min: 4, label: '4★+' },
      { id: '5', min: 5, label: '5★' },
    ]);
  });

  it('offers the page-wide bar every floor but the 5★ ceiling', async () => {
    const { LENS_RATING_FLOORS } = await load();
    expect(LENS_RATING_FLOORS).toEqual([
      { id: 'any', min: null, label: 'Any rating' },
      { id: '3', min: 3, label: '3★+' },
      { id: '4', min: 4, label: '4★+' },
    ]);
  });

  it('finds the 5★ floor over the whole vocabulary but never over the bar\'s', async () => {
    const { ratingFloorById, lensRatingFloorById } = await load();
    expect(ratingFloorById('5')).toEqual({ id: '5', min: 5, label: '5★' });
    expect(lensRatingFloorById('5')).toBeNull();
    expect(lensRatingFloorById('4')).toEqual({ id: '4', min: 4, label: '4★+' });
  });
});

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
