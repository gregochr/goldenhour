import { formatDriveDuration } from './briefingDisplay.js';
import { REACH_TIERS, gateSpotsByReach } from './reachLens.js';
import { ANY_RATING_ID, LENS_RATING_FLOORS, ratingFloorById } from './ratingLens.js';

/**
 * What the whole plan says when the lens has shut it — page-level, above the matrix.
 *
 * <h2>Why this is page-level and the card's own line was not</h2>
 *
 * <p>Until M2 each window card carried its own {@code lensEmpty} ladder: a headline naming the
 * thresholds, a body naming the population beyond them, and one button per axis that had been
 * tested to actually refill <em>that</em> card. With the six-row list deleted (plan-matrix §7) there
 * is nowhere to put six of those, and six copies of one sentence was never the right shape anyway —
 * a reach tier that empties one window has usually emptied all six, so the statement is about the
 * plan. The design says so directly: the conflict messages "sit above the strip, so they're about
 * the whole plan rather than one window".
 *
 * <p>The per-<em>window</em> half of that job did not disappear with the ladder — it moved into the
 * popup's quiet sentence, which is the only place a single window (or a region focus inside one) can
 * be empty while the plan as a whole is not. The two replacements land together, which is what makes
 * the deletion safe: neither alone covers what the ladder covered.
 *
 * <h2>At most one message, and the order is causal rather than aesthetic</h2>
 *
 * <p>Reach is asked first because it is upstream: if nothing at all is within the chosen drive then
 * the rating floor has had nothing to reject, and a message blaming the floor would point the reader
 * at the control that is not the cause. The design's prototype makes the same call with an
 * {@code else if}, and the two rules are mutually exclusive by construction anyway — rule 2 requires
 * a non-empty pool, which is exactly what rule 1 requires the absence of.
 *
 * <h2>Every action is tested against the data before it is offered</h2>
 *
 * <p>Plan §3 rule 14 bans a control with no visible effect, and the naive "widen one step" is
 * exactly that: from 45 minutes the next step is 1h 30min, and a plan whose nearest location is two
 * hours out would offer a button that moves the lens and changes nothing on screen. So the reach
 * ladder is <b>walked</b> until a tier is found that would put something in some window's pool, and
 * the rating action names the measured ceiling rather than the next step down. This is the discipline
 * {@code windowLensEmpty.js} established per card, kept and moved rather than dropped with it.
 *
 * <h2>What the counts are allowed to be</h2>
 *
 * <p>Plan §3 rule 5 bans counting our own data as a fact about the sky. These counts are not that:
 * "N locations in your regions" is the same statement {@code formatLensReadout} already makes on the
 * bar a few pixels above — places you could go — and the nearest drive is a fact about the roads.
 * Neither says anything about how many were <em>scored</em>.
 */

/** The population a message is about: every distinct location the scope holds, across all windows. */
function scopeLocations(cards) {
  const byId = new Map();
  for (const card of cards) {
    for (const spot of card?.allSpots || []) {
      if (!spot) continue;
      // Id-first, name-fallback — the join policy `heatSpots.js` sets for this arm. A roster with
      // two locations of one name would otherwise count as one and under-report the scope.
      const key = spot.locationId ?? spot.locationName;
      if (key != null && !byId.has(key)) byId.set(key, spot);
    }
  }
  return [...byId.values()];
}

/** The shortest measured drive in a set, or null when nothing in it carries one. */
function nearestSpot(spots) {
  let nearest = null;
  for (const spot of spots) {
    const drive = spot.driveMinutes;
    if (typeof drive !== 'number' || !Number.isFinite(drive)) continue;
    if (nearest === null || drive < nearest.driveMinutes) nearest = spot;
  }
  return nearest;
}

/**
 * The first tier looser than the current one that would put something in some window's pool.
 *
 * @param {Array}   cards        the window cards
 * @param {string}  tierId       the active tier's id
 * @returns {?object} the tier, or null when no widening helps
 */
function helpfulTier(cards, tierId) {
  const from = REACH_TIERS.findIndex((tier) => tier.id === tierId);
  if (from < 0) return null;
  for (let i = from + 1; i < REACH_TIERS.length; i += 1) {
    const tier = REACH_TIERS[i];
    const helps = cards.some(
      (card) => gateSpotsByReach(card.allSpots || [], tier.limitMinutes).length > 0,
    );
    if (helps) return tier;
  }
  return null;
}

/** The window this plan's best reachable rating is in, and the spot that carries it. */
function ceilingOf(cards) {
  let best = null;
  for (const card of cards) {
    for (const spot of card?.pool || []) {
      if (spot?.rating == null) continue;
      if (best === null || spot.rating > best.rating) best = { rating: spot.rating, spot, card };
    }
  }
  return best;
}

/** `Tomorrow sunset 20:41` → the phrase a body line can end on, lower-cased where it should be. */
function windowPhrase(card) {
  const label = [card?.kicker, card?.when].filter(Boolean).join(' ').toLowerCase();
  return [label, card?.time].filter(Boolean).join(' ');
}

/** The scope's own name, for a sentence that has to say where. */
function scopeName(origin) {
  return origin ? origin.name : 'your regions';
}

/**
 * The way back to the home origin, appended to whichever message fires.
 *
 * <p>Away, the scope is something the reader <em>chose</em>, so it is one of the things that can be
 * wrong — and unlike the two lens axes it is not on the bar. The same action, wording and ordering
 * rule {@code windowLensEmpty.js} used per card: it goes last, because widening the lens keeps the
 * reader where they asked to be and going home abandons it.
 *
 * <p><b>The {@code false} arm is defensive and, as the two branches stand, unreachable.</b> Both
 * messages carry at least one lens action by construction — the reach ladder can always walk to
 * "Any" while the scope holds anything, and the rating message always offers "Show the week as it
 * is" — so this is never the first action. It stays because it is one ternary between correct
 * wording and an orphan "Or", and {@code planConflicts.test.js} pins the invariant that makes it
 * unreachable rather than the branch itself.
 */
function homeAction(hasOthers) {
  return { kind: 'origin', id: 'home', label: hasOthers ? 'Or plan from home' : 'Plan from home' };
}

/**
 * The one conflict message the plan has, or null when it has none.
 *
 * @param {object}  args
 * @param {Array}   args.cards        the window cards ({@code allSpots}, {@code pool},
 *                                    {@code spots}, {@code kicker}, {@code when}, {@code time})
 * @param {?object} [args.origin]     the away origin ({@code {name, baseName}}), or null for home
 * @param {?string} [args.homePlace]  the reader's own place name, for the home sentence's "of X"
 * @param {string}  args.tierId       the active reach tier's id
 * @param {?number} args.limitMinutes that tier's threshold, null for "Any"
 * @param {string}  args.floorId      the active rating floor's id
 * @param {?number} args.minRating    that floor's threshold, null for "Any rating"
 * @returns {?{id: string, headline: string, body: string, actions: Array}} the message
 */
export function buildPlanConflict({
  cards, origin = null, homePlace = null, tierId, limitMinutes, floorId, minRating,
}) {
  const list = (Array.isArray(cards) ? cards : []).filter(Boolean);
  if (list.length === 0) return null;

  const scope = scopeLocations(list);
  // Nothing anywhere in scope is not a lens conflict — there is no control that would help, and a
  // message offering one would be the banned no-op control. The matrix's own empty cells and the
  // per-card "nothing in reach" already say what is true.
  if (scope.length === 0) return null;

  const anyPool = list.some((card) => (card.pool || []).length > 0);
  const tierLabel = REACH_TIERS.find((tier) => tier.id === tierId)?.label ?? null;

  if (!anyPool) {
    // Reachable only while the tier has a threshold: under "Any" nothing is gated, so an empty pool
    // would mean an empty scope, which the guard above has already returned on.
    if (limitMinutes == null || !tierLabel) return null;
    const from = origin ? origin.baseName : homePlace;
    const nearest = nearestSpot(scope);
    const wider = helpfulTier(list, tierId);
    const actions = [];
    if (wider) {
      actions.push({ kind: 'reach', id: wider.id, label: `Widen to ${wider.label.toLowerCase()}` });
    }
    if (origin) actions.push(homeAction(actions.length > 0));
    return {
      id: 'reach',
      // Named only where the origin or the settings answer has one. "Nothing within 45 min of
      // undefined" is worse than the bare threshold, and a reader with no postcode saved has no
      // place for the sentence to name.
      headline: from
        ? `Nothing within ${tierLabel.toLowerCase()} of ${from}.`
        : `Nothing within ${tierLabel.toLowerCase()}.`,
      body: nearest
        ? `${scope.length} location${scope.length === 1 ? '' : 's'} in ${scopeName(origin)}, and the closest is ${nearest.locationName} at ${formatDriveDuration(nearest.driveMinutes)}.`
        // No measured drive anywhere is a state the gate cannot produce (an unknown drive passes
        // every tier), so this is the shorter true sentence rather than a guess at a distance.
        : `${scope.length} location${scope.length === 1 ? '' : 's'} in ${scopeName(origin)}.`,
      actions,
    };
  }

  if (minRating == null) return null;
  const shut = list.every((card) => (card.spots || []).length === 0);
  if (!shut) return null;

  const floorLabel = ratingFloorById(floorId)?.label ?? null;
  if (!floorLabel) return null;
  const ceiling = ceilingOf(list);
  const actions = [
    { kind: 'rating', id: ANY_RATING_ID, label: 'Show the week as it is' },
  ];
  // Only when the ceiling is itself a floor the bar can draw a pressed chip for. A plan topping out
  // at 2★ has no such step, and offering "drop the floor to 2★+" would name a control that does not
  // exist; "Show the week as it is" is then the whole of the offer, which is the true one.
  const step = ceiling == null
    ? null
    : LENS_RATING_FLOORS.find((floor) => floor.min === ceiling.rating) ?? null;
  if (step) {
    actions.push({ kind: 'rating', id: step.id, label: `Or drop the floor to ${step.label}` });
  }
  if (origin) actions.push(homeAction(true));
  return {
    id: 'rating',
    headline: `Nothing at ${floorLabel} anywhere in ${scopeName(origin)} this week.`,
    body: ceiling
      ? `The best on offer${tierLabel && limitMinutes != null ? ` within ${tierLabel.toLowerCase()}` : ''} is ${ceiling.rating}★ — ${ceiling.spot.locationName}, ${windowPhrase(ceiling.card)}.`
      // Not "none good enough" — nothing in reach has been scored at all, which is the ordinary
      // state on a far-horizon plan and a different fact from a low ceiling. The distinction
      // `windowLensEmpty.js` drew per card, kept here.
      : 'Nothing within reach has been rated yet.',
    actions,
  };
}
