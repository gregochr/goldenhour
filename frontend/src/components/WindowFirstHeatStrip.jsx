import React, { useCallback, useMemo } from 'react';
import PropTypes from 'prop-types';
import {
  aspect, bbox, clamp, drawGeo,
} from '../utils/heatField.js';
import { useHeatCanvas } from '../hooks/useHeatCanvas.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import { windowCardDomId } from '../utils/windowFirstCards.js';
import { areaSpots, beyondRegions, GLANCE_MINUTES } from '../utils/planningArea.js';
import { RAMP_STOPS } from '../utils/scoreRamp.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';
import { movementChip, topMovers } from '../utils/movement.js';

/**
 * The thumbnail frame's aspect clamps.
 *
 * <p>Not kernel behaviour — {@code aspect()} has no clamps of its own — so they are the strip's
 * constants and the strip's boundary tests. The prototype's reason for capping tighter than the
 * true frame is worth keeping verbatim: the six must stay comparable AND compact, and a slight
 * letterbox costs less than a 200px-tall summary strip.
 */
export const THUMB_ASPECT_MIN = 0.85;
/** @see THUMB_ASPECT_MIN */
export const THUMB_ASPECT_MAX = 1.22;

/** Grid step, radius floor, radius factor and blur — the prototype's thumbnail dials (plan §4.3). */
const THUMB_GRID = 4;
const THUMB_RADIUS_MIN = 10;
const THUMB_RADIUS_FACTOR = 0.155;
const THUMB_BLUR = 2.4;
const THUMB_LINE = 0.5;

/**
 * The strip's own measurement floor, handed to {@link useHeatCanvas}.
 *
 * <p><b>25, not 21, and the extra four pixels are the height gate.</b> {@code drawGeo} tests BOTH
 * dimensions ({@code !(w > 20) || !(h > 20)}) while this measures width alone, and the height is
 * {@code width × frameAspect} with the aspect floored at {@link THUMB_ASPECT_MIN} = 0.85. At a
 * width of 22–24 the measurement passes, the height lands at 19–20, and {@code drawGeo} declines
 * with the retry budget already spent — a permanently blank canvas from a gate the retry cannot
 * see. 25 × 0.85 = 21.25, which clears it. Unreachable at any supported viewport (a 22px cell needs
 * a ~150px container), so this closes a latent mismatch rather than a live defect. The hook now
 * carries the general form of that rule as well ({@code MIN_CANVAS_PX}); this constant is what
 * makes it unreachable here, and is the boundary the strip's own tests pin.
 */
const MIN_THUMB_PX = 25;

/**
 * The footer's ramp bar, built from the ramp module's own stops.
 *
 * <p>A JS-derived value rather than a CSS gradient literal, for the reason `scoreRamp.js` exists at
 * all: the canvas is painted from these numbers, and a hand-written gradient beside it is a second
 * copy that can drift with nothing failing. Computed once at module load — it depends on nothing
 * that changes.
 */
const RAMP_GRADIENT = `linear-gradient(90deg, ${RAMP_STOPS
  .map((stop, index) => `${stop.hex} ${Math.round((index / (RAMP_STOPS.length - 1)) * 100)}%`)
  .join(', ')})`;

/** The planning-area threshold in whole hours, for the beyond line's own sentence. */
const GLANCE_HOURS = GLANCE_MINUTES / 60;

/**
 * The frame every thumbnail is projected into, clamped.
 *
 * <p>Exported for its boundary tests: the clamps are this component's, not the kernel's, and a
 * frame flatter than 0.85 or taller than 1.22 is exactly what a coverage change produces.
 *
 * @param {object} fitTo a corner MultiPoint from {@code bbox}
 * @returns {number} height / width for the thumbnail canvases
 */
export function thumbAspect(fitTo) {
  return clamp(aspect(fitTo), THUMB_ASPECT_MIN, THUMB_ASPECT_MAX);
}

/**
 * The Plan pane's heat strip — six solar windows as a visual index of the list below.
 *
 * <h2>It replaces the day rail, and it is a Plan-pane element rather than screen chrome</h2>
 *
 * <p>Decision D1, confirmed by the owner on 2026-08-18 and set out in full in §1.1 of
 * {@code heat-field-plan.md}. The rail was four DAY tiles above the tab bar; this is six WINDOW
 * thumbnails under the lens bar. Stacking them would be two summaries of one forecast at two
 * grains, costing roughly two screens before the first window row — the "quality stated four
 * times" overload the design's own open question warns about. Every job the rail did has a named
 * home in §1.1's relocation table, and the one genuinely lost — whole-screen date context above
 * the tabs — is a recorded reversal, acceptable now only because the Map pane renders its own
 * {@code DateStrip} and every Coming-up row carries its dates.
 *
 * <h2>The strip is never reordered</h2>
 *
 * <p>The Order control ranks the window CARDS; the thumbnails stay in the payload's chronological
 * order under both settings. There is no code here that could do otherwise — the cards arrive
 * built from {@code upcomingEvents} and this component neither sorts nor filters them — which is
 * the point: the time axis is the only reason the shape of the week is legible at a glance.
 *
 * <h2>What the header may say, and what it may not</h2>
 *
 * <p>The mock's "204 rated locations · 51 named" is not ported. The pre-pilot sweep bans counts of
 * our own data (§6 clause 4 — "11 aligned is a fact about the database, not about tonight"), and
 * {@code WindowAttributeRow}, {@code WindowFirstDoors} and the retired rail were each stripped of
 * exactly this copy. What survives is a horizon kicker describing the surface directly beneath it —
 * a count of the days DRAWN, which is the one kind of number §6 permits, because it cannot describe
 * a set the reader is not looking at.
 *
 * <h2>The BEST BET flag is a passive span</h2>
 *
 * <p>The rail's equivalent was a button opening {@code WindowPickDialog}. Here the thumbnail is
 * itself a button, and interactive content inside a {@code <button>} is invalid HTML — the same
 * {@code nested-interactive} defect the rail's own comment records fixing. So the flag states the
 * pick and the dialog stays reachable from the window card's pick badge, which is where the prose
 * it opens belongs. It reads {@code Best bet} in the markup and is upper-cased by the stylesheet,
 * so a screen reader says the words rather than spelling the letters.
 *
 * <h2>An away window is not a button</h2>
 *
 * <p>It has no card to open — the pipeline skips evaluation on a travel day, so there is no row to
 * reveal — and §6 bans a control with no visible effect. It keeps its slot in the strip because
 * removing it would silently renumber the shape of the week, and it keeps its sun time because
 * that is almanac and true whether or not a forecast ran.
 *
 * <h2>An unscored window is marked, not left blank</h2>
 *
 * <p>A window nobody rated has an empty point set, the kernel returns null for one, and the tile
 * renders as bare geography — indistinguishable from a window whose field happened to paint
 * nothing. That blank was read as a forecast, and the verdict word underneath made it worse: the
 * word comes from the briefing's own weather thresholds, which run the whole horizon, so an
 * unscored Saturday printed a confident "Poor" above an empty map next to a Thursday whose
 * identical "Poor" was the roster actually rated bad.
 *
 * <p>It is gated on {@code scoresKnown}, and that gate is the difference between a mark and a
 * flash. An empty point set is also what an unfetched ratings response looks like, and the
 * locations prop routinely lands first — ungated, every mount painted six hatched plates and a
 * footer clause for as long as that took, then flipped to the real field. So the strip marks
 * nothing until the provider says the ratings actually arrived.
 *
 * <p>Three parts, one predicate ({@code unscored}). The plate takes {@code drawGeo}'s hatch; the
 * footer names the convention in words, once, for as long as a hatched plate is on screen; and the
 * accessible sentence carries "not scored" straight after the verdict it qualifies. The verdict
 * word itself is untouched — it is a true statement about the weather, and it is the vocabulary
 * {@code UnscoredPill} and the region band's swatch already settled: an unscored surface withholds
 * the RATING channel and leaves everything else alone.
 *
 * <h2>Canvases are decorative; the button's own words are the answer</h2>
 *
 * <p>Each thumbnail's accessible name is ONE visually-hidden sentence — "Tomorrow sunrise, 05:12,
 * Worth it, best bet" — and every visible string on it, canvas included, is {@code aria-hidden}.
 * That is not an {@code aria-label} in disguise: the sentence is real text assembled from the same
 * fields the visible words are, in the same render, so the two cannot drift, and WCAG 2.5.3 holds
 * because it contains the visible time, the visible verdict word, "best bet" and (since P6) the
 * movement magnitude verbatim. Relying on name-from-contents instead does not survive a browser —
 * see the note at the render site.
 *
 * <p>The directional glyphs are deliberately NOT in the name. {@code ▲} and {@code ↓} are icons
 * rather than words, so a speech-input user cannot utter them and 2.5.3 does not ask for them; the
 * name spells the direction out instead. That rule predates P6 — the visible arrow and the visible
 * weekday were already left out of it — and the movement chip follows it rather than setting one.
 *
 * <h2>Movement is one channel in two renderings, and neither may be the whole of it</h2>
 *
 * <p>Each thumbnail carries the delta of its own leading region as a chip; the line under the
 * footer names the two biggest movers, their regions and how long ago the run was. They are not the
 * same fact stated twice — the chip is a magnitude the eye can scan across six windows, and the
 * line is the only place the REGION is named and the only place the age is stated. A chip alone
 * would leave the reader with a number and no idea what moved; a line alone would put the movement
 * on a different surface from the window it describes.
 *
 * <p>A null delta renders <b>nothing</b>, on both. See {@code movement.js} for why that is not the
 * same as the flat {@code —}, and why collapsing them would light up every thumbnail on the first
 * serve after a deploy.
 *
 * @param {object}   props
 * @param {Array}    props.cards      descriptors from {@code buildHeatStripCards}, chronological
 * @param {Map}      props.pointSets  window key → kernel points, from {@code buildHeatPointSets}
 * @param {Array}    props.spots      the whole heat catalogue, for framing and the beyond line
 * @param {boolean}  [props.scoresKnown] whether the ratings response has been received. Gates the
 *   unscored mark ONLY: absent or false, a window with no points is left as it always rendered,
 *   because "we have not fetched the ratings yet" and "nothing was rated" are the same empty map
 *   and only the provider can tell them apart. Defaults false so a caller that has not thought
 *   about it makes no claim.
 * @param {?Map}     [props.reachById] per-user reach, keyed by location id — framing only
 * @param {Set}      [props.openKeys] the window keys whose cards are open
 * @param {string}   props.todayStr   today's ISO date in Europe/London, for the horizon fallback
 * @param {?string}  [props.runAge]   the last forecast run's age, already formatted by the shell
 *                                    from {@code briefing.generatedAt} — passed rather than
 *                                    recomputed so the strip's line and the footer's stamp can
 *                                    never disagree by a rounding boundary
 * @param {Function} [props.onOpenWindow] opens and reveals a window's card
 */
export default function WindowFirstHeatStrip({
  cards, pointSets, spots, scoresKnown = false, reachById, openKeys, todayStr, runAge, onOpenWindow,
}) {
  // Framing is the ONE thing the planning area is allowed to decide about the field (planningArea's
  // own module comment): which regions are in shot. It must never become the point set — handing
  // `areaSpots` to the kernel would turn the framing into the reach filter plan §3 forbids, and the
  // footer's own caption promises it does not.
  const framed = useMemo(() => areaSpots(spots, reachById), [spots, reachById]);
  const beyond = useMemo(() => beyondRegions(spots, reachById), [spots, reachById]);
  const fitTo = useMemo(() => bbox(framed), [framed]);
  const frameAspect = useMemo(() => thumbAspect(fitTo), [fitTo]);
  // Only the windows that actually moved — see `topMovers`. Empty is the ordinary state on the
  // first serve after a deploy (no previous build recorded) and whenever nothing changed, and the
  // line is withheld entirely rather than printing an age with nothing attached to it.
  const movers = useMemo(() => topMovers(cards), [cards]);

  /**
   * The windows carrying no ratings at all — the strip's own derivation, from the very map its
   * paint callback reads.
   *
   * <p><b>The point set, never the paint result.</b> {@code field()} also returns null when every
   * point is culled outside the frame, so "nothing painted" answers a framing question as well as
   * a data one; {@code pointSets.get(key).length} answers only the data one, and it is the same
   * expression the canvas is drawn from, so the mark and the picture cannot disagree.
   *
   * <p><b>An away window is excluded, because it already has a truer word.</b> Its point set is
   * empty too — nothing is evaluated on a travel day — but "Not forecast" is the more specific
   * claim and it is the one the payload supports: the weather was never run for it, whereas an
   * unscored live window has a real verdict and only lacks a rating.
   */
  const unscored = useMemo(() => {
    const keys = new Set();
    if (!scoresKnown) return keys;
    for (const card of cards) {
      if (card.away) continue;
      if (!(pointSets?.get?.(card.key)?.length > 0)) keys.add(card.key);
    }
    return keys;
  }, [cards, pointSets, scoresKnown]);

  /**
   * One paint for all six, at one size.
   *
   * <p>The six have to be comparable — that is the whole claim the strip makes — so
   * {@link useHeatCanvas} takes ONE measurement (from the first thumbnail's well) and this callback
   * spends it on every canvas. Memoised because the hook makes it a dependency of its paint effect;
   * an inline arrow would repaint on every render.
   */
  const paint = useCallback(({ width, height, canvases }) => {
    for (const card of cards) {
      const canvas = canvases.get(card.key);
      if (!canvas) continue;
      // The point sets are KEYED, never positional (heatSpots.js): the integer the kernel takes
      // does not exist at this call site, and every point carries its one score at
      // POINT_SCORE_INDEX.
      const points = pointSets?.get?.(card.key) || [];
      const tier = resolveConfidence(
        { confidence: card.confidence }, daysOut(card.date, todayStr),
      );
      drawGeo(canvas, width, height, points, POINT_SCORE_INDEX, {
        grid: THUMB_GRID,
        radius: Math.max(THUMB_RADIUS_MIN, width * THUMB_RADIUS_FACTOR),
        blur: THUMB_BLUR,
        line: THUMB_LINE,
        // One scalar for the haze and the badge decay, so the picture cannot look more certain
        // than the word beside it (plan D3).
        conf: confidenceScalar(tier),
        // Off the same Set the markup reads, rather than off `points.length` here: one predicate,
        // so a hatched plate and an unmarked tile can never describe the same window.
        hatch: unscored.has(card.key),
        fit: fitTo,
      });
    }
  }, [cards, pointSets, unscored, fitTo, todayStr]);

  const { attachFrame, canvasRef, geoFailed } = useHeatCanvas({
    enabled: cards.length > 0,
    // The first thumbnail's well is the measured box, and its width sizes all six.
    measureKey: cards[0]?.key ?? null,
    aspect: frameAspect,
    minPx: MIN_THUMB_PX,
    paint,
  });

  // Nothing to index. The strip is a picture of the field, so with no catalogue joined — a scores
  // fetch that failed, a session with no roster — it withdraws entirely rather than drawing six
  // empty coastlines under a header claiming to summarise them. The window rows are untouched.
  if (cards.length === 0 || spots.length === 0) return null;

  // A plain `<section>`, deliberately without an `aria-label`: a NAMED section becomes a `region`
  // landmark, and this would be the arm's only one — a landmark inside a tabpanel whose name
  // matches no visible text, announced ahead of six buttons that each name themselves. The grouping
  // the strip needs is visual, and the buttons carry the words.
  return (
    <section data-testid="wf-heat-strip" className="wf-hstrip-block">
      <div data-testid="wf-heat-head" className="wf-hstrip-h">
        <span className="wf-hstrip-k">The days ahead</span>
        <span className="wf-hstrip-rule" aria-hidden="true" />
      </div>

      <div ref={attachFrame} data-testid="wf-heat-grid" className="wf-hstrip">
        {cards.map((card) => {
          const notScored = unscored.has(card.key);
          // Built once per card so the hidden sentence and the visible words cannot be assembled
          // from different values. The comma-separated form is what a screen reader pauses on.
          // The chip is inside the `aria-hidden` top row and its mark is a glyph, so without this
          // clause the movement channel would exist for sighted readers only. Spelled out ("up 0.6
          // since the last forecast run") rather than transliterated, for the same reason the
          // region band's dots spell out their direction glyph.
          const chip = movementChip(card.movement?.delta);
          // The REGION is named here and nowhere else on the thumbnail. The chip is the only
          // region-grain figure on a cell whose every other element (time, verdict, best bet) is
          // window-grain, and the change line names only the top two movers — so without this a
          // reader who cannot see the change line has a number attributable to nothing. Sighted
          // readers get the same answer from the change line for the two that matter most.
          // "not scored" lands straight after the verdict because that is what it qualifies —
          // "Poor" is a reading of the weather, and this says nothing rated it. The visible word
          // is still in the name verbatim, so WCAG 2.5.3 holds.
          const accessibleName = [card.label, card.time, card.verdictLabel]
            .filter(Boolean)
            .concat(notScored ? ['not scored'] : [])
            .concat(card.bestBet ? ['best bet'] : [])
            .concat(chip ? [`${card.movement.regionName} ${chip.spoken}`] : [])
            .join(', ');
          const nameId = `wf-heat-name-${card.key.replace(/:/g, '-')}`;
          const body = (
            <>
              {/* ⚠️ EVERY visible string here is aria-hidden, and the accessible name comes from the
                  one `sr-only` sentence below. That is deliberate and it is not an `aria-label` in
                  disguise — the sentence is real text built from the same fields in the same render,
                  so the two cannot drift, and WCAG 2.5.3 holds because it contains the visible time,
                  the visible verdict word, "best bet" and the movement magnitude verbatim.

                  The alternative — name-from-contents over the visible spans — does not survive
                  contact with a browser. `.wf-hc` and `.wf-hc-bot` are flex containers, and CSS
                  Flexbox §4 says a contiguous child text run of only white space "is not rendered
                  (just as if its text nodes were `display: none`)"; AccName then excludes it. So the
                  separators an earlier cut relied on contributed nothing outside jsdom, where
                  `css: false` leaves every span `display: inline` and the literal text nodes ARE the
                  only spaces. The test passed and the browser would have announced
                  "Tonight Sunset21:11Worth it". */}
              <span id={nameId} className="sr-only">{accessibleName}</span>
              <span className="wf-hc-top" aria-hidden="true">
                <span className="wf-hc-dow">{card.dow}</span>
                {/* Unpadded vertically and at the row's own 9px, on purpose: `.wf-hc-flag` is
                    absolutely positioned at a MEASURED `top: 22px` clearing this row, and a chip
                    that grew the line box would slide the flag over the canvas with nothing
                    failing. The stylesheet's own note at that rule records the pair. */}
                {chip && (
                  <span
                    data-testid="wf-heat-move"
                    className="wf-hc-mv"
                    data-tone={chip.tone}
                  >
                    {chip.mark}
                  </span>
                )}
                <span className="wf-hc-ar">
                  {card.away ? '✈' : (card.sunrise ? '↑' : '↓')}
                </span>
              </span>
              {!geoFailed && (
                <span className="wf-hc-cv">
                  <canvas
                    aria-hidden="true"
                    data-testid="wf-heat-canvas"
                    ref={canvasRef(card.key)}
                  />
                </span>
              )}
              <span className="wf-hc-bot" aria-hidden="true">
                <span data-testid="wf-heat-time" className="wf-hc-t">{card.time}</span>
                <span
                  data-testid="wf-heat-verdict"
                  className="wf-hc-vd"
                  data-verdict={card.verdict || 'AWAY'}
                >
                  {card.verdictLabel}
                </span>
              </span>
              {card.bestBet && (
                <span data-testid="wf-heat-flag" className="wf-hc-flag" aria-hidden="true">
                  Best bet
                </span>
              )}
            </>
          );
          // An away window has no row to open — see the class comment. It keeps its cell and its
          // sun time and simply is not a control.
          return card.away ? (
            <div
              key={card.key}
              data-testid="wf-heat-card"
              data-away="true"
              className="wf-hc wf-hc-away"
            >
              {body}
            </div>
          ) : (
            <button
              key={card.key}
              type="button"
              data-testid="wf-heat-card"
              // The same attribute name the region band's swatch uses for the same state, so the
              // two unscored marks on the Plan screen are one convention rather than two. It
              // drives no colour here: the mark is the canvas hatch, and dimming the verdict word
              // instead would put "Poor" — measured to AA at 9px in `index.css`, and the one word
              // a reader most needs at a glance — back under the floor that measurement set.
              data-unscored={notScored ? 'true' : undefined}
              data-open={openKeys?.has(card.key) ? 'true' : undefined}
              // Which row is open was a CSS-only signal — a gold border tint and two recoloured
              // words — so a screen-reader user could not tell which of six windows they were in,
              // and neither could anyone who cannot resolve the tint. `aria-expanded` states it in
              // the same channel the card's own expander already uses for the same disclosure, and
              // `aria-controls` points at the card this thumbnail reveals (the id is always
              // rendered: `buildWindowCards` builds a card for every non-travel event, and an away
              // window is not a button at all).
              aria-expanded={Boolean(openKeys?.has(card.key))}
              aria-controls={windowCardDomId(card.key)}
              // `aria-labelledby` at the visually-hidden sentence, rather than leaving
              // name-from-contents to find it. Both routes end at the same string, but this one is
              // explicit: every accessible-name implementation honours an IDREF, whereas
              // name-from-contents has to walk into a clipped 1px box — and a tree dump of the
              // running app showed these six buttons with NO computed name while every sibling
              // control that uses an IDREF or a label was named. Still not an `aria-label`: the
              // label is real text in the document, built from the same fields the visible words
              // are, so it cannot drift and WCAG 2.5.3's label-in-name holds by construction.
              aria-labelledby={nameId}
              className={`wf-hc${openKeys?.has(card.key) ? ' on' : ''}`}
              onClick={() => onOpenWindow?.(card.key)}
            >
              {body}
            </button>
          );
        })}
      </div>

      <div data-testid="wf-heat-foot" className="wf-hstrip-foot">
        <span
          data-testid="wf-heat-legbar"
          className="wf-hstrip-legbar"
          aria-hidden="true"
          style={{ background: RAMP_GRADIENT }}
        />
        <span>poor → worth it</span>
        <span>later days render hazier — lower confidence</span>
        {/* Named in words because a texture is not vocabulary, and named HERE rather than on each
            thumbnail because a 55px tile has no room for a second string — the same call the haze
            clause beside it makes about the same picture.

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
          The field shows the forecast, not your reach — the cards below apply it
        </span>
      </div>

      {/* Under the footer and above the beyond line: it is a statement about the whole strip, so it
          sits with the legend that decodes the strip rather than among the window rows — and the
          beyond line stays last, because that one is the tail about what is NOT drawn.

          ⚠️ THE VERB IS "MOVED AT", NEVER "SINCE", and the two are not interchangeable here.
          The delta's basis is the build BEFORE the one whose age this line prints — the interval
          measured is [previous build → now], while `generatedAt` names the LAST build. "Since the
          last forecast run 52m ago" (the plan's own sample copy) therefore claims the one interval
          in which almost none of the movement happened: with builds ~11h apart, a reader at 14:52
          would be told a ten-hour change occurred in fifty-two minutes. "Moved at" attributes the
          change to the run rather than to the period after it, which is true of the run-to-run
          component and honest about the rest.

          The age stays `generatedAt` (not the served `previousGeneratedAt`) because it is the
          stamp the shell footer already prints, and two different ages for one forecast on one
          screen is its own defect. The residual caveat is on the field itself
          (`BriefingRegion.meanRatingDelta`): the current side is re-derived at serve time, so the
          quantity includes post-build batch drift as well as run-to-run change. */}
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
                  own comment says the strip exists not to be. Only `label ▲0.6` may not break.

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
          computed. The search link that would let a reader plan from one arrives with P7; until
          then the names are the whole of it. */}
      {beyond.length > 0 && (
        <p data-testid="wf-heat-beyond" className="wf-hstrip-beyond">
          {`Beyond ${GLANCE_HOURS}h from home: ${beyond.join(' · ')}`}
        </p>
      )}
    </section>
  );
}

WindowFirstHeatStrip.propTypes = {
  cards: PropTypes.arrayOf(PropTypes.shape({
    key: PropTypes.string.isRequired,
    date: PropTypes.string.isRequired,
    dow: PropTypes.string,
    sunrise: PropTypes.bool,
    label: PropTypes.string,
    time: PropTypes.string,
    verdict: PropTypes.string,
    verdictLabel: PropTypes.string,
    bestBet: PropTypes.bool,
    away: PropTypes.bool,
    confidence: PropTypes.string,
    movement: PropTypes.shape({
      regionName: PropTypes.string,
      delta: PropTypes.number,
    }),
  })).isRequired,
  pointSets: PropTypes.instanceOf(Map),
  spots: PropTypes.array.isRequired,
  scoresKnown: PropTypes.bool,
  reachById: PropTypes.instanceOf(Map),
  openKeys: PropTypes.instanceOf(Set),
  todayStr: PropTypes.string,
  runAge: PropTypes.string,
  onOpenWindow: PropTypes.func,
};
