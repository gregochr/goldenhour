import { describe, it, expect } from 'vitest';
import { browseFloorById, browseSpots } from '../utils/windowSpotBrowse.js';

/** The Map keeps a 1 to 5 star minimum and opens the list on it, so the sheet must resolve every star exactly. */
describe('browseFloorById', () => {
  it.each(['3', '4', '5', 'any'])('is the vocabulary\'s own for %s', (id) => {
    expect(browseFloorById(id).id).toBe(id);
  });

  it.each(['1', '2'])('resolves the Map\'s %s-star floor to exactly that floor', (id) => {
    expect(browseFloorById(id)).toEqual({ id, min: Number(id), label: `${id}★+` });
  });

  it.each(['6', '0', 'x', undefined, null])('is null for %s', (id) => {
    expect(browseFloorById(id)).toBeNull();
  });

  it('filters at the Map\'s floor, not at Any', () => {
    const spots = [1, 2, 3].map((rating) => ({
      key: String(rating), locationName: `S${rating}`, rating, driveMinutes: 10,
    }));
    const kept = (ratingFloorId) => browseSpots({
      spots, limitMinutes: null, ratingFloorId, typeId: 'any', typesByName: null, measured: false,
    }).map((s) => s.rating).sort();
    expect(kept('2')).toEqual([2, 3]);
    expect(kept('1')).toEqual([1, 2, 3]);
    expect(kept('3')).toEqual([3]);
  });
});
