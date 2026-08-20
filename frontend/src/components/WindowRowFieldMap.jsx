import React, { useCallback, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import {
  aspect, bbox, centroid, clamp, drawGeo,
} from '../utils/heatField.js';
import { useHeatCanvas } from '../hooks/useHeatCanvas.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import { scopeSpots } from '../utils/planOrigin.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';

/**
 * The row map's aspect clamps — wider than the thumbnails' and, on a phone, taller.
 *
 * <p>Not kernel behaviour ({@code aspect()} has no clamps of its own), so they are this component's
 * constants and this component's boundary tests — the same split {@code thumbAspect} records. The
 * design's reason for the two bands: a desktop row can afford a letterbox because the rail and band
 * sit beside the reader's eye line, while a phone column has the width to spare and not the height,
 * so its map is allowed to go nearly square.
 */
export const MAP_ASPECT_MIN = 0.36;
/** @see MAP_ASPECT_MIN */
export const MAP_ASPECT_MAX = 0.62;
/** @see MAP_ASPECT_MIN */
export const MAP_ASPECT_MIN_PHONE = 0.5;
/** @see MAP_ASPECT_MIN */
export const MAP_ASPECT_MAX_PHONE = 0.95;

/** Grid step, radius floor, radius factor, blur and coastline stroke — the design's row dials. */
const MAP_GRID = 6;
const MAP_RADIUS_MIN = 20;
const MAP_RADIUS_FACTOR = 0.072;
const MAP_BLUR = 3.6;
const MAP_LINE = 0.85;

/**
 * The measurement floor, sized for this component's own aspect band.
 *
 * <p>{@code drawGeo} declines when EITHER dimension is 20px or smaller, and at the desktop floor of
 * 0.36 a 56px-wide map is only 20px tall. {@link useHeatCanvas} applies that height test itself, so
 * this is the width half of the same gate — and 56 is a value no supported viewport reaches, which
 * is what makes both of them latent rather than live.
 */
const MIN_MAP_PX = 56;

/**
 * How close a pointer has to land to a region's centroid to select it, as a fraction of the frame's
 * width. Straight from the bundle (`plan-tab.js`: {@code bd < r.width*0.26}).
 */
const PICK_RADIUS_FRACTION = 0.26;

/**
 * The open row's full-width field map — the same kernel the strip paints, at row dials.
 *
 * <h2>It is a pointer shortcut, never the only route</h2>
 *
 * <p>The canvas is {@code aria-hidden} and carries no {@code tabindex}: it is a picture, and a
 * picture is not a control vocabulary. Everything it can do — see which regions exist, see how they
 * rank, select one — the region rail directly beneath it does with named buttons, which is the
 * accessible equivalent §4.4 specifies. The click here is a convenience for a reader who is already
 * looking at the map, and it can be removed without removing a capability. That is the condition
 * {@code frontend-test-standards.md} sets for an {@code aria-hidden} surface: never the sole path to
 * anything.
 *
 * <h2>The labels are DOM, not canvas</h2>
 *
 * <p>Canvas text does not scale with the reader's font settings, cannot be selected, and would be
 * re-rasterised on every repaint. Absolutely-positioned spans cost one layout and stay legible.
 *
 * <p>⚠️ <b>Two CSS facts here are load-bearing and are recorded in the bundle's README.</b>
 * {@code .wf-mapbox} carries {@code line-height: 0} to kill the inline gap under the canvas, and the
 * absolutely-positioned labels inside it <em>inherit</em> it — so {@code .wf-mlab span} sets
 * {@code line-height: 1.35} explicitly. Without it the selected region's dark plate paints 6px tall
 * behind 9.5px of text and reads as a strike-through rather than as a chip.
 *
 * <h2>A window nobody rated is marked, not left blank</h2>
 *
 * <p>The heat strip's rule, one level down and for the same reason: with no rating the kernel
 * paints nothing, so the map is bare coastline — indistinguishable from a window whose field
 * happened to paint nothing, while the card above it carries a verdict word the briefing's weather
 * thresholds produced for the whole horizon either way. The plate takes {@code drawGeo}'s hatch
 * and a {@code Not scored} chip names it.
 *
 * <p>⚠️ <b>Keyed on the window's served {@code bestRating}, never on {@code points} being empty.</b>
 * The strip's class comment records what that cost in production: an empty point set is a fact
 * about the join behind the picture, and three windows the payload was rating drew a plate saying
 * nobody had. The rail directly below this map reads the same field for its All cell, so the two
 * cannot disagree.
 *
 * <p>The chip is the strip's vocabulary, not its wording: there the footer says
 * {@code unshaded — not scored} because it decodes six tiles at once, and here there is one map
 * whose whole plate is hatched, so "unshaded" would name nothing.
 *
 * <h2>Framing, focus, and the one thing the planning area may decide</h2>
 *
 * <p>The frame is the planning area's, exactly as the strip's is, so the row map and the six
 * thumbnails above it are the same picture at two sizes. The <b>field</b> is not area-filtered —
 * plan §3 — and the point set handed to the kernel is the window's own, keyed.
 *
 * <p>Centroids are taken over the FRAMED catalogue rather than over the window's scored points, so
 * a label sits in the same place whatever this window's coverage happens to be. Taking them from the
 * scored subset would make the labels drift between windows as coverage changed, which reads as the
 * map being wrong rather than as the forecast being thin.
 *
 * @param {object}   props
 * @param {string}   props.windowKey  the window this map paints, for its keyed point set
 * @param {string}   props.date       the window's date, for the confidence horizon
 * @param {?string}  props.confidence the window's served confidence tier
 * @param {Array}    props.spots      the whole heat catalogue
 * @param {?object}  [props.origin]   the away origin, or null for home — framing only
 * @param {Array}    props.points     this window's kernel points, from {@code buildHeatPointSets}
 * @param {?number}  [props.bestRating] the window's served best rating — null when the payload
 *   says nothing in this window is rated, which is the one thing that draws the unscored mark.
 *   Absent is treated as "not rated" only in the sense that a caller passing nothing gets no mark:
 *   see {@code notScored}.
 * @param {Array}    props.regionNames the rail's regions, in rank order — the labellable set
 * @param {?string}  props.selectedRegion the focused region, or null
 * @param {?Map}     [props.reachById] per-user reach, keyed by location id — framing only
 * @param {string}   props.todayStr   today's ISO date in Europe/London
 * @param {Function} [props.onSelectRegion] called with a region name, or null to clear
 */
export default function WindowRowFieldMap({
  windowKey, date, confidence, spots, points, bestRating = null, regionNames, selectedRegion,
  origin = null,
  reachById, todayStr, onSelectRegion,
}) {
  const isMobile = useIsMobile();
  // Scope, not area — the planning area at home and the origin's own region when away, so the
  // open row's map is framed exactly as the six thumbnails above it are. One module, so a row and
  // the strip that opened it cannot disagree about what is in shot.
  const framed = useMemo(() => scopeSpots(spots, reachById, origin), [spots, reachById, origin]);
  const fitTo = useMemo(() => bbox(framed), [framed]);
  const frameAspect = useMemo(() => clamp(
    aspect(fitTo),
    isMobile ? MAP_ASPECT_MIN_PHONE : MAP_ASPECT_MIN,
    isMobile ? MAP_ASPECT_MAX_PHONE : MAP_ASPECT_MAX,
  ), [fitTo, isMobile]);

  /**
   * The projection and the label positions, set together by one paint.
   *
   * <p>ONE piece of state rather than a ref for the projection and state for the labels: the click
   * test and the labels are two readings of the same geometry, and splitting them lets a click be
   * answered against a projection the labels on screen were not drawn from — after a resize, for
   * the frame or two before the labels catch up.
   */
  const [frame, setFrame] = useState(null);

  /**
   * Whether the payload says nothing in this window is rated — the strip's mark, one level down.
   *
   * <p>The same predicate as the strip, off the same field, and deliberately NOT
   * {@code points.length === 0}: that asks whether the FIELD can paint, which is a different
   * question with a different answer (see the class comment). Here the misreading was quieter than
   * on the strip but not smaller — the region labels still sit on the plate, so an unmarked bare
   * map reads as "these places, nothing doing" rather than as "nobody looked".
   *
   * <p>Simpler than the strip's Set because this component is handed ONE window rather than six.
   */
  const notScored = bestRating == null;

  // Fewer than two regions and there is nothing to choose between: the rail withdraws (§4.4) and so
  // does the click. Normally a P7 origin case; mechanically reachable now on a one-region payload.
  const selectable = regionNames.length > 1;

  /**
   * The focus the kernel is actually given — the selection, but only when a point carries it.
   *
   * <p>⚠️ <b>Without this the field can go completely blank on a legitimate selection.</b> The two
   * populations do not agree and nothing else reconciles them: the rail's names come from
   * {@code eligibleRegions} over the BRIEFING payload, which keeps a region whose slots are all
   * unrated (and one with no slots at all), while the kernel's points come from
   * {@code heatPointsFor}, which drops every spot without a finite score for this window. So an
   * Awaiting region — a T+3 window in a grid cell the nightly policy did not evaluate, or an
   * ordinary misty morning — is nameable on the rail, has spots in the strip, has a label on the
   * map, and has <em>no point</em> in the field.
   *
   * <p>The kernel's answer to a focus matching nothing is to multiply EVERY weight by 1e-4, which
   * takes every cell under the 0.02 coverage clamp: the whole canvas paints transparent, the other
   * regions' heat vanishes with it, and nothing appears in the console.
   * {@code heatField.test.js} pins that outcome directly ("is inert when the focus id matches
   * nothing"). The selection is still real — the label plates, the rail cell reads pressed, the band
   * opens and the strip filters — it simply does not dim a field that has nothing to dim.
   */
  const focusRegion = useMemo(
    () => (selectedRegion && points.some((point) => point.rid === selectedRegion)
      ? selectedRegion
      : undefined),
    [selectedRegion, points],
  );

  const paint = useCallback(({ width, height, canvases }) => {
    const canvas = canvases.get(windowKey);
    if (!canvas) return;
    const tier = resolveConfidence({ confidence }, daysOut(date, todayStr));
    const project = drawGeo(canvas, width, height, points, POINT_SCORE_INDEX, {
      grid: MAP_GRID,
      radius: Math.max(MAP_RADIUS_MIN, width * MAP_RADIUS_FACTOR),
      blur: MAP_BLUR,
      line: MAP_LINE,
      conf: confidenceScalar(tier),
      // The hatch is the caller's claim, never inferred from an empty field — `drawGeo`'s own note
      // says why (a null field also answers a framing question).
      hatch: notScored,
      fit: fitTo,
      // The kernel raises its own alpha to 238 when a focus is set, so a focused field reads as
      // deliberate rather than as a dimmer version of the unfocused one. Byte-identical, never
      // normalised (`heatSpots.js`), and withheld when no point carries it — see `focusRegion`.
      focus: focusRegion,
    });
    // Three different reasons `drawGeo` returns null (P0's note); none of them leaves a projection
    // worth keeping, and a stale one would answer clicks against geometry no longer on screen.
    if (!project) {
      setFrame(null);
      return;
    }
    const toPoint = (spot) => project([spot.lng, spot.lat]);
    const labels = [];
    for (const name of regionNames) {
      const at = centroid(framed, name, toPoint);
      if (!at || !Number.isFinite(at[0]) || !Number.isFinite(at[1])) continue;
      labels.push({ name, x: at[0], y: at[1] });
    }
    setFrame({ width, labels });
  }, [windowKey, date, confidence, points, notScored, fitTo, framed, regionNames, focusRegion,
    todayStr]);

  const { attachFrame, canvasRef, geoFailed } = useHeatCanvas({
    enabled: points.length > 0 || framed.length > 0,
    measureKey: windowKey,
    aspect: frameAspect,
    minPx: MIN_MAP_PX,
    paint,
  });

  /**
   * The click test: nearest labelled centroid, but only within 26% of the frame's width.
   *
   * <p>The threshold is what makes empty sea a CLEAR rather than a selection of whichever region
   * happens to be least far away — on a map of northern England every pixel has a nearest region,
   * so without it there is no way to deselect by clicking the picture, and the design's "clicking
   * empty space clears" would be unreachable.
   *
   * <p>Measured in the canvas's own client box rather than in the projected width, because that is
   * where the pointer is: the two agree at DPR 1 and diverge nowhere that matters, but reading the
   * box the event was delivered against needs no assumption.
   */
  const handleClick = useCallback((event) => {
    if (!selectable || !frame || !onSelectRegion) return;
    const box = event.currentTarget.getBoundingClientRect();
    const x = event.clientX - box.left;
    const y = event.clientY - box.top;
    let nearest = null;
    let nearestDistance = Infinity;
    for (const label of frame.labels) {
      const distance = Math.hypot(label.x - x, label.y - y);
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = label.name;
      }
    }
    // Same region again clears, which is the design's own rule and the reason the map needs no
    // separate dismiss affordance — the band's "Show all regions ×" is the labelled route.
    if (nearest === null || nearestDistance >= box.width * PICK_RADIUS_FRACTION) onSelectRegion(null);
    else onSelectRegion(nearest === selectedRegion ? null : nearest);
  }, [selectable, frame, onSelectRegion, selectedRegion]);

  // The words survive a failed topology fetch; the empty frame does not. An unpainted map implies a
  // field with nothing in it, which is a different and false claim from "the picture is
  // unavailable" — the same call the strip makes.
  if (geoFailed) return null;

  return (
    <div data-testid="wf-row-map" className="wf-rowmap">
      <div ref={attachFrame} className="wf-mapbox">
        <canvas
          aria-hidden="true"
          data-testid="wf-row-map-canvas"
          ref={canvasRef(windowKey)}
          onClick={handleClick}
        />
        {/* `aria-hidden` with the canvas: these are the picture's own annotations, and the rail
            below names every one of them on a real button. */}
        <span className="wf-mlab" aria-hidden="true">
          {(frame?.labels || []).map((label) => (
            <span
              key={label.name}
              data-testid="wf-row-map-label"
              data-hot={label.name === selectedRegion ? 'true' : undefined}
              style={{ left: `${label.x}px`, top: `${label.y}px` }}
            >
              {label.name}
            </span>
          ))}
        </span>
        {selectable && (
          <span data-testid="wf-row-map-hint" className="wf-mhint" aria-hidden="true">
            {selectedRegion ? 'Select it again to clear' : 'Select a region'}
          </span>
        )}
        {/* A chip on the thing it describes, rather than the strip's footer clause — there is one
            map here and the whole of it is hatched, so "unshaded" would name nothing.

            `aria-hidden` with the canvas and the labels it sits among, and that is this
            component's own doctrine rather than an omission: the picture does not exist for a
            screen reader, so a caption decoding it would be noise. The accessible answer is the
            rail below, which already withholds `best N★` when nothing there is rated: it says
            less rather than saying something false, which is what this chip does in the visual
            channel. */}
        {notScored && (
          <span data-testid="wf-row-map-unscored" className="wf-mnote" aria-hidden="true">
            Not scored
          </span>
        )}
      </div>
    </div>
  );
}

WindowRowFieldMap.propTypes = {
  windowKey: PropTypes.string.isRequired,
  date: PropTypes.string.isRequired,
  confidence: PropTypes.string,
  spots: PropTypes.array.isRequired,
  /** The away origin ({@code {name}}), or null for home. Framing only. */
  origin: PropTypes.shape({ name: PropTypes.string.isRequired }),
  points: PropTypes.array.isRequired,
  bestRating: PropTypes.number,
  regionNames: PropTypes.arrayOf(PropTypes.string).isRequired,
  selectedRegion: PropTypes.string,
  reachById: PropTypes.instanceOf(Map),
  todayStr: PropTypes.string.isRequired,
  onSelectRegion: PropTypes.func,
};
