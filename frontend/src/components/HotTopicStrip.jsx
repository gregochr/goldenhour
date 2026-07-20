import { useState } from 'react';
import PropTypes from 'prop-types';
import InfoTip from './InfoTip.jsx';
import { bortleLabel, moonIlluminationStyle, MOON_EMOJI } from '../utils/conversions.js';

/**
 * Accent colours keyed by topic type. Per the Kodachrome tidy-up, atmospheric
 * "fact" topics share the tide teal and the nightglow topics share a violet so
 * the strip reads as a small, coherent palette rather than a rainbow \u2014 the warm
 * reds/ambers/greens stay reserved for verdict semantics elsewhere.
 */
const TIDE_TEAL = '#6FA8B0';
const NIGHTGLOW_VIOLET = '#8E86D6';

const HOT_TOPIC_STYLES = {
  BLUEBELL:    { color: '#8b5cf6', emoji: '\uD83D\uDC9C' },
  KING_TIDE:   { color: TIDE_TEAL, emoji: '\uD83D\uDC51' },
  SPRING_TIDE: { color: TIDE_TEAL, emoji: '\uD83C\uDF0A' },
  STORM_SURGE: { color: '#f59e0b', emoji: '\u26A1' },
  AURORA:      { color: NIGHTGLOW_VIOLET, emoji: '\uD83C\uDF0C' },
  DUST:        { color: '#f97316', emoji: '\uD83C\uDF05' },
  INVERSION:   { color: TIDE_TEAL, emoji: '\u2601\uFE0F' },
  SUPERMOON:   { color: '#fbbf24', emoji: '\uD83C\uDF15' },
  SNOW_FRESH:  { color: '#e0f2fe', emoji: '\u2744\uFE0F' },
  SNOW_MIST:   { color: '#cbd5e1', emoji: '\uD83C\uDF2B\uFE0F' },
  SNOW_TOPS:   { color: '#bfdbfe', emoji: '\uD83C\uDFD4\uFE0F' },
  NLC:         { color: NIGHTGLOW_VIOLET, emoji: '\u2728' },
  METEOR:      { color: NIGHTGLOW_VIOLET, emoji: '\u2604\uFE0F' },
  EQUINOX:     { color: '#fcd34d', emoji: '\u2600\uFE0F' },
  CLEARANCE:   { color: '#fb923c', emoji: '\u26C5' },
};

const DEFAULT_STYLE = { color: '#ffffff33', emoji: '' };

const AURORA_LEVEL_COLOUR = {
  STRONG: 'text-red-400',
  MODERATE: 'text-amber-400',
  MINOR: 'text-green-400',
};

const AURORA_LEVEL_LABEL = { MINOR: 'Minor', MODERATE: 'Moderate', STRONG: 'Strong' };

/** Score colour for bluebell ratings (1-5 Claude rubric: 5 = exceptional). */
function bluebellScoreColour(score) {
  if (score >= 5) return 'rgb(74, 222, 128)';
  if (score >= 4) return 'rgb(251, 191, 36)';
  return 'rgba(255,255,255,0.45)';
}

function formatAlertLevel(level) {
  const label = AURORA_LEVEL_LABEL[level] || level;
  return level && level !== 'QUIET' && AURORA_LEVEL_LABEL[level] ? `${label} aurora` : label;
}

function weatherCodeToIcon(code) {
  if (code == null) return '';
  if (code === 0) return '\u2600\uFE0F';
  if (code <= 2) return '\uD83C\uDF24\uFE0F';
  if (code === 3) return '\u2601\uFE0F';
  if (code <= 48) return '\uD83C\uDF2B\uFE0F';
  if (code <= 67 || (code >= 80 && code <= 82)) return '\uD83C\uDF26\uFE0F';
  if (code <= 77 || (code >= 85 && code <= 86)) return '\u2744\uFE0F';
  return '\u26C8\uFE0F';
}

function msToMph(ms) {
  if (ms == null) return null;
  return Math.round(ms * 2.237);
}

/**
 * Glyph + word for each photographic event, keyed by the topic's `eventType`.
 * ↑ rising sun = sunrise, ↓ setting sun = sunset, ☾ moon = after dark.
 */
const EVENT_LEAD = {
  SUNRISE: { glyph: '↑', word: 'sunrise' },
  SUNSET: { glyph: '↓', word: 'sunset' },
  NIGHT: { glyph: '☾', word: 'night' },
};

/**
 * Compact day word for the timing lead: "Today" / "Tomorrow", or a short weekday ("Sat")
 * for anything further out. Mirrors the relative-day logic in `formatDateLabel`.
 *
 * @param {string} dateStr ISO date (YYYY-MM-DD)
 * @param {Date}   [now]   reference "today"
 * @returns {string} the day word, or '' when the date is missing/invalid
 */
function leadDayWord(dateStr, now = new Date()) {
  if (!dateStr) return '';
  const parts = dateStr.split('-').map(Number);
  if (parts.length !== 3 || parts.some(Number.isNaN)) return '';
  const [year, month, day] = parts;
  const todayUtc = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  const targetUtc = Date.UTC(year, month - 1, day);
  const diffDays = Math.round((targetUtc - todayUtc) / 86400000);
  if (diffDays === 0) return 'Today';
  if (diffDays === 1) return 'Tomorrow';
  return new Date(targetUtc).toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
}

/**
 * Normalises the topic's event time to a display "HH:mm". The API sends a bare 24-hour
 * clock string already; an ISO datetime is tolerated defensively.
 *
 * @param {string} eventTime "HH:mm" or ISO datetime
 * @returns {string|null} the clock time, or null when absent/invalid
 */
function formatEventTime(eventTime) {
  if (!eventTime) return null;
  if (eventTime.includes('T')) {
    const d = new Date(eventTime);
    if (Number.isNaN(d.getTime())) return null;
    return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
  }
  return eventTime;
}

// Relative day vocabulary the backend embeds in `detail` (via DayLabels) — a run of day
// tokens joined by "and" / commas, optionally trailed by "night" ("tomorrow night").
const RELATIVE_DAY = '(?:today|tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday)';
const RELATIVE_PHRASE = new RegExp(
  `\\s*\\b${RELATIVE_DAY}(?:\\s+night)?(?:(?:,\\s*|\\s+and\\s+)${RELATIVE_DAY}(?:\\s+night)?)*\\b`,
  'gi',
);

/**
 * Strips the now-redundant relative-time phrase from a detail string once the structured
 * timing lead carries the day. Used for display only — the raw `detail` still drives the
 * aurora tonight/tomorrow join. Leaves detail untouched when it holds no relative phrase.
 *
 * @param {string} detail the topic detail prose
 * @returns {string} the detail with its relative-day phrase removed and separators tidied
 */
function stripRelativePhrase(detail) {
  if (!detail) return detail;
  let out = detail.replace(RELATIVE_PHRASE, ' ');
  out = out.replace(/\s{2,}/g, ' ').trim();
  // Tidy separators left dangling by the removal (e.g. "horizon  — 64" or a trailing dash).
  out = out.replace(/\s+—\s*$/, '').replace(/[·,]\s*$/, '').trim();
  return out;
}

/**
 * Resolves the aurora summary object for an AURORA topic's date.
 * Matches topic.date against today/tomorrow to select the right object.
 */
function resolveAuroraData(topic, auroraTonight, auroraTomorrow) {
  if (!topic || topic.type !== 'AURORA') return null;
  if (auroraTonight && topic.detail?.toLowerCase().includes('tonight')) return auroraTonight;
  if (auroraTomorrow && topic.detail?.toLowerCase().includes('tomorrow')) return auroraTomorrow;
  return auroraTonight || auroraTomorrow || null;
}

/**
 * Expanded aurora detail card rendered below an AURORA pill.
 */
function AuroraExpandedCard({ auroraData }) {
  if (!auroraData) return null;

  const alertLevel = auroraData.alertLevel;
  const kpValue = auroraData.kp ?? auroraData.peakKp;
  const regions = auroraData.regions || [];

  return (
    <div
      data-testid="aurora-expanded-card"
      style={{
        marginTop: '6px',
        padding: '10px 12px',
        borderRadius: '6px',
        border: `1px solid ${NIGHTGLOW_VIOLET}26`,
        background: `${NIGHTGLOW_VIOLET}0A`,
      }}
    >
      {/* Header: alert level + Kp */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
        <span
          data-testid="aurora-expanded-alert"
          className={AURORA_LEVEL_COLOUR[alertLevel] || 'text-plex-text-secondary'}
          style={{ fontSize: '12px', fontWeight: 600 }}
        >
          {formatAlertLevel(alertLevel)}
        </span>
        {kpValue != null && (
          <span style={{ fontSize: '11px', color: 'rgba(255,255,255,0.5)' }}>
            Kp {kpValue.toFixed(1)}
          </span>
        )}
      </div>

      {/* Moon line */}
      {auroraData.moonPhase && (() => {
        const illum = auroraData.moonIlluminationPct ?? 0;
        const { colourClass, suffix } = moonIlluminationStyle(
          illum, auroraData.windowQuality,
          auroraData.moonRiseTime, auroraData.moonSetTime,
        );
        return (
          <div
            data-testid="aurora-expanded-moon"
            className={colourClass}
            style={{ fontSize: '11px', marginBottom: '6px' }}
          >
            {MOON_EMOJI[auroraData.moonPhase] || ''} {Math.round(illum)}%{suffix}
          </div>
        );
      })()}

      {/* Per-region sections */}
      {regions.map((region) => {
        const locations = (region.locations || [])
          .filter((l) => l.bortleClass != null)
          .sort((a, b) => (a.bortleClass ?? 99) - (b.bortleClass ?? 99));

        return (
          <div key={region.regionName} style={{ marginBottom: '8px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '3px' }}>
              <span style={{ fontSize: '12px', fontWeight: 600, color: 'rgba(255,255,255,0.85)' }}>
                {region.regionName}
              </span>
              {region.glossHeadline && (
                <span style={{ fontSize: '11px', color: 'rgba(255,255,255,0.62)', fontStyle: 'italic' }}>
                  {region.glossHeadline}
                  {region.glossDetail && (
                    <span style={{ marginLeft: '3px' }}>
                      <InfoTip text={region.glossDetail} position="above" />
                    </span>
                  )}
                </span>
              )}
            </div>

            {/* Region summary: Bortle + clear % */}
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px' }}>
              {region.bestBortleClass != null && bortleLabel(region.bestBortleClass) && (
                <span
                  style={{
                    fontSize: '10px',
                    padding: '1px 5px',
                    borderRadius: '3px',
                    background: 'rgba(20, 184, 166, 0.15)',
                    color: 'rgb(94, 234, 212)',
                    fontWeight: 500,
                  }}
                >
                  {bortleLabel(region.bestBortleClass)} · Bortle {region.bestBortleClass}
                </span>
              )}
              {region.totalDarkSkyLocations > 0 && (
                <span style={{ fontSize: '10px', color: 'rgba(255,255,255,0.45)' }}>
                  {Math.round((region.clearLocationCount / region.totalDarkSkyLocations) * 100)}% clear
                </span>
              )}
            </div>

            {/* Location rows */}
            {locations.map((loc) => (
              <div
                key={loc.locationName}
                data-testid="aurora-expanded-location"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  padding: '3px 6px',
                  marginTop: '2px',
                  borderRadius: '4px',
                  background: 'rgba(255,255,255,0.03)',
                }}
              >
                <span
                  style={{
                    display: 'inline-block',
                    width: '7px',
                    height: '7px',
                    borderRadius: '50%',
                    background: loc.clear ? 'rgb(74, 222, 128)' : 'rgba(248, 113, 113, 0.6)',
                    flexShrink: 0,
                  }}
                  title={loc.clear ? 'Clear skies' : 'Cloudy'}
                />
                <span style={{ fontSize: '12px', fontWeight: 500, color: 'rgba(255,255,255,0.8)' }}>
                  {loc.locationName}
                </span>
                {loc.bortleClass != null && bortleLabel(loc.bortleClass) && (
                  <span
                    style={{
                      fontSize: '9px',
                      padding: '0 4px',
                      borderRadius: '3px',
                      background: 'rgba(20, 184, 166, 0.15)',
                      color: 'rgb(94, 234, 212)',
                      fontWeight: 500,
                    }}
                  >
                    Bortle {loc.bortleClass}
                  </span>
                )}
                <span style={{ fontSize: '10px', color: 'rgba(255,255,255,0.4)' }}>
                  {loc.cloudPercent}% cloud
                  {loc.temperatureCelsius != null && ` · ${Math.round(loc.temperatureCelsius)}°C`}
                  {loc.windSpeedMs != null && ` · ${msToMph(loc.windSpeedMs)}mph`}
                  {loc.weatherCode != null && ` ${weatherCodeToIcon(loc.weatherCode)}`}
                </span>
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
}

/**
 * Expanded bluebell detail card rendered below a BLUEBELL pill.
 */
function BluebellExpandedCard({ expandedDetail, topic, onShowOnMap = null, isLiteUser = false }) {
  if (!expandedDetail?.regionGroups) return null;

  const accentColor = HOT_TOPIC_STYLES.BLUEBELL.color;
  const canOpenMap = !!onShowOnMap && !isLiteUser;

  return (
    <div
      data-testid="bluebell-expanded-card"
      style={{
        marginTop: '6px',
        padding: '10px 12px',
        borderRadius: '6px',
        border: `1px solid ${accentColor}25`,
        background: `${accentColor}08`,
      }}
    >
      {expandedDetail.regionGroups.map((region) => (
        <div key={region.regionName} style={{ marginBottom: '8px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '3px' }}>
            <button
              type="button"
              data-testid="bluebell-region-link"
              onClick={canOpenMap ? () => onShowOnMap({
                region: region.regionName, date: topic.date, label: topic.label, locationNames: topic.locationNames, filterAction: topic.filterAction,
              }) : undefined}
              disabled={!canOpenMap}
              className={canOpenMap ? 'hover:underline' : ''}
              style={{
                background: 'none', border: 'none', padding: 0, font: 'inherit', textAlign: 'left',
                fontSize: '12px', fontWeight: 600,
                color: canOpenMap ? accentColor : 'rgba(255,255,255,0.85)',
                cursor: canOpenMap ? 'pointer' : 'default',
              }}
            >
              {region.regionName}
            </button>
            {region.glossHeadline && (
              <span
                data-testid="bluebell-gloss-headline"
                style={{ fontSize: '11px', color: 'rgba(255,255,255,0.62)', fontStyle: 'italic' }}
              >
                {region.glossHeadline}
              </span>
            )}
          </div>

          {(region.locations || []).map((loc) => {
            const metrics = loc.bluebellLocationMetrics;
            const score = metrics?.score;
            return (
              <div
                key={loc.locationName}
                data-testid="bluebell-expanded-location"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  padding: '3px 6px',
                  marginTop: '2px',
                  borderRadius: '4px',
                  background: 'rgba(255,255,255,0.03)',
                }}
              >
                <span style={{ fontSize: '12px' }}>{'\uD83C\uDF3F'}</span>
                <LocationMapLink
                  topic={topic}
                  locationName={loc.locationName}
                  onShowOnMap={onShowOnMap}
                  disabled={isLiteUser}
                  style={{
                    fontSize: '12px', fontWeight: 500,
                    color: canOpenMap ? accentColor : 'rgba(255,255,255,0.8)',
                  }}
                />
                {loc.locationType && (
                  <span
                    data-testid="bluebell-exposure-chip"
                    style={{
                      fontSize: '9px',
                      padding: '0 4px',
                      borderRadius: '3px',
                      background: `${accentColor}20`,
                      color: `${accentColor}`,
                      fontWeight: 500,
                    }}
                  >
                    {loc.locationType}
                  </span>
                )}
                {score != null && (
                  <span
                    data-testid="bluebell-score"
                    style={{
                      fontSize: '10px',
                      color: bluebellScoreColour(score),
                      fontWeight: 600,
                    }}
                  >
                    {score}/5
                  </span>
                )}
                {loc.badge && (
                  <span
                    data-testid="bluebell-badge"
                    style={{
                      fontSize: '9px',
                      padding: '0 4px',
                      borderRadius: '3px',
                      background: 'rgba(74, 222, 128, 0.15)',
                      color: 'rgb(74, 222, 128)',
                      fontWeight: 600,
                    }}
                  >
                    {loc.badge}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}

/**
 * Expanded tide detail card rendered below a KING_TIDE or SPRING_TIDE pill.
 */
function TideExpandedCard({ expandedDetail, topic, onShowOnMap = null, isLiteUser = false }) {
  if (!expandedDetail?.regionGroups) return null;

  const accentColor = HOT_TOPIC_STYLES.KING_TIDE.color;
  const canOpenMap = !!onShowOnMap && !isLiteUser;

  return (
    <div
      data-testid="tide-expanded-card"
      style={{
        marginTop: '6px',
        padding: '10px 12px',
        borderRadius: '6px',
        border: `1px solid ${accentColor}25`,
        background: `${accentColor}08`,
      }}
    >
      {expandedDetail.regionGroups.map((region) => (
        <div key={region.regionName} style={{ marginBottom: '8px' }}>
          <div style={{ marginBottom: '3px' }}>
            <button
              type="button"
              data-testid="tide-region-link"
              onClick={canOpenMap ? () => onShowOnMap({
                region: region.regionName, date: topic.date, label: topic.label, locationNames: topic.locationNames, filterAction: topic.filterAction,
              }) : undefined}
              disabled={!canOpenMap}
              className={canOpenMap ? 'hover:underline' : ''}
              style={{
                background: 'none', border: 'none', padding: 0, font: 'inherit', textAlign: 'left',
                fontSize: '12px', fontWeight: 600,
                color: canOpenMap ? accentColor : 'rgba(255,255,255,0.85)',
                cursor: canOpenMap ? 'pointer' : 'default',
              }}
            >
              {region.regionName}
            </button>
          </div>

          {(region.locations || []).map((loc) => {
            const tidePreference = loc.tideLocationMetrics?.tidePreference;
            return (
              <div
                key={loc.locationName}
                data-testid="tide-expanded-location"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  padding: '3px 6px',
                  marginTop: '2px',
                  borderRadius: '4px',
                  background: 'rgba(255,255,255,0.03)',
                }}
              >
                <span style={{ fontSize: '12px' }}>{'\uD83C\uDF0A'}</span>
                <LocationMapLink
                  topic={topic}
                  locationName={loc.locationName}
                  onShowOnMap={onShowOnMap}
                  disabled={isLiteUser}
                  style={{
                    fontSize: '12px', fontWeight: 500,
                    color: canOpenMap ? accentColor : 'rgba(255,255,255,0.8)',
                  }}
                />
                {tidePreference && (
                  <span
                    data-testid="tide-preference-label"
                    style={{
                      fontSize: '9px',
                      padding: '0 4px',
                      borderRadius: '3px',
                      background: `${accentColor}20`,
                      color: `${accentColor}`,
                      fontWeight: 500,
                    }}
                  >
                    {tidePreference}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}

/**
 * A location name that opens the map overlay focused on that spot when the map is available.
 */
function LocationMapLink({ topic, locationName, onShowOnMap, disabled, style }) {
  const canOpen = !!onShowOnMap && !disabled;
  return (
    <button
      type="button"
      data-testid="expanded-location-link"
      onClick={canOpen ? () => onShowOnMap(topic.date, topic.eventType, locationName) : undefined}
      disabled={!canOpen}
      className={canOpen ? 'hover:underline' : ''}
      style={{
        background: 'none', border: 'none', padding: 0, font: 'inherit', textAlign: 'left',
        cursor: canOpen ? 'pointer' : 'default', ...style,
      }}
    >
      {locationName}
    </button>
  );
}

LocationMapLink.propTypes = {
  topic: PropTypes.object.isRequired,
  locationName: PropTypes.string.isRequired,
  onShowOnMap: PropTypes.func,
  disabled: PropTypes.bool,
  style: PropTypes.object,
};

/**
 * Renders the appropriate expanded card for the given topic type.
 */
function ExpandedCard({ topic, auroraData, onShowOnMap, isLiteUser }) {
  if (topic.type === 'AURORA') return <AuroraExpandedCard auroraData={auroraData} />;
  if (topic.type === 'BLUEBELL') {
    return <BluebellExpandedCard expandedDetail={topic.expandedDetail} topic={topic} onShowOnMap={onShowOnMap} isLiteUser={isLiteUser} />;
  }
  if (topic.type === 'KING_TIDE' || topic.type === 'SPRING_TIDE') {
    return <TideExpandedCard expandedDetail={topic.expandedDetail} topic={topic} onShowOnMap={onShowOnMap} isLiteUser={isLiteUser} />;
  }
  return null;
}

ExpandedCard.propTypes = {
  topic: PropTypes.object.isRequired,
  auroraData: PropTypes.object,
  onShowOnMap: PropTypes.func,
  isLiteUser: PropTypes.bool,
};

/**
 * Compass point → look-direction arrow glyph, for a fact's accent `dir`. Non-compass directions
 * (imperatives like "get above it") get no arrow.
 */
const COMPASS_ARROW = {
  N: '↑', NNE: '↗', NE: '↗', ENE: '↗', E: '→', ESE: '↘', SE: '↘', SSE: '↘',
  S: '↓', SSW: '↙', SW: '↙', WSW: '↙', W: '←', WNW: '↖', NW: '↖', NNW: '↖',
};

/**
 * The generalized enriched "science showing" second line: a wrapping row of monospace fact chips
 * plus one italic muted "where to look" note. Driven by the backend-supplied `topic.facts`
 * (`[{ key, value, dir?, emphasis?, optional? }]`) and `topic.note`, so every topic type — including
 * NLC — renders through the same view. Renders nothing when the topic carries no facts.
 *
 * <p>For Lite users the values are blurred — a paywall tease that shows the shape of the science
 * without the readable numbers. On narrow viewports, chips marked `optional` are hidden via the
 * `.opt` class so the line stays to the headline metric plus the note.
 *
 * @param {object}  props
 * @param {object}  props.topic       the hot topic, carrying facts + note
 * @param {string}  props.accentColor the pill accent colour for directional chips
 * @param {boolean} props.isLiteUser  blur the values when true
 */
function TopicFacts({ topic, accentColor, isLiteUser = false }) {
  if (!topic.facts || topic.facts.length === 0) return null;

  return (
    <div
      data-testid={`topic-facts-${topic.type}`}
      className="hot-topic-facts"
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: '6px 15px',
        padding: '0 13px 11px 38px',
        ...(isLiteUser ? { filter: 'blur(3.5px)', userSelect: 'none', pointerEvents: 'none' } : {}),
      }}
    >
      {topic.facts.map((fact, i) => {
        const arrow = fact.dir ? (COMPASS_ARROW[String(fact.dir).toUpperCase()] ?? '') : '';
        return (
          <span
            key={`${fact.key ?? ''}-${fact.value}-${i}`}
            data-testid="topic-fact"
            className={fact.optional ? 'opt' : undefined}
            style={{
              fontFamily: 'var(--font-mono)',
              fontSize: '11px',
              display: 'inline-flex',
              alignItems: 'center',
              gap: '5px',
              whiteSpace: 'nowrap',
            }}
          >
            {fact.key && <span style={{ color: 'var(--color-plex-text-muted)' }}>{fact.key}</span>}
            <span
              style={{
                color: fact.emphasis ? 'var(--color-plex-text)' : 'var(--color-plex-text-secondary)',
                fontWeight: fact.emphasis ? 600 : 400,
              }}
            >
              {fact.value}
            </span>
            {fact.dir && (
              <span style={{ color: accentColor, fontWeight: 600, letterSpacing: '0.02em' }}>
                {arrow ? `${arrow} ` : ''}
                {fact.dir}
              </span>
            )}
          </span>
        );
      })}
      {topic.note && (
        <span
          data-testid="topic-fact-note"
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: '10px',
            // Carries real information (the science-showing fact line), so it sits at the
            // secondary tier (0.66α) rather than muted (0.42α) — below comfortable contrast
            // for body-size fine print. See Change C3.
            color: 'var(--color-plex-text-secondary)',
            fontStyle: 'italic',
          }}
        >
          {topic.note}
        </span>
      )}
    </div>
  );
}

TopicFacts.propTypes = {
  topic: PropTypes.object.isRequired,
  accentColor: PropTypes.string.isRequired,
  isLiteUser: PropTypes.bool,
};

/**
 * Two-column responsive grid of Hot Topic pills shown between the Best Bet
 * cards and the quality slider in the briefing planner.
 *
 * Expandable pills: AURORA (via frontend aurora data join), BLUEBELL / KING_TIDE /
 * SPRING_TIDE (via backend expandedDetail). Only one pill expanded at a time.
 *
 * @param {Object}   props
 * @param {Array}    props.hotTopics        array of hot topic objects from the API
 * @param {boolean}  props.isLiteUser       true when role === 'LITE_USER'
 * @param {Function} props.onTopicTap       callback(topic) invoked when a non-expandable pill is tapped
 * @param {Function} props.onShowOnMap      opens the map overlay for a region/topic (optional)
 * @param {Object}   props.auroraTonight    aurora summary for tonight (optional)
 * @param {Object}   props.auroraTomorrow   aurora summary for tomorrow (optional)
 */
export default function HotTopicStrip({
  hotTopics,
  isLiteUser,
  onTopicTap,
  onShowOnMap = null,
  auroraTonight = null,
  auroraTomorrow = null,
}) {
  const [expandedKey, setExpandedKey] = useState(null);

  if (!hotTopics || hotTopics.length === 0) return null;

  // Order chronologically so a reader sees all of one day's topics together
  // (Saturday, then Sunday, then Monday). Within a day, fall back to the
  // server-assigned priority, then type, for a stable order.
  const orderedTopics = [...hotTopics].sort((a, b) => {
    const dateCmp = String(a.date ?? '').localeCompare(String(b.date ?? ''));
    if (dateCmp !== 0) return dateCmp;
    const prioCmp = (a.priority ?? 99) - (b.priority ?? 99);
    if (prioCmp !== 0) return prioCmp;
    return String(a.type ?? '').localeCompare(String(b.type ?? ''));
  });

  return (
    <div
      data-testid="hot-topic-strip"
      className="hot-topic-grid"
    >
      {orderedTopics.map((topic) => {
        const style = HOT_TOPIC_STYLES[topic.type] ?? DEFAULT_STYLE;
        const isAurora = topic.type === 'AURORA';
        const pillKey = `${topic.type}-${topic.date}`;
        const isExpanded = expandedKey === pillKey;
        const auroraData = isAurora ? resolveAuroraData(topic, auroraTonight, auroraTomorrow) : null;

        // Rich expand: aurora uses the frontend join, bluebell/tide use expandedDetail.
        const canExpandRich = !isLiteUser
          && ((isAurora && auroraData != null) || topic.expandedDetail != null);

        // Plain expand: a topic with regions but no rich card reveals its region
        // list on tap. The region list never renders inline by default — a long
        // list is the worst density offender on mobile.
        const regionCount = topic.regions?.length ?? 0;
        const canRevealRegions = !isLiteUser && !canExpandRich && regionCount > 0;
        const isExpandable = canExpandRich || canRevealRegions;

        const handleClick = () => {
          if (isLiteUser) return;
          if (isExpandable) {
            setExpandedKey(isExpanded ? null : pillKey);
          } else if (!isAurora) {
            // Nothing to reveal — fall back to the legacy map-filter tap.
            // AURORA pills without data never expand and never call onTopicTap.
            onTopicTap?.(topic);
          }
        };

        return (
          <div
            key={pillKey}
            style={{
              borderRadius: '0 6px 6px 0',
              border: `1px solid ${style.color}33`,
              borderLeft: `3px solid ${style.color}`,
              background: `${style.color}0F`,
              opacity: isLiteUser ? 0.45 : 1,
            }}
          >
            <button
              data-testid={`hot-topic-pill-${topic.type}`}
              onClick={handleClick}
              disabled={isLiteUser}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '12px',
                padding: '9px 13px',
                width: '100%',
                background: 'transparent',
                border: 'none',
                textAlign: 'left',
                cursor: isLiteUser ? 'default' : 'pointer',
                pointerEvents: isLiteUser ? 'none' : 'auto',
                transition: 'background 0.15s',
              }}
            >
              {/* Label group — emoji + bone label + optional infotip */}
              <span
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  flexShrink: 0,
                }}
              >
                {style.emoji && (
                  <span
                    style={{
                      fontSize: '14px',
                      lineHeight: 1,
                      ...(topic.type === 'INVERSION' ? { display: 'inline-block', transform: 'rotate(180deg)' } : {}),
                    }}
                  >
                    {style.emoji}
                  </span>
                )}
                <span
                  style={{
                    fontSize: '12px',
                    fontWeight: 600,
                    color: 'var(--color-plex-text)',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {topic.label}
                </span>
                {topic.description && (
                  <span style={{ color: 'var(--color-plex-text-muted)' }}>
                    <InfoTip
                      text={topic.description}
                      position="above"
                      heading="The science"
                      accentColor={style.color}
                    />
                  </span>
                )}
              </span>

              {/* Detail sentence — single line, ellipsis-truncated. When the topic carries a
                  structured event, lead with "{glyph} {day} {event} · {time}" in the topic's
                  accent, then the plain condition (relative-day prose stripped for display). */}
              <span
                data-testid={`topic-detail-${topic.type}`}
                style={{
                  flex: 1,
                  minWidth: 0,
                  fontSize: '12px',
                  color: 'var(--color-plex-text-secondary)',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {(() => {
                  const ev = topic.eventType ? EVENT_LEAD[topic.eventType] : null;
                  if (!ev) return topic.detail;
                  const time = formatEventTime(topic.eventTime);
                  const dayWord = leadDayWord(topic.date);
                  const detailText = stripRelativePhrase(topic.detail);
                  return (
                    <>
                      <span
                        data-testid={`topic-timing-lead-${topic.type}`}
                        style={{
                          fontFamily: 'var(--font-mono)',
                          fontWeight: 600,
                          color: style.color,
                        }}
                      >
                        {ev.glyph} {[dayWord, ev.word].filter(Boolean).join(' ')}
                        {time ? ` · ${time}` : ''}
                      </span>
                      {detailText && (
                        <span style={{ color: 'var(--color-plex-text-muted)' }}> — </span>
                      )}
                      {detailText}
                    </>
                  );
                })()}
              </span>

              {/* Region count — just the number, never the inline list */}
              {regionCount > 0 && (
                <span
                  data-testid={`topic-region-count-${topic.type}`}
                  style={{
                    flexShrink: 0,
                    fontSize: '11px',
                    fontFamily: 'var(--font-mono)',
                    color: 'var(--color-plex-text-muted)',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {regionCount} {regionCount === 1 ? 'region' : 'regions'}
                </span>
              )}

              {/* Chevron — rotates when open */}
              {isExpandable && (
                <span
                  data-testid={`expand-chevron-${topic.type}`}
                  style={{
                    flexShrink: 0,
                    fontSize: '10px',
                    color: 'var(--color-plex-text-muted)',
                    transition: 'transform 0.15s',
                    transform: isExpanded ? 'rotate(90deg)' : 'rotate(0deg)',
                  }}
                >
                  {'▶'}
                </span>
              )}
            </button>

            {/* Generalized enriched second line — the "science showing" fact chips + note,
                driven by backend-supplied topic.facts. Persistent, not gated behind expansion.
                Every topic type (NLC included) renders through this one component. */}
            {topic.facts?.length > 0 && (
              <TopicFacts topic={topic} accentColor={style.color} isLiteUser={isLiteUser} />
            )}

            {/* Expanded body — rich card, or the plain region list */}
            {isExpanded && canExpandRich && (
              <div style={{ padding: '0 13px 10px' }}>
                <ExpandedCard topic={topic} auroraData={auroraData} onShowOnMap={onShowOnMap} isLiteUser={isLiteUser} />
              </div>
            )}
            {isExpanded && canRevealRegions && (
              <div
                data-testid={`topic-regions-${topic.type}`}
                style={{
                  padding: '0 13px 10px',
                  fontSize: '11px',
                  fontFamily: 'var(--font-mono)',
                  color: 'var(--color-plex-text-muted)',
                  lineHeight: 1.5,
                }}
              >
                {topic.regions.map((region, i) => {
                  const canOpenMap = !!onShowOnMap && !isLiteUser;
                  return (
                    <span key={region}>
                      {i > 0 && ', '}
                      <button
                        type="button"
                        data-testid={`topic-region-link-${topic.type}`}
                        onClick={canOpenMap ? () => onShowOnMap({
                          region,
                          date: topic.date,
                          label: topic.label,
                          locationNames: topic.locationNames,
                          filterAction: topic.filterAction,
                        }) : undefined}
                        disabled={!canOpenMap}
                        className={canOpenMap ? 'hover:underline' : ''}
                        style={{
                          background: 'none',
                          border: 'none',
                          padding: 0,
                          font: 'inherit',
                          color: canOpenMap ? 'var(--color-tide)' : 'inherit',
                          cursor: canOpenMap ? 'pointer' : 'default',
                        }}
                      >
                        {region}
                      </button>
                    </span>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
      {isLiteUser && (
        <span
          data-testid="hot-topic-upsell"
          style={{
            display: 'flex',
            alignItems: 'center',
            fontSize: '11px',
            fontWeight: 600,
            color: 'var(--color-plex-text)',
            whiteSpace: 'nowrap',
            padding: '0 8px',
          }}
        >
          Upgrade to Pro
        </span>
      )}
    </div>
  );
}

HotTopicStrip.propTypes = {
  hotTopics: PropTypes.arrayOf(
    PropTypes.shape({
      type: PropTypes.string.isRequired,
      label: PropTypes.string.isRequired,
      detail: PropTypes.string,
      date: PropTypes.string.isRequired,
      priority: PropTypes.number,
      filterAction: PropTypes.string,
      regions: PropTypes.arrayOf(PropTypes.string),
      description: PropTypes.string,
      expandedDetail: PropTypes.object,
      eventType: PropTypes.oneOf(['SUNRISE', 'SUNSET', 'NIGHT']),
      eventTime: PropTypes.string,
      locationNames: PropTypes.arrayOf(PropTypes.string),
      facts: PropTypes.arrayOf(PropTypes.shape({
        key: PropTypes.string,
        value: PropTypes.string,
        dir: PropTypes.string,
        emphasis: PropTypes.bool,
        optional: PropTypes.bool,
      })),
      note: PropTypes.string,
    }),
  ).isRequired,
  isLiteUser: PropTypes.bool,
  onTopicTap: PropTypes.func,
  onShowOnMap: PropTypes.func,
  auroraTonight: PropTypes.object,
  auroraTomorrow: PropTypes.object,
};
