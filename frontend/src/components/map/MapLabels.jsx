import {
  useCallback, useEffect, useLayoutEffect, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';
import L from 'leaflet';
import { useMap } from 'react-leaflet';
import { pxPerKmAtHome, RING_MIN_PX, RING_TIERS } from '../../utils/reachRings.js';
import { seedObstacles } from '../../utils/labelPlacement.js';
import {
  chipCandidates, homeLabelItems, placeLabelPass, regionLabelItems, ringLabelItems, verdictWord,
  REGION_TINY_FRAME_WIDTH,
} from '../../utils/mapLabels.js';
import { formatDriveDuration } from '../../utils/briefingDisplay.js';
import { rampHex } from '../../utils/scoreRamp.js';

/**
 * The pane the label layer paints into, and its stacking order.
 *
 * <p>⚠️ 650, NOT the bundle's own 420 (PR #733 review — a confirmed finding, not a typo). The
 * design bundle's z-ladder ("heat 410 / selection ring 415 / labels 420 / CHROME 1100 / callout
 * 1350 / tooltip 1400 / MENUS 1500") assumed a heat view with NO markers ever rendering below the
 * label layer. Heat mode no longer fades Leaflet's real markers back in across the zoom handover
 * either (`MapHeatLayer.jsx`'s {@code hidesMarkers} — the chips ARE what the field hands over to),
 * so the bundle's assumption now HOLDS for the ordinary case — but not for all of it: a window with
 * nothing scored still keeps the medallions at full opacity, in Leaflet's own {@code markerPane} at
 * its built-in z600, sitting on the exact same projected points as the chips, since a chip and its
 * marker name the same location. At 420 a chip would render, and hit-test, UNDER its own marker on
 * exactly the windows where the chip is the only thing carrying a name. 650 clears Leaflet's marker
 * pane (600) while staying below its popup pane (700) and every chrome chip (1100+) — the P7 chrome
 * comment in index.css records the ladder with this correction.
 */
const LABEL_PANE = 'wf-labels';
const LABEL_PANE_Z = 650;

/**
 * Live chrome to seed as obstacles (README §6: "the window bar, Regions/Heat/Pins/Filters bar,
 * Legend chip, count footer, zoom group, the open callout and any open menu") — every piece of
 * chrome this tab actually ships, live TODAY. Queried each placement pass from
 * `map.getContainer().parentElement` — the wrapper `MapView` renders both the Leaflet container and
 * these React-rendered siblings into.
 *
 * <p>`wf-jump-menu` (P11) is the Regions jump list's own open dropdown, and needs its OWN entry for
 * the same reason `wf-win-menu`/`wf-filters-panel` already do despite nesting inside
 * `wf-map-chrome-tl`/`wf-map-chrome-tr`: an absolutely-positioned dropdown overflows its trigger
 * chip's own layout box, so the chrome wrapper's `getBoundingClientRect()` does not cover it — only
 * the panel's own rect does. The closed chip needs no entry of its own; the wrapper already covers it.
 */
const OBSTACLE_SELECTOR = [
  '[data-testid="wf-map-chrome-tl"]',
  '[data-testid="wf-map-chrome-tr"]',
  '[data-testid="wf-map-chrome-bl"]',
  '[data-testid="wf-map-counts-footer"]',
  '[data-testid="wf-win-menu"]',
  '[data-testid="wf-jump-menu"]',
  '[data-testid="wf-filters-panel"]',
  '[data-testid="wf-legend-panel"]',
  '[data-testid="colour-scale-notice"]',
  '[data-testid="viewline-upsell-chip"]',
  '[data-testid="photocast-scored-legend"]',
].join(', ');

/**
 * Leaflet's OWN bottom-right corner container (`.leaflet-bottom.leaflet-right`) — the zoom
 * control and {@code CentreOnHomeControl} both register at {@code position: 'bottomright'} and so
 * share this one div, appended by Leaflet as a child of the map's OWN container rather than as a
 * sibling of it. {@link OBSTACLE_SELECTOR} cannot reach it (it is queried from the container's
 * PARENT), so it is queried from the container itself, separately, below.
 */
const LEAFLET_CORNER_SELECTOR = '.leaflet-bottom.leaflet-right';

/**
 * A ring wider than this multiple of the frame's larger side is entirely off-frame — skip its
 * label along with its circle. {@code MapHeatLayer}'s own constant of the same name and value
 * (itself matching {@code WindowRowFieldMap}'s); not exported from `utils/reachRings.js` because
 * it is a per-host DISPLAY concern, not a fact about the tiers — see that module's own note.
 */
const RING_OFFFRAME_FACTOR = 1.15;

/**
 * The tooltip's own CSS `max-width` (`.wf-maplab-tip` in index.css) — the fallback used to clamp
 * its position before the node has rendered once and so cannot yet report a real
 * {@code offsetWidth}. See {@code positionTip}'s own note.
 */
const TOOLTIP_WIDTH_FALLBACK = 240;

/**
 * The Map tab's HTML label layer (map-tab-v2-plan.md §3 P8,
 * `docs/design/map-tab-v2/README.md` §6 "Labels — placement and density") — home marker, reach
 * ring labels, region names and location chips, placed by one greedy pass in priority order and
 * re-run through the same rAF-guarded cadence the field paints on.
 *
 * <h2>Why this is a Leaflet PANE host, exactly like {@code MapHeatLayer}</h2>
 *
 * <p>Labels are positioned in CONTAINER pixel coordinates (`map.latLngToContainerPoint`), so the
 * layer has to be glued to the container origin the same way the field's canvas is — a Leaflet
 * pane is translated during a drag, and {@code L.DomUtil.setPosition} on this layer's own root
 * cancels that translation every paint, exactly like {@code MapHeatLayer}'s canvas.
 *
 * <h2>Two-pass measure-then-place, the {@code WindowRowFieldMap} pattern generalised</h2>
 *
 * <p>A label's box depends on its own rendered text (a font that may not have loaded, a name of
 * unknown length), so it cannot be computed before the browser lays it out. Every candidate is
 * therefore rendered once, off-screen, to measure; a {@code useLayoutEffect} reads the real
 * {@code offsetWidth}/{@code offsetHeight} off refs, runs {@code utils/mapLabels.placeLabelPass}
 * (the Map tab's own ladder, `labelPlacement.js`'s {@code MAP_NUDGES}/{@code mapDxOffsets}), and
 * commits the result to state — which is what the SECOND render actually positions. This is a
 * LAYOUT effect rather than a passive one for the same reason {@code WindowRowFieldMap}'s is: an
 * ordinary effect would let the off-screen pass paint first, and every label would visibly fly in
 * from {@code left:-9999px} on every repaint.
 *
 * <h2>Obstacles are read from the live chrome DOM, not threaded as props</h2>
 *
 * <p>{@link OBSTACLE_SELECTOR} matches the testids the P7 chrome already carries
 * ({@code wf-map-chrome-tl}/{@code -tr}, the counts footer, and the two menus that can be open at
 * once) — queried from {@code map.getContainer().parentElement}, the SAME wrapper `MapView`
 * already renders both the Leaflet container and the chrome siblings into, so no extra ref needs
 * threading down from `MapView` for this to work.
 *
 * <h2>Re-placed on the SAME rAF-guarded cadence as the field, never a second listener storm</h2>
 *
 * <p>A dedicated {@code move zoom viewreset resize} → throttled repaint, {@code moveend zoomend}
 * → immediate settle pair, mirroring {@code MapHeatLayer}'s own event names and throttle-vs-debounce
 * split exactly — a SEPARATE subscription rather than a shared callback, because this layer's
 * paint touches no canvas and shares no measurement with the field's hook-based host.
 *
 * @param {object}   props
 * @param {Array<{name: string, lat: number, lng: number, rid: string, rating: ?number,
 *   bortleClass: ?number, driveMinutes: ?number}>} props.spots the filtered pool for the CURRENT
 *   window — the same "named" pool `MapView` already derives for its markers (§4.5's
 *   `scopedVisibleLocations`), joined with the current window's rating
 * @param {?{lat: number, lon: number}} [props.homeCoords] the saved home postcode, or null
 * @param {boolean}  [props.rings] the reach-rings toggle — SAME state `MapHeatLayer` reads, so the
 *   canvas circles and their labels can never disagree about being on or off
 * @param {boolean}  [props.reachMeasured] whether a real drive time gates this screen's reach lens
 *   — ring labels state a duration only when true, a plain distance otherwise (§5.2's honesty
 *   rule, the same one `WindowRowFieldMap`'s own ring labels apply)
 * @param {?string}  [props.selectedName] the selected location's name — always chipped
 * @param {?Function} [props.onSelect] called with a location's name on chip click — the caller
 *   wires this to the SAME path a marker click already takes (today: `setSelectedLocationName` +
 *   the Leaflet popup; P9 replaces the far end with the callout, not this call site)
 * @param {string}   [props.eventLabel] the active EV row's label+time, for the hover tooltip's
 *   "event" line (e.g. "Sunset · Tonight 19:58")
 */
export default function MapLabels({
  spots, homeCoords = null, rings = false, reachMeasured = false, selectedName = null,
  onSelect = null, eventLabel = '',
}) {
  const map = useMap();

  const [pane] = useState(() => {
    if (!map?.createPane) return null;
    const el = map.getPane?.(LABEL_PANE) || map.createPane(LABEL_PANE);
    if (el?.style) {
      el.style.zIndex = String(LABEL_PANE_Z);
      // Labels are annotations on the field, and every place one sits is also a real marker —
      // the click belongs to the map/chip beneath, never the layer as a whole (mirrors
      // `MapHeatLayer`'s identical pane-level rule, and `.wf-mchips`' own precedent).
      el.style.pointerEvents = 'none';
    }
    return el || null;
  });

  /** The candidate lists for the CURRENT paint — pure data, no DOM measurement yet. */
  const [frame, setFrame] = useState(null);
  /** {frame, placed: Map<key, box>} once the measure-then-place pass has run for THIS frame. */
  const [placement, setPlacement] = useState(null);
  const [hover, setHover] = useState(null);
  const [tipPos, setTipPos] = useState({ x: 0, y: 0 });

  const rootRef = useRef(null);
  /**
   * ⚠️ BLOCKING fix (map-tab-v2-plan.md §3 P9 browser verification): a chip click was reaching
   * `onSelect` correctly, but the selection was wiped on the SAME click, every time — traced live,
   * instrumented call-by-call, to this firing order within one native click: chip's `onClick` →
   * `selectMapLocation` (sets the selection) → P7's `MapBackgroundClickController`
   * (`MapView.jsx`) firing right after, on the SAME click, and clearing it again. A chip is a plain
   * HTML `<button>` inside this layer's Leaflet PANE (`map.createPane`, a real descendant of
   * `.leaflet-map-pane`/`.leaflet-container`), not a Leaflet `Marker` — real markers never trigger
   * this because `L.Marker` stops its own click from bubbling to the map (`bubblingMouseEvents:
   * false`); nothing did that for a bare button, so the click kept bubbling into Leaflet's own
   * container listener and fired the MAP's background-click handler right after React's, clearing
   * the very selection it had just set. `CentreOnHomeControl`'s identical comment already names the
   * fix for an HTML control sitting over the map: a React `stopPropagation` fires too late (it runs
   * during React's OWN dispatch, which happens AFTER Leaflet's native container listener in this
   * bubble order) — `L.DomEvent.disableClickPropagation` stops the NATIVE `mousedown`/`click`/etc.
   * before Leaflet's own container listener ever sees them. Applied to the layer ROOT once, via a
   * callback ref (fires exactly on attach, not every render) — every current and future clickable
   * label (today: only the location chip) is covered without a second call site to keep in step.
   */
  const setRootRef = useCallback((node) => {
    rootRef.current = node;
    if (node && L?.DomEvent?.disableClickPropagation) L.DomEvent.disableClickPropagation(node);
  }, []);
  const homeRef = useRef(null);
  const ringRefs = useRef(new Map());
  const regionRefs = useRef(new Map());
  const chipRefs = useRef(new Map());
  const tipRef = useRef(null);

  /**
   * One measure-and-paint pass: projects the current window's candidates and stores them as
   * `frame` — never touches the DOM beyond `map.*` reads, so it is safe to call from the
   * rAF-throttled `repaint`/immediate `repaintNow` pair below exactly like `MapHeatLayer`'s own.
   */
  const paint = useCallback(() => {
    if (typeof map?.getSize !== 'function') return;
    const container = map.getContainer?.();
    // Same guard `MapHeatLayer.measure` uses: a hidden shell panel still reports a cached
    // Leaflet size, so this is the only reliable "am I actually on screen" check.
    if (container && !container.offsetWidth) return;
    const size = map.getSize();
    const width = size.x;
    const height = size.y;
    if (!(width > 20) || !(height > 20)) return;
    const zoom = map.getZoom();
    const project = (spot) => {
      const p = map.latLngToContainerPoint([spot.lat, spot.lng]);
      return [p.x, p.y];
    };

    const home = (homeCoords?.lat != null && homeCoords?.lon != null)
      ? map.latLngToContainerPoint([homeCoords.lat, homeCoords.lon])
      : null;
    const ringsActive = Boolean(home) && rings;
    // Measured at HOME's own latitude (`pxPerKmAtHome`), never the viewport centre's — the SAME
    // fix `MapHeatLayer`'s canvas rings apply, so a ring's LABEL never drifts off its own circle
    // as the reader pans. The two skip rules below are `WindowRowFieldMap`'s own: illegibly small
    // (the shared `RING_MIN_PX` floor) or entirely off-frame (`RING_OFFFRAME_FACTOR`, a per-host
    // display constant — see that constant's own note on why it is not exported alongside the
    // floor). A ring MapHeatLayer skips drawing must not still carry a label with nothing to sit
    // on, so this list is what both the label pass AND the ring-label candidate builder read.
    const ringsWithRadius = ringsActive
      ? RING_TIERS
        .map((ring) => ({ ...ring, r: ring.km * pxPerKmAtHome(map, homeCoords) }))
        .filter((ring) => ring.r >= RING_MIN_PX && ring.r <= Math.max(width, height) * RING_OFFFRAME_FACTOR)
      : [];

    let inViewNames = null;
    if (typeof map.getBounds === 'function') {
      const bounds = map.getBounds();
      inViewNames = new Set(
        spots.filter((s) => bounds.contains([s.lat, s.lng])).map((s) => s.name),
      );
    }

    const chips = chipCandidates({
      spots, inViewNames, zoom, selectedName,
    }).map((spot) => {
      const [x, y] = project(spot);
      return { spot, x, y };
    });

    setFrame({
      width,
      height,
      home,
      regionItems: regionLabelItems(spots, project, zoom),
      ringItems: ringsActive ? ringLabelItems({
        homePoint: home, zoom, ringsWithRadius, frameHeight: height, reachMeasured,
        formatDuration: formatDriveDuration,
      }) : [],
      homeItems: home ? homeLabelItems(home, zoom) : [],
      chips,
      tiny: width < REGION_TINY_FRAME_WIDTH,
    });
  }, [map, spots, homeCoords, rings, reachMeasured, selectedName]);

  /**
   * The rAF-guarded throttle/settle pair, mirroring `MapHeatLayer.jsx`'s own — "never their own
   * listener storm" (map-tab-v2-plan.md §3 P8).
   */
  const paintRef = useRef(paint);
  useLayoutEffect(() => { paintRef.current = paint; }, [paint]);
  const rafRef = useRef(0);
  const repaintNow = useCallback(() => {
    if (rafRef.current) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = 0;
    }
    paintRef.current();
  }, []);
  const repaint = useCallback(() => {
    if (rafRef.current) return;
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = 0;
      paintRef.current();
    });
  }, []);

  useEffect(() => {
    if (typeof map?.on !== 'function') return undefined;
    map.on('move zoom viewreset resize', repaint);
    map.on('moveend zoomend', repaintNow);
    return () => {
      map.off('move zoom viewreset resize', repaint);
      map.off('moveend zoomend', repaintNow);
    };
  }, [map, repaint, repaintNow]);

  useEffect(() => () => {
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
  }, []);

  // Repaints whenever an input the map's own events cannot see changes — the pool, home, the
  // selection, the rings toggle: `paint`'s identity moves with any of them (its own dependency
  // list, above), and `paintRef` is already pointed at the fresh closure by the layout effect
  // above by the time this runs. `repaintNow` rather than a bare call so a frame the throttle
  // already owes is not doubled.
  useEffect(() => { repaintNow(); }, [paint, repaintNow]);

  /**
   * The measure-then-place pass (see the class doc). Keyed on the `frame` OBJECT's identity, the
   * same guard `WindowRowFieldMap` uses: a repaint that produced an identical-looking frame still
   * gets a fresh object, so this always re-measures after a real paint and never after a render
   * the frame itself did not cause (e.g. a hover-only state update).
   */
  useLayoutEffect(() => {
    if (!frame || placement?.frame === frame) return;
    const items = [];

    for (const it of frame.homeItems) {
      const node = homeRef.current;
      const w = node?.offsetWidth ?? 0;
      const h = node?.offsetHeight ?? 0;
      if (w > 0 && h > 0) items.push({ ...it, w, h });
    }
    for (const it of frame.ringItems) {
      const node = ringRefs.current.get(it.key);
      const w = node?.offsetWidth ?? 0;
      const h = node?.offsetHeight ?? 0;
      if (w > 0 && h > 0) items.push({ ...it, w, h });
    }
    for (const it of frame.regionItems) {
      const node = regionRefs.current.get(it.key);
      const w = node?.offsetWidth ?? 0;
      const h = node?.offsetHeight ?? 0;
      if (w > 0 && h > 0) items.push({ ...it, w, h });
    }
    for (const { spot, x, y } of frame.chips) {
      const node = chipRefs.current.get(spot.name);
      const w = node?.offsetWidth ?? 0;
      const h = node?.offsetHeight ?? 0;
      if (w > 0 && h > 0) items.push({
        key: `chip:${spot.name}`, x, y, w, h,
      });
    }

    let obstacles = [];
    const containerEl = map?.getContainer?.();
    if (containerEl) {
      const containerRect = containerEl.getBoundingClientRect();
      // Two roots, because the two kinds of chrome sit on opposite sides of the container: the
      // React-rendered pieces are its SIBLINGS (queried from its parent), while Leaflet's own
      // bottom-right corner (zoom + ⌂) is a CHILD of the container itself.
      const siblingChrome = containerEl.parentElement
        ? [...containerEl.parentElement.querySelectorAll(OBSTACLE_SELECTOR)]
        : [];
      const leafletChrome = [...containerEl.querySelectorAll(LEAFLET_CORNER_SELECTOR)];
      obstacles = seedObstacles(
        [...siblingChrome, ...leafletChrome].map((el) => el.getBoundingClientRect()),
        containerRect,
        5,
      );
    }

    const placed = placeLabelPass(items, frame.width, frame.height, obstacles);
    // A setState in an effect, and it is the case the rule's own escape hatch is for — the same
    // one `WindowRowFieldMap`'s identical measure-then-place effect documents: this is a
    // MEASUREMENT. A label's box is its text in a font the browser may still be swapping, so it
    // cannot be computed from props — the DOM has to be laid out and read first. The write is
    // idempotent and bounded by the guard above (once per genuinely new `frame`), and it is a
    // LAYOUT effect so the off-screen measuring pass is never painted.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPlacement({ frame, placed });
    // `placement` is read only as the guard on its own write (above) — listing it here would make
    // this effect re-run on the write it just made. `frame` is the identity that actually decides
    // whether there is new work, and it IS in the list.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [frame, map]);

  // Glues the layer to the container origin (see the class doc's "why this is a pane host" note)
  // — runs after every render, which costs nothing extra: `setPosition` is a no-op write when the
  // offset has not moved, and both the candidate render and the placed render need it equally.
  useEffect(() => {
    if (!rootRef.current || typeof map?.containerPointToLayerPoint !== 'function') return;
    L.DomUtil?.setPosition?.(rootRef.current, map.containerPointToLayerPoint([0, 0]));
  });

  const isMeasured = placement?.frame === frame;
  const boxFor = (key) => (isMeasured ? placement.placed.get(key) : undefined);

  const styleFor = (key) => {
    const box = boxFor(key);
    if (isMeasured && !box) return { display: 'none' };
    if (!box) return { left: '-9999px', top: '0px', visibility: 'hidden' };
    return { left: `${box.x}px`, top: `${box.y}px` };
  };

  const positionTip = useCallback((event) => {
    // ⚠️ The MAP CONTAINER's rect, never `rootRef` (PR #733 review — a confirmed regression from
    // the review round that added this clamp). `.wf-maplab-layer` is `position: absolute; left: 0;
    // top: 0` with no `inset`/width/height of its own, and every one of its children is ALSO
    // absolutely positioned — so it never acquires any intrinsic size from its content, and
    // `getBoundingClientRect()` on it reports `width: 0, height: 0` in a real browser, not merely
    // as a jsdom artefact. `wrapRect.width - tipWidth - 8` was therefore always a large negative
    // number, and `Math.min(rawX, that)` always picked it — every tooltip landed off-map to the
    // left, unconditionally. `map.getContainer()` is the one DOM node in this chain the app itself
    // sizes (`MapView`'s `style={{height:'100%',width:'100%'}}`), and its top-left corner is the
    // same point the label layer is glued to (`L.DomUtil.setPosition`), so switching the READING
    // to it changes nothing about where `left`/`top` land, only that `width`/`height` are now real.
    const wrapRect = map?.getContainer?.()?.getBoundingClientRect();
    if (!wrapRect) return;
    // The bundle's own `bindTip` rule (`map-tab-v2.js`): offset off the cursor, then clamp so the
    // card never runs past the frame's own edges. The tooltip's WIDTH is only knowable once it has
    // rendered at least once (`tipRef`); before that, `TOOLTIP_WIDTH_FALLBACK` — this CSS class's
    // own `max-width` — is a safe over-estimate for the clamp, since clamping against a width that
    // is too generous can only pull the card further from the edge than strictly necessary, never
    // let it overhang.
    const tipWidth = tipRef.current?.offsetWidth || TOOLTIP_WIDTH_FALLBACK;
    const rawX = event.clientX - wrapRect.left + 13;
    setTipPos({
      x: Math.min(rawX, wrapRect.width - tipWidth - 8),
      y: Math.max(6, event.clientY - wrapRect.top - 10),
    });
  }, [map]);
  const showTip = useCallback((spot, event) => {
    setHover(spot);
    positionTip(event);
  }, [positionTip]);
  const hideTip = useCallback(() => setHover(null), []);

  if (!pane || !frame) return null;

  const labelLayer = createPortal(
    <div ref={setRootRef} className="wf-maplab-layer" data-testid="map-labels">
      {frame.homeItems.map((it) => (
        <span
          key={it.key}
          ref={homeRef}
          className="wf-hm"
          aria-hidden="true"
          data-testid="map-label-home"
          style={styleFor(it.key)}
        >
          <i className="wf-hm-mk" />
          <span className="wf-hm-lb">HOME</span>
        </span>
      ))}

      {frame.ringItems.map((it) => (
        <span
          key={it.key}
          ref={(node) => {
            if (node) ringRefs.current.set(it.key, node);
            else ringRefs.current.delete(it.key);
          }}
          className="wf-ringlb"
          aria-hidden="true"
          data-testid="map-label-ring"
          style={styleFor(it.key)}
        >
          {it.text}
        </span>
      ))}

      {frame.regionItems.map((it) => (
        <span
          key={it.key}
          ref={(node) => {
            if (node) regionRefs.current.set(it.key, node);
            else regionRefs.current.delete(it.key);
          }}
          className="wf-maplab-region"
          aria-hidden="true"
          data-hot={it.hot ? 'true' : undefined}
          data-tiny={frame.tiny ? 'true' : undefined}
          data-testid="map-label-region"
          style={styleFor(it.key)}
        >
          {it.rid}
        </span>
      ))}

      {frame.chips.map(({ spot }) => {
        const key = `chip:${spot.name}`;
        const hasRating = Number.isFinite(spot.rating);
        const onTheLight = Boolean(spot.onTheLight);
        // Extends the rating announcement rather than replacing it — an aria-label REPLACES the
        // rendered text entirely, so the glyph's own meaning has to be spelled out here or a
        // screen-reader user never hears it at all.
        const ariaLabel = [
          hasRating ? `${spot.name}, ${spot.rating} star` : spot.name,
          onTheLight ? 'tide on the light' : null,
        ].filter(Boolean).join(', ');
        return (
          <button
            key={spot.name}
            type="button"
            ref={(node) => {
              if (node) chipRefs.current.set(spot.name, node);
              else chipRefs.current.delete(spot.name);
            }}
            className="wf-maplab-chip"
            data-testid="map-label-chip"
            data-selected={selectedName === spot.name ? 'true' : undefined}
            data-tide={onTheLight ? 'true' : undefined}
            style={styleFor(key)}
            aria-label={ariaLabel}
            onClick={() => onSelect?.(spot.name)}
            onMouseEnter={(e) => showTip(spot, e)}
            onMouseMove={positionTip}
            onMouseLeave={hideTip}
          >
            <i
              className="wf-maplab-chip-m"
              style={{ background: hasRating ? rampHex(spot.rating) : 'var(--color-plex-border-light)' }}
            />
            <b className="wf-maplab-chip-n">{spot.name}</b>
            {onTheLight && (
              // A glyph, not a second number (bundle rev 2's tide-chip tweak) — this window's
              // tide lands on the light here. The path is the design bundle's TIDEGLYPH verbatim.
              <svg
                className="wf-maplab-chip-tw"
                viewBox="0 0 14 8"
                aria-hidden="true"
                data-testid="map-label-chip-tide"
              >
                <path
                  d="M0.6 5.6C3 5.6 3 2.4 5.4 2.4S7.8 5.6 10.2 5.6 12.6 2.4 13.4 2.4"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                />
              </svg>
            )}
            {hasRating && <em className="wf-maplab-chip-r">{spot.rating}★</em>}
          </button>
        );
      })}
    </div>,
    pane,
  );

  // The hover tooltip is a SEPARATE portal, deliberately not a sibling inside the layer above.
  // `.leaflet-map-pane` carries a CSS `transform` (Leaflet's own panning mechanism), and a
  // transformed ancestor establishes a stacking context — every z-index inside it, including this
  // layer's own pane at 650, can only be compared against OTHER descendants of that SAME
  // transformed ancestor. The tooltip's declared z-index of 1400 (index.css's own ladder: "chrome
  // 1100 / callout 1350 / tooltip 1400 / menus 1500") is therefore inert while it lives inside
  // `pane`: real, on-screen chrome at 1100 (siblings of the Leaflet container, never descendants
  // of the transformed pane) would still paint OVER it. Portalling to the chrome wrapper —
  // `map.getContainer().parentElement`, the SAME node `MapView` renders both the Leaflet container
  // and the chrome siblings into — puts the tooltip in that outer stacking context instead, where
  // 1400 actually outranks every chip beneath it.
  const chromeRoot = map?.getContainer?.()?.parentElement ?? null;
  const tooltip = hover && chromeRoot && createPortal(
    <div
      ref={tipRef}
      className="wf-maplab-tip"
      data-testid="map-label-tip"
      style={{ left: `${tipPos.x}px`, top: `${tipPos.y}px` }}
    >
      <div className="wf-maplab-tip-n">{hover.name}</div>
      <div className="wf-maplab-tip-s">
        {[eventLabel, Number.isFinite(hover.rating)
          ? `${hover.rating}★ ${verdictWord(hover.rating) ?? ''}`.trim() : null]
          .filter(Boolean).join(' · ')}
      </div>
      <div className="wf-maplab-tip-s">
        {[
          hover.rid,
          Number.isFinite(hover.driveMinutes) ? formatDriveDuration(hover.driveMinutes) : null,
          hover.bortleClass != null ? `sky ${hover.bortleClass}` : null,
        ].filter(Boolean).join(' · ')}
      </div>
      {hover.onTheLight && hover.nearestSolarOffsetPhrase && (
        // A third line, teal-inked (`.wf-maplab-tip-t`, `--color-badge-tide` — measured 9.68:1,
        // never the raw bundle hex nor `--color-tide`, which is for borders/accents) — only when
        // this window's water actually lands on the light (bundle rev 2's tide-chip tweak).
        <div className="wf-maplab-tip-s wf-maplab-tip-t" data-testid="map-label-tip-tide">
          {`Tide lands on the light — ${hover.nearestSolarOffsetPhrase}`}
        </div>
      )}
    </div>,
    chromeRoot,
  );

  return (
    <>
      {labelLayer}
      {tooltip}
    </>
  );
}

MapLabels.propTypes = {
  spots: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string.isRequired,
    lat: PropTypes.number.isRequired,
    lng: PropTypes.number.isRequired,
    rid: PropTypes.string.isRequired,
    rating: PropTypes.number,
    bortleClass: PropTypes.number,
    driveMinutes: PropTypes.number,
    /** `PinsLayer`'s stand-down/no-data distinction — carried on every spot but read only by a
     * caller that draws pins alongside chips; untyped until now (`MapView.jsx`'s `spotOf` always
     * sets it). */
    isStandDown: PropTypes.bool,
    /** Bundle rev 2's tide-chip tweak — true when THIS window's water lands on the light here. */
    onTheLight: PropTypes.bool,
    /** The formatted "HW 19:52 · 36m before sunset" phrase, or null — only meaningful (and only
     * rendered) alongside `onTheLight: true`. */
    nearestSolarOffsetPhrase: PropTypes.string,
  })).isRequired,
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  rings: PropTypes.bool,
  reachMeasured: PropTypes.bool,
  selectedName: PropTypes.string,
  onSelect: PropTypes.func,
  eventLabel: PropTypes.string,
};
