/**
 * The rating lens — the lowest star the reader still wants to hear about.
 *
 * <h2>It moved onto the bar, and plan §5f said not to. Here is what changed</h2>
 *
 * <p>P11 put the rating floor in the drill-down sheet and argued it on <b>scope</b>: reach is a
 * judgement about today that governs six windows, where a floor is "about the list you are reading
 * now". The design handoff that this module implements overrules that — the floor is a global lens
 * beside reach — and the one concrete hazard §5f raised turns out not to fire. Its words were: "a
 * global 4★ floor would leave a card reading {@code best 5★} over a strip its own control had
 * emptied of everything below 4". <b>A floor cannot remove the best-rated spot.</b> If the window's
 * best clears the floor it is still drawn; if it does not, the strip is empty and the card says so
 * in a line that names the floor. The collision was with a filter that removes from the <em>top</em>
 * — which reach does, and the plan already accepts that pairing ("two true claims about two
 * different things").
 *
 * <p>What the move does cost is stated where it is paid: {@code windowFirstCards.js} withholds the
 * header's "N within reach" while a floor is active, because that set was gated on two axes and the
 * word names one.
 *
 * <h2>§5f's real rule survives, by a different route</h2>
 *
 * <p>"The rating control is offered only when something is rated, and the floor does not run when it
 * is not offered." Its purpose was that a stored floor must never filter a page with no control on
 * screen to explain it. On a sticky bar the control is <em>always</em> on screen, so the pairing is
 * satisfied by construction rather than by a predicate — and an unevaluated window empties into a
 * line that names the floor and offers to drop it in one tap. The sheet keeps the predicate, because
 * its control is still conditional.
 *
 * <h2>An unrated spot fails the floor</h2>
 *
 * <p>Unchanged from §5f, and deliberately not symmetrical with reach's rule that an unknown drive
 * time passes every tier. "4★ and up" is a claim about what was measured, and an unknown does not
 * meet it. Written as an explicit null test rather than {@code (rating ?? 0) >= min}, because that
 * coercion is the one §5e caught rendering {@code ☆☆☆☆☆} for an unrated spot.
 */

/** The floor that floors nothing. Its own id, so a stored value can name it. */
export const ANY_RATING_ID = 'any';

/** The projector's own ceiling. A floor at the ceiling is "exactly this", not "this and up". */
const MAX_RATING = 5;

/**
 * Every floor this product speaks, loosest first.
 *
 * <p>Whole stars, because {@code bestRating} and {@code claudeRating} are integers in 1–5 and a 3.5
 * floor could only ever behave as 4 — the reasoning {@code CloseToHome}'s {@code RATING_STEPS}
 * already records, and the same ladder, so a reader who has used the local block meets one
 * vocabulary rather than two.
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

/**
 * The floors the page-wide bar offers — everything but the ceiling.
 *
 * <p>The handoff's reason, kept because it is a fact about the data rather than about the layout:
 * "5★ nights are scarce enough that it would usually return nothing", and a page-wide control that
 * usually empties six windows at once is a control nobody presses twice. The <em>sheet</em> keeps
 * the 5★ step: one window's full list is exactly where "show me only the best" is a real question,
 * and it is a browsing choice that dies with the dialog rather than a stored preference.
 *
 * <p>So the bar can never store a floor it could not then draw a pressed chip for — see
 * {@link readStoredRatingFloorId}, which rejects one.
 */
export const LENS_RATING_FLOORS = RATING_FLOORS.filter((floor) => floor.min !== MAX_RATING);

/** The floor for an id, over the whole vocabulary, or null when nothing matches. */
export function ratingFloorById(id) {
  return RATING_FLOORS.find((floor) => floor.id === id) || null;
}

/**
 * The floor for an id, restricted to what the bar offers — null for {@code '5'} as well as for
 * nonsense.
 *
 * <p>Separate from {@link ratingFloorById} because the two answer different questions and the wider
 * one is the wrong guard for a stored value: {@code '5'} is a perfectly good floor in the drill-down
 * and is one the bar cannot draw a pressed chip for. Writing it would leave the page filtered by a
 * control that looks untouched.
 */
export function lensRatingFloorById(id) {
  return LENS_RATING_FLOORS.find((floor) => floor.id === id) || null;
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
 *
 * <p>The key is unchanged from the sheet-owned floor it replaces, and that is deliberate: the value
 * means the same thing it always did. The one shape it can no longer hold is {@code '5'}, which the
 * sheet could write and the bar cannot draw — see below.
 */
export const PLAN_RATING_KEY = 'photocast.planRating';

/**
 * The stored floor id, or null when there is none this bar can act on.
 *
 * <p>Null covers no key, unparseable JSON, a missing field, an id no build has, <b>and a
 * {@code '5'} left behind by the sheet-owned floor this replaces</b>. All five mean the same thing
 * to the caller: no floor. The last is the only migration this move needs, and it degrades in the
 * safe direction — a reader who had pinned the old sheet to 5★ gets an unfiltered page rather than
 * six empty windows above a bar with no chip pressed.
 *
 * @returns {?string} a floor id the bar offers, or null
 */
export function readStoredRatingFloorId() {
  try {
    const raw = localStorage.getItem(PLAN_RATING_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return lensRatingFloorById(parsed?.rating) ? parsed.rating : null;
  } catch {
    return null;
  }
}

/**
 * Records a floor. A whole-value write of the one field this module owns.
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
 * The spots a floor leaves on screen.
 *
 * <p>A null minimum returns the input untouched. An unrated spot is dropped — see the module
 * comment for why that is the opposite of {@code gateSpotsByReach}'s rule and meant to be.
 *
 * @param {Array} spots ordered descriptors from {@code buildWindowSpots}
 * @param {?number} minRating the active floor's threshold, or null for "Any rating"
 * @returns {Array} the spots that pass, in the order they arrived
 */
export function gateSpotsByRating(spots, minRating) {
  if (minRating == null) return spots;
  return spots.filter((spot) => spot.rating != null && spot.rating >= minRating);
}

/*
 * ⚠️ There is deliberately no `nextLooserFloorId` here, and no `nextWiderTierId` in `reachLens.js`.
 * Both were written for the empty card's way out and both were wrong for it: the NEXT step down is
 * not necessarily a step that puts anything on screen, and a button that loosens the lens and
 * leaves the card empty is the demo control §6 bans, arriving by a slightly longer route.
 * `planConflicts.js` walks each ladder until a step actually passes, which is why it needs
 * `LENS_RATING_FLOORS` and `REACH_TIERS` rather than a next-step helper.
 */
