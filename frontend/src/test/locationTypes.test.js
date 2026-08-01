/**
 * Tests for the shared location-type metadata and predicates.
 *
 * The point of consolidating five copies into one module is that a new backend `LocationType`
 * costs one edit instead of five, and that the copies cannot drift apart again. These tests pin
 * the two things that made drift invisible before: every constant carries metadata, and the sky
 * predicate matches the backend gate it mirrors.
 */
import { describe, it, expect } from 'vitest';
import {
  LOCATION_TYPE_META,
  LOCATION_TYPES,
  LOCATION_TYPE_ICONS,
  DISPLAY_TYPES,
  SKY_SUBJECT_TYPES,
  locationTypeLabel,
  locationTypeIcons,
  isSkyPromptCandidate,
} from '../utils/locationTypes.js';

// Mirrors backend/src/main/java/com/gregochr/goldenhour/entity/LocationType.java. A constant added
// there and not here renders as nothing (badges filter unknowns out) or as a raw enum name — the
// silent failure this module exists to prevent, so it is asserted rather than assumed.
const BACKEND_LOCATION_TYPES = [
  'LANDSCAPE', 'WILDLIFE', 'SEASCAPE', 'WATERFALL', 'BLUEBELL', 'WOODLAND',
];

describe('LOCATION_TYPE_META', () => {
  it('covers every backend LocationType constant', () => {
    expect(Object.keys(LOCATION_TYPE_META).sort()).toEqual([...BACKEND_LOCATION_TYPES].sort());
  });

  it('gives every type both a label and an emoji', () => {
    for (const [type, meta] of Object.entries(LOCATION_TYPE_META)) {
      expect(meta.label, `${type} label`).toBeTruthy();
      expect(meta.emoji, `${type} emoji`).toBeTruthy();
    }
  });

  it('uses one emoji per type across every surface', () => {
    // The copies had already drifted: briefingDisplay used 💧 for WATERFALL where the map, the
    // popup, the badges and the admin list all used 💦. Deriving the icon map from the metadata
    // makes that divergence unrepresentable.
    expect(LOCATION_TYPE_ICONS.WATERFALL).toBe(LOCATION_TYPE_META.WATERFALL.emoji);
    expect(Object.keys(LOCATION_TYPE_ICONS)).toEqual(LOCATION_TYPES);
  });
});

describe('DISPLAY_TYPES', () => {
  it('omits BLUEBELL — a seasonal subject, not a kind of place', () => {
    expect(DISPLAY_TYPES).not.toContain('BLUEBELL');
  });

  it('includes WOODLAND, which is structural and year-round', () => {
    expect(DISPLAY_TYPES).toContain('WOODLAND');
  });

  it('contains only real types', () => {
    for (const type of DISPLAY_TYPES) {
      expect(LOCATION_TYPE_META[type], type).toBeDefined();
    }
  });
});

describe('locationTypeLabel', () => {
  it('labels BLUEBELL even though it has no permanent chip', () => {
    expect(locationTypeLabel('BLUEBELL')).toBe('Bluebell');
  });

  it('falls back to the raw name for an unknown constant', () => {
    // Deliberate: a type this build has never heard of should read as an unfamiliar word rather
    // than vanish, so the gap is visible.
    expect(locationTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('isSkyPromptCandidate', () => {
  it.each(SKY_SUBJECT_TYPES)('%s has a sky subject', (type) => {
    expect(isSkyPromptCandidate([type])).toBe(true);
  });

  it('includes WATERFALL — the harness predicates used to omit it', () => {
    // ModelTestView (twice) and PromptTestView tested LANDSCAPE || SEASCAPE only, so waterfall
    // locations could not be selected in either harness even though the backend gate admits them.
    expect(isSkyPromptCandidate(['WATERFALL'])).toBe(true);
  });

  it('excludes WOODLAND — a canopy site has no sky to forecast', () => {
    expect(isSkyPromptCandidate(['WOODLAND'])).toBe(false);
  });

  it('excludes a wildlife-only location', () => {
    expect(isSkyPromptCandidate(['WILDLIFE'])).toBe(false);
  });

  it('admits an untyped location, matching the backend guard', () => {
    expect(isSkyPromptCandidate([])).toBe(true);
    expect(isSkyPromptCandidate(null)).toBe(true);
    expect(isSkyPromptCandidate(undefined)).toBe(true);
  });

  it('admits a wood that also has an open aspect', () => {
    expect(isSkyPromptCandidate(['WOODLAND', 'LANDSCAPE'])).toBe(true);
  });

  it('does not admit a bluebell wood on the strength of BLUEBELL alone', () => {
    expect(isSkyPromptCandidate(['BLUEBELL', 'WOODLAND'])).toBe(false);
  });
});

describe('locationTypeIcons', () => {
  it('renders every display type a location carries, in display order', () => {
    // Order comes from LOCATION_TYPE_META rather than the array's own, so the same location
    // cannot read 🏔️🌊 in one row and 🌊🏔️ in the next.
    expect(locationTypeIcons(['SEASCAPE', 'LANDSCAPE'])).toBe('🏔️🌊');
  });

  it('renders a multi-typed location AT ALL — the bug this exists to fix', () => {
    // The briefing rows indexed the icon map with the array itself. `['LANDSCAPE']` coerced to
    // the string 'LANDSCAPE' and happened to hit, so single-typed rows looked fine; anything with
    // two types produced 'LANDSCAPE,BLUEBELL', missed, and rendered nothing. Rannerdale Knotts —
    // an open fell that also carries bluebells — was one of the locations showing no icon.
    expect(locationTypeIcons(['LANDSCAPE', 'BLUEBELL'])).toBe('🏔️');
  });

  it('never asserts a bloom: BLUEBELL is a seasonal subject, not a badge', () => {
    // DISPLAY_TYPES excludes it deliberately, and this is one of the surfaces that rule protects —
    // the drill-down list is read in August as often as in May.
    expect(locationTypeIcons(['BLUEBELL'])).toBe('');
    expect(locationTypeIcons(['BLUEBELL', 'WOODLAND'])).toBe('🌳');
  });

  it('tolerates the shapes the callers can actually hand it', () => {
    expect(locationTypeIcons(null)).toBe('');
    expect(locationTypeIcons(undefined)).toBe('');
    expect(locationTypeIcons([])).toBe('');
    expect(locationTypeIcons('LANDSCAPE')).toBe('🏔️');
    expect(locationTypeIcons(['NOT_A_TYPE'])).toBe('');
  });
});
