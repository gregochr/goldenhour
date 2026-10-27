import {
  useCallback, useEffect, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';
import L from 'leaflet';
import { useMap } from 'react-leaflet';
import {
  clamp, drawTiles, fit, land, load, radiusFor,
} from '../utils/heatField.js';
import { createLandMask } from '../utils/landMask.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import { useHeatCanvas } from '../hooks/useHeatCanvas.js';
import { getMode } from '../utils/scoreRamp.js';
import { pxPerKmAtHome, RING_MIN_PX, RING_TIERS } from '../utils/reachRings.js';
import { fadeAt } from '../utils/heatHandover.js';

/**
 * The pane the field is painted into, and its stacking order.
 *
 * <p>350 is deliberately between Leaflet's own tile pane (200) and its overlay/marker panes
 * (400/600), so the field sits ON the basemap and UNDER everything that means something specific:
 * the azimuth lines, the aurora viewline, the label chips and every popup. A field painted above
 * them would be a wash over the answer rather than the ground beneath it.
 */
const HEAT_PANE = 'wf-heat';
const HEAT_PANE_Z = 350;

/** One canvas, so the hook's keyed registry has exactly one entry. */
const CANVAS_KEY = 'field';

/**
 * The handover band (D8): the zoom range across which the Legend's handover indicator reads — moved
 * to `utils/heatHandover.js` at map-tab-v2-plan.md §3 P10 (its own doc comment carries the full
 * "why 10.4→12.0/0.12" reasoning) so the Legend panel's handover indicator can read the same
 * fraction without eagerly pulling this module's own `heatField.js` import into every mount's
 * bundle. Re-exported below so this file's own existing imports/tests are unaffected.
 */
export { fadeAt };

/**
 * The kernel dials for this host — §4.5's numbers, and the prototype's before that.
 *
 * <p>Re-tuned at map-tab-v2-plan.md §3 P4 — {@code 7200m / 30–190px}, from
 * {@code 8500m / 34–240px} — in the SAME commit as the land clip below, because the two were
 * co-tuned: the old radius is part of why the field swam offshore
 * (docs/design/map-tab-v2/README.md, "The heat field").
 *
 * <p>{@code HEAT_OPACITY} corrected 0.9 → 0.92 on 2026-09-03: the design bundle's map surface has
 * always painted at {@code opacity:0.92*alpha} (docs/design/map-tab-v2/map-tab-v2.js:173), but 0.9
 * was the pre-existing repo value the P4 re-tune above never listed as a deliberate change — an
 * unledgered drift a spec-vs-shipped audit caught. See map-tab-v2-plan.md §4 for the ledger entry.
 *
 * <p>{@code RADIUS_MAX_PX} re-tuned 190 → 400 on 2026-10-02 (owner report: field invisible at zoom
 * ~12 with 4-star chips). At zoom 12 (~22 m/px at lat 55) 7.2 km is ~330 px, so the old cap bit and
 * shrank the field into blobs around each chip instead of covering the same countryside; it stacked
 * with the 0.12 floor in `heatHandover.js` (now 0.35).
 */
const HEAT_OPACITY = 0.92;
const RADIUS_METRES = 7200;
const RADIUS_MIN_PX = 30;
const RADIUS_MAX_PX = 400;
const GRID = 6;
const BLUR = 4;

/**
 * The bloom flag for this surface — `docs/design/map-tab-v2/README.md`, "The heat bloom (required
 * on a dark ground)" table's "Map tab field" row (3.0/190/2.4). No `bloomFrom`/`bloomA`/`bloomBlur`
 * dial is set here: the kernel's own defaults in `heatField.js` (`field()`'s `bloomFrom`/`bloomA`,
 * `blitField`'s `bloomBlur`) already ARE that row, so this host asks only for the layer to exist.
 * `bloomFrom` must stay at 3.0 on every surface painted with the shared/temperature ramp,
 * whose luminance peaks at 3★ — to tame a small surface, cut `bloomBlur`, never raise the gate.
 * A monotonic ramp injected via `opts.ramp` (aurora, plan §4 #4) may pass its own `bloomFrom`
 * (3.4), because it has no dead band to leave.
 */
const FIELD_BLOOM = 1;

/**
 * The land clip (map-tab-v2-plan.md §3 P4) — dropped at/above this zoom, where a 1:50m
 * coastline's own survey error would show as a visibly false coast. By then the field is already
 * close to `heatHandover.js`'s {@code FADE_TO} floor opacity, so an unclipped wash over water reads less wrong than a
 * hard edge in the wrong place (docs/design/map-tab-v2/README.md, "The heat field").
 *
 * <p>Re-checked 2026-10-02 after the floor rose to 0.35: the field now shows above this zoom and
 * spills softly over the sea at the Northumberland coast. Extending the clip to zoom 14 was tried
 * and rejected — at zoom 12 around Bamburgh the 1:50m coast cut straight diagonals through the
 * water and across the headland, a visibly false coast, worse than a graded wash. Left at 11.5.
 */
const LAND_CLIP_MAX_ZOOM = 11.5;

/**
 * The outline of a Lite reader's sub-regions is 1.4px stroked zoomed out and 1.6px zoomed in (the S5
 * handoff, task 1), moving between the two across the zooms the field is read at.
 */
const OUTLINE_WIDTH_FAR = 1.4;
const OUTLINE_WIDTH_NEAR = 1.6;
const OUTLINE_ZOOM_FAR = 8;
const OUTLINE_ZOOM_NEAR = 11;

/**
 * The outline's stroke width at a zoom: {@link OUTLINE_WIDTH_FAR} at/below zoom 8, rising linearly to
 * {@link OUTLINE_WIDTH_NEAR} at/above zoom 11.
 *
 * @param {number} zoom the map's current zoom
 * @returns {number} the width in CSS px
 */
export function outlineWidthAt(zoom) {
  const t = clamp((zoom - OUTLINE_ZOOM_FAR) / (OUTLINE_ZOOM_NEAR - OUTLINE_ZOOM_FAR), 0, 1);
  return OUTLINE_WIDTH_FAR + (OUTLINE_WIDTH_NEAR - OUTLINE_WIDTH_FAR) * t;
}

/**
 * The clip's own blur, in px — a hard {@code ctx.clip()} would put a crisp edge through a blurred
 * field, which reads as an artefact wherever the eye knows the line should not be sharp.
 */
const LAND_CLIP_SOFT_PX = 4;

/**
 * The seaward dilation of the land mask, in metres of Natural Earth 1:50m survey slack. Without
 * it, clipping raw erased 7 of 51 coastal locations (including a 5★) by cutting through their own
 * gaussian core — a rated location painting as empty ground being worse than the bug the clip
 * fixes (docs/design/map-tab-v2/README.md, "The heat field").
 */
const COAST_ERROR_METRES = 4200;
const COAST_ERROR_MIN_PX = 3;
const COAST_ERROR_MAX_PX = 120;

/**
 * The stand-down hatch's disc radius (big-seas plan F3): the design's `radiusFor(map, 6500, 22, 150)`.
 * Smaller than the field's own radius, so a stood place's hatch hugs the place.
 */
const STAND_RADIUS_METRES = 6500;
const STAND_RADIUS_MIN_PX = 22;
const STAND_RADIUS_MAX_PX = 150;

/**
 * The coastline stroke's own fade-out, independent of the land clip's own threshold above: the
 * stroke is gone by zoom 11, a county-scale zoom below where the clip itself drops (11.5), because
 * past it the basemap's own detail has taken over asserting the coast.
 */
const COAST_STROKE_MAX_ZOOM = 11;
const COAST_STROKE_FADE_SPAN = 1.6;
const COAST_STROKE_MAX_ALPHA = 0.5;
const COAST_STROKE_WIDTH = 0.8;
const COAST_STROKE_RGB = '242,231,211';

/**
 * The coastline stroke's alpha at a given zoom — {@code clamp((11 − zoom) / 1.6, 0, 1) × 0.5}.
 *
 * <p>Exported so the fade can be pinned precisely on pure numbers, the way {@link fadeAt} already
 * is, rather than parsed back out of a canvas {@code strokeStyle} string.
 *
 * @param {number} zoom the map's current zoom
 * @returns {number} 0 at/above {@link COAST_STROKE_MAX_ZOOM}, rising to 0.5 as the zoom falls
 */
export function coastAlphaAt(zoom) {
  return clamp(
    (COAST_STROKE_MAX_ZOOM - zoom) / COAST_STROKE_FADE_SPAN, 0, 1,
  ) * COAST_STROKE_MAX_ALPHA;
}

/**
 * Reach rings (map-tab-v2-plan.md §3 P8) — dashed circles at the shared 25/50 mi tiers around
 * home, drawn on the SAME canvas as the coastline stroke, in the same paint pass. The tiers are
 * `utils/reachRings.js`'s one definition, shared with `WindowRowFieldMap`'s own rings on the Plan
 * popup's field map (decision D-4) — a second set of numbers here would silently disagree with
 * that surface's circles.
 *
 * <p>Gated on the SAME zoom the field/label handover treats as "still answering WHERE" (README
 * "Reach rings": below zoom 10.6) — past it the question has become WHICH, and a ring answering a
 * distance question has nothing left to add. Also gated on a saved home postcode and the `rings`
 * prop (default false here; the Map tab's own state defaults it true and is where a future Legend
 * panel toggle — P10 — will reach it).
 */
const RING_MAX_ZOOM = 10.6;
const RING_DASH = [3, 4];
const RING_STROKE = 'rgba(201,162,75,.42)';
const RING_LINE_WIDTH = 1;

/**
 * A ring wider than this multiple of the frame's larger side is entirely off-frame — skip it.
 * {@code WindowRowFieldMap}'s own constant of the same name and value; not exported from
 * `utils/reachRings.js` because it is a per-host DISPLAY concern (how big a frame this host has),
 * not a fact about the tiers themselves — see that module's own note on why only the tiers and the
 * legibility floor are shared.
 */
const RING_OFFFRAME_FACTOR = 1.15;

/**
 * Draws the reach rings straight in CONTAINER coordinates via {@code latLngToContainerPoint} —
 * exactly like {@code drawTiles}'s own points, and UNLIKE the coastline stroke just above: there
 * is no absolute-pixel {@code Path2D} here, so the canvas already being glued to the container
 * origin (`setPosition`, in the paint callback) is enough on its own — no `clipDx`/`clipDy`
 * translate is needed or applied.
 *
 * @param {CanvasRenderingContext2D} ctx the field's own context, already sized via {@code fit}
 * @param {object} map the Leaflet map
 * @param {{lat: number, lon: number}} homeCoords
 * @param {number} width the frame's width, in px — for the off-frame skip
 * @param {number} height the frame's height, in px — for the off-frame skip
 */
function drawReachRings(ctx, map, homeCoords, width, height) {
  const homePt = map.latLngToContainerPoint([homeCoords.lat, homeCoords.lon]);
  // Measured at HOME's own latitude, never the viewport centre's (map-tab-v2-plan.md §3 P8
  // review) — `utils/reachRings.pxPerKmAtHome`'s own doc comment has the full reasoning: Web
  // Mercator's ground resolution varies with latitude, so a ring centred on a fixed point must be
  // measured at THAT point to stay a stable size as the reader pans.
  const pxPerKm = pxPerKmAtHome(map, homeCoords);
  ctx.save();
  ctx.setLineDash(RING_DASH);
  ctx.lineWidth = RING_LINE_WIDTH;
  ctx.strokeStyle = RING_STROKE;
  for (const ring of RING_TIERS) {
    const r = ring.km * pxPerKm;
    // The SAME two skip rules `WindowRowFieldMap`'s own rings apply: illegibly small (the shared
    // `RING_MIN_PX` floor), or so large the circle is entirely off-frame (a per-host ceiling —
    // see `RING_OFFFRAME_FACTOR`'s own note on why it is not shared).
    if (r < RING_MIN_PX || r > Math.max(width, height) * RING_OFFFRAME_FACTOR) continue;
    ctx.beginPath();
    ctx.arc(homePt.x, homePt.y, r, 0, Math.PI * 2);
    ctx.stroke();
  }
  ctx.restore();
}

/**
 * The heat field, hosted on the Leaflet map — the same kernel the Plan tab's strip and row maps
 * paint, over tiles instead of a vendored coastline.
 *
 * <h2>Why it is a third consumer of one hook and not a third canvas host</h2>
 *
 * <p>P3 extracted {@code useHeatCanvas} from the strip and measured, in the same breath, that it
 * did not yet generalise here: it waited on {@code land()} (which {@code drawTiles} never reads),
 * it had no throttled imperative repaint, and its change gate watched width alone. P4 widened those
 * three rather than writing a host that would drift from the other two — the hook's own docs record
 * each. What this component owns is what a host is allowed to own: its pane, its dials and its
 * event wiring.
 *
 * <h2>Throttle, never debounce</h2>
 *
 * <p>{@code move}/{@code zoom}/{@code viewreset}/{@code resize} take the throttled
 * {@code repaint} — at most one paint per animation frame, and the FIRST event in a frame is the
 * one that schedules it. {@code moveend}/{@code zoomend} take {@code repaintNow}, which paints in
 * the calling tick. A debounce would push the frame back on every event of a drag and so paint
 * nothing at all until the finger stopped, which is the stale-overlay lesson `map-tab.js:96-100`
 * records.
 *
 * <h2>The canvas lives in a pane, so it has to be pushed back to the container origin</h2>
 *
 * <p>{@code drawTiles} projects through {@code map.latLngToContainerPoint}, i.e. in CONTAINER
 * coordinates. A Leaflet pane sits inside {@code _mapPane}, which is translated as you drag — so a
 * canvas at the pane's own origin drifts by exactly that translation. {@code setPosition} to
 * {@code containerPointToLayerPoint([0, 0])} cancels it. The reward for being inside the pane
 * rather than floating above the map is that a zoom ANIMATION scales the field with the tiles, for
 * free, and {@code zoomend}'s un-throttled repaint then redraws it sharp.
 *
 * @param {object}   props
 * @param {Array}    props.points the kernel points for the selected window, already filtered to
 *        the places the map's own controls say count (see {@code MapView}'s `heatPoints`)
 * @param {Array}    [props.unrated] the window's "not rated yet" places ({@code unratedPointsFor}'s
 *        shape), already narrowed as {@code points} is; the ground around them is hatched UNDER the
 *        field (thin-sample plan F4). Null or empty draws nothing, so a night, an unscored window
 *        and every existing caller paint exactly what they did.
 * @param {?Array}  [props.outside] the places OUTSIDE a Lite reader's sub-regions ({@code gated}), same
 *        point shape as {@code unrated}; present (even empty) only when the briefing body is shaped.
 *        With {@code allowed} it draws the veil under the field and the dashed outline after it, both
 *        clipped to land and so only below {@link LAND_CLIP_MAX_ZOOM}. Null (every Pro, Admin and
 *        flag-off reader) spreads no key, so the kernel sees today's exact options.
 * @param {?Array}  [props.allowed] every other place, rated or not (the veil yields to them, the
 *        outline traces them). Ignored without {@code outside}.
 * @param {?number}  props.conf   the window's confidence as a 0–1 scalar; the kernel desaturates
 *        and thins with it, so a day-4 guess cannot look as authoritative as tonight
 * @param {?{lat: number, lon: number}} [props.homeCoords] the saved home postcode's coordinates,
 *        or null when none is saved — gates the reach rings (map-tab-v2-plan.md §3 P8) exactly as
 *        it gates `CentreOnHomeControl`.
 * @param {boolean}  [props.rings] whether the reach rings are switched on. Defaults false so a
 *        caller that never passes it (every existing mount) draws none — the Map tab's own state
 *        defaults to true and is threaded down explicitly.
 * @param {number}   [props.dim] a factor on the FIELD's fill opacity, 1 by default (no change). Ask
 *        PhotoCast's picks pass less than 1 so the field steps back behind the numbered chips; it
 *        reaches the fill inside the draw and nothing else, so the coastline stroke and the reach
 *        rings that share the canvas keep their own strength. A change repaints (it is a dependency
 *        of the paint callback, which is what the canvas hook repaints on).
 * @param {Function} [props.ramp] a `(score) => [r, g, b]` colour function handed to the kernel as
 *        `opts.ramp` (aurora, `aurora-heat-plan.md` §3 A2); null by default, which leaves the
 *        kernel on `scoreRamp`'s module state exactly as before.
 * @param {{rgb: number[], from: number, a: number}} [props.bloom] the bloom colour, gate and
 *        strength of an injected ramp (`AURORA_BLOOM`). When set it asks for the bloom whatever the
 *        colour-scale preference is (plan D3); null leaves the temperature-only rule.
 */
export default function MapHeatLayer({
  points, unrated = null, stood = null, outside = null, allowed = null, conf = null, colourMode = null,
  homeCoords = null, rings = false, dim = 1, ramp = null, bloom = null,
}) {
  const map = useMap();

  /**
   * The land clip's own {@code Path2D} cache (map-tab-v2-plan.md §3 P4) — one per map mount, in a
   * state initialiser for the same reason the pane below is: it must exist before the first paint
   * runs, not after a second render pass triggered by a {@code useMemo} recompute.
   */
  const [landMask] = useState(() => createLandMask(map));

  /**
   * The pane, made in a state initialiser so it exists on the FIRST render.
   *
   * <p>The {@code CentreOnHomeControl} precedent, and for the same reason: the portal needs
   * somewhere to go without a second render pass, and here it is also what lets the canvas be
   * registered before the hook's mount effect runs — otherwise the first paint would depend on the
   * measurement retry noticing a canvas that had arrived late.
   *
   * <p>{@code getPane} first, because {@code createPane} builds a fresh div and overwrites the
   * map's registry every time it is called: under StrictMode's double invocation that would leak
   * one detached pane per mount, and the portal would render into the one the map had forgotten.
   */
  const [pane] = useState(() => {
    if (!map?.createPane) return null;
    const el = map.getPane?.(HEAT_PANE) || map.createPane(HEAT_PANE);
    if (el?.style) {
      el.style.zIndex = String(HEAT_PANE_Z);
      // The field is a picture, not a control: every click belongs to the map or to a chip.
      el.style.pointerEvents = 'none';
    }
    // ⚠️ Leaflet does NOT scale a pane's contents during a zoom animation. Only elements carrying
    // `leaflet-zoom-animated` and their own `zoomanim` handler do (`GridLayer`, `Renderer`); the map
    // merely puts `leaflet-zoom-anim` on `_mapPane` and fires the event. And `_move` suppresses
    // `zoom`/`move` for the whole 250 ms of a wheel or double-click zoom, so this layer would hold
    // its pre-zoom pixels at its pre-zoom offset and then snap. `leaflet-zoom-hide` is Leaflet's own
    // answer for a layer that does not animate: the field steps aside for the transition and
    // `zoomend`'s un-throttled repaint brings it back sharp, which is honest where a detached smear
    // is not.
    el?.classList?.add('leaflet-zoom-hide');
    return el || null;
  });

  /**
   * The host's own measurement — the map's size, which is the SAME quantity {@code drawTiles} will
   * use when it paints.
   *
   * <p>The hook's default derivation cannot work here: a Leaflet pane is absolutely positioned with
   * no intrinsic box, so the canvas's parent measures 0 wide. Handing the hook {@code map.getSize()}
   * also closes, by construction, the class of defect P2 was caught by in a browser — one end
   * measuring a different quantity from the other.
   */
  const measure = useCallback(() => {
    // ⚠️ `offsetWidth` first, and it is not a second opinion about the size — it is the only way to
    // tell that the map is not on screen. The shell hides a deselected panel with `display: none`
    // rather than unmounting it, and Leaflet's `getSize()` returns its CACHED `_size`, so a hidden
    // map still reports 800×500 quite happily. Without this every briefing poll ran a full
    // `drawTiles` into a canvas nobody could see, for as long as the reader stayed on the Plan tab.
    const container = map?.getContainer?.();
    if (container && !container.offsetWidth) return null;
    const size = map?.getSize?.();
    return size ? { width: size.x, height: size.y } : null;
  }, [map]);

  // {@link fadeAt}'s `markers` component survives as the Legend indicator's own fraction
  // (`MapView`'s `legendHandoverFraction`, via `utils/heatHandover.js`): the handover's progress,
  // no longer an opacity anything paints at.

  const paint = useCallback(({ width, height, canvases }) => {
    const canvas = canvases.get(CANVAS_KEY);
    if (!canvas || !map?.getZoom) return;
    L.DomUtil?.setPosition?.(canvas, map.containerPointToLayerPoint([0, 0]));
    const zoom = map.getZoom();
    const fade = fadeAt(zoom);
    const standRadius = () => radiusFor(map, STAND_RADIUS_METRES, STAND_RADIUS_MIN_PX, STAND_RADIUS_MAX_PX);

    /* The land clip (map-tab-v2-plan.md §3 P4) — dropped at/above LAND_CLIP_MAX_ZOOM. `landPath`
       can still be null below the threshold while the topology is in flight: the kernel treats a
       null `clipPath` as no clip at all, which is the graceful, prototype-matching fallback the
       mount effect below resolves out of the moment `load()` settles. */
    const clipActive = zoom < LAND_CLIP_MAX_ZOOM;
    const landPath = clipActive ? landMask.get() : null;
    let clipOpts = null;
    if (clipActive) {
      const origin = map.getPixelBounds().min;
      clipOpts = {
        clipPath: landPath,
        clipSoft: LAND_CLIP_SOFT_PX,
        clipGrow: radiusFor(map, COAST_ERROR_METRES, COAST_ERROR_MIN_PX, COAST_ERROR_MAX_PX),
        clipDx: -origin.x,
        clipDy: -origin.y,
      };
    }

    drawTiles(canvas, map, points, POINT_SCORE_INDEX, {
      radius: radiusFor(map, RADIUS_METRES, RADIUS_MIN_PX, RADIUS_MAX_PX),
      grid: GRID,
      blur: BLUR,
      // Null rather than 1 when the window carries no tier: `field` reads `conf == null` as full
      // confidence, which is the honest default for a window the backend declined to qualify —
      // the haze is a qualifier on a claim, not a claim of its own.
      conf,
      // `dim` steps the FILL back while Ask's picks are on the map (`MapView`'s `ASK_HEAT_DIM`). It
      // is a factor here, inside the draw, and nowhere else: the coastline stroke and the reach
      // rings below are drawn on this same canvas, and an opacity on the pane would dim them too.
      opacity: HEAT_OPACITY * fade.heat * dim,
      // Bloom only in TEMPERATURE mode (map-tab-v2-plan.md §3 P2, decision D-1): the bloom's whole
      // rationale is the temp ramp's luminance inversion, and the verdict ramp has none — an ember
      // glow over a green "go" would be a false signal. In verdict mode this spreads NO keys at
      // all, not a falsy one, so `drawTiles` sees today's exact options object.
      // The aurora arm comes FIRST (plan D3): an injected `bloom` asks for the glow with its own
      // colour, gate and strength whatever the persisted colour scale is.
      ...(bloom
        ? { bloom: FIELD_BLOOM, bloomFrom: bloom.from, bloomA: bloom.a, bloomRGB: bloom.rgb }
        : getMode() === 'temp' ? { bloom: FIELD_BLOOM } : null),
      ...(ramp ? { ramp } : null),
      // Absence, not an empty array: `drawTiles` then sees today's exact options object.
      // `opacity` above is the hatch's too: `paintUnknown` reads it from these same options, so the
      // hatch fades with the field at the handover and under Ask's dim.
      ...(unrated?.length ? { unrated } : null),
      // The stand-down hatch (big-seas plan F3), the same absence rule; it fades and dims with the
      // field through the same `opacity`.
      ...(stood?.length ? { stood, standRadius: standRadius() } : null),
      // Outside the picks (sub-regions-plan S5a): only for a shaped body, and only where the land clip
      // is live (below LAND_CLIP_MAX_ZOOM, with the land path in hand) — the veil and the outline are
      // statements about LAND, and unclipped they would wash the sea. Absence, not an empty array.
      ...(Array.isArray(outside) && clipActive && landPath
        ? { outside, allowed: allowed || [], outlineWidth: outlineWidthAt(zoom) } : null),
      // Same rule as the bloom above: below LAND_CLIP_MAX_ZOOM this spreads all five clip keys
      // (clipPath may still be null); at/above it, none at all — absence, not a falsy value.
      ...(clipOpts || null),
    });


    /* Coastline stroke, from the SAME Path2D the clip uses (never a second geometry) — drawn on
       the same canvas, after the field, whenever the fade alpha is above zero: even an empty
       point set or a field already at its floor opacity still gets a coast, because the coast is
       furniture, not a claim about the data (map-tab-v2-plan.md §3 P4). Skipped only when there is
       no path to stroke — the same graceful "topology not here yet" fallback as the clip above. */
    const coastAlpha = coastAlphaAt(zoom);
    if (coastAlpha > 0 && landPath) {
      // Reacquires the SAME context `drawTiles` just painted into — `fit`'s backing-store write is
      // guarded against reallocation when the size has not changed, so this costs a transform
      // reset, not a fresh canvas.
      const ctx = fit(canvas, width, height);
      ctx.save();
      ctx.translate(clipOpts.clipDx, clipOpts.clipDy);
      ctx.strokeStyle = `rgba(${COAST_STROKE_RGB},${coastAlpha.toFixed(3)})`;
      ctx.lineWidth = COAST_STROKE_WIDTH;
      ctx.stroke(landPath);
      ctx.restore();
    }

    /* Reach rings (map-tab-v2-plan.md §3 P8) — same canvas, same paint pass, drawn last so they
       sit over the field and the coastline rather than under them. Home-gated, toggle-gated and
       zoom-gated; see `drawReachRings`'s own doc comment for why no clip translate is needed. */
    if (rings && zoom < RING_MAX_ZOOM
      && homeCoords?.lat != null && homeCoords?.lon != null) {
      const ctx = fit(canvas, width, height);
      drawReachRings(ctx, map, homeCoords, width, height);
    }
  // `colourMode` is a REPAINT KEY, not a colour source. The heat kernel paints from
  // `scoreRamp`'s live module state (`heatField.js` -> `rampRgb`), which this callback's other
  // dependencies cannot see: when the settings fetch resolves after the first paint, `MODE`
  // changes with no prop or datum changing, so a memoised paint callback keeps the OLD ramp on
  // the canvas while the markers and legend — which do read live — repaint. That is the Plan
  // thumbnails and the map bitmap disagreeing with everything around them, and it is a RACE:
  // whether it happens depends on which fetch resolves first. Threading the resolved mode into
  // the dependency array is what makes the repaint happen; its value is never read for colour.
  // `ramp`/`bloom` are the opposite — REAL inputs, read above — and sit in the same array so that
  // Aurora -> Sunset -> Aurora repaints with the right ramp each way (aurora-heat-plan.md §6).
    // Referenced so it is a REAL dependency, not a suppressed one: `exhaustive-deps` cannot see
    // that this callback's colours come from `scoreRamp`'s module state, so it reads the entry as
    // unnecessary. `void` makes the dependency honest and keeps the rule on.
    void colourMode;
  }, [map, points, unrated, stood, outside, allowed, conf, colourMode, landMask, rings, homeCoords,
    dim, ramp, bloom]);

  const {
    attachFrame, canvasRef, geoFailed, repaint, repaintNow,
  } = useHeatCanvas({
    enabled: true,
    measureKey: CANVAS_KEY,
    paint,
    measure,
    // `drawTiles` itself draws no coastline and clips to nothing — the basemap carries the
    // geography; the land clip and coastline stroke above are painted by THIS component, straight
    // onto `drawTiles`'s own canvas, after it returns. With `requiresLand` true the hook would
    // decline every paint — the whole field, not just the clip — until the topology resolved,
    // which is the opposite of the graceful "paint unclipped, then upgrade" fallback P4 wants,
    // so the topology fetch and its retry are owned by this component's own mount effect below,
    // never by the hook.
    requiresLand: false,
  });

  /**
   * Fetches the vendored UK land geometry the clip and the coastline stroke both need
   * (map-tab-v2-plan.md §3 P4). `load()` shares one in-flight promise with every other caller
   * (see its own doc comment), so mounting this alongside the Plan tab's thumbnails costs nothing
   * extra, and calling it again on a remount is free once the topology has already resolved.
   *
   * <p>A rejection is logged and swallowed, never rethrown: the field already paints unclipped, so
   * a permanent blank would be the wrong failure mode for geometry that is cosmetic to the rest of
   * the tab, not load-bearing.
   *
   * <p>{@code alreadyLoaded} mirrors {@code useHeatCanvas}'s own identical guard on its
   * {@code requiresLand} path: {@code load()} resolves immediately when the Plan tab (or an
   * earlier mount of this same component) has already fetched the topology, and repainting again
   * in that case would be a doubled paint on every mount after the first, of a clip the FIRST
   * paint already had — {@code landMask.get()} reads {@code land()} itself, so nothing was
   * missing.
   */
  useEffect(() => {
    let cancelled = false;
    const alreadyLoaded = land() != null;
    load()
      .then(() => {
        if (cancelled || alreadyLoaded) return;
        // The cache is keyed on zoom, and the topology arriving does not change the zoom — so
        // without this the clip would stay absent until the reader's next pan or zoom.
        landMask.invalidate();
        repaintNow();
      })
      .catch((err) => {
        console.error('[MapHeatLayer] land geometry failed to load; the field stays unclipped', err);
      });
    return () => { cancelled = true; };
  }, [landMask, repaintNow]);

  /**
   * The settle, deduped on the VIEW rather than on the tick.
   *
   * <p>⚠️ Leaflet's {@code _moveEnd} fires {@code zoomend} and then {@code moveend} synchronously on
   * every zoom, and §4.5 binds the un-throttled repaint to both — so a plain binding paints the same
   * picture twice at the end of every zoom gesture, which is exactly what {@code repaintNow}'s
   * cancelled frame exists to prevent one frame later.
   *
   * <p>The fix has to key on the view and not on the tick. A same-tick latch was tried and is a
   * correctness bug rather than an optimisation: it also swallows a settle for a view that HAS
   * moved, and a map driven through several zooms inside one task then keeps the fade it computed
   * for the first of them. That was measured in a browser, not reasoned about.
   */
  const lastView = useRef('');
  const settle = useCallback(() => {
    if (typeof map?.getCenter !== 'function') return;
    const c = map.getCenter();
    const view = `${c.lat},${c.lng},${map.getZoom()}`;
    if (view === lastView.current) return;
    lastView.current = view;
    repaintNow();
  }, [map, repaintNow]);

  useEffect(() => {
    if (typeof map?.on !== 'function') return undefined;
    // The land mask is cached on zoom alone, so a genuine zoom change already busts it on the
    // next `get()` — this is belt-and-braces, not load-bearing. map-tab-v2-plan.md §3 P4 names
    // both triggers explicitly, and `map.project` (what the cache is built from) does not depend
    // on the container at all, so a resize invalidation costs one Path2D rebuild against a
    // mistake, never a correctness fix.
    const invalidateMask = () => landMask.invalidate();
    map.on('move zoom viewreset resize', repaint);
    map.on('moveend zoomend', settle);
    map.on('zoomend resize', invalidateMask);
    return () => {
      map.off('move zoom viewreset resize', repaint);
      map.off('moveend zoomend', settle);
      map.off('zoomend resize', invalidateMask);
    };
  }, [map, repaint, settle, landMask]);

  /**
   * The container as the hook's observed frame.
   *
   * <p>Leaflet's own {@code resize} event fires only when something calls {@code invalidateSize},
   * and on this arm that is {@code MapSizeSync} on a nonce. A container that changes size for any
   * other reason — the overlay's filter drawer, a pane revealed at a width it was not hidden at —
   * would otherwise repaint late or not at all. This is also the case the hook's change gate was
   * widened to both dimensions for: the drawer takes height and leaves the width alone.
   */
  useEffect(() => {
    const container = map?.getContainer?.();
    if (!container) return undefined;
    attachFrame(container);
    return () => attachFrame(null);
  }, [map, attachFrame]);

  // With `requiresLand` off the only way this is set is a browser refusing a 2d context, which is
  // terminal for this host — `measureAndPaint` declines from then on. Rendering the canvas anyway
  // would leave an empty element under a toolbar whose legend is still explaining a ramp.
  if (!pane || geoFailed) return null;

  return createPortal(
    <canvas
      // The field is decorative in the accessibility sense: it says nothing the rest of the tab
      // does not, and every place it paints is on the map as a chip with a name.
      aria-hidden="true"
      data-testid="map-heat-canvas"
      ref={canvasRef(CANVAS_KEY)}
      style={{ position: 'absolute', left: 0, top: 0 }}
    />,
    pane,
  );
}

MapHeatLayer.propTypes = {
  /**
   * The active scoreRamp mode — a repaint key only; see the paint callback's note. Aurora is NOT
   * a colourMode value: in A2 it arrives as separate `ramp`/`bloom` props, and the aurora bloom
   * arm must precede the `getMode() === 'temp'` check in `paint`.
   */
  colourMode: PropTypes.oneOf(['temp', 'verdict']),
  points: PropTypes.arrayOf(PropTypes.shape({
    lat: PropTypes.number,
    lng: PropTypes.number,
    rid: PropTypes.string,
    r: PropTypes.arrayOf(PropTypes.number),
  })).isRequired,
  unrated: PropTypes.arrayOf(PropTypes.shape({
    lat: PropTypes.number,
    lng: PropTypes.number,
    rid: PropTypes.string,
  })),
  stood: PropTypes.arrayOf(PropTypes.shape({
    lat: PropTypes.number,
    lng: PropTypes.number,
    rid: PropTypes.string,
  })),
  outside: PropTypes.arrayOf(PropTypes.shape({
    lat: PropTypes.number,
    lng: PropTypes.number,
    rid: PropTypes.string,
  })),
  allowed: PropTypes.arrayOf(PropTypes.shape({
    lat: PropTypes.number,
    lng: PropTypes.number,
    rid: PropTypes.string,
  })),
  conf: PropTypes.number,
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  rings: PropTypes.bool,
  /** A factor on the field's fill opacity (default 1) — Ask's picks dim the field behind them. */
  dim: PropTypes.number,
  /** An injected `(score) => [r, g, b]` ramp (aurora); null keeps the scoreRamp module state. */
  ramp: PropTypes.func,
  /** An injected bloom `{rgb, from, a}` (aurora's `AURORA_BLOOM`); null keeps the temp-only rule. */
  bloom: PropTypes.shape({
    rgb: PropTypes.arrayOf(PropTypes.number).isRequired,
    from: PropTypes.number.isRequired,
    a: PropTypes.number.isRequired,
  }),
};
