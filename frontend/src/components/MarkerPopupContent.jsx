import React, { useState } from 'react';
import PropTypes from 'prop-types';
import {
  formatEventTimeUk,
  formatGeneratedAtFull,
  mpsToMph,
  degreesToCompass,
} from '../utils/conversions.js';
import { runForecast } from '../api/forecastApi.js';
import createEventSource from '../utils/createEventSource.js';
import { bortleLabel } from '../utils/conversions.js';
import TideIndicator from './TideIndicator.jsx';
import InfoTip from './InfoTip.jsx';

/**
 * User-facing label for each TriageReason enum value.
 */
const TRIAGE_REASON_LABELS = {
  HIGH_CLOUD: 'heavy low cloud at the solar horizon',
  PRECIPITATION: 'active precipitation forecast at event time',
  LOW_VISIBILITY: 'visibility too low (fog or heavy haze)',
  TIDE_MISALIGNED: 'tide not aligned with location preference',
  GENERIC: 'regional conditions predicted poor',
};

/**
 * Builds the "Forecast generated" footer text. For triaged forecasts,
 * appends a note that Claude was not invoked.
 */
function buildGeneratedFooter(forecast) {
  const base = formatGeneratedAtFull(forecast.forecastRunAt);
  const model = forecast.evaluationModel && forecast.evaluationModel !== 'WILDLIFE'
    ? forecast.evaluationModel.charAt(0) + forecast.evaluationModel.slice(1).toLowerCase()
    : null;

  if (forecast.triageReason) {
    const reason = TRIAGE_REASON_LABELS[forecast.triageReason] ?? TRIAGE_REASON_LABELS.GENERIC;
    return model
      ? `Forecast generated: ${base} (${model} run, but not evaluated by Claude due to ${reason})`
      : `Forecast generated: ${base} (not evaluated by Claude due to ${reason})`;
  }

  return model
    ? `Forecast generated: ${base} by ${model}`
    : `Forecast generated: ${base}`;
}

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

/**
 * Formats a drive duration in minutes as "X hr Y mins", "X hrs", or "Y mins".
 * Returns null for 0 (Home) or null input.
 *
 * @param {number|null} minutes
 * @returns {string|null}
 */
function formatDriveTime(minutes) {
  if (minutes == null || minutes === 0) return null;
  if (minutes < 60) return `${minutes} mins`;
  const hrs = Math.floor(minutes / 60);
  const mins = minutes % 60;
  if (mins === 0) return `${hrs} hr${hrs > 1 ? 's' : ''}`;
  return `${hrs} hr${hrs > 1 ? 's' : ''} ${mins} mins`;
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
 * Score-bar fills run muted-grey (low) → hot colour (high), so a higher score
 * both fills further AND glows hotter. The gradient is sized to the full track
 * and the unfilled remainder is masked, so a low score shows only the muted end.
 */
const FIERY_FILL = 'linear-gradient(90deg, #B5A06A, #E0A542 45%, #C8452F)';
const GOLDEN_FILL = 'linear-gradient(90deg, #6B6453, #C88E2E 45%, #F5C518)';

/** Value-number tint ramps (low → high), matching each bar's direction. */
const FIERY_TINT = ['#8A8175', '#E0A542', '#E86A4A'];
const GOLDEN_TINT = ['#8A8175', '#C88E2E', '#F5C518'];

/** Linear interpolation between two #rrggbb hex colours, returning an rgb() string. */
function lerpHex(a, b, t) {
  const pa = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16));
  const pb = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16));
  const c = pa.map((v, i) => Math.round(v + (pb[i] - v) * t));
  return `rgb(${c[0]}, ${c[1]}, ${c[2]})`;
}

/** Maps a 0–100 score onto a 3-stop colour ramp (stops at 0/50/100). */
function rampTint(stops, pct) {
  const t = Math.min(1, Math.max(0, pct / 100));
  return t <= 0.5 ? lerpHex(stops[0], stops[1], t / 0.5) : lerpHex(stops[1], stops[2], (t - 0.5) / 0.5);
}

/**
 * Inline score bar used inside both Leaflet popups and the mobile bottom sheet.
 *
 * @param {object} props
 * @param {string} props.label - Score label (e.g. "Fiery Sky").
 * @param {number|null} props.score - Score value 0–100, or null.
 */
function PopupScoreRow({ label, score }) {
  const pct = score != null ? Math.min(100, Math.max(0, score)) : null;
  const isFiery = label === 'Fiery Sky';
  const fill = isFiery ? FIERY_FILL : GOLDEN_FILL;
  const numberTint = pct == null
    ? 'var(--color-plex-text-muted)'
    : rampTint(isFiery ? FIERY_TINT : GOLDEN_TINT, pct);
  return (
    <div style={{ marginBottom: '4px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: 'var(--color-plex-text-secondary)', marginBottom: '2px' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', borderBottom: '1px dotted var(--color-plex-text-muted)' }}>
          {label}
          {SCORE_TOOLTIPS[label] && <InfoTip text={SCORE_TOOLTIPS[label]} />}
        </span>
        <span style={{ fontWeight: '600', fontFamily: "'IBM Plex Mono', monospace", color: numberTint }}>{pct != null ? pct : '—'}</span>
      </div>
      <div style={{ position: 'relative', height: '6px', background: fill, borderRadius: '999px', overflow: 'hidden' }}>
        <div
          style={{
            position: 'absolute', top: 0, right: 0, height: '100%',
            width: pct != null ? `${100 - pct}%` : '100%',
            background: 'var(--color-plex-surface-light)',
          }}
        />
      </div>
    </div>
  );
}

PopupScoreRow.propTypes = {
  label: PropTypes.string.isRequired,
  score: PropTypes.number,
};

/**
 * Stand-down badge used when a location has been triaged out by weather.
 * Same dark-red styling regardless of whether the verdict came from a full
 * `forecast` row or from the briefing evaluation cache (scored-forecast branch
 * vs. empty-forecast branch).
 *
 * @param {object} props
 * @param {string} props.triageReason - TriageReason enum value.
 * @param {string|null} props.triageMessage - Formatted stand-down reason.
 */
function StandDownBadge({ triageReason, triageMessage }) {
  return (
    <div
      data-testid="triage-standdown-badge"
      style={{
        fontSize: '12px',
        lineHeight: '1.5',
        padding: '6px 10px',
        marginBottom: '8px',
        background: 'rgba(80, 19, 19, 0.18)',
        border: '1px solid rgba(163, 45, 45, 0.5)',
        borderRadius: '6px',
        color: '#f1d6d6',
      }}
    >
      <div style={{ fontWeight: 700, marginBottom: '2px' }}>
        Stand-down — {TRIAGE_REASON_LABELS[triageReason] ?? TRIAGE_REASON_LABELS.GENERIC}
      </div>
      {triageMessage && <div style={{ fontWeight: 400 }}>{triageMessage}</div>}
    </div>
  );
}

StandDownBadge.propTypes = {
  triageReason: PropTypes.string.isRequired,
  triageMessage: PropTypes.string,
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
 * @param {string} props.role - User role (ADMIN, PRO_USER, LITE_USER).
 * @param {string} props.date - Selected date string (YYYY-MM-DD).
 * @param {function} props.onTideFetchedAt - Called with fetchedAt timestamp from TideIndicator.
 * @param {string|null} props.tideFetchedAt - Previously fetched tide timestamp for footer display.
 * @param {object|null} [props.briefingScore] - Briefing evaluation result for this location/date/event.
 *   When `forecast` is null but `briefingScore.triageReason` is set, the popover renders a
 *   stand-down branch instead of the "no forecast yet" empty state.
 */
export default function MarkerPopupContent({
  location,
  forecast,
  hourlyData,
  eventType,
  isPureWildlife,
  showComfortRows = false,
  role,
  date,
  onTideFetchedAt,
  tideFetchedAt,
  onTideClassification,
  tideClassification,
  auroraScore = null,
  isAuroraMode = false,
  astroScore = null,
  isAstroMode = false,
  onForecastRun,
  driveMinutes = null,
  briefingScore = null,
  travelDay = false,
}) {
  const [isExpanded, setIsExpanded] = useState(false);
  const [runningForecast, setRunningForecast] = useState(false);
  const [runProgress, setRunProgress] = useState('');
  const [forecastError, setForecastError] = useState(null);

  /** Triggers a single-location, single-date, single-event forecast run and tracks via SSE. */
  const handleRunForecast = async () => {
    setRunningForecast(true);
    setRunProgress('');
    setForecastError(null);
    try {
      const { jobRunId } = await runForecast(date, location.name, eventType);
      createEventSource(
        '/api/forecast/run/' + jobRunId + '/progress',
        {},
        {
          'run-summary': (data) => {
            setRunProgress(`${data.completed}/${data.total}`);
          },
          'run-complete': (data) => {
            setRunningForecast(false);
            if (data.failed > 0) {
              setForecastError('Forecast run failed');
            } else {
              onForecastRun?.();
            }
          },
        },
        {
          closeOn: 'run-complete',
          onError: () => {
            setRunningForecast(false);
            setForecastError('Lost connection to server');
          },
        },
      );
    } catch (err) {
      setRunningForecast(false);
      setForecastError(err.response?.data?.message || err.message || 'Failed to start forecast run');
    }
  };
  const onToggleExpanded = () => setIsExpanded((prev) => !prev);

  const isSunrise = eventType === 'SUNRISE';
  const risingTide = forecast ? getRisingTideWarning(forecast, eventType) : null;
  const eventTime  = forecast ? formatEventTimeUk(forecast.solarEventTime) : null;
  const goldenStart = forecast ? formatEventTimeUk(forecast.goldenHourStart) : null;
  const goldenEnd   = forecast ? formatEventTimeUk(forecast.goldenHourEnd) : null;
  const blueStart   = forecast ? formatEventTimeUk(forecast.blueHourStart) : null;
  const blueEnd     = forecast ? formatEventTimeUk(forecast.blueHourEnd) : null;

  const goldenPillStyle = { ...POPUP_PILL, background: 'rgba(224,165,66,0.12)', color: 'var(--color-verdict-marginal)', border: '1px solid rgba(224,165,66,0.28)', fontFamily: "'IBM Plex Mono', monospace" };
  const bluePillStyle   = { ...POPUP_PILL, background: 'rgba(124,141,214,0.12)', color: '#aab4e6', border: '1px solid rgba(124,141,214,0.28)', fontFamily: "'IBM Plex Mono', monospace" };

  const locTypes    = (location.locationType ?? []).filter((t) => POPUP_LOC_TYPE_META[t]);
  const solarTypes  = (location.solarEventType ?? []).filter((t) => POPUP_SOLAR_EVENT_META[t]);
  const coastalTides = (location.tideType ?? []).filter((t) => POPUP_TIDE_META[t]);

  return (
    <div style={{ fontFamily: "'IBM Plex Sans', system-ui, sans-serif" }}>

      {/* Row 1: Title + drive time + event time pill */}
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: '6px', marginBottom: '8px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: '8px' }}>
          <div style={{ fontWeight: '700', fontSize: '18px', letterSpacing: '-0.01em', color: 'var(--color-plex-text)' }}>
            {location.name}
          </div>
        </div>
        {eventTime && (
          <span style={{
            ...POPUP_PILL,
            fontFamily: "'IBM Plex Mono', monospace",
            background: 'rgba(124,141,214,0.14)',
            color: '#aab4e6',
            border: '1px solid rgba(124,141,214,0.3)',
          }}>
            {isSunrise ? '🌅' : '🌇'} {isSunrise ? 'Sunrise' : 'Sunset'} · {eventTime}
          </span>
        )}
      </div>

      {/* Travel-day notice: the overnight batch skips Claude forecasts on days
          you're away, so explain the absence rather than showing a blank slot. */}
      {travelDay && (
        <div
          data-testid="popup-travel-day-notice"
          style={{
            display: 'flex', alignItems: 'center', gap: '6px',
            background: 'rgba(148,113,74,0.15)', color: '#c5a46f',
            border: '1px solid rgba(148,113,74,0.4)', borderRadius: '8px',
            padding: '7px 10px', fontSize: '12px', marginBottom: '8px',
          }}
        >
          ✈️ <span>Forecast not run — you&apos;re away (travel day).</span>
        </div>
      )}

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
                  <div style={{ display: 'table-cell', color: 'var(--color-plex-text-muted)', paddingRight: '8px', paddingBottom: '3px', whiteSpace: 'nowrap' }}>
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
          <div style={{ fontSize: '12px', color: 'var(--color-plex-text-muted)', fontStyle: 'italic' }}>
            No hourly forecast available
          </div>
        )
      ) : forecast ? (
        <>
          <div style={{ marginBottom: '6px' }}>
            {forecast.rating != null && (
              <div style={{ fontSize: '16px', letterSpacing: '2px', marginBottom: '4px' }}>
                <span style={{ color: 'var(--color-verdict-marginal)' }}>{'★'.repeat(forecast.rating)}</span>
                <span style={{ color: 'var(--color-plex-text-muted)' }}>{'☆'.repeat(5 - forecast.rating)}</span>
                <span style={{ fontSize: '12px', color: 'var(--color-plex-text-muted)', fontFamily: "'IBM Plex Mono', monospace", marginLeft: '8px', letterSpacing: 0 }}>
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
                {role === 'LITE_USER'
                  ? '🌫️ High aerosols'
                  : '🏜️ Elevated dust (e.g. Saharan dust) — expect unusually vivid warm tones'}
              </span>
            </div>
          )}
          {role !== 'LITE_USER' && forecast.inversionPotential && forecast.inversionPotential !== 'NONE' && (
            <div style={{ marginBottom: '6px' }} data-testid="inversion-badge">
              <span style={{
                ...POPUP_PILL,
                background: forecast.inversionPotential === 'STRONG'
                  ? 'rgba(123, 31, 162, 0.2)' : 'rgba(25, 118, 210, 0.2)',
                color: forecast.inversionPotential === 'STRONG'
                  ? '#ce93d8' : '#90caf9',
                border: `1px solid ${forecast.inversionPotential === 'STRONG'
                  ? 'rgba(123, 31, 162, 0.4)' : 'rgba(25, 118, 210, 0.4)'}`,
              }}>
                <span style={{ display: 'inline-block', transform: 'scaleY(-1)' }}>☁️</span>
                {' '}{forecast.inversionPotential === 'STRONG'
                  ? 'Strong cloud inversion — dramatic sea of clouds likely'
                  : 'Moderate cloud inversion — cloud blanket below viewpoint possible'}
              </span>
            </div>
          )}
          {forecast.bluebellScore != null && forecast.bluebellScore >= 2 && (
            <div style={{ marginBottom: '6px' }} data-testid="bluebell-badge">
              <span style={{
                ...POPUP_PILL,
                background: forecast.bluebellScore >= 4
                  ? 'rgba(99, 102, 241, 0.15)' : 'rgba(99, 102, 241, 0.08)',
                color: '#a5b4fc',
                border: `1px solid rgba(99, 102, 241, ${forecast.bluebellScore >= 4 ? '0.4' : '0.25'})`,
                ...(role === 'LITE_USER' ? { opacity: 0.45, pointerEvents: 'none' } : {}),
              }}>
                🌸 {role === 'LITE_USER'
                  ? 'Bluebell conditions — Upgrade to Pro'
                  : `Bluebell: ${forecast.bluebellSummary ?? `${forecast.bluebellScore}/5`}`}
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
          {driveMinutes != null && driveMinutes > 0 && (
            <div style={{ marginBottom: '6px' }} data-testid="drive-time-badge">
              <span style={{ ...POPUP_PILL, background: 'rgba(255,255,255,0.05)', color: 'var(--color-plex-text-secondary)', border: '1px solid var(--color-plex-border-light)' }}>
                🚗 {formatDriveTime(driveMinutes)}
              </span>
            </div>
          )}
          {/* Aurora mode: no score means location is not aurora-eligible */}
          {isAuroraMode && !auroraScore && (
            <div style={{ marginBottom: '6px' }} data-testid="aurora-not-eligible">
              <span style={{
                ...POPUP_PILL,
                display: 'block',
                background: 'rgba(30,30,50,0.5)',
                color: 'var(--color-plex-text-muted)',
                border: '1px solid rgba(107,114,128,0.3)',
                fontSize: '11px',
                padding: '6px 10px',
              }}>
                🌌 Not suitable for aurora photography
                {location.bortleClass ? ` (Light pollution: ${bortleLabel(location.bortleClass)})` : ''}
              </span>
            </div>
          )}

          {/* Aurora score section — shown when aurora is active and a score is available for this location */}
          {auroraScore && (
            <div
              style={{ marginBottom: '6px' }}
              data-testid="aurora-score-section"
            >
              <span style={{
                ...POPUP_PILL,
                display: 'inline-flex',
                flexDirection: 'column',
                alignItems: 'flex-start',
                gap: '4px',
                padding: '6px 10px',
                background: auroraScore.alertLevel === 'STRONG'
                  ? 'rgba(255, 0, 0, 0.12)'
                  : 'rgba(255, 153, 0, 0.12)',
                color: auroraScore.alertLevel === 'STRONG' ? '#fca5a5' : '#fbbf24',
                border: `1px solid ${auroraScore.alertLevel === 'STRONG' ? 'rgba(255,0,0,0.4)' : 'rgba(255,153,0,0.4)'}`,
                borderRadius: '8px',
                width: '100%',
                boxSizing: 'border-box',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontWeight: '700', fontSize: '12px' }}>
                  <span>🌌</span>
                  <span>Aurora</span>
                  <span data-testid="aurora-score-stars" style={{ color: '#a78bfa', letterSpacing: '1px' }}>
                    {'★'.repeat(auroraScore.stars)}{'☆'.repeat(5 - auroraScore.stars)}
                  </span>
                  <span style={{ fontSize: '10px', fontWeight: '400', opacity: 0.8 }}>({auroraScore.stars}/5)</span>
                </div>
                {auroraScore.detail && (
                  <div data-testid="aurora-score-detail" style={{ fontSize: '11px', fontWeight: '400', opacity: 0.9, lineHeight: '1.6', whiteSpace: 'pre-line' }}>
                    {auroraScore.detail}
                  </div>
                )}
              </span>
            </div>
          )}

          {/* Astro conditions section — shown in Astro mode when a score is available */}
          {isAstroMode && astroScore && (
            <div
              style={{ marginBottom: '6px' }}
              data-testid="astro-score-section"
            >
              <span style={{
                ...POPUP_PILL,
                display: 'inline-flex',
                flexDirection: 'column',
                alignItems: 'flex-start',
                gap: '4px',
                padding: '6px 10px',
                background: 'rgba(59, 130, 246, 0.12)',
                color: '#93c5fd',
                border: '1px solid rgba(59, 130, 246, 0.4)',
                borderRadius: '8px',
                width: '100%',
                boxSizing: 'border-box',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontWeight: '700', fontSize: '12px' }}>
                  <span>🌙</span>
                  <span>Astro</span>
                  <span data-testid="astro-score-stars" style={{ color: '#93c5fd', letterSpacing: '1px' }}>
                    {'★'.repeat(astroScore.stars)}{'☆'.repeat(5 - astroScore.stars)}
                  </span>
                  <span style={{ fontSize: '10px', fontWeight: '400', opacity: 0.8 }}>({astroScore.stars}/5)</span>
                </div>
                {astroScore.summary && (
                  <div data-testid="astro-score-summary" style={{ fontSize: '11px', fontWeight: '400', opacity: 0.9, lineHeight: '1.4' }}>
                    {astroScore.summary}
                  </div>
                )}
                <div style={{ fontSize: '10px', fontWeight: '400', opacity: 0.7, lineHeight: '1.5' }}>
                  {astroScore.cloudExplanation && <div>☁️ {astroScore.cloudExplanation}</div>}
                  {astroScore.visibilityExplanation && <div>👁️ {astroScore.visibilityExplanation}</div>}
                  {astroScore.moonExplanation && <div>🌑 {astroScore.moonExplanation}</div>}
                </div>
                {astroScore.moonPhase && (
                  <div style={{ fontSize: '10px', fontWeight: '400', opacity: 0.6 }}>
                    Moon: {astroScore.moonPhase.replace(/_/g, ' ').toLowerCase()} ({Math.round(astroScore.moonIlluminationPct)}% illuminated)
                  </div>
                )}
              </span>
            </div>
          )}
          {isAstroMode && !astroScore && (
            <div style={{ marginBottom: '6px' }} data-testid="astro-no-data">
              <span style={{
                ...POPUP_PILL,
                display: 'block',
                background: 'rgba(30,30,50,0.5)',
                color: 'var(--color-plex-text-muted)',
                border: '1px solid rgba(107,114,128,0.3)',
                fontSize: '11px',
                padding: '6px 10px',
              }}>
                🌙 No astro conditions data for this date
              </span>
            </div>
          )}

          {tideClassification && tideClassification.map((tc) => {
            const isKing = tc.isKing;
            const near = tc.nearSolarEvent;
            const lunar = forecast?.lunarTideType;
            const hasLunarData = lunar != null;

            // Build combined label from lunar + statistical dimensions
            // When lunar data is present, use new naming; otherwise fall back to legacy labels
            let label;
            if (hasLunarData) {
              const lunarLabel = lunar === 'KING_TIDE' ? 'King Tide'
                : lunar === 'SPRING_TIDE' ? 'Spring Tide' : null;
              const statLabel = isKing ? 'Extra Extra High'
                : tc.isSpring ? 'Extra High' : null;
              label = lunarLabel && statLabel ? `${lunarLabel}, ${statLabel}`
                : lunarLabel || statLabel || (isKing ? 'King tide' : 'Spring tide');
            } else {
              label = isKing ? 'King tide' : 'Spring tide';
            }

            const isLunarKing = lunar === 'KING_TIDE';
            const isHighPriority = isKing || isLunarKing;
            const emoji = isHighPriority ? '👑' : '🌊';
            return (
              <div key={tc.time} style={{ marginBottom: '6px' }} data-testid={isHighPriority ? 'king-tide-badge' : 'spring-tide-badge'}>
                <span style={{
                  ...POPUP_PILL,
                  background: near
                    ? (isHighPriority ? 'rgba(220, 38, 38, 0.15)' : 'rgba(245, 158, 11, 0.15)')
                    : 'rgba(107, 114, 128, 0.15)',
                  color: near
                    ? (isHighPriority ? '#fca5a5' : '#fbbf24')
                    : '#9ca3af',
                  border: `1px solid ${near
                    ? (isHighPriority ? 'rgba(220, 38, 38, 0.4)' : 'rgba(245, 158, 11, 0.4)')
                    : 'rgba(107, 114, 128, 0.3)'}`,
                }}>
                  {near
                    ? `${emoji} ${label} — high at ${formatEventTimeUk(tc.time)} (${tc.height.toFixed(1)}m)`
                    : `${emoji} ${label} today — but outside golden/blue hours (high at ${formatEventTimeUk(tc.time)}, ${tc.height.toFixed(1)}m)`}
                </span>
              </div>
            );
          })}
          {(location.locationType ?? []).includes('SEASCAPE')
            && (location.tideType == null || location.tideType.length === 0) && (
            <div style={{ marginBottom: '6px' }} data-testid="tide-preferences-missing-badge">
              <span style={{
                ...POPUP_PILL,
                background: 'rgba(245, 158, 11, 0.15)',
                color: '#fbbf24',
                border: '1px solid rgba(245, 158, 11, 0.4)',
              }}>
                ⚠️ No tide preferences set — check tide alignment before planning your shoot
              </span>
            </div>
          )}
          {forecast.triageReason && (
            <StandDownBadge
              triageReason={forecast.triageReason}
              triageMessage={forecast.triageMessage}
            />
          )}
          {!forecast.triageReason && forecast.summary && (
            <div style={{ fontSize: '13px', lineHeight: '1.55', fontFamily: "'Newsreader', Georgia, serif", color: 'var(--color-plex-text-secondary)', marginBottom: '8px' }}>
              {forecast.summary}
            </div>
          )}
          {role === 'LITE_USER' && (
            <p data-testid="upgrade-hint" style={{ fontSize: '12px', color: 'var(--color-plex-text-muted)', marginBottom: '8px' }}>
              <a href="/upgrade" style={{ color: 'var(--color-plex-text)', textDecoration: 'underline', textUnderlineOffset: '2px' }}>Upgrade to Pro</a> for Fiery Sky &amp; Golden Hour scores, storm surge analysis, and full AI analysis
            </p>
          )}

          {/* "More details" toggle — bone/mono underline, matching the drill-down affordance */}
          <button
            data-testid="more-details-toggle"
            onClick={onToggleExpanded}
            style={{
              background: 'none', border: 'none', cursor: 'pointer', padding: '4px 0',
              fontSize: '12px', fontWeight: '600', color: 'var(--color-plex-text-muted)',
              fontFamily: "'IBM Plex Mono', monospace", textDecoration: 'underline', textUnderlineOffset: '2px',
              display: 'flex', alignItems: 'center', gap: '4px', marginBottom: '6px',
            }}
          >
            {isExpanded ? 'Less details ▴' : 'More details ▾'}
          </button>

          {isExpanded && (
            <>
              {/* Location type pills */}
              {locTypes.length > 0 && (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', marginBottom: '6px' }}>
                  {locTypes.map((t) => {
                    const m = POPUP_LOC_TYPE_META[t];
                    return (
                      <span key={t} style={{ ...POPUP_PILL, borderRadius: '6px', background: 'var(--color-plex-surface-light)', color: 'var(--color-plex-text)', border: '1px solid var(--color-plex-border-light)' }}>
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
                      <span key={t} style={{ ...POPUP_PILL, borderRadius: '6px', background: 'rgba(224,165,66,0.12)', color: 'var(--color-verdict-marginal)', border: '1px solid rgba(224,165,66,0.28)' }}>
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
                    <span key={t} style={{ ...POPUP_PILL, borderRadius: '6px', background: 'rgba(111,168,176,0.16)', color: 'var(--color-tide)', border: '1px solid rgba(111,168,176,0.3)' }}>
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

              {/* Score bars — PRO/ADMIN only */}
              {role !== 'LITE_USER' && forecast && forecast.fierySkyPotential != null && (
                <div style={{ marginBottom: '6px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '10px', color: 'var(--color-plex-text-muted)', marginBottom: '4px' }}>
                    <span>Scores</span>
                    <InfoTip text="Fiery Sky measures dramatic colour from clouds catching light. Golden Hour measures overall light quality and can score high even with clear sky." />
                  </div>
                  <PopupScoreRow label="Fiery Sky" score={forecast.fierySkyPotential} />
                  <PopupScoreRow label="Golden Hour" score={forecast.goldenHourPotential} />
                </div>
              )}

              {/* Comfort rows */}
              {forecast.temperatureCelsius != null && (
                <div style={{ borderTop: `1px solid var(--color-plex-border)`, paddingTop: '6px', marginTop: '4px', fontSize: '12px', color: 'var(--color-plex-text-secondary)', lineHeight: '1.8' }}>
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
                <div style={{ marginTop: '8px', paddingTop: '6px', borderTop: `1px solid var(--color-plex-border)`, fontSize: '10px', color: 'var(--color-plex-text-muted)' }}>
                  {buildGeneratedFooter(forecast)}
                </div>
              )}
            </>
          )}

          {/* Hourly comfort rows for waterfall locations */}
          {showComfortRows && hourlyData.length > 0 && (
            <div style={{ borderTop: `1px solid var(--color-plex-border)`, paddingTop: '6px', marginTop: '6px' }}>
              <div style={{ fontSize: '11px', fontWeight: '700', color: '#38bdf8', marginBottom: '6px' }}>
                💦 Hourly comfort during daylight hours
              </div>
              <div style={{ display: 'table', width: '100%', fontSize: '11px', borderCollapse: 'collapse' }}>
                {hourlyData.map((h) => (
                  <div key={h.solarEventTime} style={{ display: 'table-row' }}>
                    <div style={{ display: 'table-cell', color: 'var(--color-plex-text-muted)', paddingRight: '8px', paddingBottom: '3px', whiteSpace: 'nowrap' }}>
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
            <div style={{ marginTop: '8px', paddingTop: '6px', borderTop: `1px solid var(--color-plex-border)`, fontSize: '10px', color: 'var(--color-plex-text-muted)' }}>
              {buildGeneratedFooter(forecast)}
              {tideFetchedAt && (
                <div>Tide data fetched: {formatGeneratedAtFull(tideFetchedAt)}</div>
              )}
            </div>
          )}
        </>
      ) : (() => {
        // Empty state — enrich with location metadata
        const dayData = location.forecastsByDate?.get(date);
        const emptySunriseRaw = dayData?.sunrise?.solarEventTime;
        const emptySunsetRaw = dayData?.sunset?.solarEventTime;
        const emptySunriseTime = formatEventTimeUk(emptySunriseRaw);
        const emptySunsetTime = formatEventTimeUk(emptySunsetRaw);
        const emptyEventTime = isSunrise ? emptySunriseTime : emptySunsetTime;

        // Short day name from the solar event time (e.g. "Thu")
        const sunriseDayLabel = emptySunriseRaw
          ? new Date(emptySunriseRaw + 'Z').toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'Europe/London' })
          : null;
        const sunsetDayLabel = emptySunsetRaw
          ? new Date(emptySunsetRaw + 'Z').toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'Europe/London' })
          : null;
        const selectedDayLabel = isSunrise ? sunriseDayLabel : sunsetDayLabel;

        // Aurora/astro nights are bracketed by both solar events, so show both
        // as dark-window context. Sunrise/sunset tabs must show only the
        // selected event — surfacing the other event's time here contradicts
        // the active tab and reads as a wrong forecast.
        const showBothSolar = isAuroraMode || isAstroMode;
        const showSolarRow = showBothSolar
          ? Boolean(emptySunriseTime || emptySunsetTime)
          : Boolean(emptyEventTime);

        const locTypeLabel = locTypes.map((t) => POPUP_LOC_TYPE_META[t]?.label).filter(Boolean).join(' · ');
        const subParts = [locTypeLabel, location.regionName].filter(Boolean);

        const hasAuroraChip = location.bortleClass != null;
        const hasBortleChip = location.bortleClass != null;
        const hasDriveChip = driveMinutes != null && driveMinutes > 0;
        const hasChips = hasAuroraChip || hasDriveChip;

        const isStandDown = briefingScore?.triageReason != null;
        const isBatchScored = !isStandDown && briefingScore?.rating != null;

        return (
          <div data-testid={isStandDown ? 'standdown-popup' : isBatchScored ? 'batch-scored-popup' : 'empty-popup'}>

            {/* Event badge (outer header has no badge when forecast is null) */}
            {emptyEventTime && !isAuroraMode && !isAstroMode && (
              <div style={{ marginBottom: '6px' }}>
                <span style={{
                  ...POPUP_PILL,
                  background: isSunrise ? 'rgba(249,115,22,0.15)' : 'rgba(168,85,247,0.15)',
                  color: isSunrise ? '#fb923c' : '#c084fc',
                  border: `1px solid ${isSunrise ? 'rgba(249,115,22,0.35)' : 'rgba(168,85,247,0.35)'}`,
                }}>
                  {isSunrise ? '🌅' : '🌇'} {isSunrise ? 'Sunrise' : 'Sunset'} · {emptyEventTime}
                </span>
              </div>
            )}

            {/* Sub-row: type · region */}
            {subParts.length > 0 && (
              <div style={{ fontSize: '12px', color: 'var(--color-plex-text-muted)', marginBottom: '6px' }}>
                {subParts.join(' · ')}
              </div>
            )}

            {/* Solar event row — sunrise/sunset tabs show only the selected
                event so the time can never contradict the active tab;
                aurora/astro show both events as the dark-window bracket. */}
            {showSolarRow && (
              <div data-testid="solar-times-row" style={{
                display: 'flex', gap: '12px', padding: '5px 8px', borderRadius: '6px',
                background: 'rgba(255,255,255,0.04)',
                marginBottom: '6px', fontSize: '11px',
              }}>
                {showBothSolar ? (
                  <>
                    {emptySunriseTime && (
                      <span style={{ color: '#fb923c' }}>
                        🌅 {emptySunriseTime}{sunriseDayLabel ? ` ${sunriseDayLabel}` : ''}
                      </span>
                    )}
                    {emptySunsetTime && (
                      <span style={{ color: '#c084fc' }}>
                        🌇 {emptySunsetTime}{sunsetDayLabel ? ` ${sunsetDayLabel}` : ''}
                      </span>
                    )}
                  </>
                ) : (
                  <span style={{ color: isSunrise ? '#fb923c' : '#c084fc' }}>
                    {isSunrise ? '🌅' : '🌇'} {emptyEventTime}{selectedDayLabel ? ` ${selectedDayLabel}` : ''}
                  </span>
                )}
              </div>
            )}

            {/* Chips: aurora friendly, light pollution, drive time */}
            {hasChips && (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', marginBottom: '6px' }}>
                {hasAuroraChip && (
                  <span data-testid="aurora-badge" style={{ ...POPUP_PILL, background: 'rgba(139,92,246,0.15)', color: '#a78bfa', border: '1px solid rgba(139,92,246,0.3)' }}>
                    🌌 Aurora friendly
                  </span>
                )}
                {hasBortleChip && (
                  <span data-testid="light-pollution-badge" style={{ ...POPUP_PILL, background: 'rgba(255,255,255,0.05)', color: 'var(--color-plex-text-secondary)', border: '1px solid var(--color-plex-border-light)' }}>
                    💡 Light pollution: {bortleLabel(location.bortleClass)} (Bortle {location.bortleClass})
                  </span>
                )}
                {hasDriveChip && (
                  <span data-testid="drive-time-badge" style={{ ...POPUP_PILL, background: 'rgba(255,255,255,0.05)', color: 'var(--color-plex-text-secondary)', border: '1px solid var(--color-plex-border-light)' }}>
                    🚗 {formatDriveTime(driveMinutes)}
                  </span>
                )}
              </div>
            )}

            {isStandDown ? (
              <StandDownBadge
                triageReason={briefingScore.triageReason}
                triageMessage={briefingScore.triageMessage}
              />
            ) : isBatchScored ? (
              <>
                {briefingScore.rating != null && (
                  <div style={{ marginBottom: '6px' }}>
                    <div style={{ fontSize: '16px', letterSpacing: '2px', marginBottom: '4px' }}>
                      <span style={{ color: 'var(--color-verdict-marginal)' }}>{'★'.repeat(briefingScore.rating)}</span>
                      <span style={{ color: 'var(--color-plex-text-muted)' }}>{'☆'.repeat(5 - briefingScore.rating)}</span>
                      <span style={{ fontSize: '12px', color: 'var(--color-plex-text-muted)', fontFamily: "'IBM Plex Mono', monospace", marginLeft: '8px', letterSpacing: 0 }}>
                        {briefingScore.rating}/5
                      </span>
                    </div>
                  </div>
                )}
                {briefingScore.summary && (
                  <div style={{ fontSize: '13px', lineHeight: '1.55', fontFamily: "'Newsreader', Georgia, serif", color: 'var(--color-plex-text-secondary)', marginBottom: '8px' }}>
                    {briefingScore.summary}
                  </div>
                )}
                {role !== 'LITE_USER' && briefingScore.fierySkyPotential != null && (
                  <div style={{ marginBottom: '6px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '10px', color: 'var(--color-plex-text-muted)', marginBottom: '4px' }}>
                      <span>Scores</span>
                      <InfoTip text="Fiery Sky measures dramatic colour from clouds catching light. Golden Hour measures overall light quality and can score high even with clear sky." />
                    </div>
                    <PopupScoreRow label="Fiery Sky" score={briefingScore.fierySkyPotential} />
                    <PopupScoreRow label="Golden Hour" score={briefingScore.goldenHourPotential} />
                  </div>
                )}
                {role === 'LITE_USER' && (
                  <p data-testid="upgrade-hint" style={{ fontSize: '12px', color: 'var(--color-plex-text-muted)', marginBottom: '8px' }}>
                    <a href="/upgrade" style={{ color: 'var(--color-plex-text)', textDecoration: 'underline', textUnderlineOffset: '2px' }}>Upgrade to Pro</a> for Fiery Sky &amp; Golden Hour scores, storm surge analysis, and full AI analysis
                  </p>
                )}
              </>
            ) : (
              <>
                {/* Dashed divider */}
                <div style={{
                  borderTop: '1px dashed var(--color-plex-border)',
                  textAlign: 'center', position: 'relative', margin: '8px 0',
                }}>
                  <span style={{
                    position: 'relative', top: '-8px',
                    background: 'var(--color-plex-surface)',
                    padding: '0 8px', fontSize: '11px', color: 'var(--color-plex-text-muted)',
                  }}>
                    no forecast yet
                  </span>
                </div>

                {/* Run Forecast button (admin only) */}
                {role === 'ADMIN' && (
                  <button
                    data-testid="run-forecast-btn"
                    disabled={runningForecast}
                    onClick={handleRunForecast}
                    style={{
                      display: 'block',
                      padding: '4px 12px',
                      fontSize: '11px',
                      fontWeight: 500,
                      color: '#fff',
                      backgroundColor: runningForecast ? '#6b7280' : '#3b82f6',
                      border: 'none',
                      borderRadius: '4px',
                      cursor: runningForecast ? 'not-allowed' : 'pointer',
                      opacity: runningForecast ? 0.7 : 1,
                    }}
                  >
                    {runningForecast ? `Running\u2026 ${runProgress}` : 'Run Forecast'}
                  </button>
                )}
              </>
            )}
            {forecastError && (
              <div style={{ marginTop: '4px', color: '#ef4444', fontSize: '11px' }}>
                {forecastError}
              </div>
            )}
          </div>
        );
      })()}
    </div>
  );
}

MarkerPopupContent.propTypes = {
  location: PropTypes.shape({
    name: PropTypes.string.isRequired,
    solarEventType: PropTypes.arrayOf(PropTypes.string),
    locationType: PropTypes.arrayOf(PropTypes.string),
    tideType: PropTypes.arrayOf(PropTypes.string),
    bortleClass: PropTypes.number,
    regionName: PropTypes.string,
    forecastsByDate: PropTypes.object,
  }).isRequired,
  forecast: PropTypes.object,
  hourlyData: PropTypes.array.isRequired,
  eventType: PropTypes.oneOf(['SUNRISE', 'SUNSET']).isRequired,
  isPureWildlife: PropTypes.bool.isRequired,
  showComfortRows: PropTypes.bool,
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
  auroraScore: PropTypes.shape({
    stars: PropTypes.number.isRequired,
    alertLevel: PropTypes.string.isRequired,
    cloudPercent: PropTypes.number.isRequired,
    summary: PropTypes.string,
    detail: PropTypes.string,
  }),
  isAuroraMode: PropTypes.bool,
  astroScore: PropTypes.shape({
    stars: PropTypes.number.isRequired,
    summary: PropTypes.string,
    cloudExplanation: PropTypes.string,
    visibilityExplanation: PropTypes.string,
    moonExplanation: PropTypes.string,
    moonPhase: PropTypes.string,
    moonIlluminationPct: PropTypes.number,
  }),
  isAstroMode: PropTypes.bool,
  onForecastRun: PropTypes.func,
  driveMinutes: PropTypes.number,
  briefingScore: PropTypes.shape({
    rating: PropTypes.number,
    fierySkyPotential: PropTypes.number,
    goldenHourPotential: PropTypes.number,
    summary: PropTypes.string,
    triageReason: PropTypes.string,
    triageMessage: PropTypes.string,
  }),
  travelDay: PropTypes.bool,
};
