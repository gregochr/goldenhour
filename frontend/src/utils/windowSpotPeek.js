import { firstClause } from './firstClause.js';
import { leaveBy } from './leaveBy.js';
import { lookupBriefingScore } from './briefingScoreIndex.js';

/**
 * The spot peek's geometry and its content gate.
 *
 * <p>Plan §5a`:692` says P10′ works by "reusing {@code previewPlacement}, the fixed-position escape
 * hatch and the open/hold/dismiss timers" — read as <b>copying</b> rather than importing, since the
 * numbers below are re-derived rather than transplanted, the panel they place being a different
 * height (see {@link PEEK_ESTIMATED_HEIGHT}).
 */

/** Panel width in px. Mirrored by `.wf-peek` in `index.css`; keep the two in step. */
const PEEK_WIDTH = 280;

/** Gap between the anchor card and the panel, in px. The pointer has to cross it — see the hook. */
const PEEK_GAP = 10;

/** Inset from the strip's own left and right edges, so a peek reads as part of the card. */
const STRIP_EDGE_MARGIN = 10;

/** The rotated square that tethers the panel to its card. Must equal `.wf-peek::after`'s size. */
const ARROW_SIZE = 11;

/** How far the arrow is held clear of the panel's rounded corners, in px. */
const ARROW_INSET = 16;

/** Smallest distance the panel may sit from a viewport edge when the strip's rect is unknown. */
const VIEWPORT_MARGIN = 8;

/**
 * Height used only to choose which SIDE of the card the panel goes on.
 *
 * <p><b>Measured for this panel, not assumed.</b> A smaller estimate licensed by "bounded by
 * construction (a stars row, one clause, one prompt line)" would under-estimate here, since this
 * panel restores the two score bars — and an under-estimate makes the flip choose 'below' when
 * below cannot in fact hold it. Measured on the running app at
 * 1280×900, both scores present: 23px of padding (11 + 12), a 23px stars row, a 54px score block
 * over an 8px lead, a 19.375px clause line over an 8px lead, and a 17px prompt over an 8px lead —
 * <b>163px</b> with a one-line clause, and <b>202px</b> at the three lines a 252px content box
 * allows. 220 rounds that up, because the whole value of an estimate is the slack: over-estimating
 * flips to the side with more room, which is the safe direction, while under-estimating pins the
 * panel to a side that cannot hold it.
 *
 * <p>Still an estimate rather than a measurement: measuring exactly
 * would mean rendering hidden, reading back and re-rendering — a second paint to choose between two
 * answers that are both legible.
 */
const PEEK_ESTIMATED_HEIGHT = 220;

/**
 * A score-bar value's own bounds — the model's 0–100 integer output range. Mirrors
 * {@code locationSheet.js}'s {@code boundedScore} exactly, so an out-of-range or fractional value is
 * discarded here the same way it is on the sheet's side of the same served row. Without this the two
 * drill-down layers could disagree on a malformed row: the sheet would show silence (bounded to
 * null) while the peek rendered a bar clamped to a number the pipeline never produced — the "same
 * snapshot, two reductions, one shows a number the other refuses" defect class this project has hit
 * before (Contracts §3).
 */
function boundedScore(value) {
  return Number.isInteger(value) && value >= 0 && value <= 100 ? value : null;
}

/**
 * Places the peek under the hovered spot card, tethered to it by an arrow.
 *
 * <p>Under, not beside: the panel belongs to one card, and a tooltip hanging off a card's flank in a
 * 3.5-across filmstrip points at the gap between two of them. It is clamped to the <em>strip's</em>
 * own edges rather than the viewport's, so a peek off the last card in a window still reads as part
 * of that window card instead of drifting out over the page.
 *
 * <p>Coordinates are viewport-relative, which is what {@code getBoundingClientRect} already returns
 * and what a {@code position: fixed} panel wants — they must be given no scroll compensation. The
 * corollary is that they go stale the instant anything scrolls, which is why {@code useSpotPeek}
 * dismisses on scroll rather than trying to follow the anchor. Same trade the shared popover host
 * (since deleted as dead code) made, and stated there too.
 *
 * @param {DOMRect}  anchorRect the hovered spot card's bounding box
 * @param {?DOMRect} stripRect  the strip wrapper's bounding box, when it is mounted
 * @returns {{placement: string, arrowLeft: number, position: Object}} the panel's placement
 */
export function spotPeekPlacement(anchorRect, stripRect) {
  const leftBound = (stripRect?.left ?? VIEWPORT_MARGIN) + STRIP_EDGE_MARGIN;
  const rightBound = (stripRect?.right ?? (window.innerWidth - VIEWPORT_MARGIN)) - STRIP_EDGE_MARGIN;
  const centre = anchorRect.left + anchorRect.width / 2;
  const maxLeft = rightBound - PEEK_WIDTH;
  // A strip narrower than the panel has no range to clamp into, and the answer is the left bound.
  // That falls out of the arithmetic rather than needing a guard: when `maxLeft < leftBound` the
  // inner `Math.min` can only return something below `leftBound`, so the outer `Math.max` floors it
  // there anyway — no explicit `maxLeft >= leftBound` guard is needed, confirmed by mutation
  // testing: a surviving mutant on that condition would prove it dead, and none does.
  const left = Math.max(leftBound, Math.min(centre - PEEK_WIDTH / 2, maxLeft));

  // Flip above only when below genuinely cannot hold it AND above holds it better — a card near the
  // bottom of a short window would otherwise flip for the sake of a few pixels.
  const roomBelow = window.innerHeight - VIEWPORT_MARGIN - (anchorRect.bottom + PEEK_GAP);
  const roomAbove = anchorRect.top - PEEK_GAP - VIEWPORT_MARGIN;
  const above = roomBelow < PEEK_ESTIMATED_HEIGHT && roomAbove > roomBelow;

  // The arrow points at the card's centre, held clear of the shell's corners. Its `left` is the
  // rotated square's top-left corner, so half a side comes back off.
  const tip = Math.max(ARROW_INSET, Math.min(centre - left, PEEK_WIDTH - ARROW_INSET));

  return {
    placement: above ? 'above' : 'below',
    arrowLeft: tip - ARROW_SIZE / 2,
    position: above
      ? { left, bottom: window.innerHeight - anchorRect.top + PEEK_GAP }
      : { left, top: anchorRect.bottom + PEEK_GAP },
  };
}

/**
 * What the peek would show for one spot, or null when it would show nothing new.
 *
 * <h2>The gate is "carries something the card does not", not "has a summary"</h2>
 *
 * <p>Without score bars, a summary-less peek would restate what the reader can already see on the
 * card and add nothing but a prompt. P10′ puts the bars back, so that reasoning no longer applies —
 * the surviving <em>principle</em> is that a peek must carry something the card does not, and under
 * it a spot with scores but no sentence has earned one.
 *
 * <p>Rating, drive time and leave-by are therefore not part of the gate: all three are printed on
 * the card. The clause and the two score bars are, and a slot with none of the three gets no peek
 * at all.
 *
 * <p><b>Leave-by is computed here rather than taken from the card, and it is the one formatted
 * string in an otherwise raw return.</b> The panel is built from the descriptor rather than from
 * the card's DOM, so there is nothing to take it from; the alternative — passing
 * {@code solarEventTime} through and calling {@code leaveBy} inside {@code WindowSpotPeek}, the way
 * {@code driveMinutes} is formatted there — would put the same two-field derivation in a third
 * place for no gain. {@code clause} is already a pre-derived string on the same reasoning. The two
 * surfaces cannot disagree because both call one pure function on the same two fields of the same
 * object; {@code rating} is taken from the descriptor for the same single-source reason.
 *
 * <p><b>Triage is deliberately not a fourth key.</b> The index also carries {@code triageReason} and
 * {@code triageMessage}, and a slot that was triaged out has neither scores nor a summary — so it
 * falls through to no peek. That is the intent: a triage message states why the pipeline did not
 * look, which is a fact about the run rather than about the sky, and the strip has no vocabulary for
 * it. The card already says the same thing by omitting its rating badge.
 *
 * <p><b>The clause is computed here rather than in the component</b>, not left as a raw summary for
 * the component to truncate on render. That is what makes the gate provably the thing rendered: a
 * summary of pure whitespace, or one that {@code firstClause} reduces to nothing, would otherwise
 * pass a {@code summary != null} gate and then draw an empty paragraph.
 *
 * <p>Everything read here is already in memory. That is the whole point: opening a peek must cost
 * nothing, or a pointer sweeping a strip becomes a burst of requests.
 *
 * <p>{@code fierySky}/{@code goldenHour} are bounded 0–100 integer, discarding anything outside that
 * range to null — the same rule {@code locationSheet.js}'s {@code buildScoreIndex} applies to the
 * location sheet's own copy of these two fields, off the same served row. Without matching bounds a
 * malformed row could show a bar here that the sheet, one drill-down step deeper, shows nothing for.
 *
 * @param {object}  spot       the spot descriptor from {@code buildWindowSpots}
 * @param {string}  date       the window's date
 * @param {string}  targetType SUNRISE or SUNSET
 * @param {?Map}    scoreIndex briefing-score index, from {@code buildBriefingScoreIndex}
 * @returns {?{rating: ?number, driveMinutes: ?number, leaveBy: ?string, fierySky: ?number,
 *          goldenHour: ?number, clause: ?string}} the peek's content, or null when there is
 *          nothing to show
 */
export function resolveSpotPeek(spot, date, targetType, scoreIndex) {
  const score = lookupBriefingScore(scoreIndex, spot.locationName, date, targetType);
  const fierySky = boundedScore(score?.fierySkyPotential ?? null);
  const goldenHour = boundedScore(score?.goldenHourPotential ?? null);
  const clause = firstClause(score?.summary ?? null);
  if (fierySky == null && goldenHour == null && clause == null) return null;
  return {
    // The RATING is the card's own, never the score index's — a peek that disagreed with the card
    // it hangs off is the exact failure `CloseToHomeService`'s single-source contract exists to
    // prevent, and `buildWindowSpots` has already discarded a rating outside 1–5 that the index
    // would still hand back.
    rating: spot.rating ?? null,
    driveMinutes: spot.driveMinutes ?? null,
    // Computed AFTER the gate, deliberately: a leave-by time is on the card, so a peek carrying
    // nothing else would restate what the pointer is already resting on.
    leaveBy: leaveBy(spot.solarEventTime, spot.driveMinutes),
    fierySky,
    goldenHour,
    clause,
  };
}
