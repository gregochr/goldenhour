import { describe, it, expect } from 'vitest';
import { FAMILY_GLYPHS, CHIP_GLYPHS, entryGlyph, coincidenceLineGlyph } from '../utils/comingUpGlyphs.js';
import { FILTER_CHIPS } from '../utils/comingUpFeed.js';
import { CONDITION_FAMILY } from '../utils/comingUpConditions.js';

describe('comingUpGlyphs — entryGlyph', () => {
  it.each([
    ['coastal', '🌊'],
    ['night-sky', '🌌'],
    ['aurora', '🌌'],
    ['sun-moon', '☀️'],
    ['dust', '🏜️'],
    ['air', '☁️'],
    ['eclipse', '◐'],
  ])('resolves the %s family to its glyph', (family, glyph) => {
    expect(entryGlyph({ family, type: 'anything-not-overridden' })).toBe(glyph);
  });

  it('overrides the family glyph for a supermoon-typed entry, even under sun-moon', () => {
    expect(entryGlyph({ family: 'sun-moon', type: 'supermoon' })).toBe('🌙');
  });

  it('returns null for an unknown family and no type override', () => {
    expect(entryGlyph({ family: 'not-a-real-family', type: 'not-a-real-type' })).toBeNull();
  });

  it('returns null for a nullish entry', () => {
    expect(entryGlyph(null)).toBeNull();
    expect(entryGlyph(undefined)).toBeNull();
  });
});

describe('comingUpGlyphs — coincidenceLineGlyph (plan §4.5)', () => {
  it('resolves by the line\'s own served family, the same lookup as everywhere else', () => {
    expect(coincidenceLineGlyph({ family: 'sun-moon', name: 'Supermoon' })).toBe('☀️');
    expect(coincidenceLineGlyph({ family: 'coastal', name: 'Spring tide run' })).toBe('🌊');
  });

  it('falls back to the name regex only when the line carries no family at all', () => {
    expect(coincidenceLineGlyph({ name: 'A supermoon rises' })).toBe('🌙');
    expect(coincidenceLineGlyph({ name: 'A spring tide run' })).toBe('🌊');
    expect(coincidenceLineGlyph({ name: 'high water at the coast' })).toBe('🌊');
  });

  it('never falls back to the regex when a (possibly unmapped) family is present', () => {
    // The family lookup wins even when it resolves to null — a served family that names nothing in
    // FAMILY_GLYPHS must render glyph-less, not silently pick up the regex fallback meant for the
    // no-family case.
    expect(coincidenceLineGlyph({ family: 'not-a-real-family', name: 'A supermoon rises' })).toBeNull();
  });

  it('returns null when neither a family nor a matching name is present', () => {
    expect(coincidenceLineGlyph({ name: 'Autumn equinox' })).toBeNull();
    expect(coincidenceLineGlyph(null)).toBeNull();
  });
});

describe('comingUpGlyphs — completeness (honestly scoped, not a literal-copy circularity)', () => {
  // A copy of FAMILY_GLYPHS' own keys would be circular — it can never fail no matter what family
  // is deleted or misspelled. This derives the family universe from the OTHER live exports that
  // actually name a family client-side, so registering a family anywhere without a glyph fails here.
  it('covers every family named by a live chip, a live condition mapping, and aurora', () => {
    const chipFamilies = FILTER_CHIPS.flatMap((chip) => chip.families ?? []);
    const conditionFamilies = Object.values(CONDITION_FAMILY);
    const universe = new Set([...chipFamilies, ...conditionFamilies, 'aurora']);
    for (const family of universe) {
      expect(FAMILY_GLYPHS[family], `FAMILY_GLYPHS missing an entry for "${family}"`).toBeTruthy();
    }
  });

  it('covers every non-all filter chip id', () => {
    for (const chip of FILTER_CHIPS) {
      if (chip.id === 'all') continue;
      expect(CHIP_GLYPHS[chip.id], `CHIP_GLYPHS missing an entry for chip "${chip.id}"`).toBeTruthy();
    }
  });

  it('the all chip deliberately carries no glyph', () => {
    expect(CHIP_GLYPHS.all).toBeUndefined();
  });

  // Mutation coverage for a deletion or typo FAMILY_GLYPHS' own keys can't catch on their own — the
  // seven-token family list, named against its source (index.css's `--color-topic-*` block) rather
  // than asserted as a bare literal copy of FAMILY_GLYPHS.
  it('carries exactly the seven --color-topic-* families (index.css)', () => {
    expect(Object.keys(FAMILY_GLYPHS).sort()).toEqual(
      ['air', 'aurora', 'coastal', 'dust', 'eclipse', 'night-sky', 'sun-moon'].sort(),
    );
  });
});
