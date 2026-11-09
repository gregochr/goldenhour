import { describe, it, expect } from 'vitest';
import { buildPlanConflict } from '../utils/planConflicts.js';

/**
 * Edge behaviour of the page-level conflict message that {@code planConflicts.test.js} leaves open:
 * the shape of a card that is missing fields, the tie-break rules of the location and ceiling scans,
 * and the "which cards count" quantifiers (any window versus every window).
 *
 * <p>Cards here are hand-built rather than run through the gates, because several cases are exactly
 * about a card whose fields are absent or whose window disagrees with its neighbour.
 */

function spot(overrides = {}) {
  return {
    locationId: 1,
    locationName: 'Bamburgh Beach',
    rating: 4,
    driveMinutes: 100,
    ...overrides,
  };
}

function card(overrides = {}) {
  return {
    kicker: 'Tomorrow',
    when: 'sunset',
    time: '20:41',
    allSpots: [],
    pool: [],
    spots: [],
    ...overrides,
  };
}

function reachConflict(cards, extra = {}) {
  return buildPlanConflict({
    cards, tierId: '45', limitMinutes: 45, floorId: 'any', minRating: null, ...extra,
  });
}

function ratingConflict(cards, extra = {}) {
  return buildPlanConflict({
    cards, tierId: 'any', limitMinutes: null, floorId: '4', minRating: 4, ...extra,
  });
}

describe('planConflicts edges — which locations make up the scope', () => {
  it('ignores an empty entry in a card\'s spot list', () => {
    const message = ratingConflict([card({
      allSpots: [null, spot()], pool: [spot({ rating: 3 })], spots: [],
    })]);
    expect(message.id).toBe('rating');
  });

  it('does not count a spot that has neither an id nor a name', () => {
    const message = reachConflict([card({ allSpots: [spot(), { driveMinutes: 80 }] })]);
    // The nameless 80-minute spot is no location, so it cannot be the nearest.
    expect(message.line.text).toBe('within 45 min · nearest is Bamburgh Beach, 1h 40min');
  });

  it('keeps the first spot seen for a location that appears in several windows', () => {
    const first = spot({ locationName: 'First sight', driveMinutes: 100 });
    const later = spot({ locationName: 'Later sight', driveMinutes: 60 });
    const message = reachConflict([card({ allSpots: [first] }), card({ allSpots: [later] })]);
    expect(message.line.text).toBe('within 45 min · nearest is First sight, 1h 40min');
  });
});

describe('planConflicts edges — the closest location', () => {
  it('picks the shortest drive whatever order the spots arrive in', () => {
    const message = reachConflict([card({
      allSpots: [
        spot({ locationId: 1, locationName: 'Far', driveMinutes: 120 }),
        spot({ locationId: 2, locationName: 'Near', driveMinutes: 60 }),
        spot({ locationId: 3, locationName: 'Middle', driveMinutes: 90 }),
      ],
    })]);
    expect(message.line.text).toBe('within 45 min · nearest is Near, 1h');
  });

  it('keeps the earlier spot when two drives tie', () => {
    const message = reachConflict([card({
      allSpots: [
        spot({ locationId: 1, locationName: 'Alpha', driveMinutes: 100 }),
        spot({ locationId: 2, locationName: 'Bravo', driveMinutes: 100 }),
      ],
    })]);
    expect(message.line.text).toContain('nearest is Alpha, ');
  });

  it('skips a location whose drive is unknown and names one that has a drive', () => {
    const message = reachConflict([card({
      allSpots: [
        spot({ locationId: 1, locationName: 'Unrouted', driveMinutes: null, driveStatus: 'UNROUTABLE' }),
        spot({ locationId: 2, locationName: 'Measured', driveMinutes: 120 }),
      ],
    })], { reachMeasured: true });
    expect(message.line.text).toBe('within 45 min · nearest is Measured, 2h');
  });

  it.each([
    ['not a number', Number.NaN],
    ['infinite', Number.POSITIVE_INFINITY],
  ])('does not name a location whose drive is %s', (_label, driveMinutes) => {
    const message = reachConflict([card({ allSpots: [spot({ driveMinutes })] })]);
    expect(message.line.text).toBe('within 45 min');
  });

  it('stops at the tier, with its action and a plain announce, when no drive is measured', () => {
    const message = reachConflict([card({
      allSpots: [
        spot({ locationId: 1, driveMinutes: null, driveStatus: 'UNROUTABLE' }),
        spot({ locationId: 2, driveMinutes: null, driveStatus: 'UNROUTABLE' }),
      ],
    })], { reachMeasured: true });
    expect(message.line.text).toBe('within 45 min');
    expect(message.line.announce).toBe('Nothing within 45 min.');
    expect(message.line.action).toEqual({ kind: 'reach', id: 'any', label: 'Show any distance' });
  });
});

describe('planConflicts edges — the widening offered', () => {
  it('offers a tier that helps SOME window, not one that would help every window', () => {
    const message = reachConflict([
      card({ allSpots: [spot({ driveMinutes: 100 })] }),
      card({ allSpots: [] }),
    ]);
    expect(message.line.action).toEqual({ kind: 'reach', id: '150', label: 'Widen to 2h 30min' });
  });

  it('does not let a window with no spot list help a tier', () => {
    const noList = { kicker: 'Today', when: 'sunrise', time: '06:10', pool: [] };
    const message = reachConflict([card({ allSpots: [spot({ driveMinutes: 100 })] }), noList]);
    expect(message.line.action).toEqual({ kind: 'reach', id: '150', label: 'Widen to 2h 30min' });
  });

  it('only walks tiers looser than the active one', () => {
    // The hand-built card contradicts its own tier on purpose: a 100-minute spot would pass the
    // 150-minute tier, so a scan that started from the tightest tier would offer it. The message
    // is about what is LOOSER than the tier the reader is on.
    const message = reachConflict([card({ allSpots: [spot({ driveMinutes: 100 })] })], {
      tierId: '150', limitMinutes: 150,
    });
    expect(message.line.action).toEqual({ kind: 'reach', id: 'any', label: 'Show any distance' });
  });

  it('⚠️ offers no action at all, and no way home, when the active tier has nothing looser', () => {
    // Defensive and unreachable with a real lens (the ladder always walks to Any while the scope
    // holds anything); a lens whose id says Any but whose threshold says 45 is the contradiction
    // that reaches it. Away, the line then names where it is measured from; at home it does not.
    const cards = [card({ allSpots: [spot()] })];
    const away = reachConflict(cards, {
      tierId: 'any', limitMinutes: 45, origin: { name: 'Lakes', baseName: 'Keswick' },
    });
    expect(away.line).toEqual({
      lead: '0',
      text: 'within 45 min of Keswick',
      action: null,
      announce: 'Nothing within 45 min of Keswick.',
    });
    const home = reachConflict(cards, { tierId: 'any', limitMinutes: 45 });
    expect(home.line).toEqual({
      lead: '0', text: 'within 45 min', action: null, announce: 'Nothing within 45 min.',
    });
  });

  it('says nothing when the tier is Any, even with an empty pool', () => {
    const message = reachConflict([card({ allSpots: [spot()] })], { tierId: 'any', limitMinutes: null });
    expect(message).toBeNull();
  });
});

describe('planConflicts edges — what counts as a window with something in it', () => {
  it('tolerates a null entry among the cards', () => {
    const message = ratingConflict([null, card({
      allSpots: [spot()], pool: [spot({ rating: 3 })], spots: [],
    })]);
    expect(message.id).toBe('rating');
  });

  it('treats one window with a pool as a pool, so the floor is blamed rather than the reach', () => {
    const message = ratingConflict([
      card({ allSpots: [spot()], pool: [spot({ rating: 3 })], spots: [] }),
      card({ allSpots: [spot({ locationId: 2 })], pool: [], spots: [] }),
    ], { tierId: '45', limitMinutes: 45 });
    expect(message.id).toBe('rating');
  });

  it('treats a window with no pool field as an empty pool', () => {
    const message = reachConflict([{ allSpots: [spot()], spots: [] }]);
    expect(message.id).toBe('reach');
  });

  it('treats a window with no spots field as shut', () => {
    const message = ratingConflict([{
      kicker: 'Tomorrow', when: 'sunset', time: '20:41', allSpots: [spot()], pool: [spot({ rating: 3 })],
    }]);
    expect(message.id).toBe('rating');
  });

  it('is not shut while any one window still draws a spot', () => {
    const message = ratingConflict([
      card({ allSpots: [spot()], pool: [spot({ rating: 3 })], spots: [] }),
      card({ allSpots: [spot({ locationId: 2 })], pool: [spot({ locationId: 2, rating: 5 })],
        spots: [spot({ locationId: 2, rating: 5 })] }),
    ]);
    expect(message).toBeNull();
  });

  it('is not shut while a single window draws several spots', () => {
    const drawn = [spot({ rating: 5 }), spot({ locationId: 2, rating: 4 })];
    const message = ratingConflict([card({ allSpots: drawn, pool: drawn, spots: drawn })]);
    expect(message).toBeNull();
  });

  it('says nothing about a floor that is not set, however empty the drawn spots are', () => {
    const message = ratingConflict([
      card({ allSpots: [spot()], pool: [spot({ rating: 3 })], spots: [] }),
    ], { floorId: 'any', minRating: null });
    expect(message).toBeNull();
  });

  it('says nothing when the floor id is not one the bar knows', () => {
    const message = ratingConflict([
      card({ allSpots: [spot()], pool: [spot({ rating: 3 })], spots: [] }),
    ], { floorId: '9' });
    expect(message).toBeNull();
  });
});

describe('planConflicts edges — the ceiling sentence', () => {
  const lowCard = (overrides) => card({
    allSpots: [spot()], pool: [spot({ rating: 3, locationName: 'Copt Hill' })], spots: [], ...overrides,
  });

  it('tolerates a window that carries no pool beside one that does', () => {
    const noPool = { kicker: 'Today', when: 'sunrise', time: '06:10', allSpots: [spot()], spots: [] };
    const outlier = spot({ rating: 3, locationName: 'Outlier Point', neighbourOutlier: true });
    expect(ratingConflict([noPool, lowCard({})]).line.text)
      .toBe('at 4★+ · best is 3★, Copt Hill, tomorrow sunset 20:41');
    expect(ratingConflict([noPool, lowCard({ pool: [outlier] })]).line.text)
      .toBe('at 4★+ · best is 3★, tomorrow sunset 20:41');
  });

  it('skips an empty entry in a pool', () => {
    const message = ratingConflict([lowCard({ pool: [null, spot({ rating: 3, locationName: 'Copt Hill' })] })]);
    expect(message.line.text).toBe('at 4★+ · best is 3★, Copt Hill, tomorrow sunset 20:41');
  });

  it('names the earlier window when two windows share the best rating', () => {
    const message = ratingConflict([
      lowCard({ kicker: 'Today', when: 'sunrise', time: '06:10' }),
      lowCard({ kicker: 'Tomorrow', when: 'sunset', time: '20:41' }),
    ]);
    expect(message.line.text).toBe('at 4★+ · best is 3★, Copt Hill, today sunrise 06:10');
  });

  it('finds a non-outlier carrier in a later window and survives an empty pool entry before it', () => {
    const outlier = spot({ rating: 3, locationName: 'Outlier Point', neighbourOutlier: true });
    const sound = spot({ locationId: 2, rating: 3, locationName: 'Sound Point' });
    const message = ratingConflict([
      card({ allSpots: [outlier], pool: [outlier], spots: [] }),
      card({ kicker: 'Today', when: 'sunrise', time: '06:10', allSpots: [sound], pool: [null, sound], spots: [] }),
    ]);
    expect(message.line.text).toBe('at 4★+ · best is 3★, Sound Point, today sunrise 06:10');
  });

  it('names the window of the outlier-only ceiling, not the first window scanned', () => {
    const modest = spot({ locationId: 5, rating: 1, locationName: 'Modest' });
    const outlier = spot({ rating: 3, locationName: 'Outlier Point', neighbourOutlier: true });
    const message = ratingConflict([
      card({ kicker: 'Today', when: 'sunrise', time: '06:10', allSpots: [modest], pool: [modest], spots: [] }),
      card({ kicker: 'Friday', when: 'sunset', time: '20:41', allSpots: [outlier], pool: [outlier], spots: [] }),
    ]);
    expect(message.line.text).toBe('at 4★+ · best is 3★, friday sunset 20:41');
  });

  it('leaves out a missing kicker and a missing time instead of padding with spaces', () => {
    const message = ratingConflict([lowCard({ kicker: undefined, time: undefined })]);
    expect(message.line.text).toBe('at 4★+ · best is 3★, Copt Hill, sunset');
  });

  it('says nothing has been rated when every pooled spot is unrated', () => {
    const unrated = spot({ rating: null });
    const message = ratingConflict([card({ allSpots: [unrated], pool: [unrated], spots: [] })]);
    expect(message.line.text).toBe('at 4★+ · nothing rated yet');
  });

  it('names the tier only when a drive time exists somewhere in the scope', () => {
    const measured = spot({ rating: 2, driveMinutes: 30 });
    const unknown = spot({ locationId: 2, rating: 2, driveMinutes: null });
    const message = ratingConflict([
      card({ allSpots: [measured, unknown], pool: [measured, unknown], spots: [] }),
    ], { tierId: '45', limitMinutes: 45 });
    expect(message.line.text).toContain('best in reach is 2★');
  });
});
