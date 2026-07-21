import React, { useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import { computeCellTier, isCellVisible, resolveRegionDisplay } from '../utils/tierUtils.js';
import { formatTideHighlight } from '../utils/conversions.js';
import {
  LOCATION_TYPE_ICONS, isPoorSlot, slotSortKey, sortedSlotsByTidePriority, weatherCodeToIcon,
  msToMph, formatDriveDuration, formatTime, isEventPast,
} from '../utils/briefingDisplay.js';
import SlotLocationName from './shared/SlotLocationName.jsx';
import VerdictPill from './shared/VerdictPill.jsx';
import ProvisionalMark from './shared/ProvisionalMark.jsx';
import { RATING_COLOURS } from './markerUtils.js';
import { daysOut, resolveConfidence, confidenceTreatment, scaleRgbaAlpha } from '../utils/confidenceUtils.js';

// ── Pure helpers (copied from DailyBriefing — shared logic) ─────────────────

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

// Calendar-tile parts: short weekday ("Sat") and day-of-month number ("4").
function getCalDow(dateStr) {
  const d = new Date(dateStr + 'T12:00:00Z');
  return d.toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
}
function getCalDayNum(dateStr) {
  const d = new Date(dateStr + 'T12:00:00Z');
  return d.toLocaleDateString('en-GB', { day: 'numeric', timeZone: 'UTC' });
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

/** Small local components */
/* eslint-disable react/prop-types */

// ── LocationSlotList ──────────────────────────────────────────────────────────

/**
 * Star rating badge colour as an inline style, sourced from the unified RATING_COLOURS palette.
 * Returns a style object so every medallion in the app uses the same hex values.
 */
function ratingStyle(rating) {
  const clamped = Math.max(1, Math.min(5, Math.round(rating)));
  const bg = RATING_COLOURS[clamped];
  // 3★ is a pale yellow — dark text reads better on it; all other shades use white.
  const text = clamped === 3 ? '#1f1300' : '#ffffff';
  return { backgroundColor: bg, color: text };
}

/**
 * Rank-bucketed star-pill colours from the Kodachrome tidy-up: 4–5★ green,
 * 3★ amber, 1–2★ red. Returns an inline style object so the heatmap cell pill
 * and drill-down star pills read identically.
 */
function starPillStyle(rating) {
  if (rating >= 4) return { background: 'rgba(138,174,114,0.25)', color: '#b6d49e' };
  if (rating >= 3) return { background: 'rgba(224,165,66,0.22)', color: '#f0cd8a' };
  if (rating >= 2) return { background: 'rgba(200,69,47,0.22)', color: '#f0a08e' };
  return { background: 'rgba(200,69,47,0.30)', color: '#f0a08e' };
}

/**
 * Merges SSE evaluation scores with backend-cached Claude scores on the BriefingSlot.
 * SSE scores take precedence (they are more recent).
 */
function mergedScore(slot, sseScore) {
  if (sseScore?.rating != null) return sseScore;
  if (slot.claudeRating != null) {
    return {
      rating: slot.claudeRating,
      fierySkyPotential: slot.fierySkyPotential,
      goldenHourPotential: slot.goldenHourPotential,
      summary: slot.claudeSummary,
    };
  }
  return null;
}

/** Grid template shared by viable and poor drill-down rows: star · name · chips · drive. */
const ROW_GRID = '46px 1fr auto auto';

/** Tide-fact chip (teal) — or a muted variant for the not-evaluated poor section. */
function TideChip({ label, muted = false }) {
  return (
    <span
      className="rounded whitespace-nowrap"
      style={{
        fontSize: '10px',
        fontWeight: 500,
        padding: '1px 6px',
        ...(muted
          ? { background: 'rgba(242,231,211,0.04)', color: 'var(--color-plex-text-muted)', border: '1px solid rgba(242,231,211,0.06)' }
          : { background: 'rgba(111,168,176,0.16)', color: 'var(--color-tide)' }),
      }}
    >
      {label}
    </span>
  );
}

function LocationSlotList({ slots, driveMap, typeMap, scores = new Map(), evaluationComplete = false, showAllLocations = false, date = null, targetType = null, onShowOnMap = null }) {
  // Rows are collapsed by default — the reasoning sentence is one tap away.
  const [expandedRows, setExpandedRows] = useState(new Set());
  const visible = sortedSlotsByTidePriority((slots || []).filter((s) => !isPoorSlot(s)));
  const standdownSlots = showAllLocations
    ? sortedSlotsByTidePriority((slots || []).filter(isPoorSlot))
    : [];

  const hasHiddenStanddowns = !showAllLocations && (slots || []).some(isPoorSlot);

  if (visible.length === 0 && standdownSlots.length === 0) {
    if (hasHiddenStanddowns) {
      return (
        <div className="mt-1 px-2 text-plex-text-muted italic" style={{ fontSize: '12px' }}
          data-testid="standdown-hint">
          No viable locations for this event. Toggle &ldquo;Include poor locations&rdquo; to see why.
        </div>
      );
    }
    return null;
  }

  // Re-sort by Claude score (star descending) once scores exist; keep triage order
  // while streaming so rows don't jump around.
  const hasCachedScores = visible.some((s) => s.claudeRating != null);
  const sorted = ((evaluationComplete || hasCachedScores) && (scores.size > 0 || hasCachedScores))
    ? [...visible].sort((a, b) => {
      const sa = mergedScore(a, scores.get(a.locationName));
      const sb = mergedScore(b, scores.get(b.locationName));
      const ra = sa?.rating ?? 0;
      const rb = sb?.rating ?? 0;
      if (ra !== rb) return rb - ra; // higher score first
      const diff = slotSortKey(a) - slotSortKey(b);
      return diff !== 0 ? diff : a.locationName.localeCompare(b.locationName);
    })
    : visible;

  const toggleExpand = (name) => {
    setExpandedRows((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  return (
    <div className="mt-1" data-testid="region-slots">
      {sorted.map((slot) => {
        const drive = formatDriveDuration(driveMap.get(slot.locationName));
        const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
        const score = mergedScore(slot, scores.get(slot.locationName));
        const rating = score?.rating;
        const reasoning = score?.summary;
        const open = expandedRows.has(slot.locationName);
        return (
          <div key={slot.locationName} data-testid="briefing-slot" className="rounded">
            <div
              data-testid="drilldown-row-head"
              role={reasoning ? 'button' : undefined}
              tabIndex={reasoning ? 0 : undefined}
              className={`rounded ${reasoning ? 'cursor-pointer hover:bg-plex-surface-light/40' : ''}`}
              style={{ display: 'grid', gridTemplateColumns: ROW_GRID, gap: '12px', alignItems: 'center', padding: '9px 10px' }}
              onClick={reasoning ? () => toggleExpand(slot.locationName) : undefined}
              onKeyDown={reasoning ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleExpand(slot.locationName); } } : undefined}
            >
              {rating != null ? (
                <span
                  data-testid="score-badge"
                  className="inline-flex items-center justify-center rounded font-bold animate-fade-in"
                  style={{ fontSize: '11px', padding: '3px 0', ...starPillStyle(rating) }}
                >
                  {rating}★
                </span>
              ) : (
                <span className="text-center text-plex-text-muted" style={{ fontSize: '11px' }} aria-hidden="true">—</span>
              )}
              <span className="flex items-center" style={{ minWidth: 0 }}>
                {reasoning && (
                  <span
                    aria-hidden="true"
                    className="inline-block text-plex-text-muted transition-transform shrink-0"
                    style={{ fontSize: '10px', marginRight: '6px', transform: open ? 'rotate(90deg)' : 'rotate(0deg)' }}
                  >
                    ▶
                  </span>
                )}
                <SlotLocationName
                  name={slot.locationName}
                  typeIcon={typeIcon}
                  date={date}
                  targetType={targetType}
                  onShowOnMap={onShowOnMap}
                />
              </span>
              <span className="flex gap-1 justify-end">
                {(slot.flags || []).map((flag) => <TideChip key={flag} label={flag} />)}
              </span>
              {drive ? (
                <span
                  data-testid="slot-drive-time"
                  className="text-plex-text-muted whitespace-nowrap text-right"
                  style={{ fontSize: '11px', fontFamily: 'var(--font-mono)' }}
                >
                  🚗 {drive}
                </span>
              ) : <span />}
            </div>
            {open && reasoning && (
              <div
                data-testid="expanded-detail"
                className="text-plex-text-secondary italic animate-fade-in"
                style={{ fontSize: '12px', fontFamily: 'var(--font-serif)', lineHeight: 1.5, padding: '0 10px 10px 68px' }}
              >
                {reasoning}
                {(score.fierySkyPotential != null || score.goldenHourPotential != null) && (
                  <div className="flex gap-3 mt-1 text-plex-text-muted not-italic" style={{ fontSize: '11px', fontFamily: 'var(--font-mono)' }}>
                    {score.fierySkyPotential != null && (
                      <span data-testid="fiery-sky-score">Fiery Sky {score.fierySkyPotential}</span>
                    )}
                    {score.goldenHourPotential != null && (
                      <span data-testid="golden-hour-score">Golden Hour {score.goldenHourPotential}</span>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        );
      })}

      {standdownSlots.length > 0 && (
        <>
          <div
            data-testid="standdown-divider"
            className="flex items-center gap-2.5 text-plex-text-muted uppercase"
            style={{ fontSize: '10px', letterSpacing: '0.06em', fontFamily: 'var(--font-mono)', padding: '12px 10px 6px' }}
          >
            <span className="flex-1 h-px bg-plex-border" />
            poor · not evaluated · {standdownSlots.length} {standdownSlots.length === 1 ? 'location' : 'locations'}
            <span className="flex-1 h-px bg-plex-border" />
          </div>
          {standdownSlots.map((slot) => {
            const drive = formatDriveDuration(driveMap.get(slot.locationName));
            const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
            return (
              <div
                key={slot.locationName}
                data-testid="standdown-slot"
                className="text-plex-text-muted"
                style={{ display: 'grid', gridTemplateColumns: ROW_GRID, gap: '12px', alignItems: 'center', padding: '7px 10px' }}
              >
                <span
                  className="inline-flex items-center justify-center rounded font-bold uppercase"
                  style={{ fontSize: '10px', letterSpacing: '0.04em', padding: '3px 0', background: 'rgba(200,69,47,0.18)', color: 'rgba(240,160,142,0.75)' }}
                >
                  Poor
                </span>
                <span className="flex items-center text-plex-text-muted" style={{ fontSize: '13px', minWidth: 0 }}>
                  {typeIcon && <span className="mr-1">{typeIcon}</span>}
                  {slot.locationName}
                </span>
                <span className="flex gap-1 justify-end">
                  {(slot.flags || []).map((flag) => <TideChip key={flag} label={flag} muted />)}
                </span>
                {drive ? (
                  <span className="whitespace-nowrap text-right" style={{ fontSize: '11px', fontFamily: 'var(--font-mono)' }}>
                    🚗 {drive}
                  </span>
                ) : <span />}
              </div>
            );
          })}
        </>
      )}
    </div>
  );
}

// ── HeatmapDrillDown ──────────────────────────────────────────────────────────

function HeatmapDrillDown({ date, regionName, targetType, briefingDays, driveMap, typeMap, onClose, onShowOnMap, evaluationScores = new Map(), isPro = false, showAllLocations = false, onShowAllLocationsChange = null }) {
  const day = briefingDays.find((d) => d.date === date);

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

  return (
    <div
      data-testid="drill-down-panel"
      style={{ gridColumn: '1 / -1' }}
      className="px-3 py-2.5 rounded bg-plex-bg/50 border border-plex-border/30 mt-0.5"
    >
      <div className="flex items-center gap-3 mb-2">
        <span className="font-semibold text-plex-text" style={{ fontSize: '15px' }}>
          {regionName} — {getShortDate(date)}
          {targetType && (
            <span className="ml-1.5 text-plex-text-secondary" style={{ fontSize: '12px', fontFamily: 'var(--font-mono)' }}>
              {targetType === 'SUNRISE' ? '🌅 Sunrise' : '🌇 Sunset'}
            </span>
          )}
        </span>
        {onShowAllLocationsChange && (
          <button
            type="button"
            data-testid="drilldown-show-all-toggle"
            onClick={() => onShowAllLocationsChange(!showAllLocations)}
            className="ml-auto flex items-center gap-1.5 text-plex-text-secondary hover:text-plex-text transition-colors"
            style={{ fontSize: '11px' }}
            aria-pressed={showAllLocations}
          >
            <span>Include poor locations</span>
            <span className="quality-toggle-track" data-checked={showAllLocations ? 'true' : 'false'}>
              <span className="quality-toggle-thumb" />
            </span>
          </button>
        )}
        <button
          onClick={onClose}
          className={`text-plex-text-muted hover:text-plex-text px-1 text-sm ${onShowAllLocationsChange ? '' : 'ml-auto'}`}
          aria-label="Close drill-down"
        >
          ✕
        </button>
      </div>

      <div className="space-y-0.5">
        {events.map(({ es, region, past }) => {
          const regionDisplay = resolveRegionDisplay(region);
          const regionIsPoor = regionDisplay === 'STAND_DOWN' || regionDisplay === 'AWAITING';
          const tappable = !past && (!regionIsPoor || showAllLocations);
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
                <VerdictPill
                  displayVerdict={region.displayVerdict}
                  verdict={region.verdict}
                  label={region.verdictLabel}
                  confidence={region.confidence}
                />
                <span className="text-plex-text-secondary flex-1 truncate" style={{ fontSize: '12px' }}>
                  {region.summary}
                </span>
                {region.lightlyEvaluated && (
                  <span
                    data-testid="coverage-note"
                    className="shrink-0 text-plex-text-muted"
                    style={{ fontSize: '11px' }}
                  >
                    · {region.scoredLocationCount} of {(region.slots || []).length} evaluated
                  </span>
                )}
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
              </div>

              {region.glossDetail && (
                <div className="text-plex-text-secondary italic px-2 py-1" style={{ fontSize: '13px', fontFamily: 'var(--font-serif)', lineHeight: 1.5 }}>
                  {region.glossDetail}
                </div>
              )}
              {tappable && (
                <LocationSlotList
                  slots={region.slots}
                  driveMap={driveMap}
                  typeMap={typeMap}
                  scores={slotScores}
                  evaluationComplete={slotScores.size > 0 || (region.slots || []).some((s) => s.claudeRating != null)}
                  isPro={isPro}
                  showAllLocations={showAllLocations}
                  date={date}
                  targetType={es.targetType}
                  onShowOnMap={onShowOnMap}
                />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ── Sub-column cell ───────────────────────────────────────────────────────────

const CELL_TIP_WIDTH = 220;
const CELL_TIP_MARGIN = 8;
const CELL_TIP_GAP = 8;

/**
 * Compute a viewport-anchored (`position: fixed`) style for a heatmap cell's hover tooltip so it
 * escapes the daily-briefing card's `overflow: hidden` (which clips the rightmost column's tip no
 * matter how it's anchored inside the grid). The tip sits above the cell, anchored to the cell's
 * left edge unless that would overrun the viewport's right edge, in which case it flips to a right
 * anchor. Mirrors the summary-strip region tooltip (see BriefingSummaryStrip).
 *
 * @param {DOMRect} rect the cell's bounding box
 * @returns {{style: Object, alignRight: boolean}} inline style + caret-side flag
 */
function computeCellTipPlacement(rect) {
  const alignRight = rect.left + CELL_TIP_WIDTH + CELL_TIP_MARGIN > window.innerWidth;
  const style = { bottom: `${window.innerHeight - rect.top + CELL_TIP_GAP}px` };
  if (alignRight) {
    style.right = `${Math.max(CELL_TIP_MARGIN, window.innerWidth - rect.right)}px`;
  } else {
    style.left = `${Math.max(CELL_TIP_MARGIN, rect.left)}px`;
  }
  return { style, alignRight };
}

function HeatmapCell({ date, regionName, targetType, briefingDays, qualityTier, isActive, onToggle, evaluationScores = new Map(), showAllLocations = false, todayStr = null }) {  const cellData = getSubCellData(date, regionName, targetType, briefingDays);

  // Hover tooltip placement, portalled to <body> so the plan card's overflow:hidden can't clip
  // the rightmost column's tip. Declared before any early return to satisfy the rules of hooks.
  const [tip, setTip] = useState(null);
  const showTip = (e) => setTip(computeCellTipPlacement(e.currentTarget.getBoundingClientRect()));
  const hideTip = () => setTip(null);

  // Empty cell — region doesn't appear in this event type. Minimal: a muted
  // dash, no hover, no interaction; sized to align with the tidy ~52px band.
  if (!cellData) {
    return (
      <div
        className="flex items-center justify-center rounded text-plex-text-muted italic"
        style={{ minHeight: '52px', fontSize: '12px', opacity: 0.3, cursor: 'default' }}
      >
        —
      </div>
    );
  }

  const { region, past } = cellData;

  const cellTier = computeCellTier(region);
  const visible = isCellVisible(cellTier, qualityTier);

  const displaySignal = resolveRegionDisplay(region);
  const isStanddown = displaySignal === 'STAND_DOWN' || displaySignal === 'AWAITING';

  // Extract the best tide label from tideHighlights (e.g. "King Tide at 3 coastal spots" → "3 king tides")
  const tideHighlight = (region.tideHighlights || []).find((h) =>
    h.toLowerCase().includes('king') || h.toLowerCase().includes('spring') || h.toLowerCase().includes('extra'));
  const tideLabel = tideHighlight ? formatTideHighlight(tideHighlight) : null;
  const alignedCount = (region.slots || []).filter((s) => s.tideAligned).length;

  const eventLabel = targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
  const verdictLabel = displaySignal === 'STAND_DOWN' ? 'Poor'
    : displaySignal === 'AWAITING' ? 'Awaiting'
      : displaySignal === 'WORTH_IT' ? `Worth it ${eventLabel}`
        : `Maybe ${eventLabel}`;

  const standdownClickable = isStanddown && showAllLocations;
  const cellDisabled = (isStanddown && !showAllLocations) || !visible || past;
  const cellClickable = !cellDisabled;

  // ── Poor cell — collapses to a single quiet word, vertically centred ──
  // No gloss, no weather line, no distribution bar: ~30 of 42 cells stop shouting.
  if (isStanddown) {
    return (
      <div
        data-testid="heatmap-cell"
        role="button"
        tabIndex={cellDisabled ? -1 : 0}
        aria-disabled={cellDisabled || undefined}
        className={`flex items-center justify-center rounded border transition-all
          ${standdownClickable ? 'cursor-pointer hover:scale-[1.01]' : 'cursor-default'}
          ${isActive ? 'ring-1 ring-plex-text/25' : ''}`}
        style={{
          minHeight: '52px',
          background: 'rgba(200,69,47,0.06)',
          borderColor: 'rgba(200,69,47,0.12)',
          pointerEvents: cellClickable ? undefined : 'none',
        }}
        onClick={cellClickable ? () => onToggle(date, regionName, targetType) : undefined}
        onKeyDown={cellClickable ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onToggle(date, regionName, targetType); } } : undefined}
      >
        <span className="text-plex-text-muted" style={{ fontSize: '10px', opacity: 0.7 }}>
          Poor
        </span>
      </div>
    );
  }

  // ── Good / Maybe cell — quiet by default; the gloss sentence moves to hover ──
  const isGo = displaySignal === 'WORTH_IT';
  const baseCellBg = isGo
    ? { background: 'rgba(138,174,114,0.18)', borderColor: 'rgba(138,174,114,0.35)' }
    : { background: 'rgba(224,165,66,0.14)', borderColor: 'rgba(224,165,66,0.28)' };
  // Confidence channel: a far-horizon / wide-spread verdict reads visibly more provisional —
  // dimmed fill + a small marker — WITHOUT touching the star (quality) signal. Fail-soft: a
  // missing backend confidence falls back to a horizon-only tier via resolveConfidence.
  const confidenceTier = resolveConfidence(region, daysOut(date, todayStr));
  const treatment = confidenceTreatment(confidenceTier);
  const cellBg = {
    background: scaleRgbaAlpha(baseCellBg.background, treatment.fillScale),
    borderColor: scaleRgbaAlpha(baseCellBg.borderColor, treatment.fillScale),
  };
  const verdictColour = isGo ? 'var(--color-verdict-go)' : 'var(--color-verdict-marginal)';

  const weatherLine = region.regionTemperatureCelsius != null
    ? `${weatherCodeToIcon(region.regionWeatherCode)}${Math.round(region.regionTemperatureCelsius)}°C${region.regionWindSpeedMs != null ? ` ${msToMph(region.regionWindSpeedMs)}mph` : ''}`
    : null;

  // Gloss sentence shown only on hover (one interaction away).
  const glossSentence = region.glossDetail || region.glossHeadline || region.summary || null;

  // Mean Claude rating across the cell's scored locations.
  const ratings = [];
  const prefix = `${regionName}|${date}|${targetType}|`;
  for (const [key, result] of evaluationScores) {
    if (key.startsWith(prefix) && result.rating != null) ratings.push(result.rating);
  }
  if (ratings.length === 0 && region?.slots) {
    for (const s of region.slots) {
      if (s.claudeRating != null) ratings.push(s.claudeRating);
    }
  }
  const meanRating = ratings.length > 0
    ? parseFloat((ratings.reduce((a, b) => a + b, 0) / ratings.length).toFixed(1))
    : null;

  return (
    <div
      data-testid="heatmap-cell"
      role="button"
      tabIndex={cellDisabled ? -1 : 0}
      aria-disabled={cellDisabled || undefined}
      className={`heatmap-cell-hoverable relative flex flex-col gap-0.5 rounded border text-left px-2 py-1.5 transition-transform
        ${cellClickable ? 'cursor-pointer hover:scale-[1.015]' : 'cursor-default'}
        ${isActive ? 'ring-1 ring-plex-text/25' : ''}`}
      style={{ minHeight: '52px', ...cellBg, pointerEvents: cellClickable ? undefined : 'none' }}
      onClick={cellClickable ? () => onToggle(date, regionName, targetType) : undefined}
      onKeyDown={cellClickable ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onToggle(date, regionName, targetType); } } : undefined}
      onMouseEnter={showTip}
      onMouseLeave={hideTip}
      onFocus={showTip}
      onBlur={hideTip}
    >
      <div className="font-medium flex items-center gap-1" style={{ fontSize: '11px', color: verdictColour }}>
        <span>{verdictLabel}</span>
        {treatment.provisional && <ProvisionalMark />}
      </div>

      {weatherLine && (
        <div className="text-plex-text-secondary" style={{ fontSize: '10px', fontFamily: 'var(--font-mono)' }}>
          {weatherLine}
        </div>
      )}

      {(tideLabel || alignedCount > 0) && (
        <div className="flex flex-wrap gap-0.5">
          <span
            className="rounded px-1 font-medium"
            style={{ fontSize: '9px', background: 'rgba(111,168,176,0.18)', color: 'var(--color-tide)' }}
          >
            {tideLabel || `${alignedCount} ${alignedCount === 1 ? 'tide aligned' : 'tides aligned'}`}
          </span>
        </div>
      )}

      {meanRating != null && (
        <div data-testid="mean-score-badge">
          <span className="inline-block rounded px-1.5 font-semibold" style={{ fontSize: '10px', ...starPillStyle(meanRating) }}>
            {meanRating}★
          </span>
        </div>
      )}

      {/* Hover tooltip — verdict + gloss (Newsreader italic) + weather. Portalled to <body> and
          positioned via JS (position: fixed) so the plan card's overflow:hidden can't clip the
          rightmost column's tip; it flips left↔right at the viewport edge. */}
      {tip && (glossSentence || weatherLine) && createPortal(
        <div
          className={`heatmap-cell-tip${tip.alignRight ? ' heatmap-cell-tip--right' : ''}`}
          role="tooltip"
          data-testid="cell-hover-tip"
          style={tip.style}
        >
          <div className="font-semibold" style={{ fontSize: '11px', color: verdictColour, marginBottom: '4px' }}>
            {verdictLabel}
          </div>
          {glossSentence && (
            <div
              className="text-plex-text-secondary italic"
              style={{ fontSize: '11px', fontFamily: 'var(--font-serif)', lineHeight: 1.4, marginBottom: '6px' }}
            >
              {glossSentence}
            </div>
          )}
          {weatherLine && (
            <div className="text-plex-text-muted" style={{ fontSize: '10px', fontFamily: 'var(--font-mono)' }}>
              {weatherLine}
            </div>
          )}
        </div>,
        document.body,
      )}
    </div>
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
  isPro = false,
  astroScoresByDate = {},
  showAllLocations = false,
  onShowAllLocationsChange = null,
  travelDayDates = new Set(),
}) {
  const [drillDown, setDrillDown] = useState(null); // { date, regionName, targetType }
  const [showPoorRegions, setShowPoorRegions] = useState(false); // A3a: reveal the pooled poor-only rows
  const [prevPoolPoor, setPrevPoolPoor] = useState(false); // tracks poolPoor to reset the reveal (F4)

  // A3b: away days (travel days with no forecast) are dropped from the grid's columns and shown as
  // a slim band below it, so the reclaimed width goes to the real forecast days (the cramped ones).
  const gridEvents = useMemo(
    () => events.filter((ev) => !travelDayDates.has(ev.date)),
    [events, travelDayDates],
  );

  // Compute day groups from the forecast (non-away) events for dynamic header spanning
  const dayGroups = useMemo(() => {
    const groups = [];
    let current = null;
    for (const ev of gridEvents) {
      if (!current || current.date !== ev.date) {
        current = { date: ev.date, count: 1 };
        groups.push(current);
      } else {
        current.count++;
      }
    }
    return groups;
  }, [gridEvents]);

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

  const numEventCols = gridEvents.length;
  const gridCols = `minmax(100px, 140px) repeat(${numEventCols}, minmax(0, 1fr))`;

  // Group the in-view away dates into consecutive runs, one band per run (e.g. Mon–Tue).
  const awayDatesInView = [...new Set(events.map((ev) => ev.date).filter((d) => travelDayDates.has(d)))].sort();
  const awayRuns = [];
  for (const date of awayDatesInView) {
    const run = awayRuns[awayRuns.length - 1];
    const prev = run?.[run.length - 1];
    const consecutive = prev
      && Date.parse(`${date}T00:00:00Z`) - Date.parse(`${prev}T00:00:00Z`) === 86400000;
    if (consecutive) run.push(date);
    else awayRuns.push([date]);
  }
  const awayRunLabel = (run) => {
    const start = getDayLabel(run[0], todayStr, tomorrowStr);
    return run.length === 1 ? start : `${start}–${getDayLabel(run[run.length - 1], todayStr, tomorrowStr)}`;
  };

  const toggleDrillDown = (date, regionName, targetType) => {
    setDrillDown((prev) => {
      const isClosing = prev?.date === date && prev?.regionName === regionName && prev?.targetType === targetType;
      return isClosing ? null : { date, regionName, targetType };
    });
  };

  // Sort region rows by best visible tier at the current slider position.
  const reorderedRegions = [...sortedRegions].sort((a, b) => {
    let bestA = 6;
    let bestB = 6;
    for (const { date, targetType } of gridEvents) {
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
    for (const { date, targetType } of gridEvents) {
      const cd = getSubCellData(date, regionName, targetType, briefingDays);
      if (!cd || cd.past) continue;
      const t = computeCellTier(cd.region);
      if (isCellVisible(t, qualityTier)) return false;
    }
    return true;
  }

  // ── A3a: pool poor-only region rows behind a reveal ──────────────────────────
  // A region is "all poor" when none of its evaluated cells is a GO/MARGINAL (Worth it / Maybe)
  // — every cell is STAND_DOWN or AWAITING. The quality slider was retired (qualityTier is pinned
  // to SHOW_ALL_TIER), so isRegionFullyHidden — which keys on isCellVisible — never fires; we key
  // on the display verdict directly instead. reorderedRegions is sorted best-first, so poor rows
  // already trail. Split them off and tuck them behind a full-width toggle so a rated-heavy grid
  // isn't mostly dead cells the eye must scan past.
  function isRegionAllPoor(regionName) {
    for (const { date, targetType } of gridEvents) {
      const cd = getSubCellData(date, regionName, targetType, briefingDays);
      if (!cd || cd.past || !cd.region) continue;
      const dv = resolveRegionDisplay(cd.region);
      if (dv === 'WORTH_IT' || dv === 'MAYBE') return false;
    }
    return true;
  }
  const poorRegions = reorderedRegions.filter(isRegionAllPoor);
  const ratedRegions = reorderedRegions.filter((r) => !isRegionAllPoor(r));
  // Only pool when there's a genuine mix: an all-poor week has nothing rated to lead with, so
  // show every row (faded, as before) rather than an empty grid sitting behind a toggle.
  const poolPoor = ratedRegions.length > 0 && poorRegions.length > 0;
  // Reset the reveal whenever pooling stops being active, so a stale "open" flag can't
  // auto-expand the pool after a briefing refresh reshapes the region set (F4). This is React's
  // supported adjust-state-during-render pattern — it runs before paint and can't loop (once
  // prevPoolPoor tracks poolPoor the branch is skipped). Cheaper than a post-render effect, and
  // benign polls that keep poolPoor true never touch the user's chosen open state.
  if (poolPoor !== prevPoolPoor) {
    setPrevPoolPoor(poolPoor);
    if (!poolPoor) setShowPoorRegions(false);
  }
  const leadRows = poolPoor ? ratedRegions : reorderedRegions;

  // Render one region row (label + event cells + drill-down). `revealed` forces a pooled poor
  // row to full opacity once the user has deliberately opened the poor section.
  const renderRegionRow = (regionName, revealed = false) => {
    const faded = isRegionFullyHidden(regionName) && !revealed;
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
            opacity: faded ? 0.06 : 1,
          }}
          aria-hidden={faded}
        >
          {regionName}
        </div>

        {/* Event cells (away days are excluded from the columns — shown as a band below) */}
        {gridEvents.map(({ date, targetType }) => {
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
                    ? 'cursor-pointer hover:scale-[1.01]'
                    : 'bg-plex-surface/30 border-plex-border/20 text-plex-text-muted cursor-default'
                }`}
                style={bestStars != null ? ratingStyle(bestStars) : undefined}
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
              showAllLocations={showAllLocations}
              todayStr={todayStr}
            />
          );
        })}

        {/* Drill-down panel — spans full grid width. Gated on the drill-down's date still being a
            forecast column: if a briefing refresh reclassifies that date as away (A3b drops it from
            gridEvents into the band), the panel must not linger orphaned below a column that's gone. */}
        {drillDown?.regionName === regionName && drillDown?.date
          && gridEvents.some((ev) => ev.date === drillDown.date) && (
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
            isPro={isPro}
            showAllLocations={showAllLocations}
            onShowAllLocationsChange={onShowAllLocationsChange}
          />
        )}
      </React.Fragment>
    );
  };

  return (
    <>
      {gridEvents.length > 0 && (
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
        const isToday = date === todayStr;
        return (
          <div
            key={date}
            data-testid="heatmap-day-header"
            className="flex flex-col items-center py-1 px-1"
            style={{ gridColumn: `span ${count}` }}
          >
            {/* Calendar chip: weekday-over-number tile + relative day + solar times */}
            <div
              className="inline-flex items-center gap-2 rounded-lg"
              style={{
                padding: '5px 10px 5px 5px',
                border: isToday
                  ? '1px solid color-mix(in srgb, var(--color-plex-gold) 50%, transparent)'
                  : '1px solid var(--color-plex-border)',
                background: isToday
                  ? 'color-mix(in srgb, var(--color-plex-gold) 8%, transparent)'
                  : 'var(--color-plex-surface)',
              }}
            >
              <div
                className="flex flex-col items-center rounded-md"
                style={{
                  lineHeight: 1,
                  padding: '4px 8px',
                  background: isToday
                    ? 'color-mix(in srgb, var(--color-plex-gold) 16%, transparent)'
                    : 'rgba(255,255,255,0.05)',
                }}
              >
                <span
                  className="font-mono font-semibold uppercase"
                  style={{ fontSize: '8px', letterSpacing: '0.09em', color: isToday ? 'var(--color-plex-gold)' : 'var(--color-plex-text-muted)' }}
                >
                  {getCalDow(date)}
                </span>
                <span
                  className="font-mono font-semibold"
                  style={{ fontSize: '18px', marginTop: '1px', color: isToday ? 'var(--color-plex-gold)' : 'var(--color-plex-text)' }}
                >
                  {getCalDayNum(date)}
                </span>
              </div>
              <div className="flex flex-col items-start" style={{ gap: '2px' }}>
                <span
                  className="font-semibold"
                  style={{ fontSize: '11px', color: isToday ? 'var(--color-plex-gold)' : 'var(--color-plex-text)' }}
                >
                  {getDayLabel(date, todayStr, tomorrowStr)}
                </span>
                {(times?.sunriseTime || times?.sunsetTime) && (
                  <span
                    data-testid="heatmap-day-solar-times"
                    className="flex text-plex-text-muted"
                    style={{ fontSize: '10px', gap: '8px', fontVariantNumeric: 'tabular-nums' }}
                  >
                    {times.sunriseTime && (
                      <span>
                        <span className="font-mono" style={{ color: 'var(--color-plex-text-muted)', marginRight: '1px' }} aria-hidden="true">{'↑'}</span>
                        {formatTime(times.sunriseTime)}
                      </span>
                    )}
                    {times.sunsetTime && (
                      <span>
                        <span className="font-mono" style={{ color: 'var(--color-plex-text-muted)', marginRight: '1px' }} aria-hidden="true">{'↓'}</span>
                        {formatTime(times.sunsetTime)}
                      </span>
                    )}
                  </span>
                )}
              </div>
            </div>
          </div>
        );
      })}

      {/* ── Sub-column header row: 🌅 / 🌇 ── */}
      <div /> {/* empty corner */}
      {gridEvents.map(({ date, targetType }) => (
        <div
          key={`${date}-${targetType}`}
          className="text-center text-plex-text-muted pb-0.5"
          style={{ fontSize: '13px' }}
          title={targetType === 'SUNRISE' ? 'Sunrise' : targetType === 'ASTRO' ? 'Astro conditions' : 'Sunset'}
        >
          {targetType === 'SUNRISE' ? '🌅' : targetType === 'ASTRO' ? '🌙' : '🌇'}
        </div>
      ))}

      {/* ── Region rows (rated lead; poor rows pooled behind a reveal) ── */}
      {leadRows.map((regionName) => renderRegionRow(regionName))}

      {/* A3a: full-width toggle revealing the poor-only region rows */}
      {poolPoor && (
        <button
          type="button"
          data-testid="heatmap-poor-toggle"
          aria-expanded={showPoorRegions}
          onClick={() => setShowPoorRegions((v) => !v)}
          // Secondary (0.66a), not muted (0.42a): this is the sole affordance to reveal the pooled
          // rows, so its label must clear comfortable contrast at rest — hover never fires for
          // keyboard/touch users. Matches the C3 fine-print bump.
          className="font-mono text-plex-text-secondary hover:text-plex-text border border-plex-border hover:border-plex-border-light rounded transition-colors"
          style={{ gridColumn: '1 / -1', fontSize: '11px', padding: '6px 10px', marginTop: '2px', justifySelf: 'stretch' }}
        >
          {showPoorRegions
            ? 'Hide poor regions ▴'
            : `+${poorRegions.length} region${poorRegions.length !== 1 ? 's' : ''} · all poor ▾`}
        </button>
      )}
      {poolPoor && showPoorRegions && poorRegions.map((regionName) => renderRegionRow(regionName, true))}
      </div>
      )}

      {/* ── A3b: away days collapsed into slim consecutive-range band(s) below the grid ── */}
      {awayRuns.length > 0 && (
        <div data-testid="heatmap-away-bands" className="hidden sm:flex sm:flex-col gap-1 mt-1">
          {awayRuns.map((run) => (
            <div
              key={run[0]}
              data-testid="heatmap-away-band"
              className="font-mono"
              style={{
                fontSize: '11px',
                color: 'var(--color-tide)',
                border: '1px dashed var(--color-plex-border-light)',
                background: 'rgba(111,168,176,0.06)',
                borderRadius: '6px',
                padding: '4px 10px',
              }}
            >
              ✈ {awayRunLabel(run)} · away — no forecast generated
            </div>
          ))}
        </div>
      )}
    </>
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
  isPro: PropTypes.bool,
  astroScoresByDate: PropTypes.object,
  showAllLocations: PropTypes.bool,
  onShowAllLocationsChange: PropTypes.func,
  travelDayDates: PropTypes.instanceOf(Set),
};
