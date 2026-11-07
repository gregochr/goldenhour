import { describe, it, expect, beforeEach, vi } from 'vitest';

describe('locationTypes edges', () => {
  let mod;

  beforeEach(async () => {
    vi.resetModules();
    mod = await import('../utils/locationTypes.js');
  });

  it('holds the label and emoji of every backend type, in display order', () => {
    expect(mod.LOCATION_TYPE_META).toEqual({
      LANDSCAPE: { label: 'Landscape', emoji: '🏔️' },
      WILDLIFE: { label: 'Wildlife', emoji: '🐾' },
      SEASCAPE: { label: 'Seascape', emoji: '🌊' },
      WOODLAND: { label: 'Woodland', emoji: '🌳' },
      WATERFALL: { label: 'Waterfall', emoji: '💦' },
      BLUEBELL: { label: 'Bluebell', emoji: '🌸' },
    });
    expect(Object.keys(mod.LOCATION_TYPE_META)).toEqual(
      ['LANDSCAPE', 'WILDLIFE', 'SEASCAPE', 'WOODLAND', 'WATERFALL', 'BLUEBELL'],
    );
  });

  it('shows five permanent types, bluebell absent', () => {
    expect(mod.DISPLAY_TYPES).toEqual(['LANDSCAPE', 'WILDLIFE', 'SEASCAPE', 'WOODLAND', 'WATERFALL']);
  });

  it('counts only the sky subjects as sky candidates, and an untyped place too', () => {
    expect(mod.SKY_SUBJECT_TYPES).toEqual(['LANDSCAPE', 'SEASCAPE', 'WATERFALL']);
    expect(mod.isSkyPromptCandidate(['WOODLAND'])).toBe(false);
    expect(mod.isSkyPromptCandidate(['WILDLIFE', 'WATERFALL'])).toBe(true);
    expect(mod.isSkyPromptCandidate([])).toBe(true);
    expect(mod.isSkyPromptCandidate(null)).toBe(true);
  });

  it('words subjects in catalogue order from a list, a bare string or nothing', () => {
    expect(mod.subjectWordsOf(['SEASCAPE', 'LANDSCAPE', 'BLUEBELL'])).toEqual(['Landscape', 'Seascape']);
    expect(mod.subjectWordsOf('WILDLIFE')).toEqual(['Wildlife']);
    expect(mod.subjectWordsOf(null)).toEqual([]);
    expect(mod.subjectWordsOf(undefined)).toEqual([]);
  });

  it('knows a pure wildlife hide only when every type is WILDLIFE', () => {
    expect(mod.isWildlifeOnly('WILDLIFE')).toBe(true);
    expect(mod.isWildlifeOnly(['WILDLIFE'])).toBe(true);
    expect(mod.isWildlifeOnly(['WILDLIFE', 'SEASCAPE'])).toBe(false);
    expect(mod.isWildlifeOnly([])).toBe(false);
    expect(mod.isWildlifeOnly(null)).toBe(false);
    expect(mod.isWildlifeOnly('SEASCAPE')).toBe(false);
  });

  it('labels a type, passing an unknown one through', () => {
    expect(mod.locationTypeLabel('WATERFALL')).toBe('Waterfall');
    expect(mod.locationTypeLabel('MYSTERY')).toBe('MYSTERY');
  });

  it('maps only named, typed locations', () => {
    const map = mod.buildLocationTypeMap([
      { name: 'A', locationType: ['LANDSCAPE'] }, { name: 'B' }, { locationType: ['X'] }, null,
    ]);
    expect([...map.entries()]).toEqual([['A', ['LANDSCAPE']]]);
    expect(mod.buildLocationTypeMap(null).size).toBe(0);
  });
});
