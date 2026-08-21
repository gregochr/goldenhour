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

  it('names the threshold, the place it is measured from, and the closest location', () => {
    const message = conflict({ cards: [card(far, { limitMinutes: 45 })], tierId: '45', limitMinutes: 45 });
    expect(message.id).toBe('reach');
    expect(message.headline).toBe('Nothing within 45 min of Chester-le-Street.');
    expect(message.body).toBe('2 locations in your regions, and the closest is Roseberry Topping at 1h 35min.');
  });

  it('drops the "of X" clause when no home is known, rather than naming undefined', () => {
    const message = conflict({
      cards: [card(far, { limitMinutes: 45 })], tierId: '45', limitMinutes: 45, homePlace: null,
    });
    expect(message.headline).toBe('Nothing within 45 min.');
  });

  it('agrees with itself for one location', () => {
    const one = [spot({ driveMinutes: 150 })];
    const message = conflict({ cards: [card(one, { limitMinutes: 45 })], tierId: '45', limitMinutes: 45 });
    expect(message.body).toContain('1 location in your regions');
  });

  it('counts a location once however many windows hold it', () => {
    // Id-first, name-fallback — the arm's join policy. Two windows over one roster is one roster.
    const message = conflict({
      cards: [card(far, { limitMinutes: 45 }), card(far, { limitMinutes: 45 })],
      tierId: '45',
      limitMinutes: 45,
    });
    expect(message.body).toContain('2 locations');
  });

  it('⚠️ skips a widening that would change nothing and names the one that does', () => {
    // The nearest is 95 minutes, so 1h 30min still leaves the plan empty. Offering it would be a
    // control with no visible effect — the defect this ladder exists to prevent.
    const message = conflict({ cards: [card(far, { limitMinutes: 45 })], tierId: '45', limitMinutes: 45 });
    expect(message.actions.map((a) => a.label)).toEqual(['Widen to 2h 30min']);
  });

  it('⚠️ walks all the way to Any when no BOUNDED tier helps', () => {
    // The ladder always terminates: `Any` gates nothing, so while the scope holds anything at all
    // there is a step that refills the plan. What the ladder is for is skipping the ones that do
    // not — see the case above.
    const unreachable = [spot({ driveMinutes: 400 })];
    const message = conflict({
      cards: [card(unreachable, { limitMinutes: 150 })], tierId: '150', limitMinutes: 150,
    });
    expect(message.actions.map((a) => a.id)).toEqual(['any']);
    expect(message.actions[0].label).toBe('Widen to any');
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

  it('names the floor, the scope, the ceiling and where it is', () => {
    const message = conflict({
      cards: [card(reachable, { limitMinutes: 90, minRating: 4 })],
      tierId: '90',
      limitMinutes: 90,
      floorId: '4',
      minRating: 4,
    });
    expect(message.id).toBe('rating');
    expect(message.headline).toBe('Nothing at 4★+ anywhere in your regions this week.');
    expect(message.body).toBe('The best on offer within 1h 30min is 3★ — Copt Hill, tomorrow sunset 20:41.');
  });

  /**
   * ⚠️ §6 clause 7 on the page-level message, the third of four surfaces that made the same claim.
   *
   * <p>A reader with no home postcode has no drive time at all, and an unknown drive passes every
   * tier (plan §2.5), so the tier gated nothing. Both arms of this message named it anyway. Found by
   * an adversarial review of M5, after the popup and the lens readout had been fixed.
   */
  describe('when no drive time exists for the tier to gate on', () => {
    const unmeasured = [
      spot({ rating: 2, driveMinutes: null }),
      spot({
        key: '2', locationId: 2, locationName: 'Copt Hill', rating: 3, driveMinutes: null,
      }),
    ];

    it('drops the tier clause from the ceiling sentence and keeps the ceiling', () => {
      const message = conflict({
        cards: [card(unmeasured, { limitMinutes: 90, minRating: 4 })],
        tierId: '90',
        limitMinutes: 90,
        floorId: '4',
        minRating: 4,
      });
      expect(message.body).toBe('The best on offer is 3★ — Copt Hill, tomorrow sunset 20:41.');
    });

    it('says "nothing here" rather than "nothing within reach" when nothing is rated', () => {
      const message = conflict({
        cards: [card([spot({ rating: null, driveMinutes: null })], { limitMinutes: 90, minRating: 4 })],
        tierId: '90',
        limitMinutes: 90,
        floorId: '4',
        minRating: 4,
      });
      expect(message.body).toBe('Nothing here has been rated yet.');
    });

    it('still names the tier where a drive time DOES exist', () => {
      // The control, so the flag cannot be deleted by making both arms unconditional.
      const message = conflict({
        cards: [card(reachable, { limitMinutes: 90, minRating: 4 })],
        tierId: '90',
        limitMinutes: 90,
        floorId: '4',
        minRating: 4,
      });
      expect(message.body).toContain('within 1h 30min');
    });
  });

  it('offers the two the design asks for: show it as it is, or drop to the measured ceiling', () => {
    const message = conflict({
      cards: [card(reachable, { minRating: 4 })], floorId: '4', minRating: 4,
    });
    expect(message.actions.map((a) => a.label))
      .toEqual(['Show the week as it is', 'Or drop the floor to 3★+']);
  });

  it('⚠️ offers only "as it is" when the ceiling is below the lowest floor the bar can draw', () => {
    // A plan topping out at 2★ has no 2★ chip, and "drop the floor to 2★+" would name a control
    // that does not exist.
    const message = conflict({
      cards: [card([spot({ rating: 2 })], { minRating: 3 })], floorId: '3', minRating: 3,
    });
    expect(message.actions.map((a) => a.id)).toEqual(['any']);
  });

  it('⚠️ says nothing has been rated rather than that none of it is good enough', () => {
    // The ordinary state on a far-horizon plan, and a different fact from a low ceiling: "none at
    // 4★+" over windows nobody looked at says the forecast was poor when there is no forecast.
    const message = conflict({
      cards: [card([spot({ rating: null })], { minRating: 4 })], floorId: '4', minRating: 4,
    });
    expect(message.body).toBe('Nothing within reach has been rated yet.');
    expect(message.actions.map((a) => a.id)).toEqual(['any']);
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
    expect(message.body).toContain('3★ — Marsden Bay');
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

describe('planConflicts — away, the origin explains and the way home is offered', () => {
  const origin = { name: 'The Lake District', baseName: 'Keswick' };

  it('names the region rather than "your regions", and measures from the base', () => {
    const message = conflict({
      cards: [card([spot({ driveMinutes: 200 })], { limitMinutes: 45 })],
      origin,
      tierId: '45',
      limitMinutes: 45,
    });
    expect(message.headline).toBe('Nothing within 45 min of Keswick.');
    expect(message.body).toContain('in The Lake District');
  });

  it('appends the way home after the lens action, because widening keeps the reader where they are', () => {
    const message = conflict({
      cards: [card([spot({ driveMinutes: 100 })], { limitMinutes: 45 })],
      origin,
      tierId: '45',
      limitMinutes: 45,
    });
    expect(message.actions.map((a) => a.kind)).toEqual(['reach', 'origin']);
    expect(message.actions.at(-1).label).toBe('Or plan from home');
  });

  it('⚠️ always has a lens action to follow, so the way home always reads as the alternative', () => {
    // Both message kinds carry at least one lens action by construction — the reach ladder can
    // always reach "Any" while the scope holds anything, and the rating message always offers
    // "Show the week as it is". So the way home is never the first action, which is why
    // `homeAction(false)`'s wording is defensive rather than a state the page can show. Pinned here
    // so a future branch that CAN offer nothing else is caught by this file rather than by a reader.
    const reachAway = conflict({
      cards: [card([spot({ driveMinutes: 400 })], { limitMinutes: 150 })],
      origin,
      tierId: '150',
      limitMinutes: 150,
    });
    const ratingAway = conflict({
      cards: [card([spot({ rating: 2 })], { minRating: 4 })], origin, floorId: '4', minRating: 4,
    });
    expect(reachAway.actions[0].kind).not.toBe('origin');
    expect(ratingAway.actions[0].kind).not.toBe('origin');
    expect(reachAway.actions.at(-1).label).toBe('Or plan from home');
    expect(ratingAway.actions.at(-1).label).toBe('Or plan from home');
  });

  it('adds nothing at all at home', () => {
    const message = conflict({
      cards: [card([spot({ driveMinutes: 100 })], { limitMinutes: 45 })],
      tierId: '45',
      limitMinutes: 45,
    });
    expect(message.actions.every((a) => a.kind !== 'origin')).toBe(true);
  });
});
