import React, { useCallback, useMemo } from 'react';
import PropTypes from 'prop-types';
import {
  aspect, bbox, clamp, drawGeo,
} from '../utils/heatField.js';
import { useHeatCanvas } from '../hooks/useHeatCanvas.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import { badgeChannel } from '../utils/windowFirstCards.js';
import { beyondRegions, GLANCE_MINUTES } from '../utils/planningArea.js';
import { scopeRegions, scopeSpots } from '../utils/planOrigin.js';
import { activeStops, rampRgb, rgb } from '../utils/scoreRamp.js';
import { spotBadgeStyle } from '../utils/windowFirstSpots.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';
import { topMovers } from '../utils/movement.js';
import { buildWindowMatrix, MATRIX_ROW } from '../utils/windowFirstMatrix.js';
import {
  buildSpread, poolPhrase, poolWithinReach, spreadBars, spreadTitle, unratedPhrase,
} from '../utils/windowFirstSpread.js';
import { buildTopicIndex, windowTopics } from '../utils/windowFirstTopics.js';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { leaveBy } from '../utils/leaveBy.js';

/**
 * The thumbnail frame's aspect clamps.
 *
 * <p>Not kernel behaviour — {@code aspect()} has no clamps of its own — so they are this
 * component's constants and this component's boundary tests.
 *
 * <p><b>0.78–1.0, tightened from P2's 0.85–1.22 by the v3 design.</b> The strip's six cards sat in
 * one row and could afford to be tall; the matrix stacks two rows of them, and every pixel of
 * thumbnail height is paid for twice down the column and again by the four value rows beneath each
 * canvas. Capping at 1.0 means a card's picture is never taller than it is wide, which is what
 * keeps two rows of cards plus the legend, change and beyond lines inside one screen.
 */
export const THUMB_ASPECT_MIN = 0.78;
/** @see THUMB_ASPECT_MIN */
export const THUMB_ASPECT_MAX = 1.0;

/** Grid step, radius floor, radius factor and blur — the prototype's thumbnail dials (plan §4.3). */
const THUMB_GRID = 4;
const THUMB_RADIUS_MIN = 10;
const THUMB_RADIUS_FACTOR = 0.155;
const THUMB_BLUR = 2.4;
const THUMB_LINE = 0.5;

/**
 * The strip's own measurement floor, handed to {@link useHeatCanvas}.
 *
 * <p><b>26, not 21, and the extra five pixels are the height gate.</b> {@code drawGeo} tests BOTH
 * dimensions ({@code !(w > 20) || !(h > 20)}) while this measures width alone, and the height is
 * {@code width × frameAspect} with the aspect floored at {@link THUMB_ASPECT_MIN}. At a width just
 * above the floor the measurement passes, the height lands at 20 or below, and {@code drawGeo}
 * declines with the retry budget already spent — a permanently blank canvas from a gate the retry
 * cannot see.
 *
 * <p>⚠️ <b>It is derived from the aspect floor and MOVED WITH IT.</b> At P2's floor of 0.85 it was
 * 25; the v3 clamp is 0.78, where 25 × 0.78 = 19.5 and the height gate fires. The derivation is
 * {@code ceil(20 / aspectFloor)} = {@code ceil(25.64)} = <b>26</b> — and it works because the hook's
 * own test is STRICT ({@code !(width > minPx)}), so the smallest width that ever reaches
 * {@code drawGeo} is 27 and {@code round(27 × 0.78) = 21 > 20}. Anyone re-tuning
 * {@link THUMB_ASPECT_MIN} must re-derive this in the same edit.
 *
 * <p>Unreachable at any supported viewport (a 26px cell needs a ~120px container), so it closes a
 * latent mismatch rather than a live defect. The hook carries the general form of the rule as well
 * ({@code MIN_CANVAS_PX}); this constant is what makes it unreachable here, and is the boundary
 * this component's own tests pin.
 */
const MIN_THUMB_PX = 26;

/**
 * The footer's ramp bar, built from the ramp module's own active stops.
 *
 * <p>A JS-derived value rather than a CSS gradient literal, for the reason `scoreRamp.js` exists at
 * all: the canvas is painted from these numbers, and a hand-written gradient beside it is a second
 * copy that can drift with nothing failing. A function, called from the render body, rather than a
 * module-level constant: it reads `scoreRamp`'s active mode, which can change after this module has
 * already loaded (`setMode` is a preference switch, not a load-time constant), so a value computed
 * once at import would freeze on whichever ramp was live at first import and never repaint.
 */
function rampGradient() {
  const stops = activeStops();
  return `linear-gradient(90deg, ${stops
    .map((stop, index) => `${stop.hex} ${Math.round((index / (stops.length - 1)) * 100)}%`)
    .join(', ')})`;
}

/** The planning-area threshold in whole hours, for the beyond line's own sentence. */
const GLANCE_HOURS = GLANCE_MINUTES / 60;

/**
 * The frame every thumbnail is projected into, clamped.
 *
 * <p>Exported for its boundary tests: the clamps are this component's, not the kernel's, and a
 * frame outside them is exactly what a coverage change produces.
 *
 * @param {object} fitTo a corner MultiPoint from {@code bbox}
 * @returns {number} height / width for the thumbnail canvases
 */
export function thumbAspect(fitTo) {
  return clamp(aspect(fitTo), THUMB_ASPECT_MIN, THUMB_ASPECT_MAX);
}

/**
 * The tint class for a served verdict, or null for the two states that take none.
 *
 * <p>⚠️ <b>A lookup on the served enum, never a threshold.</b> The design derives its own class
 * from an average ({@code ≥3.7} → {@code vg}, {@code ≥2.8} → {@code vm}); plan §3 rule 1 and A1
 * reject that outright, because the window's verdict is already the top region's band and a client
 * threshold would be a second, quieter answer to a question the backend owns. This function must
 * stay a map — a comparison operator appearing in it is the defect.
 *
 * <p>AWAITING and an away window take no tint. Neither is a judgement about the sky: one means the
 * window has neither a rating nor a triage signal, the other that nothing was forecast at all, and
 * a coloured card would read as one of the three grades.
 *
 * @param {?string} verdict the served {@code DisplayVerdict}
 * @returns {?string} `vg`, `vm`, `vp`, or null
 */
export function verdictTint(verdict) {
  return VERDICT_TINT[verdict] ?? null;
}

/** @see verdictTint */
const VERDICT_TINT = {
  WORTH_IT: 'vg',
  MAYBE: 'vm',
  STAND_DOWN: 'vp',
};

/**
 * The spoken form of a rating, for the card's one visually-hidden sentence.
 *
 * <p>The visible chip is {@code 4★}, and a star glyph is an icon rather than a word — the rule
 * this component already applies to the movement arrow and the sun glyph.
 */
function starsSpoken(rating) {
  return `${rating} star${rating === 1 ? '' : 's'}`;
}

/**
 * What the card's third row says about the best place this reader could actually drive to.
 *
 * <p><b>One function, three states, and the visible text and the spoken text are built together</b>
 * — the same discipline the accessible sentence has followed since P2, for the same reason: two
 * derivations of one claim are two chances to disagree about it.
 *
 * <ul>
 *   <li><b>A rated head</b> — the rating takes the label column in its own ramp colour and the name
 *       takes the whole value column, which is what lets a long name wrap to two lines rather than
 *       ellipsing.</li>
 *   <li><b>An empty pool</b> — nothing is within reach, which is the lens biting or a scope with
 *       nothing in it. The design's own copy.</li>
 *   <li><b>A pool with nothing rated</b> — the ORDINARY state on a far-horizon window, since T+4 is
 *       never evaluated. It must not read as "nothing in reach" (there are places, they are simply
 *       unrated) and it must not name the head as "best" (the order that chose it was alphabetical
 *       — {@code compareSpots} sorts unrated last, so an unrated head means no rating was compared
 *       at all). "Not scored yet" is the strip footer's own existing word for this state.</li>
 * </ul>
 *
 * @param {object} card the thumbnail descriptor
 * @returns {{rating: ?number, label: ?string, value: string, muted: boolean, title: ?string,
 *          spoken: string}} what to draw and what to say
 */
export function bestReachLine(card) {
  const best = card?.bestReach ?? null;
  if (best) {
    const drive = formatDriveDuration(best.driveMinutes);
    const leave = leaveBy(best.solarEventTime, best.driveMinutes);
    // Only the parts that exist: a user with no postcode has no drive and no leave time, and a
    // title reading "Region · · leave " is worse than a title naming the region alone.
    const parts = [best.regionName, drive, leave ? `leave ${leave}` : null].filter(Boolean);
    return {
      rating: best.rating,
      label: null,
      value: best.locationName,
      muted: false,
      title: parts.join(' · ') || null,
      // ⚠️ THE SAME PARTS THE TITLE CARRIES, joined for speech rather than for a tooltip. A `title`
      // on a non-focusable span inside a button named by `aria-labelledby` reaches nobody: not the
      // accessible name, not touch, not the keyboard. Leaving the region, the drive and the
      // departure there alone would make the one figure on this card a photographer actually acts
      // on — when to leave — pointer-only, which is the "number with no route to the thing it
      // counts" defect CLAUDE.md already records against Close-to-home.
      spoken: [`best ${best.locationName}`, starsSpoken(best.rating), ...parts].join(', '),
    };
  }
  const empty = (card?.pool?.length ?? 0) === 0;
  // ⚠️ "In reach" only where the reach axis could have acted — `card.reachMeasured`, computed once
  // in `buildWindowCards` and folded onto this descriptor. An unknown drive passes every tier (plan
  // §2.5), so for a reader with no home postcode the pool was never gated and an empty one means
  // this window has no sky-gated slots at all; blaming a control that did nothing is §6 clause 7's
  // own sentence.
  const emptyWord = card?.reachMeasured ? 'nothing in reach' : 'nothing to show';
  return {
    rating: null,
    label: 'Best',
    value: empty ? emptyWord : 'not scored yet',
    muted: true,
    title: null,
    // Both forms keep the visible label's own word, so WCAG 2.5.3's label-in-name holds for a
    // speech-input user reading "Best" off the row.
    spoken: empty ? `best, ${emptyWord}` : 'best, not scored yet',
  };
}

/**
 * The Plan pane's matrix — the week as day columns, sunrise over sunset, and the plan itself.
 *
 * <h2>It is no longer an index into a list; it IS the plan</h2>
 *
 * <p>Plan-matrix §1. P2 shipped this as one row of six thumbnails labelling the window rows
 * beneath them. The v3 design turns the row into a <b>matrix</b> — a column per day, sunrise on
 * the upper row and sunset on the lower — and moves the verdict word, the pick, the topics and
 * the best place you could actually drive to onto the card faces themselves. Reading down a
 * column is one day; reading across a row compares the same light day to day.
 *
 * <p><b>The list below stays alive this phase.</b> Card click keeps P2's behaviour (open the
 * window's row and scroll to it), so {@code aria-expanded}/{@code aria-controls} still point at a
 * real element. M2 replaces both with a dialog and deletes the list.
 *
 * <h2>Placement is explicit, and that is what makes the phone free</h2>
 *
 * <p>{@code buildWindowMatrix} derives the columns from the data and every cell carries
 * {@code --c}/{@code --r}, so the stylesheet places rather than flows. The phone transposes the
 * identical markup into two columns under a spanning day header with no second render path — a
 * day holding one window takes the full row ({@code .solo}) and the empty cell is
 * {@code display: none}, because a 390px screen has no width to spend on a hole.
 *
 * <h2>Every word on a card comes from the payload</h2>
 *
 * <p>Plan §3 rule 1, restated because the design breaks it and we do not: the bundle derives its
 * verdict word from a client threshold on a client average ({@code >= 3.7} / {@code >= 2.8}). Here
 * the word is {@code card.verdictLabel} and the tint is a LOOKUP on {@code card.verdict}
 * ({@link verdictTint}) — a comparison operator anywhere in this file's verdict path is a defect.
 * The picks are the served {@code BriefingWindow.pick}, so an away plan whose pick names a
 * scoped-out region legitimately shows no legend at all (D-5).
 *
 * <h2>The two client derivations, and why they are client-side</h2>
 *
 * <p>The spread histogram and the best-reachable line are measured over the card's <b>pool</b> —
 * origin-scoped, canopy-filtered, reach-gated, before the rating floor. Reach is per-user and must
 * never ride the shared, ETag-revalidated briefing payload (plan §3 rule 4), so "how many places
 * could I get to, and which is the best of them" has no servable answer on that contract. Both are
 * recorded as members of the per-user-derivation class in A10/A11, with a never-cached per-user
 * endpoint as their eventual exit (§8 O-4). They aggregate nothing the backend also answers: the
 * window's own {@code bestRating} is untouched and still describes the whole roster.
 *
 * <p><b>The floor is deliberately not applied to the pool</b> — the design's own reason is that an
 * average of things that already passed a 4-star filter always reads 4-something — so the
 * histogram's leading count exceeds the sum of its bars whenever an unrated spot is in reach. The
 * tooltip names that remainder rather than leaving the arithmetic open (A10).
 *
 * <h2>Topics are named in full, and filtered by exactly one rule</h2>
 *
 * <p>Every topic on a night is named on its card — the design's point is that a night carrying
 * three of them is the most interesting night of the week, so hiding two behind a {@code +2} had
 * it backwards. The only client judgement is the scope intersection in {@code windowFirstTopics.js}
 * (A8), which filters like the lens rather than re-deriving a served judgement, and which is
 * <b>exempt by type</b> for the whole-sky kinds — aurora and NLC serve populated {@code regions}
 * lists that mean Bortle coverage and where-it-is-clear, so an unexempted intersection would delete
 * aurora from every away plan while every naive test passed.
 *
 * <h2>Canvases are decorative; the card's own hidden sentence is the answer</h2>
 *
 * <p>Each card's accessible name is ONE visually-hidden sentence, and every visible string on it —
 * canvas included — is {@code aria-hidden}. That is not an {@code aria-label} in disguise: the
 * sentence is real text assembled from the same values the visible words are, in the same render,
 * so the two cannot drift, and WCAG 2.5.3 holds because it contains the visible time, the visible
 * verdict word, the visible best-reach words and the visible pick words verbatim.
 *
 * <p><b>It counts what is actually rendered</b> (plan §3 rule 15). When the card face grew, the
 * sentence grew with it: the pool count the histogram draws, the best-reach claim, and every topic
 * name. The histogram's per-band breakdown stays in its {@code title} alone — a five-clause
 * distribution inside a button name would make six cards unscannable, and the ranked list the card
 * opens carries every spot with its rating. The star glyph and the sun word's glyph forebear are
 * not spoken as glyphs: an icon is not a word, so 2.5.3 does not ask for them and the sentence
 * spells them out.
 *
 * <p>Relying on name-from-contents instead does not survive a browser — see the note at the render
 * site.
 *
 * <h2>An away window is not a button, and an unscored one is marked</h2>
 *
 * <p>An away cell has no row to open — the pipeline skips evaluation on a travel day — and §6 bans
 * a control with no visible effect, so it is a {@code <div>} (plan §3 rule 14). It keeps its cell,
 * because removing it would silently renumber the shape of the week, and it keeps its sun time,
 * which is almanac and true whether or not a forecast ran. It draws none of the card's derived rows
 * — there is no pool behind them.
 *
 * <p>An unscored window takes {@code drawGeo}'s hatch, and the question is answered by
 * {@code card.bestRating} rather than by the point set. That distinction cost a production defect:
 * an empty point set is a fact about the JOIN behind the picture, while {@code bestRating} is the
 * payload's own "is anything in this window rated" — on 2026-08-19 the two disagreed for three of
 * six windows and a hatched plate reading "not scored" sat directly above a card reading
 * {@code best spot 5 stars}. One predicate, read by the plate, the footer note and the hidden
 * sentence.
 *
 * <h2>Movement is the change line's alone</h2>
 *
 * <p>P6's per-card delta chip is <b>deleted here</b> (deletion ledger, M1). The design's note is
 * that it earned nothing: a magnitude on a 55px tile with no room to name the region it belonged to
 * left the reader with a number attributable to nothing, while the change line under the footer
 * names the two biggest movers, their regions and the age of the run. That line is unchanged, and
 * its vocabulary is still {@code movement.js}' "moved at" family — never "since", for the reason
 * its own comment records.
 *
 * @param {object}   props
 * @param {Array}    props.cards      descriptors from {@code buildHeatStripCards}, chronological
 * @param {Map}      props.pointSets  window key to kernel points, from {@code buildHeatPointSets}
 * @param {Array}    props.spots      the whole heat catalogue, for framing and the beyond line
 * @param {?Map}     [props.reachById] per-user reach, keyed by location id — framing only
 * @param {Array}    [props.hotTopics] the served {@code briefing.hotTopics}, for the scope filter
 *                                    and (at M2) the science notes. Absent simply means no topic is
 *                                    scope-filtered — see {@code windowFirstTopics.js}
 * @param {Set}      [props.openKeys] the window keys whose cards are open
 * @param {string}   props.todayStr   today's ISO date in Europe/London, for the horizon fallback,
 *                                    the today column and the elapsed-morning cell
 * @param {?string}  [props.runAge]   the last forecast run's age, already formatted by the shell
 *                                    from {@code briefing.generatedAt} — passed rather than
 *                                    recomputed so the change line and the footer's stamp can
 *                                    never disagree by a rounding boundary
 * @param {Function} [props.onOpenWindow] opens and reveals a window's card
 * @param {?object}  [props.origin]   the away origin, or null for home — framing and scope
 * @param {Function} [props.onSearchRegion] opens search pre-filled with a region name
 */
export default function WindowFirstHeatStrip({
  cards, pointSets, spots, reachById, hotTopics, openKeys, todayStr, runAge, onOpenWindow,
  origin = null, onSearchRegion,
}) {
  // Framing is the ONE thing scope is allowed to decide about the field: which regions are in shot.
  // It must never become the point set — handing the scoped list to the kernel would turn the
  // framing into the reach filter plan §3 forbids, and the footer's own caption promises it does
  // not. `scopeSpots` is the planning area at home and the origin's own region when away, which is
  // what makes every thumbnail re-frame together on an origin move (plan §4.8's headline state).
  const framed = useMemo(() => scopeSpots(spots, reachById, origin), [spots, reachById, origin]);
  // The same scope, NAMED — the topic filter's other operand. Read from `scopeRegions` rather than
  // reduced out of `framed` so the two arms cannot drift: `scopeSpots` is itself defined in terms
  // of this function, and calling it directly is one call rather than a second derivation.
  const scopeNames = useMemo(
    () => scopeRegions(spots, reachById, origin), [spots, reachById, origin],
  );
  const beyond = useMemo(
    // Away the line is withheld entirely rather than recomputed from the base: "beyond 3h from
    // home" is a statement about the home planning area, and the shared matrix carries no such
    // threshold. A wrong frame of reference on a line whose whole job is to name distances is
    // worse than the line's absence, and the origin chip already says where you are.
    () => (origin ? [] : beyondRegions(spots, reachById)),
    [spots, reachById, origin],
  );
  const fitTo = useMemo(() => bbox(framed), [framed]);
  const frameAspect = useMemo(() => thumbAspect(fitTo), [fitTo]);
  // Only the windows that actually moved — see `topMovers`. Empty is the ordinary state on the
  // first serve after a deploy (no previous build recorded) and whenever nothing changed, and the
  // line is withheld entirely rather than printing an age with nothing attached to it.
  const movers = useMemo(() => topMovers(cards), [cards]);
  // The grid itself. Days, their holes and the column count all fall out of the rendered windows.
  const matrix = useMemo(() => buildWindowMatrix(cards, todayStr), [cards, todayStr]);
  // Indexed once for the whole matrix rather than scanned per card — `buildTopicIndex` records why.
  const topicIndex = useMemo(() => buildTopicIndex(hotTopics), [hotTopics]);

  /**
   * Everything each card derives from its own pool, computed once per render rather than inline.
   *
   * <p>Both figures read ONE list ({@code card.pool}) so the picture, the count and the name can
   * never describe different populations — which is the design's own requirement that the map, the
   * list and the line share a pool.
   */
  const derived = useMemo(() => {
    const byKey = new Map();
    for (const card of cards) {
      const pool = card.pool || [];
      const spread = buildSpread(pool);
      // ⚠️ `poolWithinReach([])` is TRUE by `Array.every`, so an empty pool would license the reach
      // word on a card where nothing was gated by distance. `card.reachMeasured` is what actually
      // answers "could the tier have acted", and it is the same field `bestReachLine` reads for its
      // own empty word, so the tooltip and the visible line agree by construction.
      const withinReach = pool.length === 0 ? Boolean(card.reachMeasured) : poolWithinReach(pool);
      byKey.set(card.key, {
        spread,
        bars: spreadBars(spread),
        title: spreadTitle(spread, withinReach),
        withinReach,
        best: bestReachLine(card),
        topics: windowTopics(card.key, card.badges, topicIndex, scopeNames),
      });
    }
    return byKey;
  }, [cards, topicIndex, scopeNames]);

  /**
   * The windows the payload says carry no rating at all.
   *
   * <p>{@code bestRating} and nothing else — the class comment records why the point set was the
   * wrong evidence and what it cost. The descriptor carries it straight off the window card, so
   * this cell and the row it opens answer one question from one field and cannot contradict one
   * another.
   *
   * <p><b>An away window is excluded, because it already has a truer word.</b> It carries no
   * rating either — nothing is evaluated on a travel day — but "Not forecast" is the more specific
   * claim and the one the payload supports: the weather was never run for it, whereas an unscored
   * live window has a real verdict and only lacks a rating.
   */
  const unscored = useMemo(() => {
    const keys = new Set();
    for (const card of cards) {
      if (!card.away && card.bestRating == null) keys.add(card.key);
    }
    return keys;
  }, [cards]);

  /**
   * One paint per card, at that card's OWN width.
   *
   * <p>⚠️ <b>The single measurement P2 took is no longer valid, and this is the change.</b> The
   * strip's six cards were one row of equal columns, so measuring the first well and spending it
   * on all six was exactly right. In the matrix they are not equal: on the phone a day holding one
   * window spans the full row, which is twice the width of a paired card in the same grid. Painting
   * it at a sibling's width would draw it at half scale inside its own box.
   *
   * <p>So the hook's measurement is used for the GATE (its retry budget, its floors, its
   * null-context guard — all properties of the document rather than of one card) and each canvas is
   * then measured against its own unpadded well. The fallback to the hook's width covers the
   * environment with no layout at all: jsdom reports every {@code clientWidth} as 0 unless a test
   * stubs it, and a zero-width paint would be a silent no-op rather than a visible one.
   *
   * <p>Memoised because the hook makes it a dependency of its paint effect; an inline arrow would
   * repaint on every render.
   *
   * <h3>Measured, because the plan asks for a number rather than an assertion (§10.1)</h3>
   *
   * <p>A full matrix repaint costs <b>60–210 ms</b> of main thread (median ~97 ms at 1180px, ~99 ms
   * at 390px), measured in Chrome with a {@code PerformanceObserver} on {@code longtask} over a
   * twelve-step width storm. The control run — the identical storm with this section
   * {@code display: none} — produced <b>zero</b> long tasks, so the cost is this surface's and not
   * the shell's. It is ~1.0–1.3 M device pixels across six canvases at DPR 2, roughly 3× P2's
   * one-row strip: the matrix trades six narrow thumbnails for two rows of wider ones.
   *
   * <p>It is a cost per LAYOUT CHANGE, not per render — a resize, a rotate, an origin move — and the
   * dials are the plan's §5 invariants, so nothing here is retuned to buy it back. The identified
   * mitigation is a debounce on the observer's nonce (the design's own "debounced resize (170ms)"),
   * which would coalesce a desktop window-drag's many observations into one paint; it belongs in
   * {@link useHeatCanvas}, which the Map pane shares, and therefore in M5's performance pass rather
   * than here.
   */
  const paint = useCallback(({ width, canvases }) => {
    for (const card of cards) {
      const canvas = canvases.get(card.key);
      if (!canvas) continue;
      const well = canvas.parentElement;
      const cardWidth = well && well.clientWidth > 0 ? well.clientWidth : width;
      const cardHeight = Math.round(cardWidth * frameAspect);
      // The point sets are KEYED, never positional (heatSpots.js): the integer the kernel takes
      // does not exist at this call site, and every point carries its one score at
      // POINT_SCORE_INDEX.
      const points = pointSets?.get?.(card.key) || [];
      const tier = resolveConfidence(
        { confidence: card.confidence }, daysOut(card.date, todayStr),
      );
      drawGeo(canvas, cardWidth, cardHeight, points, POINT_SCORE_INDEX, {
        grid: THUMB_GRID,
        // Scaled to THIS card, so a solo full-width card is not drawn with a paired card's blur
        // radius — the same reason its width is measured separately.
        radius: Math.max(THUMB_RADIUS_MIN, cardWidth * THUMB_RADIUS_FACTOR),
        blur: THUMB_BLUR,
        line: THUMB_LINE,
        // One scalar for the haze and the badge decay, so the picture cannot look more certain
        // than the word beside it (plan D3).
        conf: confidenceScalar(tier),
        // Off the same Set the markup reads, rather than off `points.length` here: one predicate,
        // so a hatched plate and an unmarked cell can never describe the same window.
        hatch: unscored.has(card.key),
        fit: fitTo,
      });
    }
  }, [cards, pointSets, unscored, fitTo, todayStr, frameAspect]);

  /**
   * The card whose well the hook's gate measures.
   *
   * <p>⚠️ Taken from the MATRIX, not from {@code cards[0]}. The two can differ:
   * {@code buildWindowMatrix} places only the target types the grid has a row for, so a card it
   * dropped would register no canvas — and the gate would then wait on a ref that never arrives,
   * spend its thirty-frame budget and leave <em>every</em> canvas blank while every word on every
   * card rendered correctly. Unreachable while {@code TargetType} has two constants; the point is
   * that the gate's key and the grid's placement are now two decisions and must not be two answers.
   */
  const measureKey = useMemo(() => {
    for (const day of matrix.days) {
      if (day.am) return day.am.key;
      if (day.pm) return day.pm.key;
    }
    return null;
  }, [matrix]);

  const { attachFrame, canvasRef, geoFailed } = useHeatCanvas({
    enabled: cards.length > 0,
    // The first PLACED card's well is the GATE's measured box — see `paint` for why it is no longer
    // the width every canvas is drawn at.
    measureKey,
    aspect: frameAspect,
    minPx: MIN_THUMB_PX,
    paint,
  });

  // Nothing to index. The matrix is a picture of the field, so with no catalogue joined — a scores
  // fetch that failed, a session with no roster — it withdraws entirely rather than drawing six
  // empty coastlines under a header claiming to summarise them. The window rows are untouched.
  if (cards.length === 0 || spots.length === 0) return null;

  /**
   * One card, as a cell of the matrix.
   *
   * <p>A local closure rather than a component: it reads the derived map, the unscored set, the
   * canvas ref factory and the open set off this render, and hoisting it would mean threading every
   * one of them through a prop list for an element with exactly one call site.
   *
   * @param {object} card   the thumbnail descriptor
   * @param {number} column the grid column, 1-based
   * @param {number} row    the grid row — {@code MATRIX_ROW.sunrise} or {@code .sunset}
   * @param {boolean} solo  whether this card's day holds only one window
   */
  const renderCard = (card, column, row, solo) => {
    const notScored = unscored.has(card.key);
    const open = Boolean(openKeys?.has(card.key));
    const facts = derived.get(card.key);
    const tint = card.away ? null : verdictTint(card.verdict);
    const place = { '--c': column, '--r': row };
    const poolTotal = facts.spread.total;
    // ⚠️ `poolPhrase`/`unratedPhrase`, not a second spelling of them. The tooltip and this sentence
    // describe the SAME set on the same card, and A10's disclosure of the remainder (`N > Σbars`
    // whenever an unrated spot is in reach) has to reach the reader who cannot see the bars — the
    // `title` carrying it sits on a span inside an `aria-hidden` subtree and reaches nobody.
    const spokenPool = poolTotal === 0
      ? null
      : `${poolPhrase(poolTotal, facts.withinReach)}${unratedPhrase(facts.spread, ', ')}`;
    // Built once per card so the hidden sentence and the visible words cannot be assembled from
    // different values. The comma-separated form is what a screen reader pauses on.
    //
    // "not scored" lands straight after the verdict because that is what it qualifies — "Poor" is a
    // reading of the weather, and this says nothing rated it. When it fires, the best-reach clause
    // is suppressed: a window with no rating anywhere has no rated spot in its pool either, so the
    // two would be one fact said twice.
    const accessibleName = [card.label, card.time, card.verdictLabel]
      .filter(Boolean)
      .concat(notScored ? ['not scored'] : [])
      .concat(card.away || !spokenPool ? [] : [spokenPool])
      // ⚠️ Suppressed when the window carries no rating anywhere — the pool then has no rated spot
      // either, so "not scored" and "best, not scored yet" would be one fact said twice in a
      // sentence that has to stay scannable across six cards. NOT suppressed on an empty pool: the
      // face still says "nothing in reach", and "nothing is scored" is a different claim from
      // "nothing is reachable".
      .concat(card.away || (notScored && poolTotal > 0) ? [] : [facts.best.spoken])
      .concat(card.away ? [] : facts.topics.map((t) => t.badge.label).filter(Boolean))
      .concat(card.pickKind === 'best' ? ['best bet'] : [])
      .concat(card.pickKind === 'also' ? ['also good'] : [])
      .join(', ');
    const nameId = `wf-heat-name-${card.key.replace(/:/g, '-')}`;
    const body = (
      <>
        {/* ⚠️ EVERY visible string here is aria-hidden, and the accessible name comes from the
            one `sr-only` sentence below. That is deliberate and it is not an `aria-label` in
            disguise — the sentence is real text built from the same values in the same render,
            so the two cannot drift, and WCAG 2.5.3 holds because it contains the visible time,
            the visible verdict word, the visible best-reach words and the visible pick words
            verbatim.

            The alternative — name-from-contents over the visible spans — does not survive
            contact with a browser. `.wf-hc` and `.wf-hc-pls` are flex and grid containers, and
            CSS Flexbox §4 says a contiguous child text run of only white space "is not rendered
            (just as if its text nodes were `display: none`)"; AccName then excludes it. So the
            separators an earlier cut relied on contributed nothing outside jsdom, where
            `css: false` leaves every span `display: inline` and the literal text nodes ARE the
            only spaces. The test passed and the browser would have announced
            "Tonight Sunset21:11Worth it". */}
        <span id={nameId} className="sr-only">{accessibleName}</span>
        {/* The WORD, not an arrow — the design's own call, and it is right: a down arrow for
            sunset reads as a falling forecast. The glyph it replaces was P2's. */}
        <span className="wf-hc-top" aria-hidden="true">
          <span data-testid="wf-heat-sun" className="wf-hc-sun">
            {card.sunrise ? 'SUNRISE' : 'SUNSET'}
          </span>
        </span>
        {!geoFailed && (
          <span data-testid="wf-heat-well" className="wf-hc-cv">
            <canvas
              aria-hidden="true"
              data-testid="wf-heat-canvas"
              ref={canvasRef(card.key)}
            />
          </span>
        )}
        <span className="wf-hc-pls" aria-hidden="true">
          <span data-testid="wf-heat-time" className="wf-hc-t">{card.time}</span>
          <span className="wf-hc-pv">
            <span
              data-testid="wf-heat-verdict"
              className="wf-hc-vd"
              data-verdict={card.verdict || 'AWAY'}
            >
              {card.verdictLabel}
            </span>
          </span>
          {/* An away window draws none of the three derived rows: there is no card behind it, so
              there is no pool, and an empty histogram beside "Best — nothing in reach" would be
              three claims about a night nobody forecast. */}
          {!card.away && (
            <>
              <span className="wf-hc-k">Spread</span>
              <span className="wf-hc-pv">
                {/* `title` carries the per-band breakdown; the pool count itself is in the card's
                    hidden sentence, so the figure a reader acts on is in the accessibility tree
                    and only the detail is pointer-only. */}
                <span
                  data-testid="wf-heat-spread"
                  className="wf-hc-hist"
                  title={facts.title}
                >
                  {facts.bars.map((bar) => (
                    <i
                      key={bar.star}
                      data-testid="wf-heat-spread-bar"
                      data-star={bar.star}
                      style={{
                        height: `${bar.heightPx}px`,
                        // The band's own ramp colour, from the same module the canvas above it is
                        // painted from — never a second table of five hexes. Full opacity, and the
                        // empty band lifted to 0.40: both are measured against the histogram's dark
                        // well rather than against the card, which is what puts every stop over
                        // SC 1.4.11's 3:1 — see the stylesheet's note for the whole table.
                        background: bar.filled
                          ? rgb(rampRgb(bar.star), 1)
                          : 'rgba(242,231,211,0.40)',
                      }}
                    />
                  ))}
                </span>
              </span>
              {facts.best.rating != null ? (
                <span
                  data-testid="wf-heat-best-rating"
                  className="wf-hc-rt"
                  // The ramp as a FILL with `readableInkOn`'s per-stop ink, not as text in the ramp
                  // colour: the bottom two stops measure 2.9:1 and 3.7:1 as ink on this card and
                  // would ship a rating below AA on exactly the nights the best you can reach is a
                  // 1 or a 2. `spotBadgeStyle` is the arm's existing measured pair — see the
                  // stylesheet's note, and `windowFirstSpots.readableInkOn` for why one ink cannot
                  // serve five stops.
                  style={spotBadgeStyle(facts.best.rating) ?? undefined}
                >
                  {`${facts.best.rating}★`}
                </span>
              ) : (
                <span className="wf-hc-k">{facts.best.label}</span>
              )}
              <span className="wf-hc-pv2">
                <span
                  data-testid="wf-heat-best"
                  className={`wf-hc-best${facts.best.muted ? ' none' : ''}`}
                  title={facts.best.title ?? undefined}
                >
                  {facts.best.value}
                </span>
              </span>
              {/* Reserved even when empty, so a topic-free card's rows land on the same baselines
                  as its neighbours' — the design's own reason. */}
              <span data-testid="wf-heat-topics" className="wf-hc-tps">
                {facts.topics.map(({ badge }) => (
                  <span
                    key={`${badge.type}:${badge.label}`}
                    data-testid="wf-heat-topic"
                    data-channel={badgeChannel(badge.type)}
                    className="wf-hc-tw"
                    title={badge.detail ? `${badge.label} — ${badge.detail}` : badge.label}
                  >
                    {badge.label}
                  </span>
                ))}
              </span>
            </>
          )}
        </span>
        {/* The pick rides the card's own border, like a legend on a fieldset, so it stops
            competing with the verdict word for the same column and every card keeps its verdict
            in the same place. `background: inherit` is what cuts the border line behind it — see
            the stylesheet, where that pairing is load-bearing. */}
        {card.pickKind && (
          <span
            data-testid="wf-heat-legend"
            data-pick={card.pickKind}
            className="wf-hc-lg"
            aria-hidden="true"
          >
            {card.pickKind === 'best' ? 'Best bet' : 'Also good'}
          </span>
        )}
      </>
    );
    const className = [
      'wf-hc',
      tint,
      solo ? 'solo' : null,
      open ? 'on' : null,
      card.pickKind === 'best' ? 'best' : null,
      card.pickKind === 'also' ? 'also' : null,
    ].filter(Boolean).join(' ');
    // An away window has no row to open — see the class comment. It keeps its cell and its sun
    // time and simply is not a control.
    return card.away ? (
      <div
        key={card.key}
        data-testid="wf-heat-card"
        data-away="true"
        className={`${className} wf-hc-away`}
        style={place}
      >
        {body}
      </div>
    ) : (
      <button
        key={card.key}
        type="button"
        data-testid="wf-heat-card"
        // The same attribute name the region band's swatch uses for the same state, so the two
        // unscored marks on the Plan screen are one convention rather than two. It drives no
        // colour here: the mark is the canvas hatch, and dimming the verdict word instead would
        // put "Poor" — measured to AA in `index.css`, and the one word a reader most needs at a
        // glance — back under the floor that measurement set.
        data-unscored={notScored ? 'true' : undefined}
        data-open={open ? 'true' : undefined}
        // Which window is open was a CSS-only signal — a gold border tint and two recoloured words
        // — so a screen-reader user could not tell which one they were in, and neither could anyone
        // who cannot resolve the tint.
        //
        // ⚠️ M1 stated it as `aria-expanded` + `aria-controls` pointing at the row this cell
        // revealed. At M2 the row is gone and the cell opens a DIALOG, so both had to change rather
        // than merely be re-pointed: `aria-controls` would have named an id no longer in the
        // document (an IDREF that resolves to nothing is announced as nothing), and `aria-expanded`
        // on a control that opens a modal reads as an in-place disclosure a reader can Tab into.
        // `aria-haspopup="dialog"` is the pattern for a control that opens one, and the expanded
        // state stays alongside it — ARIA permits the pair, and it is how a reader hears which of
        // six cards the dialog on screen belongs to.
        aria-haspopup="dialog"
        aria-expanded={open}
        // `aria-labelledby` at the visually-hidden sentence, rather than leaving
        // name-from-contents to find it. Both routes end at the same string, but this one is
        // explicit: every accessible-name implementation honours an IDREF, whereas
        // name-from-contents has to walk into a clipped 1px box — and a tree dump of the running
        // app showed these buttons with NO computed name while every sibling control that uses an
        // IDREF or a label was named. Still not an `aria-label`: the label is real text in the
        // document, built from the same values the visible words are, so it cannot drift and WCAG
        // 2.5.3's label-in-name holds by construction.
        aria-labelledby={nameId}
        className={className}
        style={place}
        onClick={() => onOpenWindow?.(card.key)}
      >
        {body}
      </button>
    );
  };

  /**
   * A hole the matrix explains, or leaves in silence — see {@code windowFirstMatrix.js} for why
   * only two of them get words.
   */
  const renderEmpty = (copy, column, row, key) => (
    <div
      key={key}
      data-testid="wf-heat-empty"
      className="wf-hgap"
      style={{ '--c': column, '--r': row }}
    >
      {copy && <span>{copy}</span>}
    </div>
  );

  // A plain `<section>`, deliberately without an `aria-label`: a NAMED section becomes a `region`
  // landmark, and this would be the arm's only one — a landmark inside a tabpanel whose name
  // matches no visible text, announced ahead of buttons that each name themselves. The grouping
  // the matrix needs is visual, and the buttons carry the words.
  return (
    <section data-testid="wf-heat-strip" className="wf-hstrip-block">
      <div data-testid="wf-heat-head" className="wf-hstrip-h">
        {/* ⚠️ A HEADING, not a span. It is a section heading by every visual convention — 9.5px mono,
            600, letter-spaced, uppercase, beside a full-width hairline — and it was marked up as a
            `<span>`, which left the whole v2 Plan tab with exactly one heading (the masthead
            wordmark's `h1`). A browse-mode reader pressing `H` went from the wordmark to the end of
            the page. Level 2 under that `h1`; `.wf-hstrip-k` carries every visible property and
            Tailwind's preflight resets heading size and weight to inherited, so nothing moves. */}
        <h2 className="wf-hstrip-k">The days ahead</h2>
        <span className="wf-hstrip-rule" aria-hidden="true" />
      </div>

      <div
        ref={attachFrame}
        data-testid="wf-heat-grid"
        className="wf-hstrip"
        style={{ '--dc': matrix.columns }}
      >
        {matrix.days.map((day, index) => {
          const column = index + 1;
          return (
            <React.Fragment key={day.date}>
              {/* The day, said once per column instead of once per card. The rule running to the
                  right edge of the column is its header — the same legend idiom the picks use. */}
              <div
                data-testid="wf-heat-day"
                data-today={day.today ? 'true' : undefined}
                className={`wf-hday${day.today ? ' today' : ''}`}
                style={{ '--c': column, '--r': MATRIX_ROW.header }}
              >
                <span className="wf-hday-cal">
                  {/* ⚠️ The WORD for today, in the weekday's own slot, and it is a deliberate
                      deviation from the bundle ("Today's column: … No 'TODAY' word"). The design's
                      reason for omitting it was that "the cards below already carry Tonight/Today in
                      their own labels" — true of P2's thumbnail, which drew `card.label`, and false
                      of the v3 card face, which draws the sun word instead and leaves the day
                      label in the visually-hidden sentence alone. That left the today column marked
                      by a gold tile border and a gold digit and by nothing else. The window rows
                      still below the matrix rescue it this phase; M2 deletes them, so restoring the
                      word now is cheaper than restoring it as an M2 blocker. Same slot, same size,
                      no second row — only the tile is a few pixels wider, which shortens its own
                      rule and nothing else. */}
                  <span className="wf-hday-dow">{day.today ? 'Today' : day.dow}</span>
                  <span className="wf-hday-dn">{day.dn}</span>
                </span>
                <span className="wf-hday-rule" aria-hidden="true" />
              </div>
              {day.am
                ? renderCard(day.am, column, MATRIX_ROW.sunrise, day.solo)
                : renderEmpty(day.amEmpty, column, MATRIX_ROW.sunrise, `${day.date}:am`)}
              {day.pm
                ? renderCard(day.pm, column, MATRIX_ROW.sunset, day.solo)
                : renderEmpty(day.pmEmpty, column, MATRIX_ROW.sunset, `${day.date}:pm`)}
            </React.Fragment>
          );
        })}
      </div>

      <div data-testid="wf-heat-foot" className="wf-hstrip-foot">
        <span
          data-testid="wf-heat-legbar"
          className="wf-hstrip-legbar"
          aria-hidden="true"
          style={{ background: rampGradient() }}
        />
        <span>poor → worth it</span>
        <span>later days render hazier — lower confidence</span>
        {/* Named in words because a texture is not vocabulary, and named HERE rather than on each
            card because a card's own rows are already full — the same call the haze clause beside
            it makes about the same picture.

            ⚠️ NOT `.wf-hstrip-sp`, so it survives the phone. The desktop-only rule next to it is
            about a PERMANENT third clause competing for a narrow bar; this one appears only while
            a hatched plate is actually on screen, and the phone is where the misreading was
            reported. It says nothing about WHY — "at this range" was drafted and dropped, because
            the horizon is the usual cause and not the only one (a failed batch leaves T+0
            unscored), and the client cannot tell those apart. */}
        {unscored.size > 0 && (
          <span data-testid="wf-heat-unscored-note">unshaded — not scored</span>
        )}
        {/* Desktop only, as the design has it — the phone bar has no room for a third clause and
            the first two are the ones that decode the picture. */}
        <span className="wf-hstrip-sp">
          The field shows the forecast, not your reach — the cards apply it
        </span>
      </div>

      {/* Under the footer and above the beyond line: it is a statement about the whole matrix, so
          it sits with the legend that decodes it rather than among the cards — and the beyond line
          stays last, because that one is the tail about what is NOT drawn.

          ⚠️ THE VERB IS "MOVED AT", NEVER "SINCE", and the two are not interchangeable here.
          The delta's basis is the build BEFORE the one whose age this line prints — the interval
          measured is [previous build, now], while `generatedAt` names the LAST build. "Since the
          last forecast run 52m ago" (the design's own sample copy) therefore claims the one
          interval in which almost none of the movement happened: with builds ~11h apart, a reader
          at 14:52 would be told a ten-hour change occurred in fifty-two minutes. "Moved at"
          attributes the change to the run rather than to the period after it, which is true of the
          run-to-run component and honest about the rest.

          ⚠️ It is now the ONLY movement channel on this surface — the per-card chip died with the
          old card face (deletion ledger, M1) — so the region names and the age it carries are the
          whole of what a reader is told about change on the Plan screen until M2's popup header.

          The age stays `generatedAt` (not the served `previousGeneratedAt`) because it is the
          stamp the shell footer used to print, and two different ages for one forecast on one
          screen is its own defect. The residual caveat is on the field itself
          (`BriefingRegion.meanRatingDelta`): the current side is re-derived at serve time, so the
          quantity includes post-build batch drift as well as run-to-run change.

          ⚠️ THE AGE NOW HAS NOWHERE ELSE TO GO. M3 deleted the rail footer, so this is the page's
          only statement of how old the forecast is (Rule 7's "one age per screen", finally true
          rather than approximately so — the footer and this line both printed it before). That is
          why the branch below exists: with no movement basis the change line is withheld, and
          withholding the AGE with it would lose a fact that is not about movement at all. The two
          forms are mutually exclusive by construction and a test pins that they are, because two
          age lines is the defect this consolidation removes. The no-movement wording drops the
          verb entirely — "Moved at the last forecast run" over an empty list would assert a
          movement the same element has just declined to name. */}
      {movers.length === 0 && runAge && (
        <p data-testid="wf-heat-runage" className="wf-hstrip-change">
          {`Last forecast run ${runAge}`}
        </p>
      )}
      {movers.length > 0 && (
        <p data-testid="wf-heat-change" className="wf-hstrip-change">
          {runAge
            ? `Moved at the last forecast run, ${runAge}`
            : 'Moved at the last forecast run'}
          {movers.map((mover) => (
            <span key={mover.key} data-testid="wf-heat-change-item">
              {/* ⚠️ THE SEPARATOR AND THE REGION SIT OUTSIDE THE `nowrap` ATOM, and that is a
                  reflow fix rather than a preference. With `white-space: nowrap` on the whole item
                  the clause was ONE unbreakable run — JSX strips the whitespace-only lines between
                  array elements, so the last break opportunity in the paragraph was the space
                  before the run age, and a 25-character region name (the arm's own
                  "Northumberland & Tyneside") put ~328px of unbreakable text into a 315px line at
                  375px. That is a horizontal page scroller, which is exactly what `.wf-hstrip`'s
                  own comment says this surface exists not to be. Only `label ▲0.6` may not break.

                  The middle dot is `aria-hidden` with a real comma beside it: NVDA, JAWS and
                  VoiceOver do not speak `·` at default punctuation levels and do not pause on it,
                  so without the comma the clauses fuse into one run-on — while this file's own
                  accessible-name rule is that "the comma-separated form is what a screen reader
                  pauses on". */}
              <span aria-hidden="true">{' · '}</span>
              <span className="sr-only">{', '}</span>
              <span className="wf-hstrip-chg">
                {mover.label}
                {' '}
                {/* The glyph carries the direction visually and is unreadable aloud, so it is
                    hidden and the words beside it say the same thing — the treatment the region
                    band's direction dots already use. */}
                <b
                  data-testid="wf-heat-change-mark"
                  data-tone={mover.chip.tone}
                  aria-hidden="true"
                >
                  {mover.chip.mark}
                </b>
              </span>
              <span className="sr-only">{` ${mover.chip.shortSpoken}`}</span>
              {` in ${mover.regionName}`}
            </span>
          ))}
        </p>
      )}

      {/* Named, never counted, and only where a drive was actually measured: `beyondRegions`
          withholds an unmeasured region precisely so this line cannot claim a distance nobody
          computed.

          The link is P7's, deferred from P2 for the reason this arm defers every such control: a
          line naming places you cannot reach, with no route to them, is the "number with no route
          to the thing it counts" defect CLAUDE.md already records against Close-to-home. It opens
          search pre-filled with the FIRST beyond region — `beyondRegions` is sorted nearest-first,
          so that is the one a reader is most likely to want, and the box is left editable rather
          than the link being one-per-region: six links would make the tail longer than the matrix.
          It is a link into search rather than a direct origin move because a beyond region may
          have no base town, and a control that silently does nothing is worse than one that shows
          you why. */}
      {beyond.length > 0 && (
        <p data-testid="wf-heat-beyond" className="wf-hstrip-beyond">
          {`Beyond ${GLANCE_HOURS}h from home: ${beyond.join(' · ')}`}
          {onSearchRegion && (
            <>
              {' '}
              <button
                type="button"
                data-testid="wf-heat-beyond-search"
                className="wf-hstrip-beyond-act"
                onClick={() => onSearchRegion(beyond[0])}
              >
                {`search to plan from ${beyond[0]}`}
                <span aria-hidden="true"> →</span>
              </button>
            </>
          )}
        </p>
      )}
    </section>
  );
}

WindowFirstHeatStrip.propTypes = {
  cards: PropTypes.arrayOf(PropTypes.shape({
    key: PropTypes.string.isRequired,
    date: PropTypes.string.isRequired,
    targetType: PropTypes.string,
    dow: PropTypes.string,
    sunrise: PropTypes.bool,
    label: PropTypes.string,
    time: PropTypes.string,
    verdict: PropTypes.string,
    verdictLabel: PropTypes.string,
    bestRating: PropTypes.number,
    /** The served pick's kind — `best`, `also`, or null. Never re-derived here (plan §3 rule 1). */
    pickKind: PropTypes.oneOf(['best', 'also']),
    away: PropTypes.bool,
    confidence: PropTypes.string,
    movement: PropTypes.shape({
      regionName: PropTypes.string,
      delta: PropTypes.number,
    }),
    /** The reach-gated, pre-floor spot pool the spread and the best-reach line are measured over. */
    pool: PropTypes.array,
    /** That pool's head, or null when nothing in it is rated — see {@link bestReachLine}. */
    bestReach: PropTypes.shape({
      locationName: PropTypes.string,
      regionName: PropTypes.string,
      rating: PropTypes.number,
      driveMinutes: PropTypes.number,
      solarEventTime: PropTypes.string,
    }),
    /** The window's badges BEFORE row promotion — the matrix names every topic on the night. */
    badges: PropTypes.array,
  })).isRequired,
  pointSets: PropTypes.instanceOf(Map),
  spots: PropTypes.array.isRequired,
  reachById: PropTypes.instanceOf(Map),
  /** The served hot topics, for the scope filter. Absent means nothing is scope-filtered. */
  hotTopics: PropTypes.array,
  openKeys: PropTypes.instanceOf(Set),
  todayStr: PropTypes.string,
  runAge: PropTypes.string,
  onOpenWindow: PropTypes.func,
  /**
   * The away origin ({@code {name, baseName}}), or null for home. Framing only — it never reaches
   * the kernel's point set.
   */
  origin: PropTypes.shape({
    name: PropTypes.string.isRequired,
    baseName: PropTypes.string.isRequired,
  }),
  /** Opens search pre-filled with a region name. Omit and the beyond line renders without its link. */
  onSearchRegion: PropTypes.func,
};
