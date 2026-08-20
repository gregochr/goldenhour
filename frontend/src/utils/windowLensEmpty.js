import { formatDriveDuration } from './briefingDisplay.js';
import { REACH_TIERS, gateSpotsByReach, tierById } from './reachLens.js';
import { LENS_RATING_FLOORS, gateSpotsByRating, ratingFloorById } from './ratingLens.js';

/**
 * What a window says when the page-wide lens has left it with nothing — and the one tap out.
 *
 * <h2>Why the way out is derived rather than written</h2>
 *
 * <p>The handoff's empty card names "the next reach step up (45 → 90 → 150 → any)". Taken
 * literally that is a control which can do nothing: from 45 min, the next step is 1h 30min, and a
 * window whose nearest qualifying spot is two hours away would offer a button that widens the lens
 * and leaves the card exactly as empty — the demo control §6 bans, arriving by a slightly longer
 * route. So each candidate is tested by <b>re-running both gates with that one control loosened</b>
 * and the ladder is walked until one passes. The button then names a destination that works.
 *
 * <p>That derivation is also the whole of the LITE story, with no role anywhere near it: a LITE
 * reader is pinned to "Any" reach, so there is no wider tier and the reach action is never offered.
 * It is the same shape {@code browseEmptyLine} already uses in the drill-down, for the same stated
 * reason — a user with no drive times at all has a reach tier that gated nothing, and telling them
 * to widen it sends them to the one control that cannot help.
 *
 * <h2>The sentence names the population, because "nothing" reads as a bad forecast</h2>
 *
 * <p>Plan §5a's existing line already made this point for reach alone: an empty card that says only
 * "nothing" reads as a bad night rather than a tight filter, so it states how many are beyond the
 * threshold. With two axes there are three different things that can be true, and they are not
 * interchangeable — spots that clear the floor but sit further out, spots within reach that no
 * amount of driving will make good enough, and a window nothing has scored yet. The third is the one
 * worth splitting out: at T+3 and beyond the batch has usually rated nothing, and "none rated 4★+"
 * over a window that was never looked at says the forecast was poor when in fact there is no
 * forecast. That distinction is the same one {@code AWAITING} exists to draw on the verdict badge.
 *
 * <h2>Away, the origin is part of the explanation and the way back is part of the offer</h2>
 *
 * <p>Plan §4.8's two clash states — nothing within reach of the base, and a rating floor set at
 * home that empties an away region — are lens-empty states, so they extend this module rather than
 * introducing a second empty card. Two things change when {@code origin} is set, and only two.
 *
 * <p>First, an <b>empty scope now speaks</b>. At home a window with no spots at all returns null,
 * because a card the lens never touched must not carry a line about the lens. Away that same state
 * is reachable by <em>choosing</em> — the reader has just pointed the page at a region that has
 * nothing in this window — and silence there is a dead end: the card would render nothing at all
 * with no route back. So the away arm names the region and offers the way home.
 *
 * <p>Second, every away empty state carries <b>one extra action</b>: return to the home origin. It
 * is appended after the lens actions rather than put first, because widening the lens keeps the
 * reader where they asked to be and going home abandons it — the offer that preserves the reader's
 * intent goes first. It is offered even when a lens step would also fill the card, because the two
 * answer different questions ("show me more here" against "this region is not the one").
 */

/** `1 spot` / `4 spots`, so a sentence never reads "1 spots". */
function spotCount(n) {
  return `${n} spot${n === 1 ? '' : 's'}`;
}

/**
 * The nearest drive among a set, or null when none of them carries one.
 *
 * <p><b>The null branch is defensive and, as the gates stand, unreachable — which is worth saying
 * out loud rather than testing.</b> Its only caller runs on spots that clear the floor while the
 * window is empty, and the only thing that can have removed them is the reach gate; that gate lets
 * an unknown drive time through every tier (plan §2.5 rule 1), so every spot it removed carries a
 * known one. The guard stays because it is the difference between a shorter sentence and
 * "the nearest is undefined away" if that rule ever changes, and it costs one comparison.
 */
function nearestDrive(spots) {
  const known = spots.map((spot) => spot.driveMinutes).filter((m) => m != null);
  return known.length === 0 ? null : Math.min(...known);
}

/**
 * The way back to the home origin.
 *
 * <p>Its own kind rather than a third lens ladder, because it is not a threshold: the shell reads
 * {@code kind} and moves the origin, where the other two move the bar. One builder so the label
 * and the kind cannot drift apart across the two sites that emit it.
 */
function homeAction(hasOthers) {
  // "Or" only when it is not the first, so a lone action does not read as an afterthought — the
  // same rule the rating action already follows one function down.
  return {
    kind: 'origin',
    id: 'home',
    label: hasOthers ? 'Or plan from home' : 'Plan from home',
  };
}

/** Both gates, in the order the card applies them. */
function passing(spots, limitMinutes, minRating) {
  return gateSpotsByRating(gateSpotsByReach(spots, limitMinutes), minRating);
}

/**
 * The first step along a ladder that actually puts something on screen.
 *
 * @param {Array}    ladder   the options, loosest last for reach and loosest first for rating
 * @param {number}   from     the active option's index in that ladder
 * @param {number}   step     +1 to loosen a reach ladder, -1 to loosen a rating one
 * @param {Function} passes   given a candidate option, whether the window would be non-empty
 * @returns {?object} the option, or null when no step along the ladder helps
 */
function firstHelpfulStep(ladder, from, step, passes) {
  if (from < 0) return null;
  for (let i = from + step; i >= 0 && i < ladder.length; i += step) {
    if (passes(ladder[i])) return ladder[i];
  }
  return null;
}

/**
 * What the card shows in place of its spot strip, or null when it should show neither.
 *
 * <p>Null in two cases that look alike and are not: the window still has spots to draw, and the
 * window had none to begin with. The second is why this is not simply {@code spots.length === 0} —
 * a card the lens never touched must not carry a line about the lens.
 *
 * @param {object}  args
 * @param {Array}   args.allSpots     the window's spots before either gate
 * @param {Array}   args.spots        the spots as drawn, after both
 * @param {string}  args.tierId       the active reach tier's id
 * @param {?number} args.limitMinutes that tier's threshold, null for "Any"
 * @param {string}  args.floorId      the active rating floor's id
 * @param {?number} args.minRating    that floor's threshold, null for "Any rating"
 * @param {?object} [args.origin]     the origin descriptor ({@code {name, baseName}}), or null for
 *                                    home. Away it adds the return-home action and lets an empty
 *                                    scope speak — see the module comment.
 * @returns {?{headline: string, body: string, actions: Array}} the descriptor
 */
export function buildLensEmptyState({
  allSpots, spots, tierId, limitMinutes, floorId, minRating, origin = null,
}) {
  const all = allSpots || [];
  if ((spots || []).length > 0) return null;
  if (all.length === 0) {
    if (!origin) return null;
    // The reader chose this scope, so the card owes them a sentence and a way back.
    return {
      headline: `Nothing in ${origin.name} for this window.`,
      body: `You are planning from ${origin.baseName}.`,
      actions: [homeAction(false)],
    };
  }

  const reachLabel = tierById(tierId)?.label ?? null;
  const ratingLabel = minRating == null ? null : ratingFloorById(floorId)?.label ?? null;
  const gatedByReach = limitMinutes != null;

  // Headline — it names only the thresholds that are doing something, so a bar sitting on "Any"
  // never appears in a sentence about why a window is empty.
  const clauses = [];
  if (ratingLabel) clauses.push(`at ${ratingLabel}`);
  if (gatedByReach && reachLabel) clauses.push(`within ${reachLabel}`);
  const headline = clauses.length > 0
    ? `Nothing ${clauses.join(' ')} in this window.`
    : 'Nothing in this window.';

  // The three populations the body can be about. `withinReach` is what the floor acted on;
  // `clearingFloor` is what widening would reveal. Both are empty of anything drawn, by precondition.
  const withinReach = gateSpotsByReach(all, limitMinutes);
  const clearingFloor = gateSpotsByRating(all, minRating);

  let body;
  if (minRating == null) {
    // Reach alone — plan §5a's own sentence, unchanged, so the state that has shipped since P8 reads
    // exactly as it did.
    body = `${spotCount(all.length)} ${all.length === 1 ? 'is' : 'are'} further out.`;
  } else if (clearingFloor.length > 0) {
    const nearest = nearestDrive(clearingFloor);
    const tail = nearest == null ? '.' : ` — the nearest is ${formatDriveDuration(nearest)} away.`;
    body = `${spotCount(clearingFloor.length)} here ${clearingFloor.length === 1 ? 'is' : 'are'} rated ${ratingLabel}${tail}`;
  } else if (all.every((spot) => spot.rating == null)) {
    // Not "none good enough" — nothing here has been scored at all, which is a different fact and
    // the one a reader four days out is most often looking at.
    body = 'Nothing in this window has been rated yet.';
  } else if (withinReach.length > 0) {
    body = `${spotCount(withinReach.length)} here, none rated ${ratingLabel}.`;
  } else {
    body = `${spotCount(all.length)} here ${all.length === 1 ? 'is' : 'are'} further out, and none is rated ${ratingLabel}.`;
  }

  // Each action is the nearest step along its own ladder that would actually fill the card.
  const actions = [];
  const wider = firstHelpfulStep(
    REACH_TIERS,
    REACH_TIERS.findIndex((tier) => tier.id === tierId),
    1,
    (tier) => passing(all, tier.limitMinutes, minRating).length > 0,
  );
  if (wider) actions.push({ kind: 'reach', id: wider.id, label: `Try ${wider.label}` });

  const looser = firstHelpfulStep(
    LENS_RATING_FLOORS,
    LENS_RATING_FLOORS.findIndex((floor) => floor.id === floorId),
    -1,
    (floor) => passing(all, limitMinutes, floor.min).length > 0,
  );
  if (looser) {
    // "Or" only when it is the second of the two, so a lone action does not read as an afterthought.
    const verb = actions.length > 0 ? 'Or drop to' : 'Drop to';
    actions.push({ kind: 'rating', id: looser.id, label: `${verb} ${looser.label.toLowerCase()}` });
  }

  if (origin) actions.push(homeAction(actions.length > 0));

  return { headline, body, actions };
}
