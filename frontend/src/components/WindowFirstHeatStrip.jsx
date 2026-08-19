import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import {
  aspect, bbox, clamp, drawGeo, land, load,
} from '../utils/heatField.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import { windowCardDomId } from '../utils/windowFirstCards.js';
import { areaSpots, beyondRegions, GLANCE_MINUTES } from '../utils/planningArea.js';
import { RAMP_STOPS } from '../utils/scoreRamp.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';

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
 * The measurement floor and the retry budget.
 *
 * <p>{@code drawGeo} declines below 20px because a zero measure throws on {@code cv.width}, and the
 * arm's panes mount {@code display: none} — so the first measure can legitimately be zero. The
 * retry is the prototype's {@code drawThumbs(tries)}, and its budget covers <b>measurement only</b>:
 * "the geometry has not loaded yet" is the other reason {@code drawGeo} returns null, and polling
 * for it would let a budget sized for a layout tick expire before the topology chunk arrives. That
 * one is handled by {@link load}'s promise instead, which is what {@code land()} is exported for.
 *
 * <p><b>25, not 21, and the extra four pixels are the height gate.</b> {@code drawGeo} tests BOTH
 * dimensions ({@code !(w > 20) || !(h > 20)}) while this measures width alone, and the height is
 * {@code width × frameAspect} with the aspect floored at {@link THUMB_ASPECT_MIN} = 0.85. At a
 * width of 22–24 the measurement passes, the height lands at 19–20, and {@code drawGeo} declines
 * with the retry budget already spent — a permanently blank canvas from a gate the retry cannot
 * see. 25 × 0.85 = 21.25, which clears it. Unreachable at any supported viewport (a 22px cell needs
 * a ~150px container), so this closes a latent mismatch rather than a live defect.
 */
const MIN_THUMB_PX = 25;
const MAX_MEASURE_TRIES = 30;

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
 * <h2>Canvases are decorative; the button's own words are the answer</h2>
 *
 * <p>Each thumbnail's accessible name is ONE visually-hidden sentence — "Tomorrow sunrise, 05:12,
 * Worth it, best bet" — and every visible string on it, canvas included, is {@code aria-hidden}.
 * That is not an {@code aria-label} in disguise: the sentence is real text assembled from the same
 * fields the visible words are, in the same render, so the two cannot drift, and WCAG 2.5.3 holds
 * because it contains the visible time, the visible verdict word and "best bet" verbatim. Relying
 * on name-from-contents instead does not survive a browser — see the note at the render site.
 *
 * @param {object}   props
 * @param {Array}    props.cards      descriptors from {@code buildHeatStripCards}, chronological
 * @param {Map}      props.pointSets  window key → kernel points, from {@code buildHeatPointSets}
 * @param {Array}    props.spots      the whole heat catalogue, for framing and the beyond line
 * @param {?Map}     [props.reachById] per-user reach, keyed by location id — framing only
 * @param {Set}      [props.openKeys] the window keys whose cards are open
 * @param {string}   props.todayStr   today's ISO date in Europe/London, for the horizon fallback
 * @param {Function} [props.onOpenWindow] opens and reveals a window's card
 */
export default function WindowFirstHeatStrip({
  cards, pointSets, spots, reachById, openKeys, todayStr, onOpenWindow,
}) {
  /**
   * The grid, observed through a REF CALLBACK rather than a mount effect.
   *
   * <p>A `useEffect(…, [])` reading `gridRef.current` cannot work here, and the failure is total
   * rather than occasional: on a cold load `cards` and `spots` are both empty, so this component
   * returns null, the grid does not exist, and an effect with an empty dependency list never runs
   * again. The observer would then be absent for the whole session — no repaint on a rotate, and
   * none on the reveal of a pane that first mounted `display: none`, which is the case the
   * observer exists for. A ref callback fires whenever the node attaches, whenever that is.
   */
  const observerRef = useRef(null);
  const gridNodeRef = useRef(null);
  const lastWidthRef = useRef(0);
  const canvasRefs = useRef(new Map());
  /** Bumped when the vendored geometry resolves, which is what re-runs the paint effect. */
  const [landNonce, setLandNonce] = useState(0);
  /** Bumped by the ResizeObserver — a reveal or a window resize, never a paint of its own. */
  const [resizeNonce, setResizeNonce] = useState(0);
  /**
   * True when the topology chunk could not be fetched. The canvases are then not rendered at all
   * rather than left as six black boxes: an empty frame implies a map that has nothing on it,
   * which is a different (and false) claim from "the picture is unavailable". The words stay.
   */
  const [geoFailed, setGeoFailed] = useState(false);

  // Framing is the ONE thing the planning area is allowed to decide about the field (planningArea's
  // own module comment): which regions are in shot. It must never become the point set — handing
  // `areaSpots` to the kernel would turn the framing into the reach filter plan §3 forbids, and the
  // footer's own caption promises it does not.
  const framed = useMemo(() => areaSpots(spots, reachById), [spots, reachById]);
  const beyond = useMemo(() => beyondRegions(spots, reachById), [spots, reachById]);
  const fitTo = useMemo(() => bbox(framed), [framed]);
  const frameAspect = useMemo(() => thumbAspect(fitTo), [fitTo]);

  useEffect(() => {
    let cancelled = false;
    // Whether the geometry was ALREADY resolved when this mounted, which decides whether the nonce
    // is worth moving. `load()` resolves immediately in that case, and bumping would repaint six
    // canvases the paint effect below has just drawn — a doubled first paint on every mount after
    // the first. The call itself is still made unconditionally, so a REJECTION is always seen.
    const alreadyLoaded = land() != null;
    load()
      .then(() => { if (!cancelled && !alreadyLoaded) setLandNonce((n) => n + 1); })
      // Swallowed into a rendering state rather than rethrown: an unhandled rejection here shows
      // as a permanent loading state with no visible cause, which is exactly what `load`'s own
      // docs warn callers about.
      .catch(() => { if (!cancelled) setGeoFailed(true); });
    return () => { cancelled = true; };
  }, []);

  const attachGrid = useCallback((node) => {
    observerRef.current?.disconnect();
    observerRef.current = null;
    gridNodeRef.current = node;
    if (!node || typeof ResizeObserver === 'undefined') return;
    // Gated on the WIDTH actually changing, not on any observation. A ResizeObserver delivers one
    // callback immediately on `observe()`, and the first paint itself resizes the grid (a canvas
    // goes from its 300×150 intrinsic ratio to `width × frameAspect`), so an ungated observer
    // repaints all six twice more on every mount — which would quietly undo the `alreadyLoaded`
    // guard below, whose whole purpose is avoiding a doubled first paint.
    //
    // The zero box is skipped for the reason `WindowFirstMapPane` records: hiding a pane fires an
    // observation at 0×0, and repainting against it would spend a frame proving the canvas is too
    // small. The reveal fires its own observation, which is the one worth acting on.
    const ro = new ResizeObserver(() => {
      // ⚠️ `clientWidth`, NOT `getBoundingClientRect()`, and the difference is not stylistic: the
      // paint measures the canvas well's `clientWidth`, so the observer has to watch the same
      // quantity or the two can disagree about whether anything moved. Caught in the browser —
      // in a host where `getBoundingClientRect()` answered 0 while `clientWidth` answered 82, this
      // callback took the zero-box early return on every observation and the strip never repainted
      // on a resize: at 360px the canvases were still drawn at the 390px width and clipped by
      // `.wf-hc`'s own `overflow: hidden`, silently. One measurement API for both ends.
      const width = node.clientWidth;
      if (width === 0 || width === lastWidthRef.current) return;
      lastWidthRef.current = width;
      setResizeNonce((n) => n + 1);
    });
    ro.observe(node);
    observerRef.current = ro;
  }, []);

  useEffect(() => () => observerRef.current?.disconnect(), []);

  /**
   * The window's own resize, alongside the observer.
   *
   * <p>Belt and braces, and the braces are the ones that were measured. The prototype redrew on
   * {@code window.resize} and this component started with a {@code ResizeObserver} alone, on the
   * reasoning that an observer catches strictly more (a pane revealed from {@code display: none}
   * fires no window resize). That is true and the observer stays for exactly that case — but in the
   * browser it was checked in, the observer did not fire on a viewport change at all: at 360px the
   * canvases were still drawn at their 390px width and clipped by {@code .wf-hc}'s
   * {@code overflow: hidden}. Two triggers for one repaint is cheap; the repaint is idempotent
   * (`lastWidthRef` makes a no-change resize a no-op) and the paint itself is six 4px-grid fields.
   */
  useEffect(() => {
    const onResize = () => {
      const width = gridNodeRef.current?.clientWidth ?? 0;
      if (width === 0 || width === lastWidthRef.current) return;
      lastWidthRef.current = width;
      setResizeNonce((n) => n + 1);
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    // `land()` rather than a retry: the paint effect re-runs when `landNonce` moves, so waiting
    // here costs nothing and cannot consume the measurement budget (see MAX_MEASURE_TRIES).
    if (geoFailed || !land() || cards.length === 0) return undefined;
    let raf = 0;
    let cancelled = false;
    const attempt = (tries) => {
      if (cancelled) return;
      // ONE measurement for all six, taken from the first thumbnail's canvas well. The six have to
      // be comparable — that is the whole claim the strip makes — and measuring each independently
      // would let a sub-pixel column difference paint two of them at different scales. The well is
      // an unpadded wrapper, so its `clientWidth` is the drawable width directly; measuring the
      // button would need its padding subtracted, which is the prototype's `- 12` and a constant
      // that silently rots the moment the stylesheet changes.
      const first = canvasRefs.current.get(cards[0].key);
      const well = first?.parentElement;
      const cell = well ? well.clientWidth : 0;
      if (!(cell > MIN_THUMB_PX)) {
        if (tries < MAX_MEASURE_TRIES) raf = requestAnimationFrame(() => attempt(tries + 1));
        return;
      }
      // P0 left this guard to whoever mounts a canvas, and this is that caller: `field`/`fit`
      // DEREFERENCE the 2d context rather than declining, and a real browser can return null for it
      // under memory pressure. Unguarded, the TypeError is thrown inside an effect and takes the
      // whole Plan pane to the error boundary — six thumbnails costing the reader every window row.
      // Checked once on the first canvas rather than per canvas: they are siblings created in one
      // commit, so the condition is a property of the document rather than of one element.
      if (!first.getContext('2d')) {
        setGeoFailed(true);
        return;
      }
      const height = Math.round(cell * frameAspect);
      for (const card of cards) {
        const canvas = canvasRefs.current.get(card.key);
        if (!canvas) continue;
        // The point sets are KEYED, never positional (heatSpots.js): the integer the kernel takes
        // does not exist at this call site, and every point carries its one score at
        // POINT_SCORE_INDEX.
        const points = pointSets?.get?.(card.key) || [];
        const tier = resolveConfidence(
          { confidence: card.confidence }, daysOut(card.date, todayStr),
        );
        drawGeo(canvas, cell, height, points, POINT_SCORE_INDEX, {
          grid: THUMB_GRID,
          radius: Math.max(THUMB_RADIUS_MIN, cell * THUMB_RADIUS_FACTOR),
          blur: THUMB_BLUR,
          line: THUMB_LINE,
          // One scalar for the haze and the badge decay, so the picture cannot look more certain
          // than the word beside it (plan D3).
          conf: confidenceScalar(tier),
          fit: fitTo,
        });
      }
    };
    attempt(0);
    return () => {
      cancelled = true;
      if (raf) cancelAnimationFrame(raf);
    };
  }, [cards, pointSets, fitTo, frameAspect, todayStr, landNonce, resizeNonce, geoFailed]);

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

      <div ref={attachGrid} data-testid="wf-heat-grid" className="wf-hstrip">
        {cards.map((card) => {
          // Built once per card so the hidden sentence and the visible words cannot be assembled
          // from different values. The comma-separated form is what a screen reader pauses on.
          const accessibleName = [card.label, card.time, card.verdictLabel]
            .filter(Boolean)
            .concat(card.bestBet ? ['best bet'] : [])
            .join(', ');
          const nameId = `wf-heat-name-${card.key.replace(/:/g, '-')}`;
          const body = (
            <>
              {/* ⚠️ EVERY visible string here is aria-hidden, and the accessible name comes from the
                  one `sr-only` sentence below. That is deliberate and it is not an `aria-label` in
                  disguise — the sentence is real text built from the same fields in the same render,
                  so the two cannot drift, and WCAG 2.5.3 holds because it contains the visible time,
                  the visible verdict word and "best bet" verbatim.

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
                <span className="wf-hc-ar">
                  {card.away ? '✈' : (card.sunrise ? '↑' : '↓')}
                </span>
              </span>
              {!geoFailed && (
                <span className="wf-hc-cv">
                  <canvas
                    aria-hidden="true"
                    data-testid="wf-heat-canvas"
                    ref={(node) => {
                      if (node) canvasRefs.current.set(card.key, node);
                      else canvasRefs.current.delete(card.key);
                    }}
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
        {/* Desktop only, as the design has it — the phone bar has no room for a third clause and
            the first two are the ones that decode the picture. */}
        <span className="wf-hstrip-sp">
          The field shows the forecast, not your reach — the cards below apply it
        </span>
      </div>

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
  })).isRequired,
  pointSets: PropTypes.instanceOf(Map),
  spots: PropTypes.array.isRequired,
  reachById: PropTypes.instanceOf(Map),
  openKeys: PropTypes.instanceOf(Set),
  todayStr: PropTypes.string,
  onOpenWindow: PropTypes.func,
};
