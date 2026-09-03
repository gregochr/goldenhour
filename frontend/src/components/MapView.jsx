import React, { useState, useEffect, useMemo, useCallback, useRef, Suspense, lazy } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import { MapContainer, TileLayer, Marker, Popup, Polyline, useMapEvents, useMap } from 'react-leaflet';
import MarkerClusterGroup from 'react-leaflet-cluster';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import 'leaflet.markercluster/dist/MarkerCluster.css';
import { useAuth } from '../context/AuthContext.jsx';
import { useIsMobile } from '../hooks/useIsMobile.js';
import BottomSheet from './BottomSheet.jsx';
import MarkerPopupContent from './MarkerPopupContent.jsx';
import ForecastTypeSelector, { EVENT_TYPE_LABELS } from './ForecastTypeSelector.jsx';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';
import { getAuroraLocations, getAuroraForecastResults, getAuroraForecastAvailableDates } from '../api/auroraApi.js';
import { getDriveTimes } from '../api/settingsApi.js';
import { getAstroConditions, getAstroAvailableDates } from '../api/astroApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { isTravelDate, formatEventTimeUk } from '../utils/conversions.js';
import { fitBoundsKey } from '../utils/fitBoundsKey.js';
import { buildBriefingScoreIndex, lookupBriefingScore } from '../utils/briefingScoreIndex.js';
import { resolveStandDown } from '../utils/standDown.js';
import { resolveAuroraNight, ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';
import { LOCATION_TYPE_META, DISPLAY_TYPES, locationTypeLabel, SKY_SUBJECT_TYPES } from '../utils/locationTypes.js';
import AuroraViewlineOverlay from './AuroraViewlineOverlay.jsx';
import { rampHex, rampGradientCss, getMode } from '../utils/scoreRamp.js';
import WindowControl from './map/WindowControl.jsx';
import FiltersPopover from './map/FiltersPopover.jsx';
import MapCallout from './map/MapCallout.jsx';
import MapLegendPanel from './map/MapLegendPanel.jsx';
import RegionsJump from './map/RegionsJump.jsx';
import { fadeAt } from '../utils/heatHandover.js';
import { buildMapEvents, findEvIndex, solarHorizonDates, EVENT_KIND } from '../utils/mapEvents.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';
import { GLANCE_MINUTES } from '../utils/planningArea.js';
import { latLngBounds } from '../utils/heatGeometry.js';
import { buildJumpRows, regionBestRatingFor, buildNightRegionBest } from '../utils/regionsJump.js';

/** localStorage key for the "colours changed" notice's one-time dismissal. */
const COLOUR_SCALE_NOTICE_DISMISSED_KEY = 'colourScaleNoticeDismissed';

/**
 * The map's own localStorage filter keys, read/written fail-soft — a storage-denied browser
 * (Safari "Block all cookies", an enterprise site-data policy) throws `SecurityError` on bare
 * access, and several of these reads happen inside `useState` initializers, i.e. during render:
 * an unguarded throw there would crash the whole app, not just the map. Matches
 * `useLocalStorageState.js`'s existing convention.
 */
function readMapFilter(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function writeMapFilter(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Storage unavailable — the in-memory state still holds the value for this session.
  }
}
function clearMapFilter(key) {
  try {
    localStorage.removeItem(key);
  } catch {
    // Storage unavailable — nothing to clear.
  }
}

/**
 * The heat field, behind a `lazy()` boundary — and the boundary is load-bearing, not tidiness.
 *
 * <p>`MapHeatLayer` statically imports the kernel, which statically imports `d3-geo`. `MapView` is
 * mounted twice (the Map pane, the Plan overlay) and only ONE of them passes `heat`; a static
 * import here would put the `geo` chunk on the network for the overlay, which never renders the
 * layer. Nothing is fetched until something renders the layer, and only the opted-in caller can.
 */
const MapHeatLayer = lazy(() => import('./MapHeatLayer.jsx'));

/**
 * The label layer (map-tab-v2-plan.md §3 P8), behind the SAME kind of `lazy()` boundary as
 * {@code MapHeatLayer} above, for the identical reason: {@code utils/mapLabels.js} imports
 * {@code centroid} from {@code utils/heatField.js}, which statically imports {@code d3-geo}. A
 * static import here would put that chunk on the network for the Plan overlay too, which never
 * mounts this layer (`heat` is never handed to it).
 */
const MapLabels = lazy(() => import('./map/MapLabels.jsx'));

/**
 * Pins mode (map-tab-v2-plan.md §3 P10), behind the SAME kind of `lazy()` boundary as
 * {@code MapLabels} above and for the identical reason: it imports {@code homeLabelItems}/
 * {@code placeLabelPass} from {@code utils/mapLabels.js}, which is the module that carries the
 * `d3-geo` chain via {@code centroid}/`heatField.js`. Never mounted alongside {@code MapLabels} —
 * the tab's Heat/Pins segment is one view or the other — but both still need their OWN `lazy()`
 * entry, since Pins-only sessions must not pay for a `MapLabels` chunk they never render either.
 */
const PinsLayer = lazy(() => import('./map/PinsLayer.jsx'));

/**
 * The selection callout (map-tab-v2-plan.md §3 P9) — a plain static import, unlike the layers
 * above: it imports no `d3-geo`-carrying module (`utils/mapCallout.js`, `utils/locationSheet.js`,
 * `utils/scoreRamp.js`, `utils/locationTypes.js` are all leaf modules), so there is no weight to
 * keep off the Plan overlay's network in the first place, and a `lazy()` boundary here would only
 * add a Suspense flash the instant a reader selects a location.
 */

/**
 * Fits the map to a bounds box, once per nonce, WITHOUT animating.
 *
 * <p>⚠️ `{animate: false}` is the bundle's own trap and it is not a preference. A heavy field
 * paint in the same frame as an animated `fitBounds` forces layout mid-transition and strands
 * Leaflet at the old view — so the toolbar's labels would change while the map never moved. A jump
 * is honest; a silent no-op is not.
 *
 * <p>Separate from `FitBoundsController` rather than a flag on it: that one answers a Plan-tab
 * handoff, animates deliberately, and caps the zoom at 12 so a one-location region does not slam to
 * street level. This one answers a framing control, where an animation is the defect and a cap
 * would stop "My area" actually showing your area.
 *
 * <p>`padding` defaults to the area/whole-catalogue segment's own `[28, 28]` — unchanged from
 * before this prop existed. The Regions jump list (map-tab-v2-plan.md §3 P11) passes `[40, 40]`
 * instead, matching the design bundle's own `jumpTo` (`docs/design/map-tab-v2/map-tab-v2.js`), which
 * pads a region's own, generally tighter box more generously than the wider area/catalogue box needs.
 */
function HeatBoundsController({ bounds, nonce, padding = [28, 28] }) {
  const map = useMap();
  /**
   * The framing already applied, as a value rather than a "have I run yet" boolean.
   *
   * <p>Two things follow, and both were review findings. A `useRef(false)` armed flag is NOT
   * StrictMode-safe — dev double-invokes the effect and the ref survives, so the second run fires
   * the very camera move the guard exists to skip. And keying on the nonce alone made the opening
   * framing depend on a race: `heat.areaBounds` is null until the briefing and the reach matrix have
   * both landed, so a reader who opened the Map tab first got the whole-of-Britain box for the rest
   * of the session, with the only correction hidden behind a segment that is itself absent without a
   * home. Comparing the applied VALUE fixes both: the first run records what `MapContainer` already
   * opened on, and any later change — the area arriving, or a segment press bumping the nonce —
   * re-frames exactly once.
   */
  const applied = useRef(null);
  const key = `${nonce}|${JSON.stringify(bounds)}`;
  useEffect(() => {
    if (applied.current === null) {
      applied.current = key;
      return;
    }
    if (applied.current === key) return;
    applied.current = key;
    if (!bounds || typeof map?.fitBounds !== 'function') return;
    map.fitBounds(bounds, { padding, animate: false });
    // Keyed on the composed value, not on the bounds ARRAY identity, which is rebuilt on every poll.
    // `padding` is deliberately NOT a dependency either: it always changes in lockstep with `bounds`
    // (both are derived from the SAME `jumpFitOverride` presence one level up), so the closure read
    // here is never stale at the moment `key` actually changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return null;
}

HeatBoundsController.propTypes = {
  bounds: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
  nonce: PropTypes.number,
  padding: PropTypes.arrayOf(PropTypes.number),
};

/**
 * A spot's identity for the field's own filtering, id-first with a name fallback.
 *
 * <p>The same precedence `heatSpots.buildHeatSpots` joins on, for the same reason: ids are stable
 * and names are what exists when they are not. The sigils keep the two spaces apart — without them
 * a location whose id is 5 and one whose name is "5" are the same key.
 */
function heatSpotKey(spot) {
  return spot.id != null ? `#${spot.id}` : `@${spot.name}`;
}

/**
 * One empty point array, shared.
 *
 * <p>A fresh `[]` per render would change `MapHeatLayer`'s `points` identity, which is a dependency
 * of its paint callback — so an unscored window would repaint the same nothing on every render.
 */
const EMPTY_POINTS = [];

/**
 * One empty date array, shared — the same reasoning as {@link EMPTY_POINTS} one level up, for the
 * `forecastDates` prop's own default (PR #731 review).
 *
 * <p>`forecastDates = []` as an ordinary destructuring default builds a FRESH array literal on
 * every call — i.e. every render — which is harmless where `forecastDates` is only ever read
 * inline, but became a genuine infinite loop the moment `horizonDates`/`bounded*AvailableDates`
 * below started depending on it inside a `useMemo`: a new array reference every render forces
 * those memos to recompute every render, which produces new array references for the multi-date
 * fetch effects' OWN dependency arrays, which re-fires `Promise.all(...).then(setState(...))` on
 * every render, whose `setState` triggers the next render — forever. A caller that never passes
 * `forecastDates` at all (most of this file's own test fixtures) hit this every time; production's
 * one caller (`WindowFirstMapPane.jsx`) already forwards a `useMemo`'d `allDates`, so it likely
 * never surfaced there — but the fix belongs at the default, not at the one caller that happens to
 * dodge it.
 */
const EMPTY_DATES = [];

// Override Leaflet popup width + scrolling.
// Max-height must be less than the map container height (500px) so the popup
// scrolls internally rather than being clipped by the container's overflow:hidden.
const popupStyles = `
  .leaflet-popup-content-wrapper {
    width: calc(100vw - 40px) !important;
    max-width: 600px !important;
    max-height: 380px !important;
    overflow-y: auto !important;
    overflow-x: hidden !important;
    background: var(--color-plex-surface) !important;
    color: var(--color-plex-text) !important;
    border: 1px solid var(--color-plex-border-light) !important;
    border-radius: 8px !important;
    box-shadow: 0 18px 50px rgba(0, 0, 0, 0.6) !important;
  }
  .leaflet-popup-content {
    overflow-y: visible !important;
    overflow-x: hidden !important;
  }
  .leaflet-popup-tip {
    background: var(--color-plex-surface) !important;
    border: 1px solid var(--color-plex-border-light) !important;
    box-shadow: none !important;
  }
  .leaflet-popup-close-button {
    color: var(--color-plex-text-muted) !important;
  }
  .leaflet-popup-close-button:hover {
    color: var(--color-plex-text) !important;
  }
  /* Map markers — names are revealed on hover/focus (no permanent labels), and
     the hovered disc lifts above its neighbours so an overlapped marker pops
     fully forward rather than staying half-buried. */
  .photocast-marker:hover .marker-name-chip,
  .photocast-marker:focus-within .marker-name-chip {
    opacity: 1 !important;
  }
  .leaflet-marker-icon.photocast-marker:hover,
  .leaflet-marker-icon.photocast-marker:focus-within {
    z-index: 1000 !important;
  }
  /* Drill-down emphasis — the map overlay opens focused on ONE location, and the user
     should never have to hunt for the good pin. The target lifts and its neighbours recede.

     Deliberately expressed in the app's OWN marker language (the 1-5 rating ramp and its
     arcs), scaled and muted, rather than the flat coloured circles the design mock drew: a
     second marker vocabulary that existed only inside the overlay would make a 4-star pin
     read one way on the Map tab and another way here.

     The transform lands on the inner wrapper, never on .leaflet-marker-icon itself — Leaflet
     owns that element's transform for positioning, so scaling it would move the pin off its
     coordinates. */
  .leaflet-marker-icon.photocast-marker--focus {
    z-index: 900 !important;
  }
  .photocast-marker--focus > div {
    transform: scale(1.22);
    filter: drop-shadow(0 4px 14px rgba(0, 0, 0, 0.55));
    transition: transform 0.16s ease;
  }
  .photocast-marker--muted > div {
    opacity: 0.4;
    transition: opacity 0.16s ease;
  }
  .photocast-marker--muted:hover > div {
    opacity: 0.85;
  }
  @media (prefers-reduced-motion: reduce) {
    .photocast-marker--focus > div,
    .photocast-marker--muted > div { transition: none; }
  }
`;

/**
 * Sits inside a Leaflet Popup and directly manipulates the popup's DOM
 * to enforce a max-height with scrolling whenever deps change.
 */
/**
 * Sits inside a Leaflet Popup and directly manipulates the popup's DOM
 * to enforce a max-height with scrolling whenever deps change.
 */
function PopupResizer({ deps }) {
  const map = useMap();
  useEffect(() => {
    const id = setTimeout(() => {
      map.eachLayer((layer) => {
        const popup = layer.getPopup?.();
        if (popup?.isOpen()) {
          const wrapper = popup.getElement()?.querySelector('.leaflet-popup-content-wrapper');
          if (wrapper) {
            const maxH = Math.max(300, map.getContainer().clientHeight - 120);
            wrapper.style.setProperty('max-height', maxH + 'px', 'important');
            wrapper.style.setProperty('overflow-y', 'auto', 'important');
          }
        }
      });
    }, 20);
    return () => clearTimeout(id);
  }, deps); // eslint-disable-line react-hooks/exhaustive-deps
  return null;
}

PopupResizer.propTypes = {
  deps: PropTypes.array.isRequired,
};

import InfoTip from './InfoTip.jsx';
import { buildMarkerSvg, buildStandDownSvg, markerLabelAndColour, createClusterIcon, STAND_DOWN_COLOUR } from './markerUtils.js';
import { DARK_SKY_THRESHOLD } from '../utils/mapOverlay.js';

const SUNRISE_LINE_COLOUR = '#f97316';
const SUNSET_LINE_COLOUR  = '#a855f7';

/**
 * Zoom at which the Esri reference (place-name) layer joins the base tile — map-tab-v2-plan.md §3
 * P3, values from `docs/design/map-tab-v2/README.md`'s "The basemap" section.
 *
 * <p>Below this the layer is UNMOUNTED, not merely hidden: dropping town labels below the glance
 * scale is "the biggest single legibility win" (our own location chips carry a rating theirs
 * cannot, so the two were competing for the same pixels), and past it the chips have thinned out
 * enough that the village you are driving through becomes useful context again — a layer toggled
 * back on under our control, not baked into the tile at every scale.
 */
const REFERENCE_LAYER_MIN_ZOOM = 11.8;

/**
 * Maps Leaflet zoom level to azimuth line length in km.
 * Zoomed out (zoom 7-8) → long lines; zoomed in (zoom 13+) → short lines.
 */
const ZOOM_TO_LINE_KM = {
  7: 200, 8: 150, 9: 100, 10: 70, 11: 40, 12: 20, 13: 10, 14: 5,
};

function lineKmForZoom(zoom) {
  const keys = Object.keys(ZOOM_TO_LINE_KM).map(Number).sort((a, b) => a - b);
  if (zoom <= keys[0]) return ZOOM_TO_LINE_KM[keys[0]];
  if (zoom >= keys[keys.length - 1]) return ZOOM_TO_LINE_KM[keys[keys.length - 1]];

  // Interpolate between two zoom levels
  const z = Math.floor(zoom);
  const nextZ = z + 1;
  if (!ZOOM_TO_LINE_KM[z] || !ZOOM_TO_LINE_KM[nextZ]) return 50;

  const frac = zoom - z;
  return ZOOM_TO_LINE_KM[z] + (ZOOM_TO_LINE_KM[nextZ] - ZOOM_TO_LINE_KM[z]) * frac;
}

/**
 * Invisible component that tracks map zoom and calls onZoom when it changes — including the
 * INITIAL zoom at mount, not only the zoom after the first `zoomend`.
 *
 * <p>⚠️ PR #728 review (Codex, confirmed after our own review wrongly refuted the same charge):
 * without the mount effect below, `onZoom`'s consumer state seeds at whatever `useState` default
 * the caller chose (`MapView`'s `zoom` starts at `9`) and stays there until the reader's first
 * zoom gesture fires a real `zoomend`. React-leaflet applies the construction-time `bounds` fit
 * BEFORE children render, and this component's `zoomend` listener attaches AFTER — so a map whose
 * initial fit lands somewhere else entirely never corrects the guess until the user zooms. This is
 * reachable on the Plan overlay in particular: its construction `bounds` can fit tight on one
 * focused location, easily landing past 11.8. And it is wrong regardless of reachability, since
 * the seed is a guess where the truth (`map.getZoom()`) is one call away.
 *
 * <p>The reference-layer gate (map-tab-v2-plan.md §3 P3) was the first consumer for which this
 * guess was VISIBLE — a whole extra tile layer either mounted or not — but every other reader of
 * the same `zoom` state had the identical gap, silently: the azimuth-length interpolation
 * (`lineKmForZoom`) drew wrong-length lines for the same pre-first-zoom window, and any later
 * phase's own zoom threshold would have inherited it too. This fixes the state at its one source
 * rather than patching each consumer.
 *
 * <p>`useMapEvents` returns the map instance (the same contract as `useMap()`), so no second hook
 * is needed to read the truth once, on mount.
 */
function ZoomTracker({ onZoom }) {
  const map = useMapEvents({ zoomend: (e) => onZoom(e.target.getZoom()) });
  useEffect(() => {
    // Guarded the same way `MapSizeSync` guards `invalidateSize` above — several test harnesses
    // across this file stub `useMapEvents`/`useMap` returning `null` (they exercise a `zoomend`
    // handler directly rather than a real map instance), and calling `getZoom` on that would throw
    // on every one of them. `onZoom` is `setZoom` from `useState`, stable across renders, so this
    // fires once per real `map` instance (once per MapView mount) rather than on every render.
    if (typeof map?.getZoom !== 'function') return;
    onZoom(map.getZoom());
  }, [map, onZoom]);
  return null;
}

ZoomTracker.propTypes = {
  onZoom: PropTypes.func.isRequired,
};

/**
 * Moves Leaflet's OWN zoom control to a different corner after mount — the design's `+ − ⌂`
 * bottom-right (map-tab-v2-plan.md §3 P7's chrome enumeration), stacked above the (also
 * bottom-right) `CentreOnHomeControl`.
 *
 * <p>Imperative repositioning rather than react-leaflet's documented `zoomControl={false}` +
 * `<ZoomControl position="bottomright" />` swap — deliberately, because that swap changes the
 * COMPONENT the zoom control renders through, and every one of the eighteen test files in this
 * suite that mocks `react-leaflet` mocks it down to the handful of exports each one actually
 * uses; none exports `ZoomControl`. Adding it to the JSX tree would have broken all eighteen for
 * a corner that changes on one mount. `map.zoomControl` is the real Leaflet `Map`'s own reference
 * to the control its `zoomControl: true` construction option already created (`L.Control.Zoom`'s
 * init hook sets it), and `.setPosition` is Leaflet's own public API for moving an existing
 * control between corners — so this needs no new import from `react-leaflet` and touches no mock.
 *
 * <p>Guarded with optional chaining throughout: the JSDOM test harness's `useMap()` stubs return a
 * plain object with only the methods each file happens to need, so `map.zoomControl` is usually
 * `undefined` there — exactly like `CentreOnHomeControl`'s own guard on `L.Control` one component
 * up, and for the identical reason.
 */
function ZoomControlPositioner({ position }) {
  const map = useMap();
  useEffect(() => {
    map?.zoomControl?.setPosition?.(position);
  }, [map, position]);
  return null;
}

ZoomControlPositioner.propTypes = {
  position: PropTypes.string.isRequired,
};

/**
 * Invisible component that reports the map's viewport as `[south, west, north, east]`.
 *
 * <p>Mounted only where a count is drawn from it. The overlay's context bar says "N pins in view",
 * and "in view" has to mean the viewport rather than the filtered set — otherwise panning away
 * from the pins leaves a number describing something the reader is no longer looking at, which is
 * the one failure a read-only receipt line may not have.
 */
function BoundsTracker({ onBounds }) {
  const map = useMap();
  // The initial read, before any pan or zoom has fired. Guarded because the test harness stubs
  // `useMap` with the two methods it needs and no more.
  useEffect(() => {
    const bounds = map?.getBounds?.();
    if (bounds) onBounds(bounds);
  }, [map, onBounds]);
  useMapEvents({
    moveend: (e) => onBounds(e.target.getBounds()),
    zoomend: (e) => onBounds(e.target.getBounds()),
  });
  return null;
}

BoundsTracker.propTypes = {
  onBounds: PropTypes.func.isRequired,
};

/**
 * Closes whichever of the map tab's own overlay popovers is open when the reader clicks empty map
 * — map-tab-v2-plan.md §3 P7 / README "Interactions & behaviour": "Click map background → Close
 * menus". Leaflet's marker click handlers stop propagation before it reaches the map's own `click`
 * event (`L.Marker` sets `bubblingMouseEvents: false`), so this never fires for a marker tap —
 * only genuine empty-map ground.
 *
 * <p>⚠️ map-tab-v2-plan.md §3 P9's ordering rule ("popover, then callout — never both on one
 * press") needs a SECOND event, `mousedown`, and it is not optional. `WindowControl`/
 * `FiltersPopover` each close THEIR OWN menu via a `document`-level `mousedown` listener
 * (`onDocMouseDown`), entirely independent of this controller. On a real click that listener fires
 * — and commits its `setOpen(false)`/`onOpenChange(false)` — BEFORE the native `click` event that
 * follows it reaches this controller's own handler (browser event order: `mousedown` → `mouseup` →
 * `click`, and React's automatic batching flushes the `mousedown`-triggered update in between): by
 * the time `onBackgroundClick` ran, `openMapMenu` had ALREADY gone null, so the ordering collapsed
 * to "close everything on one click" — a live regression, caught in the browser (not by any unit
 * test, since every one of them invoked the captured `click` handler manually, never alongside a
 * real `mousedown`). `mousedown` fires on THIS controller too, via Leaflet's own map event of that
 * name, and — because `.leaflet-container` is an ancestor of `document` — reaches it BEFORE the
 * document-level listener does, so `onMouseDown` snapshots `openMapMenu`'s value into a ref at the
 * one moment it is still trustworthy. The actual close still happens on `click`, never `mousedown`
 * itself, because `click` is Leaflet's OWN pan-vs-tap distinction (a `mousedown` that turns into a
 * drag never fires `click`) — reacting on `mousedown` directly would close the callout at the START
 * of every pan gesture.
 */
function MapBackgroundClickController({ onMouseDown, onBackgroundClick }) {
  useMapEvents({
    mousedown: () => onMouseDown(),
    click: () => onBackgroundClick(),
  });
  return null;
}

MapBackgroundClickController.propTypes = {
  onMouseDown: PropTypes.func.isRequired,
  onBackgroundClick: PropTypes.func.isRequired,
};

/**
 * Keeps Leaflet's idea of its own size in step with a container whose height is animating.
 *
 * <p>The overlay's map grows and shrinks as the filter drawer opens and closes. Leaflet caches the
 * container size and only recomputes it on `invalidateSize`, so without this the tiles keep the
 * old geometry: grey bands at the bottom on a grow, and a centre that has silently moved on a
 * shrink. Polled across the transition rather than fired once at the end, because a single call at
 * `transitionend` leaves the map wrong for the whole 240ms the user is watching it move.
 *
 * <p><b>The Map pane needs the same thing for a different reason.</b> Its panel is hidden with
 * `display: none` rather than unmounted, so a viewport change while the reader is on another tab
 * — a phone rotating, most obviously — leaves Leaflet holding a size for a container that no
 * longer has it, and the map paints grey on return. That pane bumps `resizeNonce` from a
 * `ResizeObserver`; both of `MapView`'s remaining mounts (the pane, the overlay) always ask for
 * this, so the sync runs unconditionally.
 */
function MapSizeSync({ trigger }) {
  const map = useMap();
  useEffect(() => {
    if (typeof map?.invalidateSize !== 'function') return undefined;
    const tick = setInterval(() => map.invalidateSize({ animate: false }), 60);
    const stop = setTimeout(() => clearInterval(tick), 340);
    return () => { clearInterval(tick); clearTimeout(stop); };
  }, [map, trigger]);
  return null;
}

MapSizeSync.propTypes = {
  trigger: PropTypes.any,
};

/**
 * Programmatically flies the map to a target lat/lon when `target` changes.
 */
function FlyToController({ target }) {
  const map = useMap();
  useEffect(() => {
    if (target) {
      map.flyTo([target.lat, target.lon], Math.max(map.getZoom(), 11));
    }
  }, [target, map]);
  return null;
}

FlyToController.propTypes = {
  target: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
};

/**
 * Fits the map to a region's locations when handed off from a Plan-tab bet card.
 * The {@code key} (a monotonic nonce) lets the same region re-fit on repeat taps.
 */
function FitBoundsController({ target }) {
  const map = useMap();
  useEffect(() => {
    if (target?.points?.length) {
      map.fitBounds(L.latLngBounds(target.points), { padding: [60, 60], maxZoom: 12, animate: true });
    }
    // Only re-fit when the handoff key changes, not on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target?.key]);
  return null;
}

FitBoundsController.propTypes = {
  target: PropTypes.shape({
    points: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
    key: PropTypes.string,
  }),
};

/**
 * Opens the desktop Leaflet popup for a handed-off location once the fly-to
 * animation settles (so the marker has declustered). Re-fires whenever the
 * {@code nonce} changes, allowing the same location to be re-selected.
 */
/**
 * ⚠️ map-tab-v2-plan.md §3 P9: the TAB branch no longer opens a Leaflet popup, because the tab no
 * longer binds one to any marker (`MapCallout` reads {@code selectedLocationName} reactively and
 * needs no imperative nudge — its own anchoring effect handles a marker that has not yet finished
 * flying into place). The overlay branch is BYTE-IDENTICAL to before this phase: it still opens the
 * marker's own bound `Popup`.
 */
function HandoffPopupController({
  locationName, nonce, markerRefs, overlayMode,
}) {
  const map = useMap();
  useEffect(() => {
    if (!overlayMode || !locationName || typeof map.once !== 'function') return undefined;
    const open = () => {
      const marker = markerRefs.current.get(locationName);
      if (marker && typeof marker.openPopup === 'function') marker.openPopup();
    };
    // Open after the fly animation ends; a timeout covers the case where the
    // map is already centred on the target and no move event fires.
    map.once('moveend', open);
    const timer = setTimeout(open, 700);
    return () => {
      map.off('moveend', open);
      clearTimeout(timer);
    };
  }, [overlayMode, locationName, nonce, map, markerRefs]);
  return null;
}

HandoffPopupController.propTypes = {
  locationName: PropTypes.string,
  nonce: PropTypes.number,
  markerRefs: PropTypes.shape({ current: PropTypes.instanceOf(Map) }).isRequired,
  overlayMode: PropTypes.bool.isRequired,
};

/**
 * Calculates a destination lat/lon given a start point, bearing and distance.
 * Uses the spherical law of cosines (accurate enough for distances under 500km).
 *
 * @param {number} lat - Start latitude in decimal degrees.
 * @param {number} lon - Start longitude in decimal degrees.
 * @param {number} bearingDeg - Bearing in degrees clockwise from North.
 * @param {number} distanceKm - Distance in kilometres.
 * @returns {[number, number]} [lat, lon] of the destination point.
 */
function destinationPoint(lat, lon, bearingDeg, distanceKm) {
  const R = 6371;
  const d = distanceKm / R;
  const bearing = (bearingDeg * Math.PI) / 180;
  const lat1 = (lat * Math.PI) / 180;
  const lon1 = (lon * Math.PI) / 180;

  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(d) +
    Math.cos(lat1) * Math.sin(d) * Math.cos(bearing),
  );
  const lon2 =
    lon1 +
    Math.atan2(
      Math.sin(bearing) * Math.sin(d) * Math.cos(lat1),
      Math.cos(d) - Math.sin(lat1) * Math.sin(lat2),
    );

  return [lat2 * (180 / Math.PI), lon2 * (180 / Math.PI)];
}

/**
 * Creates a custom Leaflet DivIcon for a location marker with radial progress arcs.
 *
 * @param {number|null} rating - Star rating 1–5, or null.
 * @param {number|null} fierySky - Fiery sky score 0–100, or null.
 * @param {number|null} goldenHour - Golden hour score 0–100, or null.
 * @param {string} locationName - Display name shown beneath the marker.
 * @param {boolean} [isPureWildlife=false] - If true, renders a green wildlife marker.
 * @param {boolean} [excludeFromCluster=false] - If true, scores are excluded from cluster averages (e.g. WATERFALL).
 * @param {boolean} [isStandDown=false] - If true, renders a muted stand-down marker (triaged forecast).
 * @returns {L.DivIcon}
 */
// Cache of built DivIcons keyed by the content that determines the icon (name + scores + variant
// flags). Returning a stable instance for unchanged markers lets react-leaflet skip setIcon (and
// the SVG/DOM rebuild) across re-renders — a zoom, hover or filter change no longer rebuilds every
// marker's icon. A DivIcon is safely shareable: Leaflet's createIcon() builds fresh DOM from
// options.html on each use, and the per-marker options embedded here (rating/scores/exclude flag,
// read by createClusterIcon) are part of the cache key, so identical-content markers share safely.
const markerIconCache = new Map();

/** Soft cap so a long-lived tab whose scores change daily can't grow the icon cache without bound. */
const MARKER_ICON_CACHE_LIMIT = 2000;

function makeMarkerIcon(rating, fierySky, goldenHour, locationName, isPureWildlife = false, excludeFromCluster = false, isStandDown = false, emphasis = null) {
  // `emphasis` is part of the key: a DivIcon is cached by everything that determines it, and
  // the className carries the focus/muted modifier. Omitting it would serve the Map tab's
  // plain icon to the overlay (or worse, leak the overlay's muted icon back to the Map tab).
  //
  // `getMode()` joined the key in Stage 6 (heat-scale-unification-plan.md), the first time
  // anything in the running app actually calls `scoreRamp.setMode('temp')`. Before that, "same
  // name+scores+flags → same colour" held because the active ramp never varied for the life of
  // the tab, so the cache silently depended on a mode that was always constant. Now that a user
  // can switch it live, a stale-mode entry would otherwise survive under an unchanged key and
  // keep painting the OLD scale until a hard reload — the one thing this preference must not do.
  // Read live rather than threaded in as a parameter: `rampHex`/`rampRgb` (inside
  // `markerLabelAndColour` below) already read this exact module state to choose the colour, so
  // calling it again here for the key can never disagree with what actually got painted — a
  // parameter sourced from a caller's own prop could, if that prop ever lagged the real mode.
  const cacheKey = `${locationName}|${rating}|${fierySky}|${goldenHour}|${isPureWildlife ? 1 : 0}|${excludeFromCluster ? 1 : 0}|${isStandDown ? 1 : 0}|${emphasis ?? '-'}|${getMode()}`;
  const cached = markerIconCache.get(cacheKey);
  if (cached) return cached;

  const { label, colour } = markerLabelAndColour(rating, fierySky, goldenHour, isPureWildlife);

  const svg = isStandDown
    ? buildStandDownSvg()
    : buildMarkerSvg(label, colour, fierySky, goldenHour, rating, isPureWildlife);
  // Name is no longer a permanent label (they collide into text-soup in dense
  // corridors). It's an absolutely-positioned chip, hidden by default and
  // revealed on hover/focus via the `.photocast-marker` CSS in popupStyles.
  const html = `
    <div style="position:relative;width:44px;height:44px;display:flex;align-items:center;justify-content:center;">
      ${svg}
      <div class="marker-name-chip" style="
        position:absolute;top:calc(100% + 5px);left:50%;transform:translateX(-50%);
        background:rgba(13,10,8,0.92);
        color:var(--color-plex-text, #F2E7D3);
        font-size:10px;font-weight:600;
        padding:3px 7px;border-radius:3px;
        white-space:nowrap;
        box-shadow:0 2px 8px rgba(0,0,0,0.5);
        border:1px solid rgba(255,255,255,0.1);
        opacity:0;pointer-events:none;transition:opacity 0.12s ease;
      " title="${locationName}">${locationName}</div>
    </div>
  `;

  const icon = L.divIcon({
    html,
    // The emphasis modifier has to land on the class, not just the cache key — without it the
    // `.photocast-marker--focus` / `--muted` rules in popupStyles match nothing and the overlay's
    // drill-down highlight is inert.
    className: emphasis ? `photocast-marker photocast-marker--${emphasis}` : 'photocast-marker',
    iconSize: [44, 44],
    iconAnchor: [22, 22],
    rating: rating,
    fierySky: fierySky,
    goldenHour: goldenHour,
    excludeFromCluster: excludeFromCluster,
    popupAnchor: [0, -24],
  });

  if (markerIconCache.size >= MARKER_ICON_CACHE_LIMIT) markerIconCache.clear();
  markerIconCache.set(cacheKey, icon);
  return icon;
}

/**
 * Map view showing all locations as score markers for a given date.
 * Selecting a marker draws orange (sunrise) and purple (sunset) azimuth lines.
 *
 * @param {object} props
 * @param {Array<{name: string, lat: number, lon: number, forecastsByDate: Map}>} props.locations
 * @param {string|null} props.date - The target date (YYYY-MM-DD) to display ratings for.
 */
/**
 * Subject filter chips, in display order. BLUEBELL is excluded here because it gets its own
 * season-gated chip below — see DISPLAY_TYPES for why that distinction is deliberate.
 */
const MAP_FILTER_CHIPS = DISPLAY_TYPES.map((type) => [type, LOCATION_TYPE_META[type]]);

/**
 * Determines whether the next solar event is sunrise or sunset based on the
 * current time relative to today's sunrise. For future dates, defaults to SUNSET.
 *
 * @param {Array} locations - Location data with forecastsByDate maps.
 * @param {string} date - The selected date (YYYY-MM-DD).
 * @returns {string} 'SUNRISE' or 'SUNSET'.
 */
function getNextEventType(locations, date) {
  const now = new Date();
  // UK calendar — `date` is a backend date keyed to Europe/London, so judging it on the browser's
  // zone made a reader outside the UK take the future-date branch on the actual current day.
  const todayStr = ukDateStr(now);
  if (date !== todayStr) return 'SUNSET';

  for (const loc of locations) {
    if ((loc.locationType ?? []).every((t) => t === 'WILDLIFE')) continue;
    const dayData = loc.forecastsByDate.get(date);
    const sunriseTime = dayData?.sunrise?.solarEventTime;
    if (sunriseTime) {
      return new Date(sunriseTime + 'Z') > now ? 'SUNRISE' : 'SUNSET';
    }
  }
  return 'SUNSET';
}

const ALERT_WORTHY_LEVELS = new Set(['MODERATE', 'STRONG']);

/** Width of the drawer's mono label column in compact (overlay) mode. */
const COMPACT_LABEL_WIDTH = '78px';

/**
 * "⌂" — a Leaflet control, because it is a map action.
 *
 * <p>Bottom-right (map-tab-v2-plan.md §3 P7's chrome enumeration: "zoom + ⌂ bottom-right"),
 * directly above the zoom box in that same corner stack, in its OWN control container rather than
 * as a third button in the zoom bar: this is not a zoom step, and putting it in that bar would make
 * it read as one. Leaflet's corner container handles the stacking and the gap, so nothing here
 * hardcodes an offset. This component is mounted tab-only already (see its call site) — the
 * Plan-tab overlay keeps Leaflet's own top-left corner untouched, so there is no position to
 * parameterise here.
 *
 * <h2>Reconciled with map-tab-v2-plan.md §3 P11's own line: "resets scope to My area and refits"</h2>
 *
 * <p>Before P11 this button flew to the home COORDINATE at a zoom derived from the reader's
 * Close-to-home radius (`homeRadiusMiles`) — a narrower, single-purpose gesture from before the
 * Filters popover's "My area / Whole catalogue" scope segment (P7) existed at all. The design
 * bundle's own `zhome` never centred on a point: it reset {@code S.area} and refit to the scoped
 * spot set's bounds, and P11 states the same for this control in as many words. The two are not
 * additive — refitting to a fixed-radius disc around the coordinate AND to the (generally larger,
 * differently-shaped) planning-area bounds in one click would leave the SECOND fit as the only one
 * the reader ever sees, making the radius-framing dead weight — so the old behaviour is retired
 * rather than layered under the new one. What survives is the button's OTHER identity, which this
 * phase treats as the more general form of "take me home": resetting to My area already centres
 * near home, because My area is itself drive-time-scoped from it. The old {@code zoomForHomeRadius}
 * maths (and the {@code homeRadiusMiles} prop that fed it) are retired with it — nothing else in
 * this component read either.
 *
 * <p><b>Only when a home coordinate exists.</b> With no postcode, {@code heatArea} already reads
 * the same box either way (`FiltersPopover`'s own scope row is withheld entirely for the identical
 * reason: "a control whose every press does nothing is banned outright"), so a reset would be a
 * genuine no-op — the pre-P11 "open Settings on the postcode field" fallback stays exactly as it
 * was, because THAT still does something.
 *
 * <p>The refit goes through the SAME `onResetScope` path `FiltersPopover`'s own "My area" segment
 * button uses (`MapView`'s `resetToMyArea`), so the two controls can never disagree about what
 * resetting scope means, and both inherit {@code animate: false} from the identical reasoning: a
 * heavy field repaint in the same frame as an animated `fitBounds` strands Leaflet at the old view
 * (`HeatBoundsController`'s own class doc; map-tab-v2-plan.md §3 P7's recorded Leaflet-strand trap).
 *
 * <p>Home coordinates are NOT geocoded here. The postcode was resolved once, on the server, when
 * the user saved it — `GET /api/user/settings` returns the stored lat/lon.
 *
 * <p>With no postcode saved the button stays visible and disabled rather than disappearing: a
 * control that is absent explains nothing, and this one's whole job when unset is to say where
 * the missing setting lives. Clicking it opens Settings on the postcode field.
 *
 * @param {Object}    props
 * @param {?Object}   props.homeCoords  `{ lat, lon }`, or null when no postcode is saved
 * @param {?Function} props.onResetScope resets scope to My area and refits (animate:false) — only
 *        ever called while {@code homeCoords} is set
 * @param {?Function} props.onOpenSettings opens the settings dialog focused on the postcode field
 */
function CentreOnHomeControl({ homeCoords = null, onResetScope = null, onOpenSettings = null }) {
  const map = useMap();
  // The container is made once, in a state initialiser rather than in the effect, so it exists on
  // the first render and the portal has somewhere to go without a second render pass. Leaflet's
  // `Control.addTo` adds `leaflet-control` itself, which is what earns the corner spacing.
  const [container] = useState(() => {
    if (typeof document === 'undefined') return null;
    const el = document.createElement('div');
    el.className = 'leaflet-bar map-home-control';
    return el;
  });

  useEffect(() => {
    // Guarded rather than assumed: the JSDOM test harness stubs `leaflet` down to the two icon
    // factories. Unattached, the portal's contents simply never reach the page.
    if (!container || !map?.addControl || !L?.Control || !L?.DomEvent) return undefined;
    // Leaflet listens natively on the map container, so a React `stopPropagation` would fire too
    // late — a click would recentre AND start a map drag.
    L.DomEvent.disableClickPropagation(container);
    L.DomEvent.disableScrollPropagation(container);
    const control = new L.Control({ position: 'bottomright' });
    control.onAdd = () => container;
    control.addTo(map);
    return () => control.remove();
  }, [container, map]);

  if (!container) return null;

  const hasHome = homeCoords?.lat != null && homeCoords?.lon != null;
  return createPortal(
    <button
      type="button"
      data-testid="centre-on-home"
      // ⚠️ State-truthful, mirroring `title` exactly — the same accname-drift bug class this phase
      // fixed on `MastheadTickLine`'s own origin control (adversarial review finding, live browser
      // pass): the name must describe what a click NOW does (reset scope to My area and refit, or
      // open Settings), not what the pre-P11 `flyTo` used to do. A stale "Centre on home" here would
      // have been pinned by a green test forever, exactly like the masthead's own near-miss.
      aria-label={hasHome ? 'Reset to My area' : 'Set your home postcode in Settings'}
      title={hasHome ? 'Reset to My area' : 'Set your home postcode in Settings'}
      onClick={() => {
        if (!hasHome) {
          onOpenSettings?.();
          return;
        }
        onResetScope?.();
      }}
      data-disabled={hasHome ? undefined : 'true'}
    >
      <span aria-hidden="true">⌂</span>
    </button>,
    container,
  );
}

CentreOnHomeControl.propTypes = {
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  onResetScope: PropTypes.func,
  onOpenSettings: PropTypes.func,
};

/**
 * One labelled group of the filter drawer, in the two shapes the drawer has.
 *
 * <p>Stacked (Map tab) is the layout the rail has always had: a heading, then its controls beneath.
 * Compact (overlay) turns the heading ninety degrees into a fixed mono column on the left, which
 * both makes the drawer scannable — four labels in a straight line — and, more to the point,
 * shorter: every row it saves is map the modal gets back.
 *
 * <p>Two layouts, ONE set of controls. The children are the same elements with the same handlers
 * in both, because a second copy of the quality segment or the subject chips is a second place for
 * "what does this filter actually do" to drift.
 *
 * @param {Object}       props
 * @param {string}       props.label        heading text in the stacked layout
 * @param {string}       props.compactLabel heading text in the compact layout (shorter)
 * @param {boolean}      [props.compact]    use the compact layout
 * @param {?string}      [props.hint]       a mono aside to the right of the controls (compact only)
 * @param {?React.Node}  [props.info]       an InfoTip beside the heading (stacked only)
 * @param {React.Node}   props.children     the controls
 */
function FilterRow({ label, compactLabel, compact = false, hint = null, info = null, children }) {
  if (!compact) {
    return (
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center gap-1.5">
          <span className="text-[11px] uppercase tracking-wide text-plex-text-muted font-semibold">
            {label}
          </span>
          {info}
        </div>
        {children}
      </div>
    );
  }
  return (
    <div className="flex items-start flex-wrap" style={{ gap: '8px' }}>
      <span
        className="font-mono uppercase shrink-0 text-plex-text-muted"
        style={{
          width: COMPACT_LABEL_WIDTH,
          fontSize: '9.5px',
          fontWeight: 600,
          letterSpacing: '0.1em',
          paddingTop: '7px',
        }}
      >
        {compactLabel}
      </span>
      <div className="flex flex-col gap-1.5" style={{ flex: '1 1 260px' }}>{children}</div>
      {hint && (
        <span
          className="font-mono text-plex-text-muted self-center"
          style={{ fontSize: '10.5px' }}
        >
          {hint}
        </span>
      )}
    </div>
  );
}

FilterRow.propTypes = {
  label: PropTypes.string.isRequired,
  compactLabel: PropTypes.string.isRequired,
  compact: PropTypes.bool,
  hint: PropTypes.string,
  info: PropTypes.node,
  children: PropTypes.node,
};

/**
 * The overlay's own map heights, and the easing its drawer and its map share.
 *
 * <p>The overlay lives in a modal whose whole height is spoken for, so every row of chrome above
 * the map comes straight out of it. Opening the overlay from a plan card left ~165px of visible
 * map — a strip too short to show the focused pin, its neighbours and the popup without panning —
 * because five rows of filter controls were sitting where the map should be. Folding them away
 * buys the map back. The Map TAB carries no equivalent constant (map-tab-v2-plan.md §3 P7 retired
 * the old `MAP_HEIGHT_PX`) — its map container is `flex:1; min-height:0` inside a frame the shell
 * already sizes to the viewport.
 */
const OVERLAY_MAP_HEIGHT_PX = 470;
const OVERLAY_MAP_HEIGHT_FILTERS_OPEN_PX = 300;
const DRAWER_EASING = 'cubic-bezier(0.2, 0.7, 0.2, 1)';

/**
 * The selection callout's five new props (map-tab-v2-plan.md §3 P9), tab-only in practice — the
 * overlay never selects a location this way, so these are simply unused on that mount rather than
 * behind a second `overlayMode` branch:
 *
 * - `scoreIndex`/`scoresKnown` — from `utils/locationSheet.buildScoreIndex` over the SAME
 *   `scoreRows` `LocationFourDaySheet` already reads (`WindowFirstBriefingContext`), so the
 *   callout's reason prose and its "every window" strip can never disagree with the sheet one step
 *   away in the Plan tab about what this location was rated.
 * - `regionGlossIndex` — from `utils/mapCallout.buildRegionGlossIndex`, the reason prose's fallback
 *   when this location's own window carries no served summary.
 * - `reachById` — the per-user HOME reach map (`{driveMinutes, distanceMiles}`), read ONLY for the
 *   callout's straight-line miles fact. Deliberately separate from `heat.driveOverrideById` (the
 *   AWAY origin's drive minutes, already plumbed): an away origin's map has no `distanceMiles` at
 *   all (`utils/planOrigin.js`'s own rule — those miles are measured from home, and printing them
 *   under an away drive would put two journeys on one line), so this prop is never consulted once
 *   `driveOverride` is set.
 * - `onOpenLocationInPlan` — the real shell handoff (`App.jsx`'s `openLocationInPlan`, mirroring
 *   `openFullMapTab`'s shape in reverse): switches to the Plan tab via `WindowFirstShell`'s
 *   `selectTab` and opens this location's `LocationFourDaySheet` as the only dialog layer.
 */
function MapView({ locations, date, onSelectDate = null, forecastDates = EMPTY_DATES, autoEventType, handoffEventType, handoffFilterAction, handoffDarkSky = null, handoffLocationName = null, handoffRegion = null, handoffNonce = null, briefingScores = new Map(), onForecastRun, seasonalFeatures = [], focus = null, emphasiseLocationName = null, overlayMode = false, homeCoords = null, onOpenSettings = null, resizeNonce = null, heat = null, mapColourScale = null, colourScaleDefaulted = false, scoreIndex = null, scoresKnown = false, regionGlossIndex = null, regionBestIndex = null, reachById = null, onOpenLocationInPlan = null }) {
  // `MapView` is `React.memo`'d, and its two long-lived mounts (the Map pane, the standalone
  // overlay) sit hidden rather than unmounted when the reader looks away — so a mode switch made
  // in Settings while this instance is already alive would otherwise never reach it: nothing else
  // in its normal prop set changes when only the colour preference does. `mapColourScale` exists
  // for exactly that: a caller that re-resolves the setting (`App.jsx`'s `loadHomeCoords`) hands
  // down a genuinely new value, which is what breaks `React.memo`'s shallow prop compare and lets
  // this render run at all. Its own VALUE is deliberately never consulted below — every actual
  // colour read (`rampHex`, and `getMode()` in `makeMarkerIcon`'s cache key) goes straight to
  // `scoreRamp`'s live module state, the one place that can never disagree with what gets painted.
  // A prop-derived value used for that instead could lag the real mode by a render.
  // Also forwarded to `MapHeatLayer` as its paint-dependency key — the heat BITMAP is memoised
  // on data that does not change when only the preference does, so without it the canvas keeps
  // the old ramp while the markers around it repaint. Neither use reads the value for colour:
  // every colour read goes to `scoreRamp`'s live module state.
  void mapColourScale;
  const { role } = useAuth();
  const isMobile = useIsMobile();
  const [userHasOverriddenEvent, setUserHasOverriddenEvent] = useState(false);
  const [eventType, setEventType] = useState(() => getNextEventType(locations, date));
  /**
   * The EV-ownership forwarding rule's local half (map-tab-v2-plan.md §3 P6). A night (astro or
   * aurora) EV row whose date is not in `forecastDates` cannot be forwarded through `onSelectDate`
   * — `App`'s `effectiveDate` guard would reject it outright (`App.jsx`'s `allDates.includes`
   * check) — so this pane keeps it locally instead. Cleared the moment a forwardable row (any
   * solar row, or a night row whose date IS in `forecastDates`) is picked, so a stale override
   * cannot survive past the selection that would have superseded it.
   */
  const [localNightDate, setLocalNightDate] = useState(null);
  /**
   * The date value THIS component itself just asked the parent to adopt via `onSelectDate` —
   * distinguishes "the `date` prop changed because we forwarded a row" from "the `date` prop
   * changed for some other reason" in the invalidation effect below. A plain value, not state:
   * writing it must never itself trigger a render.
   */
  const forwardedDateRef = useRef(null);
  /**
   * What the astro/aurora fetch effects and the aurora viewline gate actually mean by "the current
   * night" — `date` everywhere except while `localNightDate` is standing in for a night the parent
   * would not accept. Identical to `date` in every ordinary (solar, or in-domain night) case, so
   * every OTHER reader of `date` in this file is unaffected by this override.
   */
  const nightDate = (eventType === 'ASTRO' || eventType === 'AURORA')
    ? (localNightDate ?? date)
    : date;
  /**
   * ⚠️ Invalidates a kept-local night the moment `date` moves for any reason OTHER than this
   * component's own forwarding (adversarial review finding, BLOCKING). `localNightDate` is
   * otherwise touched in exactly one place — `selectEvRow` — but `eventType`/`date` change through
   * FOUR other paths that never cleared it: the aurora auto-jump latch (calls `onSelectDate`
   * directly), `handoffEventType`, `autoEventType`, and any external `onSelectDate` the reader
   * triggers from outside this component entirely (a Coming-up card, a Best Bet, another tab).
   * Left alone, `localNightDate ?? date` always prefers the stale override over a genuinely new
   * `date` prop, forever — a kept-local aurora night from ten minutes ago would silently keep
   * steering the astro/aurora fetch effects and the viewline gate after the reader browsed
   * somewhere else entirely.
   *
   * <p>`forwardedDateRef` is what tells the two cases apart: `selectEvRow` records the value it
   * is about to forward immediately before calling `onSelectDate`, and this effect — which fires
   * on every `date` change, including the one `selectEvRow` itself caused — only clears the
   * override when the NEW `date` does not match what was just forwarded. When it matches, the
   * marker is consumed (set back to `null`) rather than left armed, so a later EXTERNAL change to
   * that same value is not mistaken for an echo of this one.
   */
  useEffect(() => {
    if (forwardedDateRef.current !== null && forwardedDateRef.current === date) {
      forwardedDateRef.current = null;
      return;
    }
    setLocalNightDate(null);
  }, [date]);
  const [selectedLocationName, setSelectedLocationName] = useState(null);
  const [zoom, setZoom] = useState(9);
  const [activeTypeFilters, setActiveTypeFilters] = useState(new Set());
  // Minimum-quality threshold (1–5). It's a true "this and above" control, so it
  // always holds a value; persisted to localStorage as 'mapFilterMinStars', and
  // defaults to 3★+ when unset (per the filter-bar tidy).
  const DEFAULT_MIN_STARS = 3;
  const [minStars, setMinStars] = useState(() => {
    const saved = readMapFilter('mapFilterMinStars');
    const n = saved !== null ? parseInt(saved, 10) : NaN;
    return Number.isFinite(n) && n >= 1 && n <= 5 ? n : DEFAULT_MIN_STARS;
  });
  const [showUnrated, setShowUnrated] = useState(false);
  // Stand-down filter — hidden by default; power users can reveal triaged locations
  const [showStandDown, setShowStandDown] = useState(() => {
    return readMapFilter('mapFilterShowStandDown') === '1';
  });
  const [driveTimeFilter, setDriveTimeFilter] = useState(0); // 0 = All; positive = max minutes
  const [userDriveTimes, setUserDriveTimes] = useState({});
  useEffect(() => { getDriveTimes().then(setUserDriveTimes).catch(() => {}); }, []);
  /**
   * How far a location is, from wherever the caller is planning from.
   *
   * <p><b>An OVERWRITE, never a fallback.</b> When the reader's origin has moved to a region base
   * it hands this component a base-measured map, and a location missing from that map must read as
   * <em>unknown</em> — falling through to the reader's home figure would put two origins' journeys
   * on one screen, which is the one thing the shared/per-user seam exists to prevent. Absent an
   * override — the map at home, and the Plan overlay — this is byte-identical to the previous
   * expression.
   *
   * <p>⚠️ Pre-existing bug, fixed on owner request: the override branch returned
   * {@code driveOverride.get(...)} — the whole {@code {driveMinutes, distanceMiles}} entry
   * (`originReachMap`'s own shape, `utils/planOrigin.js`) — rather than its {@code driveMinutes}
   * NUMBER, under any away origin. Every arithmetic consumer misbehaved silently: the drive-time
   * filter's {@code mins <= driveTimeFilter} compared a number against an object (always `false`,
   * so the filter matched nothing once away) and every duration rendering (the callout's Drive
   * fact, the label chips' hover tooltip) would have printed `[object Object]` the moment either
   * read a number rather than re-formatting the whole entry. It predates P9 — P9's own
   * {@code distanceMilesFor} below was built to read `reachById` directly rather than reuse this
   * one, which is what kept the callout's own miles fact from ever hitting it. Still an OVERWRITE,
   * never a fallback: a location absent from the override map reads `null`, never the home figure.
   */
  const driveOverride = heat?.driveOverrideById ?? null;
  const driveMinutesFor = useCallback((locId) => (
    driveOverride
      ? (driveOverride.get(Number(locId))?.driveMinutes ?? null)
      : (userDriveTimes[String(locId)] ?? null)
  ), [driveOverride, userDriveTimes]);
  /**
   * Straight-line miles, for the selection callout's facts row ONLY (map-tab-v2-plan.md §3 P9) —
   * `driveMinutesFor` above never carries a mile figure at all (`userDriveTimes` is minutes-only,
   * `GET /api/user/settings/drive-times`), so the callout's own {@code distanceMiles} fact reads
   * this separate map instead.
   *
   * <p><b>Home origin only</b>, matching the reach-honesty rule the callout's own module comment
   * states: `driveOverride` set means the reader has moved to a region base, and
   * `originReachMap`'s own contract is that {@code distanceMiles} is ALWAYS null there — those miles
   * are measured from home, and printing them beside an away drive would put two different journeys
   * on one line (`utils/planOrigin.js`'s module comment). So this reads `null` outright once away,
   * rather than looking the location up in a map that would answer with the wrong journey's number.
   */
  const distanceMilesFor = useCallback((locId) => (
    driveOverride ? null : (reachById?.get(Number(locId))?.distanceMiles ?? null)
  ), [driveOverride, reachById]);
  // Travel-day ranges — drive the "forecast not executed (away)" popup badge.
  const [travelRanges, setTravelRanges] = useState([]);
  useEffect(() => { fetchTravelDayRanges().then(setTravelRanges).catch(() => {}); }, []);
  const [darkSkyFilter, setDarkSkyFilter] = useState(false);
  /**
   * The Map tab's two heat controls, and the nonce that re-frames the camera.
   *
   * <p>Both are local to this mount, so the Plan tab's open row and this tab cannot pull each other
   * around: plan §4.5 keeps the two tabs' questions separate — time there, space here.
   *
   * <p>`heat` is the default view in v2 (that is the feature). `heatView`'s other value is
   * `'pins'` on the TAB (map-tab-v2-plan.md §3 P10, `PinsLayer.jsx` — "the honest comparison": one
   * dot per location, no field) and stays the pre-P10 literal `'medallions'` in the OVERLAY's own
   * dead render branch below, which never actually runs (`heatOffered` is always false there) and
   * is kept byte-identical rather than renamed along with the tab (§2's shared-component blast-
   * radius rule: treat any overlay diff as a review finding). `heatArea` frames and filters to the
   * planning area, which is the state a reader arrives in — you do not open on the whole of
   * Britain.
   */
  const [heatView, setHeatView] = useState('heat');
  const [heatArea, setHeatArea] = useState(true);
  const [heatFitNonce, setHeatFitNonce] = useState(0);
  /**
   * The Regions jump list's own camera target (map-tab-v2-plan.md §3 P11) — an OVERRIDE of the
   * ordinary `heatArea`-derived bounds below, not a second `HeatBoundsController`.
   *
   * <p>Selecting a jump row fits a THIRD box `HeatBoundsController` was never built to hold: neither
   * `heat.areaBounds` nor `heat.catalogueBounds`, but one region's own. Feeding it a second,
   * independently-nonce'd controller instance risked a genuine race — jumping outside "My area" ALSO
   * flips `heatArea`, which changes the ORDINARY `heatBounds` value below and would re-arm THAT
   * controller in the very same commit, so two `fitBounds` calls would compete over one frame with
   * nothing but React's internal effect order deciding which one the reader actually sees. An
   * override sidesteps the race outright: the one controller is handed one `bounds` prop, and while
   * this is non-null it wins unconditionally. `FiltersPopover`'s own "My area"/"Whole catalogue"
   * segment (`onSelectScope` below) clears it on every press of its own — the sole place that does —
   * so a stale jump can never survive the reader's own later choice to reframe by scope.
   */
  const [jumpFitOverride, setJumpFitOverride] = useState(null);
  /** Monotonic source for {@code jumpFitOverride.nonce} — a ref, not state, since bumping it is
   *  always paired with a `setJumpFitOverride` call that already triggers the re-render. */
  const jumpFitSeq = useRef(0);
  /**
   * The reach-rings toggle (map-tab-v2-plan.md §3 P8) — read by both {@code MapHeatLayer} (the
   * dashed canvas circles) and {@code MapLabels} (their duration/distance labels), so the two can
   * never disagree about whether rings are on. Defaults true; {@code MapLegendPanel} (P10) is the
   * first and only writer.
   */
  const [ringsEnabled, setRingsEnabled] = useState(true);
  /**
   * Which of the map tab's own overlay popovers is open — map-tab-v2-plan.md §3 P7's exclusivity
   * rule ("opening one popover closes the others"). `'window'`, `'filters'`, `'legend'` (P10) and
   * `'jump'` (P11, the Regions jump list) are the four today, all on this ONE switch rather than a
   * separate state variable each. Tab-only in practice (the overlay never mounts any of these
   * popovers), but declared unconditionally rather than behind `!overlayMode` — a `useState` call
   * must never be conditional, and an unused value on the overlay mount costs nothing.
   */
  const [openMapMenu, setOpenMapMenu] = useState(null);
  // Filters are collapsed by default (a quiet "tell me more" follow-up to Plan);
  // the open/closed choice persists since users rarely change filters.
  //
  // In the OVERLAY the choice deliberately does not persist, and collapsed is the state every
  // single time: arriving here from a plan card IS a filter action — the user picked the location
  // and the solar event on the way in — so the drawer opens answering a question that has already
  // been answered. A remembered "open" would restate it on every drill-down thereafter. Changing
  // a filter while the drawer is open still persists the FILTER VALUE exactly as it does on the
  // Map tab; it is only the disclosure's own state that is forgotten.
  const [advancedOpen, setAdvancedOpen] = useState(
    () => !overlayMode && readMapFilter('mapFiltersOpen') === '1',
  );
  const toggleAdvancedOpen = () => setAdvancedOpen((v) => {
    const next = !v;
    if (overlayMode) return next;
    if (next) writeMapFilter('mapFiltersOpen', '1');
    else clearMapFilter('mapFiltersOpen');
    return next;
  });
  // Viewport of the overlay's map, as [south, west, north, east] — see BoundsTracker.
  const [mapBounds, setMapBounds] = useState(null);
  const handleBounds = useCallback((b) => {
    const next = [b.getSouth(), b.getWest(), b.getNorth(), b.getEast()];
    // Identity-compared before storing: `moveend` fires on every pan, and a fresh array each time
    // would re-render (and so rebuild every marker) for a viewport that has not actually moved.
    setMapBounds((prev) => (prev && prev.every((v, i) => v === next[i]) ? prev : next));
  }, []);
  const { status: auroraStatus } = useAuroraStatus();
  // The night aurora results are keyed to — from the backend, which owns the dusk/dawn rule.
  // Falls back to the local calendar date when status is absent (LITE, failed fetch, or a backend
  // older than the field), which is the behaviour this replaced.
  const auroraNight = resolveAuroraNight(auroraStatus);
  const viewlineEnabled = role !== 'LITE_USER' && auroraStatus != null
    && ALERT_WORTHY_LEVELS.has(auroraStatus.level);
  const [viewlineUpsellDismissed, setViewlineUpsellDismissed] = useState(false);
  const showViewlineUpsell = role === 'LITE_USER' && !viewlineUpsellDismissed
    && auroraStatus != null && ALERT_WORTHY_LEVELS.has(auroraStatus.level);
  // The Stage 7 flip's one-time "colours changed" notice. Persisted (not session-only, unlike the
  // viewline chip above): the reader who needs telling is exactly the one who will never open
  // Settings to find out, so this has to survive across visits until they have actually seen it
  // once. `readMapFilter`/`writeMapFilter` are the map's existing fail-soft localStorage helpers —
  // a storage-denied browser must not crash on this any more than it may on the filter state they
  // already guard, several of which are read inside a `useState` initializer exactly like this one.
  const [colourScaleNoticeDismissed, setColourScaleNoticeDismissed] = useState(
    () => readMapFilter(COLOUR_SCALE_NOTICE_DISMISSED_KEY) === '1',
  );
  const dismissColourScaleNotice = () => {
    setColourScaleNoticeDismissed(true);
    writeMapFilter(COLOUR_SCALE_NOTICE_DISMISSED_KEY, '1');
  };
  // `colourScaleDefaulted` alone is not quite enough: it says the STORED preference was null, but
  // the notice's own words ("cold to hot") only make sense while the live ramp is actually temp.
  // Cheap and correct to check both rather than assume the one implies the other forever — if
  // `DEFAULT_MODE` ever stops being `'temp'`, a defaulted reader would otherwise still get a
  // notice describing a ramp they are not looking at.
  const showColourScaleNotice = colourScaleDefaulted && getMode() === 'temp'
    && !colourScaleNoticeDismissed;
  const { viewline } = useAuroraViewline(viewlineEnabled, auroraStatus?.triggerType);
  const [auroraScores, setAuroraScores] = useState({});
  const [storedAuroraResults, setStoredAuroraResults] = useState({}); // locationName → result
  const [auroraAvailableDates, setAuroraAvailableDates] = useState([]); // ISO date strings
  const [astroScores, setAstroScores] = useState({}); // locationName → { stars, summary, ... }
  const [astroAvailableDates, setAstroAvailableDates] = useState([]); // ISO date strings
  const [flyTarget, setFlyTarget] = useState(null);
  const [fitBoundsTarget, setFitBoundsTarget] = useState(null);
  const [tideFetchedAt, setTideFetchedAt] = useState({});
  // Live Leaflet marker instances keyed by location name — used to open a popup
  // programmatically when the Plan tab hands off a specific location.
  const markerRefs = useRef(new Map());
  /**
   * `openMapMenu`'s value at the START of the CURRENT click gesture — see
   * `MapBackgroundClickController`'s own class doc for why a bare closure read at `click` time is
   * unreliable (map-tab-v2-plan.md §3 P9's close-ordering rule). Written on `mousedown`, read on
   * `click`; never read anywhere else.
   */
  const openMapMenuAtMouseDownRef = useRef(null);
  /**
   * The raw `L.MarkerClusterGroup` instance (map-tab-v2-plan.md §3 P8 review) — `@react-leaflet/
   * core`'s `createPathComponent` forwards a `ref` straight to the underlying Leaflet layer, the
   * same convention `Marker`'s own ref callback below already relies on. Needed so a chip click
   * can call `zoomToShowLayer` on a marker that is currently folded into a cluster bubble, where a
   * bare `marker.openPopup()` is a silent no-op (`marker._map` is null while it is clustered).
   */
  const clusterGroupRef = useRef(null);

  // Aurora is available when the user is ADMIN/PRO and either the state machine is active
  // or there are stored forecast results for any date on the date strip.
  const hasStoredAuroraResults = auroraAvailableDates.length > 0;
  const auroraAvailable = role !== 'LITE_USER'
    && (auroraStatus?.active === true || hasStoredAuroraResults);
  const astroAvailable = astroAvailableDates.length > 0;

  // Auto-reset to SUNSET when aurora mode becomes unavailable.
  useEffect(() => {
    if (eventType === 'AURORA' && !auroraAvailable) {
      // Wrapped in an inline async function to satisfy react-hooks/set-state-in-effect.
      // The body still runs synchronously in this tick, preserving prior behaviour.
      (async () => {
        setEventType('SUNSET');
        setMinStars(null);
        setShowUnrated(false);
        // A kept-local night (adversarial review, BLOCKING) has no meaning once the mode that
        // produced it is gone — this leaves SUNSET, which never reads `nightDate`'s override at
        // all, but a later re-entry into ASTRO/AURORA must not resume a night from a session ago.
        setLocalNightDate(null);
      })();
      clearMapFilter('mapFilterMinStars');
    }
  }, [auroraAvailable, eventType]);

  // Apply the auto-selected event type when forecast data arrives, unless the user
  // has already manually chosen an event type this session.
  useEffect(() => {
    if (!userHasOverriddenEvent && autoEventType) {
      // Inline async wrapper satisfies react-hooks/set-state-in-effect while the
      // setState still applies synchronously this tick (see note above).
      (async () => {
        setEventType(autoEventType);
        // A fresh auto-selection from a new forecast payload supersedes any night this pane was
        // previously keeping local on its own (adversarial review, BLOCKING).
        setLocalNightDate(null);
      })();
    }
  }, [autoEventType, userHasOverriddenEvent]);

  // Apply a forced event type from the Plan tab handoff, overriding any user selection.
  useEffect(() => {
    if (handoffEventType) {
      (async () => {
        setEventType(handoffEventType);
        setUserHasOverriddenEvent(false);
        // A handoff is a fresh, externally-driven entry into whatever mode it names — it must
        // not silently resume a night this pane happened to be keeping local before it arrived
        // (adversarial review, BLOCKING).
        setLocalNightDate(null);
      })();
    }
  }, [handoffEventType]);

  // Apply a filter action handoff from a Hot Topic pill tap (e.g. BLUEBELL).
  //
  // Re-targeted onto the popover for the Map tab (map-tab-v2-plan.md §3 P7): the drawer this used
  // to open (`setAdvancedOpen(true)`) no longer exists there — it is `overlayMode`-only now — so a
  // tab-mode handoff instead opens `FiltersPopover` via the same `openMapMenu` switch the window
  // control and the popover's own chip share. The overlay keeps its exact old behaviour.
  useEffect(() => {
    if (handoffFilterAction) {
      (async () => {
        setActiveTypeFilters(new Set([handoffFilterAction]));
        if (overlayMode) setAdvancedOpen(true);
        else setOpenMapMenu('filters');
      })();
    }
  }, [handoffFilterAction, overlayMode]);

  // Apply a Coming up chronology handoff (`coastal-spots`/`dark-sky-spots`, D8) — sets BOTH the
  // dark-sky toggle and the type filter together, because the coming-up channel always sends both
  // explicitly (`mapOverlay.js`'s `coming-up` branch: `filterAction` a real value or `null`,
  // `darkSky` always a real boolean) and the two must move in lockstep or they desynchronise.
  //
  // ⚠️ Depends on BOTH `handoffDarkSky` AND `handoffNonce`, unlike the standalone
  // `handoffFilterAction` effect above (which topic pills still use, and which only ever turns a
  // filter ON — an empty/falsy `handoffFilterAction` never fires it, so omitting the nonce there is
  // harmless). This effect must be able to turn EITHER flag off too (dark-sky spots, then coastal
  // spots, on the same never-unmounted map pane, and the reverse) — setting both in BOTH directions
  // on every dispatch, keyed by the nonce so a repeat tap of the SAME action re-applies it. Omitting
  // the nonce (copying the filter-action effect's shape for what is really a different value class)
  // would latch a flag permanently once turned on, since a `false`/`null` value is
  // `useEffect`-invisible without something else in the dependency array to force the re-run — this
  // is the exact trap plan D8 names, and turned out to apply to the type filter too, not just
  // dark-sky (found by an external review pass on the pushed PR, AGENTS.md's review-rules P1 class):
  // the standalone `handoffFilterAction` effect above never CLEARS on a falsy value, so a
  // coastal-spots handoff followed by a dark-sky-spots one left the stale SEASCAPE filter ANDed
  // with the new dark-sky one — "dark-sky coastal spots only", not "all dark-sky spots" (Codex).
  //
  // ⚠️ Guarded on `handoffDarkSky != null` — found by adversarial review. `handoffNonce` is ONE
  // monotonic counter shared by every `handleShowOnMap` call in the app (App.jsx), not just
  // coming-up ones, so an unconditional `setDarkSkyFilter(!!handoffDarkSky)` fired on every handoff
  // reaching this never-unmounted pane — a reader who manually turns Dark sky on, then taps ANY
  // unrelated map action (a Plan location drill-down, a region row), would find it silently turned
  // back off, since every other handoff's `handoffDarkSky` resolves to `null` (nobody else sets it).
  // The guard is safe specifically because the coming-up channel is the only producer of this prop
  // and it ALWAYS sends an explicit boolean (`mapOverlay.js`'s `darkSky: !!trigger.darkSky`, never
  // `null`) — so "null" unambiguously means "a different, unrelated handoff", and only a genuine
  // coming-up trigger (either flavour) can reach the `act` branch below. The SAME guard licenses
  // touching `activeTypeFilters` here too: only a coming-up handoff (which always sets both flags
  // together) reaches this branch, so a topic pill's own `handoffFilterAction`-only dispatch (no
  // `darkSky` field at all, `handoffDarkSky` stays `null`) is untouched by this effect and keeps
  // working exactly as it did before this phase.
  useEffect(() => {
    if (handoffDarkSky == null) return;
    (async () => {
      setDarkSkyFilter(!!handoffDarkSky);
      setActiveTypeFilters(handoffFilterAction ? new Set([handoffFilterAction]) : new Set());
      // Re-targeted onto the popover for the Map tab (map-tab-v2-plan.md §3 P7) — see the
      // standalone `handoffFilterAction` effect above for why this branches on `overlayMode`.
      if (overlayMode) setAdvancedOpen(true);
      else setOpenMapMenu('filters');
    })();
  }, [handoffDarkSky, handoffFilterAction, handoffNonce, overlayMode]);

  // Apply a specific-location handoff from a Plan tab drill-down: fly to the
  // location and select it. HandoffPopupController opens its popup once the fly
  // animation settles. The nonce makes repeat taps re-trigger this effect.
  useEffect(() => {
    if (!handoffLocationName) return;
    const loc = locations.find((l) => l.name === handoffLocationName && l.enabled !== false);
    if (!loc) return;
    (async () => {
      setSelectedLocationName(loc.name);
      setFlyTarget({ lat: loc.lat, lon: loc.lon });
    })();
    // locations intentionally omitted: re-running on every locations identity
    // change would re-fly mid-session. Nonce + name capture the intent to refly.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [handoffLocationName, handoffNonce]);

  // Apply a region handoff from a Plan-tab bet card: fit the map to the spread of
  // that region's locations (the macro view), so the pins shown match the bet.
  useEffect(() => {
    if (!handoffRegion) return;
    const points = locations
      .filter((l) => l.enabled !== false && l.regionName === handoffRegion
        && l.lat != null && l.lon != null)
      .map((l) => [l.lat, l.lon]);
    if (points.length === 0) return;
    (async () => setFitBoundsTarget({ points, key: fitBoundsKey('region', handoffNonce) }))();
    // locations intentionally omitted (see the location handoff above).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [handoffRegion, handoffNonce]);

  // Map-overlay focus: fit the map to an arbitrary set of pins (a multi-region event or a hot
  // topic's flagged locations). Re-fits when the focus nonce changes; a no-op on the Map tab.
  useEffect(() => {
    if (!focus?.points?.length) return;
    // Deferred (async) like the region handoff above, so the fit runs after commit.
    (async () => setFitBoundsTarget({ points: focus.points, key: fitBoundsKey('focus', focus.nonce) }))();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focus?.nonce]);
  const [tideClassifications, setTideClassifications] = useState({});

  // Inject popup width styles (desktop only)
  useEffect(() => {
    if (isMobile) return;
    const styleEl = document.createElement('style');
    styleEl.textContent = popupStyles;
    document.head.appendChild(styleEl);
    return () => styleEl.remove();
  }, [isMobile]);

  // Close bottom sheet / reset expanded state when switching mobile ↔ desktop
  useEffect(() => {
    void 0;
  }, [isMobile]);

  // Fetch per-location aurora scores when an alert is active (MODERATE or STRONG).
  // Scores are keyed by location name for O(1) lookup in popup render.
  useEffect(() => {
    if (!auroraStatus || !ALERT_WORTHY_LEVELS.has(auroraStatus.level)) {
      (async () => setAuroraScores({}))();
      return;
    }
    getAuroraLocations({ maxBortle: 9, minStars: 1 })
      .then((scores) => {
        const byName = {};
        scores.forEach((s) => { byName[s.location.name] = s; });
        setAuroraScores(byName);
      })
      .catch(() => {
        // Non-critical — popup will simply not show the aurora section
      });
  }, [auroraStatus]);

  // Fetch available dates for stored aurora forecast results (ADMIN/PRO only).
  // Determines whether the Aurora toggle should be shown when no live alert is active.
  useEffect(() => {
    if (role === 'LITE_USER') return;
    getAuroraForecastAvailableDates()
      .then(setAuroraAvailableDates)
      .catch(() => {});
  }, [role]);

  // Fetch stored aurora results when in Aurora mode and the selected NIGHT changes — `nightDate`,
  // not `date`: they diverge only when the window control's EV-ownership rule has kept a night
  // row local because its date is not in `forecastDates` (map-tab-v2-plan.md §3 P6).
  useEffect(() => {
    if (eventType !== 'AURORA' || !nightDate) {
      (async () => setStoredAuroraResults({}))();
      return;
    }
    getAuroraForecastResults(nightDate)
      .then((results) => {
        const byName = {};
        results.forEach((r) => { byName[r.locationName] = r; });
        setStoredAuroraResults(byName);
      })
      .catch(() => {
        setStoredAuroraResults({});
      });
  }, [eventType, nightDate]);

  // Fetch available dates for astro conditions (available to everyone).
  useEffect(() => {
    getAstroAvailableDates()
      .then(setAstroAvailableDates)
      .catch(() => {});
  }, []);

  // Fetch astro condition scores when in Astro mode and the selected NIGHT changes — see the
  // aurora fetch above for why this is `nightDate` rather than `date`.
  useEffect(() => {
    if (eventType !== 'ASTRO' || !nightDate) {
      (async () => setAstroScores({}))();
      return;
    }
    getAstroConditions(nightDate)
      .then((results) => {
        const byName = {};
        results.forEach((r) => { byName[r.locationName] = r; });
        setAstroScores(byName);
      })
      .catch(() => {
        setAstroScores({});
      });
  }, [eventType, nightDate]);

  /**
   * The multi-date astro/aurora fetch, for the window control's dropdown — map-tab-v2-plan.md
   * §3 P6. `utils/mapEvents.js` states each night's best achievable score ("choosing a window is
   * then an informed act rather than a guess", README), which needs every available night's
   * results, not just the one currently on screen. Scoped to `!overlayMode`: the overlay never
   * mounts the window control (it inherits its event from the card that opened it), so fetching
   * a whole horizon of astro/aurora data there would be pure waste on a surface that cannot show
   * it — the shared-component blast-radius rule (plan §2) applied to network cost rather than
   * markup. The single-night state above stays wired to `nightDate` regardless of mode, since the
   * overlay can still switch into astro/aurora mode via its own inherited `ForecastTypeSelector`.
   *
   * <p>⚠️ <b>Bounded to the solar horizon, never the raw available-dates list (PR #731 review).</b>
   * `getAstroAvailableDates`/`getAuroraForecastAvailableDates` answer with EVERY distinct date
   * ever persisted — a writer replaces a rerun date's row rather than pruning it — so on a
   * long-lived database an unbounded `Promise.all` over that list fanned a single Map-tab mount
   * out to hundreds of concurrent requests. `solarHorizonDates` is the SAME domain
   * `buildMapEvents` derives its D-13 filler rows from (briefing dates + forecast dates, UK-civil
   * today-forward), so intersecting against it caps the fan-out at the horizon's own size
   * (naturally ≤ about a week) with no new backend endpoint needed this phase. A night outside the
   * horizon still gets a real EV row — this bound has no effect on `buildMapEvents` itself — and,
   * once actually SELECTED, still gets its own dedicated fetch regardless of range through the
   * `nightDate`-keyed single-night effects above; only the unbounded PREVIEW fetch is capped.
   */
  const horizonDates = useMemo(() => solarHorizonDates({
    solarWindows: heat?.windows || [], forecastDates, todayStr: ukDateStr(),
  }), [heat, forecastDates]);
  const boundedAstroAvailableDates = useMemo(
    () => astroAvailableDates.filter((d) => horizonDates.includes(d)),
    [astroAvailableDates, horizonDates],
  );
  const boundedAuroraAvailableDates = useMemo(
    () => auroraAvailableDates.filter((d) => horizonDates.includes(d)),
    [auroraAvailableDates, horizonDates],
  );

  const [astroConditionsByDate, setAstroConditionsByDate] = useState(new Map());
  useEffect(() => {
    if (overlayMode || boundedAstroAvailableDates.length === 0) {
      // Inline async wrapper satisfies react-hooks/set-state-in-effect while the setState still
      // applies synchronously this tick — the same idiom the aurora-availability effect above uses.
      (async () => setAstroConditionsByDate(new Map()))();
      return undefined;
    }
    let cancelled = false;
    Promise.all(boundedAstroAvailableDates.map((d) => (
      getAstroConditions(d).then((rows) => [d, rows]).catch(() => [d, []])
    ))).then((pairs) => {
      if (!cancelled) setAstroConditionsByDate(new Map(pairs));
    });
    return () => { cancelled = true; };
  }, [overlayMode, boundedAstroAvailableDates]);

  const [auroraResultsByDate, setAuroraResultsByDate] = useState(new Map());
  useEffect(() => {
    if (overlayMode || boundedAuroraAvailableDates.length === 0) {
      (async () => setAuroraResultsByDate(new Map()))();
      return undefined;
    }
    let cancelled = false;
    Promise.all(boundedAuroraAvailableDates.map((d) => (
      getAuroraForecastResults(d).then((rows) => [d, rows]).catch(() => [d, []])
    ))).then((pairs) => {
      if (!cancelled) setAuroraResultsByDate(new Map(pairs));
    });
    return () => { cancelled = true; };
  }, [overlayMode, boundedAuroraAvailableDates]);

  const lineKm = lineKmForZoom(zoom);

  function toggleTypeFilter(type) {
    setActiveTypeFilters((prev) => {
      const next = new Set(prev);
      if (next.has(type)) {
        next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  }

  function handleMinStarsClick(star) {
    // Threshold control: selecting a level sets "this and above" and persists.
    setMinStars(star);
    writeMapFilter('mapFilterMinStars', String(star));
  }

  function toggleShowUnrated() {
    setShowUnrated((v) => !v);
  }

  function toggleShowStandDown() {
    setShowStandDown((v) => {
      const next = !v;
      if (next) writeMapFilter('mapFilterShowStandDown', '1');
      else clearMapFilter('mapFilterShowStandDown');
      return next;
    });
  }

  // Index briefing scores once per map so the per-marker lookup is O(1), instead of scanning the
  // whole Map for every marker (previously O(markers x scores) on each render).
  const briefingScoreIndex = useMemo(() => buildBriefingScoreIndex(briefingScores), [briefingScores]);

  const isAuroraMode = eventType === 'AURORA';
  const isAstroMode = eventType === 'ASTRO';

  /**
   * The heat field's opt-in, and the one place it is decided.
   *
   * <p>`heat` defaults to null and only `WindowFirstMapPane` passes it — the Plan overlay opens
   * focused on one spot from a card that has already answered the question, so it never fetches
   * the field or renders the toolbar. Deliberate, not an oversight: a field and toolbar over a
   * modal would be a second plan.
   *
   * <p>Withheld in AURORA mode even when handed: aurora's marker quantity (Kp visibility) is
   * latitude-led rather than a place property, so a field over it would be a smear over a signal
   * the field's own geography has nothing to say about.
   *
   * <p>ASTRO is the one exception, since map-tab-v2-plan.md §3 P6 — an astro night's observing
   * quality IS a place property, exactly like a solar window's sky colour, so the field paints it
   * the same way; see {@code astroHeatPoints} below for how it is scored.
   */
  const heatOffered = Boolean(heat?.enabled) && !isAuroraMode;
  const heatOn = heatOffered && heatView === 'heat';
  /**
   * Pins mode, tab-only (map-tab-v2-plan.md §3 P10) — `heatView === 'pins'` can only be reached
   * through the tab's own segment button (the overlay's dead render branch still writes/reads the
   * pre-P10 literal `'medallions'`, and never runs at all since `heatOffered` is always false
   * there), so this is equivalent to "the tab, in Pins view" without an explicit `!overlayMode`
   * check.
   */
  const heatPinsOn = heatOffered && heatView === 'pins';

  /**
   * The Legend panel's own handover fraction (map-tab-v2-plan.md §3 P10) — `MapHeatLayer.fadeAt`
   * re-exported via `utils/heatHandover.js` so this READS the same number the canvas fade paints
   * from without eagerly importing `MapHeatLayer.jsx`'s own `d3-geo` chain (see that module's own
   * class doc for why it is lazy). Zero while there is no field to hand over at all — the panel is
   * withheld entirely in that case (below), but the prop stays a plain number either way.
   */
  const legendHandoverFraction = heatOffered ? fadeAt(zoom).markers : 0;

  /**
   * The window the field paints — the map's OWN date and event, never a third time control.
   *
   * <p>The tab already has two ways to say when: the date strip above it and the event chips in
   * the filter drawer. A selector with its own state would make three, and the two that disagreed
   * would put the field on one evening and the markers on another with the same star beside them.
   * So the toolbar's selector sets the map's date and event; it does not hold a window of its own.
   *
   * <p>Null when the map is on a date the briefing does not cover. `GET /api/forecast` reaches
   * further than the briefing's six windows, so this is an ordinary state and not an error: the
   * field is absent, the markers are not, and the selector shows nothing selected.
   */
  const heatWindow = useMemo(() => {
    if (!heatOffered) return null;
    const key = `${date}:${eventType}`;
    return (heat.windows || []).find((w) => w.key === key) || null;
  }, [heatOffered, heat, date, eventType]);

  /**
   * The places the field is allowed to count.
   *
   * <p>⚠️ <b>Deliberately NOT the marker population.</b> The map defaults to 3★-and-above, and a
   * field filtered by that threshold would paint only the good news — every region green, the
   * gradient the feature exists to show flattened away. A red area is information.
   *
   * <p>⚠️ <b>And deliberately NOT the planning area either.</b> The first cut narrowed the field
   * with the "My area" segment, following the prototype (`map-tab.js`'s `visible()`), and the plan
   * overrules the prototype here in five places: §3 quotes the design's own rule that <em>the lens
   * does not filter the field</em>; §4.5 gives the segment as <em>fitBounds</em> and lists the
   * opening bounds separately; §9 Q4 keeps "should the field respect drive time" OPEN and says
   * revisit only with evidence; and both {@code planningArea.js} and {@code WindowFirstHeatStrip}
   * carry the same warning in as many words — handing {@code areaSpots} to the kernel turns the
   * framing into a reach filter. P2 chose the strip's footer wording <em>because</em> the field is
   * not area-filtered, so filtering here would have made one caption false on the tab next door.
   * The segment moves the camera; it does not decide which forecasts exist.
   *
   * <p>The dark-sky toggle is the one control that does narrow the field (§4.5, D7), and the
   * difference is not arbitrary: darkness is a property of the PLACE, and how far you would drive
   * is a property of the reader. Bortle 1–9, lower is darker, threshold {@code <= 4} — the bundle's
   * `>= 3.8` is its own mock scale inverted and must never be ported.
   */
  const heatSpotPool = useMemo(() => {
    if (!heatOffered || !darkSkyFilter) return null;
    return (heat.spots || []).filter(
      (s) => s.bortleClass != null && s.bortleClass <= DARK_SKY_THRESHOLD,
    );
  }, [heatOffered, heat, darkSkyFilter]);

  /**
   * The astro field's own points — map-tab-v2-plan.md §3 P6. Astro carries no served window key
   * (it is not one of the briefing's rendered solar windows), so there is no pre-built entry in
   * `heat.pointsByKey` to read; this builds the equivalent shape directly off `astroScores`
   * (already fetched for `nightDate` above) joined against the catalogue's own lat/lng.
   *
   * <p>⚠️ <b>Filtered to scored locations BEFORE the field ever sees them.</b> P1's review
   * established that a kernel `score` callback returning `null`/`undefined` for an unrated spot
   * yields a NaN weight that poisons every field cell that spot touches — so this loop excludes an
   * unrated spot outright rather than including it with a placeholder score, the same discipline
   * `heatSpots.heatPointsFor` already applies to solar windows. The point carries its star at
   * `r[0]` (`POINT_SCORE_INDEX`), the exact shape `drawTiles` already reads for every other
   * window — no new callback or kernel change needed, since the "unscored" exclusion happens here,
   * before construction, rather than inside the paint.
   */
  const astroHeatPoints = useMemo(() => {
    if (!isAstroMode || !heat?.spots?.length) return EMPTY_POINTS;
    const points = [];
    for (const spot of heat.spots) {
      const score = astroScores[spot.name]?.stars;
      if (typeof score !== 'number' || !Number.isFinite(score)) continue;
      points.push({
        id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
      });
    }
    return points;
  }, [isAstroMode, heat, astroScores]);

  /**
   * The astro field's own confidence — adversarial review finding (real #3). Astro carries no
   * `heat.windows` entry, so `heatWindow?.conf` (the solar path's own scalar, computed once in
   * `WindowFirstMapPane.jsx`) is always null for it, and the field was painting at full strength
   * regardless of horizon. Computed with the identical formula `utils/mapEvents.js`'s `nightRow`
   * uses for the astro EV row's own `confidence` field (`resolveConfidence(null, daysOut(...))`,
   * capped-inference since astro serves no confidence of its own) — re-derived here rather than
   * read off the EV list so this does not depend on `findEvIndex` having found a match.
   */
  const astroConfidenceScalar = useMemo(() => {
    if (!isAstroMode) return null;
    return confidenceScalar(resolveConfidence(null, daysOut(nightDate, ukDateStr())));
  }, [isAstroMode, nightDate]);

  /**
   * This window's kernel points, narrowed to that pool.
   *
   * <p>The catalogue carries `bortleClass` and the points do not — `heatSpots.js` says so in as
   * many words ("P4's dark-sky filter wants the whole catalogue; only the kernel wants points"), so
   * the filter is decided on spots and applied to points through the join's own id-first key.
   *
   * <p>ASTRO takes its own point set (above) rather than this join: it has no `heat.pointsByKey`
   * entry (no served window key), and its roster is dark-sky-enriched by construction, so the
   * dark-sky pool this join exists to apply would be a no-op filter over an already-narrower set.
   */
  const heatPoints = useMemo(() => {
    if (isAstroMode) return astroHeatPoints;
    if (!heatOn || !heatWindow) return EMPTY_POINTS;
    const points = heat.pointsByKey?.get(heatWindow.key) || EMPTY_POINTS;
    if (!heatSpotPool) return points;
    const allowed = new Set(heatSpotPool.map(heatSpotKey));
    return points.filter((p) => allowed.has(heatSpotKey(p)));
  }, [isAstroMode, astroHeatPoints, heatOn, heatWindow, heat, heatSpotPool]);

  /**
   * Whether the payload says nothing in the selected window is rated — the Plan tab's mark, adapted
   * to a host with no plate to hatch.
   *
   * <p>⚠️ <b>The window's served {@code bestRating}, never a point count.</b> Two different point
   * counts were tried and both were wrong evidence. {@code heatPoints} is narrowed by the dark-sky
   * toggle, so a reader who filtered every Bortle ≤ 4 location out of a well-rated window would
   * have been told the forecast was unscored. The unfiltered set fixes that and is still wrong for
   * a subtler reason the strip's class comment records: an empty point set is a fact about the
   * join behind the picture, and in production three windows the payload was rating had one.
   *
   * <p>It stays distinct from {@code heatWindow} being null, which is the map sitting on a date the
   * briefing does not reach: the selector already says "No forecast window" for that, and it is a
   * statement about the CAMERA rather than about the forecast.
   *
   * <p>ASTRO has no {@code heatWindow} at all (no served window key), so it asks the same question
   * of its own point set directly — an empty {@code astroHeatPoints} IS "nothing here is rated",
   * since every entry in it was already filtered to a real score.
   */
  const windowUnscored = Boolean(
    heatOn && (isAstroMode
      ? astroHeatPoints.length === 0
      : heatWindow && heatWindow.bestRating == null),
  );

  /**
   * The camera's framing for each segment state — `null` when the roster cannot supply a box.
   *
   * <p>`jumpFitOverride` (map-tab-v2-plan.md §3 P11) wins outright while set — see its own
   * declaration for why an override, not a second controller, is what keeps a region jump and an
   * ordinary scope press from racing each other's `fitBounds` call in the same commit.
   */
  const heatBounds = jumpFitOverride
    ? jumpFitOverride.bounds
    : (heatArea ? heat?.areaBounds : heat?.catalogueBounds) || null;
  const heatBoundsNonce = jumpFitOverride ? jumpFitOverride.nonce : heatFitNonce;
  /** `[40, 40]` for a region jump (the design bundle's own `jumpTo`), `[28, 28]` otherwise. */
  const heatBoundsPadding = jumpFitOverride ? [40, 40] : [28, 28];
  /** The box the map OPENS on, which is the area one whenever a field exists at all. */
  const openingBounds = (heat?.enabled ? heat.areaBounds : null) || null;

  /**
   * Entering aurora mode lands on the night the results are stored under.
   *
   * <p>A night is not a day. Run a forecast at 02:00 and the backend scores the window in progress
   * and stores it under <em>yesterday</em>; the map's date is a calendar date, so it opened on
   * today with nothing on it. The results were reachable — the strip carries T−2 — but not where
   * anyone would look, which made a paid run appear to have produced nothing.
   *
   * <p>Three guards, and each one is the difference between a default and a component that fights
   * its reader:
   * <ul>
   *   <li><b>Once per entry into aurora mode.</b> The latch resets only on leaving, so after the
   *       jump the strip is the reader's again — including to a night with no results.</li>
   *   <li><b>Only when the night actually has results.</b> Otherwise this would move the date to
   *       land on an equally empty day.</li>
   *   <li><b>Only when the current date has none.</b> Someone who opened the map on a scored night
   *       is already looking at what they came for.</li>
   * </ul>
   *
   * <p>Fails soft in both directions: with no {@code onSelectDate} it does nothing, and the parent
   * ignores a date that is not on the strip, so a night with aurora results but no colour forecast
   * row simply does not move the map.
   *
   * <p>⚠️ <b>Suppressed outright while a night is kept local (PR #731 review).</b> Sequence that
   * used to reach the parent regardless: pick an out-of-domain aurora row (through the window
   * control) → `localNightDate` is set and `isAuroraMode` becomes true in the SAME render → this
   * effect reacts to `isAuroraMode` changing, reads the RAW `date` prop (untouched by the pick,
   * since it was kept local rather than forwarded) and calls `onSelectDate(auroraNight)` — jumping
   * the parent to the backend's "current night" regardless of what the reader just chose. If the
   * parent accepted it, the `[date]`-keyed invalidation effect above then read the resulting prop
   * change as EXTERNAL (which, from its own narrow view, it correctly was) and cleared the very
   * selection the reader had just made. The fix is at the SOURCE rather than in the invalidation
   * effect: a `localNightDate` already means "the reader explicitly chose a night", which is the
   * latch's whole purpose already satisfied, so the auto-jump has nothing left to do. A fresh entry
   * into aurora mode with NO local selection is unaffected and still auto-jumps exactly as before —
   * this guard is additional, not a replacement for the three bullets above.
   */
  const auroraNightRequested = useRef(false);
  useEffect(() => {
    if (!isAuroraMode) {
      auroraNightRequested.current = false;
      return;
    }
    if (localNightDate != null) return;
    if (auroraNightRequested.current || !onSelectDate) return;
    // Nothing to land on yet — either no run for this night, or the fetch has not returned. Do NOT
    // latch here: `auroraAvailableDates` starts empty and arrives async, so latching on an empty
    // list would spend the one look before there was anything to look at.
    if (!auroraAvailableDates.includes(auroraNight)) return;
    // Latched HERE, before the last guard, because the decision is made at this point whether or
    // not it moves anything. Latching only on the firing path meant that entering aurora mode
    // already ON the night — the ordinary daytime case, since both default to today — left the
    // effect armed, and it then ate the reader's very next date-strip click and snapped back.
    // Reproduced in a browser, not theorised.
    auroraNightRequested.current = true;
    if (date === auroraNight || auroraAvailableDates.includes(date)) return;
    onSelectDate(auroraNight);
  }, [isAuroraMode, auroraAvailableDates, auroraNight, date, onSelectDate, localNightDate]);

  /** True when this location's forecast for the current event was triaged (stand-down). */
  const isStandDownLocation = useCallback((loc) => {
    if (eventType === 'AURORA') return false;
    const types = loc.locationType ?? [];
    const isPureWildlife = types.length > 0 && types.every((t) => t === 'WILDLIFE');
    if (isPureWildlife) return false;
    const briefingScore = lookupBriefingScore(briefingScoreIndex, loc.name, date, eventType);
    const dayData = loc.forecastsByDate.get(date);
    const solarType = eventType === 'SUNRISE' ? 'sunrise' : 'sunset';
    const forecast = dayData?.[solarType];
    return resolveStandDown(briefingScore, forecast);
  }, [eventType, date, briefingScoreIndex]);

  /** Get the forecast rating for a location on the current date/event. */
  const getRatingForLocation = useCallback((loc) => {
    if (eventType === 'AURORA') {
      // Prefer stored DB results; fall back to live state cache for tonight
      return storedAuroraResults[loc.name]?.stars
        ?? auroraScores[loc.name]?.stars
        ?? null;
    }
    if (eventType === 'ASTRO') {
      return astroScores[loc.name]?.stars ?? null;
    }
    // Mirror the marker render's precedence (line ~901): briefing score wins, then
    // forecast row. Without this, locations rated only via cached_evaluation render a
    // medallion but get hidden by the star-threshold filter.
    const briefingScore = lookupBriefingScore(briefingScoreIndex, loc.name, date, eventType);
    const dayData = loc.forecastsByDate.get(date);
    const forecast = eventType === 'SUNRISE' ? dayData?.sunrise : dayData?.sunset;
    return briefingScore?.rating ?? forecast?.rating ?? null;
  }, [eventType, date, briefingScoreIndex, storedAuroraResults, auroraScores, astroScores]);

  // Filter logic: type filters and rating filters are both AND-ed.
  // Within each filter group, any match passes (OR).
  const typeFiltered = useMemo(() => (
    activeTypeFilters.size === 0
      ? locations
      : locations.filter((loc) => {
          const types = loc.locationType ?? [];
          return types.length === 0 || types.some((t) => activeTypeFilters.has(t));
        })
  ), [locations, activeTypeFilters]);

  const hasStandDown = typeFiltered.some((loc) => isStandDownLocation(loc));
  const hasUnrated = typeFiltered.some((loc) => (
    !isStandDownLocation(loc) && getRatingForLocation(loc) == null
  ));

  // Full filter pipeline → the markers actually rendered. Memoised so a re-render that touches no
  // filter input (e.g. a zoom change, which only affects the azimuth line length) reuses the same
  // location list, keeping marker identities stable for react-leaflet.
  const visibleLocations = useMemo(() => {
    const ratingFiltered = typeFiltered.filter((loc) => {
      if (isStandDownLocation(loc)) return showStandDown;
      const types = loc.locationType ?? [];
      const isPureWildlife = types.length > 0 && types.every((t) => t === 'WILDLIFE');
      const rating = getRatingForLocation(loc);
      // Wildlife has no sky rating by design, so the sky-quality threshold must not
      // hide it. Other unrated (not-yet-evaluated) locations stay admin-gated behind
      // the "unknown" toggle, so the default 3★+ map reads quality-first.
      if (rating == null) return isPureWildlife || showUnrated;
      return rating >= minStars;
    });

    const driveFiltered = driveTimeFilter === 0
      ? ratingFiltered
      : ratingFiltered.filter((loc) => {
          const mins = driveMinutesFor(loc.id);
          return mins != null && mins <= driveTimeFilter;
        });

    // Dark sky filter: show only locations with Bortle class 4 or darker.
    const darkSkyFiltered = darkSkyFilter
      ? driveFiltered.filter((loc) =>
          loc.bortleClass != null && loc.bortleClass <= DARK_SKY_THRESHOLD,
        )
      : driveFiltered;

    // Map-overlay focus: when a hot-topic drilldown carries its qualifying spots, show ONLY those
    // markers — exactly the locations that made the topic fire (coastal / dark-sky / elevated / …) —
    // overriding the map's own type/rating filters so nothing worth showing is hidden.
    const focusNames = focus?.names?.length ? new Set(focus.names) : null;
    return focusNames
      ? locations.filter((loc) => focusNames.has(loc.name))
      : isAstroMode
        ? darkSkyFiltered.filter((loc) => loc.bortleClass != null)
        : darkSkyFiltered;
  }, [
    typeFiltered, locations, isStandDownLocation, getRatingForLocation,
    showStandDown, showUnrated, minStars, driveTimeFilter, driveMinutesFor,
    darkSkyFilter, focus, isAstroMode,
  ]);

  /**
   * The selected location — resolved from `locations`, the FULL enabled catalogue, never from
   * `visibleLocations` (PR #734 review, a confirmed finding). Filters govern the FIELD, the labels
   * and the markers; they must never govern a DELIBERATE selection the reader already made. Two
   * real failures fell out of reading this off `visibleLocations`: the every-window strip
   * switching to a window where the selected location sits below the min-stars default or is
   * unscored re-ran `getRatingForLocation` for that window through the SAME filter, dropped the
   * location out of the pool, and unmounted the callout mid-interaction — exactly when the reader
   * asked "how is THIS place on THAT window"; and an inbound `handoffLocationName` already resolves
   * against `locations` (the effect above), so `setSelectedLocationName` succeeds, but the callout
   * this variable feeds never appeared when the destination sat outside the reader's current
   * filters. `locations` is the same "enabled" catalogue `typeFiltered`/`visibleLocations` both
   * start from, so this loses no coverage on an ordinary selection — it only stops actively
   * REMOVING one.
   */
  const selectedLoc = locations.find((l) => l.name === selectedLocationName) ?? null;

  /**
   * `visibleLocations`, additionally narrowed to the scope pool — the ONE place scope is allowed
   * to narrow anything, and it is a REPORTING narrowing, not a marker one: the pins on the map stay
   * deliberately scope-independent (see `heatSpotPool` above — "the segment moves the camera; it
   * does not decide which forecasts exist"). Joined by the same id-first/name-fallback key
   * `heatPoints` already uses, since heat spots and `locations` are two different shapes
   * describing the same catalogue. Feeds the Map tab's `FiltersPopover` footer and counts footer
   * (map-tab-v2-plan.md §3 P7) — declared here, alongside `visibleLocations`, rather than lower in
   * the component: every hook must run unconditionally on every render, and the early return two
   * screens down (`if (!date || locations.length === 0)`) would make a `useMemo` declared after it
   * conditional.
   */
  const scopedVisibleLocations = useMemo(() => {
    if (!heatOffered || !heatArea) return visibleLocations;
    const allowed = new Set((heat?.areaSpots || []).map(heatSpotKey));
    return visibleLocations.filter((loc) => allowed.has(heatSpotKey(loc)));
  }, [heatOffered, heatArea, heat, visibleLocations]);

  /**
   * The Map tab's label catalogue (map-tab-v2-plan.md §3 P8) — `MapLabels`' own "named" pool, the
   * exact same filtered/scoped set the markers themselves draw from (`scopedVisibleLocations`),
   * joined with the current window's rating via the SAME accessor the star-threshold filter and
   * the marker render both already use (`getRatingForLocation`) — so a location's chip can never
   * show a different star than its own marker does. Tab-only (the overlay never mounts
   * {@code MapLabels} at all, so this costs it nothing to compute either way — cheap over a few
   * hundred rows, not worth a second `overlayMode` branch).
   *
   * <p>⚠️ Appends the SELECTED location's own row when the pool's filters have dropped it (PR #734
   * review): {@code chipCandidates}' own "the selected location always gets its chip" guarantee
   * (P8) can only force a chip for a spot that actually exists in the array it is handed — a
   * selection resolved from the full roster (see {@code selectedLoc}'s own comment) but absent from
   * this FILTERED one got a ring and a callout with no chip to anchor beside. Pushed rather than
   * unshifted: {@code chipCandidates}' own selected-name handling already moves it to the front.
   */
  const labelSpots = useMemo(() => {
    const spotOf = (loc) => ({
      name: loc.name,
      lat: loc.lat,
      lng: loc.lon,
      rid: loc.regionName || '',
      rating: getRatingForLocation(loc),
      bortleClass: loc.bortleClass ?? null,
      driveMinutes: driveMinutesFor(loc.id),
      // `PinsLayer`'s own stand-down/no-data distinction (adversarial review C8) — a triaged
      // location is not merely "nothing scored yet", the same distinction the medallion markers
      // already draw via `resolveStandDown`/`STAND_DOWN_COLOUR`, so a pin should not collapse the
      // two into one grey.
      isStandDown: isStandDownLocation(loc),
    });
    const spots = scopedVisibleLocations.map(spotOf);
    if (selectedLoc && !spots.some((s) => s.name === selectedLoc.name)) {
      spots.push(spotOf(selectedLoc));
    }
    return spots;
  }, [scopedVisibleLocations, getRatingForLocation, driveMinutesFor, selectedLoc, isStandDownLocation]);

  /**
   * The ring labels' own {@code reachMeasured} — "a real drive time gated this screen's reach
   * lens" (§5.2), the same honesty rule {@code WindowRowFieldMap}'s ring labels apply.
   *
   * <p>⚠️ Read off the FULL {@code userDriveTimes} fetch, never off {@code labelSpots} (map-tab-
   * v2-plan.md §3 P8 review, a confirmed finding): {@code labelSpots} is built from
   * {@code scopedVisibleLocations}, which the reader's own rating/subject/drive/dark-sky filters
   * narrow — so filtering away every measured location would flip this flag from true to false
   * while nothing about whether a drive time exists actually changed, and the ring label would
   * then silently swap from a duration back to a bare distance for a reason that has nothing to do
   * with reach honesty. "Was a drive time measured for this reader at all" is a fact about the
   * FETCH, not about which pins the filters currently allow through.
   */
  const mapReachMeasured = Boolean(homeCoords)
    && Object.values(userDriveTimes).some((mins) => Number.isFinite(mins));

  /**
   * Whether a real home COORDINATE exists — the exact test `MapHeatLayer`'s reach-ring paint and
   * `MapLabels`' ring-label candidates both gate on (`homeCoords?.lat != null && homeCoords?.lon
   * != null`), and what the Legend panel's rings toggle must gate its own PRESENCE on too
   * (adversarial review C4). ⚠️ Deliberately NOT `heat?.hasHome` — that field answers a different
   * question (the roster/reach-matrix's own "is there a home for planning-area purposes" signal,
   * which is what `FiltersPopover`'s scope segment and the overlay's area segment key on) and can
   * diverge from whether `homeCoords` itself has resolved — a toggle gated on the wrong one is
   * exactly the "control whose every press does nothing" the coherence rule elsewhere in this file
   * already bans, just for a different control.
   */
  const hasHomeCoords = homeCoords?.lat != null && homeCoords?.lon != null;

  // The emphasis target, but only when it survived the filter pipeline — see the marker render.
  const emphasisTarget = useMemo(() => (
    emphasiseLocationName != null
      && visibleLocations.some((l) => l.name === emphasiseLocationName)
      ? emphasiseLocationName
      : null
  ), [emphasiseLocationName, visibleLocations]);

  // Best aurora location — highest-starred entry from current aurora scores.
  const bestAuroraLocation = useMemo(() => {
    if (!isAuroraMode) return null;
    const entries = Object.values(auroraScores);
    if (entries.length === 0) return null;
    const best = entries.reduce((b, curr) => (curr.stars > b.stars ? curr : b), entries[0]);
    // When every location scored 1 star (all overcast / triage-rejected), don't highlight one
    if (best.stars <= 1) return null;
    return best;
  }, [isAuroraMode, auroraScores]);

  if (!date || locations.length === 0) {
    return (
      <p className="text-plex-text-muted text-sm text-center py-8">
        No forecast data available.
      </p>
    );
  }

  // Check if any location has a sunrise/sunset forecast for the selected date.
  // Also check briefingScores — batch-scored locations may only exist in cached_evaluation.
  const hasBriefingScoreForType = (type) => {
    if (!briefingScores || briefingScores.size === 0) return false;
    const suffix = `|${date}|${type}|`;
    for (const key of briefingScores.keys()) {
      if (key.includes(suffix)) return true;
    }
    return false;
  };
  const sunriseAvailable = locations.some((loc) => loc.forecastsByDate.get(date)?.sunrise != null)
    || hasBriefingScoreForType('SUNRISE');
  const sunsetAvailable  = locations.some((loc) => loc.forecastsByDate.get(date)?.sunset  != null)
    || hasBriefingScoreForType('SUNSET');

  const bounds = locations.map((loc) => [loc.lat, loc.lon]);

  const selectedDayData = selectedLoc?.forecastsByDate.get(date);
  // True when the selected date falls in a travel range — the overnight batch
  // skips Claude forecasts for those days, so the popup says so explicitly.
  const isTravelDayForDate = isTravelDate(date, travelRanges);
  const sunriseAzimuth = selectedDayData?.sunrise?.azimuthDeg ?? null;
  const sunsetAzimuth  = selectedDayData?.sunset?.azimuthDeg  ?? null;

  /** Derive popup content props for a location. */
  function getContentProps(loc) {
    // `nightDate`, not `date`: identical in the ordinary case, and the honest "no weather context"
    // gap rather than silently showing a stale night's data in the rare case the EV-ownership rule
    // has kept a night row local (map-tab-v2-plan.md §3 P6) — the roster join below already returns
    // `undefined` for a date that date-strip domain never carried anyway, so this only changes
    // which "nothing here" a reader sees, never which real forecast they see.
    const dayData = loc.forecastsByDate.get(nightDate);
    // Aurora/Astro mode: use sunset as background weather context (night event)
    const solarType = eventType === 'SUNRISE' ? 'sunrise' : 'sunset';
    const forecast = dayData?.[solarType];
    const hourlyData = dayData?.hourly ?? [];
    const types = loc.locationType ?? [];
    const isPureWildlife = types.length > 0 && types.every((t) => t === 'WILDLIFE');
    const isWaterfall = types.includes('WATERFALL');
    // A canopy site's rating answers a different question from every other pin's. Excluded from
    // cluster averages for the same reason WATERFALL is: a wood rated 5 on a flat overcast misty
    // evening would drag its cluster's grey→gold ramp toward gold on precisely the nights the sky
    // is at its worst. The two are ORed into one flag because the cluster only asks "does this
    // score mean sky colour", and for both the answer is no.
    const excludeFromSkyCluster = isWaterfall || !types.some((t) => SKY_SUBJECT_TYPES.includes(t));
    return { forecast, hourlyData, isPureWildlife, isWaterfall, excludeFromSkyCluster };
  }

  // Active (non-default) filters drive the collapsed pill summary and its highlight — and, in the
  // overlay, the read-only chips on the context bar. One list, two renderings: a chip row that
  // disagreed with the pill summary about what was being cut would be worse than neither.
  const STAR_THRESHOLD_LABELS = { 1: '1★+', 2: '2★+', 3: '3★+', 4: '4★+', 5: '5★' };
  const hasNonDefaultFilters = minStars !== DEFAULT_MIN_STARS
    || activeTypeFilters.size > 0 || showUnrated || showStandDown
    || driveTimeFilter > 0 || darkSkyFilter;
  // The quality threshold leads and is ALWAYS stated, default or not: it is the one filter that
  // is never a no-op — a 3★+ floor is hiding pins right now, and the pin count beside it would
  // otherwise be an unexplained number. Everything after it appears only when it is actually
  // cutting something, so the bar stays a short receipt rather than a list of switched-off things.
  const filterSummaryParts = [STAR_THRESHOLD_LABELS[minStars]];
  activeTypeFilters.forEach((t) => filterSummaryParts.push(
    locationTypeLabel(t),
  ));
  if (driveTimeFilter > 0) {
    filterSummaryParts.push(driveTimeFilter < 60 ? `≤${driveTimeFilter}m` : `≤${driveTimeFilter / 60}h`);
  }
  if (darkSkyFilter) filterSummaryParts.push('Dark sky');
  if (showStandDown) filterSummaryParts.push('+ stand-down');
  if (showUnrated) filterSummaryParts.push('+ unknown');

  // ── Map tab chrome: FiltersPopover + counts footer (map-tab-v2-plan.md §3 P7) ──
  // Distinct from `hasNonDefaultFilters`/`filterSummaryParts` above, which still drive the
  // OVERLAY's read-only context-bar chips (the joined `filterSummary` string the tab's old drawer
  // pill used to show is gone with it). The popover's chip shows a COUNT,
  // never a text summary, and the count deliberately EXCLUDES scope — switching "My area" ⇄
  // "Whole catalogue" reframes the camera rather than hiding anything the reader asked to see
  // (README §4: "N = count of active filters, scope not counted").
  //
  // ⚠️ Admin stand-down/unknown toggles get the EXACT SAME treatment as scope — present, sticky,
  // uncounted — adjudicated on review: they are debug LENSES that widen the pool back out to
  // triaged/unrated locations a reader's own filters already excluded, not filters a reader chose
  // to narrow anything by. Counting them would make "Filters (1)" mean "an admin is looking at
  // triage data" as often as it means "this reader narrowed something", and Clear all resetting
  // them would fight the exact debugging session they exist for. D-8 ("ride along" in an
  // admin-only row) describes where they LIVE in the popover, not how the count or Clear all
  // treat them.
  const filterActiveCount = (minStars !== DEFAULT_MIN_STARS ? 1 : 0)
    + activeTypeFilters.size
    + (driveTimeFilter > 0 ? 1 : 0)
    + (darkSkyFilter ? 1 : 0);

  /**
   * The popover's "Clear all" — every READER filter, but never scope and never the admin
   * stand-down/unknown lenses (both excluded for the reason `filterActiveCount` states above).
   */
  function clearAllMapFilters() {
    setActiveTypeFilters(new Set());
    setMinStars(DEFAULT_MIN_STARS);
    setDriveTimeFilter(0);
    setDarkSkyFilter(false);
    clearMapFilter('mapFilterMinStars');
  }

  /**
   * The scope-only pool — "My area" or "Whole catalogue", before every OTHER filter (rating,
   * subject, drive, dark-sky). The counts footer's "of K" and the popover's own "N of M shown"
   * both read this, matching the design's `basePool()` (README "State"). Empty when there is no
   * catalogue at all, so the footer reads "0 of 0" on a fresh install rather than throwing.
   */
  const scopeBasePool = heatOffered
    ? ((heatArea ? heat?.areaSpots : heat?.spots) || EMPTY_POINTS)
    : EMPTY_POINTS;

  /**
   * The counts footer's second line — "Beyond {@code N}h: …" in My area, or the whole-catalogue
   * sentence otherwise (README §9 "Count footer"). `heat.beyondRegionNames` is `WindowFirstMapPane`
   * `beyondRegions(heatSpots, reachById)` — the SAME test `planningArea.areaRegions` uses to build
   * the scope itself, so the two can never name a region on both sides. `GLANCE_MINUTES` is
   * `planningArea`'s own constant — one source for the "3h" the plan requires.
   */
  const countsSecondLine = !heatOffered ? null
    : heatArea
      ? ((heat?.beyondRegionNames?.length)
        ? `Beyond ${GLANCE_MINUTES / 60}h: ${heat.beyondRegionNames.join(' · ')}`
        : null)
      : 'Whole catalogue — including regions you would not drive to tonight';

  // ── Overlay context bar ──
  // The inherited event, with the clock time the plan card was showing. Taken from the pin the
  // overlay opened on where there is one, so the chip states that location's own solar time rather
  // than an arbitrary member of the set — they differ by minutes across a region.
  const contextEventTime = (() => {
    if (!overlayMode || isAuroraMode || isAstroMode) return null;
    const slot = eventType === 'SUNRISE' ? 'sunrise' : 'sunset';
    const anchor = (emphasisTarget && visibleLocations.find((l) => l.name === emphasisTarget))
      ?? visibleLocations[0];
    return formatEventTimeUk(anchor?.forecastsByDate?.get?.(date)?.[slot]?.solarEventTime ?? null);
  })();

  // What the map is actually showing. Falls back to the whole filtered set until the first bounds
  // report — a true statement either way, and never a count of something already filtered out.
  const pinsInView = mapBounds
    ? visibleLocations.filter(({ lat, lon }) => lat >= mapBounds[0] && lat <= mapBounds[2]
        && lon >= mapBounds[1] && lon <= mapBounds[3]).length
    : visibleLocations.length;

  /**
   * The overlay's own fixed-px height, animated open/closed with its drawer — unchanged. The Map
   * TAB no longer uses a pixel constant at all (map-tab-v2-plan.md §3 P7 retires `MAP_HEIGHT_PX`):
   * its map container is `flex:1; min-height:0` inside a frame `App`'s own flex-column recast
   * (`isMapTabActive`) sizes down to real, laid-out pixels — no computed height anywhere in the
   * chain — so the pane fills whatever space flexbox gives it rather than carrying its own opinion
   * about how tall that is.
   */
  const overlayMapHeight = advancedOpen ? OVERLAY_MAP_HEIGHT_FILTERS_OPEN_PX : OVERLAY_MAP_HEIGHT_PX;

  const eventSelector = (
    <ForecastTypeSelector
      eventType={eventType}
      onChange={(value) => {
        setUserHasOverriddenEvent(true);
        setEventType(value);
        setMinStars(DEFAULT_MIN_STARS);
        setShowUnrated(false);
        setShowStandDown(false);
        clearMapFilter('mapFilterMinStars');
        clearMapFilter('mapFilterShowStandDown');
      }}
      showAurora={role !== 'LITE_USER'}
      auroraAvailable={auroraAvailable}
      astroAvailable={astroAvailable}
      sunriseAvailable={sunriseAvailable}
      sunsetAvailable={sunsetAvailable}
    />
  );

  /**
   * The Map tab's single chronological event list — map-tab-v2-plan.md §3 P6, replacing the
   * date strip, the event pills and the in-map window select on the TAB only (the overlay keeps
   * `eventSelector` above, inherited from the card that opened it).
   *
   * <p>Built fresh every render rather than `useMemo`'d: every input already recomputes on its
   * own cadence above (the briefing poll's `heat.windows`, the two multi-date fetches), so a memo
   * here would carry the identical dependency list for a builder over a few tens of rows at most —
   * not a cost worth a second list to keep in sync.
   *
   * <p>The overlay gets the empty list outright rather than a real one nobody reads: it never
   * mounts {@code WindowControl} below, so building one would be pure waste on a surface that
   * cannot show it (the same reasoning that scopes the multi-date astro/aurora fetches above).
   */
  const mapEvents = overlayMode ? [] : buildMapEvents({
    solarWindows: heat?.windows || [],
    forecastDates,
    todayStr: ukDateStr(),
    tomorrowStr: ukDateStrOffset(1),
    astroAvailableDates,
    astroConditionsByDate,
    auroraAvailableDates,
    auroraResultsByDate,
    isLite: role === 'LITE_USER',
    formatTimeUk: formatEventTimeUk,
  });
  /** Which EV row is "now showing" — derived from `eventType`/`nightDate`, never a second store. */
  const activeEvIndex = findEvIndex(mapEvents, eventType, nightDate);

  /**
   * The active EV row's label+time, for {@code MapLabels}' hover tooltip "event" line
   * (`docs/design/map-tab-v2/README.md` "Interactions & behaviour": "Tooltip: name, event, N★
   * verdict, region · drive · Bortle"). Read straight off the row the window control itself shows
   * as current, so the tooltip can never name a different window than the pill does.
   */
  const mapEventLabel = (() => {
    const row = mapEvents[activeEvIndex];
    if (!row) return '';
    return row.time ? `${row.label} ${row.time}` : row.label;
  })();

  /** The row `MapCallout`'s verdict block and "every window" strip treat as "now showing" — the
   * SAME row the pill/tooltip above already read off `activeEvIndex`, never a second lookup. */
  const activeMapEvent = mapEvents[activeEvIndex] ?? null;

  /**
   * The Regions jump list (map-tab-v2-plan.md §3 P11, `docs/design/map-tab-v2/README.md` §2).
   *
   * <p><b>The drive map is EITHER the away region-base matrix OR the per-user home reach, never
   * both</b> — the exact precedence `driveMinutesFor` above already applies for every other drive
   * figure on this tab, reused rather than re-decided: `driveOverride` is only ever set while away,
   * so a home reader's rows are never touched by it.
   *
   * <p><b>Rows are built over the WHOLE catalogue</b> (`heat.spots`, never `heat.areaSpots`) — see
   * `utils/regionsJump.js`'s own module doc for why a jump scoped to where you already are could
   * never answer "where else could I go".
   *
   * <p><b>The best-score join is served for a SOLAR active event, and the SAME licence at a finer
   * key for a night one</b> (adjudicated ruling, map-tab-v2-plan.md §3 P11). Solar reads the served
   * `BriefingRegion.bestRating`, name-keyed. Astro/aurora carry no per-region rollup on the wire at
   * all, but the window dropdown's own "N★ best" column ALREADY takes a licensed client max over
   * that night's served per-location stars (`mapEvents.bestOfNight` — no server-owned figure exists
   * for a night, which is precisely why that max is licensed there). `utils/regionsJump.
   * buildNightRegionBest` groups those SAME served rows by region — via `heat.spots`' own
   * location-name→region-name pairing — and calls `bestOfNight` once per group: a finer key on an
   * already-licensed operation, not a second re-derivation, so the dropdown and this list can never
   * disagree about a night's best per region. Only a region with no served night rows at all still
   * renders with no score, the same em-dash `WindowControl`'s own unscored rows use.
   *
   * <p>Built fresh every render rather than `useMemo`'d/`useCallback`'d — the SAME choice
   * `mapEvents` above makes, and for the identical reason: every input already recomputes on its own
   * cadence, so a memo here would carry an equally long dependency list for a builder over a few
   * rows at most. (It also could not be a hook at all in this exact spot: this component's own
   * conditional early return sits between `mapEvents`/`activeMapEvent` above and this block, so a
   * `useCallback`/`useMemo` placed after it would violate the Rules of Hooks on every render that
   * takes that return — the same trap `selectMapLocation`'s own comment records a few screens down.)
   */
  const activeNightRows = (() => {
    if (!activeMapEvent || activeMapEvent.kind === EVENT_KIND.SOLAR) return null;
    return activeMapEvent.kind === EVENT_KIND.ASTRO
      ? astroConditionsByDate.get(activeMapEvent.date)
      : auroraResultsByDate.get(activeMapEvent.date);
  })();
  const nightRegionBest = activeNightRows
    ? buildNightRegionBest(activeNightRows, heat?.spots || EMPTY_POINTS)
    : null;
  function jumpBestRatingFor(regionName) {
    if (!activeMapEvent) return null;
    if (activeMapEvent.kind === EVENT_KIND.SOLAR) {
      return regionBestRatingFor(regionBestIndex, activeMapEvent.date, activeMapEvent.eventType, regionName);
    }
    return nightRegionBest?.get(regionName) ?? null;
  }
  const jumpDriveMap = driveOverride || reachById || null;
  const jumpRows = buildJumpRows({
    spots: heat?.spots || EMPTY_POINTS,
    driveMap: jumpDriveMap,
    bestRatingFor: jumpBestRatingFor,
  });
  /**
   * Resets scope to My area and refits — `FiltersPopover`'s own "My area" segment button AND
   * `CentreOnHomeControl`'s `⌂` (map-tab-v2-plan.md §3 P11's reconciliation, see that component's
   * own doc) both go through this ONE function, so the two can never disagree about what "reset
   * scope" means. Clears any standing jump override first: a stale region fit must not survive a
   * reader's own, later choice to reframe by scope.
   */
  function resetToMyArea() {
    setJumpFitOverride(null);
    setHeatArea(true);
    setHeatFitNonce((n) => n + 1);
  }
  /**
   * Selecting a jump row (README §2: "Selecting a row fits the map to that region's bounds.
   * If the region lies outside 'My area', it switches scope to Whole catalogue automatically" —
   * "a jump is honest; a no-op is not"). `heat.areaSpots` is read directly for the "is this region
   * in scope" test rather than re-derived from `planningArea.areaRegions`: it already reflects
   * whichever scope is actually in force — the home planning area, or a single away region — so a
   * second computation could only ever disagree with the one the camera and the filters already
   * agree on.
   *
   * <p><b>The panel closes on selection — the bundle is silent on this, so the choice is stated
   * here rather than left to look accidental.</b> A jump is a COMPLETED navigation: the reader named
   * a destination and the camera has already moved there, so a panel left open afterward would sit
   * directly over the very ground they just asked to see. That is a different question from
   * `FiltersPopover`'s own rows, which rightly stay open on every press — a filter is a STANDING
   * choice the reader is still composing, one control at a time, and closing it on the first press
   * would make every subsequent one a fresh re-open (adversarial review + live browser finding,
   * map-tab-v2-plan.md §3 P11).
   */
  function jumpToRegion(regionName) {
    const spots = heat?.spots || EMPTY_POINTS;
    const regionSpots = spots.filter((s) => s?.regionName === regionName);
    if (regionSpots.length === 0) return;
    if (heatArea) {
      const inArea = (heat?.areaSpots || EMPTY_POINTS).some((s) => s?.regionName === regionName);
      if (!inArea) setHeatArea(false);
    }
    jumpFitSeq.current += 1;
    setJumpFitOverride({
      bounds: latLngBounds(regionSpots, 0.06),
      nonce: jumpFitSeq.current,
    });
    // Closes the jump menu — see this function's own doc for why a jump closes where a filter row
    // would not.
    setOpenMapMenu(null);
  }

  /**
   * The Plan-tab handoff (map-tab-v2-plan.md §3 P9's "Open in Plan" action) — builds the SAME
   * {@code {id, name, regionName}} shape `WindowFirstShell.jsx`'s `sheetSpotOf` already normalises
   * every location-sheet entry point onto, so `App.jsx`'s `openLocationInPlan` needs no second
   * translation for this one more caller.
   */
  function handleOpenLocationInPlan() {
    if (!selectedLoc) return;
    onOpenLocationInPlan?.({
      id: selectedLoc.id ?? null,
      name: selectedLoc.name,
      regionName: selectedLoc.regionName ?? null,
    });
  }

  /**
   * Picking a row from the window control — map-tab-v2-plan.md §3 P6's EV-ownership paragraph.
   *
   * <p><b>`onSelectDate` is forwarded only when the row's own date is one the forecast endpoint
   * actually returned</b> (`forecastDates`, D-13's own domain). `App`'s `effectiveDate` guard
   * rejects any date not in its `allDates` outright, so forwarding one App will not accept would
   * silently do nothing there while this component moved on regardless — the two would then show
   * different nights with nothing telling either of them so. A night row whose date fails that
   * test keeps `localNightDate` instead, which every reader above that needs "the current night"
   * (the astro/aurora fetch effects, the aurora viewline gate, `getContentProps`) already consults
   * through {@code nightDate} rather than the raw prop.
   *
   * <p>Filters reset only when the KIND actually changes — the same condition
   * {@code ForecastTypeSelector}'s own {@code onChange} above resets unconditionally on, since
   * every one of its presses IS a kind change. Stepping between two dates of the same kind (the
   * old {@code DateStrip}'s entire job) must not silently clear a star floor or the stand-down/
   * unknown toggles the reader only just set.
   */
  function selectEvRow(row) {
    const typeChanged = row.eventType !== eventType;
    setUserHasOverriddenEvent(true);
    setEventType(row.eventType);
    if (typeChanged) {
      setMinStars(DEFAULT_MIN_STARS);
      setShowUnrated(false);
      setShowStandDown(false);
      clearMapFilter('mapFilterMinStars');
      clearMapFilter('mapFilterShowStandDown');
    }
    // `row.inForecastDomain` — the SAME fact `utils/mapEvents.js` already computed when it built
    // this row, gated symmetrically for solar and night rows alike (adversarial review, minor #7).
    // Every served/D-13 solar row happens to carry `inForecastDomain: true` by construction, so
    // this reads identically to the old `row.kind === 'solar'` shortcut in practice — but it is no
    // longer a SEPARATE claim that could silently drift from the EV list's own domain test.
    if (row.inForecastDomain) {
      setLocalNightDate(null);
      if (row.date !== date) {
        // Recorded so the `[date]` invalidation effect above can tell this forward apart from an
        // externally-driven `date` change (adversarial review, BLOCKING) — set immediately before
        // the call, never after, since the parent may (in a real app) re-render synchronously.
        forwardedDateRef.current = row.date;
        onSelectDate?.(row.date);
      }
    } else {
      setLocalNightDate(row.date);
    }
  }

  /**
   * A chip click (map-tab-v2-plan.md §3 P8) — wired to the EXACT SAME path a marker click already
   * takes on this tab, never a new one: {@code setSelectedLocationName} plus, on desktop, revealing
   * that marker if it is currently folded into a cluster bubble.
   *
   * <p>⚠️ Through P8 this also opened the marker's own Leaflet popup. P9 replaces the popup with
   * the anchored callout on this tab (map-tab-v2-plan.md §3 P9 — "the tab stops mounting Leaflet
   * `Popup`/`BottomSheet` for markers"), so there is no popup left to open here: `MapCallout` reads
   * {@code selectedLocationName} reactively and positions itself off the SAME marker ref's own
   * projected point, needing no imperative nudge from this function. {@code zoomToShowLayer} stays
   * — a marker still worth REVEALING out of its cluster bubble before the ring and the card anchor
   * to it, since a ring drawn around a still-clustered bubble would point at the wrong disc.
   *
   * <p>A plain function, like {@code selectEvRow} above — NOT {@code useCallback}, which would be
   * a hook called after this component's own conditional early return a few screens up and so
   * break the Rules of Hooks.
   */
  function selectMapLocation(name) {
    setSelectedLocationName(name);
    if (isMobile) return;
    const marker = markerRefs.current.get(name);
    if (!marker) return;
    const clusterGroup = clusterGroupRef.current;
    if (clusterGroup && typeof clusterGroup.zoomToShowLayer === 'function') {
      clusterGroup.zoomToShowLayer(marker);
    }
  }

  /**
   * The window control, controlled for menu exclusivity (map-tab-v2-plan.md §3 P7). `openMapMenu`
   * is this pane's own "which popover is open" — shared with `FiltersPopover` below so a press on
   * either closes the other, and with the map-background click controller inside `MapContainer`.
   */
  const windowControl = !overlayMode && (
    <WindowControl
      events={mapEvents}
      activeIndex={activeEvIndex}
      onSelect={selectEvRow}
      open={openMapMenu === 'window'}
      onOpenChange={(next) => setOpenMapMenu(next ? 'window' : null)}
    />
  );

  // The overlay's own disclosure. The chips beside it already summarise what is active, so this
  // drops to the plain weight of the modal's ✕ — one button, right-aligned, with a caret that
  // turns. The Map TAB no longer renders this at all (map-tab-v2-plan.md §3 P7): its filters live
  // in `FiltersPopover`, mounted as chrome over the map rather than in this primary row.
  const filtersButton = (
    <button
      type="button"
      data-testid="advanced-filters-toggle"
      onClick={toggleAdvancedOpen}
      aria-expanded={advancedOpen}
      className="map-ctx-btn ml-auto"
    >
      Filters
      <span aria-hidden="true" className="map-ctx-caret" data-open={advancedOpen || undefined}>▾</span>
    </button>
  );

  /**
   * `Esc` closes menus, THEN the callout (map-tab-v2-plan.md §3 P9, README "Interactions"
   * table) — never both on one press. `WindowControl`/`FiltersPopover` each close THEIR OWN open
   * menu locally on `Escape` (calling `onOpenChange`, which updates `openMapMenu`) without
   * `stopPropagation`, so the bubbled keydown still reaches this wrapper on the SAME press — but
   * `openMapMenu` here is read from the CLOSURE captured before that update commits, so it still
   * reads the menu's PRE-press value on press 1 (skipping the callout) and its POST-press value
   * (null) on press 2 (closing the callout). No `stopPropagation` needed on either child.
   *
   * <p>Tab-only: the overlay has no popover and no callout, so this is a no-op there — it is
   * simply never wired to the overlay's return path below.
   */
  function handleMapPaneKeyDown(mapPaneEvent) {
    if (mapPaneEvent.key !== 'Escape') return;
    if (openMapMenu != null) return;
    if (selectedLocationName != null) setSelectedLocationName(null);
  }

  return (
    <div
      // `wf-map-tab` is a pure CSS scoping hook (map-tab-v2-plan.md §3 P12) — the phone media
      // query needs to hide Leaflet's OWN zoom control (a real `.leaflet-control-zoom` DOM node
      // this component never renders itself, so there is no React-owned element to gate) on the
      // TAB only, never the overlay, whose own mount never carries this class.
      className={overlayMode ? 'flex flex-col' : 'flex flex-col flex-1 min-h-0 wf-map-tab'}
      onKeyDown={overlayMode ? undefined : handleMapPaneKeyDown}
    >
      {overlayMode && (
        /* ── Context bar — the overlay's default row ──
           A receipt, not a control panel: what the map is showing, in read-only chips, because
           the user already answered every one of these questions on the way in.

           Tab-gated: the Map TAB renders none of this (map-tab-v2-plan.md §3 P7) — its window
           control and filters live as chrome over the full-frame map further down. */
        <div
          data-testid="map-context-bar"
          className="flex items-center flex-wrap"
          style={{
            gap: '9px',
            padding: '9px 18px',
            borderBottom: '1px solid var(--color-plex-border)',
            background: 'rgba(0,0,0,0.16)',
          }}
        >
          <span
            className="font-mono uppercase text-plex-text-muted"
            style={{ fontSize: '10px', fontWeight: 600, letterSpacing: '0.1em' }}
          >
            Showing
          </span>
          <span data-testid="map-context-event" className="map-ctx-chip map-ctx-chip--lead">
            {EVENT_TYPE_LABELS[eventType] ?? eventType}
            {contextEventTime && ` · ${contextEventTime}`}
          </span>
          {filterSummaryParts.map((part) => (
            <span key={part} data-testid="map-context-chip" className="map-ctx-chip">{part}</span>
          ))}
          <span
            data-testid="map-context-count"
            className="font-mono text-plex-text-muted"
            style={{ fontSize: '11px' }}
          >
            {pinsInView} {pinsInView === 1 ? 'pin' : 'pins'} in view
          </span>
          {filtersButton}
        </div>
      )}

      {/* Advanced filters — the OVERLAY's own slide-down drawer, byte-identical to before P7 and
          tab-gated in full: the Map tab no longer renders any of this (its filters live in
          `FiltersPopover`, mounted as chrome over the map further down). */}
      {overlayMode && (
      <div
        className="overflow-hidden"
        data-testid="advanced-filters-panel"
        style={{
          maxHeight: advancedOpen ? '340px' : 0,
          transition: `max-height 0.24s ${DRAWER_EASING}`,
          borderBottom: '1px solid var(--color-plex-border)', background: 'rgba(0,0,0,0.10)',
        }}
      >
        <div
          className="flex flex-col"
          data-testid="advanced-filters-content"
          style={{ gap: '11px', padding: '13px 18px 15px' }}
        >

          {/* ── Event — the one control the drill-down genuinely inherited, so it is labelled as
              such rather than presented as a fresh question. ── */}
          <FilterRow
            compact
            label="Event"
            compactLabel="Event"
            hint="inherited from the plan card"
          >
            {eventSelector}
          </FilterRow>

          {/* ── Minimum quality — a single "this and above" threshold ── */}
          <FilterRow
            compact={overlayMode}
            label="Minimum quality"
            compactLabel="Quality"
            info={<InfoTip text="Shows locations rated this many stars and above. Combines with the Subject and Logistics filters: a location must match all three." />}
          >
            <div className="flex items-center gap-2.5 flex-wrap">
              <div className="inline-flex rounded-full border border-plex-border overflow-hidden" role="group" aria-label="Minimum quality threshold">
                {[1, 2, 3, 4, 5].map((star) => (
                  <button
                    key={`star-${star}`}
                    onClick={() => handleMinStarsClick(star)}
                    data-testid={`star-filter-${star}`}
                    aria-pressed={star >= minStars}
                    title={`Show ${star}★ and above`}
                    className={`inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium transition-colors border-l border-plex-border first:border-l-0 ${
                      star >= minStars
                        ? 'bg-plex-gold/20 text-plex-gold'
                        : 'bg-plex-surface text-plex-text-secondary hover:text-plex-text'
                    }`}
                  >
                    <span
                      aria-hidden="true"
                      className="inline-block rounded-full"
                      /* scoreRamp is the map's only colour language — the field, the markers and
                         the clusters all paint on it in every view, so the swatch matches every
                         marker on screen regardless of heat/medallions. */
                      style={{ width: 8, height: 8, backgroundColor: rampHex(star) }}
                    />
                    {star}&#9733;{star < 5 ? '+' : ''}
                  </button>
                ))}
              </div>
              <span className="text-[11px] text-plex-text-muted" data-testid="quality-hint">
                showing {minStars}★ and above · saved
              </span>
            </div>

            {/* Admin-only debug toggles — surface washouts / unknown-state locations
                so an empty map reads as "nothing good" rather than a load failure.
                Not a photographer feature, hence the admin gate. */}
            {role === 'ADMIN' && !isAuroraMode && !isAstroMode && (
              <div className="flex items-center gap-2 flex-wrap mt-0.5">
                <span className="text-[10px] uppercase tracking-wide text-plex-text-muted/70 border border-plex-border rounded px-1.5 py-px">admin</span>
                <button
                  onClick={toggleShowStandDown}
                  disabled={!hasStandDown}
                  data-testid="star-filter-standdown"
                  title={!hasStandDown
                    ? 'No stand-down locations in view'
                    : showStandDown ? 'Hide stand-down locations' : 'Show stand-down locations'}
                  className={`inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium rounded-full border transition-colors ${
                    !hasStandDown
                      ? 'bg-plex-surface border-plex-border text-plex-text-muted/40 cursor-not-allowed'
                      : showStandDown
                        ? 'bg-plex-gold/20 border-plex-gold/50 text-plex-gold'
                        : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
                  }`}
                >
                  <span aria-hidden="true" className="inline-block rounded-full" style={{ width: 8, height: 8, backgroundColor: STAND_DOWN_COLOUR }} />
                  &mdash; stand-down
                </button>
                <button
                  onClick={toggleShowUnrated}
                  disabled={!hasUnrated}
                  data-testid="star-filter-unrated"
                  title={!hasUnrated ? 'No unknown-state locations in view' : 'Toggle locations with no evaluation'}
                  className={`inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium rounded-full transition-colors ${
                    !hasUnrated
                      ? 'bg-transparent border border-dashed border-plex-text-muted/20 text-plex-text-muted/40 cursor-not-allowed'
                      : showUnrated
                        ? 'bg-plex-text-muted/20 border border-dashed border-plex-text-muted/60 text-plex-text-secondary'
                        : 'bg-transparent border border-dashed border-plex-text-muted/30 text-plex-text-muted hover:text-plex-text-secondary'
                  }`}
                >
                  <span aria-hidden="true" className="inline-block rounded-full" style={{ width: 8, height: 8, backgroundColor: 'transparent', border: '1px dashed #888780' }} />
                  ? unknown
                </button>
              </div>
            )}
          </FilterRow>

          {/* ── Subject — location-type chips (hidden in Aurora/Astro modes) ── */}
          {!isAuroraMode && !isAstroMode && (
            <FilterRow compact={overlayMode} label="Subject" compactLabel="Subject">
              <div className="flex items-center gap-2 flex-wrap">
                {MAP_FILTER_CHIPS.map(([type, { label, emoji }]) => (
                  <button
                    key={type}
                    onClick={() => toggleTypeFilter(type)}
                    className={`px-3 py-1 text-xs font-medium rounded-full border transition-colors ${
                      activeTypeFilters.has(type)
                        ? 'bg-plex-border border-plex-border-light text-plex-text'
                        : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
                    }`}
                  >
                    <span className={type === 'WILDLIFE' ? 'brightness-200 contrast-200 inline-block' : undefined} style={type === 'WILDLIFE' ? { filter: 'brightness(2) contrast(1.5)' } : undefined}>{emoji}</span> {label}
                  </button>
                ))}
                {seasonalFeatures.includes('BLUEBELL') && (
                  <button
                    key="BLUEBELL"
                    data-testid="location-type-filter-BLUEBELL"
                    onClick={() => role !== 'LITE_USER' ? toggleTypeFilter('BLUEBELL') : undefined}
                    disabled={role === 'LITE_USER'}
                    className={`px-3 py-1 text-xs font-medium rounded-full border transition-colors ${
                      role === 'LITE_USER'
                        ? 'opacity-45 cursor-default bg-plex-surface border-plex-border text-plex-text-secondary'
                        : activeTypeFilters.has('BLUEBELL')
                          ? 'bg-plex-border border-plex-border-light text-plex-text'
                          : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
                    }`}
                    title={role === 'LITE_USER' ? 'Upgrade to Pro to filter by Bluebell sites' : undefined}
                  >
                    🌸 Bluebell
                  </button>
                )}
              </div>
            </FilterRow>
          )}

          {/* ── Logistics — drive time, dark-sky, clear ── */}
          <FilterRow compact={overlayMode} label="Logistics" compactLabel="Logistics">
            <div className="flex items-center gap-2 flex-wrap">
              <select
                value={driveTimeFilter}
                onChange={(e) => setDriveTimeFilter(parseInt(e.target.value, 10))}
                className="text-xs px-2 py-1 bg-plex-surface border border-plex-border rounded-full text-plex-text-secondary focus:outline-none focus:ring-1 focus:ring-plex-gold"
                data-testid="drive-time-filter-select"
                title="Filter by drive time from last-refreshed position"
              >
                <option value={0}>🚗 Any drive time</option>
                <option value={30}>🚗 ≤30 min</option>
                <option value={45}>🚗 ≤45 min</option>
                <option value={60}>🚗 ≤60 min</option>
                <option value={90}>🚗 ≤90 min</option>
                <option value={120}>🚗 ≤2 hrs</option>
              </select>
              {!isAuroraMode && !isAstroMode && (
                <>
                  <button
                    onClick={() => setDarkSkyFilter((v) => !v)}
                    data-testid="dark-sky-filter-toggle"
                    title={`Show only locations with a light pollution rating of ${DARK_SKY_THRESHOLD} or lower — suitable for aurora, astrophotography, and stargazing.`}
                    className={`px-3 py-1 text-xs font-medium rounded-full border transition-colors ${
                      darkSkyFilter
                        ? 'bg-indigo-900/40 border-indigo-500/60 text-indigo-300'
                        : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
                    }`}
                  >
                    🔭 Dark sky only
                  </button>
                  <InfoTip text={`Shows locations with a light pollution rating of ${DARK_SKY_THRESHOLD} or lower — suitable for aurora, astrophotography, and stargazing.${role === 'ADMIN' ? '\n\nRun 🌌 Refresh Light Pollution in Location Management to populate ratings.' : ''}`} />
                </>
              )}
              {hasNonDefaultFilters && (
                <button
                  onClick={() => {
                    setActiveTypeFilters(new Set());
                    setMinStars(DEFAULT_MIN_STARS);
                    setShowUnrated(false);
                    setShowStandDown(false);
                    setDriveTimeFilter(0);
                    setDarkSkyFilter(false);
                    clearMapFilter('mapFilterMinStars');
                    clearMapFilter('mapFilterShowStandDown');
                  }}
                  className="px-3 py-1 text-xs font-medium rounded-full border border-plex-border text-plex-text-muted hover:text-plex-text-secondary transition-colors"
                  data-testid="clear-all-filters"
                >
                  Clear
                </button>
              )}
            </div>
          </FilterRow>
        </div>
      </div>
      )}

      {/* Best aurora location card — visible only in aurora mode */}
      {isAuroraMode && bestAuroraLocation && (
        <div
          style={overlayMode ? { margin: '10px 18px' } : undefined}
          className="flex items-center justify-between gap-3 px-4 py-2.5 rounded-lg border border-indigo-500/30 bg-indigo-900/20 text-sm"
          data-testid="aurora-best-location-card"
        >
          <div className="flex items-center gap-2 min-w-0">
            <span className="text-indigo-300 shrink-0">🏆</span>
            <div className="min-w-0">
              <span className="text-indigo-200 font-medium">{bestAuroraLocation.location.name}</span>
              <span className="text-indigo-400 ml-2">{'★'.repeat(bestAuroraLocation.stars)}{'☆'.repeat(5 - bestAuroraLocation.stars)}</span>
              {bestAuroraLocation.summary && (
                <p className="text-indigo-400 text-xs mt-0.5 truncate">{bestAuroraLocation.summary}</p>
              )}
            </div>
          </div>
          <button
            onClick={() => setFlyTarget({ lat: bestAuroraLocation.location.lat, lon: bestAuroraLocation.location.lon })}
            className="shrink-0 px-3 py-1 text-xs font-medium rounded-full border border-indigo-500/40 text-indigo-300 bg-indigo-900/40 hover:bg-indigo-800/40 transition-colors"
          >
            Centre map
          </button>
        </div>
      )}
      {/* All-overcast message now shown inside AuroraBanner */}

      {/* Map.
          In the overlay it is full-bleed and its height is the reclaimed space — no rounding or
          ring, because it butts against the modal's own edges rather than sitting on a page. The
          height transition shares the drawer's easing so the two move as one gesture.

          On the TAB (map-tab-v2-plan.md §3 P7) it is `flex:1; min-height:0` instead of a pixel
          height: the pane fills whatever the shell's viewport-height chain gives it, and every
          overlay chip below (window control, filters, legend, counts) is absolutely positioned
          INSIDE this same `position:relative` box rather than occupying page-flow rows above it —
          which is what lets the map own the whole frame with no page scroll. */}
      <div
        data-testid="map-container"
        className={overlayMode ? '' : 'flex-1 min-h-0'}
        style={overlayMode ? {
          height: `${overlayMapHeight}px`,
          position: 'relative',
          zIndex: 0,
          transition: `height 0.24s ${DRAWER_EASING}`,
        } : {
          position: 'relative',
          zIndex: 0,
        }}
      >
        <MapContainer
          /* The heat arm opens on the PLANNING AREA, not on every location the roster holds (§4.5).
             Framing the whole catalogue would open on a box wide enough to make the field a smudge
             and every marker a cluster, which is the overload the feature removes.

             Falls back to the default framing (the whole catalogue, 60px padding) when no box
             could be derived at all — no spots, a failed scores fetch, a briefing that has not
             landed. ⚠️ NOT when there is no home: with no postcode `planningArea` treats every
             unmeasured region as in-area, so the box is the catalogue's own, padded 0.12° and at
             28px rather than the 60px default. That is a deliberate difference from the fallback,
             not a fallback itself, and it is written down because the first cut's comment claimed
             the opposite.

             Gated on `heat.enabled` rather than on `heatOffered`, which additionally excludes aurora
             and astro modes: `MapContainer` reads `bounds` once at construction, so a tab that
             happened to mount in aurora mode would have taken the heat framing and kept it for the
             session. */
          bounds={openingBounds || bounds}
          boundsOptions={openingBounds ? { padding: [28, 28] } : { padding: [60, 60] }}
          style={{ height: '100%', width: '100%' }}
          zoomControl
          /* Fractional zoom on the TAB only (map-tab-v2-plan.md §3 P3) — without it, every zoom
             threshold this redesign is adding (the reference layer's 11.8, later phases' 11.5/11.2/
             10.6/10.4) becomes a whole-number step instead of a gradient. `zoomSnap` is a Leaflet
             map OPTION read once at `L.map()` construction, and `overlayMode` is a prop that never
             changes across a given MapView mount, so branching on it here is safe.

             This phase touches the overlay in THREE ways, and only this one is a divergence
             between the two mounts — naming all three so a reader does not conflate them: the tile
             CLASSES below reach both mounts and are pure dress (no behaviour change either side);
             the reference layer's zoom gate (also below) reaches both mounts too, and IS a
             behaviour change on the overlay (it used to keep that layer on unconditionally) —
             deliberate and plan-sanctioned, not an oversight; and `zoomSnap` here is the one Leaflet
             construction OPTION that deliberately does NOT reach the overlay, which keeps Leaflet's
             own default (1) — the shared-component blast-radius rule (§2: gate every shared change
             behind a caller opt-in, treat any overlay behaviour diff as a review finding) applied to
             the one place this phase actually needed a fork.

             ⚠️ Written as an explicit `1`, not `overlayMode ? undefined : 0`: Leaflet's own
             `_limitZoom` reads `this.options.zoomSnap` as a bare truthy check (`if (snap) {...}`),
             and `L.Util.setOptions` copies every OWN key from the options object onto the map's
             options — including one whose value is `undefined` — so an explicit `undefined` shadows
             the prototype's default of `1` and is exactly as falsy as `0`. That would have put the
             overlay into fractional zoom too, silently. */
          zoomSnap={overlayMode ? 1 : 0}
        >
          {heatOffered && (
            /* No fallback: the field is a picture, and a spinner where a picture is loading says
               less than the map already does.

               Mounted on `heatOffered` alone (not `heatOn`) since map-tab-v2-plan.md §3 P10:
               `MapHeatLayer` is now the coastline stroke's own host too, and the stroke keeps
               drawing in BOTH the Heat and Pins views ("MapHeatLayer's pins-mode contract") — only
               the field ITSELF (and the reach rings, which read the field's own home-gated canvas)
               are withheld via `fieldEnabled={heatOn}`. Its OWN `Suspense` boundary, separate from
               `MapLabels`/`PinsLayer` below: a shared boundary would re-suspend this ALREADY
               PAINTED layer — and blank the coastline stroke it now hosts — for however long it
               takes the OTHER lazy layer's chunk to resolve on the reader's very first switch
               between Heat and Pins, which is exactly the flash-of-nothing this component's own
               `fallback={null}` choice exists to avoid. */
            <Suspense fallback={null}>
              <MapHeatLayer
                colourMode={mapColourScale}
                points={heatPoints}
                // ASTRO has no `heat.windows` entry to read a scalar off (adversarial review,
                // real #3) — `astroConfidenceScalar` is this mode's own capped-inference figure.
                conf={isAstroMode ? astroConfidenceScalar : (heatWindow?.conf ?? null)}
                markersLocked={selectedLocationName != null}
                homeCoords={homeCoords}
                rings={ringsEnabled}
                fieldEnabled={heatOn}
              />
            </Suspense>
          )}
          {/* `MapLabels` (ring labels, region names, location chips) and `PinsLayer` (the honest
              one-dot-per-location comparison) are mutually exclusive on `heatOn`/`heatPinsOn` — the
              tab's Heat/Pins segment is one view or the other — and share the SAME kind of `lazy()`
              split as `MapHeatLayer` above for the identical `d3-geo` reason (map-tab-v2-plan.md
              §3 P8/P10), but their OWN boundary: see the comment on `MapHeatLayer`'s boundary above
              for why the two must not share one. */}
          {heatOn && (
            <Suspense fallback={null}>
              <MapLabels
                spots={labelSpots}
                homeCoords={homeCoords}
                rings={ringsEnabled}
                reachMeasured={mapReachMeasured}
                selectedName={selectedLocationName}
                onSelect={selectMapLocation}
                eventLabel={mapEventLabel}
              />
            </Suspense>
          )}
          {heatPinsOn && (
            <Suspense fallback={null}>
              <PinsLayer
                spots={labelSpots}
                homeCoords={homeCoords}
                selectedName={selectedLocationName}
                onSelect={selectMapLocation}
                eventLabel={mapEventLabel}
              />
            </Suspense>
          )}
          {/* ⚠️ Gated on `!overlayMode`, NOT `heatOffered` (PR #740 review — a confirmed Codex
              finding). `heatOffered` is `Boolean(heat?.enabled) && !isAuroraMode` — false for the
              whole time an aurora window is active — but `RegionsJump`/`⌂` are mounted regardless
              of aurora mode and write ONLY `jumpFitOverride`/`heatArea`/`heatFitNonce`, which this
              controller is the SOLE reader of. Gating the controller itself on `heatOffered` meant
              a jump or a `⌂` press during an aurora window silently moved nothing (the state changed,
              nothing was mounted to act on it) — and switching back to a solar window did not
              recover it either: `HeatBoundsController`'s own `applied` ref starts `null` on every
              fresh mount, and its effect's FIRST run always records the current key as the baseline
              WITHOUT firing (by design, for the "MapContainer already opened here" race) — so a
              pending fit from while it was unmounted is adopted as "already applied" and silently
              dropped, not replayed. Every input here is already aurora-safe: `heat.spots`/
              `heat.areaSpots`/`heat.areaBounds`/`heat.catalogueBounds` come from the briefing-wide
              `heat` prop, which does not vary with the active event type, so a region jump made
              during an aurora window still resolves and fits that region's own location bounds
              exactly as it does for a solar one. The overlay keeps its own untouched machinery
              (`overlayMode` true here means this whole controller is simply absent, as before). */}
          {!overlayMode && (
            <HeatBoundsController bounds={heatBounds} nonce={heatBoundsNonce} padding={heatBoundsPadding} />
          )}
          {/* CARTO's basemaps.cartocdn.com dark_all tiles now require a registered API key
              (https://carto.com/basemaps/apikey) — anonymous requests render an
              "API KEY REQUIRED" watermark instead of the tile. Esri's Canvas basemaps stay
              free and keyless, so the dark theme is now two stacked Esri layers: an unlabelled
              base plus a reference overlay for place labels, matching what dark_all rendered
              as a single tile before.
              maxZoom is capped at 16 — Esri only renders these tiles natively that deep, and
              nothing in this app ever zooms further (lat/lon is edited via numeric fields, not
              by placing a pin on the map), so there is no feature to trade against the blur an
              upscaled zoom 17-19 would otherwise show (map-tab-v2-plan.md §4.4, decision D-6 —
              the bundle's maxNativeZoom:16/maxZoom:19 upscale is declined for now).

              `.wf-basemap-warm`/`.wf-basemap-ref` (index.css) are the two CSS filters from
              docs/design/map-tab-v2/README.md's "The basemap" section, verbatim — Leaflet's
              GridLayer adds `options.className` straight onto each tile <img>, so these are per-
              tile filters, not app theme tokens. Both TileLayers reach BOTH mounts (the tab and the
              Plan overlay) deliberately, and the CLASSES are pure tile dress: no behaviour changes
              either side, so this is not gated on `overlayMode` (map-tab-v2-plan.md §3 P3). */}
          <TileLayer
            url="https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}"
            attribution="Tiles &copy; Esri &mdash; Esri, HERE, Garmin, &copy; OpenStreetMap contributors, GIS User Community"
            maxZoom={16}
            className="wf-basemap-warm"
          />
          {/* Unmounted below REFERENCE_LAYER_MIN_ZOOM rather than merely hidden — see that
              constant's comment for why the labels come back late instead of never loading. `zoom`
              is ZoomTracker's own state (below), already read by both mounts, so this needs no new
              wiring: crossing the threshold mid-session re-renders from the next `zoomend`, exactly
              like every other zoom-gated surface in this file.

              ⚠️ Unlike the classes above, THIS gate reaching both mounts IS a behaviour change on
              the overlay, not merely dress: the reference layer used to be unconditionally on there
              too, and the overlay's own flyTo commonly lands around zoom 11 — below the threshold —
              so a reader opening it on one location can now see no town labels where it always
              showed them before. Deliberate and plan-sanctioned (§3 P3's "MapView.jsx tab+overlay
              both benefit"), reviewed and confirmed as such; recorded here so it reads as a decision
              rather than an accident on the one surface this phase's dress is not entirely inert. */}
          {zoom >= REFERENCE_LAYER_MIN_ZOOM && (
            <TileLayer
              url="https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}"
              maxZoom={16}
              className="wf-basemap-ref"
              opacity={0.6}
            />
          )}
          <ZoomTracker onZoom={setZoom} />
          {/* Map tab only. The Plan overlay is already focused on the spot the user asked about,
              and "go home" there would throw away the framing they opened it for — worse, its
              no-postcode branch opens a settings dialog on top of a modal. */}
          {!overlayMode && (
            <>
              <CentreOnHomeControl
                homeCoords={homeCoords}
                onResetScope={resetToMyArea}
                onOpenSettings={onOpenSettings}
              />
              <ZoomControlPositioner position="bottomright" />
            </>
          )}
          {overlayMode && <BoundsTracker onBounds={handleBounds} />}
          {/* Tab only — the overlay has no popover of its own to close this way (its Filters
              disclosure is a page-flow drawer, not a menu the click-away rule applies to).

              ⚠️ map-tab-v2-plan.md §3 P9's ordering rule: a background click closes the callout
              ONLY AFTER any open popover — i.e. one click closes the NEAREST layer, exactly the
              two-deep-stack idiom the rest of this app already uses for Escape (never both at
              once). `openMapMenuAtMouseDownRef` — NOT a bare closure over `openMapMenu` — is what
              makes this reliable: `WindowControl`/`FiltersPopover` close THEIR OWN menu on a
              `document`-level `mousedown` listener, which fires (and commits) BEFORE the `click`
              this controller's own handler answers, so a plain closure read at click-time already
              sees the menu as closed and the ordering collapses to "close both" — a real regression
              caught live in the browser. Snapshotting on `mousedown` (which reaches this controller
              BEFORE `document`, since `.leaflet-container` is `document`'s descendant) records the
              value while it is still trustworthy; see `MapBackgroundClickController`'s own class
              doc for the full timeline. */}
          {!overlayMode && (
            <MapBackgroundClickController
              onMouseDown={() => { openMapMenuAtMouseDownRef.current = openMapMenu; }}
              onBackgroundClick={() => {
                if (openMapMenuAtMouseDownRef.current != null) {
                  setOpenMapMenu(null);
                  return;
                }
                setSelectedLocationName(null);
              }}
            />
          )}
          <MapSizeSync trigger={overlayMode ? advancedOpen : resizeNonce} />
          <FlyToController target={flyTarget} />
          <FitBoundsController target={fitBoundsTarget} />
          <HandoffPopupController
            locationName={handoffLocationName}
            nonce={handoffNonce}
            markerRefs={markerRefs}
            overlayMode={overlayMode}
          />
          {/* Gated to the CURRENT NIGHT, not to today. The viewline is a nowcast of where the
              aurora is visible right now, so it belongs on the night in progress — which between
              midnight and dawn is yesterday's date. Comparing against the calendar date hid it on
              exactly the night the reader had come to look at.

              `nightDate`, not `date` — map-tab-v2-plan.md §3 P6's own wording: the gate is "the
              selected EV row is that night's aurora row", not a raw date compare. The two agree
              whenever the selected aurora row's date is in `forecastDates` (the ordinary case,
              which forwards through `onSelectDate` and moves `date` itself); they can only diverge
              when the EV-ownership rule kept the row local (`localNightDate`), and in exactly that
              case a raw `date` compare would have hidden the viewline on the night the reader had
              just picked. */}
          {viewlineEnabled && eventType === 'AURORA' && nightDate === auroraNight && (
            <AuroraViewlineOverlay viewline={viewline} forecastKp={auroraStatus?.forecastKp} />
          )}

          {/* Azimuth lines for the selected location. Overlay-only in Pins mode (map-tab-v2-
              plan.md §3 P10, decision D-9): they were marker-layer furniture, and the tab's new
              chip/pin vocabulary has no host for them there — the overlay (which never enters
              Pins mode at all) keeps them exactly as before. `!heatPinsOn` alone is equivalent to
              "not the tab's Pins view" here: `heatPinsOn` can only be true on the tab. */}
          {!heatPinsOn && selectedLoc && sunriseAzimuth != null && eventType === 'SUNRISE' && (
            <Polyline
              positions={[
                [selectedLoc.lat, selectedLoc.lon],
                destinationPoint(selectedLoc.lat, selectedLoc.lon, sunriseAzimuth, lineKm),
              ]}
              color={SUNRISE_LINE_COLOUR}
              weight={3}
              opacity={1}
              dashArray="10 6"
            />
          )}
          {!heatPinsOn && selectedLoc && sunsetAzimuth != null && eventType === 'SUNSET' && (
            <Polyline
              positions={[
                [selectedLoc.lat, selectedLoc.lon],
                destinationPoint(selectedLoc.lat, selectedLoc.lon, sunsetAzimuth, lineKm),
              ]}
              color={SUNSET_LINE_COLOUR}
              weight={3}
              opacity={1}
              dashArray="10 6"
            />
          )}

          <MarkerClusterGroup
            ref={clusterGroupRef}
            /* No remount key here: `scoreRamp` is the only colour language now, so switching the
               view (Heat ↔ Pins on the tab, Heat ↔ Medallions on the overlay) never changes which
               palette a cluster bubble paints on, and `iconCreateFunction` never needs to re-run
               for that reason. Consequence, kept deliberately: an open popup, a spiderfied cluster
               and the selected marker now survive the toggle (pinned below) rather than being torn
               down and rebuilt. This group itself stays mounted through the tab's Pins mode too
               (map-tab-v2-plan.md §3 P10) — `MapHeatLayer`'s `fieldEnabled={false}` contract holds
               the marker panes fully hidden there instead of unmounting this component, which is
               what keeps that same "never torn down" property. */
            chunkedLoading
            iconCreateFunction={(cluster) => createClusterIcon(cluster, role)}
            // Dense corridors (e.g. Hadrian's Wall — 7 spots in a few km) must
            // collapse to one count-only bubble until zoomed in far enough that the
            // discs no longer collide. A wider radius merges co-located spots; a
            // higher disable-zoom keeps them clustered until street-level.
            maxClusterRadius={80}
            disableClusteringAtZoom={13}
            showCoverageOnHover={false}
            spiderfyOnMaxZoom
            zoomToBoundsOnClick
            animate
          >
            {visibleLocations.map((loc) => {
              const { forecast, hourlyData, isPureWildlife, isWaterfall, excludeFromSkyCluster }
                = getContentProps(loc);
              const locAuroraScore = isAuroraMode ? (auroraScores[loc.name] ?? null) : null;
              // Look up briefing evaluation score for this location (if any)
              const briefingScore = !isAuroraMode ? lookupBriefingScore(briefingScoreIndex, loc.name, date, eventType) : null;
              const markerRating = isAuroraMode
                ? (locAuroraScore?.stars ?? null)
                : (briefingScore?.rating ?? forecast?.rating ?? null);
              const markerFiery = (!isAuroraMode && role !== 'LITE_USER')
                ? (briefingScore?.fierySkyPotential ?? forecast?.fierySkyPotential ?? null)
                : null;
              const markerGolden = (!isAuroraMode && role !== 'LITE_USER')
                ? (briefingScore?.goldenHourPotential ?? forecast?.goldenHourPotential ?? null)
                : null;
              const isStandDown = !isAuroraMode && !isPureWildlife
                && resolveStandDown(briefingScore, forecast);
              // Drill-down emphasis is overlay-only: `emphasiseLocationName` is set solely by
              // the Plan-tab map overlay, never by the Map tab (which passes the same handoff
              // for its escape-hatch landing and must keep every pin equal).
              //
              // Gated on the target actually surviving the filters. Nothing relaxes the star or
              // stand-down thresholds for a handoff, so a drill-down to a 2-star spot on a 3-star
              // map leaves no pin to focus — and muting every remaining marker would dim the whole
              // overlay to 40% in deference to a pin that is not there.
              const emphasis = emphasisTarget
                ? (loc.name === emphasisTarget ? 'focus' : 'muted')
                : null;
              const icon = makeMarkerIcon(
                markerRating,
                markerFiery,
                markerGolden,
                loc.name,
                isPureWildlife,
                excludeFromSkyCluster,
                isStandDown,
                emphasis,
              );

              return (
                <Marker
                  // Stable per-location key: names are unique, so the marker (and its Leaflet
                  // instance) is never remounted by a drive-time load or a filter/zoom change —
                  // driveMinutes flows through as a prop, so no remount is needed to refresh it.
                  key={loc.name}
                  position={[loc.lat, loc.lon]}
                  icon={icon}
                  ref={(m) => {
                    if (m) markerRefs.current.set(loc.name, m);
                    else markerRefs.current.delete(loc.name);
                  }}
                  eventHandlers={{
                    click: () => setSelectedLocationName(loc.name),
                    ...(isMobile ? {} : {
                      popupclose: () => { setSelectedLocationName(null); void 0; },
                    }),
                  }}
                >
                  {/* map-tab-v2-plan.md §3 P9: the TAB stops mounting a Leaflet `Popup` for markers
                      at all — `MapCallout` is the tab's selection surface now. `MarkerPopupContent`
                      remains the OVERLAY's renderer, untouched. */}
                  {!isMobile && overlayMode && (
                    <Popup maxWidth={9999} autoPanPadding={[20, 60]}>
                      <PopupResizer deps={[date, eventType]} />
                      <div key={`${date}-${eventType}`} className="animate-popup-refresh">
                        <MarkerPopupContent
                          location={loc}
                          forecast={forecast}
                          briefingScore={briefingScore}
                          hourlyData={hourlyData}
                          eventType={isAuroraMode || isAstroMode ? 'SUNSET' : eventType}
                          isPureWildlife={isPureWildlife}
                          showComfortRows={isWaterfall}
                          role={role}
                          date={date}
                          travelDay={isTravelDayForDate}
                          driveMinutes={driveMinutesFor(loc.id)}
                          onTideFetchedAt={(ts) => setTideFetchedAt((prev) => ({ ...prev, [loc.name]: ts }))}
                          tideFetchedAt={tideFetchedAt[loc.name] ?? null}
                          onTideClassification={(cls) => setTideClassifications((prev) => ({ ...prev, [loc.name]: cls }))}
                          tideClassification={tideClassifications[loc.name] ?? null}
                          auroraScore={auroraScores[loc.name] ?? null}
                          isAuroraMode={isAuroraMode}
                          astroScore={astroScores[loc.name] ?? null}
                          isAstroMode={isAstroMode}
                          onForecastRun={onForecastRun}
                        />
                      </div>
                    </Popup>
                  )}
                </Marker>
              );
            })}
          </MarkerClusterGroup>

          {/* The selection callout (map-tab-v2-plan.md §3 P9, README §7) — tab only. Rendered
              regardless of `heatOn`/`heatOffered`: a marker click sets `selectedLocationName`
              whether or not the heat kernel is currently painting anything, and the callout is the
              tab's ONLY selection surface now (no Leaflet popup left to fall back to). */}
          {!overlayMode && selectedLoc && activeMapEvent && (
            <MapCallout
              location={selectedLoc}
              rating={getRatingForLocation(selectedLoc)}
              event={activeMapEvent}
              driveMinutes={driveMinutesFor(selectedLoc.id)}
              distanceMiles={distanceMilesFor(selectedLoc.id)}
              scoreIndex={scoreIndex}
              scoresKnown={scoresKnown}
              regionGlossIndex={regionGlossIndex}
              evRows={mapEvents}
              astroConditionsByDate={astroConditionsByDate}
              auroraResultsByDate={auroraResultsByDate}
              onSelectEv={selectEvRow}
              onOpenInPlan={handleOpenLocationInPlan}
              onClose={() => setSelectedLocationName(null)}
            />
          )}
        </MapContainer>

        {overlayMode ? (
          <>
            {/* The heat toolbar: view, framing, window and the ramp's key. NEVER actually renders
                in the overlay — `heatOffered` is always false there (the overlay is never handed
                `heat`) — but the branch is kept byte-identical to before P7 rather than deleted,
                since it is the one thing standing between the overlay and a silent behaviour
                change if a future caller ever DID hand it a `heat` prop. */}
            {heatOffered && (
              <div data-testid="wf-map-toolbar" className="wf-map-toolbar">
                <div className="wf-map-toolbar-row">
                  <div className="wf-seg" role="group" aria-label="Map view">
                    <button
                      type="button"
                      data-testid="wf-map-view-heat"
                      aria-pressed={heatView === 'heat'}
                      onClick={() => setHeatView('heat')}
                      className={`wf-seg-btn${heatView === 'heat' ? ' on' : ''}`}
                    >
                      Heat
                    </button>
                    <button
                      type="button"
                      data-testid="wf-map-view-medallions"
                      aria-pressed={heatView === 'medallions'}
                      onClick={() => setHeatView('medallions')}
                      className={`wf-seg-btn${heatView === 'medallions' ? ' on' : ''}`}
                    >
                      <span aria-hidden="true">◍ </span>
                      Medallions
                    </button>
                  </div>
                  {heat.hasHome && (
                    <div className="wf-seg" role="group" aria-label="Map area">
                      <button
                        type="button"
                        data-testid="wf-map-area-home"
                        aria-pressed={heatArea}
                        onClick={() => { setHeatArea(true); setHeatFitNonce((n) => n + 1); }}
                        className={`wf-seg-btn${heatArea ? ' on' : ''}`}
                      >
                        <span aria-hidden="true">◎ </span>
                        {heat?.areaLabel || 'My area'}
                      </button>
                      <button
                        type="button"
                        data-testid="wf-map-area-all"
                        aria-pressed={!heatArea}
                        onClick={() => { setHeatArea(false); setHeatFitNonce((n) => n + 1); }}
                        className={`wf-seg-btn${heatArea ? '' : ' on'}`}
                      >
                        Whole catalogue
                      </button>
                    </div>
                  )}
                </div>
                {windowUnscored && (
                  <div data-testid="wf-map-heat-unscored" className="wf-map-key">
                    This window is not scored
                  </div>
                )}
                {heatOn && !windowUnscored && (
                  <div
                    data-testid="wf-map-heat-legend"
                    className="wf-map-key"
                    role="img"
                    aria-label="Colour key: the field runs from Poor to Worth it"
                  >
                    <span aria-hidden="true">Poor</span>
                    <span
                      aria-hidden="true"
                      data-testid="wf-map-heat-legend-ramp"
                      className="wf-map-key-ramp"
                      style={{ background: rampGradientCss() }}
                    />
                    <span aria-hidden="true">Worth it</span>
                  </div>
                )}
              </div>
            )}

            {showColourScaleNotice && (
              <div
                data-testid="colour-scale-notice"
                className="absolute top-2 right-2 z-[1200] bg-plex-surface/80 backdrop-blur-sm
                  text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30 flex items-center gap-2"
                style={{ fontSize: '11px' }}
              >
                Colours now run cold to hot.
                <button
                  data-testid="colour-scale-notice-dismiss"
                  onClick={dismissColourScaleNotice}
                  className="text-plex-text-muted hover:text-plex-text transition-colors"
                  aria-label="Dismiss"
                >
                  ✕
                </button>
              </div>
            )}

            {showViewlineUpsell && (
              <div
                data-testid="viewline-upsell-chip"
                className="absolute bottom-2 left-2 z-[1000] bg-plex-surface/80 backdrop-blur-sm
                  text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30 flex items-center gap-2"
                style={{ fontSize: '11px' }}
              >
                Aurora viewline available — upgrade to Pro
                <button
                  data-testid="viewline-upsell-dismiss"
                  onClick={() => setViewlineUpsellDismissed(true)}
                  className="text-plex-text-muted hover:text-plex-text transition-colors"
                  aria-label="Dismiss"
                >
                  ✕
                </button>
              </div>
            )}

            {!isAuroraMode && !isAstroMode && briefingScores.size > 0 && (() => {
              const suffix = `|${date}|${eventType}|`;
              for (const key of briefingScores.keys()) {
                if (key.includes(suffix)) {
                  return (
                    <div
                      data-testid="photocast-scored-legend"
                      className="absolute bottom-2 left-1/2 -translate-x-1/2 z-[1000] bg-plex-surface/80 backdrop-blur-sm
                        text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30"
                      style={{ fontSize: '11px' }}
                    >
                      ★ PhotoCast-scored locations shown
                    </div>
                  );
                }
              }
              return null;
            })()}
          </>
        ) : (
          <>
            {/* ── Full-frame map chrome (map-tab-v2-plan.md §3 P7/P10/P11) ──
                Every corner is claimed exactly once, per the plan's z-ladder (index.css): chrome
                1100, menus 1500 (a menu must beat every other chip so its own dropdown/panel is
                never hidden under a sibling). Window control top-left (clearing Leaflet's own
                zoom + home control stack the same way the old toolbar did); Regions + Heat/Pins +
                Filters top-right, in the README's own top-to-bottom order; colour-scale notice
                top-centre (the top corners are now both claimed by chrome); the Legend chip
                bottom-left (P10 — hidden in Pins mode, shares its corner with the LITE
                viewline-upsell chip, which never coexists with it); scored-locations chip
                bottom-right; counts footer bottom-centre. */}
            <div className="wf-map-chrome-tl" data-testid="wf-map-chrome-tl">
              {windowControl}
            </div>

            <div className="wf-map-chrome-tr" data-testid="wf-map-chrome-tr">
              <RegionsJump
                open={openMapMenu === 'jump'}
                onOpenChange={(next) => setOpenMapMenu(next ? 'jump' : null)}
                rows={jumpRows}
                onSelectRegion={jumpToRegion}
              />
              {heatOffered && (
                <div data-testid="wf-map-toolbar" className="wf-map-toolbar-cluster">
                  <div className="wf-map-toolbar-row">
                    <div className="wf-seg" role="group" aria-label="Map view">
                      <button
                        type="button"
                        data-testid="wf-map-view-heat"
                        aria-pressed={heatView === 'heat'}
                        onClick={() => setHeatView('heat')}
                        className={`wf-seg-btn${heatView === 'heat' ? ' on' : ''}`}
                      >
                        Heat
                      </button>
                      <button
                        type="button"
                        data-testid="wf-map-view-pins"
                        aria-pressed={heatView === 'pins'}
                        onClick={() => setHeatView('pins')}
                        className={`wf-seg-btn${heatView === 'pins' ? ' on' : ''}`}
                      >
                        <span aria-hidden="true">◍ </span>
                        Pins
                      </button>
                    </div>
                  </div>
                  {/* The ramp's key. Only in heat view — in Pins view it would explain a ramp
                      nothing on screen is painted with (the field itself is withheld there, though
                      `MapHeatLayer` stays mounted for the coastline stroke — see the mount comment
                      above). "This window is not scored" REPLACES the key rather than sitting
                      beside it, for the same reason. */}
                  {windowUnscored && (
                    <div data-testid="wf-map-heat-unscored" className="wf-map-key">
                      This window is not scored
                    </div>
                  )}
                  {heatOn && !windowUnscored && (
                    <div
                      data-testid="wf-map-heat-legend"
                      className="wf-map-key"
                      role="img"
                      aria-label="Colour key: the field runs from Poor to Worth it"
                    >
                      <span aria-hidden="true">Poor</span>
                      <span
                        aria-hidden="true"
                        data-testid="wf-map-heat-legend-ramp"
                        className="wf-map-key-ramp"
                        style={{ background: rampGradientCss() }}
                      />
                      <span aria-hidden="true">Worth it</span>
                    </div>
                  )}
                </div>
              )}
              <FiltersPopover
                open={openMapMenu === 'filters'}
                onOpenChange={(next) => setOpenMapMenu(next ? 'filters' : null)}
                minStars={minStars}
                onSelectMinStars={handleMinStarsClick}
                activeTypeFilters={activeTypeFilters}
                onToggleType={toggleTypeFilter}
                subjectChips={MAP_FILTER_CHIPS}
                seasonalFeatures={seasonalFeatures}
                role={role}
                driveTimeFilter={driveTimeFilter}
                onSelectDriveTime={setDriveTimeFilter}
                darkSkyFilter={darkSkyFilter}
                onToggleDarkSky={() => setDarkSkyFilter((v) => !v)}
                darkSkyThreshold={DARK_SKY_THRESHOLD}
                hasHome={Boolean(heat?.hasHome)}
                heatArea={heatArea}
                // `next === true` ("My area") is `resetToMyArea` itself — the SAME function `⌂`
                // calls (map-tab-v2-plan.md §3 P11's reconciliation) — so the two controls can never
                // disagree about what resetting scope means. `next === false` ("Whole catalogue")
                // clears any standing jump override for the identical reason: a stale region fit
                // must not survive the reader's own later choice to widen scope.
                onSelectScope={(next) => {
                  if (next) { resetToMyArea(); return; }
                  setJumpFitOverride(null);
                  setHeatArea(false);
                  setHeatFitNonce((n) => n + 1);
                }}
                areaLabel={heat?.areaLabel}
                isAuroraMode={isAuroraMode}
                isAstroMode={isAstroMode}
                showAdminRow={role === 'ADMIN' && !isAuroraMode && !isAstroMode}
                showStandDown={showStandDown}
                onToggleStandDown={toggleShowStandDown}
                hasStandDown={hasStandDown}
                showUnrated={showUnrated}
                onToggleUnrated={toggleShowUnrated}
                hasUnrated={hasUnrated}
                activeCount={filterActiveCount}
                filteredCount={scopedVisibleLocations.length}
                scopeCount={scopeBasePool.length}
                onClearAll={clearAllMapFilters}
              />
            </div>

            {showColourScaleNotice && (
              <div
                data-testid="colour-scale-notice"
                className="absolute top-2 left-1/2 -translate-x-1/2 z-[1100] bg-plex-surface/80 backdrop-blur-sm
                  text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30 flex items-center gap-2"
                style={{ fontSize: '11px' }}
              >
                Colours now run cold to hot.
                <button
                  data-testid="colour-scale-notice-dismiss"
                  onClick={dismissColourScaleNotice}
                  className="text-plex-text-muted hover:text-plex-text transition-colors"
                  aria-label="Dismiss"
                >
                  ✕
                </button>
              </div>
            )}

            {/* Bottom-left chrome (map-tab-v2-plan.md §3 P10) — the LITE viewline-upsell chip and
                the Legend chip STACK here rather than one suppressing the other. ⚠️ They are NOT
                mutually exclusive: `showViewlineUpsell` is keyed on `auroraStatus`'s ALERT LEVEL
                (a background poll, live regardless of which event type is on screen), while the
                Legend chip is keyed on `heatView`/`heatOffered`, which excludes AURORA MODE
                (`eventType === 'AURORA'`) specifically — and a LITE reader can never enter aurora
                mode in the first place (`viewlineEnabled` above is PRO/ADMIN-only), so an alert can
                fire while a LITE reader sits on an ordinary Heat-view sunset. An earlier revision
                of this comment conflated those two different "aurora" axes and claimed the chips
                never coexist; a live browser pass proved otherwise (adversarial review C1/C3).
                Both chips are plain flex children of ONE positioned wrapper now — neither carries
                its own `absolute` placement any more — so they can only ever stack with a gap,
                never overlap. */}
            {(showViewlineUpsell || (heatOffered && heatView === 'heat' && !isMobile)) && (
              <div className="wf-map-chrome-bl" data-testid="wf-map-chrome-bl">
                {showViewlineUpsell && (
                  <div
                    data-testid="viewline-upsell-chip"
                    className="bg-plex-surface/80 backdrop-blur-sm
                      text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30 flex items-center gap-2"
                    style={{ fontSize: '11px' }}
                  >
                    Aurora viewline available — upgrade to Pro
                    <button
                      data-testid="viewline-upsell-dismiss"
                      onClick={() => setViewlineUpsellDismissed(true)}
                      className="text-plex-text-muted hover:text-plex-text transition-colors"
                      aria-label="Dismiss"
                    >
                      ✕
                    </button>
                  </div>
                )}
                {heatOffered && heatView === 'heat' && !isMobile && (
                  <MapLegendPanel
                    open={openMapMenu === 'legend'}
                    onOpenChange={(next) => setOpenMapMenu(next ? 'legend' : null)}
                    handoverFraction={legendHandoverFraction}
                    ringsEnabled={ringsEnabled}
                    onToggleRings={() => setRingsEnabled((v) => !v)}
                    hasHome={hasHomeCoords}
                    reachMeasured={mapReachMeasured}
                  />
                )}
              </div>
            )}

            {!isAuroraMode && !isAstroMode && briefingScores.size > 0 && (() => {
              const suffix = `|${date}|${eventType}|`;
              for (const key of briefingScores.keys()) {
                if (key.includes(suffix)) {
                  return (
                    // `right-[54px]`, not `right-2`: Leaflet's own bottom-right corner stack now
                    // holds the zoom control PLUS `CentreOnHomeControl` (map-tab-v2-plan.md §3 P7
                    // moved both there from top-left), so this chip sits BESIDE that column rather
                    // than under it — the same "clear the native stack" reasoning the old top-left
                    // toolbar's `left: 60px` already used, mirrored to the opposite corner. The
                    // exact clearance is a browser-verified concern like every other pixel offset
                    // in this class (CLAUDE.md: a CSS claim is a browser claim).
                    <div
                      data-testid="photocast-scored-legend"
                      // `wf-map-scored-legend` is a pure CSS hook (PR #741 review) — the phone
                      // media query lifts this clear of the new bottom bar the same way it lifts
                      // `.wf-map-chrome-bl`; every Tailwind class above it is unmodified.
                      className="absolute bottom-2 right-[54px] z-[1100] bg-plex-surface/80 backdrop-blur-sm
                        text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30 wf-map-scored-legend"
                      style={{ fontSize: '11px' }}
                    >
                      ★ PhotoCast-scored locations shown
                    </div>
                  );
                }
              }
              return null;
            })()}

            {/* Counts footer (README "§9 Count footer") — bottom-centre, the one thing on this
                chrome that reports on the CATALOGUE rather than controlling it. Withheld entirely
                without a catalogue at all (`heatOffered` false), so a fresh install with nothing
                scored yet shows no footer rather than "0 named · 0 rated of 0". */}
            {heatOffered && (
              <div data-testid="wf-map-counts-footer" className="wf-map-counts-footer">
                <span>
                  <b>{scopedVisibleLocations.length}</b> named &middot; {scopedVisibleLocations.length} rated of {scopeBasePool.length}
                  {filterActiveCount > 0 && (
                    <span data-testid="wf-map-counts-filtered" className="wf-map-counts-flag"> filtered</span>
                  )}
                </span>
                {countsSecondLine && (
                  <span data-testid="wf-map-counts-second" className="wf-map-counts-second">{countsSecondLine}</span>
                )}
              </div>
            )}
          </>
        )}
      </div>

      {/* Mobile bottom sheet — the OVERLAY's own phone marker popup. map-tab-v2-plan.md §3 P9: the
          TAB stops mounting this for markers too; `MapCallout` (rendered inside `MapContainer`
          above) is its selection surface on every viewport now, the 266px collapsed-strip phone
          treatment being P12's polish rather than a gate on whether the callout appears at all. */}
      {overlayMode && isMobile && selectedLocationName && (() => {
        const loc = visibleLocations.find((l) => l.name === selectedLocationName);
        if (!loc) return null;
        const { forecast, hourlyData, isPureWildlife, isWaterfall } = getContentProps(loc);
        const briefingScore = !isAuroraMode ? lookupBriefingScore(briefingScoreIndex, loc.name, date, eventType) : null;
        return (
          <BottomSheet
            open
            // The sheet is the phone's marker popup, so its name is the place it describes.
            label={loc.name}
            onClose={() => { setSelectedLocationName(null); void 0; }}
          >
            <div key={`${date}-${eventType}`} className="animate-popup-refresh">
              <MarkerPopupContent
                location={loc}
                forecast={forecast}
                briefingScore={briefingScore}
                hourlyData={hourlyData}
                eventType={isAuroraMode || isAstroMode ? 'SUNSET' : eventType}
                isPureWildlife={isPureWildlife}
                showComfortRows={isWaterfall}
                role={role}
                date={date}
                travelDay={isTravelDayForDate}
                driveMinutes={driveMinutesFor(loc.id)}
                onTideFetchedAt={(ts) => setTideFetchedAt((prev) => ({ ...prev, [loc.name]: ts }))}
                tideFetchedAt={tideFetchedAt[loc.name] ?? null}
                onTideClassification={(cls) => setTideClassifications((prev) => ({ ...prev, [loc.name]: cls }))}
                tideClassification={tideClassifications[loc.name] ?? null}
                auroraScore={auroraScores[loc.name] ?? null}
                isAuroraMode={isAuroraMode}
                astroScore={astroScores[loc.name] ?? null}
                isAstroMode={isAstroMode}
                onForecastRun={onForecastRun}
              />
            </div>
          </BottomSheet>
        );
      })()}
    </div>
  );
}

MapView.propTypes = {
  locations: PropTypes.arrayOf(
    PropTypes.shape({
      name: PropTypes.string.isRequired,
      lat: PropTypes.number.isRequired,
      lon: PropTypes.number.isRequired,
      forecastsByDate: PropTypes.instanceOf(Map).isRequired,
      locationType: PropTypes.arrayOf(PropTypes.string),
    })
  ).isRequired,
  date: PropTypes.string,
  /**
   * Asks the parent to move the selected date. Used to land on the aurora night when aurora mode
   * is entered, and — since map-tab-v2-plan.md §3 P6 — by the window control whenever a picked EV
   * row's date is in {@code forecastDates}; the parent stays the owner of the date and may ignore
   * one not on the strip.
   */
  onSelectDate: PropTypes.func,
  /**
   * Every date `GET /api/forecast` returned (`WindowFirstMapPane`'s `dates`/App's `allDates`) —
   * the map's own full browsable domain, wider than the briefing's rendered horizon. Feeds two
   * things (map-tab-v2-plan.md §3 P6): `utils/mapEvents.js`'s D-13 beyond-briefing solar rows, and
   * the EV-ownership rule deciding whether a picked row's date may be forwarded via
   * `onSelectDate` at all. Empty on the overlay, which never mounts the window control.
   */
  forecastDates: PropTypes.arrayOf(PropTypes.string),
  autoEventType: PropTypes.string,
  handoffEventType: PropTypes.string,
  handoffFilterAction: PropTypes.string,
  handoffDarkSky: PropTypes.bool,
  handoffLocationName: PropTypes.string,
  emphasiseLocationName: PropTypes.string,
  handoffRegion: PropTypes.string,
  handoffNonce: PropTypes.number,
  briefingScores: PropTypes.instanceOf(Map),
  onForecastRun: PropTypes.func,
  seasonalFeatures: PropTypes.arrayOf(PropTypes.string),
  focus: PropTypes.shape({
    points: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
    names: PropTypes.arrayOf(PropTypes.string),
    nonce: PropTypes.number,
  }),
  /**
   * True when this map is the Plan tab's drill-down overlay rather than the full Map tab. The
   * overlay inherits its event and location from the card that opened it, so it opens with the
   * filters folded away behind a one-line context bar and gives the height to the map.
   */
  overlayMode: PropTypes.bool,
  /**
   * Bumped by a caller whose map lives in a container that can change size while the map is not
   * looking — currently the Map pane, whose panel is `display: none` between visits.
   */
  resizeNonce: PropTypes.number,
  /**
   * The heat field's opt-in. Default `null` — the Plan overlay passes nothing, deliberately: it
   * opens focused on one spot from a card that has already answered the question, and a field and
   * toolbar over a modal would be a second plan. `MapViewHeat.test.jsx` pins the overlay mount.
   */
  heat: PropTypes.shape({
    enabled: PropTypes.bool,
    hasHome: PropTypes.bool,
    spots: PropTypes.array,
    areaSpots: PropTypes.array,
    pointsByKey: PropTypes.instanceOf(Map),
    windows: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string,
      date: PropTypes.string,
      targetType: PropTypes.string,
      label: PropTypes.string,
      time: PropTypes.string,
      /** The window's served best rating — null means nothing in it is rated. */
      bestRating: PropTypes.number,
      conf: PropTypes.number,
      /** map-tab-v2-plan.md §3 P6 — the resolved confidence tier `utils/mapEvents.js` reads. */
      confidenceTier: PropTypes.oneOf(['high', 'medium', 'low']),
      /** The window's served topic badges — the window control's dropdown reads these directly. */
      badges: PropTypes.array,
    })),
    areaBounds: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
    catalogueBounds: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
    /**
     * Drive minutes keyed by location id, measured from wherever the caller is planning from.
     * Present, it REPLACES the per-user home times outright — see `driveMinutesFor`. Absent, this
     * component behaves exactly as it did before the field existed.
     */
    driveOverrideById: PropTypes.instanceOf(Map),
    /** What to call the framed area. Defaults to "My area"; an away origin names its base town. */
    areaLabel: PropTypes.string,
    /**
     * Region names beyond the {@code GLANCE_MINUTES} threshold, home-origin only (map-tab-v2-plan.md
     * §3 P7) — the counts footer's "Beyond {@code N}h: …" second line. `[]` when there is nothing
     * beyond, or when planning from an away origin (a single-region scope has nothing to be
     * "beyond").
     */
    beyondRegionNames: PropTypes.arrayOf(PropTypes.string),
  }),
  /** `{ lat, lon }` of the user's saved home postcode, or null when none is saved. */
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  /** Opens the settings dialog on the postcode field. */
  onOpenSettings: PropTypes.func,
  /**
   * A memo-busting signal only — its value is never read. Pass the current `scoreRamp` mode (any
   * changed value forces this `React.memo`'d component to actually re-render) when this mount can
   * outlive a live switch, as the Map pane's does; omit it for a mount that always starts fresh,
   * such as the Plan-tab overlay. The colour itself always comes straight from `scoreRamp`'s own
   * live state (`rampHex`, `getMode()`), never from this prop.
   */
  mapColourScale: PropTypes.oneOf(['temp', 'verdict']),
  /**
   * Whether the loaded `mapColourScale` preference was never explicitly chosen — the signal the
   * one-time "colours changed" notice needs to tell a defaulted reader apart from one who picked
   * either scale on purpose. `false` (the safe, no-notice default) until the caller's own settings
   * fetch resolves.
   */
  colourScaleDefaulted: PropTypes.bool,
  /**
   * From `utils/locationSheet.buildScoreIndex` over `WindowFirstBriefingContext`'s `scoreRows` —
   * the selection callout's reason prose and "every window" strip (map-tab-v2-plan.md §3 P9). Tab
   * only; the overlay never selects a location through the callout.
   */
  scoreIndex: PropTypes.object,
  /** Whether the ratings response `scoreIndex` is built from has actually landed — an unfetched
   * response is not evidence that nothing was rated (the same rule `scoresLoaded` states everywhere
   * else it is read). */
  scoresKnown: PropTypes.bool,
  /** From `utils/mapCallout.buildRegionGlossIndex` — the callout's reason-prose fallback. */
  regionGlossIndex: PropTypes.object,
  /**
   * From `utils/regionsJump.buildRegionBestIndex` — the Regions jump list's per-window "best score"
   * join (map-tab-v2-plan.md §3 P11), the served `BriefingRegion.bestRating` keyed by
   * `date|targetType|regionName`.
   */
  regionBestIndex: PropTypes.instanceOf(Map),
  /**
   * The per-user HOME reach map (`{driveMinutes, distanceMiles}`), read ONLY for the callout's
   * straight-line miles fact — see this component's own prop-block comment above `function MapView`
   * for why it is separate from `heat.driveOverrideById`.
   */
  reachById: PropTypes.instanceOf(Map),
  /**
   * The callout's "Open in Plan" action — `(spot: {id, name, regionName}) => void`, the real shell
   * handoff (`App.jsx`'s `openLocationInPlan`).
   */
  onOpenLocationInPlan: PropTypes.func,
};

export default React.memo(MapView);
