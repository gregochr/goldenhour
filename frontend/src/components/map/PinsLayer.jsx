import {
  useCallback, useEffect, useLayoutEffect, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';
import L from 'leaflet';
import { useMap } from 'react-leaflet';
import { seedObstacles } from '../../utils/labelPlacement.js';
import { homeLabelItems, placeLabelPass, verdictWord } from '../../utils/mapLabels.js';
import { formatDriveDuration } from '../../utils/briefingDisplay.js';
import { rampHex } from '../../utils/scoreRamp.js';
import { readableInkOn } from '../../utils/windowFirstSpots.js';
import { NO_DATA_COLOUR, STAND_DOWN_COLOUR } from '../markerUtils.js';

/**
 * The pane the pins paint into, and its stacking order — the SAME tier {@code MapLabels.jsx} uses
 * (see its own {@code LABEL_PANE_Z} note for why 650, not the design bundle's 420), because Pins
 * mode replaces the label layer one-for-one rather than sitting beside it: the two never mount
 * together (map-tab-v2-plan.md §3 P10 — the tab's Heat/Pins segment is one or the other).
 */
const PIN_PANE = 'wf-pins';
const PIN_PANE_Z = 650;

/**
 * Live chrome to seed as obstacles for the HOME marker's own placement pass — the exact list
 * `MapLabels.jsx`'s `OBSTACLE_SELECTOR` carries, duplicated rather than imported: the two layers
 * never mount together, and importing a heat-mode module into the pins-mode one for five strings
 * would be a stranger coupling than repeating them (see that file's own comment for the source of
 * each testid). Includes the Legend chip/panel even though neither renders in Pins mode — harmless
 * ({@code querySelectorAll} simply finds nothing — and future-proof if a later phase ever changes
 * that).
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

/** Leaflet's own bottom-right corner container — see `MapLabels.jsx`'s identical constant. */
const LEAFLET_CORNER_SELECTOR = '.leaflet-bottom.leaflet-right';

/** The pixel size of a NAMED pin — README §3: "named locations 26px with `N★` inside". */
const NAMED_PIN_PX = 26;

/**
 * The pixel size of an UNNAMED pin — README §3: "unnamed 13px". Dormant in production: every
 * catalogue location this app has is named (map-tab-v2-plan.md §4.7), so this class exists only so
 * a future unnamed row (should the catalogue ever grow one) has somewhere to land rather than being
 * silently drawn at full size.
 */
const UNNAMED_PIN_PX = 13;

/** `.wf-maplab-tip`'s own CSS `max-width` — see `MapLabels.jsx`'s identical fallback constant. */
const TOOLTIP_WIDTH_FALLBACK = 240;

/**
 * A rating sorts as this when absent — the WEAKEST possible value, so an unrated/stand-down pin
 * always draws first (i.e. underneath every rated one). "Weakest first, so the best sit on top"
 * (README §3) already implies this: an unknown is not a good, matching the app's own "absence
 * means unknown, never out of reach" rule elsewhere (`windowFirstSpots.js`'s own ordering comment).
 */
const UNRATED_SORT_VALUE = -Infinity;

/** [r, g, b] channels of a `#rrggbb` string, scaled by `factor` — the pin's own drop-shadow rim. */
function darkenHex(hex, factor) {
  const n = parseInt(hex.slice(1), 16);
  const r = Math.floor(((n >> 16) & 255) * factor);
  const g = Math.floor(((n >> 8) & 255) * factor);
  const b = Math.floor((n & 255) * factor);
  return `rgb(${r},${g},${b})`;
}

/**
 * The Map tab's Pins mode (map-tab-v2-plan.md §3 P10,
 * `docs/design/map-tab-v2/README.md` §3 "Heat / Pins segmented control") — "the honest comparison":
 * one dot per location, no field, drawn weakest-first so the best sit on top. Replaces the
 * medallion+cluster view on the tab (medallions, clustering and azimuth lines stay overlay-only —
 * decision D-9 for azimuth); the coastline stroke keeps drawing underneath, painted by
 * `MapHeatLayer`'s own `fieldEnabled={false}` contract, not by this layer.
 *
 * <h2>Why pins skip the greedy placement pass MapLabels uses</h2>
 *
 * <p>This is the "honest" view precisely because it never hides a location for want of clear air —
 * the prototype draws every dot at its own exact geographic point, overlaps and all, which is the
 * comparison heat mode's density-ramped chips cannot make. So only the HOME marker (with its own
 * text label) goes through the collision-avoidance placement pass `MapLabels.jsx` uses; the pins
 * themselves are placed directly from the projected point, unmeasured.
 *
 * <h2>The home label's obstacle list</h2>
 *
 * <p>The design bundle's own `drawPins()` places its home label against an EMPTY obstacle list —
 * a prototype shortcut, not a design choice (map-tab-v2-plan.md §3 P10's own brief): this host uses
 * the full live-chrome obstacle list instead, the same {@link OBSTACLE_SELECTOR}-driven collection
 * `MapLabels.jsx` seeds its own placement pass with.
 *
 * <h2>Hover tooltip parity with the P8 chip</h2>
 *
 * <p>Reuses `MapLabels.jsx`'s own tooltip CSS classes verbatim (`.wf-maplab-tip*`) rather than a
 * new visual language for the same card — the two layers never mount together, so there is no risk
 * of a shared class drifting between two live styles at once.
 *
 * @param {object} props
 * @param {Array<{name: string, lat: number, lng: number, rid: string, rating: ?number,
 *   bortleClass: ?number, driveMinutes: ?number, named: ?boolean, isStandDown: ?boolean}>}
 *   props.spots the filtered pool for the current window — the SAME shape/source `MapView` already
 *   builds for `MapLabels` (`labelSpots`), so admin reveal toggles and every other filter already
 *   apply. `named` defaults to true when absent (map-tab-v2-plan.md §4.7 — every production
 *   location is named today). `isStandDown` distinguishes a triaged location from a plain unrated
 *   one (adversarial review C8) — both carry no `rating`, but only one is painted with the
 *   medallions' own `STAND_DOWN_COLOUR` rather than the shared `NO_DATA_COLOUR`.
 * @param {?{lat: number, lon: number}} [props.homeCoords] the saved home postcode, or null
 * @param {?string} [props.selectedName] the selected location's name, for the tooltip/aria parity
 *   with the chip layer (pins carry no distinct "selected" treatment — `MapCallout`'s own
 *   `.wf-selmk` ring already marks the selection independently of this layer)
 * @param {?Function} [props.onSelect] called with a location's name on pin click — the SAME
 *   handler `MapLabels`' chips call, so a pin click opens the P9 callout exactly as a chip click
 *   does
 * @param {string} [props.eventLabel] the active EV row's label+time, for the hover tooltip
 */
export default function PinsLayer({
  spots, homeCoords = null, selectedName = null, onSelect = null, eventLabel = '',
}) {
  const map = useMap();

  const [pane] = useState(() => {
    if (!map?.createPane) return null;
    const el = map.getPane?.(PIN_PANE) || map.createPane(PIN_PANE);
    if (el?.style) {
      el.style.zIndex = String(PIN_PANE_Z);
      // Annotations on the map, like `MapLabels`' own pane — only a real `<button>` (every pin,
      // below) re-enables the click.
      el.style.pointerEvents = 'none';
    }
    return el || null;
  });

  const [frame, setFrame] = useState(null);
  /** {frame, placed: Map<'home', box>} once the home label's own measure-then-place pass has run. */
  const [placement, setPlacement] = useState(null);
  const [hover, setHover] = useState(null);
  const [tipPos, setTipPos] = useState({ x: 0, y: 0 });

  const rootRef = useRef(null);
  /**
   * ⚠️ The SAME native-bubbling fix `MapLabels.jsx` documents at length: a pin is a plain HTML
   * `<button>` inside a Leaflet pane, not an `L.Marker` (which stops its own click from bubbling to
   * the map), so without this a pin click reaches `MapBackgroundClickController` on the SAME click
   * and clears the selection it just set. Applied to the layer root once, via a callback ref.
   */
  const setRootRef = useCallback((node) => {
    rootRef.current = node;
    if (node && L?.DomEvent?.disableClickPropagation) L.DomEvent.disableClickPropagation(node);
  }, []);
  const homeRef = useRef(null);
  const tipRef = useRef(null);

  const paint = useCallback(() => {
    if (typeof map?.getSize !== 'function') return;
    const container = map.getContainer?.();
    // Same "am I actually on screen" guard `MapHeatLayer.measure`/`MapLabels.paint` both use — a
    // hidden shell panel still reports a cached Leaflet size.
    if (container && !container.offsetWidth) return;
    const size = map.getSize();
    const width = size.x;
    const height = size.y;
    if (!(width > 20) || !(height > 20)) return;
    const zoom = map.getZoom();

    const home = (homeCoords?.lat != null && homeCoords?.lon != null)
      ? map.latLngToContainerPoint([homeCoords.lat, homeCoords.lon])
      : null;

    // Weakest first (README §3), so later DOM order — later paint — is what puts the best pin on
    // top with no z-index bookkeeping: two plain, unstacked absolutely-positioned siblings paint in
    // source order. Missing/non-finite ratings sort as the WEAKEST value, never as strongest.
    const pins = [...spots]
      .sort((a, b) => {
        const ra = Number.isFinite(a.rating) ? a.rating : UNRATED_SORT_VALUE;
        const rb = Number.isFinite(b.rating) ? b.rating : UNRATED_SORT_VALUE;
        return ra - rb;
      })
      .map((spot) => {
        const p = map.latLngToContainerPoint([spot.lat, spot.lng]);
        return { spot, x: p.x, y: p.y };
      });

    setFrame({
      width,
      height,
      home,
      homeItems: home ? homeLabelItems(home, zoom) : [],
      pins,
    });
  }, [map, spots, homeCoords]);

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

  // Repaints whenever an input the map's own events cannot see changes (the pool, home) — mirrors
  // `MapLabels.jsx`'s identical effect.
  useEffect(() => { repaintNow(); }, [paint, repaintNow]);

  /**
   * The home label's own measure-then-place pass — the ONLY item this layer places by collision
   * avoidance (see the class doc). Keyed on the `frame` object's identity, same guard
   * `MapLabels.jsx`/`WindowRowFieldMap` both use.
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

    let obstacles = [];
    const containerEl = map?.getContainer?.();
    if (containerEl) {
      const containerRect = containerEl.getBoundingClientRect();
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
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPlacement({ frame, placed });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [frame, map]);

  useEffect(() => {
    if (!rootRef.current || typeof map?.containerPointToLayerPoint !== 'function') return;
    L.DomUtil?.setPosition?.(rootRef.current, map.containerPointToLayerPoint([0, 0]));
  });

  const isMeasured = placement?.frame === frame;
  const homeBox = isMeasured ? placement.placed.get('home') : undefined;
  const homeStyle = (() => {
    if (isMeasured && !homeBox) return { display: 'none' };
    if (!homeBox) return { left: '-9999px', top: '0px', visibility: 'hidden' };
    return { left: `${homeBox.x}px`, top: `${homeBox.y}px` };
  })();

  const positionTip = useCallback((event) => {
    // The MAP CONTAINER's rect, never `rootRef` — see `MapLabels.jsx`'s identical note (PR #733
    // review): every child here is absolutely positioned, so the layer root itself never acquires
    // an intrinsic size to clamp against.
    const wrapRect = map?.getContainer?.()?.getBoundingClientRect();
    if (!wrapRect) return;
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

  const pinsLayer = createPortal(
    <div ref={setRootRef} className="wf-maplab-layer" data-testid="pins-layer">
      {frame.homeItems.map((it) => (
        <span
          key={it.key}
          ref={homeRef}
          className="wf-hm"
          aria-hidden="true"
          data-testid="map-label-home"
          style={homeStyle}
        >
          <i className="wf-hm-mk" />
          <span className="wf-hm-lb">HOME</span>
        </span>
      ))}

      {frame.pins.map(({ spot, x, y }) => {
        const hasRating = Number.isFinite(spot.rating);
        const named = spot.named !== false;
        const size = named ? NAMED_PIN_PX : UNNAMED_PIN_PX;
        // A triaged (stand-down) location is not "nothing scored yet" — it is a decision the
        // pipeline made about tonight, the same distinction the medallion markers already draw
        // (`resolveStandDown`/`STAND_DOWN_COLOUR`). Collapsing it into the same grey as a plain
        // unrated spot would lose that distinction on the one view built to be the honest
        // comparison (adversarial review C8).
        const fill = hasRating ? rampHex(spot.rating) : (spot.isStandDown ? STAND_DOWN_COLOUR : NO_DATA_COLOUR);
        const ink = hasRating ? readableInkOn(fill) : NO_DATA_COLOUR;
        return (
          <button
            key={spot.name}
            type="button"
            className="wf-pin"
            data-testid="map-pin"
            data-named={named ? 'true' : 'false'}
            data-stand-down={!hasRating && spot.isStandDown ? 'true' : undefined}
            data-selected={selectedName === spot.name ? 'true' : undefined}
            aria-label={hasRating ? `${spot.name}, ${spot.rating} star` : spot.name}
            style={{
              left: `${x}px`,
              top: `${y}px`,
              width: size,
              height: size,
              background: fill,
              color: ink,
              boxShadow: `0 2px 0 -1px ${darkenHex(fill, 0.5)}, 0 5px 12px rgba(0,0,0,.5)`,
            }}
            onClick={() => onSelect?.(spot.name)}
            onMouseEnter={(e) => showTip(spot, e)}
            onMouseMove={positionTip}
            onMouseLeave={hideTip}
          >
            {named && hasRating && (
              <>
                {spot.rating}
                <span className="wf-pin-star" aria-hidden="true">&#9733;</span>
              </>
            )}
          </button>
        );
      })}
    </div>,
    pane,
  );

  // A separate portal into the chrome wrapper, for the SAME stacking-context reason
  // `MapLabels.jsx`'s tooltip documents at length — reused verbatim here since the two layers never
  // mount together.
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
    </div>,
    chromeRoot,
  );

  return (
    <>
      {pinsLayer}
      {tooltip}
    </>
  );
}

PinsLayer.propTypes = {
  spots: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string.isRequired,
    lat: PropTypes.number.isRequired,
    lng: PropTypes.number.isRequired,
    rid: PropTypes.string.isRequired,
    rating: PropTypes.number,
    bortleClass: PropTypes.number,
    driveMinutes: PropTypes.number,
    named: PropTypes.bool,
    isStandDown: PropTypes.bool,
  })).isRequired,
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  selectedName: PropTypes.string,
  onSelect: PropTypes.func,
  eventLabel: PropTypes.string,
};
