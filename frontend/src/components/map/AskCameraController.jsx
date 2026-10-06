import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import L from 'leaflet';
import { useMap } from 'react-leaflet';
import {
  ASK_FIT_MAX_ZOOM, ASK_SELECT_ZOOM, fitPadding, NO_INSET, prefersReducedMotion, selectOffset,
} from '../../utils/askCamera.js';

/** How long after the LAST frame resize the picks are fitted again, in ms (the dock animates 280ms). */
export const SETTLE_REFIT_MS = 250;

/** The flight's length, in seconds, when motion is allowed. */
const FLIGHT_SECONDS = 0.7;

/**
 * How long a move of the camera's OWN is still "ours" after it starts, in ms — a flight plus a little
 * slack. A Leaflet {@code zoomstart} inside this window is the camera's own flight (or the settle
 * re-fit), not the reader's; outside it, it is the reader, and the controller stops re-applying.
 */
const OWN_MOVE_MS = FLIGHT_SECONDS * 1000 + 200;

/** The fit's options: the clamped padding as Leaflet's two corners, and the zoom cap. */
function fitOptions(map, inset) {
  const pad = fitPadding(map.getSize(), inset);
  return {
    paddingTopLeft: [pad.left, pad.top],
    paddingBottomRight: [pad.right, pad.bottom],
    maxZoom: ASK_FIT_MAX_ZOOM,
  };
}

/** The centre that puts {@code sel} clear of whatever {@code inset} covers, at the select zoom. */
function selectCentre(map, sel, inset) {
  const { dx, dy } = selectOffset(map.getSize(), inset);
  return map.unproject(map.project([sel.lat, sel.lng], ASK_SELECT_ZOOM).add([dx, dy]), ASK_SELECT_ZOOM);
}

/** An inset as one comparable string — the effect below must fire on a CHANGE, not on a new object. */
const insetKeyOf = (inset) => `${inset.top ?? 0}|${inset.right ?? 0}|${inset.bottom ?? 0}|${inset.left ?? 0}`;

/**
 * Moves the map's camera for Ask PhotoCast's picks (`docs/engineering/ask-photocast-plan.md` §2.7,
 * F3) — the sibling of {@code FitBoundsController}, in a component of its own for the same reason: the
 * camera is a side effect of an event ("an answer landed", "a pick was chosen") and has to key on that
 * event, never on how often a parent re-renders. It is not a copy of that controller's key pattern: a
 * re-fit here has to be SKIPPABLE (a refusal that puts an earlier answer back must not move the
 * camera), so what it keys on is a ref of what it has already done, per answer and per choice.
 *
 * <h2>Three moves</h2>
 * <ul>
 *   <li><b>Fit</b> — once per {@code answerId}: fly to the bounds of the picks, zoomed in no further
 *       than {@link ASK_FIT_MAX_ZOOM}.</li>
 *   <li><b>Select</b> — a chosen pick flies to {@link ASK_SELECT_ZOOM}, offset clear of a covering
 *       surface by the inset. It runs for a selection made while the map is on screen; one that
 *       arrives while the map is hidden is covered by the fit that follows when it is shown (which
 *       leaves every pick in view and the chosen one ringed).</li>
 *   <li><b>Re-apply</b> — the last move is applied again, without animation, when the frame has
 *       resized and settled (the dock opening or closing, a window resize: Leaflet keeps the centre
 *       but not the picks near an edge) and when the {@code inset} changes (a surface that covers part
 *       of the frame grows or shrinks without the frame itself changing size, which is what a phone's
 *       peek sheet does — F4's seam).</li>
 * </ul>
 *
 * <h2>When it does nothing</h2>
 * <p>While {@code active} is false — the map is hidden behind another tab, or a modal (the tablet's
 * Ask sheet) stands over it — nothing moves, and the move that is owed happens when it turns true.
 * That is what makes the fit happen when the sheet CLOSES rather than behind it. A new answer with no
 * picks moves nothing; clearing an answer never moves the camera back; {@code answerId} unchanged (a
 * refusal that put an earlier answer back) fits nothing.
 *
 * <p>⚠️ <b>A reader's own move ends the re-applying.</b> The settle re-fit would otherwise undo a pan,
 * a zoom or a Regions jump made after the fit the moment the dock closed. A Leaflet {@code dragstart}
 * (only ever a hand) or a {@code zoomstart} outside the controller's own flight forgets the last move;
 * the next answer or the next choice starts afresh.
 *
 * <p>Padding is clamped to the frame ({@code utils/askCamera.js} says why: an unclamped pad puts the
 * picks outside it). Under {@code prefers-reduced-motion} the camera jumps ({@code animate: false}).
 *
 * @param {object} props
 * @param {boolean} props.active the map is on screen and nothing modal stands over it
 * @param {?number} props.answerId the answer on screen ({@code AskContext}'s monotonic id), or null
 * @param {Array<{lat: number, lng: number}>} props.points the picks' coordinates
 * @param {?{lat: number, lng: number, nonce: number}} props.selection the chosen pick;
 *        {@code nonce} is new on every choice, so choosing the same pick again moves the camera again
 * @param {{top: number, right: number, bottom: number, left: number}} [props.inset] what a surface
 *        covers of the frame; none by default. The phone's peek sheet is the one caller that passes one
 *        (F4): 470 with the Ask section open, 112 for the minimised line — a change re-applies the move
 * @param {function(number): void} [props.onOwnMove] called, before each move of the camera's own, with
 *        the {@code Date.now()} value at which it will have ended — so whoever must tell the camera's
 *        zooms from a hand's (the peek sheet's map-touch listener) can
 */
export default function AskCameraController({
  active, answerId, points, selection, inset = NO_INSET, onOwnMove = null,
}) {
  const map = useMap();
  /** The answer already fitted — never fitted twice. */
  const fittedFor = useRef(null);
  /** The selection already flown to, by nonce. */
  const selectedNonce = useRef(null);
  /** The last move, so a settled resize or a changed inset can apply it again. */
  const lastMove = useRef(null);
  /** The inset the last move was made under. */
  const appliedInset = useRef(insetKeyOf(inset));
  /** The time before which a zoom is the camera's own (see {@link OWN_MOVE_MS}). */
  const ownMoveUntil = useRef(0);
  /**
   * Starts a move of the camera's own: stamps when it ends and tells whoever must tell the camera's
   * zooms from a hand's too (`onOwnMove`: the phone peek sheet's map-touch listener) — before the move
   * itself, so the {@code zoomstart} it fires is already known to be ours.
   */
  const startOwnMove = () => {
    const until = Date.now() + OWN_MOVE_MS;
    ownMoveUntil.current = until;
    onOwnMove?.(until);
  };
  /** The pointers (fingers, a mouse button) currently down on the map — see the re-apply below. */
  const pointersDown = useRef(new Set());
  /** A re-apply owed once the last pointer is up (the inset changed while one was down). */
  const reapplyOwed = useRef(false);
  /** The effect's own `reapply`, for the pointer-up listener (a different effect) to call. */
  const reapplyNow = useRef(null);
  /** True while this controller itself calls {@code invalidateSize} (its own resize is no news). */
  const resizing = useRef(false);
  const latest = useRef(null);
  useEffect(() => { latest.current = { points, selection, inset }; });

  const hasPoints = points.length > 0;
  const insetKey = insetKeyOf(inset);

  /** Asks Leaflet to re-measure the frame, without taking its own resize for news. */
  const remeasure = () => {
    resizing.current = true;
    map.invalidateSize?.({ animate: false });
    resizing.current = false;
  };

  // Fit once per answer; fly to a chosen pick. Held while inactive, and run when it turns active.
  useEffect(() => {
    // A cleared answer leaves nothing to re-apply on a later resize (and the camera stays put).
    if (answerId == null || !hasPoints) {
      lastMove.current = null;
      return;
    }
    if (!active || typeof map?.getSize !== 'function') return;
    const { points: pts, selection: sel, inset: pad } = latest.current;
    // The size Leaflet holds may be stale (the pane was hidden, the dock just animated).
    remeasure();
    const reduced = prefersReducedMotion();
    appliedInset.current = insetKeyOf(pad);

    if (fittedFor.current !== answerId) {
      startOwnMove();
      fittedFor.current = answerId;
      selectedNonce.current = sel?.nonce ?? null;
      const bounds = L.latLngBounds(pts.map((p) => [p.lat, p.lng]));
      const options = fitOptions(map, pad);
      lastMove.current = { kind: 'fit', bounds };
      if (reduced) map.fitBounds(bounds, { ...options, animate: false });
      else map.flyToBounds(bounds, { ...options, duration: FLIGHT_SECONDS });
      return;
    }
    if (sel && sel.nonce !== selectedNonce.current) {
      startOwnMove();
      selectedNonce.current = sel.nonce;
      const at = selectCentre(map, sel, pad);
      lastMove.current = { kind: 'pick', sel };
      if (reduced) map.setView(at, ASK_SELECT_ZOOM, { animate: false });
      else map.flyTo(at, ASK_SELECT_ZOOM, { duration: FLIGHT_SECONDS });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `remeasure` closes over refs and `map`
  }, [map, active, answerId, hasPoints, selection?.nonce]);

  // The last move, applied again without animation: after a settled resize, and when the inset changed.
  useEffect(() => {
    if (!active || typeof map?.on !== 'function') return undefined;
    let timer = 0;
    const reapply = () => {
      const move = lastMove.current;
      if (!move) return;
      remeasure();
      const pad = latest.current.inset;
      appliedInset.current = insetKeyOf(pad);
      startOwnMove();
      if (move.kind === 'fit') {
        map.fitBounds(move.bounds, { ...fitOptions(map, pad), animate: false });
      } else {
        map.setView(selectCentre(map, move.sel, pad), ASK_SELECT_ZOOM, { animate: false });
      }
    };
    const onResize = () => {
      if (resizing.current) return;
      clearTimeout(timer);
      timer = setTimeout(() => {
        timer = 0;
        reapply();
      }, SETTLE_REFIT_MS);
    };
    // A hand on the map. ⚠️ A drag is ONLY ever the reader's (Leaflet's Draggable is the one thing that
    // fires `dragstart`), so it ends the re-applying whenever it comes — a camera that re-applied
    // because the sheet came down in answer to that very drag (F4: a map touch minimises the answer,
    // which changes the inset) would snap the map back under the reader's finger. A zoom is theirs
    // unless it is our own flight, which Leaflet reports as a `zoomstart` too.
    const onReaderDrag = () => { lastMove.current = null; };
    const onReaderZoom = () => {
      if (Date.now() >= ownMoveUntil.current) lastMove.current = null;
    };
    reapplyNow.current = reapply;
    // A changed inset (not a first one) re-applies: the frame did not resize, so nothing else will.
    // ⚠️ But NOT under a pointer that is down. F4's sheet minimises on `touchstart`, which changes the
    // inset before the first `touchmove` — and a camera move between a Leaflet drag's `_onDown` and its
    // first move leaves the Draggable holding the map pane's OLD position, so the drag then starts from
    // somewhere the map no longer is. Held until the last pointer is up instead: a drag has cleared the
    // move by then (nothing to re-apply — the reader has taken the camera), and a tap re-applies on release.
    if (appliedInset.current !== insetKey) {
      if (pointersDown.current.size > 0) reapplyOwed.current = true;
      else reapply();
    }
    map.on('resize', onResize);
    map.on('dragstart', onReaderDrag);
    map.on('zoomstart', onReaderZoom);
    return () => {
      map.off('resize', onResize);
      map.off('dragstart', onReaderDrag);
      map.off('zoomstart', onReaderZoom);
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `remeasure` closes over refs and `map`
  }, [map, active, insetKey]);

  // Which pointers are down on the map, and the re-apply that waited for them (above). Pointer events
  // cover a mouse, a touch and a pen alike, and a second finger is a second pointer. Listened for in the
  // capture phase on the container (Leaflet's own handlers stop propagation), and for the release on the
  // document, since a finger can lift outside the frame.
  useEffect(() => {
    const el = map?.getContainer?.();
    if (!el || typeof el.addEventListener !== 'function') return undefined;
    const pressed = pointersDown.current;
    const down = (event) => { pressed.add(event.pointerId); };
    const up = (event) => {
      pressed.delete(event.pointerId);
      if (pressed.size > 0 || !reapplyOwed.current) return;
      reapplyOwed.current = false;
      reapplyNow.current?.();
    };
    el.addEventListener('pointerdown', down, true);
    document.addEventListener('pointerup', up, true);
    document.addEventListener('pointercancel', up, true);
    return () => {
      el.removeEventListener('pointerdown', down, true);
      document.removeEventListener('pointerup', up, true);
      document.removeEventListener('pointercancel', up, true);
      pressed.clear();
      reapplyOwed.current = false;
    };
  }, [map]);

  return null;
}

AskCameraController.propTypes = {
  active: PropTypes.bool.isRequired,
  answerId: PropTypes.number,
  points: PropTypes.arrayOf(PropTypes.shape({
    lat: PropTypes.number.isRequired,
    lng: PropTypes.number.isRequired,
  })).isRequired,
  selection: PropTypes.shape({
    lat: PropTypes.number.isRequired,
    lng: PropTypes.number.isRequired,
    nonce: PropTypes.number.isRequired,
  }),
  inset: PropTypes.shape({
    top: PropTypes.number, right: PropTypes.number, bottom: PropTypes.number, left: PropTypes.number,
  }),
  onOwnMove: PropTypes.func,
};
