import { isFarSpot } from './reachLens.js';
import { rampHex } from './scoreRamp.js';

/**
 * The spots in a shooting window — the film strip's descriptors, its ordering, and its badge.
 *
 * <h2>Two contracts, joined here</h2>
 *
 * <p>Plan §2.2. Shared window content (name, region, rating) comes from {@code /api/briefing};
 * per-user reach (drive minutes, distance) comes from {@code GET /api/user/settings/reach} and must
 * never ride the briefing, which is ETag-revalidated and therefore persists its body to a browser
 * HTTP cache JavaScript cannot evict on logout. The two are joined on {@code locationId} — the only
 * key both carry, since the reach contract has no location name at all. A slot cached before slots
 * carried an id joins to nothing and simply renders without its reach line.
 *
 * <h2>The strip reads the same slot population the header star does</h2>
 *
 * <p>{@code PlanWindowProjector.bestRating} takes a max over <em>regioned, non-canopy</em> slots,
 * falling back to canopy slots only when the window has nothing else. If the strip read a wider set
 * it could show a spot rated above the header's {@code best N★} — a card contradicting its own
 * heading. So both rules are mirrored here:
 *
 * <ul>
 *   <li><b>Unregioned slots are dropped.</b> They are never enriched with Claude scores, so their
 *       rating is always null, and the card has a region line with nothing to put in it.</li>
 *   <li><b>Canopy slots are dropped unless every slot is one.</b> A woodland GO means heavy cloud
 *       and mist — the opposite of what a sky rating means — so a wood's 4★ beside a coast's 4★
 *       would be two opposite claims in one badge. CLAUDE.md is explicit that anything reducing a
 *       slot to one number must filter on this flag, and a badge is exactly that. The cost is real:
 *       a wood in bluebell season is a genuinely good destination that this strip will not list.
 *       P11's drill-down, which can carry a type control and a second vocabulary, is where that
 *       belongs — not a badge whose colour would mean the opposite of its neighbours'.</li>
 * </ul>
 */

/** The projector's own rating bounds. A value outside them is discarded, never displayed. */
const MIN_RATING = 1;
const MAX_RATING = 5;

/**
 * The two inks a rating badge may print in.
 *
 * <p>Literals rather than tokens because {@link readableInkOn} does contrast arithmetic on them,
 * and a {@code var()} would leave the maths describing a colour the badge no longer uses. The dark
 * one is the ink the v2 map marker prints its own rating in — which is where the sharing stops:
 * {@code markerUtils.buildMarkerSvg} hard-codes that ink at every site, so on the ramp it now
 * carries 2.96:1 at 1★ and 3.70:1 at 2★. That is P4's marker, not this badge, and it is recorded
 * in the P5 row rather than fixed from here; do not read this line as saying the marker is
 * covered.
 *
 * <p>⚠️ <b>The light ink is pure white and not {@code --color-plex-text}, and that changed with the
 * ramp (D2).</b> Against the old five-bucket marker palette the app's own cream cleared AA on every
 * step it was chosen for; against the score ramp it does not. Measured on the five ramp stops, cream
 * (#F2E7D3) gives 4.92 / <b>3.94</b> / 1.78 / 1.64 / 2.05 and the dark ink gives 2.96 / <b>3.70</b>
 * / 8.19 / 8.90 / 7.12 — so at 2★ (#C8452F) <em>neither</em> reaches the 4.5:1 this 10px badge
 * needs, and the swap would have shipped one step below AA. White gives 6.03 / <b>4.83</b> / 2.18 /
 * 2.01 / 2.51, so every step clears with the same two-ink derivation, and white flips to the dark
 * ink at exactly the step the cream would have (between 2★ and 3★ — <em>not</em> where the old
 * palette flipped, which was between 1★ and 2★ and again between 4★ and 5★, since that palette
 * peaked in the middle). The alternative — darkening the 2★ stop — would have broken D2's whole
 * point, which is that the badge and the field beneath it mean the same thing by the same colour.
 */
const BADGE_INK_LIGHT = '#FFFFFF';
const BADGE_INK_DARK = '#0F172A';

/** sRGB relative luminance, per WCAG 2.2. Expects `#RRGGBB`. */
function luminance(hex) {
  const channels = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

/** WCAG contrast ratio between two `#RRGGBB` colours. */
function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/**
 * Whichever of the two badge inks is more readable on the given fill.
 *
 * <p><b>Derived rather than tabulated, because the palette it reads is not this file's to change.</b>
 * The badge's palette is now {@code scoreRamp} (D2), whose luminance peaks at 4★ and falls away on
 * both sides — so no single ink clears AA on all five steps. Dark ink fails on 1★ and 2★ (2.96:1,
 * 3.70:1); the light ink fails on 3★, 4★ and 5★. Picking per step is therefore forced, and computing
 * it means a palette change cannot silently drop a step below AA the way five hard-coded pairs
 * would — which is exactly what the swap onto the ramp would have done at 2★ had the ink pair not
 * been re-measured with it (see {@link BADGE_INK_LIGHT}).
 *
 * @param {string} fillHex the badge fill, `#RRGGBB`
 * @returns {string} the ink to print the rating in
 */
export function readableInkOn(fillHex) {
  return contrast(BADGE_INK_DARK, fillHex) >= contrast(BADGE_INK_LIGHT, fillHex)
    ? BADGE_INK_DARK
    : BADGE_INK_LIGHT;
}

/**
 * The rating badge's fill and ink, or null when the spot carries no rating.
 *
 * <p>Null rather than a grey placeholder, for the reason the window header omits its star: an
 * unrated spot is one nothing has looked at, which is a different statement from a poor one.
 *
 * <p><b>The fill comes from {@code scoreRamp} (D2, P5), and the gate had to be written out to keep
 * that from changing what the badge shows.</b> The old five-key marker table answered
 * {@code undefined} for anything outside its keys, so the old {@code fill ?} test was the domain
 * check as well as the null check. {@code rampHex} is defined on the continuum and <em>clamps</em>,
 * so it answers a colour for 0, for 6 and for 2.5 — which would have put a badge on an unrated spot
 * and printed "2.5★" where the table drew nothing. The bounds are {@link MIN_RATING}/{@link
 * MAX_RATING}, the <em>rating's</em> own, and deliberately not the ramp's {@code RAMP_MIN}/
 * {@code RAMP_MAX}: they coincide today, but {@code scoreRamp} is shared with the heat kernel and
 * the markers, and a ramp re-based to 0–100 would silently widen this badge to paint a "0★".
 *
 * <p>It restates the old table's domain for every input the payload can produce, with one exception
 * worth naming: object indexing coerces, so a string key like {@code '4'} answered a colour there
 * and this does not. Jackson serialises {@code claudeRating} as a JSON number, so no live payload
 * reaches it.
 *
 * @param {?number} rating 1–5, or null
 * @returns {?{background: string, color: string}} inline style, or null
 */
export function spotBadgeStyle(rating) {
  if (!Number.isInteger(rating) || rating < MIN_RATING || rating > MAX_RATING) return null;
  const fill = rampHex(rating);
  return { background: fill, color: readableInkOn(fill) };
}

/**
 * The strip's ordering — rating descending, then drive time ascending, then name.
 *
 * <p><b>One comparator, exported, because the footer states the order it claims.</b> §6 requires a
 * footer's claimed sort to match what is rendered, and the only way to guarantee that is for the
 * sentence and the sort to read the same function. P11's drill-down ranks the same spots and takes
 * this one rather than authoring a second.
 *
 * <p>Both nulls sort <em>last</em>, and for the same reason: an unknown is not a good. An unrated
 * spot is not better than a 1★, and a spot with no drive time is not nearer than one 20 minutes
 * away — plan §2.5's rule that absence means "unknown, never out of reach" cuts both ways. Name is
 * the final tie-break so the order is total and stable; with neither ratings nor drive times — the
 * normal first-run state — it is the *only* key that fires, which is why
 * {@link spotOrderStatement} has a fourth sentence.
 *
 * @param {object} a a spot descriptor
 * @param {object} b a spot descriptor
 * @returns {number} sort order
 */
export function compareSpots(a, b) {
  const ratingA = a.rating ?? -Infinity;
  const ratingB = b.rating ?? -Infinity;
  if (ratingA !== ratingB) return ratingB - ratingA;
  const driveA = a.driveMinutes ?? Infinity;
  const driveB = b.driveMinutes ?? Infinity;
  if (driveA !== driveB) return driveA - driveB;
  return a.locationName.localeCompare(b.locationName);
}

/**
 * What the footer may honestly say about the order.
 *
 * <p>A sort key that no spot carries never fires, so naming it would describe a ranking that did
 * not happen — §6's "every footer's claimed sort and count matches what is rendered", applied to
 * the sort half. All four sentences are reachable: a user with no home postcode has no drive times
 * at all (the normal first-run state), and a briefing whose windows are still awaiting evaluation
 * has no ratings.
 *
 * @param {Array} spots the spots as rendered
 * @returns {string} the sentence
 */
export function spotOrderStatement(spots) {
  const keys = [];
  if (spots.some((s) => s.rating != null)) keys.push('rating');
  if (spots.some((s) => s.driveMinutes != null)) keys.push('drive time');
  return keys.length === 0 ? 'Listed alphabetically.' : `Ranked by ${keys.join(', then ')}.`;
}

/** Mirrors the projector: a rating outside 1–5 is discarded rather than displayed. */
function usableRating(rating) {
  return rating != null && rating >= MIN_RATING && rating <= MAX_RATING;
}

/**
 * Builds the ordered spot descriptors for one window.
 *
 * @param {?object} eventSummary the window's event summary, carrying `regions[].slots[]`
 * @param {?Map<number, {driveMinutes: ?number, distanceMiles: ?number}>} reachById per-user reach,
 *        keyed by location id. Empty until the reach request resolves, and empty forever for a user
 *        with no home postcode — in both cases every card simply renders without its reach line.
 * @param {?number} [farOverMinutes] today's default reach in minutes. A spot beyond it is marked
 *        {@code far}; null marks nothing. Deliberately the DEFAULT tier rather than the selected
 *        one — {@link isFarSpot} carries the reasoning, and the caller passes today's default so
 *        that gating and marking are the same judgement made once.
 * @returns {Array} spot descriptors, ordered by {@link compareSpots}
 */
export function buildWindowSpots(eventSummary, reachById, farOverMinutes = null) {
  const regioned = (eventSummary?.regions || []).flatMap(
    (region) => (region.slots || []).map((slot) => ({ slot, regionName: region.regionName })),
  );
  if (regioned.length === 0) return [];

  // Keyed on the PRESENCE of a sky slot, never on whether one is rated — the projector's rule, and
  // the difference is an ordinary misty sunrise: the fog that leaves every sky slot unrated is the
  // same fog that scores the wood well, so a rated-keyed test would hand that window to the wood.
  const canopyCounts = regioned.every(({ slot }) => slot.canopy);

  return regioned
    .filter(({ slot }) => canopyCounts || !slot.canopy)
    .map(({ slot, regionName }) => {
      const reach = slot.locationId == null ? null : reachById?.get(slot.locationId);
      const driveMinutes = reach?.driveMinutes ?? null;
      return {
        key: String(slot.locationId ?? slot.locationName),
        locationId: slot.locationId ?? null,
        locationName: slot.locationName,
        regionName: regionName || null,
        // THIS location's own event time, not the window's. Sunrise spans tens of minutes across
        // the roster, and `leaveBy` is advice to one person driving to one place — where the
        // window header's time is the earliest across a region set, chosen for determinism. The
        // consequence is on screen and is the right trade: a card can read `21:11` in its header
        // and a leave time derived from 21:25.
        //
        // RAW, not the formatted string, and the formatting happens per RENDERED card instead.
        // `formatInstantUk` builds an `Intl` formatter per call (~0.09 ms against 0.002 ms cached),
        // and this join runs over the whole roster × six windows on every poll and every window
        // focus — 204 × 6 = 1,224 formats to serve the handful of cards actually drawn. Both
        // consumers call one pure function on these same two fields, so they cannot disagree.
        // Null renders no leave-by line at all; see `leaveBy` for why that is defensive rather
        // than a shape the payload has ever had.
        solarEventTime: slot.solarEventTime ?? null,
        rating: usableRating(slot.claudeRating) ? slot.claudeRating : null,
        driveMinutes,
        distanceMiles: reach?.distanceMiles ?? null,
        far: isFarSpot(driveMinutes, farOverMinutes),
      };
    })
    .sort(compareSpots);
}
