import { describe, it, expect, beforeEach, vi } from 'vitest';

describe('labelPlacement edges', () => {
  let mod;

  beforeEach(async () => {
    vi.resetModules();
    mod = await import('../utils/labelPlacement.js');
  });

  it('keeps the bundle\'s own vertical ladder, signs and order intact', () => {
    expect(mod.NUDGES).toEqual([0, -13, 13, -24, 24, -36, 36]);
    expect(mod.NUDGES).toHaveLength(7);
  });

  it('keeps the Map tab\'s longer ladder, signs and order intact', () => {
    expect(mod.MAP_NUDGES).toEqual([0, -14, 14, -26, 26, -38, 38]);
    expect(mod.MAP_NUDGES).toHaveLength(7);
  });

  it('derives the horizontal fallbacks from the label width', () => {
    expect(mod.mapDxOffsets(40)).toEqual([0, -29, 29]);
    expect(mod.mapDxOffsets(41)).toEqual([0, -30, 30]);
  });
});
