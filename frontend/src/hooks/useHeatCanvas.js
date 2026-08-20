import {
  useCallback, useEffect, useLayoutEffect, useRef, useState,
} from 'react';
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
 * <p>Provably inert for {@code WindowFirstHeatStrip}, whose own floor is sized for it: its gate is
 * strict ({@code !(width > minPx)}) at a floor of 26, so the narrowest width that reaches a paint is
 * 27, and at its aspect floor of 0.78 the height is 21 — this gate cannot fire where the width gate
 * passed. That is what makes adding it here a generalisation rather than a behaviour change; see
 * that component's {@code MIN_THUMB_PX} comment for the derivation, and re-check the pair together
 * if either the floor or the aspect clamp moves.
 */
const MIN_CANVAS_PX = 20;

/**
 * What one measure-and-paint pass did, because only ONE of the three outcomes may be retried.
 *
 * <p>A box that is merely too small yet is the case the rAF budget exists for. A host that has
 * declined for any other reason — disabled, geometry absent, no 2d context — must not consume that
 * budget: the retry would spin thirty frames and, in the no-context case, would keep calling
 * {@code getContext} after the hook had already settled into its failure state.
 */
const PAINTED = 'painted';
const TOO_SMALL = 'too-small';
const DECLINED = 'declined';

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
 * <h2>P4 widened it to a THIRD host, rather than writing one</h2>
 *
 * <p>P3 recorded, measured, that this hook did not yet generalise to the Leaflet host, in three
 * specific ways. Each is now an option rather than an assumption, and none of them changes what the
 * two static hosts do:
 *
 * <ul>
 *   <li>{@code requiresLand} — the vendored coastline is {@code drawGeo}'s business. {@code drawTiles}
 *       never reads it (the basemap carries the geography), so a Leaflet host that waited for
 *       {@code land()} would sit blank until a 9 KB topology chunk it will never draw arrives. With
 *       the flag off, {@link load} is not called at all, so that asset is never fetched.
 *       <b>Measured caveat:</b> this saves the topology and the wait, NOT the 24 KB {@code geo}
 *       chunk — {@code drawTiles} shares a module with {@code drawGeo}, which needs {@code d3-geo},
 *       so the Leaflet host still loads it. Splitting the kernel from the geo host would recover it
 *       and was deliberately left alone: on the v2 arm the Plan tab's strip has already fetched
 *       {@code geo} before the Map tab is ever opened.</li>
 *   <li>{@code measure} — the two static hosts measure a canvas's parent well and derive the height
 *       from an aspect. A Leaflet canvas has no well: its parent is a Leaflet pane, which is
 *       absolutely positioned with no intrinsic box, so {@code clientWidth} there is 0. The host
 *       supplies its own measurement instead ({@code map.getSize()}), which is also the quantity
 *       {@code drawTiles} itself uses — one reading for both ends, which is the whole lesson of the
 *       {@code clientWidth}-vs-{@code getBoundingClientRect} defect below.</li>
 *   <li>{@code repaint}/{@code repaintNow} — a static host repaints when its box changes and at no
 *       other time. A map repaints on every frame of a pan. The imperative pair is the prototype's
 *       {@code render}/{@code renderNow} (`map-tab.js:96-100`): {@code repaint} coalesces to one
 *       animation frame, {@code repaintNow} paints in the calling tick for the settle. <b>A
 *       THROTTLE, never a debounce</b> — a debounce pushes the frame back on every event, so a
 *       continuous drag paints nothing at all until the finger stops, which is the stale-overlay
 *       bug the prototype's comment records.</li>
 * </ul>
 *
 * <p>The change gate moved with them. It used to compare the frame's width alone, which is complete
 * only while the height is derived from it; a map container can lose height without losing width
 * (a drawer opening, a pane revealed at a different size) and that repaint would have been dropped.
 * It now compares the full measurement — and for the two static hosts that is the same test it
 * always was, because their height is {@code round(width × aspect)} and cannot move on its own.
 *
 * <p>The proof this widening was behaviour-preserving is the same proof as last time:
 * {@code WindowFirstHeatStrip.test.jsx} and {@code WindowRowFieldMap.test.jsx} pass <b>unedited</b>.
 *
 * <h2>What it owns, and what it deliberately does not</h2>
 *
 * <p>It owns the geometry lifecycle ({@link load}/{@link land}), the measurement and its retry, the
 * two resize triggers, and the null-context guard. It owns no dial: {@code grid}, {@code radius},
 * {@code blur}, {@code line}, {@code conf}, {@code fit} and {@code focus} are the caller's, and the
 * caller's {@code paint} callback is what actually calls {@code drawGeo} (or {@code drawTiles}). So
 * this hook imports no host function.
 *
 * @param {object}   options
 * @param {boolean}  options.enabled  false withdraws the paint entirely — a collapsed row, an empty
 *        catalogue. The geometry still loads, so the first enabled frame does not wait for it.
 * @param {?string}  options.measureKey the canvas whose PARENT is measured. One measurement serves
 *        every canvas the caller registered — see {@code paint} — so a caller with six siblings
 *        names its first, and a caller with one names its only. Still required with a custom
 *        {@code measure}: it is also the canvas the null-context guard is checked on.
 * @param {number}   [options.aspect] height / width for the canvases, already clamped by the caller
 *        (the clamps are host constants, not kernel behaviour). Required unless {@code measure} is
 *        given, which supplies both dimensions itself — supplying neither declines with a console
 *        warning rather than failing as thirty silent frames.
 * @param {number}   [options.minPx]  the measurement floor. Defaults to {@link MIN_MEASURE_PX}.
 * @param {Function} options.paint    called once per successful measure with
 *        {@code {width, height, canvases}}. MUST be memoised by the caller — it is a dependency of
 *        the paint effect, so an inline arrow repaints on every render.
 * @param {?Function} [options.measure] a host measurement returning {@code {width, height}} (or
 *        null). Defaults to the well-and-aspect derivation. MUST be memoised, for the same reason
 *        {@code paint} must.
 * @param {boolean}  [options.requiresLand] whether to wait for (and fetch) the vendored coastline.
 *        Defaults true, which is what {@code drawGeo} needs.
 * @returns {{attachFrame: Function, canvasRef: Function, geoFailed: boolean, repaint: Function,
 *          repaintNow: Function}}
 */
export function useHeatCanvas({
  enabled, measureKey, aspect, minPx = MIN_MEASURE_PX, paint, measure = null, requiresLand = true,
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
   *
   * <p>It is also callable imperatively, which is how the Leaflet host attaches: its frame is
   * `map.getContainer()`, a node React never rendered.
   */
  const observerRef = useRef(null);
  const frameNodeRef = useRef(null);
  /**
   * The OBSERVED FRAME's last box, as {@code {width, height}} — the change gate, and nothing else.
   *
   * <p>⚠️ It records the FRAME's box, never the measurement {@code measureAndPaint} took. The two
   * are deliberately different quantities — the frame is the whole matrix, the measurement one
   * card's well — and writing the paint's own reading here would compare a derived canvas
   * height against a frame's real one on the next observation, which fires a repaint on every
   * resize that changed nothing. That is a failure this file's own suite catches.
   */
  const lastSizeRef = useRef({ width: 0, height: 0 });
  /**
   * Whether the frame's HEIGHT is part of the change gate.
   *
   * <p>⚠️ Only for a host that measures itself, and the asymmetry is load-bearing rather than
   * cautious. A static host's canvas height is {@code round(width × aspect)}, and its frame's
   * height is a CONSEQUENCE of that — {@code .wf-mapbox} IS the canvas's own parent, and
   * {@code .wf-hstrip} is an auto-row grid whose rows are as tall as their tallest cell. So the first
   * paint, which writes the canvas from its 300×150 intrinsic ratio to `width × aspect`, changes
   * the frame's height while the width stands still: watching it would fire an observation on every
   * mount and repaint every canvas a second time, which is exactly what the `alreadyLoaded`
   * guard above exists to prevent. It converges, so it is a doubled paint rather than a loop — and
   * jsdom, which stubs `clientWidth` alone and reports every `clientHeight` as 0, cannot see it at
   * all. Caught by review, not by a suite.
   *
   * <p>A Leaflet host has the opposite property: its canvas is sized from the map, not from the
   * frame, so the frame's height moves only when something real moved it — a drawer opening under
   * the map, a pane revealed at a different height. That is the case the gate was widened for.
   */
  const watchesHeight = measure != null;
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
    // Not merely a gate on the WAIT: `load()` is what fetches the topology chunk, so a host that
    // does not draw a coastline must not call it at all. Calling it anyway would put a 9 KB request
    // on the Map tab for geometry `drawTiles` has no use for.
    if (!requiresLand) return undefined;
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
  }, [requiresLand]);

  /**
   * One measure-and-paint pass, shared by the effect, its retry and the imperative pair.
   *
   * <p>ONE function so a host cannot repaint through a different path from the one that first
   * painted it. The measurement it uses is the caller's when given and the well-and-aspect
   * derivation otherwise — never a mix, which is what let the observer and the paint disagree about
   * whether anything had moved in the browser P2 was verified in.
   */
  const measureAndPaint = useCallback(() => {
    // `land()` rather than a retry: the paint effect re-runs when `landNonce` moves, so waiting
    // here costs nothing and cannot consume the measurement budget (see MAX_MEASURE_TRIES).
    if (geoFailed || !enabled || measureKey == null) return DECLINED;
    if (requiresLand && !land()) return DECLINED;
    // A caller that supplied neither a measurement nor an aspect cannot be answered: the derived
    // height would be NaN, every comparison against it false, and the only symptom thirty silent
    // animation frames followed by a permanently blank canvas. Decline once, loudly.
    if (!measure && !(aspect > 0)) {
      if (!warnedRef.current) {
        warnedRef.current = true;
        console.warn('[useHeatCanvas] needs either a `measure` function or a positive `aspect`');
      }
      return DECLINED;
    }
    // ONE measurement for every registered canvas, taken from `measureKey`'s well. Siblings have
    // to be comparable — that is the whole claim the strip makes — and measuring each
    // independently would let a sub-pixel column difference paint two of them at different
    // scales. The well is an unpadded wrapper, so its `clientWidth` is the drawable width
    // directly; measuring the button would need its padding subtracted, which is the prototype's
    // `- 12` and a constant that silently rots the moment the stylesheet changes.
    const first = canvasRefs.current.get(measureKey);
    const well = measure ? null : first?.parentElement;
    const box = measure ? measure() : null;
    const width = measure ? (box ? box.width : 0) : (well ? well.clientWidth : 0);
    const height = measure ? (box ? box.height : 0) : Math.round(width * aspect);
    if (!(width > minPx) || !(height > MIN_CANVAS_PX)) return TOO_SMALL;
    // Checked after the box, so a host that has not been given a canvas yet is "too small" (and so
    // retried) rather than a hard decline.
    if (!first) return TOO_SMALL;
    // P0 left this guard to whoever mounts a canvas, and this hook is that caller: `field`/`fit`
    // DEREFERENCE the 2d context rather than declining, and a real browser can return null for it
    // under memory pressure. Unguarded, the TypeError is thrown inside an effect and takes the
    // whole Plan pane to the error boundary — a picture costing the reader every window row.
    // Checked once on the measured canvas rather than per canvas: siblings are created in one
    // commit, so the condition is a property of the document rather than of one element.
    if (!first.getContext('2d')) {
      setGeoFailed(true);
      return DECLINED;
    }
    paint({ width, height, canvases: canvasRefs.current });
    return PAINTED;
  }, [geoFailed, enabled, measureKey, requiresLand, measure, aspect, minPx, paint]);

  /**
   * The latest {@link measureAndPaint}, so {@code repaint}/{@code repaintNow} can be STABLE.
   *
   * <p>A map binds them to Leaflet's own event emitter, and an identity that changed whenever the
   * window selection or the confidence tier did would re-subscribe six handlers on every such
   * change — and, worse, a frame already scheduled would paint through the callback captured when
   * it was scheduled rather than the current one.
   */
  const paintRef = useRef(measureAndPaint);
  // A LAYOUT effect, not a passive one: a passive effect flushes in a scheduler task that can land
  // after the browser's next animation frame, so a `move` that scheduled a frame in the same tick as
  // a window change would paint the previous window's points through the stale closure. One frame of
  // the wrong field, self-correcting — and avoidable for the cost of one word.
  useLayoutEffect(() => { paintRef.current = measureAndPaint; }, [measureAndPaint]);

  const rafRef = useRef(0);
  const warnedRef = useRef(false);

  /**
   * The settle: paint in this tick, cancelling any frame already owed.
   *
   * <p>Cancelling matters. Without it a {@code moveend} paint is followed one frame later by the
   * paint the last {@code move} had scheduled — same picture, twice, at the end of every gesture.
   */
  const repaintNow = useCallback(() => {
    // ⚠️ NOT deduped here, and an earlier cut that was is worth recording. Leaflet fires `zoomend`
    // then `moveend` synchronously on every zoom, so binding both — which §4.5 does — settles twice
    // per gesture. A same-tick latch removes the second paint and also removes any LATER settle in
    // the same task, which is not a theoretical loss: driving the map through several `setZoom`
    // calls in one tick left the marker fade frozen at the first one. Measured in the browser.
    // Duplicate settles are the HOST's to recognise, because only the host knows whether the view
    // actually moved — see `MapHeatLayer`'s own `settle`.
    if (rafRef.current) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = 0;
    }
    paintRef.current();
  }, []);

  /**
   * The throttle: at most one paint per animation frame, and the FIRST call in a frame is the one
   * that schedules it.
   *
   * <p>Straight from the prototype (`map-tab.js:96-100`). A debounce here would push the frame back
   * on every {@code move} event and so paint nothing at all until the drag stopped.
   */
  const repaint = useCallback(() => {
    if (rafRef.current) return;
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = 0;
      paintRef.current();
    });
  }, []);

  useEffect(() => () => {
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
  }, []);

  /**
   * Whether an observation is of a box nothing can be painted into.
   *
   * <p>The width test is unconditional and is the one that has always been here — hiding a pane
   * fires an observation at 0×0, and acting on it spends a frame proving the canvas is too small.
   * The height joins it only for a host that watches the height at all: a static host's frame
   * legitimately reports a zero height in a layout-free environment, and rejecting it there would
   * turn the existing observers off.
   */
  const zeroBox = useCallback(
    (width, height) => width === 0 || (watchesHeight && height === 0),
    [watchesHeight],
  );

  /** Whether an observation is worth a repaint, per {@link watchesHeight}. */
  const changed = useCallback((width, height) => (
    width !== lastSizeRef.current.width
    || (watchesHeight && height !== lastSizeRef.current.height)
  ), [watchesHeight]);

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
    //
    // Deliberately NOT `measureAndPaint`'s reading: this runs during the commit that attached the
    // node, so the caller's `measure` may not yet be able to answer (a Leaflet map is not laid out
    // until its own effects have run). The frame's own box is what was available before and is what
    // the seed has always been; a first observation that disagrees is exactly the repaint the
    // observer exists to deliver.
    lastSizeRef.current = { width: node.clientWidth, height: node.clientHeight };
    if (typeof ResizeObserver === 'undefined') return;
    // Gated on the measurement actually changing, not on any observation. A ResizeObserver delivers
    // one callback immediately on `observe()`, and the first paint itself resizes the frame (a
    // canvas goes from its 300×150 intrinsic ratio to `width × aspect`), so an ungated observer
    // repaints twice more on every mount — which would quietly undo the `alreadyLoaded` guard
    // above, whose whole purpose is avoiding a doubled first paint.
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
      // on a resize: at 360px the canvases were still drawn at the 390px width. (The symptom then
      // was silent clipping by `.wf-hc`'s `overflow: hidden`; the v3 card is `overflow: visible` for
      // its pick legend, and what keeps a stale bitmap inside its box now is `.wf-hc-cv canvas`'s
      // own `width: 100%`.) One measurement API for both ends.
      const width = node.clientWidth;
      const height = node.clientHeight;
      if (zeroBox(width, height)) return;
      if (!changed(width, height)) return;
      lastSizeRef.current = { width, height };
      setResizeNonce((n) => n + 1);
    });
    ro.observe(node);
    observerRef.current = ro;
  }, [changed, zeroBox]);

  useEffect(() => () => observerRef.current?.disconnect(), []);

  /**
   * The window's own resize, alongside the observer.
   *
   * <p>Belt and braces, and the braces are the ones that were measured. The prototype redrew on
   * {@code window.resize} and P2 started with a {@code ResizeObserver} alone, on the reasoning that
   * an observer catches strictly more (a pane revealed from {@code display: none} fires no window
   * resize). That is true and the observer stays for exactly that case — but in the browser it was
   * checked in, the observer did not fire on a viewport change at all: at 360px the canvases were
   * still drawn at their 390px width. Two triggers for one repaint is cheap; the repaint is idempotent (`lastSizeRef` makes a no-change
   * resize a no-op) and the paint itself is a coarse-grid field.
   */
  useEffect(() => {
    const onResize = () => {
      const node = frameNodeRef.current;
      const width = node?.clientWidth ?? 0;
      const height = node?.clientHeight ?? 0;
      if (zeroBox(width, height)) return;
      if (!changed(width, height)) return;
      lastSizeRef.current = { width, height };
      setResizeNonce((n) => n + 1);
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [changed, zeroBox]);

  useEffect(() => {
    let raf = 0;
    let cancelled = false;
    const attempt = (tries) => {
      if (cancelled) return;
      // Only TOO_SMALL is retried — see the outcome constants. A hard decline must not spend the
      // budget, and in the no-context case must not keep calling `getContext` after the hook has
      // already settled into its failure state.
      if (measureAndPaint() === TOO_SMALL && tries < MAX_MEASURE_TRIES) {
        raf = requestAnimationFrame(() => attempt(tries + 1));
      }
    };
    attempt(0);
    return () => {
      cancelled = true;
      if (raf) cancelAnimationFrame(raf);
    };
  }, [measureAndPaint, landNonce, resizeNonce]);

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

  return {
    attachFrame, canvasRef, geoFailed, repaint, repaintNow,
  };
}
