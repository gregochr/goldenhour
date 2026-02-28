import React, { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import { MapContainer, TileLayer, Marker, Popup, Polyline, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useAuth } from '../context/AuthContext.jsx';

// Override Leaflet popup width
const popupStyles = `
  .leaflet-popup-content-wrapper {
    width: 600px !important;
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
function PopupScoreRow({ label, score }) {
  const pct = score != null ? Math.min(100, Math.max(0, score)) : null;
  const barColour =
    pct == null  ? '#6B6B6B' :
    pct > 75     ? '#E5A00D' :
    pct > 50     ? '#CC8A00' :
    pct > 25     ? '#A06E00' :
                   '#6B6B6B';
  return (
    <div style={{ marginBottom: '4px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: '#A0A0A0', marginBottom: '2px' }}>
        <span>{label}</span>
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

  // Filter logic: if no filters active → show all.
  // If filters active → show untagged locations plus those matching any active filter.
  const visibleLocations = activeTypeFilters.size === 0
    ? locations
    : locations.filter((loc) => {
        const types = loc.locationType ?? [];
        return types.length === 0 || types.some((t) => activeTypeFilters.has(t));
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
      {/* Location type filter toggles */}
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
        {activeTypeFilters.size > 0 && (
          <button
            onClick={() => setActiveTypeFilters(new Set())}
            className="px-3 py-1 text-xs font-medium rounded-full border border-plex-border text-plex-text-muted hover:text-plex-text-secondary transition-colors"
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
          {selectedLoc && sunriseAzimuth != null && (
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
          {selectedLoc && sunsetAzimuth != null && (
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
                  popupclose: () => setSelectedLocationName(null),
                }}
              >
                <Popup maxWidth={600} maxHeight={600}>
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

                    {/* Row 2: Location type pills */}
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

                    {/* Row 2.5: Golden hour type */}
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

                    {/* Row 3: Tide pills — hidden if none */}
                    {coastalTides.length > 0 && (
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', marginBottom: '6px' }}>
                        {coastalTides.map((t) => (
                          <span key={t} style={{ ...POPUP_PILL, background: '#083344', color: '#67e8f9', border: '1px solid rgba(22,163,190,0.4)' }}>
                            🌊 {POPUP_TIDE_META[t]}
                          </span>
                        ))}
                      </div>
                    )}

                    {/* Row 3.5: Daily tide schedule (coastal only, fetched from API) */}
                    <TideIndicator locationName={loc.name} date={date} />

                    {/* Row 4: Golden / Blue hour pills */}
                    {forecast && goldenStart && blueStart && (
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

                    {/* Scores / summary / comfort OR hourly wildlife timeline */}
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
                                  🌡 {h.temperatureCelsius != null ? `${Math.round(h.temperatureCelsius)}°C · feels ${Math.round(h.apparentTemperatureCelsius ?? h.temperatureCelsius)}°C` : '—'}
                                </div>
                                <div style={{ display: 'table-cell', paddingRight: '8px', paddingBottom: '3px', whiteSpace: 'nowrap' }}>
                                  💨 {h.windSpeed != null ? `${mpsToMph(h.windSpeed)} mph ${degreesToCompass(h.windDirection)}` : '—'}
                                </div>
                                <div style={{ display: 'table-cell', paddingBottom: '3px', whiteSpace: 'nowrap' }}>
                                  🌧 {h.precipitationProbabilityPercent != null ? `${h.precipitationProbabilityPercent}%` : '—'}
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
                          {role !== 'LITE_USER' && forecast.fierySkyPotential != null && (
                            <>
                              <PopupScoreRow label="Fiery Sky" score={forecast.fierySkyPotential} />
                              <PopupScoreRow label="Golden Hour" score={forecast.goldenHourPotential} />
                            </>
                          )}
                        </div>
                        {forecast.summary && (
                          <div style={{ fontSize: '12px', lineHeight: '1.5', color: '#3A3D45', marginBottom: '6px' }}>
                            {forecast.summary}
                          </div>
                        )}

                        {/* Comfort rows — shown on colour popups when data is available */}
                        {forecast.temperatureCelsius != null && (
                          <div style={{ borderTop: '1px solid #e5e7eb', paddingTop: '6px', marginTop: '4px', fontSize: '12px', color: '#3A3D45', lineHeight: '1.8' }}>
                            <div>🌡 <strong>{Math.round(forecast.temperatureCelsius)}°C</strong> · feels like {Math.round(forecast.apparentTemperatureCelsius ?? forecast.temperatureCelsius)}°C</div>
                            <div>💨 <strong>{mpsToMph(forecast.windSpeed)} mph</strong> {degreesToCompass(forecast.windDirection)}</div>
                            <div>🌧 <strong>{forecast.precipitationProbabilityPercent ?? 0}%</strong> rain chance</div>
                            {parseFloat(forecast.precipitation ?? 0) > 0 && (
                              <div>💧 <strong>{parseFloat(forecast.precipitation).toFixed(1)} mm</strong> precip</div>
                            )}
                          </div>
                        )}
                      </>
                    ) : (
                      <div style={{ fontSize: '12px', color: '#9ca3af', fontStyle: 'italic' }}>
                        No forecast available
                      </div>
                    )}

                    {/* Footer: generated at (colour forecasts only) */}
                    {!locIsPureWildlife && forecast?.forecastRunAt && (
                      <div style={{ marginTop: '8px', paddingTop: '6px', borderTop: '1px solid #e5e7eb', fontSize: '10px', color: '#9ca3af' }}>
                        Forecast generated: {formatGeneratedAtFull(forecast.forecastRunAt)}{forecast.evaluationModel && forecast.evaluationModel !== 'WILDLIFE' && ` by ${forecast.evaluationModel.charAt(0) + forecast.evaluationModel.slice(1).toLowerCase()}`}
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
