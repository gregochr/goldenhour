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
import { resolveAuroraNight, ukDateStr } from '../utils/mapDates.js';
import { LOCATION_TYPE_META, DISPLAY_TYPES, locationTypeLabel, SKY_SUBJECT_TYPES } from '../utils/locationTypes.js';
import AuroraViewlineOverlay from './AuroraViewlineOverlay.jsx';
import { rampHex, RAMP_STOPS } from '../utils/scoreRamp.js';

/**
 * The heat field, behind a `lazy()` boundary — and the boundary is load-bearing, not tidiness.
 *
 * <p>`MapHeatLayer` statically imports the kernel, which statically imports `d3-geo`. `MapView` is
 * mounted three times (the v1 Map tab, the v2 pane, the Plan overlay) and only ONE of them passes
 * `heat`; a static import here would put the `geo` chunk on the network for the v1 arm, which is
 * the pilot's frozen comparison control. Nothing is fetched until something renders the layer, and
 * only the opted-in caller can.
 */
const MapHeatLayer = lazy(() => import('./MapHeatLayer.jsx'));

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
 */
function HeatBoundsController({ bounds, nonce }) {
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
    map.fitBounds(bounds, { padding: [28, 28], animate: false });
    // Keyed on the composed value, not on the bounds ARRAY identity, which is rebuilt on every poll.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return null;
}

HeatBoundsController.propTypes = {
  bounds: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
  nonce: PropTypes.number,
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
import { buildMarkerSvg, buildStandDownSvg, markerLabelAndColour, createClusterIcon, RATING_COLOURS, STAND_DOWN_COLOUR } from './markerUtils.js';

const SUNRISE_LINE_COLOUR = '#f97316';
const SUNSET_LINE_COLOUR  = '#a855f7';

/** Bortle class at or below which a location counts as "dark sky" (aurora/astro suitable). */
const DARK_SKY_THRESHOLD = 4;

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
 * Invisible component that tracks map zoom and calls onZoom when it changes.
 */
function ZoomTracker({ onZoom }) {
  useMapEvents({ zoomend: (e) => onZoom(e.target.getZoom()) });
  return null;
}

ZoomTracker.propTypes = {
  onZoom: PropTypes.func.isRequired,
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
 * Keeps Leaflet's idea of its own size in step with a container whose height is animating.
 *
 * <p>The overlay's map grows and shrinks as the filter drawer opens and closes. Leaflet caches the
 * container size and only recomputes it on `invalidateSize`, so without this the tiles keep the
 * old geometry: grey bands at the bottom on a grow, and a centre that has silently moved on a
 * shrink. Polled across the transition rather than fired once at the end, because a single call at
 * `transitionend` leaves the map wrong for the whole 240ms the user is watching it move.
 *
 * <p><b>The window-first Map tab needs the same thing for a different reason.</b> Its panel is
 * hidden with `display: none` rather than unmounted, so a viewport change while the reader is on
 * another tab — a phone rotating, most obviously — leaves Leaflet holding a size for a container
 * that no longer has it, and the map paints grey on return. That pane bumps `resizeNonce` from a
 * `ResizeObserver`; `enabled` therefore keys on EITHER caller having asked, so a `MapView` given
 * neither (the v1 Map tab) behaves exactly as before.
 */
function MapSizeSync({ trigger, enabled }) {
  const map = useMap();
  useEffect(() => {
    if (!enabled || typeof map?.invalidateSize !== 'function') return undefined;
    const tick = setInterval(() => map.invalidateSize({ animate: false }), 60);
    const stop = setTimeout(() => clearInterval(tick), 340);
    return () => { clearInterval(tick); clearTimeout(stop); };
  }, [map, trigger, enabled]);
  return null;
}

MapSizeSync.propTypes = {
  trigger: PropTypes.any,
  enabled: PropTypes.bool,
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
function HandoffPopupController({ locationName, nonce, markerRefs }) {
  const map = useMap();
  useEffect(() => {
    if (!locationName || typeof map.once !== 'function') return undefined;
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
  }, [locationName, nonce, map, markerRefs]);
  return null;
}

HandoffPopupController.propTypes = {
  locationName: PropTypes.string,
  nonce: PropTypes.number,
  markerRefs: PropTypes.shape({ current: PropTypes.instanceOf(Map) }).isRequired,
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

function makeMarkerIcon(rating, fierySky, goldenHour, locationName, isPureWildlife = false, excludeFromCluster = false, isStandDown = false, emphasis = null, ramp = false) {
  // `emphasis` is part of the key: a DivIcon is cached by everything that determines it, and
  // the className carries the focus/muted modifier. Omitting it would serve the Map tab's
  // plain icon to the overlay (or worse, leak the overlay's muted icon back to the Map tab).
  //
  // ⚠️ `ramp` is part of it for the same reason and a sharper one: this cache is MODULE-level and
  // shared by all three mounts. Leave it out and toggling Heat → Medallions serves back the icons
  // the ramp built, so the "before" view would silently render the after view's colours — the one
  // comparison the medallion view exists to make.
  const cacheKey = `${locationName}|${rating}|${fierySky}|${goldenHour}|${isPureWildlife ? 1 : 0}|${excludeFromCluster ? 1 : 0}|${isStandDown ? 1 : 0}|${emphasis ?? '-'}|${ramp ? 'r' : '-'}`;
  const cached = markerIconCache.get(cacheKey);
  if (cached) return cached;

  const { label, colour } = markerLabelAndColour(rating, fierySky, goldenHour, isPureWildlife, ramp);

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
 * Framing constants for "centre on home".
 *
 * <p>The zoom is DERIVED rather than fixed, from the user's own Close-to-home radius and the map's
 * live pixel size: "show me home" means "show me the area I said was local", and that radius is a
 * per-user setting. A hardcoded zoom would frame 30 miles for someone who set 15 and crop someone
 * who set 45. The fallbacks below cover the two things that can be missing — a radius that was
 * never chosen, and a map that cannot report its size yet.
 */
const DEFAULT_HOME_RADIUS_MILES = 30;
const FALLBACK_HOME_ZOOM = 9;
const METRES_PER_MILE = 1609.34;
/** Web-Mercator ground resolution at zoom 0, in metres per pixel at the equator. */
const EQUATOR_M_PER_PX_Z0 = 156543.03392;

/**
 * The zoom at which `radiusMiles` around `lat` fits inside the map's shorter axis.
 *
 * <p>Floored, not rounded: rounding up would frame slightly less than the radius asked for, which
 * is the one direction that makes the control lie about what it is showing.
 *
 * @param {Object}  map         the Leaflet map
 * @param {number}  lat         latitude of the centre, for the Mercator scale factor
 * @param {?number} radiusMiles the user's local radius, or null for the default
 * @returns {number} a Leaflet zoom level, clamped to what the map allows
 */
function zoomForHomeRadius(map, lat, radiusMiles) {
  const size = map?.getSize?.();
  const halfPx = size ? Math.min(size.x, size.y) / 2 : 0;
  if (!halfPx) return FALLBACK_HOME_ZOOM;
  const metres = (radiusMiles ?? DEFAULT_HOME_RADIUS_MILES) * METRES_PER_MILE;
  const scale = EQUATOR_M_PER_PX_Z0 * Math.cos((lat * Math.PI) / 180);
  const zoom = Math.floor(Math.log2(scale / (metres / halfPx)));
  const min = map.getMinZoom?.() ?? 0;
  const max = map.getMaxZoom?.() ?? 19;
  return Math.max(min, Math.min(max, zoom));
}

/**
 * "Centre on home" — a Leaflet control, because it is a map action.
 *
 * <p>It sits in the top-left corner stack directly under the zoom box, in its OWN control
 * container rather than as a third button in the zoom bar: recentring is not a zoom step, and
 * putting it in that bar would make it read as one. Leaflet's corner container handles the
 * stacking and the gap, so nothing here hardcodes an offset from the top of the map.
 *
 * <p>Home coordinates are NOT geocoded here. The postcode was resolved once, on the server, when
 * the user saved it — `GET /api/user/settings` returns the stored lat/lon — and the same values
 * already drive the Close-to-home radius. A click is a camera move and nothing else.
 *
 * <p>With no postcode saved the button stays visible and disabled rather than disappearing: a
 * control that is absent explains nothing, and this one's whole job when unset is to say where
 * the missing setting lives. Clicking it opens Settings on the postcode field.
 *
 * @param {Object}    props
 * @param {?Object}   props.homeCoords  `{ lat, lon }`, or null when no postcode is saved
 * @param {?number}   props.radiusMiles the user's Close-to-home radius, in miles
 * @param {?Function} props.onOpenSettings opens the settings dialog focused on the postcode field
 */
function CentreOnHomeControl({ homeCoords = null, radiusMiles = null, onOpenSettings = null }) {
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
    const control = new L.Control({ position: 'topleft' });
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
      aria-label="Centre on home"
      title={hasHome ? 'Centre on home' : 'Set your home postcode in Settings'}
      onClick={() => {
        if (!hasHome) {
          onOpenSettings?.();
          return;
        }
        map.flyTo(
          [homeCoords.lat, homeCoords.lon],
          zoomForHomeRadius(map, homeCoords.lat, radiusMiles),
          { duration: 0.6 },
        );
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
  radiusMiles: PropTypes.number,
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
 * Map heights, and the easing the drawer and the map share.
 *
 * <p>The Map tab's map is a fixed 500px in a page that scrolls. The overlay's is not: it lives in
 * a modal whose whole height is spoken for, so every row of chrome above it comes straight out of
 * the map. Opening the overlay from a plan card left ~165px of visible map — a strip too short to
 * show the focused pin, its neighbours and the popup without panning — because five rows of filter
 * controls were sitting where the map should be. Folding them away buys the map back.
 */
const MAP_HEIGHT_PX = 500;
const OVERLAY_MAP_HEIGHT_PX = 470;
const OVERLAY_MAP_HEIGHT_FILTERS_OPEN_PX = 300;
const DRAWER_EASING = 'cubic-bezier(0.2, 0.7, 0.2, 1)';

function MapView({ locations, date, onSelectDate = null, autoEventType, handoffEventType, handoffFilterAction, handoffLocationName = null, handoffRegion = null, handoffNonce = null, briefingScores = new Map(), onForecastRun, seasonalFeatures = [], focus = null, emphasiseLocationName = null, overlayMode = false, homeCoords = null, homeRadiusMiles = null, onOpenSettings = null, resizeNonce = null, heat = null }) {
  const { role } = useAuth();
  const isMobile = useIsMobile();
  const [userHasOverriddenEvent, setUserHasOverriddenEvent] = useState(false);
  const [eventType, setEventType] = useState(() => getNextEventType(locations, date));
  const [selectedLocationName, setSelectedLocationName] = useState(null);
  const [zoom, setZoom] = useState(9);
  const [activeTypeFilters, setActiveTypeFilters] = useState(new Set());
  // Minimum-quality threshold (1–5). It's a true "this and above" control, so it
  // always holds a value; persisted to localStorage as 'mapFilterMinStars', and
  // defaults to 3★+ when unset (per the filter-bar tidy).
  const DEFAULT_MIN_STARS = 3;
  const [minStars, setMinStars] = useState(() => {
    const saved = localStorage.getItem('mapFilterMinStars');
    const n = saved !== null ? parseInt(saved, 10) : NaN;
    return Number.isFinite(n) && n >= 1 && n <= 5 ? n : DEFAULT_MIN_STARS;
  });
  const [showUnrated, setShowUnrated] = useState(false);
  // Stand-down filter — hidden by default; power users can reveal triaged locations
  const [showStandDown, setShowStandDown] = useState(() => {
    return localStorage.getItem('mapFilterShowStandDown') === '1';
  });
  const [driveTimeFilter, setDriveTimeFilter] = useState(0); // 0 = All; positive = max minutes
  const [userDriveTimes, setUserDriveTimes] = useState({});
  useEffect(() => { getDriveTimes().then(setUserDriveTimes).catch(() => {}); }, []);
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
   * <p>`heat` is the default view in v2 (that is the feature); `medallions` is today's cluster map,
   * kept as the honest "before". `heatArea` frames and filters to the planning area, which is the
   * state a reader arrives in — you do not open on the whole of Britain.
   */
  const [heatView, setHeatView] = useState('heat');
  const [heatArea, setHeatArea] = useState(true);
  const [heatFitNonce, setHeatFitNonce] = useState(0);
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
    () => !overlayMode && localStorage.getItem('mapFiltersOpen') === '1',
  );
  const toggleAdvancedOpen = () => setAdvancedOpen((v) => {
    const next = !v;
    if (overlayMode) return next;
    if (next) localStorage.setItem('mapFiltersOpen', '1');
    else localStorage.removeItem('mapFiltersOpen');
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
      })();
      localStorage.removeItem('mapFilterMinStars');
    }
  }, [auroraAvailable, eventType]);

  // Apply the auto-selected event type when forecast data arrives, unless the user
  // has already manually chosen an event type this session.
  useEffect(() => {
    if (!userHasOverriddenEvent && autoEventType) {
      // Inline async wrapper satisfies react-hooks/set-state-in-effect while the
      // setState still applies synchronously this tick (see note above).
      (async () => setEventType(autoEventType))();
    }
  }, [autoEventType, userHasOverriddenEvent]);

  // Apply a forced event type from the Plan tab handoff, overriding any user selection.
  useEffect(() => {
    if (handoffEventType) {
      (async () => {
        setEventType(handoffEventType);
        setUserHasOverriddenEvent(false);
      })();
    }
  }, [handoffEventType]);

  // Apply a filter action handoff from a Hot Topic pill tap (e.g. BLUEBELL).
  useEffect(() => {
    if (handoffFilterAction) {
      (async () => {
        setActiveTypeFilters(new Set([handoffFilterAction]));
        setAdvancedOpen(true);
      })();
    }
  }, [handoffFilterAction]);

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

  // Fetch stored aurora results when in Aurora mode and the selected date changes.
  useEffect(() => {
    if (eventType !== 'AURORA' || !date) {
      (async () => setStoredAuroraResults({}))();
      return;
    }
    getAuroraForecastResults(date)
      .then((results) => {
        const byName = {};
        results.forEach((r) => { byName[r.locationName] = r; });
        setStoredAuroraResults(byName);
      })
      .catch(() => {
        setStoredAuroraResults({});
      });
  }, [eventType, date]);

  // Fetch available dates for astro conditions (available to everyone).
  useEffect(() => {
    getAstroAvailableDates()
      .then(setAstroAvailableDates)
      .catch(() => {});
  }, []);

  // Fetch astro condition scores when in Astro mode and the selected date changes.
  useEffect(() => {
    if (eventType !== 'ASTRO' || !date) {
      (async () => setAstroScores({}))();
      return;
    }
    getAstroConditions(date)
      .then((results) => {
        const byName = {};
        results.forEach((r) => { byName[r.locationName] = r; });
        setAstroScores(byName);
      })
      .catch(() => {
        setAstroScores({});
      });
  }, [eventType, date]);

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
    localStorage.setItem('mapFilterMinStars', String(star));
  }

  function toggleShowUnrated() {
    setShowUnrated((v) => !v);
  }

  function toggleShowStandDown() {
    setShowStandDown((v) => {
      const next = !v;
      if (next) localStorage.setItem('mapFilterShowStandDown', '1');
      else localStorage.removeItem('mapFilterShowStandDown');
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
   * <p>`heat` defaults to null and only `WindowFirstMapPane` passes it, so the v1 Map tab and the
   * Plan overlay render byte-identically without it — the `serverCellRating` shape, and the reason
   * plan §8 calls this component's blast radius out by name.
   *
   * <p>Withheld in aurora and astro modes even when handed: the markers there carry a different
   * quantity entirely (Kp visibility, observing quality) and a sky-colour field painted under them
   * would be two scales on one picture with nothing saying so.
   */
  const heatOffered = Boolean(heat?.enabled) && !isAuroraMode && !isAstroMode;
  const heatOn = heatOffered && heatView === 'heat';

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
   * This window's kernel points, narrowed to that pool.
   *
   * <p>The catalogue carries `bortleClass` and the points do not — `heatSpots.js` says so in as
   * many words ("P4's dark-sky filter wants the whole catalogue; only the kernel wants points"), so
   * the filter is decided on spots and applied to points through the join's own id-first key.
   */
  const heatPoints = useMemo(() => {
    if (!heatOn || !heatWindow) return EMPTY_POINTS;
    const points = heat.pointsByKey?.get(heatWindow.key) || EMPTY_POINTS;
    if (!heatSpotPool) return points;
    const allowed = new Set(heatSpotPool.map(heatSpotKey));
    return points.filter((p) => allowed.has(heatSpotKey(p)));
  }, [heatOn, heatWindow, heat, heatSpotPool]);

  /**
   * Whether the selected window carries no ratings at all — the Plan tab's unscored mark, adapted.
   *
   * <p><b>Off the UNFILTERED point set, never off {@code heatPoints}.</b> That array is narrowed by
   * the dark-sky toggle, and a reader who has filtered every Bortle ≤ 4 location out of a
   * perfectly well-rated window has emptied the field themselves. Labelling that "not scored" would
   * blame the forecast for the reader's own control — the one mislabelling this whole channel
   * exists to prevent.
   *
   * <p>It is also distinct from {@code heatWindow} being null, which is the map sitting on a date
   * the briefing does not reach: the selector already says "No forecast window" for that, and it is
   * a statement about the CAMERA rather than about the forecast.
   *
   * <p>Gated on {@code heat.scoresKnown} for the reason the strip and the row map are: an empty
   * point set is also what an unfetched ratings response looks like.
   */
  const windowUnscored = Boolean(
    heatOn && heatWindow && heat?.scoresKnown
      && !(heat.pointsByKey?.get(heatWindow.key)?.length > 0),
  );

  /** The camera's framing for each segment state — `null` when the roster cannot supply a box. */
  const heatBounds = (heatArea ? heat?.areaBounds : heat?.catalogueBounds) || null;
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
   */
  const auroraNightRequested = useRef(false);
  useEffect(() => {
    if (!isAuroraMode) {
      auroraNightRequested.current = false;
      return;
    }
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
  }, [isAuroraMode, auroraAvailableDates, auroraNight, date, onSelectDate]);

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
          const mins = userDriveTimes[String(loc.id)];
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
    showStandDown, showUnrated, minStars, driveTimeFilter, userDriveTimes,
    darkSkyFilter, focus, isAstroMode,
  ]);

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

  const selectedLoc = visibleLocations.find((l) => l.name === selectedLocationName) ?? null;
  const selectedDayData = selectedLoc?.forecastsByDate.get(date);
  // True when the selected date falls in a travel range — the overnight batch
  // skips Claude forecasts for those days, so the popup says so explicitly.
  const isTravelDayForDate = isTravelDate(date, travelRanges);
  const sunriseAzimuth = selectedDayData?.sunrise?.azimuthDeg ?? null;
  const sunsetAzimuth  = selectedDayData?.sunset?.azimuthDeg  ?? null;

  /** Derive popup content props for a location. */
  function getContentProps(loc) {
    const dayData = loc.forecastsByDate.get(date);
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
  const filterSummary = filterSummaryParts.join(' · ');

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

  const mapHeight = overlayMode
    ? (advancedOpen ? OVERLAY_MAP_HEIGHT_FILTERS_OPEN_PX : OVERLAY_MAP_HEIGHT_PX)
    : MAP_HEIGHT_PX;

  const eventSelector = (
    <ForecastTypeSelector
      eventType={eventType}
      onChange={(value) => {
        setUserHasOverriddenEvent(true);
        setEventType(value);
        setMinStars(DEFAULT_MIN_STARS);
        setShowUnrated(false);
        setShowStandDown(false);
        localStorage.removeItem('mapFilterMinStars');
        localStorage.removeItem('mapFilterShowStandDown');
      }}
      showAurora={role !== 'LITE_USER'}
      auroraAvailable={auroraAvailable}
      astroAvailable={astroAvailable}
      sunriseAvailable={sunriseAvailable}
      sunsetAvailable={sunsetAvailable}
    />
  );

  // The disclosure. On the Map tab it is a filter pill that also summarises what is active; in the
  // overlay the chips beside it already say that, so it drops to the plain weight of the modal's ✕
  // — one button, right-aligned, with a caret that turns.
  const filtersButton = overlayMode ? (
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
  ) : (
    <button
      data-testid="advanced-filters-toggle"
      onClick={toggleAdvancedOpen}
      aria-expanded={advancedOpen}
      className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-full border transition-colors ${
        hasNonDefaultFilters
          ? 'bg-plex-gold/10 border-plex-gold/40 text-plex-gold'
          : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
      }`}
    >
      <span aria-hidden="true">{advancedOpen ? '▴' : '▾'}</span>
      Filters
      {!advancedOpen && (
        <span data-testid="filter-summary" className="text-plex-text-muted font-normal">
          · {filterSummary}
        </span>
      )}
    </button>
  );

  return (
    <div className={overlayMode ? 'flex flex-col' : 'flex flex-col gap-4'}>
      {overlayMode ? (
        /* ── Context bar — the overlay's default row ──
           A receipt, not a control panel: what the map is showing, in read-only chips, because
           the user already answered every one of these questions on the way in. */
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
      ) : (
        /* Primary row: event type toggles + Filters disclosure button */
        <div className="flex items-center justify-between gap-3 flex-wrap">
          {eventSelector}
          {filtersButton}
        </div>
      )}

      {/* Advanced filters — hidden by default, revealed with a slide-down transition */}
      <div
        className="overflow-hidden"
        data-testid="advanced-filters-panel"
        style={{
          maxHeight: advancedOpen ? '340px' : 0,
          transition: `max-height 0.24s ${DRAWER_EASING}`,
          ...(overlayMode
            ? { borderBottom: '1px solid var(--color-plex-border)', background: 'rgba(0,0,0,0.10)' }
            : {}),
        }}
      >
        <div
          className="flex flex-col"
          data-testid="advanced-filters-content"
          style={overlayMode
            ? { gap: '11px', padding: '13px 18px 15px' }
            : { gap: '0.75rem', paddingBottom: '0.25rem' }}
        >

          {/* ── Event — in the overlay only, where the context bar took the primary row's place.
              It is the one control the drill-down genuinely inherited, so it is labelled as such
              rather than presented as a fresh question. ── */}
          {overlayMode && (
            <FilterRow
              compact
              label="Event"
              compactLabel="Event"
              hint="inherited from the plan card"
            >
              {eventSelector}
            </FilterRow>
          )}

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
                      /* One ramp per surface (D2). In heat view the field, the markers and the
                         clusters are all on `scoreRamp`, so a swatch left on v1's palette would put
                         two colour languages for one rating on the same screen — the bundle README's
                         "do not silently ship both ramps meaning the same thing". */
                      style={{ width: 8, height: 8, backgroundColor: heatOn ? rampHex(star) : RATING_COLOURS[star] }}
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
                    localStorage.removeItem('mapFilterMinStars');
                    localStorage.removeItem('mapFilterShowStandDown');
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
          height transition shares the drawer's easing so the two move as one gesture. */}
      <div
        data-testid="map-container"
        className={overlayMode ? '' : 'rounded-lg overflow-hidden ring-1 ring-gray-700'}
        style={{
          height: `${mapHeight}px`,
          position: 'relative',
          zIndex: 0,
          ...(overlayMode ? { transition: `height 0.24s ${DRAWER_EASING}` } : {}),
        }}
      >
        <MapContainer
          /* The heat arm opens on the PLANNING AREA, not on every location the roster holds (§4.5).
             Framing the whole catalogue would open on a box wide enough to make the field a smudge
             and every marker a cluster, which is the overload the feature removes.

             Falls back to the v1 framing when no box could be derived at all — no spots, a failed
             scores fetch, a briefing that has not landed. ⚠️ NOT when there is no home: with no
             postcode `planningArea` treats every unmeasured region as in-area, so the box is the
             catalogue's own, padded 0.12° and at 28px rather than v1's 60. That is a deliberate
             difference from v1, not a fallback, and it is written down because the first cut's
             comment claimed the opposite.

             Gated on `heat.enabled` rather than on `heatOffered`, which additionally excludes aurora
             and astro modes: `MapContainer` reads `bounds` once at construction, so a tab that
             happened to mount in aurora mode would have taken the heat framing and kept it for the
             session. */
          bounds={openingBounds || bounds}
          boundsOptions={openingBounds ? { padding: [28, 28] } : { padding: [60, 60] }}
          style={{ height: '100%', width: '100%' }}
          zoomControl
        >
          {heatOn && (
            /* No fallback: the field is a picture, and a spinner where a picture is loading says
               less than the map already does. */
            <Suspense fallback={null}>
              <MapHeatLayer
                points={heatPoints}
                conf={heatWindow?.conf ?? null}
                markersLocked={selectedLocationName != null}
              />
            </Suspense>
          )}
          {heatOffered && <HeatBoundsController bounds={heatBounds} nonce={heatFitNonce} />}
          <TileLayer
            url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
            attribution='&copy; <a href="https://carto.com/attributions">CARTO</a>'
            maxZoom={19}
          />
          <ZoomTracker onZoom={setZoom} />
          {/* Map tab only. The Plan overlay is already focused on the spot the user asked about,
              and "go home" there would throw away the framing they opened it for — worse, its
              no-postcode branch opens a settings dialog on top of a modal. */}
          {!overlayMode && (
            <CentreOnHomeControl
              homeCoords={homeCoords}
              radiusMiles={homeRadiusMiles}
              onOpenSettings={onOpenSettings}
            />
          )}
          {overlayMode && <BoundsTracker onBounds={handleBounds} />}
          <MapSizeSync
            trigger={overlayMode ? advancedOpen : resizeNonce}
            enabled={overlayMode || resizeNonce != null}
          />
          <FlyToController target={flyTarget} />
          <FitBoundsController target={fitBoundsTarget} />
          <HandoffPopupController
            locationName={handoffLocationName}
            nonce={handoffNonce}
            markerRefs={markerRefs}
          />
          {/* Gated to the CURRENT NIGHT, not to today. The viewline is a nowcast of where the
              aurora is visible right now, so it belongs on the night in progress — which between
              midnight and dawn is yesterday's date. Comparing against the calendar date hid it on
              exactly the night the reader had come to look at. */}
          {viewlineEnabled && eventType === 'AURORA' && date === auroraNight && (
            <AuroraViewlineOverlay viewline={viewline} forecastKp={auroraStatus?.forecastKp} />
          )}

          {/* Azimuth lines for the selected location */}
          {selectedLoc && sunriseAzimuth != null && eventType === 'SUNRISE' && (
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
          {selectedLoc && sunsetAzimuth != null && eventType === 'SUNSET' && (
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
            /* ⚠️ Remounted on the view switch, and it is the only thing that makes D8's "medallion
               view is byte-identical to today" true on screen. `L.MarkerClusterGroup` caches each
               bubble's `_iconObj` and re-calls `iconCreateFunction` only when the cluster's
               membership changes (`_iconNeedsUpdate`), and `react-leaflet-cluster` writes the new
               function into `options` without calling `refreshClusters()`. So a toggle recoloured
               the individual pins and left every cluster on the palette it was built with — not even
               healing on zoom. The key is constant for every caller that passes no `heat`, so v1
               remounts nothing. */
            key={heatOn ? 'heat-ramp' : 'medallions'}
            chunkedLoading
            /* The ramp rides the heat VIEW, not merely the opt-in: medallion view is specified as
               byte-identical to today (D8), which is what makes it a usable "before". */
            iconCreateFunction={(cluster) => createClusterIcon(cluster, role, heatOn)}
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
                heatOn,
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
                  {!isMobile && (
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
                          driveMinutes={userDriveTimes[String(loc.id)] ?? null}
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
        </MapContainer>

        {/* The heat toolbar: view, framing, window and the ramp's key.

            Top-left at `left: 60px` because Leaflet's own zoom control owns the corner and paints
            above anything below z-index 1100 — `CentreOnHomeControl` stacks under the zoom buttons
            in the same stack, and this clears both. It is a SIBLING of the Leaflet container rather
            than a Leaflet control, so a drag that starts on a button is not also a map pan.

            The buttons take the arm's shared `.wf-seg` / `.wf-seg-btn` classes rather than ad-hoc
            utilities. The first cut did not, and paid for it three ways at once: its pressed state
            named `bg-plex-accent`, a token this project has never had, so Tailwind emitted no rule
            and the selected segment painted nothing; there was no `:focus-visible` ring, and the
            group's `overflow: hidden` would have clipped the UA's; and at `py-1` the buttons stood
            21px tall, under SC 2.5.8's 24px target. The shared classes carry all three answers and
            were written against exactly this markup. */}
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

              {/* Absent entirely without a home, and that is D6 rather than a tidy-up: with no
                  postcode the planning area IS the whole roster, so the two states frame the same
                  box over the same spots — a control whose every press does nothing, which §6
                  bans. `CentreOnHomeControl` already tells a reader without a postcode where to
                  put one. */}
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
                    My area
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

              {/* The selector sets the map's own date and event rather than holding a window of its
                  own — see `heatWindow`. Its value is empty exactly when the map is on a date the
                  briefing does not reach, which is a real state and not an error. */}
              <select
                data-testid="wf-map-window"
                aria-label="Forecast window"
                className="wf-map-window"
                value={heatWindow?.key ?? ''}
                onChange={(e) => {
                  const next = (heat.windows || []).find((w) => w.key === e.target.value);
                  if (!next) return;
                  setUserHasOverriddenEvent(true);
                  setEventType(next.targetType);
                  if (next.date !== date) onSelectDate?.(next.date);
                }}
              >
                {!heatWindow && <option value="">No forecast window</option>}
                {(heat.windows || []).map((w) => (
                  <option key={w.key} value={w.key}>{`${w.label} · ${w.time}`}</option>
                ))}
              </select>
            </div>

            {/* The ramp's key. Only in heat view, because in medallion view it would explain a ramp
                nothing on screen is painted with.

                `role="img"` with its own name: the gradient is `aria-hidden` and the two words alone
                ("Poor", "Worth it") would reach a screen reader as a pair of adjectives with nothing
                saying what they qualify. */}
            {/* Its own state rather than a variant of the key, because it REPLACES the key: the
                key's own rule one comment up is that it must not explain a ramp nothing on screen
                is painted with, and an unrated window paints nothing. Leaving both would put a
                colour key above an empty map and then deny it in the next line.

                Real text with no `aria-hidden`, unlike the row map's chip: this is toolbar chrome
                rather than an annotation on a picture that does not exist for a screen reader, and
                it is the only thing on the tab that says why the field is missing. It names its
                own scope — position under the window selector carries that for a sighted reader
                and for nobody else. */}
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
                  style={{
                    background: `linear-gradient(90deg, ${RAMP_STOPS.map((stop) => stop.hex).join(', ')})`,
                  }}
                />
                <span aria-hidden="true">Worth it</span>
              </div>
            )}
          </div>
        )}

        {/* Aurora viewline upsell chip for LITE users */}
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

        {/* Legend: show when PhotoCast-scored pins are visible */}
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
      </div>

      {/* Mobile bottom sheet */}
      {isMobile && selectedLocationName && (() => {
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
                driveMinutes={userDriveTimes[String(loc.id)] ?? null}
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
   * Asks the parent to move the selected date. Used only to land on the aurora night when aurora
   * mode is entered; the parent stays the owner of the date and may ignore one not on the strip.
   */
  onSelectDate: PropTypes.func,
  autoEventType: PropTypes.string,
  handoffEventType: PropTypes.string,
  handoffFilterAction: PropTypes.string,
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
   * looking — currently the window-first Map tab, whose panel is `display: none` between visits.
   * Null (the default, and what the v1 Map tab passes) leaves `MapSizeSync` switched off exactly
   * as it was, so this cannot reach the frozen arm.
   */
  resizeNonce: PropTypes.number,
  /**
   * The v2 heat field's opt-in. Default `null` — the v1 Map tab and the Plan overlay pass nothing
   * and render byte-identically, which `MapViewHeat.test.jsx` pins per surface.
   */
  heat: PropTypes.shape({
    enabled: PropTypes.bool,
    hasHome: PropTypes.bool,
    spots: PropTypes.array,
    areaSpots: PropTypes.array,
    pointsByKey: PropTypes.instanceOf(Map),
    /** Whether the ratings response has been received — gates the unscored note only. */
    scoresKnown: PropTypes.bool,
    windows: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string,
      date: PropTypes.string,
      targetType: PropTypes.string,
      label: PropTypes.string,
      time: PropTypes.string,
      conf: PropTypes.number,
    })),
    areaBounds: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
    catalogueBounds: PropTypes.arrayOf(PropTypes.arrayOf(PropTypes.number)),
  }),
  /** `{ lat, lon }` of the user's saved home postcode, or null when none is saved. */
  homeCoords: PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number }),
  /** The user's Close-to-home radius in miles — frames the "centre on home" camera move. */
  homeRadiusMiles: PropTypes.number,
  /** Opens the settings dialog on the postcode field. */
  onOpenSettings: PropTypes.func,
};

export default React.memo(MapView);
