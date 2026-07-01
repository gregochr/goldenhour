import React, { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { computeCellTier, isCellVisible, resolveRegionDisplay } from '../utils/tierUtils.js';
import { formatEventTimeUk, formatTideHighlight } from '../utils/conversions.js';
import SlotLocationName from './shared/SlotLocationName.jsx';
import { RATING_COLOURS } from './markerUtils.js';

// ── Pure helpers (copied from DailyBriefing — shared logic) ─────────────────

const VERDICT_ORDER = { GO: 0, MARGINAL: 1, STANDDOWN: 2 };

function formatTime(isoString) {
  if (!isoString) return '';
  return formatEventTimeUk(isoString) ?? '';
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

/**
 * Colour pill for a display signal. Accepts either a {@code displayVerdict}
 * ({@code WORTH_IT} / {@code MAYBE} / {@code STAND_DOWN} / {@code AWAITING})
 * or falls back to the legacy {@code verdict} ({@code GO} / {@code MARGINAL}
 * / {@code STANDDOWN}) for call-sites that haven't been migrated.
 *
 * Optional {@code label} prop overrides the default text — used by the
 * Gate 2 honesty patch on zero-coverage STAND_DOWN regions.
 */
function VerdictPill({ displayVerdict, verdict, label }) {
  const signal = displayVerdict
    || (verdict === 'GO' ? 'WORTH_IT'
      : verdict === 'MARGINAL' ? 'MAYBE'
        : verdict === 'STANDDOWN' ? 'STAND_DOWN'
          : 'AWAITING');
  const colours = {
    WORTH_IT: 'bg-green-600 text-white',
    MAYBE: 'bg-amber-600 text-white',
    STAND_DOWN: 'bg-red-900/60 text-red-200/70',
    AWAITING: 'bg-plex-surface text-plex-text-secondary border border-plex-border',
  };
  const labels = {
    WORTH_IT: 'Worth it',
    MAYBE: 'Maybe',
    STAND_DOWN: 'Stand down',
    AWAITING: 'Awaiting',
  };
  return (
    <span
      data-testid="verdict-pill"
      className={`inline-block px-2 py-0.5 rounded text-[12px] font-bold ${colours[signal] || 'bg-plex-surface text-plex-text-secondary'}`}
    >
      {label || labels[signal] || signal}
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

// Decides whether a slot belongs in the "Poor" dimmed-rows section.
// Post Gate 2 redesign: Claude evaluates weather-STANDDOWN slots and may elevate them
// to 3-5★ — those slots should NOT be dimmed even if their triage verdict was STANDDOWN.
// The slot's `displayVerdict` already encodes this (Claude rating > triage when both present).
// For backwards compatibility with slots that pre-date the displayVerdict field, we fall
// back to the legacy verdict check.
function isPoorSlot(slot) {
  if (slot.displayVerdict) {
    return slot.displayVerdict === 'STAND_DOWN' || slot.displayVerdict === 'AWAITING';
  }
  return slot.verdict === 'STANDDOWN';
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
  const visible = sortedSlots((slots || []).filter((s) => !isPoorSlot(s)));
  const standdownSlots = showAllLocations
    ? sortedSlots((slots || []).filter(isPoorSlot))
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

function HeatmapCell({ date, regionName, targetType, briefingDays, qualityTier, isActive, onToggle, evaluationScores = new Map(), showAllLocations = false, travelDayDates = new Set() }) {  const cellData = getSubCellData(date, regionName, targetType, briefingDays);

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

  // Travel day — the operator is away, so no forecast was run for this date. Show a neutral
  // "Away" rather than a verdict like "Poor", which would falsely assert an evaluated judgement.
  if (travelDayDates.has(date)) {
    return (
      <div
        data-testid="heatmap-cell-away"
        title="You're away on this day — forecast not run"
        className="flex items-center justify-center rounded border"
        style={{
          minHeight: '52px',
          background: 'rgba(148,113,74,0.06)',
          borderColor: 'rgba(148,113,74,0.15)',
          cursor: 'default',
        }}
      >
        <span className="text-plex-text-muted" style={{ fontSize: '10px', opacity: 0.7 }}>
          ✈️ Away
        </span>
      </div>
    );
  }

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
  const cellBg = isGo
    ? { background: 'rgba(138,174,114,0.18)', borderColor: 'rgba(138,174,114,0.35)' }
    : { background: 'rgba(224,165,66,0.14)', borderColor: 'rgba(224,165,66,0.28)' };
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
    >
      <div className="font-medium" style={{ fontSize: '11px', color: verdictColour }}>
        {verdictLabel}
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

      {/* Hover tooltip — verdict + gloss (Newsreader italic) + weather */}
      {(glossSentence || weatherLine) && (
        <div className="heatmap-cell-tip" data-testid="cell-hover-tip">
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
        </div>
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
            {travelDayDates.has(date) && (
              <div
                data-testid="heatmap-travel-day-badge"
                title="You're away on this day — forecast not executed"
                className="mt-0.5 inline-block text-plex-text-muted border border-plex-border rounded-full px-1.5"
                style={{ fontSize: '10px' }}
              >
                ✈️ Away — no forecast
              </div>
            )}
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
                  travelDayDates={travelDayDates}
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
                isPro={isPro}
                showAllLocations={showAllLocations}
                onShowAllLocationsChange={onShowAllLocationsChange}
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
  isPro: PropTypes.bool,
  astroScoresByDate: PropTypes.object,
  showAllLocations: PropTypes.bool,
  onShowAllLocationsChange: PropTypes.func,
  travelDayDates: PropTypes.instanceOf(Set),
};
