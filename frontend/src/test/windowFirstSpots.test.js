import { describe, it, expect } from 'vitest';
import {
  buildWindowSpots, compareSpots, readableInkOn, spotBadgeStyle, spotOrderStatement,
} from '../utils/windowFirstSpots.js';
import { RATING_COLOURS } from '../components/markerUtils.js';

/** A briefing slot as the payload carries one. */
function slot(overrides = {}) {
  return {
    locationId: 1,
    locationName: 'Bamburgh Castle',
    claudeRating: 4,
    canopy: false,
    ...overrides,
  };
}

/** An event summary with one region holding the given slots. */
function summary(slots, regionName = 'Northumberland & Tyneside') {
  return { targetType: 'SUNSET', regions: [{ regionName, slots }], unregioned: [] };
}

/** WCAG relative luminance of `#RRGGBB`, so the contrast assertions are independent of the source. */
function luminance(hex) {
  const ch = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('buildWindowSpots', () => {
  it('carries the name, region and rating each card renders', () => {
    const [s] = buildWindowSpots(summary([slot()]), new Map());
    expect(s).toMatchObject({
      locationId: 1,
      locationName: 'Bamburgh Castle',
      regionName: 'Northumberland & Tyneside',
      rating: 4,
    });
  });

  it('joins per-user reach on the location id', () => {
    const reach = new Map([[1, { driveMinutes: 66, distanceMiles: 47 }]]);
    const [s] = buildWindowSpots(summary([slot()]), reach);
    expect(s.driveMinutes).toBe(66);
    expect(s.distanceMiles).toBe(47);
  });

  it('leaves reach null when this location is not in the reach payload', () => {
    // The gap is real: reach covers the ENABLED roster, and a briefing cached before a location
    // was disabled still carries its slot.
    const [s] = buildWindowSpots(summary([slot()]), new Map([[99, { driveMinutes: 10 }]]));
    expect(s.driveMinutes).toBeNull();
    expect(s.distanceMiles).toBeNull();
  });

  it('leaves reach null for a slot cached before slots carried a location id', () => {
    // The join key is the id and the reach contract has no name at all, so a null id joins to
    // nothing. It must not fall through to a name match, which would be a second join rule.
    const [s] = buildWindowSpots(
      summary([slot({ locationId: null })]),
      new Map([[1, { driveMinutes: 66, distanceMiles: 47 }]]),
    );
    expect(s.locationId).toBeNull();
    expect(s.driveMinutes).toBeNull();
    expect(s.key).toBe('Bamburgh Castle');
  });

  it('survives a reach map that has not arrived yet', () => {
    expect(buildWindowSpots(summary([slot()]), undefined)[0].driveMinutes).toBeNull();
    expect(buildWindowSpots(summary([slot()]), null)[0].distanceMiles).toBeNull();
  });

  describe('the slot population, which must match the header star\'s', () => {
    it('drops a canopy slot from a window that also has sky slots', () => {
      // A woodland GO means heavy cloud and mist — the opposite claim. Its 5★ beside a coast's 4★
      // would put two opposite meanings in one badge, and it would out-rank the header's own
      // `best N★`, which the projector computes over non-canopy slots.
      const spots = buildWindowSpots(summary([
        slot({ locationId: 1, locationName: 'Bamburgh Castle', claudeRating: 4 }),
        slot({ locationId: 2, locationName: 'Cragside Wood', claudeRating: 5, canopy: true }),
      ]), new Map());
      expect(spots.map((s) => s.locationName)).toEqual(['Bamburgh Castle']);
    });

    it('keeps canopy slots when every slot in the window is one', () => {
      // Mirrors the projector's fallback: an all-canopy window reports its own slots rather than
      // nothing, so the strip must not be empty where the header shows a star.
      const spots = buildWindowSpots(summary([
        slot({ locationId: 2, locationName: 'Cragside Wood', claudeRating: 5, canopy: true }),
      ]), new Map());
      expect(spots.map((s) => s.locationName)).toEqual(['Cragside Wood']);
    });

    it('keys the all-canopy fallback on presence, not on whether a sky slot is rated', () => {
      // The difference is an ordinary misty sunrise: the fog that leaves every sky slot unrated is
      // the same fog that scores the wood well. Rated-keyed, that morning hands the window to the
      // wood — so an unrated sky slot must still suppress the canopy one.
      const spots = buildWindowSpots(summary([
        slot({ locationId: 1, locationName: 'Blyth Beach', claudeRating: null }),
        slot({ locationId: 2, locationName: 'Cragside Wood', claudeRating: 5, canopy: true }),
      ]), new Map());
      expect(spots.map((s) => s.locationName)).toEqual(['Blyth Beach']);
    });

    it('ignores unregioned slots, which are never scored and have no region to name', () => {
      const es = summary([slot()]);
      es.unregioned = [slot({ locationId: 9, locationName: 'Nowhere in particular' })];
      expect(buildWindowSpots(es, new Map()).map((s) => s.locationName)).toEqual(['Bamburgh Castle']);
    });

    it.each([[0], [6], [null], [undefined]])('discards a rating of %s, as the projector does', (rating) => {
      // Out of 1–5. The header star refuses these, and a badge printing one would let a value be
      // both rejected and displayed.
      const [s] = buildWindowSpots(summary([slot({ claudeRating: rating })]), new Map());
      expect(s.rating).toBeNull();
    });

    it.each([[1], [5]])('keeps a rating of %s, which is inside the bounds', (rating) => {
      expect(buildWindowSpots(summary([slot({ claudeRating: rating })]), new Map())[0].rating)
        .toBe(rating);
    });
  });

  it('returns an empty list for a window whose regions carry no slots', () => {
    // Not hypothetical — this is exactly what the local briefing serves, and the card must then
    // render neither strip nor footer.
    expect(buildWindowSpots(summary([]), new Map())).toEqual([]);
    expect(buildWindowSpots({ regions: [] }, new Map())).toEqual([]);
    expect(buildWindowSpots(null, new Map())).toEqual([]);
    expect(buildWindowSpots(undefined, undefined)).toEqual([]);
  });

  it('flattens every region into one strip, in ranked order', () => {
    const es = {
      regions: [
        { regionName: 'The Dales', slots: [slot({ locationId: 7, locationName: 'Malham Cove', claudeRating: 2 })] },
        { regionName: 'The Lakes', slots: [slot({ locationId: 10, locationName: 'Buttermere', claudeRating: 4 })] },
      ],
    };
    expect(buildWindowSpots(es, new Map()).map((s) => s.locationName))
      .toEqual(['Buttermere', 'Malham Cove']);
  });
});

describe('compareSpots', () => {
  const s = (rating, driveMinutes, locationName) => ({ rating, driveMinutes, locationName });

  it('puts the higher rating first', () => {
    expect([s(3, 10, 'a'), s(5, 90, 'b')].sort(compareSpots).map((x) => x.locationName))
      .toEqual(['b', 'a']);
  });

  it('breaks an equal rating on the shorter drive', () => {
    expect([s(4, 90, 'a'), s(4, 20, 'b')].sort(compareSpots).map((x) => x.locationName))
      .toEqual(['b', 'a']);
  });

  it('breaks an equal rating and drive on the name, so the order is total', () => {
    expect([s(4, 20, 'Simonside'), s(4, 20, 'Bamburgh')].sort(compareSpots).map((x) => x.locationName))
      .toEqual(['Bamburgh', 'Simonside']);
  });

  it('sorts an unrated spot below every rated one', () => {
    // Unknown is not good. A spot nothing has looked at must not lead a strip over a 1★.
    expect([s(null, 5, 'a'), s(1, 90, 'b')].sort(compareSpots).map((x) => x.locationName))
      .toEqual(['b', 'a']);
  });

  it('sorts a spot with no drive time below one that has any drive time', () => {
    // The mirror rule: absence means "unknown, never out of reach", so it cannot read as "nearest".
    expect([s(4, null, 'a'), s(4, 140, 'b')].sort(compareSpots).map((x) => x.locationName))
      .toEqual(['b', 'a']);
  });
});

describe('spotOrderStatement', () => {
  it('names both keys when both fire', () => {
    expect(spotOrderStatement([{ rating: 4, driveMinutes: 20 }]))
      .toBe('Ranked by rating, then drive time.');
  });

  it('drops drive time when no spot has one', () => {
    // The normal first-run state — no home postcode, so no drive times at all. Naming a key that
    // never fired would describe a ranking that did not happen.
    expect(spotOrderStatement([{ rating: 4, driveMinutes: null }])).toBe('Ranked by rating.');
  });

  it('drops rating when nothing in the window is rated', () => {
    expect(spotOrderStatement([{ rating: null, driveMinutes: 20 }])).toBe('Ranked by drive time.');
  });

  it('claims no ranking at all when neither key fires', () => {
    // Both absent is a real state: an unevaluated briefing read by a user with no home postcode.
    // The order is then purely alphabetical, and the footer has to say so.
    expect(spotOrderStatement([{ rating: null, driveMinutes: null }])).toBe('Listed alphabetically.');
    expect(spotOrderStatement([])).toBe('Listed alphabetically.');
  });

  it('names a key that only one spot in the window carries', () => {
    expect(spotOrderStatement([{ rating: null, driveMinutes: null }, { rating: 2, driveMinutes: null }]))
      .toBe('Ranked by rating.');
  });
});

describe('the rating badge', () => {
  it.each([1, 2, 3, 4, 5])('prints %s★ readably — at least 4.5:1, the AA floor for 10px type', (rating) => {
    // The whole reason `readableInkOn` is computed rather than tabulated: RATING_COLOURS peaks in
    // luminance at 3★ and falls away on both sides, so NO single ink clears AA on all five steps.
    // Dark ink fails 1★ and 5★; light ink fails 2★, 3★ and 4★.
    const { background, color } = spotBadgeStyle(rating);
    expect(background).toBe(RATING_COLOURS[rating]);
    expect(contrast(background, color)).toBeGreaterThanOrEqual(4.5);
  });

  it('picks the light ink exactly where the dark one would fail', () => {
    // Pinned as the two ends specifically: they are the dimmest steps, and §2.9 warns they are
    // also the two that sit under 3:1 against the card surface — so a change that made the badge
    // a tint rather than a fill would break here first.
    expect(spotBadgeStyle(1).color).toBe('#F2E7D3');
    expect(spotBadgeStyle(5).color).toBe('#F2E7D3');
    expect(spotBadgeStyle(3).color).toBe('#0F172A');
  });

  it('renders no badge at all for an unrated spot', () => {
    // Not a grey placeholder: an unrated spot is one nothing has looked at, which is a different
    // statement from a poor one — the same rule that omits the header's star.
    expect(spotBadgeStyle(null)).toBeNull();
    expect(spotBadgeStyle(undefined)).toBeNull();
    expect(spotBadgeStyle(0)).toBeNull();
  });

  it('reads the medallion palette rather than a copy of it', () => {
    // §2.9 pins RATING_COLOURS as this badge's palette and calls it unchanged. A local copy would
    // let the map marker and the spot card drift apart on the same number.
    expect(readableInkOn('#FFFFFF')).toBe('#0F172A');
    expect(readableInkOn('#000000')).toBe('#F2E7D3');
  });
});
