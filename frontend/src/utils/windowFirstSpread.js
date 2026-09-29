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
 * and toward none of the bands. That makes {@code N > Σbars} the ordinary state whenever bars are
 * drawn at all, and the remainder is therefore named — "· 2 not yet rated" — rather than left for
 * the reader to notice the arithmetic does not close. {@link unratedPhrase} is exported because
 * A10's disclosure has to reach the card's accessible sentence too, not the pointer-only tooltip
 * alone.
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
 *
 * <h2>A picture needs a big enough sample, or it is not a picture (2026-09-29)</h2>
 *
 * <p>A lone rated location in a large pool draws a full-height bar exactly like a whole roster in
 * agreement — the shape and the noise are visually identical. Production data the day this rule was
 * written: a Thursday sunrise card drew one full-height 4★ bar over ONE rated location in a
 * 31-place pool, and read as "this sunrise looks good" when the truth was "this sunrise is almost
 * entirely unlooked-at". The same day's near windows held 165–216 rated places of 253 (a genuine,
 * broad distribution); its far windows held 12, 4 and 6 — every one of them a force-evaluated
 * headline candidate, picked because it looked promising, never a random sample of the roster.
 * {@link hasSpreadSample} is the gate: both an absolute floor and a coverage floor, because either
 * ALONE is gameable — the absolute floor alone is cleared by 5 headline picks out of 200, and the
 * coverage floor alone is cleared by 3 of a pool of 6. {@link spreadRowState} is the one function
 * that decides what the row shows instead when the gate fails, so the visible text, the tooltip and
 * the card's spoken sentence read it rather than each testing the thresholds on their own.
 *
 * <p>An EMPTY pool is not a gate failure — there is no sample to distrust, only nothing to show —
 * so it keeps the pre-existing treatment: {@link spreadRowState} answers {@code bars: true} for it
 * exactly as for a sufficient sample, and {@link spreadBars} already draws five hairlines for an
 * empty spread (every band's count is zero). The empty-pool tooltip sentences in {@link
 * spreadTitle} are untouched by this rule for the same reason.
 *
 * <h2>The unrated remainder as a sixth, hatched bar</h2>
 *
 * <p>Once there IS a big enough sample to draw, the bars still say nothing about how much of the
 * pool went unlooked-at — that fact lived only in the tooltip's remainder clause and the card's
 * hidden sentence, neither reachable by a phone reader's eye. {@link unratedBar} draws it as a
 * SIXTH bar, sharing the five bands' own scale (never its own, separate one — a shared scale is
 * what makes it comparable rather than merely adjacent) and rendered by the component with a hatch,
 * never a ramp colour: the map's own unscored plate already carries that exact convention
 * ("nothing was scored here", never "everything scored badly" — {@code HATCH_INK} in
 * {@code heatField.js}), and drawing the remainder as a 1★ bar would have this histogram
 * contradict the map over the same fact. It carries its own, larger minimum height ({@link
 * SPREAD_UNRATED_BAR_MIN_PX}) than a rated band's floor — a 1px-thin hatch is not reliably
 * recognisable as a hatch rather than a hairline at the size a band's own floor would allow.
 */

/** The bands, lowest first — the ramp's own domain, so a re-based ramp cannot silently widen this. */
export const SPREAD_STARS = Array.from(
  { length: RAMP_MAX - RAMP_MIN + 1 }, (_, i) => RAMP_MIN + i,
);

/** The tallest a bar may be drawn, in px — the drawable height inside the histogram's own well. */
export const SPREAD_BAR_MAX_PX = 13;
/** A band with at least one location is never thinner than this, so a count of 1 is visible. */
export const SPREAD_BAR_MIN_PX = 2;
/** A band with nothing in it draws a hairline rather than disappearing — a bars-mode row is always five bars. */
export const SPREAD_BAR_EMPTY_PX = 1;
/**
 * The unrated remainder's OWN floor, taller than {@link SPREAD_BAR_MIN_PX} — a hatch this thin
 * is not reliably distinguishable from an empty band's hairline, whereas a solid band of the same
 * height reads as "small but present" from its colour alone. See {@link unratedBar}.
 */
export const SPREAD_UNRATED_BAR_MIN_PX = 4;

/**
 * The absolute floor on the minimum-sample rule — see the class comment. Below this many rated
 * places, no count of bands is a distribution yet, however large the pool behind it.
 */
export const SPREAD_MIN_RATED_COUNT = 5;
/**
 * The coverage floor on the minimum-sample rule — see the class comment, and {@link
 * hasSpreadSample}, which reads this constant directly (`rated >= total * SPREAD_MIN_RATED_COVERAGE`)
 * rather than a hand-written fraction, so retuning this value actually changes the gate.
 *
 * <p>This is the same "half the roster" line `ConfidenceDeriver` already draws on the backend
 * (fewer than half the roster scored downgrades confidence one band) — not imported from there
 * (this module reaches for nothing server-side), but the identical answer to the identical
 * question — is this sample big enough to trust — asked of the client's own reach-gated pool. The
 * two gates measure different populations (this one the reach-gated pool a single reader sees;
 * the backend one the whole region's roster) and can disagree on the same card.
 */
export const SPREAD_MIN_RATED_COVERAGE = 0.5;

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
 * Whether the pool's rated sample is big enough to draw as a distribution at all.
 *
 * <p>Both floors must hold. The absolute one ({@link SPREAD_MIN_RATED_COUNT}) alone would be
 * cleared by five force-evaluated headline picks out of a two-hundred-strong pool; the coverage
 * one ({@link SPREAD_MIN_RATED_COVERAGE}) alone would be cleared by three of a pool of six. Either
 * ALONE is gameable — see the class comment for the production numbers that motivated both.
 *
 * @param {object} spread the result of {@link buildSpread}
 * @returns {boolean} true when the sample clears both floors
 */
export function hasSpreadSample(spread) {
  const rated = spread?.rated ?? 0;
  const total = spread?.total ?? 0;
  return rated >= SPREAD_MIN_RATED_COUNT && rated >= total * SPREAD_MIN_RATED_COVERAGE;
}

/**
 * The one decision behind the Spread row's bars-vs-text choice — the visible row and (below the
 * gate) the card's spoken sentence read this rather than testing {@link hasSpreadSample}
 * themselves, so a future retune of either threshold cannot move one reader's answer without
 * moving the other.
 *
 * <p>An EMPTY pool answers {@code bars: true} — there is no sample to distrust, only nothing to
 * show, and the pre-existing treatment (five hairlines, the old two tooltip sentences in {@link
 * spreadTitle}) is kept unchanged; see the class comment. A pool that holds places but rated none
 * of them answers "none rated yet" for BOTH {@code text} and {@code spoken} — the count would be
 * the pool size restated for no reason, since "none" already says the fraction is zero, and the
 * word needs no re-voicing either way. Otherwise, an insufficient sample names both figures, but
 * as TWO DIFFERENT STRINGS: {@code text} is the COMPACT {@code N/M rated} the visible row shows
 * (measured against the real card grid at 320/375/640px, plan-matrix-plan.md A27 — the full "of"
 * form overflows the value column for a realistic large pool, "124 of 253 rated" at 105.6px
 * against an 88.3px column at 320px, even at a reduced 10px type, 96px, where the compact form
 * fits at 78px with margin to spare), and {@code spoken} is the WORDED {@code N of M rated} the
 * accessible sentence uses instead — a screen reader voices a fraction-shaped string as a number
 * ("one quarter") or a date-like read, never as the two counts it visually is, so the compact form
 * that fixed the row's width must never reach the accessible name. One decision, two strings for
 * the one state that needs them to differ — not two decisions.
 *
 * <p>⚠️ The TOOLTIP is a separate function ({@link spreadTitle}) and deliberately says MORE than
 * either string below the gate — it restores the pool size and, for a partial sample, the
 * remainder via {@link unratedPhrase} — so do not assume it must match either verbatim outside the
 * bars-mode case.
 *
 * @param {object} spread the result of {@link buildSpread}
 * @returns {{bars: boolean, text: ?string, spoken: ?string}} whether to draw bars; when not, the
 *          exact VISIBLE text the row shows ({@code text}) and the exact WORDED text the card's
 *          spoken sentence must use instead ({@code spoken}) — identical for "none rated yet",
 *          deliberately different (`N/M rated` vs `N of M rated`) for a partial sample
 */
export function spreadRowState(spread) {
  const rated = spread?.rated ?? 0;
  const total = spread?.total ?? 0;
  if (total === 0) return { bars: true, text: null, spoken: null };
  if (hasSpreadSample(spread)) return { bars: true, text: null, spoken: null };
  if (rated === 0) return { bars: false, text: 'none rated yet', spoken: 'none rated yet' };
  return {
    bars: false,
    text: `${rated}/${total} rated`,
    spoken: `${rated} of ${total} rated`,
  };
}

/**
 * The scale every bar — the five bands and the unrated remainder alike — is drawn against. Shared
 * rather than computed separately per caller, because a shared scale is what makes the unrated bar
 * COMPARABLE to the bands rather than merely adjacent to them (the class comment's Part 2).
 *
 * @param {object} spread the result of {@link buildSpread}
 * @returns {number} the tallest count any bar must be drawn against, never 0 (a 0 divisor)
 */
function spreadScaleMax(spread) {
  return Math.max(spread?.max ?? 0, spread?.unrated ?? 0) || 1;
}

/**
 * The bars to draw, lowest star first.
 *
 * <p>Heights are proportional to the tallest band OR the unrated remainder, whichever is larger
 * (see {@link spreadScaleMax}), so a window where one band holds three of a hundred locations still
 * reads as a shape and a huge unrated remainder cannot make every band read as equally tall. The
 * floor of {@link SPREAD_BAR_MIN_PX} is what stops a count of one rounding away to nothing.
 *
 * @param {object} spread the result of {@link buildSpread}
 * @returns {Array<{star: number, count: number, heightPx: number, filled: boolean}>} the bars
 */
export function spreadBars(spread) {
  const max = spreadScaleMax(spread);
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
 * The unrated remainder as a SIXTH bar, on the SAME scale as the five bands — see the class
 * comment's Part 2. Null whenever there is nothing unrated to draw, which is what keeps a
 * fully-rated window's histogram at five bars rather than a sixth one pinned to the floor for a
 * true zero.
 *
 * <p>This is a function of {@code spread} ALONE — it does not know about {@link spreadRowState}'s
 * gate, and can answer non-null even when the pool is below it (a partial sample with a real
 * remainder). The RENDER is what must AND this with {@code state.bars}, never this function.
 *
 * @param {object} spread the result of {@link buildSpread}
 * @returns {?{count: number, heightPx: number}} the bar, or null when {@code unrated === 0}
 */
export function unratedBar(spread) {
  const unrated = spread?.unrated ?? 0;
  if (unrated <= 0) return null;
  const max = spreadScaleMax(spread);
  return {
    count: unrated,
    heightPx: Math.max(SPREAD_UNRATED_BAR_MIN_PX, Math.round((unrated / max) * SPREAD_BAR_MAX_PX)),
  };
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
 * <p>⚠️ <b>Below the minimum-sample gate ({@link hasSpreadSample}), this tooltip says MORE than the
 * visible row</b> — the row's own text ({@link spreadRowState}) is deliberately short for the
 * value column's width, but the tooltip has room, so it restores the pool size for both sub-states
 * and, for a partial sample, the rated count and the remainder (through {@link unratedPhrase}) in
 * the same "lead — detail" shape bars mode uses. The "none rated" sentence is the ORIGINAL, unchanged
 * form this function has always returned.
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
    return withinReach ? 'Nothing within reach for this one.' : 'Nothing to show for this one.';
  }
  const rated = spread?.rated ?? 0;
  const lead = poolPhrase(total, withinReach);
  // Nothing rated at all is the ORDINARY state on a far-horizon window — T+4 is never evaluated —
  // so it gets a sentence of its own rather than five zeroes and a remainder clause restating the
  // whole pool. "RATED", never "scored": plan A10 and M1 task 2 both ban the second word from this
  // copy in terms, and it is the word the remainder clause below already uses. Unchanged since
  // before the minimum-sample rule — this sentence never depended on bars being drawn.
  if (rated === 0) return `${lead} — none rated yet.`;
  // Below the gate but not zero — a partial sample. Same "lead — detail · remainder" shape as bars
  // mode, with the detail standing in for a per-band breakdown this sample is too small to trust.
  if (!hasSpreadSample(spread)) return `${lead} — ${rated} rated${unratedPhrase(spread)}`;
  const bands = SPREAD_STARS
    .map((star, index) => `${spread.counts[index]} at ${star}★`)
    .reverse()
    .join(', ');
  return `${lead} — ${bands}${unratedPhrase(spread)}`;
}
