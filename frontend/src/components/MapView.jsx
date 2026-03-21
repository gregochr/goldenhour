import React, { useState, useEffect } from 'react';
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
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { getAuroraLocations } from '../api/auroraApi.js';

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
function getNextEventType(locations, date) {
  const now = new Date();
  const todayStr = now.toLocaleDateString('en-CA'); // YYYY-MM-DD
  if (date !== todayStr) return 'SUNSET';

  for (const loc of locations) {
    if ((loc.locationType ?? []).every((t) => t === 'WILDLIFE')) continue;
    const dayData = loc.forecastsByDate.get(date);
    const sunriseTime = dayData?.sunrise?.solarEventTime;
    if (sunriseTime) {
      return new Date(sunriseTime) > now ? 'SUNRISE' : 'SUNSET';
    }
  }
  return 'SUNSET';
}

function MapView({ locations, date }) {
  const { role } = useAuth();
  const isMobile = useIsMobile();
  const [eventType, setEventType] = useState(() => getNextEventType(locations, date));
  const [selectedLocationName, setSelectedLocationName] = useState(null);
  const [zoom, setZoom] = useState(9);
  const [activeTypeFilters, setActiveTypeFilters] = useState(new Set());
  const [activeRatingFilters, setActiveRatingFilters] = useState(new Set());
  const [driveTimeFilter, setDriveTimeFilter] = useState(0); // 0 = All; positive = max minutes
  const [auroraFriendlyFilter, setAuroraFriendlyFilter] = useState(false);
  const { status: auroraStatus } = useAuroraStatus();
  const [auroraScores, setAuroraScores] = useState({});
  const [tideFetchedAt, setTideFetchedAt] = useState({});
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

  // Fetch per-location aurora scores when an alert is active (AMBER or RED).
  // Scores are keyed by location name for O(1) lookup in popup render.
  useEffect(() => {
    const ALERT_WORTHY = new Set(['AMBER', 'RED']);
    if (!auroraStatus || !ALERT_WORTHY.has(auroraStatus.level)) {
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

  function toggleRatingFilter(rating) {
    setActiveRatingFilters((prev) => {
      const next = new Set(prev);
      if (next.has(rating)) {
        next.delete(rating);
      } else {
        next.add(rating);
      }
      return next;
    });
  }

  /** Get the forecast rating for a location on the current date/event. */
  function getRatingForLocation(loc) {
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


  const ratingFiltered = activeRatingFilters.size === 0
    ? typeFiltered
    : typeFiltered.filter((loc) => {
        const rating = getRatingForLocation(loc);
        if (rating == null) return activeRatingFilters.has('unrated');
        return activeRatingFilters.has(rating);
      });

  const driveFiltered = driveTimeFilter === 0
    ? ratingFiltered
    : ratingFiltered.filter((loc) =>
        loc.driveDurationMinutes != null && loc.driveDurationMinutes <= driveTimeFilter,
      );

  // Aurora-friendly filter: show only locations with a Bortle class at or below threshold.
  // Threshold is 5 (red alert) or 4 (amber / unknown level).
  const auroraThreshold = auroraStatus?.level === 'RED' ? 5 : 4;
  const visibleLocations = auroraFriendlyFilter
    ? driveFiltered.filter((loc) =>
        loc.bortleClass != null && loc.bortleClass <= auroraThreshold,
      )
    : driveFiltered;

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
    const forecast = eventType === 'SUNRISE' ? dayData?.sunrise : dayData?.sunset;
    const hourlyData = dayData?.hourly ?? [];
    const types = loc.locationType ?? [];
    const isPureWildlife = types.length > 0 && types.every((t) => t === 'WILDLIFE');
    const isWaterfall = types.includes('WATERFALL');
    return { forecast, hourlyData, isPureWildlife, isWaterfall };
  }

  return (
    <div className="flex flex-col gap-4">
      {/* Location type + star rating filter toggles */}
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-xs text-plex-text-muted mr-1">Filter:</span>
        <InfoTip text="Type and star filters combine: locations must match both. Within each group, any selection passes." />
        {Object.entries(LOCATION_TYPE_LABELS).map(([type, { label, emoji }]) => (
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
        <span className="text-plex-border mx-1">|</span>
        {[1, 2, 3, 4, 5].map((star) => (
          <button
            key={`star-${star}`}
            onClick={() => toggleRatingFilter(star)}
            data-testid={`star-filter-${star}`}
            className={`px-2.5 py-1 text-xs font-medium rounded-full border transition-colors ${
              activeRatingFilters.has(star)
                ? 'bg-plex-gold/20 border-plex-gold/50 text-plex-gold'
                : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
            }`}
          >
            {star}&#9733;
          </button>
        ))}
        <button
          onClick={() => toggleRatingFilter('unrated')}
          disabled={!hasUnrated}
          data-testid="star-filter-unrated"
          className={`px-2.5 py-1 text-xs font-medium rounded-full border transition-colors ${
            !hasUnrated
              ? 'bg-plex-surface border-plex-border text-plex-text-muted/40 cursor-not-allowed'
              : activeRatingFilters.has('unrated')
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
        <span className="text-plex-border mx-1">|</span>
        <button
          onClick={() => setAuroraFriendlyFilter((v) => !v)}
          data-testid="aurora-filter-toggle"
          title={`Show only dark-sky locations suitable for aurora photography (Bortle ≤ ${auroraThreshold})`}
          className={`px-3 py-1 text-xs font-medium rounded-full border transition-colors ${
            auroraFriendlyFilter
              ? 'bg-indigo-900/40 border-indigo-500/60 text-indigo-300'
              : 'bg-plex-surface border-plex-border text-plex-text-secondary hover:text-plex-text'
          }`}
        >
          🌌 Aurora friendly
        </button>
        <InfoTip text={`Filters to dark-sky locations suitable for aurora photography.\n\nActive when AuroraWatch reports AMBER or RED. Locations with Bortle ≤ 4 (AMBER) or ≤ 5 (RED) qualify.\n\nStar rating (1–5) from four factors:\n• Alert level — AMBER = 3★ base, RED = 4★ base\n• Cloud cover — clear skies +1, overcast −1.5\n• Moonlight — below horizon +0.5, severe −1\n• Dark skies — Bortle 1–2 = +0.5, 3–4 = 0, 5+ = −0.5\n\nRun 🌌 Refresh Light Pollution in Location Management to populate Bortle classes.`} />
        {(activeTypeFilters.size > 0 || activeRatingFilters.size > 0
            || driveTimeFilter > 0 || auroraFriendlyFilter) && (
          <button
            onClick={() => {
              setActiveTypeFilters(new Set());
              setActiveRatingFilters(new Set());
              setDriveTimeFilter(0);
              setAuroraFriendlyFilter(false);
            }}
            className="px-3 py-1 text-xs font-medium rounded-full border border-plex-border text-plex-text-muted hover:text-plex-text-secondary transition-colors"
            data-testid="clear-all-filters"
          >
            Clear
          </button>
        )}
      </div>

      {/* Sunrise / Sunset radio toggle */}
      <div className="flex items-center gap-6">
        <label className="flex items-center gap-2 text-sm text-plex-text-secondary cursor-pointer select-none">
          <input
            type="radio"
            name="map-event-type"
            value="SUNRISE"
            checked={eventType === 'SUNRISE'}
            onChange={() => setEventType('SUNRISE')}
            className="accent-[#E5A00D]"
          />
          🌅 Sunrise
        </label>
        <label className="flex items-center gap-2 text-sm text-plex-text-secondary cursor-pointer select-none">
          <input
            type="radio"
            name="map-event-type"
            value="SUNSET"
            checked={eventType === 'SUNSET'}
            onChange={() => setEventType('SUNSET')}
            className="accent-purple-400"
          />
          🌇 Sunset
        </label>
      </div>

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
              const icon = makeMarkerIcon(
                forecast?.rating ?? null,
                role !== 'LITE_USER' ? (forecast?.fierySkyPotential ?? null) : null,
                role !== 'LITE_USER' ? (forecast?.goldenHourPotential ?? null) : null,
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
                          eventType={eventType}
                          isPureWildlife={isPureWildlife}
                          showComfortRows={isWaterfall}
                          role={role}
                          date={date}
                          onTideFetchedAt={(ts) => setTideFetchedAt((prev) => ({ ...prev, [loc.name]: ts }))}
                          tideFetchedAt={tideFetchedAt[loc.name] ?? null}
                          onTideClassification={(cls) => setTideClassifications((prev) => ({ ...prev, [loc.name]: cls }))}
                          tideClassification={tideClassifications[loc.name] ?? null}
                          auroraScore={auroraScores[loc.name] ?? null}
                        />
                      </div>
                    </Popup>
                  )}
                </Marker>
              );
            })}
          </MarkerClusterGroup>
        </MapContainer>
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
                eventType={eventType}
                isPureWildlife={isPureWildlife}
                showComfortRows={isWaterfall}
                role={role}
                date={date}
                onTideFetchedAt={(ts) => setTideFetchedAt((prev) => ({ ...prev, [loc.name]: ts }))}
                tideFetchedAt={tideFetchedAt[loc.name] ?? null}
                onTideClassification={(cls) => setTideClassifications((prev) => ({ ...prev, [loc.name]: cls }))}
                tideClassification={tideClassifications[loc.name] ?? null}
                auroraScore={auroraScores[loc.name] ?? null}
                darkMode
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
};

export default React.memo(MapView);
