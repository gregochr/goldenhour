import { RAMP_MAX, RAMP_MIN } from './scoreRamp.js';

/**
 * The window card's spread histogram — how the ratings you can actually reach are distributed.
 *
 * <h2>What it is for, and why it is not an average</h2>
 *
 * <p>Plan-matrix §4 A10. The v3 card replaces "best 4.0★" with a five-bar histogram, one bar per
 * star band, because the two readings a photographer needs are different shapes rather than
 * different numbers: a lone spike on the right reads <em>one good spot, drive to it</em>, and a
 * right-weighted block reads <em>the whole area is on</em>. An average collapses both into the
 * same digit, and the verdict word beside it already says that much.
 *
 * <h2>The pool is reach-gated and sky-gated, and the rating floor is NOT applied</h2>
 *
 * <p>The design's own note: "an average of things that already passed a 4★ filter always reads
 * 4-something". So this counts the card's pool — origin-scoped, canopy-filtered, reach-gated,
 * before the floor — which is what {@code buildWindowCards} now publishes as {@code card.pool}.
 *
 * <p><b>The leading N is the POOL's size, not the sum of the bars.</b> An unrated spot is a real
 * place within reach that nothing has looked at, so it counts toward "how many places could I go"
 * and toward none of the bands. That makes {@code N > Σbars} the ordinary state on a far-horizon
 * window (T+4 is never evaluated at all), and the remainder is therefore named — "· 2 not yet
 * rated" — rather than left for the reader to notice the arithmetic does not close. {@link
 * unratedPhrase} is exported because A10's disclosure has to reach the card's accessible sentence
 * too, not the pointer-only tooltip alone.
 *
 * <h2>What the word "within reach" may claim</h2>
 *
 * <p>Plan §2.5 rule 1: a spot with no drive time is <em>unknown</em>, not out of reach, and it
 * passes every tier. So a pool holding one is part measured and part unknown, and calling the
 * whole of it "within reach" is the over-claim {@code withinReachCount} refuses one surface along.
 * {@link poolWithinReach} is the same rule, and the phrase is dropped where it fails — the count
 * still stands, because the bars are drawn over exactly that set.
 *
 * <p>It deliberately does <b>not</b> also require an active reach tier, which is the one condition
 * {@code withinReachCount} adds. That clause is about a count being <em>informative</em> ("under
 * Any nothing was gated, so the word describes no act"); this phrase's job is to name WHICH set the
 * bars describe, and under "Any" every measured spot genuinely is within the reach the reader has
 * chosen. Two different questions, deliberately answered differently, said here so the difference
 * does not read as an oversight.
 */

/** The bands, lowest first — the ramp's own domain, so a re-based ramp cannot silently widen this. */
export const SPREAD_STARS = Array.from(
  { length: RAMP_MAX - RAMP_MIN + 1 }, (_, i) => RAMP_MIN + i,
);

/** The tallest a bar may be drawn, in px — the drawable height inside the histogram's own well. */
export const SPREAD_BAR_MAX_PX = 13;
/** A band with at least one location is never thinner than this, so a count of 1 is visible. */
export const SPREAD_BAR_MIN_PX = 2;
/** A band with nothing in it draws a hairline rather than disappearing — the row must read as five. */
export const SPREAD_BAR_EMPTY_PX = 1;

/** A rating this histogram will count: an integer inside the ramp's own domain. */
function countable(rating) {
  return Number.isInteger(rating) && rating >= RAMP_MIN && rating <= RAMP_MAX;
}

/**
 * Counts one window's pool into star bands.
 *
 * @param {Array<{rating: ?number}>} pool the card's reach-gated, pre-floor spot pool
 * @returns {{total: number, rated: number, unrated: number, counts: number[], max: number}}
 *          the pool size, how many carried a rating, the remainder, the per-band counts (index 0 is
 *          {@link RAMP_MIN}) and the tallest band
 */
export function buildSpread(pool) {
  const counts = SPREAD_STARS.map(() => 0);
  let rated = 0;
  for (const spot of Array.isArray(pool) ? pool : []) {
    const rating = spot?.rating;
    if (!countable(rating)) continue;
    counts[rating - RAMP_MIN] += 1;
    rated += 1;
  }
  const total = Array.isArray(pool) ? pool.length : 0;
  return {
    total, rated, unrated: total - rated, counts, max: Math.max(...counts),
  };
}

/**
 * The bars to draw, lowest star first.
 *
 * <p>Heights are proportional to the tallest band rather than to the pool, so a window where one
 * band holds three of a hundred locations still reads as a shape. The floor of
 * {@link SPREAD_BAR_MIN_PX} is what stops a count of one rounding away to nothing.
 *
 * @param {object} spread the result of {@link buildSpread}
 * @returns {Array<{star: number, count: number, heightPx: number, filled: boolean}>} the bars
 */
export function spreadBars(spread) {
  const max = spread?.max > 0 ? spread.max : 1;
  return SPREAD_STARS.map((star, index) => {
    const count = spread?.counts?.[index] ?? 0;
    return {
      star,
      count,
      filled: count > 0,
      heightPx: count > 0
        ? Math.max(SPREAD_BAR_MIN_PX, Math.round((count / max) * SPREAD_BAR_MAX_PX))
        : SPREAD_BAR_EMPTY_PX,
    };
  });
}

/**
 * Whether every spot in the pool has a measured drive — see the class comment.
 *
 * <p>An empty pool answers true, which is what makes "nothing within reach" safe to say: there is
 * no unmeasured spot in it to over-claim about.
 *
 * @param {Array<{driveMinutes: ?number}>} pool the card's pool
 * @returns {boolean} true when the phrase "within reach" describes the whole set
 */
export function poolWithinReach(pool) {
  return (Array.isArray(pool) ? pool : []).every((spot) => spot?.driveMinutes != null);
}

/**
 * `12 locations within reach` / `1 location` — the leading clause, honest about the word.
 *
 * <p>⚠️ <b>Exported, and the card's accessible sentence must call it rather than spell it again.</b>
 * The tooltip and the hidden sentence describe the SAME set on the same card, so a second copy of
 * the plural rule or of the "within reach" condition is two chances for one card to make two claims
 * — the defect this module's own header warns about one level down.
 *
 * @param {number} total       the pool's size
 * @param {boolean} withinReach whether the phrase may claim reach — {@link poolWithinReach}
 * @returns {string} the clause
 */
export function poolPhrase(total, withinReach) {
  return `${total} location${total === 1 ? '' : 's'}${withinReach ? ' within reach' : ''}`;
}

/**
 * The remainder clause — how many places in reach nothing has looked at yet, or an empty string.
 *
 * <p>Exported for the same reason {@link poolPhrase} is: A10 requires the remainder to be named
 * wherever the leading `N` is, and the tooltip is not the only place it is. Leading separator
 * included so a caller composes rather than punctuates.
 *
 * @param {object} spread the result of {@link buildSpread}
 * @param {string} [separator] what to join with — the tooltip's middle dot, a sentence's comma
 * @returns {string} ` · 2 not yet rated`, or ''
 */
export function unratedPhrase(spread, separator = ' · ') {
  return spread?.unrated > 0 ? `${separator}${spread.unrated} not yet rated` : '';
}

/**
 * The histogram's tooltip — what the bars count, in words.
 *
 * <p>Bands are read <b>highest star first</b>, matching the direction a reader scans the bars for
 * the good news. The copy names places to go, never "N of M scored": plan §3 rule 5 bans counts of
 * our own data presented as facts about the sky, and "locations within reach" is the statement the
 * lens readout already makes.
 *
 * @param {object} spread      the result of {@link buildSpread}
 * @param {boolean} withinReach whether the pool's drives are all measured — {@link poolWithinReach}
 * @returns {string} the tooltip
 */
export function spreadTitle(spread, withinReach) {
  const total = spread?.total ?? 0;
  // ⚠️ TWO sentences, and the second is not a dead branch. `poolWithinReach([])` is true by
  // `Array.every`, so an empty pool always arrives with the reach word available — which is exactly
  // the problem: for a reader with no home postcode nothing was gated by distance at all (an unknown
  // drive passes every tier, plan §2.5), so an empty pool means this window has no sky-gated slots,
  // and "within reach" blames a control that did nothing. §6 clause 7. The caller answers it from
  // the card's own `allSpots`, which is the same question `bestReachLine` asks eight lines away, so
  // the tooltip and the visible line agree by construction rather than by review.
  if (total === 0) {
    return withinReach ? 'Nothing within reach for this window.' : 'Nothing to show for this window.';
  }
  const lead = poolPhrase(total, withinReach);
  // Nothing rated at all is the ORDINARY state on a far-horizon window — T+4 is never evaluated —
  // so it gets a sentence of its own rather than five zeroes and a remainder clause restating the
  // whole pool. "RATED", never "scored": plan A10 and M1 task 2 both ban the second word from this
  // copy in terms, and it is the word the remainder clause below already uses.
  if (spread.rated === 0) return `${lead} — none rated yet.`;
  const bands = SPREAD_STARS
    .map((star, index) => `${spread.counts[index]} at ${star}★`)
    .reverse()
    .join(', ');
  return `${lead} — ${bands}${unratedPhrase(spread)}`;
}
