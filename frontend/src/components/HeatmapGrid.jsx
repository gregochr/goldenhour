import React, { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { computeCellTier, isCellVisible } from '../utils/tierUtils.js';
import useConfirmDialog from '../hooks/useConfirmDialog.js';

// ── Pure helpers (copied from DailyBriefing — shared logic) ─────────────────

const VERDICT_ORDER = { GO: 0, MARGINAL: 1, STANDDOWN: 2 };

function formatTime(isoString) {
  if (!isoString) return '';
  return isoString.substring(11, 16);
}

const AFTERGLOW_MS = 30 * 60 * 1000;

function isEventPast(es) {
  for (const r of es.regions || []) {
    for (const s of r.slots || []) {
      if (s.solarEventTime) {
        return new Date(s.solarEventTime + 'Z').getTime() + AFTERGLOW_MS < Date.now();
      }
    }
  }
  for (const s of es.unregioned || []) {
    if (s.solarEventTime) {
      return new Date(s.solarEventTime + 'Z').getTime() + AFTERGLOW_MS < Date.now();
    }
  }
  return false;
}

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

function msToMph(ms) {
  if (ms == null) return null;
  return Math.round(ms * 2.237);
}

function getShortDate(dateStr) {
  const d = new Date(dateStr + 'T12:00:00Z');
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
}

function getDayLabel(dateStr, todayStr, tomorrowStr) {
  if (dateStr === todayStr) return 'Today';
  if (dateStr === tomorrowStr) return 'Tomorrow';
  const d = new Date(dateStr + 'T12:00:00Z');
  return d.toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/**
 * Returns data for a specific region × date × targetType cell.
 * Returns null if the region/event doesn't exist or all events are past.
 */
function getSubCellData(date, regionName, targetType, briefingDays) {
  const day = briefingDays.find((d) => d.date === date);
  if (!day) return null;

  for (const es of day.eventSummaries || []) {
    if (es.targetType !== targetType) continue;
    const region = (es.regions || []).find((r) => r.regionName === regionName);
    if (!region) continue;
    return { region, es, past: isEventPast(es) };
  }
  return null;
}

/** Small shared components (local copies for the extracted file) */

function VerdictPill({ verdict }) {
  const colours = {
    GO: 'bg-green-600 text-white',
    MARGINAL: 'bg-amber-600 text-white',
    STANDDOWN: 'bg-red-900/60 text-red-200/70',
  };
  const labels = { GO: 'GO', MARGINAL: 'Marginal', STANDDOWN: 'Standdown' };
  return (
    <span
      data-testid="verdict-pill"
      className={`inline-block px-2 py-0.5 rounded text-[12px] font-bold ${colours[verdict] || 'bg-plex-surface text-plex-text-secondary'}`}
    >
      {labels[verdict] || verdict}
    </span>
  );
}

function FlagChip({ label }) {
  return (
    <span className="inline-block px-1.5 py-0.5 rounded bg-plex-surface border border-plex-border text-[11px] text-plex-text-secondary font-medium">
      {label}
    </span>
  );
}

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
 * Sort order for drill-down location slots:
 *   1. King tide + GO
 *   2. Tide-aligned + GO
 *   3. Other GO
 *   4. Tide-aligned + MARGINAL
 *   5. Other MARGINAL
 *   6. STANDDOWN (filtered out by caller)
 * Then A-Z within each group.
 */
function slotSortKey(slot) {
  const v = VERDICT_ORDER[slot.verdict] ?? 3;
  const hasKing = (slot.flags || []).some((f) => f.toLowerCase().includes('king'));
  if (v === 0 && hasKing) return 0;          // GO + king
  if (v === 0 && slot.tideAligned) return 1; // GO + tide
  if (v === 0) return 2;                     // GO plain
  if (v === 1 && slot.tideAligned) return 3; // MARGINAL + tide
  if (v === 1) return 4;                     // MARGINAL plain
  return 5;
}

function sortedSlots(slots) {
  return [...slots].sort((a, b) => {
    const diff = slotSortKey(a) - slotSortKey(b);
    return diff !== 0 ? diff : a.locationName.localeCompare(b.locationName);
  });
}

function formatDriveDuration(minutes) {
  if (minutes == null) return null;
  if (minutes < 60) return `${minutes} min`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m > 0 ? `${h}h ${m}min` : `${h}h`;
}

const LOCATION_TYPE_ICONS = {
  LANDSCAPE: '🏔️',
  WILDLIFE: '🐾',
  SEASCAPE: '🌊',
  WATERFALL: '💧',
};

// ── LocationSlotList ──────────────────────────────────────────────────────────

/** Star rating badge colour: green 4-5, amber 3, red 1-2. */
function ratingColour(rating) {
  if (rating >= 4) return 'bg-green-600/80 text-white';
  if (rating === 3) return 'bg-amber-600/80 text-white';
  return 'bg-red-700/70 text-red-100';
}

function LocationSlotList({ slots, driveMap, typeMap, scores = new Map() }) {
  const visible = sortedSlots((slots || []).filter((s) => s.verdict !== 'STANDDOWN'));
  if (visible.length === 0) return null;

  // Re-sort by Claude score when scores are available
  const sorted = scores.size > 0
    ? [...visible].sort((a, b) => {
      const sa = scores.get(a.locationName);
      const sb = scores.get(b.locationName);
      const ra = sa?.rating ?? 0;
      const rb = sb?.rating ?? 0;
      if (ra !== rb) return rb - ra; // higher score first
      const diff = slotSortKey(a) - slotSortKey(b);
      return diff !== 0 ? diff : a.locationName.localeCompare(b.locationName);
    })
    : visible;

  return (
    <div className="ml-4 mt-0.5 space-y-1 mb-1" data-testid="region-slots">
      {sorted.map((slot) => {
        const drive = formatDriveDuration(driveMap.get(slot.locationName));
        const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
        const score = scores.get(slot.locationName);
        return (
          <div
            key={slot.locationName}
            className="flex flex-wrap items-center gap-1.5 px-2 py-1 rounded bg-plex-bg/30 transition-all duration-500"
            data-testid="briefing-slot"
          >
            {score?.rating != null ? (
              <span
                data-testid="score-badge"
                className={`inline-block px-2 py-0.5 rounded text-[12px] font-bold transition-opacity duration-300 ${ratingColour(score.rating)}`}
              >
                {score.rating}★
              </span>
            ) : (
              <VerdictPill verdict={slot.verdict} />
            )}
            <span className="font-medium text-plex-text" style={{ fontSize: '13px' }}>
              {typeIcon && <span data-testid="slot-type-icon">{typeIcon} </span>}
              {slot.locationName}
            </span>
            <span className="text-plex-text-secondary" style={{ fontSize: '12px' }}>
              {formatTime(slot.solarEventTime)}
            </span>
            {drive && (
              <span className="text-plex-text-secondary" data-testid="slot-drive-time" style={{ fontSize: '12px' }}>
                🚗 {drive}
              </span>
            )}
            {slot.flags?.map((flag) => <FlagChip key={flag} label={flag} />)}
            {score?.summary && (
              <span className="w-full text-plex-text-secondary truncate" style={{ fontSize: '11px' }}>
                {score.summary}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ── HeatmapDrillDown ──────────────────────────────────────────────────────────

function HeatmapDrillDown({ date, regionName, targetType, briefingDays, driveMap, typeMap, onClose, onShowOnMap,
  evaluationScores = new Map(), evaluationProgress, onRunEvaluation, canRunEvaluation }) {
  const day = briefingDays.find((d) => d.date === date);
  const [expandedType, setExpandedType] = useState(null);
  const { openDialog, closeDialog, dialogElement } = useConfirmDialog();

  const events = [];
  if (day) {
    for (const es of day.eventSummaries || []) {
      // When opened from a sub-column, show only that event type
      if (targetType && es.targetType !== targetType) continue;
      const region = (es.regions || []).find((r) => r.regionName === regionName);
      if (region) events.push({ es, region, past: isEventPast(es) });
    }
  }

  // Build per-location scores map from evaluation results for this cell
  const slotScores = new Map();
  for (const [key, result] of evaluationScores) {
    // key format: "regionName|date|targetType|locationName"
    const prefix = `${regionName}|${date}|${targetType}|`;
    if (key.startsWith(prefix)) {
      slotScores.set(result.locationName, result);
    }
  }

  // Match progress to this drill-down's cell
  const progressMatch = evaluationProgress
    && evaluationProgress.regionName === regionName
    && evaluationProgress.date === date
    && evaluationProgress.targetType === targetType
    ? evaluationProgress : null;

  // Count GO/MARGINAL slots for the confirmation dialog
  const goMarginalSlots = events.flatMap(({ region }) =>
    (region.slots || []).filter((s) => s.verdict === 'GO' || s.verdict === 'MARGINAL'),
  );

  return (
    <div
      data-testid="drill-down-panel"
      style={{ gridColumn: '1 / -1' }}
      className="px-3 py-2.5 rounded bg-plex-bg/50 border border-plex-border/30 mt-0.5"
    >
      <div className="flex items-center justify-between mb-2">
        <span className="font-semibold text-plex-text" style={{ fontSize: '13px' }}>
          {regionName} — {getShortDate(date)}
          {targetType && (
            <span className="ml-1.5 text-plex-text-secondary" style={{ fontSize: '12px' }}>
              {targetType === 'SUNRISE' ? '🌅 Sunrise' : '🌇 Sunset'}
            </span>
          )}
        </span>
        <button
          onClick={onClose}
          className="text-plex-text-muted hover:text-plex-text px-1 text-sm"
          aria-label="Close drill-down"
        >
          ✕
        </button>
      </div>

      <div className="space-y-0.5">
        {events.map(({ es, region, past }) => {
          const tappable = !past && region.verdict !== 'STANDDOWN';
          const eventKey = es.targetType;
          const isExpanded = expandedType === eventKey;
          const eventTime = formatTime(
            (es.regions || []).flatMap((r) => r.slots || []).find((s) => s.solarEventTime)?.solarEventTime,
          );
          const emoji = es.targetType === 'SUNRISE' ? '🌅' : '🌇';
          const eventName = es.targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset';

          return (
            <div key={eventKey}>
              <div
                data-testid="drill-down-event-row"
                role={tappable ? 'button' : undefined}
                tabIndex={tappable ? 0 : undefined}
                className={`flex items-center gap-2 px-2 py-1.5 rounded
                  ${tappable ? 'cursor-pointer hover:bg-plex-bg/30' : 'opacity-40 cursor-default'}
                  ${isExpanded ? 'bg-plex-bg/20' : ''}`}
                style={{ fontSize: '12px' }}
                onClick={tappable ? () => setExpandedType(isExpanded ? null : eventKey) : undefined}
              >
                <span className="text-sm">{emoji}</span>
                <span className="font-medium text-plex-text" style={{ minWidth: '68px', fontSize: '13px' }}>
                  {eventName}{eventTime ? ` · ${eventTime}` : ''}
                </span>
                <VerdictPill verdict={region.verdict} />
                <span className="text-plex-text-secondary flex-1 truncate" style={{ fontSize: '12px' }}>
                  {region.summary}
                </span>
                {region.regionTemperatureCelsius != null && (
                  <span className="text-plex-text-secondary shrink-0" style={{ fontSize: '12px' }}>
                    {weatherCodeToIcon(region.regionWeatherCode)}
                    {Math.round(region.regionTemperatureCelsius)}°C
                    {region.regionWindSpeedMs != null && ` · ${msToMph(region.regionWindSpeedMs)}mph`}
                  </span>
                )}
                {tappable && onShowOnMap && date && (
                  <button
                    data-testid="show-on-map-btn"
                    className="shrink-0 text-plex-text-muted hover:text-plex-text transition-colors px-1"
                    style={{ fontSize: '14px' }}
                    title="Show on map"
                    onClick={(e) => {
                      e.stopPropagation();
                      onShowOnMap(date, es.targetType);
                    }}
                  >
                    🗺
                  </button>
                )}
                {tappable && (
                  <Chevron open={isExpanded} className="text-plex-text-muted shrink-0" />
                )}
              </div>

              {isExpanded && (
                <LocationSlotList slots={region.slots} driveMap={driveMap} typeMap={typeMap} scores={slotScores} />
              )}
            </div>
          );
        })}
      </div>

      {/* "Run full forecast" button */}
      {canRunEvaluation && (
        <div className="mt-2 pt-1.5 border-t border-plex-border/20 flex gap-2 items-center">
          {progressMatch?.status === 'complete' ? (
            <span data-testid="run-forecast-btn" className="text-green-400 text-xs font-medium">
              Evaluation complete — {progressMatch.completed} scored
            </span>
          ) : progressMatch?.status === 'running' ? (
            <>
              <button data-testid="run-forecast-btn" disabled className="btn-secondary text-xs opacity-60">
                Evaluating… {progressMatch.completed}/{progressMatch.total}
              </button>
              {progressMatch.total > 0 && (
                <div className="flex-1 h-1 bg-plex-border/30 rounded overflow-hidden">
                  <div
                    className="h-full bg-green-500 transition-all duration-300"
                    style={{ width: `${Math.round(((progressMatch.completed) / progressMatch.total) * 100)}%` }}
                  />
                </div>
              )}
            </>
          ) : progressMatch?.status === 'error' ? (
            <button
              data-testid="run-forecast-btn"
              className="btn-secondary text-xs text-red-400 border-red-700 hover:bg-red-900/40"
              onClick={() => onRunEvaluation?.(regionName, date, targetType)}
            >
              Forecast failed — retry?
            </button>
          ) : (
            <button
              data-testid="run-forecast-btn"
              className="btn-secondary text-xs hover:bg-green-800/60 hover:text-green-200"
              onClick={() => {
                const count = goMarginalSlots.length;
                openDialog({
                  title: 'Run Claude Evaluation',
                  message: `Evaluate ${count} location${count !== 1 ? 's' : ''} with Claude? Estimated cost: ~${count * 3}p (${count} × ~3p).`,
                  confirmLabel: 'Run',
                  maxWidth: 'sm',
                  onConfirm: () => {
                    closeDialog();
                    onRunEvaluation?.(regionName, date, targetType);
                  },
                });
              }}
            >
              Run full forecast
            </button>
          )}
        </div>
      )}

      {dialogElement}
    </div>
  );
}

// ── Sub-column cell ───────────────────────────────────────────────────────────

function HeatmapCell({ date, regionName, targetType, briefingDays, qualityTier, isActive, onToggle, evaluationScores = new Map() }) {
  const cellData = getSubCellData(date, regionName, targetType, briefingDays);

  // Empty cell — region doesn't appear in this event type
  if (!cellData) {
    return (
      <div
        className="text-center py-2 text-plex-text-muted opacity-30"
        style={{ fontSize: '12px' }}
      >
        —
      </div>
    );
  }

  const { region, past } = cellData;

  const cellTier = computeCellTier(region);
  const visible = isCellVisible(cellTier, qualityTier);

  const isStanddown = region.verdict === 'STANDDOWN';

  // Extract the best tide label from tideHighlights (e.g. "King Tide, Extra Extra High at Bamburgh" → "King Tide, Extra Extra High")
  const tideHighlight = (region.tideHighlights || []).find((h) =>
    h.toLowerCase().includes('king') || h.toLowerCase().includes('spring') || h.toLowerCase().includes('extra'));
  const tideLabel = tideHighlight ? tideHighlight.replace(/ at .+$/, '') : null;
  const hasKingTide = tideLabel?.toLowerCase().includes('king');
  const alignedCount = (region.slots || []).filter((s) => s.tideAligned).length;

  // Cell background colour by tier
  const tierBg = {
    0: 'bg-green-600/30 border-green-600/40 hover:bg-green-600/45', // go-king: strongest green
    1: 'bg-green-600/22 border-green-600/25 hover:bg-green-600/38', // go-tide: medium green
    2: 'bg-green-600/15 border-green-600/18 hover:bg-green-600/30', // go-plain: light green
    3: 'bg-amber-500/22 border-amber-500/28 hover:bg-amber-500/36', // ma-tide: medium amber
    4: 'bg-amber-500/14 border-amber-500/18 hover:bg-amber-500/28', // ma-plain: light amber
    5: 'border-red-500/8',                                            // standdown: faint red
  };

  const verdictTextColour = isStanddown
    ? 'text-plex-text-muted'
    : region.verdict === 'GO'
      ? 'text-green-300'
      : 'text-amber-300';

  const eventLabel = targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
  const verdictLabel = isStanddown ? 'Poor'
    : region.verdict === 'GO' ? `GO ${eventLabel}`
      : `Marginal ${eventLabel}`;

  // Clear % from best slot
  const bestSlot = (region.slots || []).reduce((best, s) => {
    if (!best) return s;
    const bOrder = VERDICT_ORDER[best.verdict] ?? 3;
    const sOrder = VERDICT_ORDER[s.verdict] ?? 3;
    return sOrder < bOrder ? s : best;
  }, null);
  const clearPct = bestSlot?.lowCloudPercent != null
    ? Math.round(100 - bestSlot.lowCloudPercent)
    : null;

  const visibilityClass = visible ? 'heatmap-cell-visible' : 'heatmap-cell-hidden';

  return (
    <button
      data-testid="heatmap-cell"
      disabled={isStanddown || !visible || past}
      aria-hidden={!visible}
      className={`relative rounded border text-left p-1.5 transition-all ${visibilityClass}
        ${tierBg[cellTier] || ''}
        ${isStanddown ? 'cursor-default' : 'cursor-pointer hover:scale-[1.01]'}
        ${isActive ? 'ring-1 ring-white/25' : ''}`}
      style={{
        pointerEvents: (!visible || isStanddown || past) ? 'none' : undefined,
        opacity: isStanddown ? (visible ? 0.3 : 0.04) : undefined,
        backgroundColor: isStanddown ? (visible ? 'rgba(180,50,50,0.04)' : undefined) : undefined,
      }}
      onClick={(!visible || isStanddown || past) ? undefined : () => onToggle(date, regionName, targetType)}
    >
      <div className={`font-medium ${verdictTextColour}`} style={{ fontSize: '11px' }}>
        {verdictLabel}
      </div>

      {!isStanddown && region && (
        <>
          {clearPct != null && (
            <div className="text-plex-text-secondary" style={{ fontSize: '10px' }}>
              {clearPct}% clear
            </div>
          )}
          {region.regionTemperatureCelsius != null && (
            <div className="text-plex-text-secondary mt-0.5" style={{ fontSize: '10px' }}>
              {weatherCodeToIcon(region.regionWeatherCode)}
              {Math.round(region.regionTemperatureCelsius)}°C
              {region.regionWindSpeedMs != null
                && ` ${msToMph(region.regionWindSpeedMs)}mph`}
            </div>
          )}
          {(tideLabel || alignedCount > 0) && (
            <div className="flex flex-wrap gap-0.5 mt-0.5">
              {tideLabel && (
                <span className={`rounded px-1 font-medium ${hasKingTide ? 'bg-red-500/20 text-red-300' : 'bg-amber-500/20 text-amber-300'}`}
                  style={{ fontSize: '9px' }}>
                  {tideLabel}
                </span>
              )}
              {alignedCount > 0 && !tideLabel && (
                <span className="rounded px-1 bg-teal-500/20 text-teal-300 font-medium"
                  style={{ fontSize: '9px' }}>
                  {alignedCount} aligned
                </span>
              )}
            </div>
          )}
        </>
      )}

      {/* Mean Claude score badge */}
      {!isStanddown && (() => {
        const prefix = `${regionName}|${date}|${targetType}|`;
        const ratings = [];
        for (const [key, result] of evaluationScores) {
          if (key.startsWith(prefix) && result.rating != null) {
            ratings.push(result.rating);
          }
        }
        if (ratings.length === 0) return null;
        const mean = (ratings.reduce((a, b) => a + b, 0) / ratings.length).toFixed(1);
        const meanNum = parseFloat(mean);
        const pillColour = meanNum >= 4 ? 'bg-green-600/60 text-green-200'
          : meanNum >= 3 ? 'bg-amber-600/60 text-amber-200'
            : 'bg-red-700/50 text-red-200';
        return (
          <div className="mt-0.5" data-testid="mean-score-badge">
            <span className={`rounded px-1 font-medium ${pillColour}`} style={{ fontSize: '10px' }}>
              {mean}★
            </span>
          </div>
        );
      })()}

      {isStanddown && region?.summary && (
        <div className="text-red-400/50 mt-0.5" style={{ fontSize: '10px' }}>
          {region.summary.slice(0, 24)}
        </div>
      )}
    </button>
  );
}

// ── HeatmapGrid (main export) ─────────────────────────────────────────────────

/**
 * Desktop heatmap grid — up to 6 event columns with dynamic day-header spanning.
 *
 * @param {object[]}  events         Up to 6 upcoming event objects [{date, targetType}]
 * @param {string[]}  sortedRegions  Region names ordered by quality (re-sorted internally by visible tier)
 * @param {object[]}  briefingDays   Raw briefing days array
 * @param {number}    qualityTier    Current slider position (0–5)
 * @param {Map}       driveMap       locationName → driveDurationMinutes
 * @param {Map}       typeMap        locationName → locationType string
 * @param {string}    todayStr       YYYY-MM-DD for today
 * @param {string}    tomorrowStr    YYYY-MM-DD for tomorrow
 * @param {function}  onShowOnMap    (date, targetType) callback
 */
export default function HeatmapGrid({
  events,
  sortedRegions,
  briefingDays,
  qualityTier,
  driveMap,
  typeMap,
  todayStr,
  tomorrowStr,
  onShowOnMap,
  evaluationScores = new Map(),
  evaluationProgress,
  onRunEvaluation,
  canRunEvaluation = false,
}) {
  const [drillDown, setDrillDown] = useState(null); // { date, regionName, targetType }

  // Compute day groups from events for dynamic header spanning
  const dayGroups = useMemo(() => {
    const groups = [];
    let current = null;
    for (const ev of events) {
      if (!current || current.date !== ev.date) {
        current = { date: ev.date, count: 1 };
        groups.push(current);
      } else {
        current.count++;
      }
    }
    return groups;
  }, [events]);

  if (sortedRegions.length === 0 || events.length === 0) return null;

  const numEventCols = events.length;
  const gridCols = `minmax(100px, 140px) repeat(${numEventCols}, minmax(0, 1fr))`;

  const toggleDrillDown = (date, regionName, targetType) => {
    setDrillDown((prev) =>
      prev?.date === date && prev?.regionName === regionName && prev?.targetType === targetType
        ? null
        : { date, regionName, targetType },
    );
  };

  // Sort region rows by best visible tier at the current slider position.
  const reorderedRegions = [...sortedRegions].sort((a, b) => {
    let bestA = 6;
    let bestB = 6;
    for (const { date, targetType } of events) {
      const cdA = getSubCellData(date, a, targetType, briefingDays);
      const cdB = getSubCellData(date, b, targetType, briefingDays);
      if (cdA && !cdA.past) {
        const t = computeCellTier(cdA.region);
        if (isCellVisible(t, qualityTier) && t < bestA) bestA = t;
      }
      if (cdB && !cdB.past) {
        const t = computeCellTier(cdB.region);
        if (isCellVisible(t, qualityTier) && t < bestB) bestB = t;
      }
    }
    return bestA !== bestB ? bestA - bestB : a.localeCompare(b);
  });

  // Check whether all cells in a region row are hidden (for label fading)
  function isRegionFullyHidden(regionName) {
    for (const { date, targetType } of events) {
      const cd = getSubCellData(date, regionName, targetType, briefingDays);
      if (!cd || cd.past) continue;
      const t = computeCellTier(cd.region);
      if (isCellVisible(t, qualityTier)) return false;
    }
    return true;
  }

  return (
    <div
      data-testid="briefing-heatmap"
      className="hidden sm:grid gap-1 mt-2"
      style={{ gridTemplateColumns: gridCols }}
    >
      {/* ── Header row: corner + day-spanning headers ── */}
      <div className="px-1 py-1" style={{ fontSize: '12px', color: 'var(--color-plex-text-secondary)' }}>
        Region
      </div>
      {dayGroups.map(({ date, count }) => (
        <div
          key={date}
          data-testid="heatmap-day-header"
          className="text-center py-1 px-1"
          style={{ gridColumn: `span ${count}` }}
        >
          <div className="font-semibold text-plex-text" style={{ fontSize: '13px' }}>
            {getDayLabel(date, todayStr, tomorrowStr)}
          </div>
          <div className="text-plex-text-secondary" style={{ fontSize: '11px' }}>
            {getShortDate(date)}
          </div>
        </div>
      ))}

      {/* ── Sub-column header row: 🌅 / 🌇 ── */}
      <div /> {/* empty corner */}
      {events.map(({ date, targetType }) => (
        <div
          key={`${date}-${targetType}`}
          className="text-center text-plex-text-muted pb-0.5"
          style={{ fontSize: '13px' }}
          title={targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset'}
        >
          {targetType === 'SUNRISE' ? '🌅' : '🌇'}
        </div>
      ))}

      {/* ── Region rows ── */}
      {reorderedRegions.map((regionName) => {
        const fullyHidden = isRegionFullyHidden(regionName);

        return (
          <React.Fragment key={regionName}>
            {/* Region label */}
            <div
              className="font-medium text-plex-text px-1 py-2 flex items-start transition-opacity duration-300"
              style={{
                fontSize: '13px',
                overflowWrap: 'break-word',
                wordBreak: 'break-word',
                minWidth: 0,
                opacity: fullyHidden ? 0.06 : 1,
              }}
              aria-hidden={fullyHidden}
            >
              {regionName}
            </div>

            {/* Event cells */}
            {events.map(({ date, targetType }) => {
              const drillKey = `${date}-${regionName}-${targetType}`;
              const isActive =
                drillDown?.date === date &&
                drillDown?.regionName === regionName &&
                drillDown?.targetType === targetType;
              return (
                <HeatmapCell
                  key={drillKey}
                  date={date}
                  regionName={regionName}
                  targetType={targetType}
                  briefingDays={briefingDays}
                  qualityTier={qualityTier}
                  isActive={isActive}
                  onToggle={toggleDrillDown}
                  evaluationScores={evaluationScores}
                />
              );
            })}

            {/* Drill-down panel — spans full grid width */}
            {drillDown?.regionName === regionName && drillDown?.date && (
              <HeatmapDrillDown
                date={drillDown.date}
                regionName={regionName}
                targetType={drillDown.targetType}
                briefingDays={briefingDays}
                driveMap={driveMap}
                typeMap={typeMap}
                onClose={() => setDrillDown(null)}
                onShowOnMap={onShowOnMap}
                evaluationScores={evaluationScores}
                evaluationProgress={evaluationProgress}
                onRunEvaluation={onRunEvaluation}
                canRunEvaluation={canRunEvaluation}
              />
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
}

HeatmapGrid.propTypes = {
  events: PropTypes.arrayOf(PropTypes.shape({
    date: PropTypes.string.isRequired,
    targetType: PropTypes.string.isRequired,
  })).isRequired,
  sortedRegions: PropTypes.arrayOf(PropTypes.string).isRequired,
  briefingDays: PropTypes.array.isRequired,
  qualityTier: PropTypes.number.isRequired,
  driveMap: PropTypes.instanceOf(Map).isRequired,
  typeMap: PropTypes.instanceOf(Map).isRequired,
  todayStr: PropTypes.string.isRequired,
  tomorrowStr: PropTypes.string.isRequired,
  onShowOnMap: PropTypes.func,
  evaluationScores: PropTypes.instanceOf(Map),
  evaluationProgress: PropTypes.shape({
    regionName: PropTypes.string,
    date: PropTypes.string,
    targetType: PropTypes.string,
    completed: PropTypes.number,
    total: PropTypes.number,
    failed: PropTypes.number,
    status: PropTypes.string,
  }),
  onRunEvaluation: PropTypes.func,
  canRunEvaluation: PropTypes.bool,
};
