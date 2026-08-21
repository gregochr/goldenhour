import React, {
  useCallback, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import {
  aspect, bbox, centroid, clamp, drawGeo,
} from '../utils/heatField.js';
import { useHeatCanvas } from '../hooks/useHeatCanvas.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import { scopeSpots } from '../utils/planOrigin.js';
import { spotBadgeStyle } from '../utils/windowFirstSpots.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';

/**
 * The field's aspect clamps — portrait on desktop, nearly square on a phone.
 *
 * <p>Not kernel behaviour ({@code aspect()} has no clamps of its own), so they are this component's
 * constants and this component's boundary tests — the same split {@code thumbAspect} records.
 *
 * <p>⚠️ <b>The desktop band went from letterbox (0.36–0.62) to portrait (0.88–1.34) at M2, and the
 * reason is the layout rather than taste.</b> The map used to sit full-width across an open row
 * with the rail and band stacked beneath it, so a letterbox was the shape that left the words
 * visible. In the window popup it sits in the LEFT column of a two-column body (plan-matrix §6
 * M2.1) with the region cards and the prose beside it, so its box is roughly two-fifths of the
 * dialog and a letterbox inside that would be a postage stamp. The phone band is unchanged, because
 * the phone body is a single column and the constraint there — width to spare, height not — never
 * moved.
 */
export const MAP_ASPECT_MIN = 0.88;
/** @see MAP_ASPECT_MIN */
export const MAP_ASPECT_MAX = 1.34;
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
 * <p>{@code drawGeo} declines when EITHER dimension is 20px or smaller. It was derived from the old
 * 0.36 desktop floor, where a 56px-wide map is exactly 20px tall — <b>and the M2 band change
 * invalidated that arithmetic</b>: at 0.88, 56px wide is 49px tall and the height gate is nowhere
 * near. 56 stays as the WIDTH half of the same gate, which {@link useHeatCanvas} does not apply for
 * this component, and it remains a value no supported viewport reaches — so both halves are latent
 * rather than live. Kept rather than lowered because a map narrower than 56px is not a map, whatever
 * the kernel would consent to draw.
 */
const MIN_MAP_PX = 56;

/**
 * How close a pointer has to land to a region's centroid to select it, as a fraction of the frame's
 * width. Straight from the bundle (`plan-tab.js`: {@code bd < r.width*0.26}).
 */
const PICK_RADIUS_FRACTION = 0.26;

/** How many location chips the field may carry — the design's cap, and its phone cap. */
const CHIP_CAP = 8;
/** @see CHIP_CAP */
const CHIP_CAP_PHONE = 6;

/** How many candidates deep the placer looks for each slot it can fill — see the anchor loop. */
const CHIP_CANDIDATE_FACTOR = 3;

/**
 * The deepest candidate list this component will ever read, exported so the CALLER slices by the
 * same number rather than spelling its own.
 *
 * <p>The desktop figure, deliberately: the phone cap is smaller, so a caller handing over the
 * desktop depth on a phone simply gives the placer spares it does not need — where two independent
 * literals would silently starve it the moment {@link CHIP_CAP} moved.
 */
export const CHIP_CANDIDATES = CHIP_CAP * CHIP_CANDIDATE_FACTOR;

/**
 * Where a chip sits relative to its point, in px — half the marker, so the 5px square lands on it.
 *
 * <p>The bundle's own number ({@code p[0]-5.5} unflipped, {@code p[0]+5.5-w} flipped).
 */
const CHIP_OFFSET = 5.5;

/** Clearance between two placed boxes, and between a box and the frame's edge, in px. */
const BOX_GAP = 3;
/** @see BOX_GAP */
const EDGE_GAP = 2;

/**
 * The bottom-left corner the hint chip owns, as a box the placer must avoid, in px.
 *
 * <p>Measured rather than guessed would be better and is not worth the second layout pass: the chip
 * is one of two fixed strings in 9px mono at a fixed inset, so a generous constant is both simpler
 * and safer than a measurement that could arrive a frame late. Over-reserving drops a chip that
 * might have fitted; under-reserving paints a name over the one sentence saying the picture is
 * interactive.
 */
const HINT_BOX = { width: 118, height: 24 };

/** One frozen array, so a caller that draws no chips does not hand over a fresh prop each render. */
const EMPTY_CHIPS = Object.freeze([]);

/**
 * Whether a candidate box sits inside the frame and clear of everything already placed.
 *
 * <p>The bundle's own {@code fits}, with its two paddings named. Greedy and order-dependent by
 * design: the caller hands the chips over in the order they deserve the space, so an early chip
 * that fits keeps it and a later one that would overlap is dropped rather than drawn on top. An
 * unreadable name is worse than a missing one, and the ranked strip below the field lists every one
 * of them anyway.
 */
function fits(box, placed, frameWidth, frameHeight) {
  if (box.x < EDGE_GAP || box.y < EDGE_GAP) return false;
  if (box.x + box.width > frameWidth - EDGE_GAP) return false;
  if (box.y + box.height > frameHeight - EDGE_GAP) return false;
  return !placed.some((other) => box.x + box.width > other.x - BOX_GAP
    && box.x < other.x + other.width + BOX_GAP
    && box.y + box.height > other.y - BOX_GAP
    && box.y < other.y + other.height + BOX_GAP);
}

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
 * <p>⚠️ <b>The location CHIPS are the one exception, and it is a deliberate one (M4, D-3).</b> Given
 * an {@code onOpenLocation} handler they become named buttons and leave the hidden layer, because
 * they then <em>are</em> a capability — the route into one place's own four days — rather than an
 * annotation of the picture. Without a handler they stay inert spans inside it. That arm has no
 * production caller (the popup always offers the sheet), so it is a <em>default</em> rather than a
 * live mode: what it buys is that a future caller who forgets the prop gets a picture instead of a
 * field of dead buttons. The rail still names every region; the ranked strip below the dialog still
 * names every location, with its region, its drive and its departure — which is also what satisfies
 * WCAG 2.5.8 for a 16px chip, through the Equivalent exception rather than the minimum-size arm
 * ({@code index.css} carries that argument and its one gap).
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
 * <p>⚠️ <b>A region label is never DROPPED for want of room, and the location chips yield to it.</b>
 * The bundle places both layers in one greedy pass and drops whichever loses; here the labels are
 * this component's existing, tested behaviour and the chips are the new layer, so the new one gives
 * way. The measured cost is real and is recorded in the plan's phase log: on a narrow frame the
 * field can name no locations at all, because four region labels and the hint chip have taken the
 * space. The focused region's label is omitted (see the paint loop), which is the design's own rule
 * and buys back the part of the frame a reader is actually looking at.
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
 * @param {Function} [props.onOpenLocation] called with the clicked chip. Its ABSENCE is what keeps
 *   the chips inert annotations inside the `aria-hidden` layer; its presence turns every one of
 *   them into a named button. See the chip block's comment for why the two cannot be split.
 */
export default function WindowRowFieldMap({
  windowKey, date, confidence, spots, points, bestRating = null, regionNames, selectedRegion,
  origin = null, chips = EMPTY_CHIPS,
  reachById, todayStr, onSelectRegion, onOpenLocation = null,
}) {
  const isMobile = useIsMobile();
  const chipCap = isMobile ? CHIP_CAP_PHONE : CHIP_CAP;
  // Scope, not area — the planning area at home and the origin's own region when away, so the
  // open row's map is framed exactly as the six thumbnails above it are. One module, so a row and
  // the strip that opened it cannot disagree about what is in shot.
  const framed = useMemo(() => scopeSpots(spots, reachById, origin), [spots, reachById, origin]);
  /**
   * The catalogue indexed for the chip join — <b>id first, name second</b> (plan §3 rule 11).
   *
   * <p>A window's spot descriptor carries no coordinates: {@code buildWindowSpots} folds the
   * briefing's slots and the reach map and nothing else, and the latitude and longitude live on the
   * heat catalogue. So a chip is a join, and it is the same join every other surface in this arm
   * makes — the id when the payload carried one, the name only as a fallback, and never a
   * normalised name.
   */
  const geoByKey = useMemo(() => {
    const byKey = new Map();
    for (const spot of spots) {
      if (spot?.id != null && !byKey.has(spot.id)) byKey.set(spot.id, spot);
    }
    for (const spot of spots) {
      if (spot?.name && !byKey.has(spot.name)) byKey.set(spot.name, spot);
    }
    return byKey;
  }, [spots]);
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
   * Where each chip actually ended up, or null while the placer has not run for this frame.
   *
   * <p>Two renders per paint, and both are necessary. A chip's width is its location's name in a
   * font the browser may still be swapping, so it cannot be predicted — the bundle measures the
   * real element and so does this. The first render puts every candidate off-screen at
   * {@code left: -9999px} where it can be measured without being seen; the layout effect then
   * measures, runs the greedy pass, and commits the survivors. A chip that did not survive is
   * unmounted rather than hidden, so the DOM carries only what is drawn.
   *
   * <p>It cannot loop: the effect depends on {@code frame} and the cap, and writing placements
   * changes neither.
   */
  const [placed, setPlaced] = useState(null);
  const chipRefs = useRef(new Map());
  const labelRefs = useRef(new Map());

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
      // ⚠️ The FOCUSED region's own name is omitted, which is the design's own rule and the one
      // that pays for itself: the rail cell reads pressed and the prose slot's heading names that
      // region, so the label is the third statement of one fact — and it is the label sitting in
      // exactly the part of the field a reader is now looking at, where the location chips need the
      // room. Nothing is lost: the plate under it is the focused heat.
      if (name === selectedRegion) continue;
      const at = centroid(framed, name, toPoint);
      if (!at || !Number.isFinite(at[0]) || !Number.isFinite(at[1])) continue;
      labels.push({ name, x: at[0], y: at[1] });
    }
    // The chips' ANCHORS only. Whether each one is drawn is a measurement question the placer
    // answers after layout — see `placements` — because a chip's width is its name in a font that
    // may not have loaded yet.
    const anchors = [];
    // Bounded well above the cap rather than at it: the placer drops a chip that will not fit, so
    // it needs spares — but a pool can hold the whole roster, and rendering a hundred hidden spans
    // to measure eight is a layout pass nobody reads. Three times the cap is the smallest bound
    // that cannot plausibly starve a full frame, given the caller hands them over in rank order.
    for (const chip of chips.slice(0, chipCap * CHIP_CANDIDATE_FACTOR)) {
      const geo = geoByKey.get(chip.locationId) ?? geoByKey.get(chip.locationName) ?? null;
      if (!geo) continue;
      const at = toPoint(geo);
      if (!at || !Number.isFinite(at[0]) || !Number.isFinite(at[1])) continue;
      anchors.push({ ...chip, x: at[0], y: at[1] });
    }
    setFrame({ width, height, labels, chips: anchors });
  }, [windowKey, date, confidence, points, notScored, fitTo, framed, regionNames, focusRegion,
    selectedRegion, todayStr, chips, geoByKey, chipCap]);

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

  /**
   * The greedy placement pass — regions claim their space, then the strongest locations take what
   * is left.
   *
   * <p>Runs in a LAYOUT effect rather than an ordinary one because it reads geometry and then
   * writes positions: an ordinary effect would let the browser paint the off-screen measuring pass
   * first, and the chips would visibly fly in from the left edge on every repaint.
   *
   * <p>Region labels are seeded as occupied boxes and are <b>never dropped</b>, which is a
   * deliberate deviation from the bundle: there the region layer is placed by the same pass and can
   * lose a name to a crowded frame. Here the labels are the field's existing, tested behaviour (and
   * the rail below is their accessible equivalent), so the new layer yields to the old one rather
   * than the other way round.
   */
  useLayoutEffect(() => {
    // One write per frame, guarded on the frame's own identity rather than on the placements being
    // null — so a repaint supersedes the last answer without ever needing to clear it first, and a
    // stale placement (a name pinned to a coastline that has moved) cannot survive a paint.
    if (!frame || placed?.frame === frame) return;
    const boxes = [];
    // The hint chip's corner, and the unscored chip's when it is drawn — both are absolutely
    // positioned siblings the placer cannot see any other way.
    if (selectable) {
      boxes.push({
        x: 0, y: frame.height - HINT_BOX.height, width: HINT_BOX.width, height: HINT_BOX.height,
      });
    }
    if (notScored) {
      boxes.push({
        x: frame.width - HINT_BOX.width,
        y: frame.height - HINT_BOX.height,
        width: HINT_BOX.width,
        height: HINT_BOX.height,
      });
    }
    for (const label of frame.labels) {
      const node = labelRefs.current.get(label.name);
      if (!node) continue;
      const width = node.offsetWidth;
      const height = node.offsetHeight;
      // Centred by `transform: translate(-50%, -50%)`, so the box the placer must avoid is the
      // centroid minus half the measured size — not the top-left the style prop names.
      boxes.push({ x: label.x - width / 2, y: label.y - height / 2, width, height });
    }
    const map = new Map();
    for (const chip of frame.chips) {
      if (map.size >= chipCap) break;
      const node = chipRefs.current.get(chip.key);
      if (!node) continue;
      const width = node.offsetWidth;
      const height = node.offsetHeight;
      // A zero-width measurement means the browser has laid nothing out yet (and is the ordinary
      // state in jsdom). Placing on it would pin every chip to one point.
      if (!(width > 0) || !(height > 0)) continue;
      const top = chip.y - height / 2;
      let box = {
        x: chip.x - CHIP_OFFSET, y: top, width, height,
      };
      let flip = false;
      if (!fits(box, boxes, frame.width, frame.height)) {
        box = {
          x: chip.x + CHIP_OFFSET - width, y: top, width, height,
        };
        flip = true;
      }
      if (!fits(box, boxes, frame.width, frame.height)) continue;
      boxes.push(box);
      map.set(chip.key, { x: box.x, y: box.y, flip });
    }
    // ⚠️ A setState in an effect, and it is the case the rule's own escape hatch is for: this is a
    // MEASUREMENT. A chip's width is its name in a font the browser may still be swapping, so it
    // cannot be computed from props — the DOM has to be laid out and read. The write is idempotent
    // and bounded by the guard above (one per paint, and a paint is a resize, a font load or an
    // origin move), and it is a LAYOUT effect so the off-screen measuring pass is never painted.
    // `useLensReserve` and `useHeatCanvas` both solve the same problem the same way.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPlaced({ frame, map });
    // `placed` is read only as the guard on its own write; listing it would re-run this on that
    // write. `frame` is the identity that actually decides, and it is in the list.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [frame, chipCap, selectable, notScored]);

  /**
   * The placements that belong to the frame currently on screen, or null while the placer has not
   * run for it. Derived rather than stored, so a repaint can never render the previous frame's
   * coordinates for one commit.
   */
  const placements = placed?.frame === frame ? placed.map : null;

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
              ref={(node) => {
                if (node) labelRefs.current.set(label.name, node);
                else labelRefs.current.delete(label.name);
              }}
              data-testid="wf-row-map-label"
              style={{ left: `${label.x}px`, top: `${label.y}px` }}
            >
              {label.name}
            </span>
          ))}
        </span>

        {/* The layer that turns the field from areas into PLACES — and, since M4 (D-3, resolved),
            the shortest route into a place's own four-day sheet.

            ⚠️ THE THREE THINGS THAT LAND TOGETHER, because M2 deferred them as a set: the click,
            the `title`, and the exit from `aria-hidden`. A chip was an inert `pointer-events: none`
            span, and a `title` on one of those reaches nobody — the same "reaches nobody" defect
            the matrix card recorded about a `title` inside an `aria-hidden` subtree. Turning one on
            without the others would have shipped either a control no assistive technology can find
            or a tooltip nothing can hover.

            ⚠️ `aria-hidden` comes off ONLY when there is a handler, and that is the rule rather
            than a convenience. Without one these are still the picture's own annotations, sitting
            among a canvas and region labels that stay hidden for the reason the class comment
            gives; exposing them would put eight names into the tab order that do nothing, which is
            plan §3 rule 14's ban on a control with no visible effect. With one they are real
            controls and must be found, so they are.

            ⚠️ The chips now DO swallow the region click underneath them — `.wf-mhint`'s note
            records that as the trap the `pointer-events: none` avoided. It is deliberate: a chip
            names one place and the click that lands on it is about that place, while the region
            underneath is still selectable from every pixel the chips do not cover and, in the
            accessible channel, from the rail's named buttons. `.wf-mchips` keeps
            `pointer-events: none` so only the chip boxes themselves take the pointer. */}
        {frame && frame.chips.length > 0 && (
          <span
            data-testid="wf-row-map-chips"
            className="wf-mchips"
            aria-hidden={onOpenLocation ? undefined : 'true'}
            // ⚠️ A NAMED GROUP once the chips are controls. Without one a screen reader meets six
            // (phone) or eight bare "<place> N stars" buttons in rating order, immediately above a
            // strip naming the same places again with region, drive and departure — and nothing
            // says which list they are in. The canvas and the region labels stay `aria-hidden`
            // (they are the picture), so the spatial meaning that justifies these existing is
            // invisible to that reader; the group's name is the only thing that can say where they
            // are. Absent on the inert arm, where the layer is `aria-hidden` and there is nothing
            // to group.
            role={onOpenLocation ? 'group' : undefined}
            aria-label={onOpenLocation ? 'Places on the field map' : undefined}
          >
            {frame.chips
              .filter((chip) => placements == null || placements.has(chip.key))
              .map((chip) => {
                const at = placements?.get(chip.key) ?? null;
                // A button once there is somewhere to go, a span otherwise — see the block
                // comment. `Tag` rather than two near-identical JSX trees, so the plate, the
                // marker, the name and the rating cannot drift between the two states; the greedy
                // placer measures whichever one is rendered, and both carry `.wf-mchip`.
                const Tag = onOpenLocation ? 'button' : 'span';
                return (
                  <Tag
                    key={chip.key}
                    ref={(node) => {
                      if (node) chipRefs.current.set(chip.key, node);
                      else chipRefs.current.delete(chip.key);
                    }}
                    type={onOpenLocation ? 'button' : undefined}
                    data-testid="wf-row-map-chip"
                    data-location={chip.locationName}
                    data-flip={at?.flip ? 'true' : undefined}
                    className="wf-mchip"
                    // Region · drive · leave-by, built by the caller from the same spot descriptor
                    // the strip below draws — never re-derived here (plan §3 rule 13: `leaveBy` has
                    // one producer). Absent where the caller has nothing to say, so a chip never
                    // carries an empty tooltip.
                    title={onOpenLocation ? (chip.title || undefined) : undefined}
                    onClick={onOpenLocation ? () => onOpenLocation(chip) : undefined}
                    // Off-screen while the placer measures — see `placements`. `visibility` rather
                    // than `display: none`, because a display-none element measures as zero and the
                    // whole point of this pass is to measure it. It also keeps an unplaced
                    // candidate out of the tab order, which `display: none` and `visibility` both
                    // do and an opacity would not.
                    style={at
                      ? { left: `${at.x}px`, top: `${at.y}px` }
                      : { left: '-9999px', top: '0px', visibility: 'hidden' }}
                  >
                    <i className="wf-mchip-m" aria-hidden="true" />
                    <b className="wf-mchip-n">{chip.locationName}</b>
                    {chip.rating != null && (
                      // ⚠️ `spotBadgeStyle`, not the raw ramp as ink. Measured on this chip's own
                      // `rgba(14,11,9,.84)` plate, the ramp's bottom two stops come out at 3.24:1
                      // (1★) and 4.04:1 (2★) — under AA at 9px, and worse where the plate sits over
                      // a lit field. The arm's existing fill-plus-`readableInkOn` pair is the
                      // measured answer and is what the matrix card's own best-reach rating uses,
                      // so the two surfaces state a rating the same way.
                      <em className="wf-mchip-r" style={spotBadgeStyle(chip.rating) ?? undefined}>
                        {/* NVDA at its default symbol level does not speak U+2605, so a chip that
                            is now a named control would announce a bare integer after the place
                            name. The sheet this opens spells it out the same way, at
                            `LocationFourDaySheet`'s own rating badge. The glyph stays as the label,
                            which is what keeps 2.5.3 satisfied: the accessible name contains every
                            visible WORD in order, and `★` is a symbol rather than one. */}
                        <span aria-hidden="true">{`${chip.rating}★`}</span>
                        {onOpenLocation && <span className="sr-only">{`${chip.rating} stars`}</span>}
                      </em>
                    )}
                  </Tag>
                );
              })}
          </span>
        )}
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
  /**
   * The locations the field may name, in the order they deserve the space — focused region first,
   * then rating, then drive. The caller owns that ordering (and the pool it comes from) so the map
   * can never name a spot the list below has excluded; this component only decides which of them
   * physically fit.
   */
  chips: PropTypes.arrayOf(PropTypes.shape({
    key: PropTypes.string.isRequired,
    locationId: PropTypes.number,
    locationName: PropTypes.string.isRequired,
    regionName: PropTypes.string,
    rating: PropTypes.number,
    /** Region · drive · leave-by, already worded by the caller. */
    title: PropTypes.string,
  })),
  regionNames: PropTypes.arrayOf(PropTypes.string).isRequired,
  selectedRegion: PropTypes.string,
  reachById: PropTypes.instanceOf(Map),
  todayStr: PropTypes.string.isRequired,
  onSelectRegion: PropTypes.func,
  onOpenLocation: PropTypes.func,
};
