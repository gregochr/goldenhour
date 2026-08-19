import {
  useCallback, useEffect, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';
import L from 'leaflet';
import { useMap } from 'react-leaflet';
import { clamp, drawTiles, radiusFor } from '../utils/heatField.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import { useHeatCanvas } from '../hooks/useHeatCanvas.js';

/**
 * The pane the field is painted into, and its stacking order.
 *
 * <p>350 is deliberately between Leaflet's own tile pane (200) and its overlay/marker panes
 * (400/600), so the field sits ON the basemap and UNDER everything that means something specific:
 * the azimuth lines, the aurora viewline, every marker and every popup. A field painted above the
 * markers would be a wash over the answer rather than the ground beneath it.
 */
const HEAT_PANE = 'wf-heat';
const HEAT_PANE_Z = 350;

/** One canvas, so the hook's keyed registry has exactly one entry. */
const CANVAS_KEY = 'field';

/**
 * The handover band (D8): the zoom range across which the field gives way to the markers.
 *
 * <p>Below {@link FADE_FROM} the question is WHERE — a county at a time, which is what a blended
 * field answers and what a wall of medallions cannot. Above {@link FADE_TO} the question has become
 * WHICH, and a smear cannot answer which. The field does not vanish at the top: it settles to
 * {@link HEAT_FLOOR}, a faint wash, because the regional answer is still true at street level and
 * removing it entirely would make the two views feel like different maps.
 */
const FADE_FROM = 10.6;
const FADE_TO = 12.2;
const HEAT_FLOOR = 0.17;

/**
 * The opacity below which the marker panes stop being click targets.
 *
 * <p>An 8%-opaque medallion is invisible and still swallows the tap that was meant for the map.
 */
const MARKER_INTERACTIVE_ALPHA = 0.2;

/** The kernel dials for this host — §4.5's numbers, and the prototype's before that. */
const HEAT_OPACITY = 0.9;
const RADIUS_METRES = 8500;
const RADIUS_MIN_PX = 34;
const RADIUS_MAX_PX = 240;
const GRID = 6;
const BLUR = 4;

/**
 * The panes faded with the zoom.
 *
 * <p>{@code markerPane} carries both the individual markers and the cluster medallions —
 * {@code L.MarkerClusterGroup} builds its clusters as ordinary {@code L.Marker}s and we pass no
 * {@code clusterPane}, so one write covers both. {@code shadowPane} rides with it because a marker
 * whose shadow outlived it would be a smudge with nothing casting it.
 */
const FADED_PANES = ['markerPane', 'shadowPane'];

/**
 * Where the map is in the handover, from its zoom.
 *
 * @param {number} zoom the map's current zoom
 * @returns {{markers: number, heat: number}} the marker opacity (0 → 1) and the heat opacity
 *          multiplier (1 → {@link HEAT_FLOOR}), which move in opposite directions across one band
 */
export function fadeAt(zoom) {
  const t = clamp((zoom - FADE_FROM) / (FADE_TO - FADE_FROM), 0, 1);
  return { markers: t, heat: 1 - (1 - HEAT_FLOOR) * t };
}

/**
 * The class that actually removes the hit target below {@link MARKER_INTERACTIVE_ALPHA}.
 *
 * <p>Its rule is in {@code index.css}; see {@link markersAreInteractive} for why an inline
 * {@code pointer-events: none} on the pane cannot do this job alone.
 */
const MARKERS_INERT_CLASS = 'wf-markers-inert';

/**
 * Whether the markers are still worth clicking at this opacity.
 *
 * <p>Exported so the threshold can be tested ON its boundary rather than either side of it: the
 * only other route in is a zoom, and no zoom maps to exactly 0.2 in floating point.
 *
 * @param {number} opacity 0–1
 * @returns {boolean} true while a marker is solid enough to be a fair target
 */
export function markersAreInteractive(opacity) {
  return opacity >= MARKER_INTERACTIVE_ALPHA;
}

/**
 * Applies the marker half of the handover.
 *
 * <p>⚠️ <b>{@code pointer-events: none} on the pane does not, on its own, stop a click.</b>
 * Leaflet's own stylesheet carries `.leaflet-marker-icon.leaflet-interactive { pointer-events:
 * auto }`, and a child re-enabling the property beats an ancestor disabling it — so the pane rule
 * §4.5 specifies is necessary and not sufficient. The class is what closes it, through a stylesheet
 * rule that reaches the children.
 *
 * <p>⚠️ And the thing that closes it is deliberately NOT {@code visibility: hidden}, which was the
 * first cut. That does remove the hit target — and it also removes every marker from the
 * accessibility tree and the tab order, at the zoom this tab opens at. The markers are the only
 * route to a location's popup on desktop and to its sheet on a phone, so a screen-reader or
 * keyboard reader would have arrived at a Map tab carrying four buttons and an {@code aria-hidden}
 * canvas and nothing else; focus on a marker was dropped to {@code <body>} the moment a zoom faded
 * it. Opacity hides the picture, the class removes the pointer target, and neither touches the
 * tree — so what the field replaces is the visual clutter, never the information.
 *
 * @param {object} map the Leaflet map
 * @param {number} opacity 0–1
 */
function applyMarkerFade(map, opacity) {
  const interactive = markersAreInteractive(opacity);
  for (const name of FADED_PANES) {
    const pane = map?.getPane?.(name);
    if (!pane?.style) continue;
    pane.style.opacity = String(opacity);
    pane.style.pointerEvents = interactive ? '' : 'none';
    pane.classList?.toggle(MARKERS_INERT_CLASS, !interactive);
  }
}

/**
 * Hands the marker panes back exactly as they were found.
 *
 * <p>The empty string, not a literal: it clears the inline declaration so the stylesheet's own
 * value wins again. Writing `1`/`auto` would leave two inline overrides behind on a map the
 * medallion view then owns — and the medallion view is specified as byte-identical to today, which
 * it cannot be while carrying this layer's fingerprints. The class goes with them.
 *
 * @param {object} map the Leaflet map
 */
function restoreMarkerPanes(map) {
  for (const name of FADED_PANES) {
    const pane = map?.getPane?.(name);
    if (!pane?.style) continue;
    pane.style.opacity = '';
    pane.style.pointerEvents = '';
    pane.classList?.remove(MARKERS_INERT_CLASS);
  }
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
 * @param {?number}  props.conf   the window's confidence as a 0–1 scalar; the kernel desaturates
 *        and thins with it, so a day-4 guess cannot look as authoritative as tonight
 * @param {boolean}  [props.markersLocked] a location is open — see {@code fadesMarkers}
 */
export default function MapHeatLayer({ points, conf = null, markersLocked = false }) {
  const map = useMap();

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
      // The field is a picture, not a control: every click belongs to the map or to a marker.
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

  /**
   * Whether the markers are the layer's to fade.
   *
   * <p>⚠️ Only when there is a field to hand over TO. A window with no scored locations — an
   * Awaiting T+3, or an ordinary morning the nightly policy did not evaluate — paints an empty
   * canvas, and fading the markers out under it would leave the reader a blank map at the zoom the
   * tab opens at. Nothing on screen would say why. With no points the markers keep today's full
   * opacity and the map is simply the map.
   */
  /**
   * And ⚠️ never while the reader has a location OPEN.
   *
   * <p>A popup lives in `popupPane` (z700) and the azimuth lines in `overlayPane` (z400); neither
   * fades, and neither should — they are content the reader asked for. But fading the marker they
   * are anchored to leaves a popup with its leader tip on bare ground and two coloured rays
   * radiating from nothing. It is reachable without trying: the Map tab's own handoff fits a region
   * with `maxZoom: 12`, which for a wide region lands well below the band, and then opens that
   * location's popup.
   *
   * <p>So a selection pins the markers at full strength. The handover is a rule about the general
   * case — at this width the question is WHERE — and a reader who has already asked WHICH has
   * stepped outside it.
   */
  const fadesMarkers = points.length > 0 && !markersLocked;

  const paint = useCallback(({ canvases }) => {
    const canvas = canvases.get(CANVAS_KEY);
    if (!canvas || !map?.getZoom) return;
    L.DomUtil?.setPosition?.(canvas, map.containerPointToLayerPoint([0, 0]));
    const fade = fadeAt(map.getZoom());
    if (fadesMarkers) applyMarkerFade(map, fade.markers);
    else restoreMarkerPanes(map);
    drawTiles(canvas, map, points, POINT_SCORE_INDEX, {
      radius: radiusFor(map, RADIUS_METRES, RADIUS_MIN_PX, RADIUS_MAX_PX),
      grid: GRID,
      blur: BLUR,
      // Null rather than 1 when the window carries no tier: `field` reads `conf == null` as full
      // confidence, which is the honest default for a window the backend declined to qualify —
      // the haze is a qualifier on a claim, not a claim of its own.
      conf,
      opacity: HEAT_OPACITY * fade.heat,
    });
  }, [map, points, conf, fadesMarkers]);

  const {
    attachFrame, canvasRef, geoFailed, repaint, repaintNow,
  } = useHeatCanvas({
    enabled: true,
    measureKey: CANVAS_KEY,
    paint,
    measure,
    // `drawTiles` draws no coastline and clips to nothing — the basemap carries the geography. With
    // this true the layer would sit blank until a topology asset it never draws had been fetched.
    // (It does not save the `geo` chunk — see the hook's own note; `drawTiles` shares a module with
    // `drawGeo`, and on this arm the Plan tab has fetched it long before the Map tab is opened.)
    requiresLand: false,
  });

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
    map.on('move zoom viewreset resize', repaint);
    map.on('moveend zoomend', settle);
    return () => {
      map.off('move zoom viewreset resize', repaint);
      map.off('moveend zoomend', settle);
    };
  }, [map, repaint, settle]);

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

  /**
   * Hand the marker panes back on the way out — a toggle to medallions, a tab change, a logout.
   *
   * <p>Separate from the paint effect and keyed on the map alone, so it runs exactly once, at
   * unmount. Folding it into the paint's cleanup would restore the panes between every repaint.
   */
  useEffect(() => () => restoreMarkerPanes(map), [map]);

  // With `requiresLand` off the only way this is set is a browser refusing a 2d context, which is
  // terminal for this host — `measureAndPaint` declines from then on. Rendering the canvas anyway
  // would leave an empty element under a toolbar whose legend is still explaining a ramp.
  if (!pane || geoFailed) return null;

  return createPortal(
    <canvas
      // The field is decorative in the accessibility sense: it says nothing the rest of the tab
      // does not, and every place it paints is on the map as a marker with a name.
      aria-hidden="true"
      data-testid="map-heat-canvas"
      ref={canvasRef(CANVAS_KEY)}
      style={{ position: 'absolute', left: 0, top: 0 }}
    />,
    pane,
  );
}

MapHeatLayer.propTypes = {
  points: PropTypes.arrayOf(PropTypes.shape({
    lat: PropTypes.number,
    lng: PropTypes.number,
    rid: PropTypes.string,
    r: PropTypes.arrayOf(PropTypes.number),
  })).isRequired,
  conf: PropTypes.number,
  markersLocked: PropTypes.bool,
};
