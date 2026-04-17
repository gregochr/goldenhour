import React, { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { computeCellTier, isCellVisible } from '../utils/tierUtils.js';
import useConfirmDialog from '../hooks/useConfirmDialog.js';
import { formatEventTimeUk, formatTideHighlight } from '../utils/conversions.js';
import InfoTip from './InfoTip.jsx';
import ProPill from './shared/ProPill.jsx';

// ── Pure helpers (copied from DailyBriefing — shared logic) ─────────────────

const VERDICT_ORDER = { GO: 0, MARGINAL: 1, STANDDOWN: 2 };

function formatTime(isoString) {
  if (!isoString) return '';
  return formatEventTimeUk(isoString) ?? '';
}

const AFTERGLOW_MS = 30 * 60 * 1000;

/** Typical cost per call in pence, keyed by model. */
const COST_PENCE_PER_CALL = { HAIKU: 0.16, SONNET: 0.40, OPUS: 0.63 };

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

/**
 * Returns the set of location names belonging to a region on a given date.
 * Extracted from briefing event summaries (any target type).
 */
function getRegionLocationNames(date, regionName, briefingDays) {
  const day = briefingDays.find((d) => d.date === date);
  if (!day) return [];
  const names = new Set();
  for (const es of day.eventSummaries || []) {
    const region = (es.regions || []).find((r) => r.regionName === regionName);
    if (region) {
      for (const slot of region.slots || []) {
        names.add(slot.locationName);
      }
    }
  }
  return [...names];
}

/** Small shared components (local copies for the extracted file) */
/* eslint-disable react/prop-types */

function VerdictPill({ verdict }) {
  const colours = {
    GO: 'bg-green-600 text-white',
    MARGINAL: 'bg-amber-600 text-white',
    STANDDOWN: 'bg-red-900/60 text-red-200/70',
  };
  const labels = { GO: 'WORTH IT', MARGINAL: 'MAYBE', STANDDOWN: 'Stand Down' };
  return (
    <span
      data-testid="verdict-pill"
      className={`inline-block px-2 py-0.5 rounded text-[12px] font-bold ${colours[verdict] || 'bg-plex-surface text-plex-text-secondary'}`}
    >
      {labels[verdict] || verdict}
    </span>
  );
}

function FlagChip({ label }) {  return (
    <span className="inline-block px-1.5 py-0.5 rounded bg-plex-surface border border-plex-border text-[11px] text-plex-text-secondary font-medium">
      {label}
    </span>
  );
}

function Chevron({ open, className = '' }) {  return (
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

/** Star rating badge colour: 5-tier from bright green to red. */
function ratingColour(rating) {
  if (rating >= 5) return 'bg-green-500/90 text-white';
  if (rating === 4) return 'bg-green-600/80 text-white';
  if (rating === 3) return 'bg-amber-500/80 text-white';
  if (rating === 2) return 'bg-orange-600/80 text-white';
  return 'bg-red-700/70 text-red-100';
}

function LocationSlotList({ slots, driveMap, typeMap, scores = new Map(), evaluationComplete = false, showAllLocations = false }) {  const visible = sortedSlots((slots || []).filter((s) => s.verdict !== 'STANDDOWN'));
  const standdownSlots = showAllLocations
    ? (slots || []).filter((s) => s.verdict === 'STANDDOWN')
    : [];

  const hasHiddenStanddowns = !showAllLocations && (slots || []).some((s) => s.verdict === 'STANDDOWN');

  if (visible.length === 0 && standdownSlots.length === 0) {
    if (hasHiddenStanddowns) {
      return (
        <div className="ml-4 mt-1 text-plex-text-muted italic" style={{ fontSize: '12px' }}
          data-testid="standdown-hint">
          No viable locations for this event. Toggle &ldquo;Show all locations&rdquo; to see why.
        </div>
      );
    }
    return null;
  }

  // Re-sort by Claude score only after evaluation completes (the "reveal" moment).
  // During streaming, keep triage order so rows don't jump around.
  const sorted = (evaluationComplete && scores.size > 0)
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
    <div className="ml-4 mt-0.5 mb-1" data-testid="region-slots">
      {sorted.map((slot) => {
        const drive = formatDriveDuration(driveMap.get(slot.locationName));
        const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
        const score = scores.get(slot.locationName);
        return (
          <div
            key={slot.locationName}
            className="flex flex-wrap items-center gap-1.5 px-2 py-1 rounded bg-plex-bg/30 transition-all duration-500 mt-1"
            data-testid="briefing-slot"
          >
            {score?.rating != null ? (
              <span
                data-testid="score-badge"
                className={`inline-block px-2 py-0.5 rounded text-[12px] font-bold animate-fade-in ${ratingColour(score.rating)}`}
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
              <span className="w-full text-plex-text-secondary truncate animate-fade-in" style={{ fontSize: '11px' }}>
                {score.summary}
              </span>
            )}
          </div>
        );
      })}

      {standdownSlots.length > 0 && (
        <>
          <div className="flex items-center gap-2 mt-2 mb-1">
            <div className="flex-1 h-px bg-plex-border/30" />
            <span className="text-plex-text-muted" style={{ fontSize: '11px' }}
              data-testid="standdown-divider">Poor conditions</span>
            <div className="flex-1 h-px bg-plex-border/30" />
          </div>
          {standdownSlots.map((slot) => {
            const drive = formatDriveDuration(driveMap.get(slot.locationName));
            const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
            return (
              <div
                key={slot.locationName}
                className="flex flex-wrap items-center gap-1.5 px-2 py-1 rounded bg-plex-bg/30 mt-1 opacity-50"
                data-testid="standdown-slot"
              >
                <span className="inline-block px-2 py-0.5 rounded text-[12px] font-bold bg-red-900/40 text-red-300/70">
                  Poor
                </span>
                <span className="font-medium text-plex-text" style={{ fontSize: '13px' }}>
                  {typeIcon && <span>{typeIcon} </span>}
                  {slot.locationName}
                </span>
                <span className="text-plex-text-muted" style={{ fontSize: '12px' }}>
                  {slot.standdownReason || slot.flags?.[0] || 'Poor conditions'}
                </span>
                {drive && (
                  <span className="text-plex-text-secondary" style={{ fontSize: '12px' }}>
                    {drive}
                  </span>
                )}
              </div>
            );
          })}
        </>
      )}
    </div>
  );
}

// ── HeatmapDrillDown ──────────────────────────────────────────────────────────

function HeatmapDrillDown({ date, regionName, targetType, briefingDays, driveMap, typeMap, onClose, onShowOnMap,  evaluationScores = new Map(), evaluationProgress, evaluationTimestamps = new Map(), onRunEvaluation, onStopEvaluation, canRunEvaluation, activeModelName, showAllLocations = false }) {
  const day = briefingDays.find((d) => d.date === date);
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
          const tappable = !past && (region.verdict !== 'STANDDOWN' || showAllLocations);
          const eventKey = es.targetType;
          const eventTime = formatTime(
            (es.regions || []).flatMap((r) => r.slots || []).find((s) => s.solarEventTime)?.solarEventTime,
          );
          const emoji = es.targetType === 'SUNRISE' ? '🌅' : '🌇';
          const eventName = es.targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset';

          return (
            <div key={eventKey}>
              <div
                data-testid="drill-down-event-row"
                className={`flex items-center gap-2 px-2 py-1.5 rounded
                  ${tappable ? '' : 'opacity-40'}`}
                style={{ fontSize: '12px' }}
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
                      if (onStopEvaluation) onStopEvaluation();
                      onShowOnMap(date, es.targetType);
                    }}
                  >
                    🗺
                  </button>
                )}
              </div>

              {region.glossDetail && (
                <div className="text-plex-text-secondary px-2 py-1" style={{ fontSize: '13px' }}>
                  {region.glossDetail}
                </div>
              )}
              {tappable && (
                <LocationSlotList
                  slots={region.slots}
                  driveMap={driveMap}
                  typeMap={typeMap}
                  scores={slotScores}
                  evaluationComplete={progressMatch?.status === 'complete' || (!progressMatch && slotScores.size > 0)}
                  isPro={canRunEvaluation}
                  showAllLocations={showAllLocations}
                />
              )}
            </div>
          );
        })}
      </div>

      {/* "Run full forecast" button */}
      {!canRunEvaluation ? (
        <div className="mt-2 pt-1.5 border-t border-plex-border/20 flex gap-2 items-center opacity-45 pointer-events-none">
          <button data-testid="run-forecast-btn" disabled className="btn-secondary text-xs">
            Run full forecast
          </button>
          <ProPill />
        </div>
      ) : (() => {
        const cachedTimestamp = evaluationTimestamps.get(`${regionName}|${date}|${targetType}`);
        const hasCachedScores = !progressMatch && slotScores.size > 0 && cachedTimestamp;

        return (
          <div className="mt-2 pt-1.5 border-t border-plex-border/20 flex gap-2 items-center">
            {(progressMatch?.status === 'complete' || hasCachedScores) ? (
              <span data-testid="run-forecast-btn" className="text-green-400 text-xs font-medium">
                Scored {progressMatch?.completed ?? slotScores.size} of {progressMatch?.total ?? slotScores.size}
                {' · '}{progressMatch?.evaluatedAt ?? cachedTimestamp}
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
                  const perCallPence = COST_PENCE_PER_CALL[activeModelName] ?? 3;
                  const totalPence = count * perCallPence;
                  const modelLabel = activeModelName ? activeModelName.charAt(0) + activeModelName.slice(1).toLowerCase() : 'Claude';
                  const totalLabel = totalPence >= 10
                    ? `~£${(totalPence / 100).toFixed(2)}`
                    : totalPence >= 1 ? `~${Math.round(totalPence)}p` : `~${totalPence.toFixed(1)}p`;
                  const costStr = `${totalLabel} (${count} × ~${perCallPence.toFixed(2)}p)`;
                  openDialog({
                    title: 'Run Claude Evaluation',
                    message: `Evaluate ${count} location${count !== 1 ? 's' : ''} with ${modelLabel}? Estimated cost: ${costStr}.`,
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
        );
      })()}

      {dialogElement}
    </div>
  );
}

// ── Sub-column cell ───────────────────────────────────────────────────────────

function HeatmapCell({ date, regionName, targetType, briefingDays, qualityTier, isActive, onToggle, evaluationScores = new Map(), evaluationTimestamps = new Map(), showAllLocations = false }) {  const cellData = getSubCellData(date, regionName, targetType, briefingDays);

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

  // Extract the best tide label from tideHighlights (e.g. "King Tide at 3 coastal spots" → "3 king tides")
  const tideHighlight = (region.tideHighlights || []).find((h) =>
    h.toLowerCase().includes('king') || h.toLowerCase().includes('spring') || h.toLowerCase().includes('extra'));
  const tideLabel = tideHighlight ? formatTideHighlight(tideHighlight) : null;
  const hasKingTide = tideHighlight?.toLowerCase().includes('king');
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
    : region.verdict === 'GO' ? `Worth it ${eventLabel}`
      : `Maybe ${eventLabel}`;

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

  const standdownClickable = isStanddown && showAllLocations;
  const cellDisabled = (isStanddown && !showAllLocations) || !visible || past;
  const cellClickable = !cellDisabled;

  return (
    <button
      data-testid="heatmap-cell"
      disabled={cellDisabled}
      aria-hidden={!visible}
      className={`relative rounded border text-left p-1.5 transition-all ${visibilityClass}
        ${tierBg[cellTier] || ''}
        ${(isStanddown && !standdownClickable) ? 'cursor-default' : 'cursor-pointer hover:scale-[1.01]'}
        ${isActive ? 'ring-1 ring-white/25' : ''}`}
      style={{
        pointerEvents: cellClickable ? undefined : 'none',
        opacity: isStanddown ? (visible ? 0.55 : 0.04) : undefined,
      }}
      onClick={cellClickable ? () => onToggle(date, regionName, targetType) : undefined}
    >
      <div className={`font-medium ${verdictTextColour}`} style={{ fontSize: '11px' }}>
        {verdictLabel}
      </div>

      {!isStanddown && region && (
        <>
          {region.glossHeadline ? (
            <div className="text-plex-text-secondary italic mt-0.5 flex items-center gap-0.5"
              style={{ fontSize: '10px' }}>
              <span>{region.glossHeadline}</span>
              {region.glossDetail && <InfoTip text={region.glossDetail} position="below" />}
            </div>
          ) : clearPct != null && (
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
                  {alignedCount} {alignedCount === 1 ? 'tide aligned' : 'tides aligned'}
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
        const timestamp = evaluationTimestamps.get(`${regionName}|${date}|${targetType}`);
        return (
          <div className="mt-0.5" data-testid="mean-score-badge">
            <span className={`rounded px-1 font-medium ${pillColour}`} style={{ fontSize: '10px' }}>
              {mean}★
            </span>
            {timestamp && (
              <span className="ml-0.5 text-plex-text-muted" style={{ fontSize: '9px' }}>
                {timestamp}
              </span>
            )}
          </div>
        );
      })()}

      {isStanddown && region?.summary && (
        <div className="text-red-300/60 mt-0.5" style={{ fontSize: '10px' }}>
          {region.summary.slice(0, 30)}
        </div>
      )}

      {/* Verdict distribution bar */}
      {(() => {
        const allSlots = region?.slots || [];
        const total = allSlots.length;
        if (total === 0) return null;
        const marginalCount = allSlots.filter((s) => s.verdict === 'MARGINAL').length;
        const standdownCount = allSlots.filter((s) => s.verdict === 'STANDDOWN').length;
        const standdownPct = (standdownCount / total) * 100;
        const marginalPct = (marginalCount / total) * 100;
        return (
          <div
            className="absolute bottom-0 left-0 right-0 rounded-b"
            style={{
              height: '4px',
              background: `linear-gradient(to right, var(--color-verdict-standdown) 0% ${standdownPct}%, var(--color-verdict-marginal) ${standdownPct}% ${standdownPct + marginalPct}%, var(--color-verdict-go) ${standdownPct + marginalPct}% 100%)`,
            }}
            data-testid="verdict-gradient"
          />
        );
      })()}
    </button>
  );
}

// ── HeatmapGrid (main export) ─────────────────────────────────────────────────

/**
 * Desktop heatmap grid — event columns with dynamic day-header spanning.
 * Solar events (SUNRISE/SUNSET) and astro (🌙) columns.
 */
/* eslint-enable react/prop-types */

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
  evaluationTimestamps = new Map(),
  onRunEvaluation,
  onStopEvaluation,
  canRunEvaluation = false,
  astroScoresByDate = {},
  activeModelName = null,
  showAllLocations = false,
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

  // Extract the first sunrise and sunset solarEventTime for each date
  const solarTimesPerDate = useMemo(() => {
    const map = new Map();
    for (const day of briefingDays || []) {
      let sunriseTime = null;
      let sunsetTime = null;
      for (const es of day.eventSummaries || []) {
        if (es.targetType === 'SUNRISE' && !sunriseTime) {
          outer: for (const r of es.regions || []) {
            for (const s of r.slots || []) {
              if (s.solarEventTime) { sunriseTime = s.solarEventTime; break outer; }
            }
          }
        }
        if (es.targetType === 'SUNSET' && !sunsetTime) {
          outer: for (const r of es.regions || []) {
            for (const s of r.slots || []) {
              if (s.solarEventTime) { sunsetTime = s.solarEventTime; break outer; }
            }
          }
        }
      }
      if (sunriseTime || sunsetTime) {
        map.set(day.date, { sunriseTime, sunsetTime });
      }
    }
    return map;
  }, [briefingDays]);

  if (sortedRegions.length === 0 || events.length === 0) return null;

  const numEventCols = events.length;
  const gridCols = `minmax(100px, 140px) repeat(${numEventCols}, minmax(0, 1fr))`;

  const toggleDrillDown = (date, regionName, targetType) => {
    setDrillDown((prev) => {
      const isClosing = prev?.date === date && prev?.regionName === regionName && prev?.targetType === targetType;
      if (isClosing && onStopEvaluation) onStopEvaluation();
      return isClosing ? null : { date, regionName, targetType };
    });
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
      {dayGroups.map(({ date, count }) => {
        const times = solarTimesPerDate.get(date);
        return (
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
            {(times?.sunriseTime || times?.sunsetTime) && (
              <div
                data-testid="heatmap-day-solar-times"
                className="text-plex-text-muted flex justify-center gap-2 flex-wrap mt-0.5"
                style={{ fontSize: '10px' }}
              >
                {times.sunriseTime && <span>🌅{formatTime(times.sunriseTime)}</span>}
                {times.sunsetTime && <span>🌇{formatTime(times.sunsetTime)}</span>}
              </div>
            )}
          </div>
        );
      })}

      {/* ── Sub-column header row: 🌅 / 🌇 ── */}
      <div /> {/* empty corner */}
      {events.map(({ date, targetType }) => (
        <div
          key={`${date}-${targetType}`}
          className="text-center text-plex-text-muted pb-0.5"
          style={{ fontSize: '13px' }}
          title={targetType === 'SUNRISE' ? 'Sunrise' : targetType === 'ASTRO' ? 'Astro conditions' : 'Sunset'}
        >
          {targetType === 'SUNRISE' ? '🌅' : targetType === 'ASTRO' ? '🌙' : '🌇'}
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

              // Astro cells use a different rendering path
              if (targetType === 'ASTRO') {
                const dateScores = astroScoresByDate[date] || {};
                const regionLocNames = getRegionLocationNames(date, regionName, briefingDays);
                const astroStars = regionLocNames
                  .map((n) => dateScores[n]?.stars)
                  .filter((s) => s != null);
                const bestStars = astroStars.length > 0 ? Math.max(...astroStars) : null;
                return (
                  <button
                    key={drillKey}
                    data-testid="astro-heatmap-cell"
                    className={`rounded border text-center p-1.5 transition-all ${
                      bestStars != null
                        ? `${ratingColour(bestStars)} cursor-pointer hover:scale-[1.01]`
                        : 'bg-plex-surface/30 border-plex-border/20 text-plex-text-muted cursor-default'
                    }`}
                    disabled={bestStars == null}
                    onClick={bestStars != null ? () => onShowOnMap?.(date, 'ASTRO') : undefined}
                    title={bestStars != null ? `Best astro: ${bestStars}★ — tap to view on map` : 'No dark-sky locations in this region'}
                  >
                    {bestStars != null ? (
                      <div className="font-bold" style={{ fontSize: '12px' }}>{bestStars}★</div>
                    ) : (
                      <div style={{ fontSize: '12px' }}>—</div>
                    )}
                  </button>
                );
              }

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
                  evaluationTimestamps={evaluationTimestamps}
                  showAllLocations={showAllLocations}
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
                onClose={() => { if (onStopEvaluation) onStopEvaluation(); setDrillDown(null); }}
                onShowOnMap={onShowOnMap}
                evaluationScores={evaluationScores}
                evaluationProgress={evaluationProgress}
                evaluationTimestamps={evaluationTimestamps}
                onRunEvaluation={onRunEvaluation}
                onStopEvaluation={onStopEvaluation}
                canRunEvaluation={canRunEvaluation}
                activeModelName={activeModelName}
                showAllLocations={showAllLocations}
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
    evaluatedAt: PropTypes.string,
  }),
  evaluationTimestamps: PropTypes.instanceOf(Map),
  onRunEvaluation: PropTypes.func,
  onStopEvaluation: PropTypes.func,
  canRunEvaluation: PropTypes.bool,
  astroScoresByDate: PropTypes.object,
  activeModelName: PropTypes.string,
  showAllLocations: PropTypes.bool,
};
