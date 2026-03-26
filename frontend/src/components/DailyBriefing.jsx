import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getDailyBriefing } from '../api/briefingApi.js';
import { useAuth } from '../context/AuthContext.jsx';

const POLL_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes

/**
 * Colour pill for a verdict (GO / MARGINAL / STANDDOWN).
 *
 * @param {object} props
 * @param {string} props.verdict
 */
function VerdictPill({ verdict }) {
  const colours = {
    GO: 'bg-green-600 text-white',
    MARGINAL: 'bg-amber-600 text-white',
    STANDDOWN: 'bg-red-700 text-white',
  };
  return (
    <span
      data-testid="verdict-pill"
      className={`inline-block px-2 py-0.5 rounded text-xs font-bold uppercase ${colours[verdict] || 'bg-plex-surface text-plex-text-secondary'}`}
    >
      {verdict}
    </span>
  );
}

/**
 * Inline chip for a flag string.
 *
 * @param {object} props
 * @param {string} props.label
 */
function FlagChip({ label }) {
  return (
    <span className="inline-block px-1.5 py-0.5 rounded bg-plex-surface border border-plex-border text-xs text-plex-text-secondary">
      {label}
    </span>
  );
}

/**
 * Rotating chevron icon — right at rest, down when open.
 *
 * @param {object} props
 * @param {boolean} props.open
 * @param {string}  [props.className]
 */
function Chevron({ open, className = '' }) {
  return (
    <span
      aria-hidden="true"
      className={`inline-block transition-transform duration-200 leading-none select-none ${open ? 'rotate-90' : 'rotate-0'} ${className}`}
    >
      ▶
    </span>
  );
}

/**
 * Formats a UTC ISO datetime string to a human-readable time (HH:MM).
 *
 * @param {string} isoString
 * @returns {string}
 */
function formatTime(isoString) {
  if (!isoString) return '';
  return isoString.substring(11, 16);
}

/**
 * Formats a UTC ISO datetime string to a relative freshness label.
 *
 * @param {string} isoString
 * @returns {string}
 */
function formatAge(isoString) {
  if (!isoString) return '';
  const generated = new Date(isoString + 'Z');
  const now = new Date();
  const diffMin = Math.round((now - generated) / 60000);
  if (diffMin < 1) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHrs = Math.round(diffMin / 60);
  return `${diffHrs}h ago`;
}

/**
 * Returns the representative solar event time for an event summary (first slot's time).
 *
 * @param {object} es - BriefingEventSummary
 * @returns {string|null} ISO datetime string or null
 */
function getEventTime(es) {
  for (const r of es.regions || []) {
    for (const s of r.slots || []) {
      if (s.solarEventTime) return s.solarEventTime;
    }
  }
  for (const s of es.unregioned || []) {
    if (s.solarEventTime) return s.solarEventTime;
  }
  return null;
}

/**
 * Returns true if the solar event for this summary has already passed.
 *
 * @param {object} es - BriefingEventSummary
 * @returns {boolean}
 */
function isEventPast(es) {
  const t = getEventTime(es);
  if (!t) return false;
  return new Date(t + 'Z') < new Date();
}

/**
 * Returns verdict counts across all regions in an event summary.
 *
 * @param {object} es - BriefingEventSummary
 * @returns {{ GO: number, MARGINAL: number, STANDDOWN: number }}
 */
function getVerdictCounts(es) {
  const counts = { GO: 0, MARGINAL: 0, STANDDOWN: 0 };
  (es.regions || []).forEach((r) => { counts[r.verdict] = (counts[r.verdict] || 0) + 1; });
  return counts;
}

/**
 * Returns true if any slot in this event summary has tide alignment.
 *
 * @param {object} es - BriefingEventSummary
 * @returns {boolean}
 */
function hasTideAligned(es) {
  return (es.regions || []).some((r) => (r.slots || []).some((s) => s.tideAligned));
}

/**
 * Compact single-row summary of one solar event. Clicking expands its region detail inline.
 *
 * @param {object}   props
 * @param {string}   props.dayLabel  - "Today" or "Tomorrow"
 * @param {object}   props.es        - BriefingEventSummary
 * @param {boolean}  props.isOpen    - whether this event's region detail is expanded
 * @param {Function} props.onToggle  - called when the row is clicked
 */
function EventSummaryRow({ dayLabel, es, isOpen, onToggle }) {
  const emoji = es.targetType === 'SUNRISE' ? '🌅' : '🌇';
  const eventLabel = es.targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset';
  const counts = getVerdictCounts(es);
  const tideAligned = hasTideAligned(es);

  const countColours = {
    GO: 'text-green-400',
    MARGINAL: 'text-amber-400',
    STANDDOWN: 'text-red-400',
  };

  return (
    <button
      data-testid="event-summary-row"
      className="w-full flex items-center gap-2 text-xs min-h-[44px] px-1 rounded hover:bg-plex-bg/30 text-left"
      onClick={onToggle}
    >
      <span className="w-36 shrink-0 font-medium text-plex-text">
        {emoji} {dayLabel} {eventLabel}
      </span>
      <span className="flex gap-2 flex-wrap flex-1">
        {counts.GO > 0 && (
          <span className={countColours.GO} data-testid="go-count">
            {counts.GO} GO
          </span>
        )}
        {counts.MARGINAL > 0 && (
          <span className={countColours.MARGINAL} data-testid="marginal-count">
            {counts.MARGINAL} MARGINAL
          </span>
        )}
        {counts.STANDDOWN > 0 && (
          <span className={countColours.STANDDOWN} data-testid="standdown-count">
            {counts.STANDDOWN} STANDDOWN
          </span>
        )}
      </span>
      {tideAligned && (
        <span title="Tide-aligned location in this event" className="text-blue-400 shrink-0">
          🌊
        </span>
      )}
      <span className="shrink-0 flex items-center justify-center w-11 h-11">
        <Chevron open={isOpen} className="text-lg text-plex-text-muted" />
      </span>
    </button>
  );
}

/** Location type icon lookup. */
const LOCATION_TYPE_ICONS = {
  LANDSCAPE: '🏔️',
  WILDLIFE: '🐾',
  SEASCAPE: '🌊',
  WATERFALL: '💧',
};

/**
 * Maps a WMO weather code to a representative emoji.
 *
 * @param {number|null} code
 * @returns {string}
 */
function weatherCodeToIcon(code) {
  if (code == null) return '';
  if (code === 0) return '☀️';
  if (code <= 2) return '🌤️';
  if (code === 3) return '☁️';
  if (code <= 48) return '🌫️';
  if (code <= 67 || (code >= 80 && code <= 82)) return '🌦️';
  if (code <= 77 || (code >= 85 && code <= 86)) return '❄️';
  return '⛈️';
}

/**
 * Converts wind speed in m/s to mph (rounded).
 *
 * @param {number|null} ms
 * @returns {number|null}
 */
function msToMph(ms) {
  if (ms == null) return null;
  return Math.round(ms * 2.237);
}

/**
 * Resolves a best-bet event string (e.g. "tomorrow_sunset") to the
 * event key used by expandedEvents state (e.g. "2026-03-27-SUNSET").
 * Returns null for aurora events or invalid inputs.
 *
 * @param {string|null} event   - e.g. "today_sunset", "tomorrow_sunrise", "aurora_tonight"
 * @param {string}      todayStr   - "YYYY-MM-DD" for today
 * @param {string}      tomorrowStr - "YYYY-MM-DD" for tomorrow
 * @returns {string|null}
 */
function resolveEventKey(event, todayStr, tomorrowStr) {
  if (!event) return null;
  const underscore = event.indexOf('_');
  if (underscore === -1) return null;
  const dayPart = event.substring(0, underscore);
  const typePart = event.substring(underscore + 1).toUpperCase();
  const dateStr = dayPart === 'today' ? todayStr : dayPart === 'tomorrow' ? tomorrowStr : null;
  if (!dateStr || !['SUNRISE', 'SUNSET'].includes(typePart)) return null;
  return `${dateStr}-${typePart}`;
}

/**
 * Claude-generated best bet banner showing ranked photography picks.
 *
 * @param {object}   props
 * @param {Array}    props.picks       - array of BestBet objects
 * @param {string}   props.todayStr    - today's date as YYYY-MM-DD
 * @param {string}   props.tomorrowStr - tomorrow's date as YYYY-MM-DD
 * @param {Function} props.onPickClick - called with eventKey when a navigable pick is clicked
 */
function BestBetBanner({ picks, todayStr, tomorrowStr, onPickClick }) {
  if (!picks || picks.length === 0) return null;

  return (
    <div className="mb-3 space-y-1.5" data-testid="best-bet-banner">
      {picks.map((pick) => {
        const eventKey = resolveEventKey(pick.event, todayStr, tomorrowStr);
        const navigable = pick.event != null && eventKey != null;
        const secondary = pick.rank !== 1;
        const lowConf = pick.confidence === 'low';

        const borderClass = secondary || lowConf
          ? 'border-plex-border'
          : 'border-amber-500/50';
        const bgClass = secondary || lowConf
          ? 'bg-plex-surface/40'
          : 'bg-amber-500/5';
        const opacityClass = secondary ? 'opacity-70' : '';
        const cursorClass = navigable ? 'cursor-pointer hover:bg-plex-surface/60' : 'cursor-default';

        const rankLabel = pick.rank === 1 ? '① BEST BET' : '② ALSO GOOD';
        const rankColour = secondary ? 'text-plex-text-muted' : 'text-amber-400';

        return (
          <button
            key={pick.rank}
            data-testid={`best-bet-pick-${pick.rank}`}
            disabled={!navigable}
            className={`w-full text-left rounded px-3 py-2.5 border transition-colors
              ${borderClass} ${bgClass} ${opacityClass} ${cursorClass}`}
            onClick={navigable ? () => onPickClick(eventKey) : undefined}
          >
            <div className="flex items-center gap-2 mb-0.5">
              <span className={`text-xs font-bold uppercase tracking-wider ${rankColour}`}>
                {rankLabel}
              </span>
              {lowConf && (
                <span className="text-xs text-plex-text-muted italic">(low confidence)</span>
              )}
              {pick.region && (
                <span className="text-xs text-plex-text-muted ml-auto">{pick.region}</span>
              )}
            </div>
            <p className={`text-sm font-medium leading-snug ${secondary ? 'text-plex-text-secondary' : 'text-plex-text'}`}>
              {pick.headline}
            </p>
            {pick.detail && (
              <p className="text-xs text-plex-text-secondary mt-0.5 leading-relaxed">{pick.detail}</p>
            )}
          </button>
        );
      })}
    </div>
  );
}

BestBetBanner.propTypes = {
  picks: PropTypes.arrayOf(PropTypes.shape({
    rank: PropTypes.number.isRequired,
    headline: PropTypes.string,
    detail: PropTypes.string,
    event: PropTypes.string,
    region: PropTypes.string,
    confidence: PropTypes.string,
  })),
  todayStr: PropTypes.string.isRequired,
  tomorrowStr: PropTypes.string.isRequired,
  onPickClick: PropTypes.func.isRequired,
};

/**
 * Expandable aurora tonight panel shown when the aurora state machine is active.
 *
 * @param {object} props
 * @param {object} props.aurora - AuroraTonightSummary from the briefing response
 */
function AuroraTonightPanel({ aurora }) {
  const [expanded, setExpanded] = useState(false);
  if (!aurora) return null;

  const levelColour = aurora.alertLevel === 'STRONG'
    ? 'text-red-400'
    : aurora.alertLevel === 'MODERATE'
      ? 'text-amber-400'
      : 'text-green-400';

  const levelLabel = { MINOR: 'Minor', MODERATE: 'Moderate', STRONG: 'Strong' };

  return (
    <div
      data-testid="aurora-tonight-panel"
      className="mb-3 rounded border border-indigo-500/30 bg-indigo-500/5 px-3 py-2"
    >
      <button
        className="w-full flex items-center gap-2 text-left"
        onClick={() => setExpanded((e) => !e)}
      >
        <span className="text-sm">🌌</span>
        <span className="text-xs font-semibold text-indigo-300">Aurora Alert</span>
        <span className={`text-xs font-bold ${levelColour}`}>
          {levelLabel[aurora.alertLevel] || aurora.alertLevel}
          {aurora.kp != null && ` (Kp ${aurora.kp.toFixed(1)})`}
        </span>
        <span className="text-xs text-plex-text-secondary ml-auto">
          {aurora.clearLocationCount} location{aurora.clearLocationCount !== 1 ? 's' : ''} clear
        </span>
        <Chevron open={expanded} className="text-sm text-plex-text-muted ml-1" />
      </button>

      {expanded && (
        <div className="mt-2 space-y-2">
          {(aurora.regions || []).map((region) => (
            <div key={region.regionName} data-testid="aurora-region" className="pl-2">
              <span className="text-xs font-medium text-plex-text">{region.regionName}</span>
              <div className="flex flex-wrap gap-1 mt-0.5">
                {(region.locations || []).map((loc) => (
                  <span
                    key={loc.locationName}
                    className={`text-xs px-1.5 py-0.5 rounded ${
                      loc.clear
                        ? 'bg-green-500/20 text-green-300'
                        : 'bg-plex-surface text-plex-text-muted'
                    }`}
                  >
                    {loc.locationName}
                    {loc.bortleClass != null && ` B${loc.bortleClass}`}
                    {loc.clear ? ' ✓' : ` ${loc.cloudPercent}%☁`}
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

AuroraTonightPanel.propTypes = {
  aurora: PropTypes.shape({
    alertLevel: PropTypes.string.isRequired,
    kp: PropTypes.number,
    clearLocationCount: PropTypes.number.isRequired,
    regions: PropTypes.array.isRequired,
  }),
};

/**
 * Small footer note for tomorrow's aurora forecast when it is worth watching.
 *
 * @param {object} props
 * @param {object} props.aurora - AuroraTomorrowSummary from the briefing response
 */
function AuroraTomorrowNote({ aurora }) {
  if (!aurora || aurora.label === 'Quiet') return null;
  return (
    <div
      data-testid="aurora-tomorrow-note"
      className="mt-2 text-xs text-indigo-300 flex items-center gap-1.5"
    >
      <span>🌌</span>
      <span>
        Tomorrow night: {aurora.label} (Kp {aurora.peakKp.toFixed(1)} forecast)
      </span>
    </div>
  );
}

AuroraTomorrowNote.propTypes = {
  aurora: PropTypes.shape({
    peakKp: PropTypes.number.isRequired,
    label: PropTypes.string.isRequired,
  }),
};

/** Sort order for verdict: GO first, MARGINAL second, STANDDOWN last. */
const VERDICT_ORDER = { GO: 0, MARGINAL: 1, STANDDOWN: 2 };

/**
 * Returns a copy of the slots array sorted by verdict (GO→MARGINAL→STANDDOWN),
 * then alphabetically by location name within each verdict group.
 *
 * @param {Array<object>} slots
 * @returns {Array<object>}
 */
function sortedSlots(slots) {
  return [...slots].sort((a, b) => {
    const vd = (VERDICT_ORDER[a.verdict] ?? 3) - (VERDICT_ORDER[b.verdict] ?? 3);
    return vd !== 0 ? vd : a.locationName.localeCompare(b.locationName);
  });
}

/**
 * Formats a drive duration in minutes as a human-readable string.
 *
 * @param {number|null} minutes
 * @returns {string|null}
 */
function formatDriveDuration(minutes) {
  if (minutes == null) return null;
  if (minutes < 60) return `${minutes} min`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m > 0 ? `${h}h ${m}min` : `${h}h`;
}

/**
 * Desktop-only heatmap grid: regions × upcoming events + optional aurora column.
 * Hidden on mobile (< sm breakpoint). Uses CSS Grid with inline gridTemplateColumns
 * since column count is dynamic.
 *
 * @param {object}   props
 * @param {Array}    props.upcomingEvents  - [{es, dayLabel, date}]
 * @param {Array}    props.allRegions      - unique region names in display order
 * @param {object}   props.briefing        - full DailyBriefingResponse
 * @param {Map}      props.driveMap        - location name → driveDurationMinutes
 * @param {Map}      props.typeMap         - location name → locationType string
 */
function HeatmapGrid({ upcomingEvents, allRegions, briefing, driveMap, typeMap }) {
  const [drillDown, setDrillDown] = useState(null); // { regionName, eventKey, region }

  const hasAurora = !!briefing.auroraTonight;

  // Cell lookup: eventKey → regionName → BriefingRegion
  const cellLookup = useMemo(() => {
    const map = new Map();
    upcomingEvents.forEach(({ es, date }) => {
      const eventKey = `${date}-${es.targetType}`;
      const regionMap = new Map();
      (es.regions || []).forEach((r) => regionMap.set(r.regionName, r));
      map.set(eventKey, regionMap);
    });
    return map;
  }, [upcomingEvents]);

  // Aurora region lookup: regionName → AuroraRegionSummary
  const auroraRegionMap = useMemo(() => {
    if (!hasAurora) return new Map();
    const m = new Map();
    (briefing.auroraTonight.regions || []).forEach((r) => m.set(r.regionName, r));
    return m;
  }, [briefing.auroraTonight, hasAurora]);

  const verdictCell = (verdict) => {
    if (verdict === 'GO') return 'bg-green-600/25 hover:bg-green-600/40 text-green-300';
    if (verdict === 'MARGINAL') return 'bg-amber-600/25 hover:bg-amber-600/40 text-amber-300';
    if (verdict === 'STANDDOWN') return 'bg-red-700/25 hover:bg-red-700/40 text-red-300';
    return 'bg-plex-surface/10 text-plex-text-muted cursor-default';
  };

  const toggleDrillDown = (regionName, eventKey, region) => {
    setDrillDown((prev) =>
      prev?.regionName === regionName && prev?.eventKey === eventKey
        ? null
        : { regionName, eventKey, region },
    );
  };

  const eventColCount = upcomingEvents.length;
  const auroraColDef = hasAurora ? ' minmax(72px, auto)' : '';
  const gridCols = `minmax(100px, auto) repeat(${eventColCount}, minmax(0, 1fr))${auroraColDef}`;

  if (allRegions.length === 0 || upcomingEvents.length === 0) return null;

  return (
    <div
      data-testid="briefing-heatmap"
      className="hidden sm:grid gap-1 mt-2"
      style={{ gridTemplateColumns: gridCols }}
    >
      {/* Header row */}
      <div className="text-xs text-plex-text-muted px-1 py-1 font-medium">Region</div>
      {upcomingEvents.map(({ es, dayLabel, date }) => {
        const emoji = es.targetType === 'SUNRISE' ? '🌅' : '🌇';
        return (
          <div
            key={`${date}-${es.targetType}`}
            className="text-xs text-plex-text-secondary text-center py-1 font-medium"
          >
            {emoji} {dayLabel}
          </div>
        );
      })}
      {hasAurora && (
        <div className="text-xs text-indigo-300 text-center py-1 font-medium">🌌 Tonight</div>
      )}

      {/* Region rows */}
      {allRegions.map((regionName) => (
        <React.Fragment key={regionName}>
          {/* Region label */}
          <div className="text-xs text-plex-text font-medium px-1 py-2 flex items-center truncate">
            {regionName}
          </div>

          {/* Event cells */}
          {upcomingEvents.map(({ es, date }) => {
            const eventKey = `${date}-${es.targetType}`;
            const region = cellLookup.get(eventKey)?.get(regionName);
            const isActive =
              drillDown?.regionName === regionName && drillDown?.eventKey === eventKey;
            return (
              <button
                key={eventKey}
                data-testid="heatmap-cell"
                className={`text-xs rounded py-1.5 px-1 text-center font-bold uppercase transition-colors
                  ${region ? verdictCell(region.verdict) : verdictCell(null)}
                  ${isActive ? 'ring-1 ring-white/20' : ''}`}
                onClick={() => region && toggleDrillDown(regionName, eventKey, region)}
                disabled={!region}
              >
                {region ? region.verdict.slice(0, 2) : '—'}
              </button>
            );
          })}

          {/* Aurora cell */}
          {hasAurora && (() => {
            const ar = auroraRegionMap.get(regionName);
            if (!ar) {
              return (
                <div className="text-xs text-plex-text-muted text-center py-1.5">—</div>
              );
            }
            const clearCount = (ar.locations || []).filter((l) => l.clear).length;
            return (
              <div className="text-xs text-center py-1.5">
                {clearCount > 0
                  ? <span className="text-indigo-300">{clearCount} clear</span>
                  : <span className="text-plex-text-muted">cloudy</span>}
              </div>
            );
          })()}

          {/* Drill-down panel — spans all columns */}
          {drillDown?.regionName === regionName && drillDown?.region && (
            <div
              data-testid="drill-down-panel"
              style={{ gridColumn: '1 / -1' }}
              className="px-2 py-2 rounded bg-plex-bg/40 border border-plex-border/50 space-y-1"
            >
              <p className="text-xs text-plex-text-secondary mb-1.5">
                {regionName} — {drillDown.region.summary}
              </p>
              {sortedSlots(drillDown.region.slots || []).map((slot) => {
                const drive = formatDriveDuration(driveMap.get(slot.locationName));
                const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
                return (
                  <div
                    key={slot.locationName}
                    className="flex flex-wrap items-center gap-1.5 text-xs"
                  >
                    <VerdictPill verdict={slot.verdict} />
                    <span className="text-sm font-medium text-plex-text">
                      {typeIcon && <span>{typeIcon} </span>}
                      {slot.locationName}
                    </span>
                    <span className="text-plex-text-secondary">{formatTime(slot.solarEventTime)}</span>
                    {drive && (
                      <span className="text-plex-text-secondary">🚗 {drive}</span>
                    )}
                    {slot.flags?.map((flag) => <FlagChip key={flag} label={flag} />)}
                  </div>
                );
              })}
            </div>
          )}
        </React.Fragment>
      ))}
    </div>
  );
}

HeatmapGrid.propTypes = {
  upcomingEvents: PropTypes.array.isRequired,
  allRegions: PropTypes.arrayOf(PropTypes.string).isRequired,
  briefing: PropTypes.object.isRequired,
  driveMap: PropTypes.instanceOf(Map).isRequired,
  typeMap: PropTypes.instanceOf(Map).isRequired,
};

/**
 * Collapsible daily briefing card displayed above the map view.
 *
 * Mobile (<sm): accordion-style event rows with drill-down per region.
 * Desktop (sm+): heatmap grid (regions × events) with inline drill-down panels.
 * Aurora tonight section displayed when the aurora state machine is active.
 *
 * Dismissal: the × button minimises to a pill and stores the dismissed briefing's generatedAt
 * in sessionStorage. A newer briefing (later generatedAt) automatically clears the dismissed
 * state and shows the card again. A new browser session always starts fresh.
 */
const DISMISSED_AT_KEY = 'briefing-dismissed-at';

export default function DailyBriefing({ locations }) {
  const { role } = useAuth();
  const canSeeBestBets = role === 'ADMIN' || role === 'PRO_USER';
  const [briefing, setBriefing] = useState(null);
  const [loading, setLoading] = useState(true);
  const [dismissedAt, setDismissedAt] = useState(() => sessionStorage.getItem(DISMISSED_AT_KEY));
  const [expandedEvents, setExpandedEvents] = useState(new Set());
  const [expandedRegions, setExpandedRegions] = useState(new Set());
  const intervalRef = useRef(null);

  /** Map from location name → driveDurationMinutes (only entries with a known duration). */
  const driveMap = useMemo(() => {
    const m = new Map();
    (locations || []).forEach((loc) => {
      if (loc.driveDurationMinutes != null) m.set(loc.name, loc.driveDurationMinutes);
    });
    return m;
  }, [locations]);

  /** Map from location name → locationType string. */
  const typeMap = useMemo(() => {
    const m = new Map();
    (locations || []).forEach((loc) => {
      if (loc.locationType) m.set(loc.name, loc.locationType);
    });
    return m;
  }, [locations]);

  const dismiss = () => {
    const ts = briefing?.generatedAt;
    if (!ts) return;
    setDismissedAt(ts);
    sessionStorage.setItem(DISMISSED_AT_KEY, ts);
  };

  const restore = () => {
    setDismissedAt(null);
    sessionStorage.removeItem(DISMISSED_AT_KEY);
  };

  const fetchBriefing = useCallback(async () => {
    try {
      const data = await getDailyBriefing();
      setBriefing(data);
    } catch {
      // Transient — keep existing data
    } finally {
      setLoading(false);
    }
  }, []);

  // Auto-show if a newer briefing has been generated since last dismissal.
  useEffect(() => {
    if (briefing && dismissedAt && briefing.generatedAt > dismissedAt) {
      setDismissedAt(null);
      sessionStorage.removeItem(DISMISSED_AT_KEY);
    }
  }, [briefing, dismissedAt]);

  useEffect(() => {
    fetchBriefing();
    intervalRef.current = setInterval(fetchBriefing, POLL_INTERVAL_MS);

    function handleFocus() {
      fetchBriefing();
    }
    window.addEventListener('focus', handleFocus);

    return () => {
      clearInterval(intervalRef.current);
      window.removeEventListener('focus', handleFocus);
    };
  }, [fetchBriefing]);

  const handlePickClick = useCallback((eventKey) => {
    setExpandedEvents((prev) => {
      const next = new Set(prev);
      next.add(eventKey);
      return next;
    });
    setTimeout(() => {
      const el = document.querySelector(`[data-event-key="${eventKey}"]`);
      if (el && typeof el.scrollIntoView === 'function') {
        el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      }
    }, 50);
  }, []);

  // Unique region names from upcoming (non-past) events — must be before early returns.
  const allRegions = useMemo(() => {
    if (!briefing) return [];
    const names = [];
    const seen = new Set();
    briefing.days.forEach((day) => {
      (day.eventSummaries || []).forEach((es) => {
        if (!isEventPast(es)) {
          (es.regions || []).forEach((r) => {
            if (!seen.has(r.regionName)) {
              seen.add(r.regionName);
              names.push(r.regionName);
            }
          });
        }
      });
    });
    return names;
  }, [briefing]);

  if (loading || !briefing) return null;

  // Dismissed — same briefing still in cache, show restore pill.
  const isDismissed = dismissedAt != null && briefing.generatedAt <= dismissedAt;

  if (isDismissed) {
    return (
      <button
        data-testid="briefing-minimised-pill"
        className="mb-4 px-3 py-1 rounded-full text-xs font-semibold text-plex-text-secondary border border-plex-border hover:bg-plex-surface transition-colors"
        onClick={restore}
        title="Restore PhotoCast Planner"
      >
        📋 Planner
      </button>
    );
  }

  const toggleEvent = (key) => {
    setExpandedEvents((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const toggleRegion = (key) => {
    setExpandedRegions((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const todayStr = new Date().toISOString().slice(0, 10);
  const tomorrowStr = (() => {
    const d = new Date();
    d.setUTCDate(d.getUTCDate() + 1);
    return d.toISOString().slice(0, 10);
  })();

  // Collect upcoming (not-yet-past) event summaries across all days
  const upcomingEvents = briefing.days.flatMap((day) =>
    (day.eventSummaries || [])
      .filter((es) => !isEventPast(es))
      .map((es) => ({
        es,
        dayLabel: day.date === todayStr ? 'Today' : 'Tomorrow',
        date: day.date,
      })),
  );

  const allExpanded = upcomingEvents.length > 0
    && upcomingEvents.every(({ es, date }) => expandedEvents.has(`${date}-${es.targetType}`));

  const toggleAll = () => {
    if (allExpanded) {
      setExpandedEvents(new Set());
    } else {
      setExpandedEvents(new Set(upcomingEvents.map(({ es, date }) => `${date}-${es.targetType}`)));
    }
  };

  const anyExpanded = upcomingEvents.some(({ es, date }) => expandedEvents.has(`${date}-${es.targetType}`));

  return (
    <div
      data-testid="daily-briefing"
      className="card mb-4 overflow-hidden"
    >
      {/* Header toggle row */}
      <div className="flex items-center gap-2">
        <button
          data-testid="briefing-toggle"
          className="flex-1 flex items-center justify-between gap-3 text-left"
          onClick={toggleAll}
        >
          <span className="text-xs font-semibold text-plex-text-secondary uppercase tracking-wide">
            PhotoCast Planner
          </span>
          <span className="flex items-center gap-2 text-xs text-plex-text-muted">
            {formatAge(briefing.generatedAt)}
            <Chevron open={allExpanded} className="text-base text-plex-text-muted" />
          </span>
        </button>
        <button
          data-testid="briefing-minimise"
          className="shrink-0 text-plex-text-muted hover:text-plex-text transition-colors text-xs px-1"
          onClick={dismiss}
          title="Minimise PhotoCast Planner"
          aria-label="Minimise PhotoCast Planner"
        >
          ✕
        </button>
      </div>

      {/* Best bet banner — ADMIN and PRO only */}
      {canSeeBestBets && briefing.bestBets && briefing.bestBets.length > 0 && (
        <BestBetBanner
          picks={briefing.bestBets}
          todayStr={todayStr}
          tomorrowStr={tomorrowStr}
          onPickClick={handlePickClick}
        />
      )}

      {/* Aurora tonight section — shown when alert is active */}
      <AuroraTonightPanel aurora={briefing.auroraTonight || null} />

      {/* Mobile accordion — hidden on desktop (sm+) */}
      <div className="sm:hidden">
        {/* Per-event summary rows — always visible */}
        <div className="mt-1" data-testid="briefing-collapsed-events">
          {upcomingEvents.length === 0 ? (
            <p className="text-xs text-plex-text-muted italic mt-1">
              No upcoming events in the next two days
            </p>
          ) : (
            upcomingEvents.map(({ es, dayLabel, date }) => (
              <div key={`${date}-${es.targetType}`} data-event-key={`${date}-${es.targetType}`}>
                <EventSummaryRow
                  dayLabel={dayLabel}
                  es={es}
                  isOpen={expandedEvents.has(`${date}-${es.targetType}`)}
                  onToggle={() => toggleEvent(`${date}-${es.targetType}`)}
                />
              </div>
            ))
          )}
        </div>

        {/* Expanded region content — renders when at least one event is open */}
        {anyExpanded && (
          <div className="mt-2 space-y-4" data-testid="briefing-expanded">
            {upcomingEvents.map(({ es, dayLabel: _dl, date }) => {
              const eventKey = `${date}-${es.targetType}`;
              if (!expandedEvents.has(eventKey)) return null;
              return (
                <div key={eventKey} className="mb-1">
                  {/* Region rows */}
                  {es.regions.map((region) => {
                    const regionKey = `${date}-${es.targetType}-${region.regionName}`;
                    const isOpen = expandedRegions.has(regionKey);
                    return (
                      <div key={regionKey} className="mb-1">
                        <button
                          data-testid="region-row"
                          className="w-full flex items-center gap-2 px-2 min-h-[44px] rounded hover:bg-plex-bg/50 text-left"
                          onClick={() => toggleRegion(regionKey)}
                        >
                          <VerdictPill verdict={region.verdict} />
                          <span className="text-sm text-plex-text font-medium">
                            {region.regionName}
                          </span>
                          <span className="text-sm text-plex-text-secondary flex-1 truncate">
                            {region.summary}
                          </span>
                          {region.regionTemperatureCelsius != null && (
                            <span
                              className="text-xs text-plex-text-secondary shrink-0 flex items-center gap-1"
                              data-testid="region-comfort"
                            >
                              {weatherCodeToIcon(region.regionWeatherCode)}
                              {Math.round(region.regionTemperatureCelsius)}°C
                              {region.regionApparentTemperatureCelsius != null && (
                                <span className="opacity-70">
                                  ({Math.round(region.regionApparentTemperatureCelsius)}°C)
                                </span>
                              )}
                              {region.regionWindSpeedMs != null && (
                                <>💨 {msToMph(region.regionWindSpeedMs)}mph</>
                              )}
                            </span>
                          )}
                          <span className="shrink-0 flex items-center justify-center w-11 h-11">
                            <Chevron open={isOpen} className="text-lg text-plex-text-muted" />
                          </span>
                        </button>

                        {/* Tide highlights */}
                        {region.tideHighlights?.length > 0 && (
                          <div className="flex flex-wrap gap-1 px-2 mt-0.5">
                            {region.tideHighlights.map((hl) => (
                              <FlagChip key={hl} label={hl} />
                            ))}
                          </div>
                        )}

                        {/* Expanded location slots */}
                        {isOpen && (
                          <div className="ml-4 mt-1 space-y-1" data-testid="region-slots">
                            {sortedSlots(region.slots).map((slot) => {
                              const drive = formatDriveDuration(driveMap.get(slot.locationName));
                              const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
                              return (
                                <div
                                  key={slot.locationName}
                                  className="flex flex-wrap items-center gap-1.5 px-2 py-1 rounded bg-plex-bg/30 text-xs"
                                  data-testid="briefing-slot"
                                >
                                  <VerdictPill verdict={slot.verdict} />
                                  <span className="text-sm font-medium text-plex-text">
                                    {typeIcon && <span data-testid="slot-type-icon">{typeIcon} </span>}
                                    {slot.locationName}
                                  </span>
                                  <span className="text-plex-text-secondary">
                                    {formatTime(slot.solarEventTime)}
                                  </span>
                                  {drive && (
                                    <span className="text-plex-text-secondary" data-testid="slot-drive-time">
                                      🚗 {drive}
                                    </span>
                                  )}
                                  {slot.flags?.map((flag) => (
                                    <FlagChip key={flag} label={flag} />
                                  ))}
                                </div>
                              );
                            })}
                          </div>
                        )}
                      </div>
                    );
                  })}

                  {/* Unregioned slots */}
                  {es.unregioned?.length > 0 && (
                    <div className="ml-2 mt-1 space-y-1">
                      {sortedSlots(es.unregioned).map((slot) => {
                        const drive = formatDriveDuration(driveMap.get(slot.locationName));
                        const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
                        return (
                          <div
                            key={slot.locationName}
                            className="flex flex-wrap items-center gap-1.5 px-2 py-1 rounded bg-plex-bg/30 text-xs"
                            data-testid="briefing-slot"
                          >
                            <VerdictPill verdict={slot.verdict} />
                            <span className="text-sm font-medium text-plex-text">
                              {typeIcon && <span data-testid="slot-type-icon">{typeIcon} </span>}
                              {slot.locationName}
                            </span>
                            <span className="text-plex-text-secondary">
                              {formatTime(slot.solarEventTime)}
                            </span>
                            {drive && (
                              <span className="text-plex-text-secondary" data-testid="slot-drive-time">
                                🚗 {drive}
                              </span>
                            )}
                            {slot.flags?.map((flag) => (
                              <FlagChip key={flag} label={flag} />
                            ))}
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Desktop heatmap grid — hidden on mobile, shown on sm+ */}
      <HeatmapGrid
        upcomingEvents={upcomingEvents}
        allRegions={allRegions}
        briefing={briefing}
        driveMap={driveMap}
        typeMap={typeMap}
      />

      {/* Aurora tomorrow footer note */}
      <AuroraTomorrowNote aurora={briefing.auroraTomorrow || null} />
    </div>
  );
}

DailyBriefing.propTypes = {
  locations: PropTypes.arrayOf(
    PropTypes.shape({
      name: PropTypes.string.isRequired,
      driveDurationMinutes: PropTypes.number,
      locationType: PropTypes.string,
    }),
  ),
};
