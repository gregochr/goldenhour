import { SKY_SUBJECT_TYPES, locationTypeLabel } from './locationTypes.js';
import { gateSpotsByReach } from './reachLens.js';
import { compareSpots } from './windowFirstSpots.js';

/**
 * The drill-down's browsing filters — the rating floor, the location type, and what they may say.
 *
 * <h2>Why these two live here and not on the lens bar</h2>
 *
 * <p>Plan §5c`:908` assumed P11 would add both to the sticky bar and warned that
 * {@code scroll-margin-top} would have to be re-measured. The mock puts them in the sheet and argues
 * for it, and the mock is right for a reason neither says: <b>they have a different scope</b>. Reach
 * is a fact about today that applies to every window on the page, which is why one control governs
 * six cards. A rating floor and a type are about the list you are reading <em>now</em> — and a
 * page-wide rating floor would collide head-on with §5c's rule that a window header's
 * {@code best N★} is never re-derived from the gated set, leaving a card reading "best 5★" over a
 * strip its own control had emptied of everything under 4. Recorded in plan §5f.
 *
 * <h2>A lens is not a gate when it has no data — extended to both new axes</h2>
 *
 * <p>Plan §2.5 rule 1 states it for reach: a spot with no drive time is <em>unknown</em>, not out of
 * reach. The same rule, applied to a control rather than to a row, is what makes these two safe:
 *
 * <ul>
 *   <li><b>The rating control is offered only when something is rated</b>, and when it is not
 *       offered the floor does not run. Without that pairing the persisted floor is a trap: a floor
 *       of 4★ stored from a scored evening would silently empty an unevaluated window the next
 *       morning, with no control on screen to explain it or take it back. The two halves only work
 *       together — see {@link browseSpots}, where the same predicate gates both.</li>
 *   <li><b>The type control is offered only when there is a choice to make</b> — two or more
 *       distinct types among the window's own spots. One type is not a filter, it is a label, and a
 *       segmented control with one real option is the demo control §6 bans. Deriving the options
 *       from the population rather than from the enum also means no chip can ever be offered that
 *       matches nothing.</li>
 * </ul>
 *
 * <p>An unrated spot <em>fails</em> a floor, which is the opposite of the reach rule and
 * deliberately so: "4★ and up" is a claim about what was measured, and an unknown does not meet it.
 * {@code CloseToHome.keepCard} already resolves it the same way. It is written as an explicit null
 * test rather than {@code (rating ?? 0) < min}, because the coercion that reads harmlessly in a
 * filter is the one P10′'s review caught rendering {@code ☆☆☆☆☆} for an unrated spot.
 */

/** The tier that floors nothing. Its own id, so a stored value can name it. */
export const ANY_RATING_ID = 'any';

/** The type option that filters nothing. */
export const ANY_TYPE_ID = 'any';

/** The projector's own ceiling. A floor at the ceiling is "exactly this", not "this and up". */
const MAX_RATING = 5;

/**
 * The rating floors, loosest first.
 *
 * <p>Whole stars, because {@code bestRating} and {@code claudeRating} are integers in 1–5 and a 3.5
 * floor could only ever behave as 4 — the reasoning {@code CloseToHome}'s {@code RATING_STEPS}
 * already records, and the same ladder (any → 3 → 4 → 5), so a reader who has used the local block
 * meets one vocabulary rather than two.
 *
 * <p>The top step reads {@code 5★} and not {@code 5★+}: there is no sixth star, and a {@code +} on
 * the ceiling asserts a range that does not exist.
 */
export const RATING_FLOORS = [{ id: ANY_RATING_ID, min: null, label: 'Any rating' }].concat(
  [3, 4, MAX_RATING].map((min) => ({
    id: String(min),
    min,
    label: min === MAX_RATING ? `${min}★` : `${min}★+`,
  })),
);

/** The floor for an id, or null when nothing matches. */
export function ratingFloorById(id) {
  return RATING_FLOORS.find((floor) => floor.id === id) || null;
}

/**
 * Storage key for the rating floor, and <b>for nothing else</b>.
 *
 * <p>Its own key rather than a field on {@code photocast.planReach}, which is the shape plan
 * §5c`:866-876` rejected when it split them: reach expires at the day roll and this does not, so one
 * object would make the expiry policy a naming convention where two keys make it structural. The
 * write is a whole value that never reads storage back — CodeQL models {@code localStorage} as one
 * store with no notion of keys, so a {@code getItem} feeding a {@code setItem} is a conduit from
 * every other write in the app.
 *
 * <p><b>No day stamp, and that is the difference from reach.</b> "How far will I drive tonight" is a
 * judgement about one evening; "I only care about 4★ and up" is taste, and re-asking it every
 * morning would be the same nagging a per-day reach avoids in the other direction.
 */
export const PLAN_RATING_KEY = 'photocast.planRating';

/**
 * The stored rating floor id, or null when there is none this build still has.
 *
 * <p>Null covers no key, unparseable JSON, a missing field and an id this build no longer offers.
 * All four mean the same thing to the caller: no floor.
 *
 * @returns {?string} a floor id, or null
 */
export function readStoredRatingFloorId() {
  try {
    const raw = localStorage.getItem(PLAN_RATING_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return ratingFloorById(parsed?.rating) ? parsed.rating : null;
  } catch {
    return null;
  }
}

/**
 * Records a rating floor. A whole-value write of the one field this module owns.
 *
 * @param {string} floorId the chosen floor, already validated by the caller
 */
export function writeStoredRatingFloorId(floorId) {
  try {
    localStorage.setItem(PLAN_RATING_KEY, JSON.stringify({ rating: floorId }));
  } catch {
    // Quota exceeded or private-browsing restriction — the choice still applies for this session
  }
}

/**
 * The types a spot's location carries that this sheet may name, in display order.
 *
 * <p>Joined by <b>name</b>, because that is the only key both sides carry: the briefing slot has a
 * location id and the enabled-locations list has both, but the roster this arm receives is keyed
 * for display. A name that misses yields no types, and a spot with no types is <b>offered by no
 * chip and kept only by "Any"</b> — an unknown kind of place does not match "Seascape", for the
 * same reason an unrated spot does not meet a rating floor. It is never hidden while the control
 * is absent, because a control that is not offered does not gate (see {@link browseSpots}).
 *
 * <p><b>{@code SKY_SUBJECT_TYPES}, not {@code DISPLAY_TYPES} — the difference is WOODLAND and it is
 * load-bearing.</b> This sheet reads the strip's population, which drops canopy slots
 * ({@code windowFirstSpots.js}), and the backend flags those via
 * {@code LocationEntity.isWoodlandOnly()} — WOODLAND <em>and no</em> LANDSCAPE/SEASCAPE/WATERFALL.
 * So a wood with an open aspect (Allen Banks: LANDSCAPE + BLUEBELL + WOODLAND) is in this list and
 * would otherwise be offered a "Woodland" chip — a complete-sounding word over a set the enclosed
 * woods had already been removed from, with nothing on screen saying so. That is the same
 * over-claim as a count describing a set nothing filtered, which §6 bans. {@code SKY_SUBJECT_TYPES}
 * is exactly the right set and already exists with the reasoning written down: "types whose subject
 * is the sky … WOODLAND is deliberately ABSENT: a location under a canopy has no sky to forecast."
 * Allen Banks then appears under <em>Landscape</em>, which is true of it and is why it is here at
 * all. BLUEBELL is absent from both sets anyway, for the reason {@code locationTypes.js} records.
 *
 * @param {object} spot        a descriptor from {@code buildWindowSpots}
 * @param {?Map<string, string[]>} typesByName location name → its {@code locationType} array
 * @returns {string[]} enum names, ordered
 */
export function spotTypes(spot, typesByName) {
  const raw = typesByName?.get(spot.locationName);
  const list = Array.isArray(raw) ? raw : (raw == null ? [] : [raw]);
  return SKY_SUBJECT_TYPES.filter((type) => list.includes(type));
}

/**
 * The type chips this window can honestly offer, or an empty list when it can offer none.
 *
 * <p>Derived from the spots rather than from the enum — see the module comment. Fewer than two
 * distinct types means there is no choice to make, and the caller renders no control at all.
 *
 * @param {Array} spots the window's full spot list, before any filter
 * @param {?Map<string, string[]>} typesByName location name → its {@code locationType} array
 * @returns {Array<{id: string, label: string}>} `Any` first, then each present type, or `[]`
 */
export function typeOptionsFor(spots, typesByName) {
  const present = new Set();
  for (const spot of spots) {
    for (const type of spotTypes(spot, typesByName)) present.add(type);
  }
  if (present.size < 2) return [];
  // This second pass ORDERS; it does not filter. `spotTypes` is the only guard, and `present` is
  // already restricted to what it returns — a mutation sweep proved the point by swapping this list
  // for the full enum and killing nothing. Said out loud so the next reader does not take it for a
  // second gate and tighten one of the two while leaving the other.
  return [{ id: ANY_TYPE_ID, label: 'Any type' }].concat(
    SKY_SUBJECT_TYPES.filter((type) => present.has(type))
      .map((type) => ({ id: type, label: locationTypeLabel(type) })),
  );
}

/**
 * Whether this window has anything for a rating floor to act on.
 *
 * <p>The control's own render condition, and the gate's — they must be one predicate, or a stored
 * floor applies with no control on screen to undo it. See the module comment.
 *
 * @param {Array} spots the window's full spot list
 * @returns {boolean} true when at least one spot is rated
 */
export function hasRatings(spots) {
  return spots.some((spot) => spot.rating != null);
}

/**
 * The spots left after all three browsing filters, ordered.
 *
 * <p><b>One function, so the list and every number describing it cannot disagree.</b> §6 requires a
 * footer's claimed count to match what is rendered, and the only way to guarantee that is for the
 * count to be the length of the array that was drawn. The order is {@link compareSpots} — exported
 * at P6 for exactly this, so the strip and the sheet can never rank the same spots differently.
 *
 * @param {object} args
 * @param {Array}  args.spots       the window's full spot list, ungated
 * @param {?number} args.limitMinutes the sheet's own reach threshold, null for "Any"
 * @param {string} args.ratingFloorId the chosen floor's id
 * @param {string} args.typeId       the chosen type's id
 * @param {?Map<string, string[]>} args.typesByName location name → its {@code locationType} array
 * @returns {Array} the spots to draw
 */
export function browseSpots({ spots, limitMinutes, ratingFloorId, typeId, typesByName }) {
  let kept = gateSpotsByReach(spots, limitMinutes);

  // Paired with the control's own render condition on purpose — a floor that runs while its chip is
  // absent is a filter the reader cannot see, cannot explain and cannot take back.
  const floor = hasRatings(spots) ? ratingFloorById(ratingFloorId)?.min ?? null : null;
  if (floor != null) kept = kept.filter((spot) => spot.rating != null && spot.rating >= floor);

  // Same pairing: `typeOptionsFor` returns nothing when there is no choice, and then nothing gates.
  const typeOffered = typeId !== ANY_TYPE_ID
    && typeOptionsFor(spots, typesByName).some((option) => option.id === typeId);
  if (typeOffered) kept = kept.filter((spot) => spotTypes(spot, typesByName).includes(typeId));

  return kept.slice().sort(compareSpots);
}

/**
 * How many spot cards the film strip shows without scrolling.
 *
 * <p>{@code .wf-spots > .wf-spot} is {@code flex: 0 0 calc((100% - 24px) / 3.5)}, so the fourth
 * card is the first one partly off-screen. Phone sizes at 72%, which is fewer still — so three is
 * the ceiling on what a reader can already see, on every breakpoint.
 */
const SPOTS_VISIBLE_AT_ONCE = 3;

/**
 * Whether the drill-down could show this window's reader anything the strip does not.
 *
 * <p>The trigger is drawn only where this is true, which is the rule the {@code ‹ ›} arrows already
 * hold on the same footer and for the same stated reason: a control that is present but cannot
 * change anything "reads as broken, where their absence reads as 'that is all of them'". A dialog
 * that opens onto the identical three cards the reader is already looking at is the demo control
 * §6 bans, arriving by a slightly longer route.
 *
 * <p>Four ways it can be true, and each is a real thing the sheet does:
 *
 * <ul>
 *   <li>the reach lens withheld some — the sheet's own tier can bring them back;</li>
 *   <li>more spots than fit the strip at once — the grid shows them together and ranked;</li>
 *   <li>something is rated — the floor can act;</li>
 *   <li>two or more types are present — the type control can act.</li>
 * </ul>
 *
 * <p>All four are computed from data rather than from layout, deliberately: {@code overflows} is
 * measured from {@code scrollWidth}, which jsdom cannot produce, so a rule keyed on it would be one
 * no test could reach.
 *
 * @param {object} card the window card descriptor
 * @param {?Map<string, string[]>} typesByName location name → its {@code locationType} array
 * @returns {boolean} true when the sheet is worth offering
 */
export function sheetOffersMore(card, typesByName) {
  const all = card?.allSpots || [];
  if (all.length === 0) return false;
  return all.length > (card.spots?.length ?? 0)
    || all.length > SPOTS_VISIBLE_AT_ONCE
    || hasRatings(all)
    || typeOptionsFor(all, typesByName).length > 0;
}

/**
 * What the empty list may honestly suggest — naming a control only where relaxing <em>it alone</em>
 * would put something on screen.
 *
 * <p>The design's line is "Nothing matches — widen the reach or drop the rating floor", which names
 * two controls unconditionally. Derived is better, but "is this control narrower than its loosest
 * setting" is not enough either, and the failure is concrete: a user with no drive times at all —
 * the normal first-run state — has a reach tier that gated nothing, so telling them to widen it
 * sends them to the one control that cannot help. §2.5 rule 2 makes the same point about counts,
 * that the word <em>reach</em> belongs to a set that was actually gated.
 *
 * <p>So each candidate is tested by <b>re-running the filters with that one control loosened</b>.
 * Three extra passes over a list bounded by a window's roster, for a sentence that then names only
 * what would work — and when two of them would each work alone, it says so, which is more useful
 * than a list of everything currently set.
 *
 * <p>Naming nothing is reachable and is <em>not</em> a contradiction: the window's own spot list can
 * be empty, or every control can be loose already. The sentence then states that rather than
 * suggesting a control.
 *
 * @param {object} args the same arguments {@link browseSpots} was called with
 * @returns {string} the sentence
 */
export function browseEmptyLine(args) {
  const wouldHelp = (override) => browseSpots({ ...args, ...override }).length > 0;
  const suggestions = [];
  if (args.limitMinutes != null && wouldHelp({ limitMinutes: null })) {
    suggestions.push('widen the reach');
  }
  if (args.ratingFloorId !== ANY_RATING_ID && wouldHelp({ ratingFloorId: ANY_RATING_ID })) {
    suggestions.push('drop the rating floor');
  }
  if (args.typeId !== ANY_TYPE_ID && wouldHelp({ typeId: ANY_TYPE_ID })) {
    suggestions.push('clear the type');
  }
  if (suggestions.length === 0) return 'No spots in this window.';
  const last = suggestions.pop();
  const joined = suggestions.length === 0 ? last : `${suggestions.join(', ')} or ${last}`;
  return `Nothing matches — ${joined}.`;
}

/**
 * How many are drawn, and how many the controls could bring back.
 *
 * <p>The second number appears only when it means something, which is the rule the strip footer
 * already holds: with nothing filtered, {@code N of N} is a count dressed as a comparison. The
 * design's word "loaded" is dropped for the reason it was dropped there — nothing here is lazily
 * fetched, and every spot in {@code total} was in hand before any filter ran.
 *
 * @param {number} visible spots drawn
 * @param {number} total   spots the filters chose from
 * @returns {string} the count
 */
export function browseCountLine(visible, total) {
  if (visible >= total) return `${visible} spot${visible === 1 ? '' : 's'}`;
  return `${visible} of ${total}`;
}
