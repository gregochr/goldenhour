import { describe, it, expect } from 'vitest';
import { buildWindowSpots, placesOf, readableInkOn } from '../utils/windowFirstSpots.js';

/**
 * Edges of the spot join the main suite leaves unpinned: the badge-ink decision at the contrast
 * boundary, the tide-fact join by id then name (first fact wins), the gated marker, and the
 * defensive handling of absent or malformed input. Fixtures are built inside each `it`.
 */

const DARK = '#0F172A';
const LIGHT = '#FFFFFF';

/** An event summary with one region holding `slots`, and optional per-window tide facts. */
function summary(slots, tideFacts) {
  return {
    targetType: 'SUNSET',
    regions: [{ regionName: 'Coast', slots }],
    window: tideFacts === undefined ? undefined : { tideFacts },
  };
}

const slot = (over = {}) => ({
  locationId: 1, locationName: 'Bamburgh', claudeRating: 4, canopy: false, ...over,
});

describe('readableInkOn — the contrast decision at its boundary', () => {
  // Each colour sits within a hair of the colour where dark and light ink tie, and is chosen so
  // that one specific slip in the WCAG relative-luminance arithmetic flips the answer. The
  // expected inks were computed from an independent WCAG implementation.
  it.each([
    ['#0000D9', LIGHT],
    ['#0000DA', LIGHT],
    ['#000600', LIGHT],
    ['#000601', LIGHT],
    ['#006FFC', LIGHT],
    ['#006FFD', LIGHT],
    ['#0075FE', DARK],
    ['#0075FF', DARK],
    ['#0078F2', LIGHT],
    ['#0084B5', LIGHT],
  ])('picks the right ink on %s', (fill, ink) => {
    expect(readableInkOn(fill)).toBe(ink);
  });

  it('reads the extremes the obvious way round', () => {
    expect(readableInkOn('#000000')).toBe(LIGHT);
    expect(readableInkOn('#FFFFFF')).toBe(DARK);
  });
});

describe('placesOf — malformed lists', () => {
  it('tolerates a null entry in a list with no gated place, returning the list as it was', () => {
    const list = [null, { key: 'a' }, { key: 'b', gated: false }];
    expect(placesOf(list)).toBe(list);
  });

  it('drops the gated places from a list that holds some', () => {
    const list = [{ key: 'a' }, { key: 'b', gated: true }];
    expect(placesOf(list)).toEqual([{ key: 'a' }]);
  });

  it('treats a non-array as no places', () => {
    expect(placesOf(undefined)).toEqual([]);
    expect(placesOf('nope')).toEqual([]);
  });
});

describe('buildWindowSpots — no regions', () => {
  it('gives no spots for an absent summary, absent regions or empty regions', () => {
    expect(buildWindowSpots(undefined, new Map())).toEqual([]);
    expect(buildWindowSpots({}, new Map())).toEqual([]);
    expect(buildWindowSpots({ regions: [] }, new Map())).toEqual([]);
  });

  it('tolerates a summary with regions but no window', () => {
    const [spot] = buildWindowSpots(summary([slot()]), new Map());
    expect(spot.tideState).toBeNull();
    expect(spot.tideAligned).toBeNull();
    expect(spot.tideQuality).toBeNull();
  });
});

describe('buildWindowSpots — the tide fact join', () => {
  const fact = (over = {}) => ({
    locationId: 1, locationName: 'Bamburgh', tideState: 'HIGH', tideAligned: true, tideAlignmentQuality: 0.8,
    ...over,
  });

  it('joins by location id when the fact names the place differently', () => {
    const [spot] = buildWindowSpots(
      summary([slot()], [fact({ locationName: 'Bamburgh Castle' })]),
      new Map(),
    );
    expect(spot).toMatchObject({ tideState: 'HIGH', tideAligned: true, tideQuality: 0.8 });
  });

  it('prefers the id match over a name match on a different fact', () => {
    const [spot] = buildWindowSpots(
      summary([slot()], [
        fact({ locationId: 99, locationName: 'Bamburgh', tideState: 'LOW' }),
        fact({ locationId: 1, locationName: 'Elsewhere', tideState: 'HIGH' }),
      ]),
      new Map(),
    );
    expect(spot.tideState).toBe('HIGH');
  });

  it('joins a slot with no id by name, against a fact with no id', () => {
    const [spot] = buildWindowSpots(
      summary([slot({ locationId: undefined })], [fact({ locationId: undefined })]),
      new Map(),
    );
    expect(spot).toMatchObject({ locationId: null, key: 'Bamburgh', tideState: 'HIGH' });
  });

  it('lets the first fact for a location id win', () => {
    const [spot] = buildWindowSpots(
      summary([slot()], [
        fact({ locationName: 'A', tideState: 'HIGH' }),
        fact({ locationName: 'B', tideState: 'LOW' }),
      ]),
      new Map(),
    );
    expect(spot.tideState).toBe('HIGH');
  });

  it('lets the first fact for a location name win', () => {
    const [spot] = buildWindowSpots(
      summary([slot({ locationId: undefined })], [
        fact({ locationId: undefined, tideState: 'HIGH' }),
        fact({ locationId: undefined, tideState: 'LOW' }),
      ]),
      new Map(),
    );
    expect(spot.tideState).toBe('HIGH');
  });

  it('skips a null fact in the list and still joins the facts around it', () => {
    const [spot] = buildWindowSpots(
      summary([slot()], [null, fact()]),
      new Map(),
    );
    expect(spot.tideState).toBe('HIGH');
  });

  it('skips a null fact in front of a name-only fact', () => {
    const [spot] = buildWindowSpots(
      summary([slot({ locationId: undefined })], [null, fact({ locationId: undefined })]),
      new Map(),
    );
    expect(spot.tideState).toBe('HIGH');
  });

  it('keeps a fact without an id out of the id index but joins it by name', () => {
    const spots = buildWindowSpots(
      summary(
        [slot({ locationId: 7, locationName: 'Seahouses' }), slot({ locationId: 8, locationName: 'Bamburgh' })],
        [fact({ locationId: undefined, locationName: 'Bamburgh', tideState: 'LOW' })],
      ),
      new Map(),
    );
    const byName = Object.fromEntries(spots.map((s) => [s.locationName, s]));
    // Seahouses has an id and no id fact, and its name has no fact: nothing joins.
    expect(byName.Seahouses.tideState).toBeNull();
    // Bamburgh has an id with no id fact, so it falls through to the name.
    expect(byName.Bamburgh.tideState).toBe('LOW');
  });

  it('serves tideAligned null when the matched fact has no tide state', () => {
    const [spot] = buildWindowSpots(
      summary([slot()], [fact({ tideState: undefined, tideAligned: true })]),
      new Map(),
    );
    expect(spot.tideState).toBeNull();
    expect(spot.tideAligned).toBeNull();
  });
});

describe('buildWindowSpots — reach join and the gated marker', () => {
  it('never joins a slot with no id to a reach entry keyed on null', () => {
    const reach = new Map([[null, { driveMinutes: 30, distanceMiles: 12 }]]);
    const [spot] = buildWindowSpots(summary([slot({ locationId: null })]), reach);
    expect(spot.driveMinutes).toBeNull();
    expect(spot.distanceMiles).toBeNull();
  });

  it('joins a slot with an id to its reach entry', () => {
    const reach = new Map([[1, { driveMinutes: 30, distanceMiles: 12, driveStatus: 'MEASURED' }]]);
    const [spot] = buildWindowSpots(summary([slot()]), reach);
    expect(spot).toMatchObject({ driveMinutes: 30, distanceMiles: 12, driveStatus: 'MEASURED' });
  });

  it('marks a slot the server gated, strips its star, and gives it no gated key when not gated', () => {
    const [gated] = buildWindowSpots(summary([slot({ gated: true })]), new Map());
    expect(gated).toMatchObject({ gated: true, rating: null });
  });

  it('does not treat gated: false as gated', () => {
    const [spot] = buildWindowSpots(summary([slot({ gated: false })]), new Map());
    expect(spot.rating).toBe(4);
    expect(spot).not.toHaveProperty('gated');
  });

  it('gates a slot the shaped list does not name, but not one it does', () => {
    const spots = buildWindowSpots(
      summary([slot(), slot({ locationId: 2, locationName: 'Craster' })]),
      new Map(),
      null,
      new Set([2]),
    );
    const byName = Object.fromEntries(spots.map((s) => [s.locationName, s]));
    expect(byName.Bamburgh).toMatchObject({ gated: true, rating: null });
    expect(byName.Craster.rating).toBe(4);
    expect(byName.Craster).not.toHaveProperty('gated');
  });

  it('never gates a slot with no id on the shaped list alone', () => {
    const [spot] = buildWindowSpots(
      summary([slot({ locationId: undefined })]), new Map(), null, new Set([2]),
    );
    expect(spot).not.toHaveProperty('gated');
    expect(spot.rating).toBe(4);
  });
});
