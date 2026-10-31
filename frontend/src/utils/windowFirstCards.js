import { formatTime, getEventTime } from './briefingDisplay.js';
import { gateSpotsByReach } from './reachLens.js';
import { gateSpotsByRating } from './ratingLens.js';
import { buildWindowSpots, placesOf } from './windowFirstSpots.js';
import { buildWindowRows } from './windowFirstRows.js';
import { gateSpotsByOrigin } from './planOrigin.js';
import { resolveRegionDisplay } from './tierUtils.js';

/**
 * The window-card descriptors — one per rendered solar window.
 *
 * <h2>It reads the window projection, and almost nothing else</h2>
 *
 * <p>{@code BriefingWindow} was built for exactly this surface, so the card is close to a
 * pass-through: verdict, best rating, confidence, badges and pick all arrive derived. What this
 * module adds is the framing the payload deliberately withholds — the window "carries no date and
 * no target type. Both are the enclosing BriefingDay and BriefingEventSummary" — plus the two
 * judgements that are the client's to make: which card leads, and what the header may honestly say.
 *
 * <h2>What the header may claim</h2>
 *
 * <p>The design's meta reads {@code best 4.0★ · 23 within reach}. Neither half survives here.
 * The star is {@code best spot 4★}: {@code bestRating} is an {@code Integer} in 1–5, so {@code 4.0★}
 * asserts a precision the field does not carry — and the word <em>spot</em> is load-bearing rather
 * than decorative. Since the verdict badge became the top region's AVERAGE (plan §2), this number
 * describes one location while the badge beside it describes a whole region, so the two can read
 * {@code Poor · best spot 4★} and both be true. The label is what makes that two facts instead of a
 * contradiction, which is the entire condition on which {@code bestRating} was allowed to stay on
 * the payload at all.
 *
 * <p><b>The header's count arrives at P8, and only when the word it uses is true.</b> P5 and P6 both
 * withheld it because the design's word is <em>reach</em> and nothing was gated: "23 within reach"
 * above an ungated strip describes a set nothing filtered, which §6 bans. The tier ships now, so the
 * sentence can be earned — but not unconditionally. {@code withinReachCount} is non-null only when
 * the gate had a threshold to apply <em>and</em> every drawn spot has a known drive time, because
 * plan §2.5 rule 1 lets an unknown drive time through every tier: a set containing one is a mix of
 * "within reach" and "not known", and calling the whole of it the former over-claims. Where the word
 * is not available the header stays as P5 shipped it — no count at all, rather than a bare "23
 * spots" duplicating the strip footer verbatim one element lower. Exactly one count when nothing was
 * gated, two complementary ones when something was.
 *
 * <p><b>And {@code bestRating} is deliberately NOT re-derived from the gated set</b>, which is
 * visible the moment the lens bites: a header can read {@code best spot 5★ · 7 within reach} over a
 * strip topping out at 4★, because the 5★ is beyond the tier. That is two true claims about two
 * different things — the star is the <em>window's</em> best, the count is what <em>this user</em>
 * can drive to — and it is useful in that state, since it says a better spot exists further out.
 * Re-deriving it would make one window's "best" differ per user for the same night, put it out of
 * step with every other surface that reads the projection, and move a quality signal when the
 * reader touched a control explicitly about distance. Plan §2.7's rule that the star is never
 * touched by the confidence channel applies here for the same reason.
 */

/**
 * Kickers and labels are day-relative.
 *
 * <p>Exported so {@code windowFirstStrip.js} names a window the same way this module does. It was
 * private while the day rail kept its own copy; with the rail retired at P2 there are two callers
 * and one definition, which is the right way round — a strip thumbnail and the card it opens
 * printing different words for one date is precisely the defect a second copy produces.
 */
export function dayLabelFor(dateStr, todayStr, tomorrowStr) {
  if (dateStr === todayStr) return 'Today';
  if (dateStr === tomorrowStr) return 'Tomorrow';
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/**
 * A PAST window's day label — {@code Today}, {@code Yesterday}, else {@code Fri 16} (short weekday,
 * day of month with no leading zero), the outcome-rating design's vocabulary for the Map tab's past
 * rows (docs/engineering/outcome-rating-plan.md §2 #2). Kept beside {@link dayLabelFor} rather than
 * widening it: its two Plan-tab callers never see a past date, and a long weekday name is the wrong
 * word for a day that has gone ("Friday" is ambiguous with next Friday; "Fri 16" is not).
 *
 * <p>A lookup over a date string — the already-licensed filter/map/select class (CLAUDE.md
 * Backend-heavy), deciding nothing. The weekday and day are read in UTC from a noon-pinned instant,
 * so the answer is the calendar date's own whatever zone the browser is in.
 *
 * @param {string} dateStr the window's ISO date
 * @param {string} todayStr the UK civil today
 * @returns {string}
 */
export function pastDayLabel(dateStr, todayStr) {
  if (dateStr === todayStr) return 'Today';
  const d = new Date(`${dateStr}T12:00:00Z`);
  const t = new Date(`${todayStr}T12:00:00Z`);
  if (Math.round((t.getTime() - d.getTime()) / 86400000) === 1) return 'Yesterday';
  return dowDayLabel(dateStr);
}

/**
 * {@code Thu 16} for any date, never {@code Today}/{@code Yesterday} — the form a sentence about ONE
 * calendar day wants ("No stored forecast for Fri 16": "yesterday" would be wrong the next morning).
 * The same noon-pinned UTC read {@link pastDayLabel} makes, so the answer is the date's own in any zone.
 *
 * @param {string} dateStr an ISO date
 * @returns {string}
 */
export function dowDayLabel(dateStr) {
  const d = new Date(`${dateStr}T12:00:00Z`);
  const dow = d.toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
  return `${dow} ${d.getUTCDate()}`;
}

/** 'sunrise' | 'sunset' — the window's own event, lower case for use inside a phrase. Exported
 *  beside {@link dayLabelFor} and for the same reason. */
export function eventWord(targetType) {
  return targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
}

/** Sentence case for the standalone form, where the kicker already carries the day. */
function eventTitle(targetType) {
  return targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset';
}

/**
 * The badge's word for a display verdict.
 *
 * <p><b>{@code AWAITING} is not a poor forecast and must not read as one.</b> The payload is
 * explicit about it — "AWAITING is reachable and means the window has neither a rating nor a triage
 * signal" — and the design's badge builder knows only three states, so the fourth needs a word the
 * product already speaks. That phrase is <b>"Not scored"</b>, on the neutral badge rather than
 * the red one — the same claim the matrix footer, the location sheet, the map callout and the
 * field map's own overlay already make ("unshaded — not scored", "Not scored yet"), where
 * "Awaiting" (this label until the copy pass) never said what was awaited. Every surface that
 * renders this enum now prints one phrase.
 *
 * <p>⚠️ <b>The width was measured, not assumed.</b> {@code .wf-hc-vd} is {@code nowrap} +
 * {@code text-overflow: ellipsis} in a {@code 1fr} grid column, so a longer word here can only
 * ellipsise, never wrap. Measured in Chromium against the running app: "Not scored" is 66px at
 * 11px IBM Plex Mono, and the slot resolves to 252px or wider at every layout the strip
 * produces — 375px phone (day-major, 315px cards), 820px (182px), 1100px (four columns, 252px).
 * Nothing clips. A future card face that narrows that column has to re-check this word.
 */
export const VERDICT_LABEL = {
  WORTH_IT: 'Worth it',
  MAYBE: 'Maybe',
  STAND_DOWN: 'Poor',
  // ⚠️ UNRATED (unrated-verdict-plan.md U2): the weather screen did not rule the window out and no
  // trustworthy rating has called it — a region whose rated sample fails the verdict sample gate,
  // or one with no rating at all, used to wear the triage pass as "Worth it"/"Maybe". It is NOT
  // AWAITING ("nothing was said") and NOT Poor ("the weather said no"): ratings exist or the
  // weather passed, and neither is enough to recommend. 'Too few rated' is the WINDOW/REGION word
  // (card, pill, drilldown rows, rail, grid cell); a SLOT says 'Not rated' (SLOT_VERDICT_LABEL).
  // 13 characters — wider than any other word here (85.8px at the card's 11px/600; the pill's
  // 10px/700 uppercase tracked word is 83–91px), so every NARROW surface prints
  // {@link VERDICT_LABEL_SHORT} and keeps this full word in its accessible name.
  UNRATED: 'Too few rated',
  AWAITING: 'Not scored',
};

/**
 * The per-surface SHORT FORM of each window/region verdict word — the ONE home of the pair
 * (unrated-verdict-plan.md U2, adversarial-review fix F1–F4).
 *
 * <p>Only {@code UNRATED} differs: "Too few" is 7 characters, a truncation that still reads as a count
 * qualifier ("Too few [rated]"). It is deliberately NOT "Unrated", which would be false of a region
 * with 38 ratings. A surface that prints it MUST keep {@code VERDICT_LABEL.UNRATED} in its accessible
 * name — visually-hidden text, or, on the Plan card, the card's own spoken sentence — so the short
 * form shortens the picture and never the claim. Surfaces: the Plan card's value row (everywhere:
 * its column is 83–89px at 320, 640 and a docked 1024, measured), the Map drilldown's row (a 62px
 * grid track), the peek sheet's "Other windows" cell (a 78px flex-none cell) and the window pill
 * at 811px and below (measured: the full word clips the day label at 320–430px and itself at
 * 640–700px). Every other surface prints the full word.
 */
export const VERDICT_LABEL_SHORT = {
  ...VERDICT_LABEL,
  UNRATED: 'Too few',
};

/**
 * The word to DRAW for a descriptor's {@code verdict} on a surface that prints short forms: the
 * short form where one exists, else the descriptor's own {@code label} unchanged (an away window's
 * "Not forecast", or any word this module does not own).
 *
 * @param {?string} verdict the served display verdict, null for an away window
 * @param {string} label the descriptor's own full label
 * @returns {string}
 */
export function shortVerdictLabel(verdict, label) {
  const short = verdict ? VERDICT_LABEL_SHORT[verdict] : null;
  return short && short !== VERDICT_LABEL[verdict] ? short : label;
}

/**
 * The slot-level word for a display verdict — one LOCATION's row, not a region or a window.
 *
 * <p>Read by exactly three consumers: Ask's pick card ({@code askModel.js}), the location sheet's
 * window rows and the Map callout's verdict slot — the last two through
 * {@code locationSheet.unratedSlotWord}, which carries the three-way choice for a slot with no
 * rating ("Not rated" / "Poor" / "Not scored yet"; the Codex review of #1140 caught that this
 * comment once named them while they still hardcoded "Not scored yet"). The popup's spot chips are
 * NOT readers: a chip draws a star badge for a rated spot and nothing at all for an unrated one
 * (no verdict word is ever printed on it), so there is no word to localise there.
 *
 * <p>The two levels share every word but one: a region is "Too few rated" because the SAMPLE is
 * small, which is a claim about a population; a slot is one place with no rating of its own, and
 * "Not rated" is the true thing to say of it (unrated-verdict-plan.md U2). Deliberately a separate
 * map rather than a branch inside {@link VERDICT_LABEL} so a slot-level reader can never pick up
 * the population wording by accident.
 */
export const SLOT_VERDICT_LABEL = {
  ...VERDICT_LABEL,
  UNRATED: 'Not rated',
};

/**
 * Which verdicts carry the confidence channel.
 *
 * <p>Confidence qualifies a <em>recommendation</em>. Decaying a Poor or an Awaiting badge would say
 * "we are unsure it is bad", which is not what the channel means and not a claim the derivation
 * supports. The same gate ships on the since-deleted `VerdictPill`, and the rail already sets its
 * own tile confidence to null on an all-poor day. It also sidesteps a real defect: the neutral
 * badge's border is a token rather than an `rgba()` literal, so it cannot decay with its fill, and
 * a half-decayed badge would look like a rendering bug.
 */
export const CONFIDENCE_VERDICTS = new Set(['WORTH_IT', 'MAYBE']);

/**
 * Which verdicts carry the SERVED confidence tier on the card descriptor, for the field's haze
 * ({@code confidenceScalar}) — {@link CONFIDENCE_VERDICTS} plus {@code UNRATED}. A superset on
 * purpose and NOT a second badge gate: every badge decay / provisional mark tests
 * {@code CONFIDENCE_VERDICTS} itself (see {@code buildWindowCards}).
 */
const HAZE_VERDICTS = new Set([...CONFIDENCE_VERDICTS, 'UNRATED']);

/**
 * The gate's two thresholds (tide-plan-card-plan.md §3 C1 #2, the spec's §2 verbatim).
 *
 * <p>A flat floor of three put a chip on all six cards in the prototype's first build — a caption
 * rather than a signal — so the gate is the SHARE, floored at three: half the coastal pool in reach,
 * never fewer than three regardless of how small that pool is. Tying it to the share also means it
 * tightens sensibly as the reach filter does, rather than a constant count that means less at a wide
 * tier and more at a narrow one.
 */
const TIDE_LIVE_MIN_COASTAL = 4;
const TIDE_LIVE_FLOOR = 3;
const TIDE_LIVE_SHARE = 0.5;

/**
 * The least rating the best-reachable line will name once a neighbour outlier was skipped to reach
 * it (neighbour-guard-plan.md §4 G3). A client twin of the server's
 * {@code NeighbourOutlierGuard.NAMED_FLOOR} (Java): deliberately a fixed 3 on both sides, not the
 * guard's configurable {@code min-rating}, so the BEST BET pick and this line cannot disagree about
 * one window. Change both together.
 */
export const NEIGHBOUR_NAMED_FLOOR = 3;

/**
 * The pool's best-reachable spot, and whether the neighbour guard withheld it.
 *
 * <p>The first spot of the pool carrying a rating that is not a served {@code neighbourOutlier}.
 * The pool arrives ordered by {@code compareSpots} (rating desc, unrated last), so with no outlier
 * this is exactly the head when the head is rated, and null when it is not. If an outlier was
 * skipped and that spot is rated below {@link NEIGHBOUR_NAMED_FLOOR} — or no rated spot remains
 * after the skip — nothing is named and {@code withheld} is true. A filter over a served flag, never
 * a second ranking.
 *
 * @param {Array<object>} pool the reach-gated, pre-floor spots in {@code compareSpots} order
 * @returns {{bestReach: ?object, withheld: boolean}}
 */
export function bestReachOf(pool) {
  const spots = pool || [];
  let skipped = false;
  for (const spot of spots) {
    if (spot?.rating == null) break;
    if (spot.neighbourOutlier === true) {
      skipped = true;
      continue;
    }
    if (skipped && spot.rating < NEIGHBOUR_NAMED_FLOOR) return { bestReach: null, withheld: true };
    return { bestReach: spot, withheld: false };
  }
  return { bestReach: null, withheld: skipped };
}

/**
 * The per-window tide-fit summary — is the coast live on this window, and how well served.
 *
 * <p><b>One pool, three readers (plan §2).</b> Computed over {@code pool} — the SAME reach-gated,
 * pre-rating-floor list the spread histogram and the best-reachable line already read
 * (plan-matrix A10/A11) — so the chip, the histogram and the named spot can never disagree about
 * which locations exist: they never see different lists. Building a second index in the shell and
 * looking the head up was rejected for exactly this reason (tide-plan-card-plan.md §1 #3, §5 #2).
 *
 * <p><b>A new, licensed member of CLAUDE.md's reach-scoped class</b> (Backend-heavy bullet): the
 * pool is per-user (reach), so no servable answer exists on the shared, ETag-revalidated
 * {@code GET /api/briefing} — the identical argument that licensed the histogram and the
 * best-reachable line. `card.tideFit` and {@code windowFirstTideRun.js}'s `tideRun` are the two new
 * members, and only those (tide-plan-card-plan.md §5 #1).
 *
 * <p>{@code coastal} counts every pool spot with a served {@code tideState} — coastal WITH a
 * derivable answer, never "coastal by location type" (§4 #8) — {@code matched} counts those that are
 * {@code tideAligned}, and {@code meanQuality} averages {@code tideQuality} over the matched spots
 * that carry one. It is deliberately NOT an average over every matched spot with a fallback of zero:
 * C0's own phase-log records that a cache payload written before C0 can read {@code tideAligned:
 * true} with a null quality until the next briefing build, and folding that missing figure in as a
 * zero would understate a window that is, in fact, well served. `null` when no matched spot carries
 * one at all (an all-legacy-cache pool, or a coastal pool that matched nothing).
 *
 * @param {Array} pool the card's reach-gated, pre-floor spot pool
 * @returns {{coastal: number, matched: number, live: boolean, meanQuality: ?number}}
 */
function tideFit(pool) {
  // A spot whose match is absent (`tideAligned == null`, e.g. a payload with the per-location match
  // withheld) makes no fit claim, so it is not counted: "0 of N on tide" would be a negative claim.
  const coastalSpots = pool.filter((spot) => spot.tideState != null && spot.tideAligned != null);
  const matchedSpots = coastalSpots.filter((spot) => spot.tideAligned === true);
  const coastal = coastalSpots.length;
  const matched = matchedSpots.length;
  const live = coastal >= TIDE_LIVE_MIN_COASTAL
    && matched >= Math.max(TIDE_LIVE_FLOOR, Math.ceil(coastal * TIDE_LIVE_SHARE));
  const qualities = matchedSpots
    .map((spot) => spot.tideQuality)
    .filter((quality) => quality != null);
  const meanQuality = qualities.length === 0
    ? null
    : qualities.reduce((sum, quality) => sum + quality, 0) / qualities.length;
  return { coastal, matched, live, meanQuality };
}

/**
 * How many of the drawn spots the header may call "within reach", or null when it may not.
 *
 * <p>Three conditions, and all are about honesty rather than presentation. The tier has to carry a
 * threshold — under "Any" nothing was gated, so the word describes no act. Every drawn spot has to
 * carry a drive time, because plan §2.5 rule 1 passes an unknown one through every tier: a set that
 * is part measured and part unknown is not a set that is within reach, and calling it one is the
 * same over-claim as counting a set nothing filtered.
 *
 * <p><b>And no rating floor may be active.</b> The drawn set is then gated on two axes, and this
 * clause names one of them — "6 within reach" over a strip a 4★ floor had trimmed to two counts
 * neither what the reader can drive to nor what is on screen. Withholding it is the same answer the
 * other two conditions give, and the bar's own count line states the page-wide cost instead. The
 * alternative — counting the reach-gated pool rather than the drawn set — was rejected: it would
 * put a number in the header that no card below it accounts for, which is what §6's rule about a
 * claimed count matching what is rendered exists to prevent.
 *
 * @param {Array}   spots       the spots as drawn
 * @param {?number} limitMinutes the active tier's threshold, or null for "Any"
 * @param {?number} minRating    the active floor's threshold, or null for "Any rating"
 * @returns {?number} the count, or null when the sentence is unavailable
 */
function withinReachCount(spots, limitMinutes, minRating) {
  if (limitMinutes == null || minRating != null || spots.length === 0) return null;
  return spots.every((spot) => spot.driveMinutes != null) ? spots.length : null;
}

/**
 * The regions of a window that may be ranked, named or counted — the projector's own filter.
 *
 * <p><b>An all-woodland region is excluded, and that exclusion is the whole of this function.</b>
 * {@code PlanWindowProjector.rank} drops a region holding no non-canopy slot before it ranks
 * anything, unless the entire window is canopy — and {@code BriefingRegion.meanRating} falls back
 * to canopy slots <em>per region</em>, so an all-wood region publishes a mean derived from
 * inverted-polarity scores. Reducing over the raw region list therefore lets a wood rated 4.8 on a
 * misty dawn outrank a sky window at 4.2 and take rank 1, under its own header reading "Poor" and
 * beside a thumbnail that paints no heat there at all (the field is sky-gated — `heatSpots.js`).
 * That is the same defect P1's review caught in the field itself, one surface along.
 *
 * <p>The gate is the presence of a sky slot, never whether one is <em>rated</em> — the projector's
 * rule, copied here as {@code buildWindowSpots} already copies it, and the difference is an
 * ordinary misty sunrise: the fog that leaves every sky slot unrated is the same fog that scores
 * the wood well.
 *
 * <p>Exported at P3 because the open row's rail and band rank, name and count the same set. Two
 * copies of this filter would be two answers to "which regions exist in this window", and the one
 * that got it wrong would be the surface a reader is looking at.
 *
 * @param {?object} es the event summary
 * @returns {Array<object>} the rankable regions, in payload order
 */
export function eligibleRegions(es) {
  const regions = es?.regions || [];
  const slots = regions.flatMap((region) => region?.slots || []);
  // An all-canopy WINDOW keeps its woods, exactly as the projector does: there is no sky answer to
  // prefer, and dropping every region would leave the window unrankable rather than honestly ranked
  // on what was measured.
  const allCanopy = slots.length > 0 && slots.every((slot) => slot.canopy);
  return regions.filter((region) => {
    const regionSlots = region?.slots || [];
    // A region with NO slots is kept: it carries no canopy claim either way, and the projector's
    // own filter passes it (`r.slots().isEmpty() || …anyMatch(s -> !s.canopy())`).
    return allCanopy || regionSlots.length === 0 || regionSlots.some((slot) => !slot.canopy);
  });
}

/**
 * The best-rated REGION in a window, over the same regions the backend's own ranking votes on.
 *
 * <p>One scan, two consumers: {@link topMeanRating} takes its number and {@link topRegionMovement}
 * takes its name and its delta. They must be the same region — a chip reading "▲0.6 in Cumbria"
 * beside a rank derived from a different region's mean is two answers to "which region leads this
 * window" — and one scan is the only way that cannot drift.
 *
 * <p><b>Ties break on the NAME, and the rail agrees.</b> {@code buildRegionRows} ranks the open
 * row's region rail with {@link byMeanThenName} (eligible first, then the mean, then the name) and
 * the Map drilldown ranks with the same comparator, which orders exactly as {@link
 * pickTopEligibleRegion} does — so the thumbnail's chip, the rail's rank 1 eight pixels below and
 * the map's first row name one region, each carrying its own delta. A payload-order tiebreak here
 * would split them. {@code windowFirstRegions.test.js} and {@code mapDrilldown.test.js} pin the
 * agreement on a thin-4.4-versus-sound-3.5 fixture.
 *
 * @param {?object} es the event summary
 * @returns {?object} the leading eligible region record, or null when none carries a finite mean
 */
function topRegion(es) {
  return pickTopEligibleRegion(eligibleRegions(es));
}

/**
 * The argmax itself, over an ALREADY-eligible region list — the single comparator behind every
 * "which region leads this window" answer in the app.
 *
 * <p><b>The name carries the constraint the signature cannot.</b> It was `pickTopRegion` in the
 * first cut of this phase, and an adversarial review charged that a later phase would grep for "top
 * region", find a newly public export, and hand it a raw {@code summary.regions} — silently
 * re-admitting the canopy defect the filter exists to prevent. The parameter cannot enforce it, so
 * the name says it.
 *
 * <p><b>Extracted because a second caller arrived, and the doc above predicted exactly this.</b>
 * {@link topRegion}'s own note ends "Keep them identical, or reconverge both on one helper"; the
 * Map tab's window verdict (`utils/mapVerdict.js`, map-landing-plan.md §3 L1) is the third surface
 * that has to name the same region, and it cannot call {@link topRegion} because its candidate set
 * is scope-limited rather than the whole event summary. So the loop moved here and both callers
 * share it — which is the reconvergence, not a new rule.
 *
 * <p><b>The eligibility filter is the CALLER's job, deliberately.</b> {@link eligibleRegions} takes
 * an event summary, because the all-canopy fallback is a property of the whole window; a
 * scope-limited caller must still apply it against the FULL summary and narrow afterwards, or a
 * window that is all-canopy outside your area but mixed inside it would answer the canopy question
 * differently on the two tabs. `mapVerdict.buildRegionVerdictIndex` does exactly that — it folds
 * over `eligibleRegions(summary)` at index-build time, before any scope is known.
 *
 * <p>Ties break on the region NAME (`localeCompare`), matching `buildRegionRows`' own
 * `mb === ma ? a.name.localeCompare(b.name) : mb - ma` — see {@link topRegion}'s note for the
 * defect that rule exists to prevent.
 *
 * <p><b>Verdict-eligible regions rank first (plan-card-verdict-honesty-plan.md H1).</b>
 * {@code PlanWindowProjector.BY_RANK} orders {@code verdictEligible()} regions ahead of every
 * ineligible one BEFORE it compares means, and the window's verdict WORD is its top region's
 * {@code displayVerdict}. This argmax used to compare means alone, so a thinly-sampled region at 4.4
 * (the verdict-minimum-sample rule withholds its own verdict) out-ranked a sufficiently-sampled
 * region at 3.5 here while the projector crowned the 3.5 one — the card would have printed the 3.5
 * region's word beside the 4.4 region's figure. The wire carries the two booleans the record's
 * {@code verdictEligible()} reads ({@code sampleSufficient}, {@code forcedSample}; absent = not
 * eligible, the same safe direction), so this is a selection among served records, never a
 * threshold. It also tightens {@code mapVerdict}'s scope-limited argmax onto the same order, which
 * is the agreement that file's own doc claims. The projector's further tiebreak — rated-voter
 * count — is not on the wire ({@code scoredLocationCount} is canopy-inclusive, a different
 * population), and needs none here: two regions tied on the served mean print the same figure
 * whichever one the projector picked.
 *
 * @param {Array<object>} regions eligible region records; anything without a finite `meanRating`
 *        is skipped rather than ranked, so an unscored region can never win
 * @returns {?object} the leading region record, or null when none carries a finite mean
 */
export function pickTopEligibleRegion(regions) {
  let best = null;
  for (const region of Array.isArray(regions) ? regions : []) {
    const mean = region?.meanRating;
    if (typeof mean !== 'number' || !Number.isFinite(mean)) continue;
    if (best === null || outranks(region, best)) {
      best = region;
    }
  }
  return best;
}

/**
 * {@code BriefingRegion.verdictEligible()} as the wire carries it — the sample gate passed, or a
 * force-evaluation exemption. Absent on a legacy payload, which reads as NOT eligible.
 *
 * <p>Exported so the region rail and the Map drilldown rank with the SAME test the card's top
 * region is chosen by, rather than a second copy that could drift (F2 of the H1–H3 review).
 *
 * @param {?object} region a served region record, or a rail row carrying {@code sampleSufficient}/
 *        {@code forcedSample} through
 * @returns {boolean}
 */
export function verdictEligible(region) {
  return region?.sampleSufficient === true || region?.forcedSample === true;
}

/**
 * Whether {@code a} ranks above {@code b} in the projector's order (H1): verdict-eligible first,
 * then the better served mean, then the NAME. Both arguments carry a finite mean.
 */
function outranks(a, b) {
  const ea = verdictEligible(a);
  const eb = verdictEligible(b);
  if (ea !== eb) return ea;
  if (a.meanRating !== b.meanRating) return a.meanRating > b.meanRating;
  return String(a.regionName ?? '').localeCompare(String(b.regionName ?? '')) < 0;
}

/**
 * The best REGION mean in a window, over the same regions the backend's own ranking votes on.
 *
 * @param {?object} es the event summary
 * @returns {?number} the best eligible region mean, or null when none carries one
 */
function topMeanRating(es) {
  return topRegion(es)?.meanRating ?? null;
}

/**
 * The mean a verdict word may be printed beside: the region's served mean when the region is
 * verdict-eligible or its word is {@code UNRATED}, else null (silence — a triage STAND_DOWN has no
 * mean standing behind it).
 *
 * @param {?object} scoped the origin's region record when away, else null
 * @param {?object} top the window's top region when not away
 * @returns {?number}
 */
function verdictMeanOf(scoped, top) {
  const region = scoped ?? top;
  if (!region) return null;
  // Two states may print a mean: a verdict-eligible region (the word IS a reading of the mean) and
  // an UNRATED one (unrated-verdict-plan.md U2) — "Too few rated 3.6★" is the honest card: the
  // figure is the evidence the gate withheld a verdict from, and the word says so in the same
  // breath. What stays silent is an ineligible region whose word is a triage STAND_DOWN (or
  // AWAITING): a figure beside "Poor" would contradict the weather call it rests on.
  if (!verdictEligible(region) && resolveRegionDisplay(region) !== 'UNRATED') return null;
  const mean = region.meanRating;
  return typeof mean === 'number' && Number.isFinite(mean) ? mean : null;
}

/**
 * What a card says beside a STAND_DOWN word over a blanked region (plan-card-verdict-honesty-plan.md
 * H4). The visible form is short enough for the mean's own slot ("unsettled", about 54px at
 * 10px, the honesty filter's "Too unsettled to forecast" abridged); the spoken form is the filter's label in full, so the card
 * and the popup's region rail speak one vocabulary.
 */
export const BLANKED_STAND_DOWN_VISIBLE = 'unsettled';
export const BLANKED_STAND_DOWN_SPOKEN = 'too unsettled to forecast';

/**
 * The region a window's verdict WORD came from when no region carries a mean to rank it by — the
 * server's own {@code PlanWindowProjector.BY_RANK} order over mean-less regions: a region with
 * slots ahead of a blanked one, a blanked region the honesty filter worded anything but AWAITING
 * (a real stand-down) ahead of a blanked AWAITING one, then the name. Client twin of the three
 * steps of that comparator that still discriminate once every mean is absent; a selection among
 * served records, never a threshold.
 *
 * @param {Array<object>} regions the eligible region records
 * @returns {?object} the region the word came from, or null for an empty list
 */
function pickMeanlessWordRegion(regions) {
  let best = null;
  const rank = (region) => {
    const blanked = Array.isArray(region?.slots) && region.slots.length === 0;
    return [blanked ? 1 : 0, blanked && resolveRegionDisplay(region) === 'AWAITING' ? 1 : 0];
  };
  for (const region of Array.isArray(regions) ? regions : []) {
    if (!region) continue;
    if (best === null) { best = region; continue; }
    const [a1, a2] = rank(region);
    const [b1, b2] = rank(best);
    const before = a1 !== b1 ? a1 < b1
      : a2 !== b2 ? a2 < b2
        : String(region.regionName ?? '').localeCompare(String(best.regionName ?? '')) < 0;
    if (before) best = region;
  }
  return best;
}

/**
 * Whether the card's verdict word is a weather stand-down over a BLANKED region (plan-card-
 * verdict-honesty-plan.md H4): the word is {@code STAND_DOWN} and the region it came from — the
 * origin's region when scoped, else the window's top region (the one {@link verdictMeanOf} reads),
 * else, with no mean anywhere, the projector's pick among mean-less regions — has an empty served
 * {@code slots} list. The honesty filter empties a region's slots exactly when it blanks it, and
 * since it words an unknown reason AWAITING, a blanked STAND_DOWN can only be weather triage: the
 * card then says so rather than leave "Poor" to be read as a rating. A STAND_DOWN region that still
 * has slots gets no qualifier.
 *
 * @param {string} verdict the verdict the card wears
 * @param {?object} scoped the origin's region record when away, else null
 * @param {?object} es the event summary
 * @returns {boolean}
 */
function verdictBlankedOf(verdict, scoped, es) {
  if (verdict !== 'STAND_DOWN') return false;
  const region = scoped ?? topRegion(es) ?? pickMeanlessWordRegion(eligibleRegions(es));
  return Array.isArray(region?.slots) && region.slots.length === 0;
}

/**
 * The region mean as the card prints it: always ONE decimal (plan-card-verdict-honesty-plan.md H1).
 *
 * <p>{@code BriefingRegion.meanRating} is already rounded to one place server-side, so this adds no
 * precision — it only keeps the place. Always-one-decimal is the point rather than a default: the
 * same card carries {@code bestRating} as a whole star ("best spot 4★"), and "4★" beside "4★" would
 * be two different quantities in one glyph, where "4.0★" is visibly the average. A finite number is
 * required; anything else is silence (null), never a placeholder.
 *
 * @param {?number} mean the served region mean
 * @returns {?string} e.g. {@code "3.5"} / {@code "4.0"}, or null when there is no mean to show
 */
export function formatRegionMean(mean) {
  return typeof mean === 'number' && Number.isFinite(mean) ? mean.toFixed(1) : null;
}

/**
 * The leading region's movement since the previous forecast run — the strip's per-window chip.
 *
 * <p><b>The window's TOP region, not its biggest mover.</b> The chip sits on a thumbnail whose
 * verdict word and star already describe that region, so a delta from somewhere else in the same
 * window would qualify a claim it is not about. It is one figure about one region, and the region
 * is named in the change line under the strip.
 *
 * <p><b>Null and a measured zero are different answers and stay so.</b> The backend publishes
 * {@code meanRatingDelta} only where both sides of the subtraction were scored in the same pair of
 * builds; anything else is absent, and absent renders nothing at all. A real {@code 0.0} — this
 * region did not move — renders its own mark. Collapsing them would make "did not move"
 * indistinguishable from "no previous run to compare against", which is precisely the degrade
 * every other channel on this arm keeps silent.
 *
 * @param {?object} es the event summary
 * @returns {?{regionName: string, delta: number}} the leading region's movement, or null
 */
function topRegionMovement(es) {
  return regionMovement(topRegion(es));
}

/**
 * One region's movement in the shape the chip renders, or null.
 *
 * <p>Extracted so the roster-wide arm and the origin-scoped arm cannot answer the shape question
 * differently — the null-versus-measured-zero rule above is the whole contract, and two copies of
 * it is how one of them loses the distinction.
 *
 * @param {?object} region a served {@code BriefingRegion}
 * @returns {?{regionName: string, delta: number}} its movement, or null
 */
function regionMovement(region) {
  const delta = region?.meanRatingDelta;
  if (typeof delta !== 'number' || !Number.isFinite(delta)) return null;
  return { regionName: region.regionName, delta };
}

/**
 * The origin's own served region record for one window, or null.
 *
 * <p><b>Why the card re-points rather than keeps the window's roll-up.</b> {@code BriefingWindow}'s
 * {@code bestRating}, {@code verdict}, {@code confidence} and the leading region's mean are all
 * roll-ups over the WHOLE roster — the backend knows nothing about the origin, which is client-only
 * by design. Left in place under an away scope they put two populations one gap apart in the same
 * meta row: a header reading {@code Worth it · best spot 5★ · 3 within reach} over a strip whose
 * best card is 2★, because the 5 belongs to a region the reader has just scoped away from. The same
 * mismatch drives the Order control, whose {@code Best} ranking would order six windows by a region
 * outside the scope and print an ordinal for it.
 *
 * <p><b>Nothing is recomputed.</b> Every field taken here is one the backend already serves per
 * region ({@code BriefingRegion.bestRating}, {@code displayVerdict}, {@code meanRating},
 * {@code confidence}, {@code meanRatingDelta}) — the same records the open row's rail and band
 * render. That is the whole reason this is a re-point rather than a client-side max: a max over
 * {@code card.spots} would re-create exactly the aggregation class Phase 3 of the verdict
 * consolidation moved server-side, minus its canopy rule.
 *
 * <p>Matched on region NAME, byte-identically, because that is the key the two payloads share
 * ({@code heatSpots.js}). Null when the window carries no record for the origin's region — an
 * ordinary state, since not every region appears in every window — and the caller then falls back
 * to the window's own figures, which is honest: with nothing served for this region there is
 * nothing scoped to say.
 *
 * @param {?object} es     the event summary
 * @param {?object} origin the origin descriptor, or null for home
 * @returns {?object} the origin region's served record, or null
 */
function originRegion(es, origin) {
  if (!origin) return null;
  return eligibleRegions(es).find((region) => region?.regionName === origin.name) ?? null;
}

/**
 * Builds the window cards for the rendered event set.
 *
 * <p><b>Away days get no card.</b> A travel day still carries slots — the pipeline skips
 * *evaluation*, not collection — so the projector turns them into STAND_DOWN or AWAITING and a
 * naive card list would put a "Poor" card under a rail tile reading "Not forecast". That is a
 * contradiction on one screen, and it is still the rule.
 *
 * <p>What changed at P9 is that the absence is no longer silent. {@code buildPaneItems} in
 * {@code windowFirstAway.js} folds a dashed row back in where the missing windows fall, because the
 * six-event cap is applied <em>before</em> the travel filter — so an away day spends a slot and then
 * vanishes, and the pane's date order skips a day with nothing to say why. This function stays
 * unchanged: it still returns cards only, and the away rows are built from the same event array
 * beside it rather than smuggled through as a fifth card variant.
 *
 * <p><b>The same event array the rail rolled up</b> is the input, so the two can never disagree
 * about which windows exist — which is also what lets the pick badge and the rail's pick flag point
 * at one set.
 *
 * @param {Array}  upcomingEvents [{date, targetType}], already ordered and capped
 * @param {Array}  briefingDays   briefing.days
 * @param {string} todayStr       today's ISO date in Europe/London
 * @param {string} tomorrowStr    tomorrow's ISO date in Europe/London
 * @param {Set}    travelDayDates dates the operator is away
 * @param {?Map}   reachById      per-user drive minutes and distance, keyed by location id. Empty
 *                                until its own request resolves, and empty for the whole session
 *                                for a user with no home postcode — in both cases every spot simply
 *                                renders without its reach line, because a lens with no data is not
 *                                a gate (plan §2.5).
 * @param {object} [lens]         the page-wide lens, both axes. {@code limitMinutes} is the active
 *                                reach tier's threshold and {@code minRating} the active floor's,
 *                                each null when that control gates nothing;
 *                                {@code defaultLimitMinutes} is today's derived default and is what
 *                                marks a spot {@code far}. (The tier and floor IDS used to ride
 *                                along too, because the per-card empty state had to name the
 *                                control it would move and a threshold cannot name a chip. That
 *                                state is page-level since M2 — {@code planConflicts.js} reads the
 *                                ids off the lenses directly — so the thresholds are all this
 *                                needs.) The default gates nothing and marks nothing,
 *                                which is what a caller with no lens should get — never a silent
 *                                gate at some assumed distance. {@code origin} is the frame of
 *                                reference (plan §4.8) — null is home and scopes nothing; a region
 *                                origin narrows every window to that region BEFORE either gate,
 *                                because the page is then about that region rather than about the
 *                                roster. It rides on the lens object rather than as a seventh
 *                                positional argument for the reason the two thresholds already
 *                                do — this signature is at its limit. {@code allowedIds} (S5b-1) is
 *                                the briefing's served {@code allowedLocationIds} as a Set, on a body
 *                                the server SHAPED by the reader's sub-region picks and null on every
 *                                other; it marks the places outside the picks {@code gated} and takes
 *                                them out of the pool.
 * @param {boolean} [picksLocked] true when the reader does not have best bets (LITE): every card's
 *                                {@code pick} is null. Defaults to false so every PRO caller is unchanged.
 * @returns {Array} card descriptors for the matrix and the window popup
 */
export function buildWindowCards(
  upcomingEvents, briefingDays, todayStr, tomorrowStr, travelDayDates, reachById,
  lens = { limitMinutes: null, defaultLimitMinutes: null },
  picksLocked = false,
) {
  const limitMinutes = lens?.limitMinutes ?? null;
  const minRating = lens?.minRating ?? null;
  const origin = lens?.origin ?? null;
  // The served `allowedLocationIds` as a Set, on a body the server SHAPED by the reader's sub-region picks only
  // (S5b-1); null for every Pro, Admin and flag-off reader, which leaves every spot exactly as it was.
  const allowedIds = lens?.allowedIds instanceof Set ? lens.allowedIds : null;
  const live = (upcomingEvents || []).filter((e) => !travelDayDates?.has(e.date));

  const cards = live.map(({ date, targetType }, index) => {
    const day = (briefingDays || []).find((d) => d.date === date);
    const es = (day?.eventSummaries || []).find((e) => e.targetType === targetType);
    const win = es?.window;

    // Lead is TODAY'S first window, not simply the first card. Both terms are load-bearing:
    // `date === todayStr` alone fires twice before dawn (today's sunrise AND its sunset are both
    // upcoming), and `index === 0` alone moves the gold onto tomorrow's sunrise the moment today's
    // last event passes — at which point the rail's gold tile has already gone, so the two surfaces
    // would contradict each other. Together they yield exactly one lead card, or none, and always
    // agree with the rail.
    const lead = index === 0 && date === todayStr;

    // "Tonight" only where it is true. A lead card can be today's SUNRISE, where the word is simply
    // wrong, and the alternative ("This morning") is vocabulary the product speaks nowhere else —
    // §6 bans inventing any. So the warmest word on the screen stays where it is honest, and the
    // day moves into the title everywhere else.
    const kicker = lead && targetType === 'SUNSET' ? 'Tonight' : null;

    // The origin's own served record, when the page is scoped to one region. Everything below that
    // would otherwise describe the whole roster reads from it instead — see `originRegion`.
    const scopedRegion = originRegion(es, origin);
    // A scoped region with no served verdict is AWAITING, not the window's: falling back to the
    // roster's word would put "Worth it" on a card about a region nothing was said about.
    //
    // ⚠️ **Through `resolveRegionDisplay`, not the raw field** — map-landing-plan.md §3 L5 step 6b
    // asked for BOTH raw readers to converge on the helper and only `windowFirstRegions` moved,
    // which left the two disagreeing where before they had at least agreed by both being raw. The
    // helper prefers the served `displayVerdict` and MAPS the legacy triage `verdict` where the
    // cached payload carries only that; reading raw here dropped to `AWAITING` while the region rail
    // beside it — built by `buildRegionRows`, converged at L5 — said `Worth it`. Both render in one
    // `WindowSheetDialog`, the card's word at its head and the rail under it. Found by the
    // cross-phase review on #792 (§4 #39).
    const verdictSource = scopedRegion ? resolveRegionDisplay(scopedRegion) : null;
    const verdict = verdictSource || win?.verdict || 'AWAITING';

    // The attribute rows — the tide row, and whatever channel joins it next. Since M2 no topic is
    // promoted out of the badge list into a row: the popup states each topic once, with its facts,
    // so `windowFirstRows.js` builds one channel and this list has no second half to reconcile.
    const rows = buildWindowRows(win);

    // Derived from the SAME event summary the window projection was, so the strip's top card and
    // the header's `best N★` read one population — see `buildWindowSpots` for the two filters
    // that keep them in step. The gate runs after, so `reachTotal` is the set the lens chose from
    // and the card can say how many it left without either number describing a different thing.
    // The origin's scope runs FIRST, before either lens gate. Away, the page is about one
    // region — "a region is Plan with the origin moved" — so the lens chooses within that region
    // rather than across the roster, and the counts the card prints ("12 spots are further out")
    // are counts of the scope the reader asked for. Applied here rather than inside
    // `buildWindowSpots` so that everything downstream, `allSpots` included, sees one population.
    const allSpots = gateSpotsByOrigin(
      buildWindowSpots(es, reachById, lens?.defaultLimitMinutes ?? null, allowedIds), origin,
    );
    // Reach first, then rating — the order the two counts describe. `reached` is what the floor
    // chose from, so the bar can say "42 of 138" without either number naming a different thing.
    const reached = gateSpotsByReach(allSpots, limitMinutes);
    const spots = gateSpotsByRating(reached, minRating);
    // ⚠️ The pool a Lite reader's counts, spread, thin-sample line, best place and tide fit read is the places
    // INSIDE their sub-regions (S5b-1, B2): a gated place is in neither half of any count. `allSpots`, `reached`
    // and `spots` keep the gated places (the popup lists them after the reader's own; the Map and the eclipse
    // race read them), so only the pool is filtered. With nothing gated `placesOf` hands `reached` back untouched,
    // so a Pro, Admin or flag-off card's `pool` is the very array it always was.
    const pool = placesOf(reached);
    const outsideCount = allSpots.length - placesOf(allSpots).length;
    const reachPick = bestReachOf(pool);

    return {
      key: `${date}:${targetType}`,
      date,
      targetType,
      lead,
      kicker,
      // With a kicker the day is already stated, so the title is the event alone. Without one it
      // carries both, exactly as every non-lead card in the design does.
      when: kicker ? eventTitle(targetType) : `${dayLabelFor(date, todayStr, tomorrowStr)} ${eventWord(targetType)}`,
      // The window's own time is the projection's answer; the slot scan is the fallback for a
      // payload cached before windows existed.
      time: formatTime(win?.eventTime || (es ? getEventTime(es) : null)) || '',
      verdict,
      verdictLabel: VERDICT_LABEL[verdict] || VERDICT_LABEL.AWAITING,
      // Null is "nothing in this window is rated", which is a different statement from a low one:
      // the header omits the star rather than printing a placeholder.
      // ⚠️ Null for EVERY window of a shaped (Lite) body: the server strips `BriefingWindow.bestRating`
      // (`LitePickShaper.shapeWindow`), so null here does NOT say nothing is rated. The strip reads "is anything
      // rated" off the place states for such a body (`hasRatedState`, S5b-1 B1), never off this field.
      bestRating: scopedRegion ? (scopedRegion.bestRating ?? null) : (win?.bestRating ?? null),
      // The best of the window's REGION means, for the Order control's `Best` ranking (plan §2.12
      // and §4.3). Deliberately a different quantity from `bestRating` above, which is one
      // location's score: ranking six windows by a single best spot would put a window with one
      // exceptional location above one where a whole region is good, which is the opposite of what
      // "which window is the best bet" means. Null when nothing in the window carries a mean, and
      // A window with no mean sorts last rather than as a zero — "not scored" and "scored
      // badly" are different statements, the rule `windowFirstRegions.js` states for regions.
      topMeanRating: scopedRegion
        ? (typeof scopedRegion.meanRating === 'number' && Number.isFinite(scopedRegion.meanRating)
          ? scopedRegion.meanRating : null)
        : topMeanRating(es),
      // The mean the card may PRINT beside its verdict word (plan-card-verdict-honesty-plan.md H1,
      // review F1) — `topMeanRating` only when the region it belongs to is verdict-eligible. An
      // INELIGIBLE region (sample gate failed, no force-evaluation exemption) is still ranked and
      // still carries a served mean, but its `displayVerdict` is the weather-triage fallback, not a
      // reading of that mean — so "Worth it 2.0★" would print a figure the word does not come from.
      // `topMeanRating` stays untouched for the Order control; this is the honesty channel. The
      // away arm tests the SCOPED region, which is the one whose word `verdict` above wears.
      verdictMean: verdictMeanOf(scopedRegion, scopedRegion ? null : topRegion(es)),
      // True when the word is a STAND_DOWN over a region the honesty filter blanked (empty `slots`):
      // the card prints `unsettled` beside "Poor" in the mean's own slot (H4). Read from the
      // same region `verdictMean` reads, so the qualifier and the word cannot describe two regions.
      verdictBlanked: verdictBlankedOf(verdict, scopedRegion, es),
      // The same region `topMeanRating` names, carrying how far it has moved since the previous
      // forecast run. Null — never a zero — when there is nothing to compare against; see
      // `topRegionMovement` for why the two must stay distinguishable.
      movement: scopedRegion ? regionMovement(scopedRegion) : topRegionMovement(es),
      // The window's leading region, for the strip's thumbnail (field-geography plan §2.3). The
      // SAME argmax `movement` reads two lines up — never a fresh one, and never recomputed from the
      // heat-spot catalogue (backend-heavy rule) — so the brightened area name and the change line's
      // named mover can never point at two different regions. Away re-points like every sibling
      // field here; null when nothing in scope carries a mean, which is a deliberate deviation from
      // the prototype's seeded `reduce` (that would brighten the first region on an all-null window).
      hotRegionName: scopedRegion
        ? (typeof scopedRegion.meanRating === 'number' && Number.isFinite(scopedRegion.meanRating)
          ? (scopedRegion.regionName ?? null) : null)
        : (topRegion(es)?.regionName ?? null),
      // The TOP REGION's confidence. Through P14 this was "the single render site", because the
      // retired day rail derived a day-level tier and deliberately rendered nothing from it. That is
      // no longer true and the change is deliberate: the heat strip reads this same field per window
      // and feeds it to the kernel's haze through `confidenceScalar`, so the picture and the badge
      // decay by ONE number (plan D3). Two renderings of one value, never two derivations.
      // Scoped too, and this one is not merely a label: the strip and the row map feed it to the
      // kernel's haze through `confidenceScalar`, so a roster-wide tier would paint a field drawn
      // for one region at another region's certainty — D3's "one number" broken by two.
      //
      // ⚠️ The HAZE and the BADGE are different channels and gate differently (unrated-verdict-plan.md
      // U2, review F5). Poor and Awaiting carry null (the haze then falls back to the horizon's
      // inferred tier, as it always has). UNRATED keeps the server's tier — an ineligible region is
      // floored LOW there, and a field drawn for a window nothing has called should be the hazy one,
      // not the horizon's MEDIUM — while the BADGE decay and `ProvisionalMark` stay gated on
      // `CONFIDENCE_VERDICTS` at their own mounts (the popup's badge, the grid cell, the since-deleted `VerdictPill`),
      // so "Too few rated" is never also a faded or marked word: it already says the provisional thing.
      confidence: HAZE_VERDICTS.has(verdict)
        ? ((scopedRegion ? scopedRegion.confidence : win?.confidence) ?? null)
        : null,
      spots,
      // The set BEFORE the reach gate, carried for P11's drill-down — which owns its own reach
      // control and must be able to widen past the bar's tier, so handing it the gated list would
      // give it a control with nothing to reveal. It is the same array `reachTotal` counts, so the
      // sheet and the strip footer can never describe two different populations.
      allSpots,
      /**
       * Whether the reach axis COULD act on this window — is there a drive time anywhere in scope.
       *
       * <h3>⚠️ It is a claim about the READER, not about this window's survivors</h3>
       *
       * <p>An unknown drive passes every tier (plan §2.5: absence means unknown, never out of
       * reach), so a reader with no home postcode has every gate open and {@code pool === allSpots}
       * whatever the tier says. Every surface that then prints "within reach" is naming a filter
       * that never ran — §6 clause 7's own sentence, "no count describes a set that was never
       * filtered". M5 fixed that in the popup's footer and an adversarial review found the same
       * claim still standing on five other surfaces, so the answer is computed ONCE here and folded
       * outward like everything else on this descriptor.
       *
       * <p>Measured over {@code allSpots} — the origin scope BEFORE the reach gate — rather than
       * over the drawn set, deliberately: asking the survivors would make the wording flicker by
       * window, printing the reach clause on one card and withholding it on its neighbour, which is
       * a stranger claim than one that is simply true of the account. It is also a WEAKER test than
       * {@code poolWithinReach}, which needs EVERY spot measured: that one licenses a COUNT ("9 in
       * reach"), this one only licenses naming a filter that did something.
       */
      reachMeasured: allSpots.some((spot) => spot?.driveMinutes != null),
      // The active reach tier's threshold in minutes, null under "Any" (plan-card-verdict-honesty-plan.md
      // H2). Carried so the Best row can say "beyond 45 min" in the tier's own words when every rated
      // spot of the verdict's region was gated out: it is the lens's served-to-us input, not a
      // derivation, and null is what keeps "beyond" unsayable when no tier acted.
      reachLimitMinutes: limitMinutes,
      // The set the reach gate LEFT, before the rating floor — the matrix's spread histogram and
      // its best-reachable line are both measured over this (plan-matrix A10/A11).
      //
      // ⚠️ It is `reached` itself rather than a copy, and under the "Any" tier `gateSpotsByReach`
      // returns its input untouched — so `pool === allSpots` there, the SAME array `reachTotal`
      // counts and `WindowSpotSheet` browses. Nothing downstream mutates any of them (the sheet
      // sorts a `slice()`), and nothing may start: an in-place sort in a future consumer would
      // silently reorder the sheet's list from the card's own chip placer.
      //
      // ⚠️ The floor is deliberately NOT applied, and the design says why: "an average of things
      // that already passed a 4★ filter always reads 4-something". The histogram's whole job is to
      // show the shape of what is out there, which a floor would flatten to one band.
      //
      // The same array `reachedTotal` counts, so a count and a picture of the same set cannot
      // describe two different populations.
      pool,
      // How many of this window's places lie outside the reader's sub-regions, and whether the body was shaped by
      // them at all (S5b-1). Present ONLY on a shaped body's card, so every Pro, Admin and flag-off card is the
      // object it always was. The popup's "· {m} outside" and the strip's Best row read them; the places
      // themselves stay on `allSpots`/`spots`, flagged `gated`.
      ...(allowedIds ? { outsideCount, picksScoped: true } : {}),
      // Is the coast live on this window, and how well served — over the SAME `pool` above, never a
      // second index (§1 #3, §2). Named `tideFit`, never `tide`: that name is taken by the served
      // `BriefingWindowTide` below, which the Map tab's strip forwards untouched (T6, §1 #3).
      tideFit: tideFit(pool),
      // The best location this reader could actually drive to for this window, NAMED.
      //
      // ⚠️ It is `bestReachOf(reached)`: the pool's first rated spot that is not a served
      // `neighbourOutlier` (neighbour-guard-plan.md G3), over the PRE-floor pool `reached`, never
      // `spots`. The pool arrives ordered by `compareSpots` (rating desc, then drive, then name)
      // from `buildWindowSpots` and both gates are order-preserving filters, so with no outlier
      // this is the head and nothing else — there is no second "pick the best" comparator here, only
      // a skip of the server's flag plus the client twin of the server's named floor
      // (`NEIGHBOUR_NAMED_FLOOR`), applied only after a skip.
      //
      // Null when the head carries no rating, which is the ordinary state on a far-horizon window
      // (T+4 is never evaluated). `compareSpots` sorts unrated last, so a rated head means every
      // rating in the pool was compared — and an UNRATED head means nothing in the pool is rated
      // at all, and the order that chose it was alphabetical. Naming that spot "the best you could
      // reach" would claim a ranking that never ran, so the caller says "not scored yet" instead.
      bestReach: reachPick.bestReach,
      // True when a neighbour outlier was skipped and the next rated spot is below the named floor
      // (or there is none): the card then says "no clear pick" rather than naming a place or
      // claiming nothing is scored. Absent from the pool's histogram and every count — the outlier
      // keeps its star everywhere a star is shown.
      bestReachWithheld: reachPick.withheld,
      // How many the lens chose from. Equal to `spots.length` whenever nothing was gated, which is
      // exactly when the strip footer keeps P6's plain count rather than the design's "N of M".
      reachTotal: allSpots.length - outsideCount,
      // How many survived reach alone — the rating floor's own denominator, summed across the page
      // into the bar's "42 of 138". A scalar beside `pool`, which is the same set as an array: this
      // is the number the bar states and `pool` is what the matrix draws, so they count one list by
      // construction. (Until M1 this comment read "nothing renders this set, only counts it", which
      // was true while the array did not exist.)
      reachedTotal: pool.length,
      // The header's claim, or null when the word "reach" would over-claim — see the module comment.
      withinReachCount: withinReachCount(placesOf(spots), limitMinutes, minRating),
      // What the card draws in place of its strip, or null when it draws a strip — or when the
      // window had no spots at all, which is a card the lens never touched and must not carry a
      // line about it.
      rows,
      // The raw served `BriefingWindowTide`, carried alongside `rows` above rather than only
      // inside it — `rows` is `buildWindowRows(win)`'s PRESENTATION form (segments/tones for the
      // Plan tab's tide fact row), and the Map tab's tide strip (tide-window-plan.md T6) needs the
      // object itself: its curve, positions and extremes, none of which `rows` carries forward.
      // Without this, `WindowFirstMapPane`'s `heat.windows` mapper (built from `buildHeatStripCards`,
      // itself folded from THIS card) has no `tide` field to copy onto the EV row `mapEvents.js`
      // reads from — `MapView`'s tide strip would receive a served, correct `window.tide` from
      // `GET /api/briefing` and never see it, since the whole chain between the payload and the map
      // runs through this card. Found in browser verification: T3's unit tests fed `solarRow` a
      // hand-built `served` object that already had `.tide`, so the gap in this real pipeline never
      // surfaced until the strip was actually rendered against a live payload.
      tide: win?.tide ?? null,
      // ⚠️ ONE badge list now, and the before/after pair is gone with the surface that needed it.
      // Through M1 there was also a `badges` holding the post-promotion remainder — what the card
      // header drew after `buildWindowRows` had turned some of them into snow attribute rows. The
      // header and the promotion both died at M2 (the popup's topic rows state every topic once,
      // facts included), so the remainder had no reader, and a second badge list nothing renders is
      // how two surfaces come to disagree about which topics a window has.
      //
      // ⚠️ `topRarityRank` — the payload's own `min(rarityRank)` for the window — was carried here
      // beside it and went at M5 with the promoted strip, its only consumer. The backend field
      // itself was removed in the dead-code sweep phase D (nothing read it), so there is no
      // window-level minimum to read. Every surface that orders topics today
      // (`windowFirstTopics.windowTopics`, `windowFirstRows.buildWindowRows`) ranks the BADGES on
      // their own `rarityRank`, and a window-level minimum kept alive beside them would be a second
      // answer waiting to disagree with the first.
      allBadges: win?.badges || [],
      // ⚠️ Withheld when the pick names a region the origin has scoped away. The pick is the
      // forecast's own recommendation over the whole roster, and its badge opens a dialog naming
      // that region — on a page framed to the Lakes, a `BEST BET` flag for Northumberland (which
      // the matrix card folds straight off this field) recommends somewhere the reader has just said
      // they are not. Withheld rather than re-derived: choosing a "best in this region" would be a
      // client-side pick, and picks are server-owned (plan §2.12).
      // Best bets are Pro (pro-features-gate-plan.md §7): a locked reader gets no pick even when the payload
      // still carries one (the server strips it in P2). Every downstream surface reads this one field.
      pick: !picksLocked && win?.pick && (!origin || win.pick.regionName === origin.name)
        ? {
          kind: win.pick.kind === 'BEST' ? 'best' : 'also',
          regionName: win.pick.regionName,
          headline: win.pick.headline,
          detail: win.pick.detail || null,
          locationName: win.pick.locationName || null,
          locationId: win.pick.locationId ?? null,
        }
        : null,
    };
  });

  // Belt-and-braces: an Also with no surviving Best badges a runner-up to a plan nobody on screen
  // can see. This explicit check is now the ONLY protection — the day rail had an emergent one (a
  // RAIL_MAX_DAYS=4 date window narrow enough that both picks' dates almost always fell inside it)
  // and the rail went at P2, taking it with it. It was never a rule this file could lean on. The backend's own pick pool is scoped to the
  // rendered event set (plan-verdict-consolidation-plan.md Phase 1), so this should be a no-op
  // against a fresh payload — it exists for the payload that is not fresh: an SWR-cached response
  // can sit for up to 12h, during which the window the BEST pick named can fall out of
  // `upcomingEvents` (passed, or pushed beyond the horizon by newer events) while the Also's window
  // survives. Kept even after the backend fix, since staleness is a client-side fact no backend fix
  // can reach.
  if (!cards.some((c) => c.pick?.kind === 'best')) {
    return cards.map((c) => (c.pick?.kind === 'also' ? { ...c, pick: null } : c));
  }
  return cards;
}


/**
 * The badge channel a hot topic's type belongs to.
 *
 * <p>Five channels ship as tokens; anything unrecognised takes the neutral base badge rather than
 * being forced into the nearest one, because a badge's colour names its channel and a wrong colour
 * is a wrong claim. New topic types are therefore additive and fail quietly.
 *
 * <p>ECLIPSE and LUNAR_ECLIPSE are matched FIRST and exactly, ahead of the substring tests below
 * them. Order matters here in a way it does not for the others: the tests are substring matches on
 * the whole type string, so a future kind named for an eclipse of a different body — a lunar
 * eclipse at a coastal spot, say — must not be captured by whichever loose test happens to hit
 * first. `LUNAR_ECLIPSE` shares the solar eclipse's channel by design (one channel, two types —
 * lunar-eclipse-plan.md §2.1, §4 #1): every registry in this codebase is keyed on the type string
 * already, so a shared `body` field would be more plumbing for the same touch count, and a fake
 * type such as `LUNAR_ECLIPSE_TIDE` still falls through to the substring arms below because it is
 * not an exact match — it stays on the `tide` channel, never `eclipse`.
 *
 * @param {string} type the topic type from the payload
 * @returns {'eclipse'|'tide'|'nlc'|'aurora'|'snow'|'plain'} the badge channel
 */
export function badgeChannel(type) {
  const t = String(type || '').toUpperCase();
  if (t === 'ECLIPSE' || t === 'LUNAR_ECLIPSE') return 'eclipse';
  if (t.includes('TIDE') || t.includes('SURGE')) return 'tide';
  if (t.includes('NLC') || t.includes('NOCTILUCENT')) return 'nlc';
  if (t.includes('AURORA')) return 'aurora';
  if (t.includes('SNOW') || t.includes('FROST')) return 'snow';
  return 'plain';
}
