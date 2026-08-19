import { useCallback, useEffect, useRef, useState } from 'react';
import { land, load } from '../utils/heatField.js';

/**
 * The default measurement floor, and the retry budget.
 *
 * <p>{@code drawGeo} declines below 20px because a zero measure throws on {@code cv.width}, and the
 * arm's panes mount {@code display: none} — so the first measure can legitimately be zero. The
 * retry is the prototype's {@code drawThumbs(tries)}/{@code drawBig(tries)}, and its budget covers
 * <b>measurement only</b>: "the geometry has not loaded yet" is the other reason {@code drawGeo}
 * returns null, and polling for it would let a budget sized for a layout tick expire before the
 * topology chunk arrives. That one is handled by {@link load}'s promise instead, which is what
 * {@code land()} is exported for.
 */
const MIN_MEASURE_PX = 21;
const MAX_MEASURE_TRIES = 30;

/**
 * {@code drawGeo}'s own second dimension test, applied here because this is where the height is
 * derived.
 *
 * <p>The host declines when EITHER dimension is 20px or smaller, and a caller's width floor only
 * covers that if it is at least {@code 20 / aspectFloor} — which is a constant each host would
 * otherwise have to re-derive from its own clamps, and get wrong once. Checking the height the hook
 * just computed is the general form of the same rule.
 *
 * <p>Provably inert for {@code WindowFirstHeatStrip}, whose own floor is already sized for it: at
 * its minimum width of 26 and its aspect floor of 0.85 the height is 22.1, so this gate cannot fire
 * where the width gate passed. That is what makes adding it here a generalisation rather than a
 * behaviour change — see the strip's {@code MIN_THUMB_PX} comment for the derivation.
 */
const MIN_CANVAS_PX = 20;

/**
 * The heat field's canvas HOST — the mounting, measuring and repainting a static kernel surface
 * needs, with none of the dials.
 *
 * <h2>Why this is a hook and not a second copy</h2>
 *
 * <p>Extracted verbatim from {@code WindowFirstHeatStrip} at P3, because the open row's field map
 * (§4.4) needs the same host at different dials — a bigger grid, a different radius, a focus
 * option, one canvas instead of six. Every rule below was paid for once: three of them were
 * adversarial-review findings against P2's first cut and one was found only in a browser. A second
 * copy would take the rules and not the reasons, and the next performance or correctness fix would
 * land on one host and not the other, which is precisely the drift the one-kernel architecture
 * exists to prevent. {@code solarDayGeometry.js} set the precedent: share the pure mechanics, let
 * each host keep its own JSX.
 *
 * <p>The proof the extraction was behaviour-preserving is that {@code WindowFirstHeatStrip.test.jsx}
 * passed <b>unedited</b>, all 41 of its tests, against this hook — the {@code solarDayGeometry}
 * precedent, same proof.
 *
 * <p>⚠️ That file was edited <em>afterwards</em>, in the same commit, and the edit is not a
 * weakening: it adds a {@code cleanup()} between the two renders of one boundary test, plus its
 * rationale. Nothing about behaviour changed — the assertions either side of it are untouched, and
 * the isolation makes the second one strictly stricter. The reason it became necessary is worth
 * knowing: that test mounts a strip at 25px (which declines and leaves a rAF retry pending), then a
 * second at 26px, and counts calls on one module-level mock. Under the parallel load this phase's
 * six new files added, a frame now has time to fire between the two renders and the first strip's
 * retry succeeds — correct product behaviour (a canvas too small that becomes big enough SHOULD
 * paint) landing on a test that could not tell the two strips apart.
 *
 * <h2>What it owns, and what it deliberately does not</h2>
 *
 * <p>It owns the geometry lifecycle ({@link load}/{@link land}), the measurement and its retry, the
 * two resize triggers, and the null-context guard. It owns no dial: {@code grid}, {@code radius},
 * {@code blur}, {@code line}, {@code conf}, {@code fit} and {@code focus} are the caller's, and the
 * caller's {@code paint} callback is what actually calls {@code drawGeo}. So this hook imports no
 * host function, and a host that wanted {@code drawTiles} instead could use it unchanged.
 *
 * @param {object}   options
 * @param {boolean}  options.enabled  false withdraws the paint entirely — a collapsed row, an empty
 *        catalogue. The geometry still loads, so the first enabled frame does not wait for it.
 * @param {?string}  options.measureKey the canvas whose PARENT is measured. One measurement serves
 *        every canvas the caller registered — see {@link paintOptions} — so a caller with six
 *        siblings names its first, and a caller with one names its only.
 * @param {number}   options.aspect   height / width for the canvases, already clamped by the caller
 *        (the clamps are host constants, not kernel behaviour).
 * @param {number}   [options.minPx]  the measurement floor. Defaults to {@link MIN_MEASURE_PX}.
 * @param {Function} options.paint    called once per successful measure with
 *        {@code {width, height, canvases}}. MUST be memoised by the caller — it is a dependency of
 *        the paint effect, so an inline arrow repaints on every render.
 * @returns {{attachFrame: Function, canvasRef: Function, geoFailed: boolean}}
 */
export function useHeatCanvas({
  enabled, measureKey, aspect, minPx = MIN_MEASURE_PX, paint,
}) {
  /**
   * The observed frame, watched through a REF CALLBACK rather than a mount effect.
   *
   * <p>A `useEffect(…, [])` reading a ref cannot work for these hosts, and the failure is total
   * rather than occasional: on a cold load the caller has no cards and no spots, so it returns null,
   * the frame does not exist, and an effect with an empty dependency list never runs again. The
   * observer would then be absent for the whole session — no repaint on a rotate, and none on the
   * reveal of a pane that first mounted `display: none`, which is the case the observer exists for.
   * A ref callback fires whenever the node attaches, whenever that is.
   */
  const observerRef = useRef(null);
  const frameNodeRef = useRef(null);
  const lastWidthRef = useRef(0);
  const canvasRefs = useRef(new Map());
  /** Bumped when the vendored geometry resolves, which is what re-runs the paint effect. */
  const [landNonce, setLandNonce] = useState(0);
  /** Bumped by the ResizeObserver — a reveal or a window resize, never a paint of its own. */
  const [resizeNonce, setResizeNonce] = useState(0);
  /**
   * True when the topology chunk could not be fetched, or when this browser will not give us a 2d
   * context. Callers render no canvas at all in that state rather than an empty frame: an empty
   * frame implies a map that has nothing on it, which is a different (and false) claim from "the
   * picture is unavailable".
   */
  const [geoFailed, setGeoFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    // Whether the geometry was ALREADY resolved when this mounted, which decides whether the nonce
    // is worth moving. `load()` resolves immediately in that case, and bumping would repaint
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

  const attachFrame = useCallback((node) => {
    observerRef.current?.disconnect();
    observerRef.current = null;
    frameNodeRef.current = node;
    if (!node) return;
    // Seeded at ATTACH, not left to the observer's first callback. A ResizeObserver delivers one
    // observation immediately on `observe()` and that is what used to write this — so where there is
    // no ResizeObserver at all the guard started at 0, and the first `window.resize` after mount
    // repainted every canvas at a width nothing had changed. Same value either way in a browser that
    // has one; the difference is that the invariant now holds by construction rather than by
    // depending on a callback that may never come.
    lastWidthRef.current = node.clientWidth;
    if (typeof ResizeObserver === 'undefined') return;
    // Gated on the WIDTH actually changing, not on any observation. A ResizeObserver delivers one
    // callback immediately on `observe()`, and the first paint itself resizes the frame (a canvas
    // goes from its 300×150 intrinsic ratio to `width × aspect`), so an ungated observer repaints
    // twice more on every mount — which would quietly undo the `alreadyLoaded` guard above, whose
    // whole purpose is avoiding a doubled first paint.
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
   * {@code window.resize} and P2 started with a {@code ResizeObserver} alone, on the reasoning that
   * an observer catches strictly more (a pane revealed from {@code display: none} fires no window
   * resize). That is true and the observer stays for exactly that case — but in the browser it was
   * checked in, the observer did not fire on a viewport change at all: at 360px the canvases were
   * still drawn at their 390px width and clipped by {@code .wf-hc}'s {@code overflow: hidden}. Two
   * triggers for one repaint is cheap; the repaint is idempotent (`lastWidthRef` makes a no-change
   * resize a no-op) and the paint itself is a coarse-grid field.
   */
  useEffect(() => {
    const onResize = () => {
      const width = frameNodeRef.current?.clientWidth ?? 0;
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
    if (geoFailed || !enabled || !land() || measureKey == null) return undefined;
    let raf = 0;
    let cancelled = false;
    const attempt = (tries) => {
      if (cancelled) return;
      // ONE measurement for every registered canvas, taken from `measureKey`'s well. Siblings have
      // to be comparable — that is the whole claim the strip makes — and measuring each
      // independently would let a sub-pixel column difference paint two of them at different
      // scales. The well is an unpadded wrapper, so its `clientWidth` is the drawable width
      // directly; measuring the button would need its padding subtracted, which is the prototype's
      // `- 12` and a constant that silently rots the moment the stylesheet changes.
      const first = canvasRefs.current.get(measureKey);
      const well = first?.parentElement;
      const cell = well ? well.clientWidth : 0;
      const height = Math.round(cell * aspect);
      if (!(cell > minPx) || !(height > MIN_CANVAS_PX)) {
        if (tries < MAX_MEASURE_TRIES) raf = requestAnimationFrame(() => attempt(tries + 1));
        return;
      }
      // P0 left this guard to whoever mounts a canvas, and this hook is that caller: `field`/`fit`
      // DEREFERENCE the 2d context rather than declining, and a real browser can return null for it
      // under memory pressure. Unguarded, the TypeError is thrown inside an effect and takes the
      // whole Plan pane to the error boundary — a picture costing the reader every window row.
      // Checked once on the measured canvas rather than per canvas: siblings are created in one
      // commit, so the condition is a property of the document rather than of one element.
      if (!first.getContext('2d')) {
        setGeoFailed(true);
        return;
      }
      paint({ width: cell, height, canvases: canvasRefs.current });
    };
    attempt(0);
    return () => {
      cancelled = true;
      if (raf) cancelAnimationFrame(raf);
    };
  }, [enabled, measureKey, aspect, minPx, paint, landNonce, resizeNonce, geoFailed]);

  /**
   * A ref callback for one canvas, keyed.
   *
   * <p>Keyed rather than positional for the reason {@code heatSpots.js} gives about point sets: the
   * integer a positional registry would hand back looks exactly like the kernel's {@code win}
   * argument, and passing it paints a full-strength field of the ramp's bottom colour.
   */
  const canvasRef = useCallback((key) => (node) => {
    if (node) canvasRefs.current.set(key, node);
    else canvasRefs.current.delete(key);
  }, []);

  return { attachFrame, canvasRef, geoFailed };
}
