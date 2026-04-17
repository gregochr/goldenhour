import { useState } from 'react';
import PropTypes from 'prop-types';
import InfoTip from './InfoTip.jsx';
import { bortleLabel, moonIlluminationStyle, MOON_EMOJI, MOON_PHASE_NAME } from '../utils/conversions.js';

/** Accent colours keyed by topic type. */
const HOT_TOPIC_STYLES = {
  BLUEBELL:    { color: '#8b5cf6', emoji: '\uD83D\uDC9C' },
  KING_TIDE:   { color: '#3b82f6', emoji: '\uD83D\uDC51' },
  SPRING_TIDE: { color: '#60a5fa', emoji: '\uD83C\uDF0A' },
  STORM_SURGE: { color: '#f59e0b', emoji: '\u26A1' },
  AURORA:      { color: '#4ade80', emoji: '\uD83C\uDF0C' },
  DUST:        { color: '#f97316', emoji: '\uD83C\uDF05' },
  INVERSION:   { color: '#94a3b8', emoji: '\u2601\uFE0F' },
  SUPERMOON:   { color: '#fbbf24', emoji: '\uD83C\uDF15' },
  SNOW_FRESH:  { color: '#e0f2fe', emoji: '\u2744\uFE0F' },
  SNOW_MIST:   { color: '#cbd5e1', emoji: '\uD83C\uDF2B\uFE0F' },
  SNOW_TOPS:   { color: '#bfdbfe', emoji: '\uD83C\uDFD4\uFE0F' },
  NLC:         { color: '#818cf8', emoji: '\u2728' },
  METEOR:      { color: '#a78bfa', emoji: '\u2604\uFE0F' },
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

/** Score colour for bluebell scores. */
function bluebellScoreColour(score) {
  if (score >= 9) return 'rgb(74, 222, 128)';
  if (score >= 7) return 'rgb(251, 191, 36)';
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
 * Builds a subtitle line for expandable pills.
 */
function buildSubtitle(topic, auroraData) {
  if (topic.type === 'AURORA' && auroraData) {
    const kp = auroraData.kp ?? auroraData.peakKp;
    const timing = topic.detail?.toLowerCase().includes('tonight') ? 'tonight' : 'tomorrow';
    const clearCount = auroraData.regions
      ?.reduce((sum, r) => sum + (r.clearLocationCount || 0), 0) ?? 0;
    const parts = [];
    if (kp != null) parts.push(`Kp ${kp.toFixed(1)} forecast ${timing}`);
    if (clearCount > 0) parts.push(`${clearCount} locations clear`);
    return parts.join(' \u00b7 ') || null;
  }
  if (topic.type === 'BLUEBELL' && topic.expandedDetail?.bluebellMetrics) {
    const m = topic.expandedDetail.bluebellMetrics;
    return `${m.scoringLocationCount} locations scoring \u00b7 best ${m.bestScore}/10`;
  }
  if ((topic.type === 'KING_TIDE' || topic.type === 'SPRING_TIDE')
      && topic.expandedDetail?.tideMetrics) {
    const m = topic.expandedDetail.tideMetrics;
    const parts = [m.tidalClassification, m.lunarPhase,
      `${m.coastalLocationCount} coastal locations`].filter(Boolean);
    return parts.join(' \u00b7 ');
  }
  return null;
}

/**
 * Builds a moon-phase line for the collapsed aurora pill header.
 * Returns null when aurora data or moon phase is absent (graceful degradation).
 */
function buildMoonLine(auroraData) {
  if (!auroraData?.moonPhase) return null;
  const illum = Math.round(auroraData.moonIlluminationPct ?? 0);
  const emoji = MOON_EMOJI[auroraData.moonPhase] || '';
  const phaseName = MOON_PHASE_NAME[auroraData.moonPhase] || auroraData.moonPhase;
  const { colourClass, suffix } = moonIlluminationStyle(
    auroraData.moonIlluminationPct ?? 0, auroraData.windowQuality,
    auroraData.moonRiseTime, auroraData.moonSetTime,
  );
  return { emoji, phaseName, illum, colourClass, suffix };
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
        border: '1px solid rgba(74, 222, 128, 0.15)',
        background: 'rgba(74, 222, 128, 0.04)',
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
                <span style={{ fontSize: '11px', color: 'rgba(255,255,255,0.5)', fontStyle: 'italic' }}>
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
function BluebellExpandedCard({ expandedDetail }) {
  if (!expandedDetail?.regionGroups) return null;

  const accentColor = HOT_TOPIC_STYLES.BLUEBELL.color;

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
            <span style={{ fontSize: '12px', fontWeight: 600, color: 'rgba(255,255,255,0.85)' }}>
              {region.regionName}
            </span>
            {region.glossHeadline && (
              <span
                data-testid="bluebell-gloss-headline"
                style={{ fontSize: '11px', color: 'rgba(255,255,255,0.5)', fontStyle: 'italic' }}
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
                <span style={{ fontSize: '12px', fontWeight: 500, color: 'rgba(255,255,255,0.8)' }}>
                  {loc.locationName}
                </span>
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
                    {score}/10
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
function TideExpandedCard({ expandedDetail }) {
  if (!expandedDetail?.regionGroups) return null;

  const accentColor = HOT_TOPIC_STYLES.KING_TIDE.color;

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
            <span style={{ fontSize: '12px', fontWeight: 600, color: 'rgba(255,255,255,0.85)' }}>
              {region.regionName}
            </span>
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
                <span style={{ fontSize: '12px', fontWeight: 500, color: 'rgba(255,255,255,0.8)' }}>
                  {loc.locationName}
                </span>
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
 * Renders the appropriate expanded card for the given topic type.
 */
function ExpandedCard({ topic, auroraData }) {
  if (topic.type === 'AURORA') return <AuroraExpandedCard auroraData={auroraData} />;
  if (topic.type === 'BLUEBELL') return <BluebellExpandedCard expandedDetail={topic.expandedDetail} />;
  if (topic.type === 'KING_TIDE' || topic.type === 'SPRING_TIDE') {
    return <TideExpandedCard expandedDetail={topic.expandedDetail} />;
  }
  return null;
}

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
 * @param {Object}   props.auroraTonight    aurora summary for tonight (optional)
 * @param {Object}   props.auroraTomorrow   aurora summary for tomorrow (optional)
 */
export default function HotTopicStrip({
  hotTopics,
  isLiteUser,
  onTopicTap,
  auroraTonight = null,
  auroraTomorrow = null,
}) {
  const [expandedKey, setExpandedKey] = useState(null);

  if (!hotTopics || hotTopics.length === 0) return null;

  return (
    <div
      data-testid="hot-topic-strip"
      className="hot-topic-grid"
    >
      {hotTopics.map((topic) => {
        const style = HOT_TOPIC_STYLES[topic.type] ?? DEFAULT_STYLE;
        const isAurora = topic.type === 'AURORA';
        const pillKey = `${topic.type}-${topic.date}`;
        const isExpanded = expandedKey === pillKey;
        const auroraData = isAurora ? resolveAuroraData(topic, auroraTonight, auroraTomorrow) : null;

        // Generic expand: aurora uses frontend join, others use expandedDetail
        const canExpand = !isLiteUser
          && ((isAurora && auroraData != null) || topic.expandedDetail != null);

        const subtitle = canExpand ? buildSubtitle(topic, auroraData) : null;
        const moonLine = isAurora ? buildMoonLine(auroraData) : null;

        // For expandable pills, regions are shown in the expanded body, not the collapsed pill
        const regionLine = !canExpand && topic.regions?.length > 0
          ? topic.regions.join(', ')
          : null;

        const handleClick = () => {
          if (isLiteUser) return;
          if (canExpand) {
            setExpandedKey(isExpanded ? null : pillKey);
          } else if (!isAurora) {
            // AURORA pills without data don't expand and don't trigger onTopicTap
            onTopicTap?.(topic);
          }
        };

        return (
          <div key={pillKey}>
            <button
              data-testid={`hot-topic-pill-${topic.type}`}
              onClick={handleClick}
              disabled={isLiteUser}
              style={{
                display: 'flex',
                flexDirection: 'column',
                gap: '2px',
                padding: '8px 14px',
                borderRadius: '0 8px 8px 0',
                border: `1px solid ${style.color}33`,
                borderLeft: `3px solid ${style.color}`,
                background: `${style.color}0F`,
                cursor: isLiteUser ? 'default' : 'pointer',
                opacity: isLiteUser ? 0.45 : 1,
                pointerEvents: isLiteUser ? 'none' : 'auto',
                textAlign: 'left',
                transition: 'background 0.15s',
                width: '100%',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'flex-start',
                  gap: '12px',
                  marginBottom: '4px',
                }}
              >
                <div
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
                      fontSize: '11px',
                      fontWeight: 600,
                      textTransform: 'uppercase',
                      letterSpacing: '0.5px',
                      color: style.color,
                      filter: 'brightness(1.4)',
                    }}
                  >
                    {topic.label}
                  </span>
                  {topic.description && (
                    <span style={{ color: `${style.color}99` }}>
                      <InfoTip text={topic.description} position="above" />
                    </span>
                  )}
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  {regionLine && (
                    <span
                      style={{
                        fontSize: '11px',
                        color: 'rgba(255, 255, 255, 0.45)',
                        textAlign: 'right',
                        lineHeight: 1.3,
                        whiteSpace: 'normal',
                        wordBreak: 'normal',
                      }}
                    >
                      {regionLine}
                    </span>
                  )}
                  {canExpand && (
                    <span
                      data-testid={`expand-chevron-${topic.type}`}
                      style={{
                        fontSize: '10px',
                        color: 'rgba(255,255,255,0.4)',
                        transition: 'transform 0.15s',
                        transform: isExpanded ? 'rotate(90deg)' : 'rotate(0deg)',
                        flexShrink: 0,
                      }}
                    >
                      {'\u25B6'}
                    </span>
                  )}
                </div>
              </div>
              {subtitle && (
                <span
                  data-testid={`subtitle-${topic.type}`}
                  style={{
                    fontSize: '11px',
                    color: 'rgba(255, 255, 255, 0.5)',
                    marginBottom: '2px',
                  }}
                >
                  {subtitle}
                </span>
              )}
              {moonLine && (
                <span
                  data-testid="aurora-pill-moon-line"
                  style={{ fontSize: '11px', marginBottom: '2px' }}
                >
                  {moonLine.emoji}{' '}
                  <span style={{ color: 'rgba(255, 255, 255, 0.5)' }}>
                    {moonLine.phaseName}
                  </span>
                  {' · '}
                  <span className={moonLine.colourClass}>
                    {moonLine.illum}%{moonLine.suffix}
                  </span>
                </span>
              )}
              <span
                style={{
                  fontSize: '12px',
                  color: 'rgba(255, 255, 255, 0.55)',
                  ...(canExpand ? { fontStyle: 'italic' } : {}),
                }}
              >
                {topic.detail}
              </span>
            </button>
            {isExpanded && <ExpandedCard topic={topic} auroraData={auroraData} />}
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
            color: '#d4a843',
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
    }),
  ).isRequired,
  isLiteUser: PropTypes.bool,
  onTopicTap: PropTypes.func,
  auroraTonight: PropTypes.object,
  auroraTomorrow: PropTypes.object,
};
