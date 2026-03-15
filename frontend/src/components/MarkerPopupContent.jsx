import React, { useState } from 'react';
import PropTypes from 'prop-types';
import {
  formatEventTimeUk,
  formatShiftedEventTimeUk,
  formatGeneratedAtFull,
  mpsToMph,
  degreesToCompass,
} from '../utils/conversions.js';
import TideIndicator from './TideIndicator.jsx';
import InfoTip from './InfoTip.jsx';

/** Inline SVG weather icons for comfort rows. */
const ICON_STYLE = { width: '14px', height: '14px', verticalAlign: 'middle', marginRight: '3px', flexShrink: 0 };

/** @returns {React.ReactElement} Thermometer SVG icon. */
function ThermometerIcon() {
  return (
    <svg style={ICON_STYLE} viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0z" />
    </svg>
  );
}

/** @returns {React.ReactElement} Wind SVG icon. */
function WindIcon() {
  return (
    <svg style={ICON_STYLE} viewBox="0 0 24 24" fill="none" stroke="#60a5fa" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17.7 7.7a2.5 2.5 0 1 1 1.8 4.3H2" />
      <path d="M9.6 4.6A2 2 0 1 1 11 8H2" />
      <path d="M12.6 19.4A2 2 0 1 0 14 16H2" />
    </svg>
  );
}

/** @returns {React.ReactElement} Rain cloud SVG icon. */
function RainIcon() {
  return (
    <svg style={ICON_STYLE} viewBox="0 0 24 24" fill="none" stroke="#38bdf8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242" />
      <path d="M16 14v6" /><path d="M8 14v6" /><path d="M12 16v6" />
    </svg>
  );
}

/** @returns {React.ReactElement} Droplet SVG icon. */
function DropletIcon() {
  return (
    <svg style={ICON_STYLE} viewBox="0 0 24 24" fill="none" stroke="#38bdf8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z" />
    </svg>
  );
}

/** Base style shared by all popup pills. */
const POPUP_PILL = {
  display: 'inline-flex', alignItems: 'center', gap: '4px',
  fontSize: '11px', padding: '2px 8px', borderRadius: '999px',
  fontWeight: '600',
};

const POPUP_LOC_TYPE_META = {
  LANDSCAPE:  { emoji: '🏔️', label: 'Landscape' },
  WILDLIFE:   { emoji: '🐾', label: 'Wildlife' },
  SEASCAPE:   { emoji: '🌊', label: 'Seascape' },
  WATERFALL:  { emoji: '💦', label: 'Waterfall' },
};

const POPUP_SOLAR_EVENT_META = {
  SUNRISE: { emoji: '🌅', label: 'Sunrise' },
  SUNSET:  { emoji: '🌇', label: 'Sunset' },
  ALLDAY:  { emoji: '☀️',  label: 'All Day' },
};

const POPUP_TIDE_META = {
  HIGH: 'High tide',
  MID:  'Mid tide',
  LOW:  'Low tide',
};

/**
 * Returns the number of minutes between two ISO datetime strings.
 *
 * @param {string} a - ISO datetime string.
 * @param {string} b - ISO datetime string.
 * @returns {number} Minutes from a to b (positive if b is after a).
 */
function minutesBetween(a, b) {
  return (new Date(b) - new Date(a)) / 60000;
}

/**
 * Returns rising tide warning info when a HIGH tide falls within ±90 min of the
 * solar event (covering the full blue+golden hour window) AND the tide is incoming
 * is after the solar event for sunrise, or before for sunset).
 *
 * @param {object} f - Forecast evaluation row with solarEventTime and nextHighTideTime.
 * @param {string} eventType - 'SUNRISE' or 'SUNSET'.
 * @returns {{minutesAway: number, highTideTime: string}|null} Info if warning applies, null otherwise.
 */
function getRisingTideWarning(f, eventType) {
  if (!f || !f.solarEventTime || !f.nextHighTideTime) return null;

  const diffMins = minutesBetween(f.solarEventTime, f.nextHighTideTime);

  if (eventType === 'SUNRISE') {
    // Sunrise: tide is rising if high tide is up to 90 min AFTER sunrise (golden hour+)
    // Also warn if high tide is up to 90 min BEFORE sunrise (tide rushing in during blue hour)
    if (diffMins >= -90 && diffMins <= 90) {
      return { minutesAway: Math.round(diffMins), highTideTime: f.nextHighTideTime };
    }
  } else {
    // Sunset: tide is rising if high tide is up to 90 min AFTER sunset (blue hour+)
    // Also warn if high tide is up to 90 min BEFORE sunset (golden hour)
    if (diffMins >= -90 && diffMins <= 90) {
      return { minutesAway: Math.round(diffMins), highTideTime: f.nextHighTideTime };
    }
  }
  return null;
}

/**
 * Returns true when aerosol data suggests elevated mineral dust likely to
 * enhance warm tones (high AOD/dust with low PM2.5 rules out smoke/haze).
 *
 * @param {object} f - Forecast evaluation row.
 * @returns {boolean} True if dust is elevated and PM2.5 is low.
 */
function isDustEnhanced(f) {
  const aodHigh  = f.aerosolOpticalDepth != null && f.aerosolOpticalDepth > 0.3;
  const dustHigh = f.dust != null && f.dust > 50;
  const pm25Low  = f.pm25 == null || f.pm25 < 35;
  return (aodHigh || dustHigh) && pm25Low;
}

const SCORE_TOOLTIPS = {
  'Fiery Sky': 'Dramatic colour from clouds catching light',
  'Golden Hour': 'Overall light quality — can score high even with clear sky',
};

/**
 * Inline score bar used inside both Leaflet popups and the mobile bottom sheet.
 *
 * @param {object} props
 * @param {string} props.label - Score label (e.g. "Fiery Sky").
 * @param {number|null} props.score - Score value 0–100, or null.
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
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', borderBottom: '1px dotted #6B6B6B' }}>
          {label}
          {SCORE_TOOLTIPS[label] && <InfoTip text={SCORE_TOOLTIPS[label]} />}
        </span>
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
 * Marker detail content used inside both the Leaflet popup (desktop) and the
 * BottomSheet (mobile). Pure presentational — no positioning logic.
 *
 * @param {object} props
 * @param {object} props.location - Location object with name, solarEventType, locationType, tideType.
 * @param {object|null} props.forecast - The sunrise or sunset forecast evaluation.
 * @param {Array} props.hourlyData - Hourly comfort data rows.
 * @param {string} props.eventType - 'SUNRISE' or 'SUNSET'.
 * @param {boolean} props.isPureWildlife - True if all location types are WILDLIFE.
 * @param {boolean} [props.showComfortRows=false] - True to show hourly comfort alongside colour forecast (e.g. WATERFALL).
 * @param {boolean} [props.isExpanded] - Ignored (expanded state is now internal).
 * @param {function} [props.onToggleExpanded] - Ignored (expanded state is now internal).
 * @param {string} props.role - User role (ADMIN, PRO_USER, LITE_USER).
 * @param {string} props.date - Selected date string (YYYY-MM-DD).
 * @param {function} props.onTideFetchedAt - Called with fetchedAt timestamp from TideIndicator.
 * @param {string|null} props.tideFetchedAt - Previously fetched tide timestamp for footer display.
 * @param {boolean} [props.darkMode=false] - True when rendered on a dark surface (e.g. BottomSheet).
 */
export default function MarkerPopupContent({
  location,
  forecast,
  hourlyData,
  eventType,
  isPureWildlife,
  showComfortRows = false,
  isExpanded: _isExpanded,
  onToggleExpanded: _onToggleExpanded,
  role,
  date,
  onTideFetchedAt,
  tideFetchedAt,
  onTideClassification,
  tideClassification,
  darkMode = false,
}) {
  const [isExpanded, setIsExpanded] = useState(false);
  const onToggleExpanded = () => setIsExpanded((prev) => !prev);

  const isSunrise = eventType === 'SUNRISE';
  const risingTide = forecast ? getRisingTideWarning(forecast, eventType) : null;
  const eventTime  = forecast ? formatEventTimeUk(forecast.solarEventTime) : null;
  const goldenStart = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? 0 : -60) : null;
  const goldenEnd   = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? 60 : 0) : null;
  const blueStart   = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? -60 : 0) : null;
  const blueEnd     = forecast ? formatShiftedEventTimeUk(forecast.solarEventTime, isSunrise ? 0 : 60) : null;

  const goldenPillStyle = { ...POPUP_PILL, background: '#451a03', color: '#fcd34d', border: '1px solid rgba(217,119,6,0.4)' };
  const bluePillStyle   = { ...POPUP_PILL, background: '#1e1b4b', color: '#a5b4fc', border: '1px solid rgba(99,102,241,0.4)' };

  const locTypes    = (location.locationType ?? []).filter((t) => POPUP_LOC_TYPE_META[t]);
  const solarTypes  = (location.solarEventType ?? []).filter((t) => POPUP_SOLAR_EVENT_META[t]);
  const coastalTides = (location.tideType ?? []).filter((t) => POPUP_TIDE_META[t]);

  return (
    <div style={{ fontFamily: "'IBM Plex Sans', system-ui, sans-serif" }}>

      {/* Row 1: Title + event time pill */}
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: '6px', marginBottom: '8px' }}>
        <div style={{ fontWeight: '800', fontSize: '17px', color: darkMode ? '#EBEBEB' : '#0f172a' }}>
          {location.name}
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
      {isPureWildlife ? (
        hourlyData.length > 0 ? (
          <div>
            <div style={{ fontSize: '11px', fontWeight: '700', color: '#16a34a', marginBottom: '6px' }}>
              🐾 Hourly comfort during daylight hours
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
          {isDustEnhanced(forecast) && (
            <div style={{ marginBottom: '6px' }} data-testid="dust-badge">
              <span style={{
                ...POPUP_PILL,
                background: 'rgba(180, 83, 9, 0.2)',
                color: '#fbbf24',
                border: '1px solid rgba(217, 119, 6, 0.4)',
              }}>
                🏜️ Elevated dust (e.g. Saharan dust) — expect unusually vivid warm tones
              </span>
            </div>
          )}
          {risingTide && (
            <div style={{ marginBottom: '6px' }} data-testid="rising-tide-badge">
              <span style={{
                ...POPUP_PILL,
                background: 'rgba(245, 158, 11, 0.15)',
                color: '#fbbf24',
                border: '1px solid rgba(245, 158, 11, 0.4)',
              }}>
                ⚠️ Rising tide — high at {formatEventTimeUk(risingTide.highTideTime)}
                {risingTide.minutesAway > 0
                  ? ` (${risingTide.minutesAway} min after ${isSunrise ? 'sunrise' : 'sunset'})`
                  : risingTide.minutesAway < 0
                    ? ` (${Math.abs(risingTide.minutesAway)} min before ${isSunrise ? 'sunrise' : 'sunset'})`
                    : ` (at ${isSunrise ? 'sunrise' : 'sunset'})`}
              </span>
            </div>
          )}
          {tideClassification && tideClassification.map((tc) => {
            const isKing = tc.isKing;
            const near = tc.nearSolarEvent;
            const label = isKing ? 'King tide' : 'Spring tide';
            const emoji = isKing ? '👑' : '🌊';
            return (
              <div key={tc.time} style={{ marginBottom: '6px' }} data-testid={isKing ? 'king-tide-badge' : 'spring-tide-badge'}>
                <span style={{
                  ...POPUP_PILL,
                  background: near
                    ? (isKing ? 'rgba(220, 38, 38, 0.15)' : 'rgba(245, 158, 11, 0.15)')
                    : 'rgba(107, 114, 128, 0.15)',
                  color: near
                    ? (isKing ? '#fca5a5' : '#fbbf24')
                    : '#9ca3af',
                  border: `1px solid ${near
                    ? (isKing ? 'rgba(220, 38, 38, 0.4)' : 'rgba(245, 158, 11, 0.4)')
                    : 'rgba(107, 114, 128, 0.3)'}`,
                }}>
                  {near
                    ? `${emoji} ${label} — high at ${formatEventTimeUk(tc.time)} (${tc.height.toFixed(1)}m)`
                    : `${emoji} ${label} today — but outside golden/blue hours (high at ${formatEventTimeUk(tc.time)}, ${tc.height.toFixed(1)}m)`}
                </span>
              </div>
            );
          })}
          {forecast.summary && (
            <div style={{ fontSize: '12px', lineHeight: '1.5', color: darkMode ? '#A0A0A0' : '#3A3D45', marginBottom: '8px' }}>
              {forecast.summary}
            </div>
          )}

          {/* "More details" toggle */}
          <button
            data-testid="more-details-toggle"
            onClick={onToggleExpanded}
            style={{
              background: 'none', border: 'none', cursor: 'pointer', padding: '4px 0',
              fontSize: '11px', fontWeight: '600', color: '#6366f1',
              display: 'flex', alignItems: 'center', gap: '4px', marginBottom: '6px',
            }}
          >
            {isExpanded ? '▾ Less details' : '▸ More details'}
          </button>

          {isExpanded && (
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

              {/* Solar event type pills */}
              {solarTypes.length > 0 && (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', marginBottom: '6px' }}>
                  {solarTypes.map((t) => {
                    const m = POPUP_SOLAR_EVENT_META[t];
                    return (
                      <span key={t} style={{ ...POPUP_PILL, background: '#431407', color: '#fcd34d', border: '1px solid rgba(146,64,14,0.5)' }}>
                        {m.emoji} {m.label}
                      </span>
                    );
                  })}
                </div>
              )}

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
              <TideIndicator
                locationName={location.name}
                date={date}
                onFetchedAt={onTideFetchedAt}
                solarEventTime={forecast?.solarEventTime}
                onTideClassification={onTideClassification}
              />

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
                  <div style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '10px', color: '#6B6B6B', marginBottom: '4px' }}>
                    <span>Scores</span>
                    <InfoTip text="Fiery Sky measures dramatic colour from clouds catching light. Golden Hour measures overall light quality and can score high even with clear sky." />
                  </div>
                  <PopupScoreRow label="Fiery Sky" score={forecast.fierySkyPotential} />
                  <PopupScoreRow label="Golden Hour" score={forecast.goldenHourPotential} />
                </div>
              )}

              {/* Comfort rows */}
              {forecast.temperatureCelsius != null && (
                <div style={{ borderTop: `1px solid ${darkMode ? '#3A3D45' : '#e5e7eb'}`, paddingTop: '6px', marginTop: '4px', fontSize: '12px', color: darkMode ? '#A0A0A0' : '#3A3D45', lineHeight: '1.8' }}>
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
                <div style={{ marginTop: '8px', paddingTop: '6px', borderTop: `1px solid ${darkMode ? '#3A3D45' : '#e5e7eb'}`, fontSize: '10px', color: '#9ca3af' }}>
                  Forecast generated: {formatGeneratedAtFull(forecast.forecastRunAt)}{forecast.evaluationModel && forecast.evaluationModel !== 'WILDLIFE' && ` by ${forecast.evaluationModel.charAt(0) + forecast.evaluationModel.slice(1).toLowerCase()}`}
                </div>
              )}
            </>
          )}

          {/* Hourly comfort rows for waterfall locations */}
          {showComfortRows && hourlyData.length > 0 && (
            <div style={{ borderTop: `1px solid ${darkMode ? '#3A3D45' : '#e5e7eb'}`, paddingTop: '6px', marginTop: '6px' }}>
              <div style={{ fontSize: '11px', fontWeight: '700', color: '#38bdf8', marginBottom: '6px' }}>
                💦 Hourly comfort during daylight hours
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
          )}

          {/* Footer: always visible for ADMIN */}
          {role === 'ADMIN' && forecast?.forecastRunAt && (
            <div style={{ marginTop: '8px', paddingTop: '6px', borderTop: `1px solid ${darkMode ? '#3A3D45' : '#e5e7eb'}`, fontSize: '10px', color: '#9ca3af' }}>
              Forecast generated: {formatGeneratedAtFull(forecast.forecastRunAt)}{forecast.evaluationModel && forecast.evaluationModel !== 'WILDLIFE' && ` by ${forecast.evaluationModel.charAt(0) + forecast.evaluationModel.slice(1).toLowerCase()}`}
              {tideFetchedAt && (
                <div>Tide data fetched: {formatGeneratedAtFull(tideFetchedAt)}</div>
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
  );
}

MarkerPopupContent.propTypes = {
  location: PropTypes.shape({
    name: PropTypes.string.isRequired,
    solarEventType: PropTypes.arrayOf(PropTypes.string),
    locationType: PropTypes.arrayOf(PropTypes.string),
    tideType: PropTypes.arrayOf(PropTypes.string),
  }).isRequired,
  forecast: PropTypes.object,
  hourlyData: PropTypes.array.isRequired,
  eventType: PropTypes.oneOf(['SUNRISE', 'SUNSET']).isRequired,
  isPureWildlife: PropTypes.bool.isRequired,
  showComfortRows: PropTypes.bool,
  isExpanded: PropTypes.bool,
  onToggleExpanded: PropTypes.func,
  role: PropTypes.string.isRequired,
  date: PropTypes.string.isRequired,
  onTideFetchedAt: PropTypes.func,
  tideFetchedAt: PropTypes.string,
  onTideClassification: PropTypes.func,
  tideClassification: PropTypes.arrayOf(PropTypes.shape({
    time: PropTypes.string.isRequired,
    height: PropTypes.number.isRequired,
    isSpring: PropTypes.bool.isRequired,
    isKing: PropTypes.bool.isRequired,
    nearSolarEvent: PropTypes.bool.isRequired,
  })),
  darkMode: PropTypes.bool,
};
