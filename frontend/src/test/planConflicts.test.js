import { describe, it, expect } from 'vitest';
import { buildPlanConflict } from '../utils/planConflicts.js';
import { gateSpotsByReach } from '../utils/reachLens.js';
import { gateSpotsByRating } from '../utils/ratingLens.js';

/**
 * The page-level conflict message — what the whole plan says when the lens has shut it.
 *
 * <h2>What this file inherits, and from where</h2>
 *
 * <p>It replaces {@code windowLensEmpty.test.js}, which covered the same job one level down: the
 * per-card empty state deleted at M2 when the six-row list went. The assertions salvaged here are
 * the ones that describe behaviour the page-level message still has — the ladder that refuses to
 * offer a step which would change nothing, the LITE path that offers no reach action with no role
 * anywhere near it, the unrated-versus-low-ceiling distinction, and the away origin's way home. The
 * ones about a single card's headline are gone with the card, and their per-window replacement is
 * the popup's quiet sentence, asserted in {@code WindowSheetDialog.test.jsx}.
 *
 * <h2>The gates are RUN, never assumed</h2>
 *
 * <p>The harness builds each card by running the real reach and rating gates over its own spots,
 * the way {@code buildWindowCards} does. A fixture that hand-writes {@code pool: []} can be right
 * about a state the pipeline never produces; running the gates means every case below is one the
 * page can actually reach.
 */

/** A spot as `buildWindowSpots` emits one. */
function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    regionName: 'Coast',
    rating: 4,
    driveMinutes: 30,
    ...overrides,
  };
}

/** A window card with both gates applied, exactly as `buildWindowCards` applies them. */
function card(allSpots, { limitMinutes = null, minRating = null, kicker = 'Tomorrow', when = 'sunset', time = '20:41' } = {}) {
  const pool = gateSpotsByReach(allSpots, limitMinutes);
  return {
    key: '2026-08-21:SUNSET',
    kicker,
    when,
    time,
    allSpots,
    pool,
    spots: gateSpotsByRating(pool, minRating),
  };
}

function conflict({
  cards, origin = null, homePlace = 'Chester-le-Street',
  tierId = 'any', limitMinutes = null, floorId = 'any', minRating = null,
}) {
  return buildPlanConflict({
    cards, origin, homePlace, tierId, limitMinutes, floorId, minRating,
  });
}

describe('planConflicts — when there is anything to say at all', () => {
  it('says nothing while the plan still has spots to draw', () => {
    expect(conflict({ cards: [card([spot()])] })).toBeNull();
  });

  it('says nothing about a plan with no spots to begin with', () => {
    // No control would help, so a message offering one would be the banned no-op control. The
    // matrix's own empty cells and per-card "nothing in reach" already say what is true.
    expect(conflict({ cards: [card([])], tierId: '45', limitMinutes: 45 })).toBeNull();
  });

  it('says nothing when it is handed no cards at all', () => {
    expect(conflict({ cards: [] })).toBeNull();
    expect(buildPlanConflict({ cards: null, tierId: 'any', floorId: 'any' })).toBeNull();
  });
});

describe('planConflicts — reach is asked first, because it is upstream', () => {
  const far = [spot({ driveMinutes: 150 }), spot({ key: '2', locationId: 2, locationName: 'Roseberry Topping', driveMinutes: 95 })];

  it('names the threshold and the closest location, and offers the tier that helps', () => {
    const message = conflict({ cards: [card(far, { limitMinutes: 45 })], tierId: '45', limitMinutes: 45 });
    expect(message.id).toBe('reach');
    expect(message.line).toEqual({
      lead: '0',
      text: 'within 45 min · nearest is Roseberry Topping, 1h 35min',
      action: { kind: 'reach', id: '150', label: 'Widen to 2h 30min' },
      announce: 'Nothing within 45 min. Nearest is Roseberry Topping, 1h 35min.',
    });
  });

  it('never names the place it is measured from at home, or anywhere a wider tier helps', () => {
    // The drive caption above the line already says "From Keswick" / "Drive from home".
    const message = conflict({
      cards: [card(far, { limitMinutes: 45 })],
      tierId: '45',
      limitMinutes: 45,
      origin: { name: 'The Lake District', baseName: 'Keswick' },
    });
    expect(message.line.text).not.toContain('Keswick');
    expect(message.line.text).not.toContain('Chester-le-Street');
  });

  it('counts a location once however many windows hold it', () => {
    // Id-first, name-fallback — the arm's join policy. Two windows over one roster is one roster,
    // and the closest is the same place either way.
    const message = conflict({
      cards: [card(far, { limitMinutes: 45 }), card(far, { limitMinutes: 45 })],
      tierId: '45',
      limitMinutes: 45,
    });
    expect(message.line.text).toBe('within 45 min · nearest is Roseberry Topping, 1h 35min');
  });

  it('⚠️ skips a widening that would change nothing and names the one that does', () => {
    // The nearest is 95 minutes, so 1h 30min still leaves the plan empty. Offering it would be a
    // control with no visible effect — the defect this ladder exists to prevent.
    const message = conflict({ cards: [card(far, { limitMinutes: 45 })], tierId: '45', limitMinutes: 45 });
    expect(message.line.action.label).toBe('Widen to 2h 30min');
  });

  it('⚠️ walks all the way to Any when no BOUNDED tier helps, and says so in the chip\'s words', () => {
    // The ladder always terminates: `Any` gates nothing, so while the scope holds anything at all
    // there is a step that refills the plan. What the ladder is for is skipping the ones that do
    // not — see the case above.
    const unreachable = [spot({ driveMinutes: 400 })];
    const message = conflict({
      cards: [card(unreachable, { limitMinutes: 150 })], tierId: '150', limitMinutes: 150,
    });
    expect(message.line.action).toEqual({ kind: 'reach', id: 'any', label: 'Show any distance' });
  });

  it('⚠️ raises nothing for a LITE reader, because the tier they are pinned to gates nothing', () => {
    // A LITE reader is pinned to "Any", so no drive is ever removed and the reach message cannot
    // fire at all — which is the whole LITE story, with no role anywhere in the path.
    const message = conflict({
      cards: [card([spot({ driveMinutes: 400 })], { limitMinutes: null })],
      tierId: 'any',
      limitMinutes: null,
    });
    expect(message).toBeNull();
  });

  it('⚠️ says nothing rather than naming a threshold it cannot state', () => {
    // The guard the case above cannot reach: a stored tier id the ladder does not recognise (a
    // rolled-back build, a half-written key). Naming "Nothing within undefined" is worse than
    // silence, and the pool is genuinely empty here, so the branch is entered.
    const message = conflict({
      cards: [card([spot({ driveMinutes: 400 })], { limitMinutes: 45 })],
      tierId: 'sideways',
      limitMinutes: 45,
    });
    expect(message).toBeNull();
  });
});

describe('planConflicts — the rating floor shutting the week out', () => {
  const reachable = [
    spot({ rating: 2 }),
    spot({ key: '2', locationId: 2, locationName: 'Copt Hill', rating: 3, driveMinutes: 20 }),
  ];

  it('names the floor, the ceiling in reach, where it is, and the one action', () => {
    const message = conflict({
      cards: [card(reachable, { limitMinutes: 90, minRating: 4 })],
      tierId: '90',
      limitMinutes: 90,
      floorId: '4',
      minRating: 4,
    });
    expect(message.id).toBe('rating');
    // A single-date plan: "this week" would be false, so the window is named instead.
    expect(message.line).toEqual({
      lead: '0',
      text: 'at 4★+ · best in reach is 3★, Copt Hill, tomorrow sunset 20:41',
      action: { kind: 'rating', id: '3', label: 'Drop to 3★+' },
      announce: 'Nothing at 4 stars or better. Best in reach is 3 stars, Copt Hill, tomorrow sunset 20:41.',
    });
  });

  it('says "this week" and drops the window phrase only when the windows span several dates', () => {
    const message = conflict({
      cards: [
        { ...card(reachable, { minRating: 4 }), key: '2026-08-21:SUNSET' },
        { ...card([spot({ key: '9', locationId: 9, locationName: 'Marsden Bay', rating: 2 })], { minRating: 4 }), key: '2026-08-22:SUNRISE' },
      ],
      floorId: '4',
      minRating: 4,
    });
    expect(message.line.text).toBe('at 4★+ this week · best is 3★, Copt Hill');
    expect(message.line.announce).toBe('Nothing at 4 stars or better this week. Best is 3 stars, Copt Hill.');
  });

  it('reads the date from the card\'s own date field when it carries one', () => {
    const message = conflict({
      cards: [
        { ...card(reachable, { minRating: 4 }), key: 'x', date: '2026-08-21' },
        { ...card(reachable, { minRating: 4 }), key: 'x', date: '2026-08-22' },
      ],
      floorId: '4',
      minRating: 4,
    });
    expect(message.line.text).toContain('this week');
  });

  /**
   * ⚠️ §6 clause 7 on the verdict line, the third of four surfaces that made the same claim.
   *
   * <p>A reader with no home postcode has no drive time at all, and an unknown drive passes every
   * tier (plan §2.5), so the tier gated nothing. "Best in reach" would name a gate that did nothing.
   */
  describe('when no drive time exists for the tier to gate on', () => {
    const unmeasured = [
      spot({ rating: 2, driveMinutes: null }),
      spot({
        key: '2', locationId: 2, locationName: 'Copt Hill', rating: 3, driveMinutes: null,
      }),
    ];

    it('says "best is", never "best in reach", and keeps the ceiling', () => {
      const message = conflict({
        cards: [card(unmeasured, { limitMinutes: 90, minRating: 4 })],
        tierId: '90',
        limitMinutes: 90,
        floorId: '4',
        minRating: 4,
      });
      expect(message.line.text).toBe('at 4★+ · best is 3★, Copt Hill, tomorrow sunset 20:41');
    });

    it('still says "nothing rated yet" with no reach word', () => {
      const message = conflict({
        cards: [card([spot({ rating: null, driveMinutes: null })], { limitMinutes: 90, minRating: 4 })],
        tierId: '90',
        limitMinutes: 90,
        floorId: '4',
        minRating: 4,
      });
      expect(message.line.text).toBe('at 4★+ · nothing rated yet');
    });

    it('says "best in reach" where a drive time DOES exist', () => {
      // The control, so the flag cannot be deleted by making both arms unconditional.
      const message = conflict({
        cards: [card(reachable, { limitMinutes: 90, minRating: 4 })],
        tierId: '90',
        limitMinutes: 90,
        floorId: '4',
        minRating: 4,
      });
      expect(message.line.text).toContain('best in reach is 3★');
    });

    it('says "best is" when reach is Any even though drives exist', () => {
      const message = conflict({
        cards: [card(reachable, { minRating: 4 })], floorId: '4', minRating: 4,
      });
      expect(message.line.text).toContain('best is 3★');
      expect(message.line.text).not.toContain('in reach');
    });
  });

  it('⚠️ offers only "Show any rating" when the ceiling is below the lowest floor the bar can draw', () => {
    // A plan topping out at 2★ has no 2★ chip, and "drop to 2★+" would name a control that does not
    // exist.
    const message = conflict({
      cards: [card([spot({ rating: 2 })], { minRating: 3 })], floorId: '3', minRating: 3,
    });
    expect(message.line.action).toEqual({ kind: 'rating', id: 'any', label: 'Show any rating' });
    expect(message.line.text).toBe('at 3★+ · best is 2★, Bamburgh Beach, tomorrow sunset 20:41');
  });

  it('⚠️ says nothing has been rated rather than that none of it is good enough', () => {
    // The ordinary state on a far-horizon plan, and a different fact from a low ceiling: "none at
    // 4★+" over windows nobody looked at says the forecast was poor when there is no forecast.
    const message = conflict({
      cards: [card([spot({ rating: null })], { minRating: 4 })], floorId: '4', minRating: 4,
    });
    expect(message.line).toEqual({
      lead: '0',
      text: 'at 4★+ · nothing rated yet',
      action: { kind: 'rating', id: 'any', label: 'Show any rating' },
      announce: 'Nothing at 4 stars or better. Nothing rated yet.',
    });
  });

  it('takes the ceiling across every window, not the first one that has any', () => {
    const message = conflict({
      cards: [
        card([spot({ rating: 2 })], { minRating: 4, kicker: 'Today', when: 'sunset', time: '20:52' }),
        card([spot({ key: '9', locationId: 9, locationName: 'Marsden Bay', rating: 3 })], { minRating: 4 }),
      ],
      floorId: '4',
      minRating: 4,
    });
    expect(message.line.text).toContain('3★, Marsden Bay');
  });

  it('spells an exact-rating floor and a one-star ceiling in the singular and without "or better"', () => {
    const message = conflict({
      cards: [card([spot({ rating: 1 })], { minRating: 5 })], floorId: '5', minRating: 5,
    });
    expect(message.line.announce).toBe('Nothing at 5 stars. Best is 1 star, Bamburgh Beach, tomorrow sunset 20:41.');
  });

  describe('the neighbour guard (neighbour-guard-plan.md §3)', () => {
    const OUTLIER = spot({
      key: '5', locationId: 5, locationName: 'Caw Gap', rating: 4, neighbourOutlier: true,
    });

    it('keeps the figure but names the highest non-outlier place that carries it', () => {
      const twin = spot({ key: '6', locationId: 6, locationName: 'Hotbank', rating: 4 });
      const message = conflict({
        cards: [card([OUTLIER, twin], { minRating: 5 })], floorId: '5', minRating: 5,
      });
      expect(message.line.text).toBe('at 5★ · best is 4★, Hotbank, tomorrow sunset 20:41');
    });

    it('prints the figure with no place when only outliers carry it', () => {
      const lower = spot({ key: '6', locationId: 6, locationName: 'Hotbank', rating: 3 });
      const message = conflict({
        cards: [card([OUTLIER, lower], { minRating: 5 })], floorId: '5', minRating: 5,
      });
      // Never "with no one place named": the clause is simply dropped.
      expect(message.line.text).toBe('at 5★ · best is 4★, tomorrow sunset 20:41');
      expect(message.line.text).not.toContain('Caw Gap');
      expect(message.line.announce).not.toContain('Caw Gap');
    });

    it('still offers the drop-the-floor step for the figure when no place is named', () => {
      const message = conflict({
        cards: [card([OUTLIER], { minRating: 5 })], floorId: '5', minRating: 5,
      });
      expect(message.line.action.label).toBe('Drop to 4★+');
    });
  });

  it('is not raised while the floor gates nothing', () => {
    expect(conflict({ cards: [card([spot({ rating: 2 })])] })).toBeNull();
  });

  it('⚠️ yields to the reach message when both would fire', () => {
    // Reach is upstream: with nothing within the chosen drive, the floor has had nothing to reject,
    // and a message blaming it would point at the control that is not the cause.
    const message = conflict({
      cards: [card([spot({ driveMinutes: 400, rating: 2 })], { limitMinutes: 45, minRating: 4 })],
      tierId: '45',
      limitMinutes: 45,
      floorId: '4',
      minRating: 4,
    });
    expect(message.id).toBe('reach');
  });
});

describe('planConflicts — away, the way home is the place picker, not the verdict', () => {
  const origin = { name: 'The Lake District', baseName: 'Keswick' };

  it('offers the lens action only, in the reach branch', () => {
    const message = conflict({
      cards: [card([spot({ driveMinutes: 100 })], { limitMinutes: 45 })],
      origin,
      tierId: '45',
      limitMinutes: 45,
    });
    expect(message.line.action).toEqual({ kind: 'reach', id: '150', label: 'Widen to 2h 30min' });
    expect(message.line.text).toBe('within 45 min · nearest is Bamburgh Beach, 1h 40min');
  });

  it('⚠️ never offers an origin action in the rating branch either', () => {
    const ratingAway = conflict({
      cards: [card([spot({ rating: 2 })], { minRating: 4 })], origin, floorId: '4', minRating: 4,
    });
    expect(ratingAway.line.action.kind).toBe('rating');
    expect(JSON.stringify(ratingAway)).not.toContain('origin');
    expect(JSON.stringify(ratingAway)).not.toContain('home');
  });

  it('adds nothing about an origin at home', () => {
    const message = conflict({
      cards: [card([spot({ driveMinutes: 100 })], { limitMinutes: 45 })],
      tierId: '45',
      limitMinutes: 45,
    });
    expect(message.line.action.kind).toBe('reach');
  });
});
