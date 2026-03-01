import React, { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import { MapContainer, TileLayer, Marker, Popup, Polyline, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useAuth } from '../context/AuthContext.jsx';

/** Inline SVG weather icons for comfort rows. */
const ICON_STYLE = { width: '14px', height: '14px', verticalAlign: 'middle', marginRight: '3px', flexShrink: 0 };

function ThermometerIcon() {
  return (
    <svg style={ICON_STYLE} viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0z" />
    </svg>
  );
}

function WindIcon() {
  return (
    <svg style={ICON_STYLE} viewBox="0 0 24 24" fill="none" stroke="#60a5fa" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17.7 7.7a2.5 2.5 0 1 1 1.8 4.3H2" />
      <path d="M9.6 4.6A2 2 0 1 1 11 8H2" />
      <path d="M12.6 19.4A2 2 0 1 0 14 16H2" />
    </svg>
  );
}

function RainIcon() {
  return (
    <svg style={ICON_STYLE} viewBox="0 0 24 24" fill="none" stroke="#38bdf8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242" />
      <path d="M16 14v6" /><path d="M8 14v6" /><path d="M12 16v6" />
    </svg>
  );
}

function DropletIcon() {
  return (
    <svg style={ICON_STYLE} viewBox="0 0 24 24" fill="none" stroke="#38bdf8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z" />
    </svg>
  );
}

// Override Leaflet popup width
const popupStyles = `
  .leaflet-popup-content-wrapper {
    width: calc(100vw - 40px) !important;
    max-width: 600px !important;
  }
  .leaflet-popup-content {
    max-height: 600px !important;
    overflow-y: auto !important;
  }
`;
import {
  formatEventTimeUk,
  formatShiftedEventTimeUk,
  formatGeneratedAtFull,
  mpsToMph,
  degreesToCompass,
} from '../utils/conversions.js';
import TideIndicator from './TideIndicator.jsx';

/**
 * Maps an average 0–100 score to a marker colour.
 * @param {number|null} avg
 * @returns {string} hex colour
 */
function scoreColour(avg) {
  if (avg == null) return '#3A3D45';
  if (avg > 80)   return '#E5A00D'; // plex gold
  if (avg > 60)   return '#CC8A00'; // plex gold-dark
  if (avg > 40)   return '#A06E00'; // warm bronze
  if (avg > 20)   return '#6B5000'; // dark bronze
  return '#6B6B6B';                 // muted
}

/** Maps a 1–5 Haiku rating to a marker colour. */
const RATING_COLOURS = {
  1: '#6B6B6B',
  2: '#6B5000',
  3: '#A06E00',
  4: '#CC8A00',
  5: '#E5A00D',
};

const SUNRISE_LINE_COLOUR = '#f97316';
const SUNSET_LINE_COLOUR  = '#a855f7';

/** Base style shared by all popup pills. */
const POPUP_PILL = {
  display: 'inline-flex', alignItems: 'center', gap: '4px',
  fontSize: '11px', padding: '2px 8px', borderRadius: '999px',
  fontWeight: '600',
};

const POPUP_LOC_TYPE_META = {
  LANDSCAPE: { emoji: '🏔️', label: 'Landscape' },
  WILDLIFE:  { emoji: '🦅', label: 'Wildlife' },
  SEASCAPE:  { emoji: '🌊', label: 'Seascape' },
};

const POPUP_GOLDEN_HOUR_META = {
  SUNRISE:    { emoji: '🌅', label: 'Sunrise' },
  SUNSET:     { emoji: '🌇', label: 'Sunset' },
  BOTH_TIMES: { emoji: '🌅🌇', label: 'Sunrise & Sunset' },
  ANYTIME:    { emoji: '☀️',  label: 'Anytime' },
};

const POPUP_TIDE_META = {
  HIGH_TIDE: 'High tide',
  LOW_TIDE:  'Low tide',
  MID_TIDE:  'Mid tide',
  ANY_TIDE:  'Any tide',
};

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
 * Creates a custom Leaflet DivIcon for a location marker.
 *
 * @param {number|null} rating - Haiku 1–5 rating, or null for Sonnet rows.
 * @param {number|null} fierySky - Fiery sky score 0–100, or null for Haiku rows.
 * @param {number|null} goldenHour - Golden hour score 0–100, or null for Haiku rows.
 * @param {string} locationName - Display name shown beneath the marker.
 * @param {boolean} [isPureWildlife=false] - If true, renders a green wildlife marker.
 * @returns {L.DivIcon}
 */
function makeMarkerIcon(rating, fierySky, goldenHour, locationName, isPureWildlife = false) {
  let colour, label;
  if (isPureWildlife) {
    colour = '#4ade80'; // green
    label = '🦅';
  } else if (rating != null) {
    colour = RATING_COLOURS[rating] ?? '#6B6B6B';
    label = `${rating}★`;
  } else {
    const avg = (fierySky != null && goldenHour != null)
      ? Math.round((fierySky + goldenHour) / 2)
      : null;
    colour = scoreColour(avg);
    label = avg != null ? avg : '?';
  }

  const html = `
    <div style="display:flex;flex-direction:column;align-items:center;gap:3px;">
      <div style="
        background:${colour};
        border-radius:50%;
        width:40px;height:40px;
        display:flex;align-items:center;justify-content:center;
        font-size:17px;font-weight:800;color:#0f172a;
        box-shadow:0 2px 10px rgba(0,0,0,0.7);
        border:2px solid rgba(255,255,255,0.2);
      ">${label}</div>
      <div style="
        background:rgba(15,23,42,0.85);
        color:#f1f5f9;
        font-size:10px;font-weight:600;
        padding:2px 7px;border-radius:4px;
        white-space:nowrap;
        box-shadow:0 1px 4px rgba(0,0,0,0.5);
        border:1px solid rgba(255,255,255,0.08);
      ">${locationName}</div>
    </div>
  `;

  return L.divIcon({
    html,
    className: '',
    iconSize: [100, 58],
    iconAnchor: [50, 20],
    popupAnchor: [0, -24],
  });
}

/**
 * Inline score row used inside Leaflet popups (which render outside React tree via innerHTML).
 * Returns a plain DOM-compatible element.
 *
 * @param {object} props
 * @param {string} props.label
 * @param {number|null} props.score
 */
const SCORE_TOOLTIPS = {
  'Fiery Sky': 'Dramatic colour from clouds catching light',
  'Golden Hour': 'Overall light quality — can score high even with clear sky',
};

function PopupScoreRow({ label, score }) {
  const pct = score != null ? Math.min(100, Math.max(0, score)) : null;
  const barColour =
    pct == null  ? '#6B6B6B' :
    pct > 75     ? '#E5A00D' :
    pct > 50     ? '#CC8A00' :
    pct > 25     ? '#A06E00' :
                   '#6B6B6B';
  return (
    <div style={{ marginBottom: '4px' }} title={SCORE_TOOLTIPS[label] ?? ''}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: '#A0A0A0', marginBottom: '2px' }}>
        <span style={{ borderBottom: '1px dotted #6B6B6B' }}>{label}</span>
        <span style={{ fontWeight: '600', color: '#EBEBEB' }}>{pct != null ? pct : '—'}</span>
      </div>
      <div style={{ height: '6px', background: '#3A3D45', borderRadius: '9999px', overflow: 'hidden' }}>
        <div style={{ height: '100%', width: pct != null ? `${pct}%` : '0%', background: barColour, borderRadius: '9999px' }} />
      </div>
    </div>
  );
}

PopupScoreRow.propTypes = {
  label: PropTypes.string.isRequired,
  score: PropTypes.number,
};

/**
 * Map view showing all locations as score markers for a given date.
 * Selecting a marker draws orange (sunrise) and purple (sunset) azimuth lines.
 *
 * @param {object} props
 * @param {Array<{name: string, lat: number, lon: number, forecastsByDate: Map}>} props.locations
 * @param {string|null} props.date - The target date (YYYY-MM-DD) to display ratings for.
 */
const LOCATION_TYPE_LABELS = {
  LANDSCAPE: { label: 'Landscape', emoji: '🏔️' },
  WILDLIFE:  { label: 'Wildlife',  emoji: '🦅' },
  SEASCAPE:  { label: 'Seascape',  emoji: '🌊' },
};

export default function MapView({ locations, date }) {
  const { role } = useAuth();
  const [eventType, setEventType] = useState('SUNSET');
  const [selectedLocationName, setSelectedLocationName] = useState(null);
  const [zoom, setZoom] = useState(9);
  const [activeTypeFilters, setActiveTypeFilters] = useState(new Set());
  const [activeRatingFilters, setActiveRatingFilters] = useState(new Set());
  const [expandedPopup, setExpandedPopup] = useState(null);
  const [tideFetchedAt, setTideFetchedAt] = useState({});

  // Inject popup width styles
  useEffect(() => {
    const styleEl = document.createElement('style');
    styleEl.textContent = popupStyles;
    document.head.appendChild(styleEl);
    return () => styleEl.remove();
  }, []);

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

  const visibleLocations = activeRatingFilters.size === 0
    ? typeFiltered
    : typeFiltered.filter((loc) => {
        const rating = getRatingForLocation(loc);
        return rating != null && activeRatingFilters.has(rating);
      });

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

  return (
    <div className="flex flex-col gap-4">
      {/* Location type + star rating filter toggles */}
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-xs text-plex-text-muted mr-1">Filter:</span>
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
            {emoji} {label}
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
        {(activeTypeFilters.size > 0 || activeRatingFilters.size > 0) && (
          <button
            onClick={() => { setActiveTypeFilters(new Set()); setActiveRatingFilters(new Set()); }}
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
        style={{ height: '500px' }}
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

          {visibleLocations.map((loc) => {
            const dayData = loc.forecastsByDate.get(date);
            const forecast = eventType === 'SUNRISE' ? dayData?.sunrise : dayData?.sunset;
            const hourlyData = dayData?.hourly ?? [];
            const locIsPureWildlife = (loc.locationType ?? []).length > 0
              && (loc.locationType ?? []).every((t) => t === 'WILDLIFE');
            const icon = makeMarkerIcon(
              forecast?.rating ?? null,
              forecast?.fierySkyPotential ?? null,
              forecast?.goldenHourPotential ?? null,
              loc.name,
              locIsPureWildlife,
            );
            const isSunrise = eventType === 'SUNRISE';

            const eventTime  = forecast ? formatEventTimeUk(forecast.solarEventTime) : null;
            const goldenStart = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? 0 : -60) : null;
            const goldenEnd   = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? 60 : 0) : null;
            const blueStart   = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? -60 : 0) : null;
            const blueEnd     = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? 0 : 60) : null;

            const goldenPillStyle = { ...POPUP_PILL, background: '#451a03', color: '#fcd34d', border: '1px solid rgba(217,119,6,0.4)' };
            const bluePillStyle   = { ...POPUP_PILL, background: '#1e1b4b', color: '#a5b4fc', border: '1px solid rgba(99,102,241,0.4)' };

            const locTypes    = (loc.locationType ?? []).filter((t) => POPUP_LOC_TYPE_META[t]);
            const coastalTides = (loc.tideType ?? []).filter((t) => t !== 'NOT_COASTAL' && POPUP_TIDE_META[t]);

            return (
              <Marker
                key={loc.name}
                position={[loc.lat, loc.lon]}
                icon={icon}
                eventHandlers={{
                  click:      () => setSelectedLocationName(loc.name),
                  popupclose: () => { setSelectedLocationName(null); setExpandedPopup(null); },
                }}
              >
                <Popup maxWidth={9999} maxHeight={600}>
                  <div style={{ fontFamily: "'IBM Plex Sans', system-ui, sans-serif" }}>

                    {/* Row 1: Title + event time pill */}
                    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: '6px', marginBottom: '8px' }}>
                      <div style={{ fontWeight: '800', fontSize: '17px', color: '#0f172a' }}>
                        {loc.name}
                      </div>
                      {eventTime && (
                        <span style={{
                          ...POPUP_PILL,
                          background: isSunrise ? 'rgba(249,115,22,0.15)' : 'rgba(168,85,247,0.15)',
                          color:      isSunrise ? '#fb923c'                : '#c084fc',
                          border:     `1px solid ${isSunrise ? 'rgba(249,115,22,0.35)' : 'rgba(168,85,247,0.35)'}`,
                        }}>
                          {isSunrise ? '🌅' : '🌇'} {isSunrise ? 'Sunrise' : 'Sunset'} · {eventTime}
                        </span>
                      )}
                    </div>

                    {/* First glance: star rating + summary */}
                    {locIsPureWildlife ? (
                      hourlyData.length > 0 ? (
                        <div>
                          <div style={{ fontSize: '11px', fontWeight: '700', color: '#16a34a', marginBottom: '6px' }}>
                            🦅 Hourly comfort during daylight hours
                          </div>
                          <div style={{ display: 'table', width: '100%', fontSize: '11px', borderCollapse: 'collapse' }}>
                            {hourlyData.map((h) => (
                              <div key={h.solarEventTime} style={{ display: 'table-row' }}>
                                <div style={{ display: 'table-cell', color: '#6B6B6B', paddingRight: '8px', paddingBottom: '3px', whiteSpace: 'nowrap' }}>
                                  {formatEventTimeUk(h.solarEventTime)}
                                </div>
                                <div style={{ display: 'table-cell', paddingRight: '8px', paddingBottom: '3px', whiteSpace: 'nowrap' }}>
                                  <span style={{ display: 'inline-flex', alignItems: 'center' }}><ThermometerIcon />{h.temperatureCelsius != null ? `${Math.round(h.temperatureCelsius)}°C · feels ${Math.round(h.apparentTemperatureCelsius ?? h.temperatureCelsius)}°C` : '—'}</span>
                                </div>
                                <div style={{ display: 'table-cell', paddingRight: '8px', paddingBottom: '3px', whiteSpace: 'nowrap' }}>
                                  <span style={{ display: 'inline-flex', alignItems: 'center' }}><WindIcon />{h.windSpeed != null ? `${mpsToMph(h.windSpeed)} mph ${degreesToCompass(h.windDirection)}` : '—'}</span>
                                </div>
                                <div style={{ display: 'table-cell', paddingBottom: '3px', whiteSpace: 'nowrap' }}>
                                  <span style={{ display: 'inline-flex', alignItems: 'center' }}><RainIcon />{h.precipitationProbabilityPercent != null ? `${h.precipitationProbabilityPercent}%` : '—'}</span>
                                </div>
                              </div>
                            ))}
                          </div>
                        </div>
                      ) : (
                        <div style={{ fontSize: '12px', color: '#9ca3af', fontStyle: 'italic' }}>
                          No hourly forecast available
                        </div>
                      )
                    ) : forecast ? (
                      <>
                        <div style={{ marginBottom: '6px' }}>
                          {forecast.rating != null && (
                            <div style={{ fontSize: '14px', color: '#E5A00D', letterSpacing: '2px', marginBottom: '4px' }}>
                              {'★'.repeat(forecast.rating)}{'☆'.repeat(5 - forecast.rating)}
                              <span style={{ fontSize: '11px', color: '#6B6B6B', marginLeft: '6px', letterSpacing: 0 }}>
                                {forecast.rating}/5
                              </span>
                            </div>
                          )}
                        </div>
                        {forecast.summary && (
                          <div style={{ fontSize: '12px', lineHeight: '1.5', color: '#3A3D45', marginBottom: '8px' }}>
                            {forecast.summary}
                          </div>
                        )}

                        {/* "More details" toggle */}
                        <button
                          onClick={() => setExpandedPopup(expandedPopup === loc.name ? null : loc.name)}
                          style={{
                            background: 'none', border: 'none', cursor: 'pointer', padding: '4px 0',
                            fontSize: '11px', fontWeight: '600', color: '#6366f1',
                            display: 'flex', alignItems: 'center', gap: '4px', marginBottom: '6px',
                          }}
                        >
                          {expandedPopup === loc.name ? '▾ Less details' : '▸ More details'}
                        </button>

                        {expandedPopup === loc.name && (
                          <>
                            {/* Location type pills */}
                            {locTypes.length > 0 && (
                              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', marginBottom: '6px' }}>
                                {locTypes.map((t) => {
                                  const m = POPUP_LOC_TYPE_META[t];
                                  return (
                                    <span key={t} style={{ ...POPUP_PILL, background: '#252830', color: '#EBEBEB', border: '1px solid #374151' }}>
                                      {m.emoji} {m.label}
                                    </span>
                                  );
                                })}
                              </div>
                            )}

                            {/* Golden hour type */}
                            {loc.goldenHourType && POPUP_GOLDEN_HOUR_META[loc.goldenHourType] && (() => {
                              const m = POPUP_GOLDEN_HOUR_META[loc.goldenHourType];
                              return (
                                <div style={{ marginBottom: '6px' }}>
                                  <span style={{ ...POPUP_PILL, background: '#431407', color: '#fcd34d', border: '1px solid rgba(146,64,14,0.5)' }}>
                                    {m.emoji} {m.label}
                                  </span>
                                </div>
                              );
                            })()}

                            {/* Tide pills */}
                            {coastalTides.length > 0 && (
                              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', marginBottom: '6px' }}>
                                {coastalTides.map((t) => (
                                  <span key={t} style={{ ...POPUP_PILL, background: '#083344', color: '#67e8f9', border: '1px solid rgba(22,163,190,0.4)' }}>
                                    🌊 {POPUP_TIDE_META[t]}
                                  </span>
                                ))}
                              </div>
                            )}

                            {/* Daily tide schedule */}
                            <TideIndicator locationName={loc.name} date={date} onFetchedAt={(ts) => setTideFetchedAt((prev) => ({ ...prev, [loc.name]: ts }))} />

                            {/* Golden / Blue hour pills */}
                            {goldenStart && blueStart && (
                              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', marginBottom: '8px' }}>
                                {isSunrise ? (
                                  <>
                                    <span style={bluePillStyle}>
                                      <span style={{ fontSize: '9px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Blue</span>
                                      {blueStart}–{blueEnd}
                                    </span>
                                    <span style={goldenPillStyle}>
                                      <span style={{ fontSize: '9px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Golden</span>
                                      {goldenStart}–{goldenEnd}
                                    </span>
                                  </>
                                ) : (
                                  <>
                                    <span style={goldenPillStyle}>
                                      <span style={{ fontSize: '9px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Golden</span>
                                      {goldenStart}–{goldenEnd}
                                    </span>
                                    <span style={bluePillStyle}>
                                      <span style={{ fontSize: '9px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Blue</span>
                                      {blueStart}–{blueEnd}
                                    </span>
                                  </>
                                )}
                              </div>
                            )}

                            {/* Score bars */}
                            {role !== 'LITE_USER' && forecast.fierySkyPotential != null && (
                              <div style={{ marginBottom: '6px' }}>
                                <PopupScoreRow label="Fiery Sky" score={forecast.fierySkyPotential} />
                                <PopupScoreRow label="Golden Hour" score={forecast.goldenHourPotential} />
                              </div>
                            )}

                            {/* Comfort rows */}
                            {forecast.temperatureCelsius != null && (
                              <div style={{ borderTop: '1px solid #e5e7eb', paddingTop: '6px', marginTop: '4px', fontSize: '12px', color: '#3A3D45', lineHeight: '1.8' }}>
                                <div style={{ display: 'flex', alignItems: 'center' }}><ThermometerIcon /><strong>{Math.round(forecast.temperatureCelsius)}°C</strong>&nbsp;· feels like {Math.round(forecast.apparentTemperatureCelsius ?? forecast.temperatureCelsius)}°C</div>
                                <div style={{ display: 'flex', alignItems: 'center' }}><WindIcon /><strong>{mpsToMph(forecast.windSpeed)} mph</strong>&nbsp;{degreesToCompass(forecast.windDirection)}</div>
                                <div style={{ display: 'flex', alignItems: 'center' }}><RainIcon /><strong>{forecast.precipitationProbabilityPercent ?? 0}%</strong>&nbsp;rain chance</div>
                                {parseFloat(forecast.precipitation ?? 0) > 0 && (
                                  <div style={{ display: 'flex', alignItems: 'center' }}><DropletIcon /><strong>{parseFloat(forecast.precipitation).toFixed(1)} mm</strong>&nbsp;precip</div>
                                )}
                              </div>
                            )}

                            {/* Footer: generated at (non-admin only — admin sees it always below) */}
                            {role !== 'ADMIN' && forecast?.forecastRunAt && (
                              <div style={{ marginTop: '8px', paddingTop: '6px', borderTop: '1px solid #e5e7eb', fontSize: '10px', color: '#9ca3af' }}>
                                Forecast generated: {formatGeneratedAtFull(forecast.forecastRunAt)}{forecast.evaluationModel && forecast.evaluationModel !== 'WILDLIFE' && ` by ${forecast.evaluationModel.charAt(0) + forecast.evaluationModel.slice(1).toLowerCase()}`}
                              </div>
                            )}
                          </>
                        )}

                        {/* Footer: always visible for ADMIN */}
                        {role === 'ADMIN' && forecast?.forecastRunAt && (
                          <div style={{ marginTop: '8px', paddingTop: '6px', borderTop: '1px solid #e5e7eb', fontSize: '10px', color: '#9ca3af' }}>
                            Forecast generated: {formatGeneratedAtFull(forecast.forecastRunAt)}{forecast.evaluationModel && forecast.evaluationModel !== 'WILDLIFE' && ` by ${forecast.evaluationModel.charAt(0) + forecast.evaluationModel.slice(1).toLowerCase()}`}
                            {tideFetchedAt[loc.name] && (
                              <div>Tide data fetched: {formatGeneratedAtFull(tideFetchedAt[loc.name])}</div>
                            )}
                          </div>
                        )}
                      </>
                    ) : (
                      <div style={{ fontSize: '12px', color: '#9ca3af', fontStyle: 'italic' }}>
                        No forecast available
                      </div>
                    )}
                  </div>
                </Popup>
              </Marker>
            );
          })}
        </MapContainer>
      </div>
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
