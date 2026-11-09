import { formatDriveDuration } from './briefingDisplay.js';
import { REACH_TIERS, gateSpotsByReach } from './reachLens.js';
import { ANY_RATING_ID, LENS_RATING_FLOORS, ratingFloorById } from './ratingLens.js';

/**
 * What the whole plan says when the lens has shut it — page-level, shown as the verdict line on the lens
 * bar's readout (lens-verdict-line-plan.md). It was a callout card above the matrix until 2026-11-09.
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
function helpfulTier(cards, tierId, measured) {
  const from = REACH_TIERS.findIndex((tier) => tier.id === tierId);
  if (from < 0) return null;
  for (let i = from + 1; i < REACH_TIERS.length; i += 1) {
    const tier = REACH_TIERS[i];
    const helps = cards.some(
      (card) => gateSpotsByReach(card.allSpots || [], tier.limitMinutes, { measured }).length > 0,
    );
    if (helps) return tier;
  }
  return null;
}

/**
 * The plan's best reachable rating, the window it is in, and the place that carries it.
 *
 * <p>The FIGURE is the forecast and counts every pooled spot, outliers included. The NAME is a
 * recommendation, so it goes to the first spot (card order, pool order) that carries the figure and
 * is not a served {@code neighbourOutlier} (neighbour-guard-plan.md §3); {@code spot} is null when
 * only outliers carry it, and the window is then the first one that does.
 */
function ceilingOf(cards) {
  let best = null;
  for (const card of cards) {
    for (const spot of card?.pool || []) {
      if (spot?.rating == null) continue;
      if (best === null || spot.rating > best.rating) best = { rating: spot.rating, spot, card };
    }
  }
  if (best === null || best.spot.neighbourOutlier !== true) return best;
  for (const card of cards) {
    const named = (card?.pool || []).find(
      (spot) => spot?.rating === best.rating && spot.neighbourOutlier !== true,
    );
    if (named) return { rating: best.rating, spot: named, card };
  }
  return { rating: best.rating, spot: null, card: best.card };
}

/** `Tomorrow sunset 20:41` → the phrase a line can end on, lower-cased where it should be. */
function windowPhrase(card) {
  const label = [card?.kicker, card?.when].filter(Boolean).join(' ').toLowerCase();
  return [label, card?.time].filter(Boolean).join(' ');
}

/** Whether the plan's windows fall on more than one date — "this week" is only true then. */
function spansSeveralDates(cards) {
  const dates = new Set(cards.map((card) => card.date ?? String(card.key ?? '').split(':')[0]));
  return dates.size > 1;
}

/** A rating the way a screen reader should hear it: the glyph is not spoken by NVDA. */
function stars(rating) {
  return `${rating} ${rating === 1 ? 'star' : 'stars'}`;
}

/**
 * The one verdict the plan has, or null when it has none.
 *
 * <p>The result is {@code {id, line}}. {@code line} is what the lens bar prints: {@code lead} (the
 * amber "0"), {@code text} (the rest of the sentence, with {@code ★} glyphs and {@code ·}
 * separators the bar marks up for assistive tech), {@code action} (one {@code {kind, id, label}} or
 * null) and {@code announce} (one plain sentence for the live region, with ratings spelled in words).
 *
 * <p><b>There is no "plan from home" action.</b> {@code helpfulTier} can always walk to "Any" while
 * the scope holds anything, so a lens action always exists and the way home would only ever be an
 * alternative; the place picker at the top of the page is the route home.
 *
 * @param {object}  args
 * @param {Array}   args.cards        the window cards ({@code allSpots}, {@code pool},
 *                                    {@code spots}, {@code kicker}, {@code when}, {@code time})
 * @param {?object} [args.origin]     the away origin ({@code {name, baseName}}), or null for home
 * @param {string}  args.tierId       the active reach tier's id
 * @param {?number} args.limitMinutes that tier's threshold, null for "Any"
 * @param {boolean} [args.reachMeasured] the reader-level answer ({@code reachIsMeasured}, computed once in
 *                                    {@code WindowFirstBriefingContext}); defaults to asking the cards' spots,
 *                                    for a caller with no context
 * @param {string}  args.floorId      the active rating floor's id
 * @param {?number} args.minRating    that floor's threshold, null for "Any rating"
 * @returns {?{id: string, line: {lead: string, text: string, action: ?object, announce: string}}}
 */
export function buildPlanConflict({
  cards, origin = null, tierId, limitMinutes, floorId, minRating, reachMeasured: measuredArg,
}) {
  const list = (Array.isArray(cards) ? cards : []).filter(Boolean);
  if (list.length === 0) return null;

  const scope = scopeLocations(list);
  // Nothing anywhere in scope is not a lens conflict — there is no control that would help, and a
  // message offering one would be the banned no-op control. The matrix's own empty cells and the
  // per-card "nothing in reach" already say what is true.
  if (scope.length === 0) return null;

  const anyPool = list.some((card) => (card.pool || []).length > 0);
  /**
   * Whether the reach axis COULD have acted — is there a drive time anywhere in scope.
   *
   * <p>⚠️ §6 clause 7. A reader with no home postcode has none, and an unknown drive passes every
   * tier (plan §2.5), so the tier gated nothing and naming it in the line describes an act that did
   * not happen. The reach BRANCH cannot be reached in that state — an ungated pool is never empty —
   * but the floor-shut branch can, and it must not say "in reach" then. Asked of the whole scope
   * rather than of any one card's drawn set, for the reason `WindowSheetDialog` records.
   */
  const reachMeasured = measuredArg ?? scope.some((spot) => spot?.driveMinutes != null);
  const tierLabel = (REACH_TIERS.find((tier) => tier.id === tierId)?.label ?? null);

  if (!anyPool) {
    // Reachable only while the tier has a threshold: under "Any" nothing is gated, so an empty pool
    // would mean an empty scope, which the guard above has already returned on.
    if (limitMinutes == null || !tierLabel) return null;
    const nearest = nearestSpot(scope);
    const wider = helpfulTier(list, tierId, reachMeasured);
    const within = `within ${formatDriveDuration(limitMinutes)}`;
    let text = within;
    let announce = `Nothing ${within}.`;
    let action = null;
    if (wider) {
      action = {
        kind: 'reach',
        id: wider.id,
        label: wider.limitMinutes == null ? 'Show any distance' : `Widen to ${wider.label}`,
      };
      // The nearest drive is a fact about the roads. No measured drive anywhere is a state the gate
      // cannot produce (an unknown drive passes every tier), so then the line just stops at the tier.
      if (nearest) {
        const nearestIs = `${nearest.locationName}, ${formatDriveDuration(nearest.driveMinutes)}`;
        text = `${within} · nearest is ${nearestIs}`;
        announce = `${announce} Nearest is ${nearestIs}.`;
      }
    } else if (origin?.baseName) {
      // Defensive: with no wider tier the scope would be empty. Away, the line names where it is
      // measured from, the one row where nothing else on the page already says.
      text = `${within} of ${origin.baseName}`;
      announce = `Nothing ${within} of ${origin.baseName}.`;
    }
    return { id: 'reach', line: { lead: '0', text, action, announce } };
  }

  if (minRating == null) return null;
  const shut = list.every((card) => (card.spots || []).length === 0);
  if (!shut) return null;

  const floorLabel = ratingFloorById(floorId)?.label ?? null;
  if (!floorLabel) return null;
  const ceiling = ceilingOf(list);
  const week = spansSeveralDates(list);
  const floorSpoken = `${stars(minRating)}${floorLabel.endsWith('+') ? ' or better' : ''}`;
  const anyRating = { kind: 'rating', id: ANY_RATING_ID, label: 'Show any rating' };
  if (!ceiling) {
    // Not "none good enough" — nothing has been scored at all, which is the ordinary state on a
    // far-horizon plan and a different fact from a low ceiling.
    return {
      id: 'rating',
      line: {
        lead: '0',
        text: `at ${floorLabel} · nothing rated yet`,
        action: anyRating,
        announce: `Nothing at ${floorSpoken}. Nothing rated yet.`,
      },
    };
  }
  // The ceiling is read from the reach-gated pool, so a bare "best is" would overclaim where reach acted.
  const best = reachMeasured && limitMinutes != null ? 'best in reach is' : 'best is';
  // On a single-date plan "this week" is false, so the window itself is named instead.
  const place = ceiling.spot ? ceiling.spot.locationName : null;
  const tailText = (week ? [place] : [place, windowPhrase(ceiling.card)]).filter(Boolean).join(', ');
  const weekText = week ? ' this week' : '';
  // Only when the ceiling is itself a floor the bar can draw a pressed chip for. A plan topping out
  // at 2★ has no such step, and "drop to 2★+" would name a control that does not exist.
  const step = LENS_RATING_FLOORS.find((floor) => floor.min === ceiling.rating) ?? null;
  return {
    id: 'rating',
    line: {
      lead: '0',
      text: `at ${floorLabel}${weekText} · ${best} ${ceiling.rating}★${tailText ? `, ${tailText}` : ''}`,
      action: step ? { kind: 'rating', id: step.id, label: `Drop to ${step.label}` } : anyRating,
      announce: `Nothing at ${floorSpoken}${weekText}. ${best.charAt(0).toUpperCase()}${best.slice(1)} ${stars(ceiling.rating)}${tailText ? `, ${tailText}` : ''}.`,
    },
  };
}
