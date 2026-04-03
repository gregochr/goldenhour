import React, { useState, useEffect, useMemo } from 'react';
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
import ForecastTypeSelector from './ForecastTypeSelector.jsx';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';
import { getAuroraLocations, getAuroraForecastResults, getAuroraForecastAvailableDates } from '../api/auroraApi.js';
import { getDriveTimes } from '../api/settingsApi.js';
import { getAstroConditions, getAstroAvailableDates } from '../api/astroApi.js';
import AuroraViewlineOverlay from './AuroraViewlineOverlay.jsx';

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
  }
  .leaflet-popup-content {
    overflow-y: visible !important;
    overflow-x: hidden !important;
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
import { buildMarkerSvg, markerLabelAndColour, createClusterIcon } from './markerUtils.js';

const SUNRISE_LINE_COLOUR = '#f97316';
const SUNSET_LINE_COLOUR  = '#a855f7';

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
 * @returns {L.DivIcon}
 */
function makeMarkerIcon(rating, fierySky, goldenHour, locationName, isPureWildlife = false, excludeFromCluster = false) {
  const { label, colour } = markerLabelAndColour(rating, fierySky, goldenHour, isPureWildlife);

  const svg = buildMarkerSvg(label, colour, fierySky, goldenHour, rating, isPureWildlife);
  const html = `
    <div style="display:flex;flex-direction:column;align-items:center;gap:3px;">
      ${svg}
      <div style="
        background:rgba(15,23,42,0.85);
        color:#f1f5f9;
        font-size:10px;font-weight:600;
        padding:2px 7px;border-radius:4px;
        white-space:nowrap;
        max-width:90px;overflow:hidden;text-overflow:ellipsis;
        box-shadow:0 1px 4px rgba(0,0,0,0.5);
        border:1px solid rgba(255,255,255,0.08);
      " title="${locationName}">${locationName}</div>
    </div>
  `;

  return L.divIcon({
    html,
    className: '',
    iconSize: [100, 62],
    iconAnchor: [50, 22],
    rating: rating,
    fierySky: fierySky,
    goldenHour: goldenHour,
    excludeFromCluster: excludeFromCluster,
    popupAnchor: [0, -26],
  });
}

/**
 * Map view showing all locations as score markers for a given date.
 * Selecting a marker draws orange (sunrise) and purple (sunset) azimuth lines.
 *
 * @param {object} props
 * @param {Array<{name: string, lat: number, lon: number, forecastsByDate: Map}>} props.locations
 * @param {string|null} props.date - The target date (YYYY-MM-DD) to display ratings for.
 */
const LOCATION_TYPE_LABELS = {
  LANDSCAPE:  { label: 'Landscape', emoji: '🏔️' },
  WILDLIFE:   { label: 'Wildlife',  emoji: '🐾' },
  SEASCAPE:   { label: 'Seascape',  emoji: '🌊' },
  WATERFALL:  { label: 'Waterfall', emoji: '💦' },
};

/**
 * Determines whether the next solar event is sunrise or sunset based on the
 * current time relative to today's sunrise. For future dates, defaults to SUNSET.
 *
 * @param {Array} locations - Location data with forecastsByDate maps.
 * @param {string} date - The selected date (YYYY-MM-DD).
 * @returns {string} 'SUNRISE' or 'SUNSET'.
 */
/**
 * Looks up a briefing evaluation score for a location by scanning all keys in the scores map.
 * Key format: "regionName|date|targetType|locationName".
 */
function getBriefingScore(briefingScores, locationName, date, eventType) {
  if (!briefingScores || briefingScores.size === 0) return null;
  const suffix = `|${date}|${eventType}|${locationName}`;
  for (const [key, result] of briefingScores) {
    if (key.endsWith(suffix)) return result;
  }
  return null;
}

function getNextEventType(locations, date) {
  const now = new Date();
  const todayStr = now.toLocaleDateString('en-CA'); // YYYY-MM-DD
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

function MapView({ locations, date, autoEventType, handoffEventType, briefingScores = new Map(), onForecastRun }) {
  const { role } = useAuth();
  const isMobile = useIsMobile();
  const [userHasOverriddenEvent, setUserHasOverriddenEvent] = useState(false);
  const [eventType, setEventType] = useState(() => getNextEventType(locations, date));
  const [selectedLocationName, setSelectedLocationName] = useState(null);
  const [zoom, setZoom] = useState(9);
  const [activeTypeFilters, setActiveTypeFilters] = useState(new Set());
  // Minimum star filter — persisted to localStorage as 'mapFilterMinStars'
  const [minStars, setMinStars] = useState(() => {
    const saved = localStorage.getItem('mapFilterMinStars');
    const n = saved !== null ? parseInt(saved, 10) : NaN;
    return Number.isFinite(n) && n >= 1 && n <= 5 ? n : null;
  });
  const [showUnrated, setShowUnrated] = useState(false);
  const [driveTimeFilter, setDriveTimeFilter] = useState(0); // 0 = All; positive = max minutes
  const [userDriveTimes, setUserDriveTimes] = useState({});
  useEffect(() => { getDriveTimes().then(setUserDriveTimes).catch(() => {}); }, []);
  const [darkSkyFilter, setDarkSkyFilter] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const { status: auroraStatus } = useAuroraStatus();
  const viewlineEnabled = role !== 'LITE_USER' && auroraStatus != null
    && ALERT_WORTHY_LEVELS.has(auroraStatus.level);
  const [viewlineUpsellDismissed, setViewlineUpsellDismissed] = useState(false);
  const showViewlineUpsell = role === 'LITE_USER' && !viewlineUpsellDismissed
    && auroraStatus != null && ALERT_WORTHY_LEVELS.has(auroraStatus.level);
  const { viewline } = useAuroraViewline(viewlineEnabled);
  const [auroraScores, setAuroraScores] = useState({});
  const [storedAuroraResults, setStoredAuroraResults] = useState({}); // locationName → result
  const [auroraAvailableDates, setAuroraAvailableDates] = useState([]); // ISO date strings
  const [astroScores, setAstroScores] = useState({}); // locationName → { stars, summary, ... }
  const [astroAvailableDates, setAstroAvailableDates] = useState([]); // ISO date strings
  const [flyTarget, setFlyTarget] = useState(null);
  const [tideFetchedAt, setTideFetchedAt] = useState({});

  // Aurora is available when the user is ADMIN/PRO and either the state machine is active
  // or there are stored forecast results for any date on the date strip.
  const hasStoredAuroraResults = auroraAvailableDates.length > 0;
  const auroraAvailable = role !== 'LITE_USER'
    && (auroraStatus?.active === true || hasStoredAuroraResults);
  const astroAvailable = astroAvailableDates.length > 0;

  // Auto-reset to SUNSET when aurora mode becomes unavailable.
  useEffect(() => {
    if (eventType === 'AURORA' && !auroraAvailable) {
      setEventType('SUNSET');
      setMinStars(null);
      setShowUnrated(false);
      localStorage.removeItem('mapFilterMinStars');
    }
  }, [auroraAvailable, eventType]);

  // Apply the auto-selected event type when forecast data arrives, unless the user
  // has already manually chosen an event type this session.
  useEffect(() => {
    if (!userHasOverriddenEvent && autoEventType) {
      setEventType(autoEventType);
    }
  }, [autoEventType, userHasOverriddenEvent]);

  // Apply a forced event type from the Plan tab handoff, overriding any user selection.
  useEffect(() => {
    if (handoffEventType) {
      setEventType(handoffEventType);
      setUserHasOverriddenEvent(false);
    }
  }, [handoffEventType]);
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
      setAuroraScores({});
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
      setStoredAuroraResults({});
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
      setAstroScores({});
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
    if (minStars === star) {
      setMinStars(null);
      localStorage.removeItem('mapFilterMinStars');
    } else {
      setMinStars(star);
      localStorage.setItem('mapFilterMinStars', String(star));
    }
  }

  function toggleShowUnrated() {
    setShowUnrated((v) => !v);
  }

  /** Get the forecast rating for a location on the current date/event. */
  function getRatingForLocation(loc) {
    if (eventType === 'AURORA') {
      // Prefer stored DB results; fall back to live state cache for tonight
      return storedAuroraResults[loc.name]?.stars
        ?? auroraScores[loc.name]?.stars
        ?? null;
    }
    if (eventType === 'ASTRO') {
      return astroScores[loc.name]?.stars ?? null;
    }
    const dayData = loc.forecastsByDate.get(date);
    const forecast = eventType === 'SUNRISE' ? dayData?.sunrise : dayData?.sunset;
    return forecast?.rating ?? null;
  }

  // Filter logic: type filters and rating filters are both AND-ed.
  // Within each filter group, any match passes (OR).
  const typeFiltered = activeTypeFilters.size === 0
    ? locations
    : locations.filter((loc) => {
        const types = loc.locationType ?? [];
        return types.length === 0 || types.some((t) => activeTypeFilters.has(t));
      });

  const hasUnrated = typeFiltered.some((loc) => getRatingForLocation(loc) == null);


  const ratingFiltered = (minStars === null && !showUnrated)
    ? typeFiltered
    : typeFiltered.filter((loc) => {
        const rating = getRatingForLocation(loc);
        if (rating == null) return showUnrated;
        return minStars === null || rating >= minStars;
      });

  const driveFiltered = driveTimeFilter === 0
    ? ratingFiltered
    : ratingFiltered.filter((loc) => {
        const mins = userDriveTimes[String(loc.id)];
        return mins != null && mins <= driveTimeFilter;
      });

  const isAuroraMode = eventType === 'AURORA';
  const isAstroMode = eventType === 'ASTRO';

  // Dark sky filter: show only locations with Bortle class 4 or darker.
  const darkSkyThreshold = 4;
  const darkSkyFiltered = darkSkyFilter
    ? driveFiltered.filter((loc) =>
        loc.bortleClass != null && loc.bortleClass <= darkSkyThreshold,
      )
    : driveFiltered;

  // Astro mode: only show dark-sky locations (Bortle is not null).
  const visibleLocations = isAstroMode
    ? darkSkyFiltered.filter((loc) => loc.bortleClass != null)
    : darkSkyFiltered;

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

  const bounds = locations.map((loc) => [loc.lat, loc.lon]);

  const selectedLoc = visibleLocations.find((l) => l.name === selectedLocationName) ?? null;
  const selectedDayData = selectedLoc?.forecastsByDate.get(date);
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
    return { forecast, hourlyData, isPureWildlife, isWaterfall };
  }

  // Count of active advanced filters — drives the badge on the Filters toggle button
  const advancedFilterCount = activeTypeFilters.size
    + (minStars !== null ? 1 : 0)
    + (showUnrated ? 1 : 0)
    + (driveTimeFilter > 0 ? 1 : 0)
    + (darkSkyFilter ? 1 : 0);

  return (
    <div className="flex flex-col gap-4">
      {/* Primary row: event type toggles + Filters disclosure button */}
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <ForecastTypeSelector
          eventType={eventType}
          onChange={(value) => {
            setUserHasOverriddenEvent(true);
            setEventType(value);
            setMinStars(null);
            setShowUnrated(false);
            localStorage.removeItem('mapFilterMinStars');
          }}
          showAurora={role !== 'LITE_USER'}
          auroraAvailable={auroraAvailable}
          astroAvailable={astroAvailable}
        />
        <button
          data-testid="advanced-filters-toggle"
          onClick={() => setAdvancedOpen((v) => !v)}
          className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-full border transition-colors ${
            advancedFilterCount > 0
              ? 'bg-plex-gold/10 border-plex-gold/40 text-plex-gold'
              : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
          }`}
        >
          Filters{advancedFilterCount > 0 ? ` (${advancedFilterCount})` : ''}
          <span aria-hidden="true">{advancedOpen ? '▲' : '▼'}</span>
        </button>
      </div>

      {/* Advanced filters — hidden by default, revealed with a slide-down transition */}
      <div
        className={`overflow-hidden transition-all duration-200 ${advancedOpen ? 'max-h-96' : 'max-h-0'}`}
        data-testid="advanced-filters-panel"
      >
        <div className="flex items-center gap-2 flex-wrap pb-1">
          <span className="text-xs text-plex-text-muted mr-1">Filter:</span>
          <InfoTip text="Type and star filters combine: locations must match both. Within each group, any selection passes." />
          {/* Location type chips — hidden in Aurora and Astro modes */}
          {!isAuroraMode && !isAstroMode && Object.entries(LOCATION_TYPE_LABELS).map(([type, { label, emoji }]) => (
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
          {!isAuroraMode && !isAstroMode && <span className="text-plex-border mx-1">|</span>}
          {[1, 2, 3, 4, 5].map((star) => (
            <button
              key={`star-${star}`}
              onClick={() => handleMinStarsClick(star)}
              data-testid={`star-filter-${star}`}
              title={minStars === star ? `Showing ${star}★ and above — click to clear` : `Show ${star}★ and above`}
              className={`px-2.5 py-1 text-xs font-medium rounded-full border transition-colors ${
                minStars !== null && star >= minStars
                  ? 'bg-plex-gold/20 border-plex-gold/50 text-plex-gold'
                  : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
              }`}
            >
              {star}&#9733;
            </button>
          ))}
          <button
            onClick={toggleShowUnrated}
            disabled={!hasUnrated}
            data-testid="star-filter-unrated"
            className={`px-2.5 py-1 text-xs font-medium rounded-full border transition-colors ${
              !hasUnrated
                ? 'bg-plex-surface border-plex-border text-plex-text-muted/40 cursor-not-allowed'
                : showUnrated
                  ? 'bg-plex-text-muted/20 border-plex-text-muted/50 text-plex-text-secondary'
                  : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
            }`}
          >
            ?
          </button>
          <span className="text-plex-border mx-1">|</span>
          <select
            value={driveTimeFilter}
            onChange={(e) => setDriveTimeFilter(parseInt(e.target.value, 10))}
            className="text-xs px-2 py-1 bg-plex-surface border border-plex-border rounded-full text-plex-text-secondary focus:outline-none focus:ring-1 focus:ring-plex-gold"
            data-testid="drive-time-filter-select"
            title="Filter by drive time from last-refreshed position"
          >
            <option value={0}>🚗 All</option>
            <option value={30}>🚗 ≤30 min</option>
            <option value={45}>🚗 ≤45 min</option>
            <option value={60}>🚗 ≤60 min</option>
            <option value={90}>🚗 ≤90 min</option>
            <option value={120}>🚗 ≤2 hrs</option>
          </select>
          {/* Dark sky chip — hidden in Astro and Aurora modes (Bortle filtering already implicit) */}
          {!isAuroraMode && !isAstroMode && (
            <>
              <span className="text-plex-border mx-1">|</span>
              <button
                onClick={() => setDarkSkyFilter((v) => !v)}
                data-testid="dark-sky-filter-toggle"
                title={`Show only locations with low light pollution (Bortle class ${darkSkyThreshold} or darker). Suitable for aurora, astrophotography, and stargazing.`}
                className={`px-3 py-1 text-xs font-medium rounded-full border transition-colors ${
                  darkSkyFilter
                    ? 'bg-indigo-900/40 border-indigo-500/60 text-indigo-300'
                    : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
                }`}
              >
                🔭 Dark sky
              </button>
              <InfoTip text={`Shows locations with low light pollution (Bortle class ${darkSkyThreshold} or darker). Suitable for aurora, astrophotography, and stargazing.${role === 'ADMIN' ? '\n\nRun 🌌 Refresh Light Pollution in Location Management to populate Bortle classes.' : ''}`} />
            </>
          )}
          {(activeTypeFilters.size > 0 || minStars !== null || showUnrated
              || driveTimeFilter > 0 || darkSkyFilter) && (
            <button
              onClick={() => {
                setActiveTypeFilters(new Set());
                setMinStars(null);
                setShowUnrated(false);
                setDriveTimeFilter(0);
                setDarkSkyFilter(false);
                localStorage.removeItem('mapFilterMinStars');
              }}
              className="px-3 py-1 text-xs font-medium rounded-full border border-plex-border text-plex-text-muted hover:text-plex-text-secondary transition-colors"
              data-testid="clear-all-filters"
            >
              Clear
            </button>
          )}
        </div>
      </div>

      {/* Best aurora location card — visible only in aurora mode */}
      {isAuroraMode && bestAuroraLocation && (
        <div
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

      {/* Map */}
      <div
        data-testid="map-container"
        className="rounded-lg overflow-hidden ring-1 ring-gray-700"
        style={{ height: '500px', position: 'relative', zIndex: 0 }}
      >
        <MapContainer
          bounds={bounds}
          boundsOptions={{ padding: [60, 60] }}
          style={{ height: '100%', width: '100%' }}
          zoomControl
        >
          <TileLayer
            url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
            attribution='&copy; <a href="https://carto.com/attributions">CARTO</a>'
            maxZoom={19}
          />
          <ZoomTracker onZoom={setZoom} />
          <FlyToController target={flyTarget} />
          {viewlineEnabled && eventType === 'AURORA' && date === new Date().toLocaleDateString('en-CA') && (
            <AuroraViewlineOverlay viewline={viewline} />
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
            chunkedLoading
            iconCreateFunction={(cluster) => createClusterIcon(cluster, role)}
            maxClusterRadius={60}
            disableClusteringAtZoom={10}
            showCoverageOnHover={false}
            spiderfyOnMaxZoom
            zoomToBoundsOnClick
            animate
          >
            {visibleLocations.map((loc) => {
              const { forecast, hourlyData, isPureWildlife, isWaterfall } = getContentProps(loc);
              const locAuroraScore = isAuroraMode ? (auroraScores[loc.name] ?? null) : null;
              // Look up briefing evaluation score for this location (if any)
              const briefingScore = !isAuroraMode ? getBriefingScore(briefingScores, loc.name, date, eventType) : null;
              const markerRating = isAuroraMode
                ? (locAuroraScore?.stars ?? null)
                : (briefingScore?.rating ?? forecast?.rating ?? null);
              const markerFiery = (!isAuroraMode && role !== 'LITE_USER')
                ? (briefingScore?.fierySkyPotential ?? forecast?.fierySkyPotential ?? null)
                : null;
              const markerGolden = (!isAuroraMode && role !== 'LITE_USER')
                ? (briefingScore?.goldenHourPotential ?? forecast?.goldenHourPotential ?? null)
                : null;
              const icon = makeMarkerIcon(
                markerRating,
                markerFiery,
                markerGolden,
                loc.name,
                isPureWildlife,
                isWaterfall,
              );

              return (
                <Marker
                  key={loc.name}
                  position={[loc.lat, loc.lon]}
                  icon={icon}
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
                          hourlyData={hourlyData}
                          eventType={isAuroraMode || isAstroMode ? 'SUNSET' : eventType}
                          isPureWildlife={isPureWildlife}
                          showComfortRows={isWaterfall}
                          role={role}
                          date={date}
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

        {/* Legend: show when Claude-scored pins are visible */}
        {!isAuroraMode && !isAstroMode && briefingScores.size > 0 && (() => {
          const suffix = `|${date}|${eventType}|`;
          for (const key of briefingScores.keys()) {
            if (key.includes(suffix)) {
              return (
                <div
                  data-testid="claude-scored-legend"
                  className="absolute bottom-2 left-1/2 -translate-x-1/2 z-[1000] bg-plex-surface/80 backdrop-blur-sm
                    text-plex-text-secondary rounded-full px-3 py-1 border border-plex-border/30"
                  style={{ fontSize: '11px' }}
                >
                  ★ Claude-scored locations shown
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
        return (
          <BottomSheet
            open
            onClose={() => { setSelectedLocationName(null); void 0; }}
          >
            <div key={`${date}-${eventType}`} className="animate-popup-refresh">
              <MarkerPopupContent
                location={loc}
                forecast={forecast}
                hourlyData={hourlyData}
                eventType={isAuroraMode || isAstroMode ? 'SUNSET' : eventType}
                isPureWildlife={isPureWildlife}
                showComfortRows={isWaterfall}
                role={role}
                date={date}
                driveMinutes={userDriveTimes[String(loc.id)] ?? null}
                onTideFetchedAt={(ts) => setTideFetchedAt((prev) => ({ ...prev, [loc.name]: ts }))}
                tideFetchedAt={tideFetchedAt[loc.name] ?? null}
                onTideClassification={(cls) => setTideClassifications((prev) => ({ ...prev, [loc.name]: cls }))}
                tideClassification={tideClassifications[loc.name] ?? null}
                auroraScore={auroraScores[loc.name] ?? null}
                isAuroraMode={isAuroraMode}
                astroScore={astroScores[loc.name] ?? null}
                isAstroMode={isAstroMode}
                darkMode
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
  autoEventType: PropTypes.string,
  handoffEventType: PropTypes.string,
  briefingScores: PropTypes.instanceOf(Map),
  onForecastRun: PropTypes.func,
};

export default React.memo(MapView);
