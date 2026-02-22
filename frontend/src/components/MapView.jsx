import React, { useState } from 'react';
import PropTypes from 'prop-types';
import { MapContainer, TileLayer, Marker, Popup } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { formatEventTimeUk, formatShiftedEventTimeUk } from '../utils/conversions.js';

const RATING_COLOURS = {
  1: '#6b7280',
  2: '#92400e',
  3: '#d97706',
  4: '#f59e0b',
  5: '#fbbf24',
};

/**
 * Creates a custom Leaflet DivIcon for a location marker.
 *
 * @param {number|null} rating - 1-5 rating, or null if unavailable.
 * @param {string} locationName - Display name shown beneath the marker.
 * @returns {L.DivIcon}
 */
function makeMarkerIcon(rating, locationName) {
  const colour = RATING_COLOURS[rating] ?? '#374151';
  const label = rating ?? '?';

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
 * Map view showing all locations as rating markers for a given date.
 * Includes a Sunrise / Sunset toggle to switch which event type is displayed.
 *
 * @param {object} props
 * @param {Array<{name: string, lat: number, lon: number, forecastsByDate: Map}>} props.locations
 * @param {string|null} props.date - The target date (YYYY-MM-DD) to display ratings for.
 */
export default function MapView({ locations, date }) {
  const [eventType, setEventType] = useState('SUNSET');

  if (!date || locations.length === 0) {
    return (
      <p className="text-gray-500 text-sm text-center py-8">
        No forecast data available.
      </p>
    );
  }

  const bounds = locations.map((loc) => [loc.lat, loc.lon]);

  return (
    <div className="flex flex-col gap-4">
      {/* Sunrise / Sunset radio toggle */}
      <div className="flex items-center gap-6">
        <label className="flex items-center gap-2 text-sm text-gray-300 cursor-pointer select-none">
          <input
            type="radio"
            name="map-event-type"
            value="SUNRISE"
            checked={eventType === 'SUNRISE'}
            onChange={() => setEventType('SUNRISE')}
            className="accent-orange-400"
          />
          🌅 Sunrise
        </label>
        <label className="flex items-center gap-2 text-sm text-gray-300 cursor-pointer select-none">
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

          {locations.map((loc) => {
            const dayData = loc.forecastsByDate.get(date);
            const forecast = eventType === 'SUNRISE' ? dayData?.sunrise : dayData?.sunset;
            const icon = makeMarkerIcon(forecast?.rating ?? null, loc.name);
            const isSunrise = eventType === 'SUNRISE';

            const eventTime = forecast ? formatEventTimeUk(forecast.solarEventTime) : null;
            const goldenStart = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? 0 : -60) : null;
            const goldenEnd   = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? 60 : 0) : null;
            const blueStart   = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? -60 : 0) : null;
            const blueEnd     = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? 0 : 60) : null;

            const pillBase = {
              display: 'inline-flex', alignItems: 'center', gap: '4px',
              fontSize: '10px', padding: '2px 7px', borderRadius: '999px',
              marginRight: '4px', fontWeight: '600',
            };
            const goldenPillStyle = { ...pillBase, background: '#451a03', color: '#fcd34d', border: '1px solid rgba(217,119,6,0.4)' };
            const bluePillStyle   = { ...pillBase, background: '#1e1b4b', color: '#a5b4fc', border: '1px solid rgba(99,102,241,0.4)' };

            return (
              <Marker key={loc.name} position={[loc.lat, loc.lon]} icon={icon}>
                <Popup>
                  <div style={{ minWidth: '220px', fontFamily: 'system-ui, sans-serif' }}>
                    {/* Header */}
                    <div style={{ fontWeight: '700', fontSize: '14px', marginBottom: '2px' }}>
                      {loc.name}
                    </div>
                    <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: eventTime ? '2px' : '8px' }}>
                      {isSunrise ? '🌅 Sunrise' : '🌇 Sunset'}
                      {eventTime && (
                        <span style={{ marginLeft: '6px', color: '#9ca3af' }}>{eventTime}</span>
                      )}
                    </div>

                    {/* Golden / Blue hour pills */}
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

                    {/* Rating + summary */}
                    {forecast ? (
                      <>
                        <div style={{ fontSize: '16px', color: RATING_COLOURS[forecast.rating], marginBottom: '6px' }}>
                          {'★'.repeat(forecast.rating)}{'☆'.repeat(5 - forecast.rating)}
                        </div>
                        <div style={{ fontSize: '12px', lineHeight: '1.5', color: '#374151' }}>
                          {forecast.summary}
                        </div>
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
    })
  ).isRequired,
  date: PropTypes.string,
};
