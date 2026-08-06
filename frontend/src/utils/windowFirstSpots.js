import { RATING_COLOURS } from '../components/markerUtils.js';
import { isFarSpot } from './reachLens.js';

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
 * and a {@code var()} would leave the maths describing a colour the badge no longer uses. The light
 * one <em>is</em> {@code --color-plex-text}; the dark one is the ink the map marker already prints
 * its own rating in, so the two surfaces that share {@link RATING_COLOURS} share its label ink too.
 */
const BADGE_INK_LIGHT = '#F2E7D3';
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
 * Plan §2.9 pins {@link RATING_COLOURS} as the badge's palette and warns in the same breath that its
 * luminance peaks at 3★ and falls away on both sides — so no single ink clears AA on all five steps.
 * Dark ink fails on 1★ and 5★ (2.55:1, 2.90:1); light ink fails on 2★, 3★ and 4★. Picking per step
 * is therefore forced, and computing it means a palette change cannot silently drop a step below AA
 * the way five hard-coded pairs would.
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
 * @param {?number} rating 1–5, or null
 * @returns {?{background: string, color: string}} inline style, or null
 */
export function spotBadgeStyle(rating) {
  const fill = RATING_COLOURS[rating];
  return fill ? { background: fill, color: readableInkOn(fill) } : null;
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
        rating: usableRating(slot.claudeRating) ? slot.claudeRating : null,
        driveMinutes,
        distanceMiles: reach?.distanceMiles ?? null,
        far: isFarSpot(driveMinutes, farOverMinutes),
      };
    })
    .sort(compareSpots);
}
