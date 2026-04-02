import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getDailyBriefing } from '../api/briefingApi.js';
import { subscribeToBriefingEvaluation } from '../api/briefingEvaluationApi.js';
import { getAstroConditions, getAstroAvailableDates } from '../api/astroApi.js';
import { getDriveTimes } from '../api/settingsApi.js';
import { getAvailableModels } from '../api/modelsApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import HeatmapGrid from './HeatmapGrid.jsx';
import QualitySlider from './QualitySlider.jsx';
import useLocalStorageState from '../hooks/useLocalStorageState.js';
import { computeCellTier, computeAuroraCellTier, isCellVisible } from '../utils/tierUtils.js';
import { formatEventTimeUk } from '../utils/conversions.js';

const POLL_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes

// ── Small shared components ─────────────────────────────────────────────────
/* eslint-disable react/prop-types */

/** Colour pill for a verdict (GO / MARGINAL / STANDDOWN). */
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

/** Inline chip for a flag string. */
function FlagChip({ label }) {  return (
    <span className="inline-block px-1.5 py-0.5 rounded bg-plex-surface border border-plex-border text-[11px] text-plex-text-secondary font-medium">
      {label}
    </span>
  );
}

/** Rotating chevron icon — right at rest, down when open. */
function Chevron({ open, className = '' }) {  return (
    <span
      aria-hidden="true"
      className={`inline-block transition-transform duration-200 leading-none select-none ${open ? 'rotate-90' : 'rotate-0'} ${className}`}
    >
      ▶
    </span>
  );
}

// ── Pure helpers ─────────────────────────────────────────────────────────────

function formatTime(isoString) {
  if (!isoString) return '';
  return formatEventTimeUk(isoString) ?? '';
}

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

const AFTERGLOW_MS = 30 * 60 * 1000;

function isEventPast(es) {
  const t = getEventTime(es);
  if (!t) return false;
  return new Date(t + 'Z').getTime() + AFTERGLOW_MS < Date.now();
}

function getVerdictCounts(es) {
  const counts = { GO: 0, MARGINAL: 0, STANDDOWN: 0 };
  (es.regions || []).forEach((r) => { counts[r.verdict] = (counts[r.verdict] || 0) + 1; });
  return counts;
}

function hasTideAligned(es) {
  return (es.regions || []).some((r) => (r.slots || []).some((s) => s.tideAligned));
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

function resolveEventKey(event, todayStr, tomorrowStr) {
  if (!event) return null;
  // Date-based aurora events: "2026-04-01_aurora" → "2026-04-01-AURORA"
  if (event.endsWith('_aurora')) {
    const dateStr = event.slice(0, -7); // strip "_aurora"
    return `${dateStr}-AURORA`;
  }
  const underscore = event.indexOf('_');
  if (underscore === -1) return null;
  const dayPart = event.substring(0, underscore);
  const typePart = event.substring(underscore + 1).toUpperCase();
  const dateStr = dayPart === 'today' ? todayStr : dayPart === 'tomorrow' ? tomorrowStr : null;
  if (!dateStr || !['SUNRISE', 'SUNSET'].includes(typePart)) return null;
  return `${dateStr}-${typePart}`;
}

/** Sort order for verdict: GO first, MARGINAL second, STANDDOWN last. */
const VERDICT_ORDER = { GO: 0, MARGINAL: 1, STANDDOWN: 2 };

function sortedSlots(slots) {
  return [...slots].sort((a, b) => {
    const vd = (VERDICT_ORDER[a.verdict] ?? 3) - (VERDICT_ORDER[b.verdict] ?? 3);
    return vd !== 0 ? vd : a.locationName.localeCompare(b.locationName);
  });
}

function formatDriveDuration(minutes) {
  if (minutes == null) return null;
  if (minutes < 60) return `${minutes} min`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m > 0 ? `${h}h ${m}min` : `${h}h`;
}

/** Location type icon lookup. */
const LOCATION_TYPE_ICONS = {
  LANDSCAPE: '🏔️',
  WILDLIFE: '🐾',
  SEASCAPE: '🌊',
  WATERFALL: '💧',
};

// ── Grid / day helpers ────────────────────────────────────────────────────────

const MAX_VISIBLE_EVENTS = 6;

/**
 * Returns up to 6 upcoming (non-past) solar event objects [{date, targetType}],
 * ordered by date then event type. Replaces the old day-based column approach.
 */
function selectUpcomingEvents(briefingDays) {
  const events = [];
  for (const day of briefingDays) {
    for (const es of day.eventSummaries || []) {
      if (!isEventPast(es)) {
        events.push({ date: day.date, targetType: es.targetType });
        if (events.length === MAX_VISIBLE_EVENTS) return events;
      }
    }
  }
  return events;
}

/**
 * Returns "Today", "Tomorrow", or the full day name (e.g. "Saturday").
 */
function getDayLabel(dateStr, todayStr, tomorrowStr) {
  if (dateStr === todayStr) return 'Today';
  if (dateStr === tomorrowStr) return 'Tomorrow';
  const d = new Date(dateStr + 'T12:00:00Z');
  return d.toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/**
 * Returns "Sat 22 Mar" style short date.
 */
function getShortDate(dateStr) {
  const d = new Date(dateStr + 'T12:00:00Z');
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
}

/**
 * For a given day and region, returns the best event across all upcoming event
 * summaries, plus a list of all events for drill-down.
 * Returns null if the region doesn't appear on this day.
 */
function getDayCellData(date, regionName, briefingDays) {
  const day = briefingDays.find((d) => d.date === date);
  if (!day) return null;

  const allEvents = [];
  let bestVerdict = null;
  let bestEs = null;
  let bestRegion = null;

  for (const es of day.eventSummaries || []) {
    const region = (es.regions || []).find((r) => r.regionName === regionName);
    if (!region) continue;
    allEvents.push({ es, region, past: isEventPast(es) });
    if (isEventPast(es)) continue;
    const vOrder = VERDICT_ORDER[region.verdict] ?? 3;
    const bestOrder = bestVerdict != null ? (VERDICT_ORDER[bestVerdict] ?? 3) : 4;
    if (vOrder < bestOrder) {
      bestVerdict = region.verdict;
      bestEs = es;
      bestRegion = region;
    }
  }

  if (allEvents.length === 0 || !bestVerdict) return null;
  return { bestVerdict, bestEs, bestRegion, allEvents };
}

/**
 * Returns all unique region names that appear across the given upcoming events,
 * sorted by best verdict (GO first, then MARGINAL, then STANDDOWN), then A-Z.
 */
function getSortedRegions(upcomingEvents, briefingDays) {
  const regionBest = new Map(); // regionName → best VERDICT_ORDER value
  const regionSeen = [];

  for (const { date, targetType } of upcomingEvents) {
    const day = briefingDays.find((d) => d.date === date);
    if (!day) continue;
    const es = (day.eventSummaries || []).find((e) => e.targetType === targetType);
    if (!es) continue;
    for (const region of es.regions || []) {
      const name = region.regionName;
      const v = VERDICT_ORDER[region.verdict] ?? 3;
      if (!regionBest.has(name)) {
        regionBest.set(name, v);
        regionSeen.push(name);
      } else if (v < regionBest.get(name)) {
        regionBest.set(name, v);
      }
    }
  }

  return regionSeen.sort((a, b) => {
    const diff = (regionBest.get(a) ?? 3) - (regionBest.get(b) ?? 3);
    return diff !== 0 ? diff : a.localeCompare(b);
  });
}

// ── EventSummaryRow (mobile collapsed: compact per-event row) ──────────────

/* eslint-disable react/prop-types */
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
      <span className="w-36 shrink-0 font-medium text-plex-text" style={{ fontSize: '13px' }}>
        {emoji} {dayLabel} {eventLabel}
      </span>
      <span className="flex gap-2 flex-wrap flex-1">
        {counts.GO > 0 && (
          <span className={countColours.GO} data-testid="go-count" style={{ fontSize: '12px' }}>
            {counts.GO} GO
          </span>
        )}
        {counts.MARGINAL > 0 && (
          <span className={countColours.MARGINAL} data-testid="marginal-count" style={{ fontSize: '12px' }}>
            {counts.MARGINAL} MARGINAL
          </span>
        )}
        {counts.STANDDOWN > 0 && (
          <span className={countColours.STANDDOWN} data-testid="standdown-count" style={{ fontSize: '12px' }}>
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

// ── Event pips (tiny coloured labels inside a day cell) ───────────────────

function EventPips({ allEvents, auroraActive }) { // eslint-disable-line no-unused-vars
  const upcoming = allEvents.filter((e) => !e.past && e.region.verdict !== 'STANDDOWN');
  if (upcoming.length === 0 && !auroraActive) return null;
  return (
    <div className="flex flex-wrap gap-0.5 mt-1">
      {upcoming.map(({ es, region }) => {
        const emoji = es.targetType === 'SUNRISE' ? '🌅' : '🌇';
        const label = region.verdict === 'GO' ? 'GO' : 'Marginal';
        const c = region.verdict === 'GO'
          ? 'bg-green-500/30 text-green-300'
          : 'bg-amber-500/30 text-amber-300';
        return (
          <span key={es.targetType} data-testid="event-pip"
            className={`rounded px-1 font-medium ${c}`}
            style={{ fontSize: '10px' }}>
            {emoji} {label}
          </span>
        );
      })}
      {auroraActive && (
        <span data-testid="event-pip"
          className="rounded px-1 font-medium bg-indigo-500/30 text-indigo-300"
          style={{ fontSize: '10px' }}>
          🌌 Aurora
        </span>
      )}
    </div>
  );
}

// ── LocationSlotList (shared between mobile drill-down and heatmap drill-down) ──

function LocationSlotList({ slots, driveMap, typeMap }) {  const visible = sortedSlots((slots || []).filter((s) => s.verdict !== 'STANDDOWN'));
  if (visible.length === 0) return null;
  return (
    <div className="ml-4 mt-0.5 space-y-1 mb-1" data-testid="region-slots">
      {visible.map((slot) => {
        const drive = formatDriveDuration(driveMap.get(slot.locationName));
        const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
        return (
          <div
            key={slot.locationName}
            className="flex flex-wrap items-center gap-1.5 px-2 py-1 rounded bg-plex-bg/30"
            data-testid="briefing-slot"
          >
            <VerdictPill verdict={slot.verdict} />
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
          </div>
        );
      })}
    </div>
  );
}

// ── EventDrillList (shared: event-row list with expandable location slots) ─

function EventDrillList({ events, driveMap, typeMap, date, onShowOnMap }) {  const [expandedType, setExpandedType] = useState(null);

  return (
    <div className="space-y-0.5">
      {events.map(({ es, region, past }) => {
        const tappable = !past && region.verdict !== 'STANDDOWN';
        const eventKey = es.targetType;
        const isExpanded = expandedType === eventKey;
        const eventTime = formatTime(getEventTime(es));
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
              onKeyDown={tappable ? (e) => { if (e.key === 'Enter' || e.key === ' ') setExpandedType(isExpanded ? null : eventKey); } : undefined}
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
              <LocationSlotList slots={region.slots} driveMap={driveMap} typeMap={typeMap} />
            )}
          </div>
        );
      })}
    </div>
  );
}

// ── HeatmapDrillDown (spans full grid, shows all events for a day × region) ─

function HeatmapDrillDown({ date, regionName, briefingDays, driveMap, typeMap, onClose, onShowOnMap }) { // eslint-disable-line no-unused-vars
  const day = briefingDays.find((d) => d.date === date);
  const events = [];
  if (day) {
    for (const es of day.eventSummaries || []) {
      const region = (es.regions || []).find((r) => r.regionName === regionName);
      if (region) events.push({ es, region, past: isEventPast(es) });
    }
  }

  return (
    <div
      data-testid="drill-down-panel"
      style={{ gridColumn: '1 / -1' }}
      className="px-3 py-2.5 rounded bg-plex-bg/50 border border-plex-border/30 mt-0.5"
    >
      <div className="flex items-center justify-between mb-2">
        <span className="font-semibold text-plex-text" style={{ fontSize: '13px' }}>
          {regionName} — {getShortDate(date)}
        </span>
        <button
          onClick={onClose}
          className="text-plex-text-muted hover:text-plex-text px-1 text-sm"
          aria-label="Close drill-down"
        >
          ✕
        </button>
      </div>
      <EventDrillList events={events} driveMap={driveMap} typeMap={typeMap}
        date={date} onShowOnMap={onShowOnMap} />
    </div>
  );
}

// HeatmapGrid is imported from ./HeatmapGrid.jsx

// ── MobileRegionCard (one region × selected day) ─────────────────────────────

function MobileRegionCard({ date, regionName, briefingDays, driveMap, typeMap, isOpen, onToggle, onShowOnMap }) {  const cellData = getDayCellData(date, regionName, briefingDays);
  if (!cellData) return null;

  const { bestVerdict, bestRegion, bestEs, allEvents } = cellData;
  const isStanddown = bestVerdict === 'STANDDOWN';

  const cardBg = isStanddown
    ? 'border-red-500/8'
    : bestVerdict === 'GO'
      ? 'bg-green-600/15 border-green-600/25'
      : 'bg-amber-500/15 border-amber-500/25';

  const eventLabel = bestEs?.targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
  const verdictLabel = isStanddown ? 'Poor'
    : bestVerdict === 'GO' ? `GO ${eventLabel}`
      : `Marginal ${eventLabel}`;

  return (
    <div className={`rounded border ${cardBg} mb-1.5`}
      style={isStanddown ? { opacity: 0.3, backgroundColor: 'rgba(180,50,50,0.04)' } : undefined}>
      <button
        data-testid="region-row"
        disabled={isStanddown}
        className={`w-full flex items-center gap-2 px-3 min-h-[48px] text-left
          ${isStanddown ? 'cursor-default' : 'cursor-pointer'}`}
        style={{ pointerEvents: isStanddown ? 'none' : undefined }}
        onClick={isStanddown ? undefined : onToggle}
      >
        <VerdictPill verdict={bestVerdict} />
        <span className="font-medium text-plex-text flex-1" style={{ fontSize: '13px' }}>
          {regionName}
        </span>
        {!isStanddown && (
          <span className="text-plex-text-secondary shrink-0" style={{ fontSize: '12px' }}>
            {verdictLabel}
          </span>
        )}
        {bestRegion?.regionTemperatureCelsius != null && (
          <span
            data-testid="region-comfort"
            className="text-plex-text-secondary shrink-0 flex items-center gap-0.5"
            style={{ fontSize: '12px' }}
          >
            {weatherCodeToIcon(bestRegion.regionWeatherCode)}
            {Math.round(bestRegion.regionTemperatureCelsius)}°C
            {bestRegion.regionApparentTemperatureCelsius != null && (
              <span className="opacity-70">
                ({Math.round(bestRegion.regionApparentTemperatureCelsius)}°C)
              </span>
            )}
            {bestRegion.regionWindSpeedMs != null && (
              <>💨 {msToMph(bestRegion.regionWindSpeedMs)}mph</>
            )}
          </span>
        )}
        {!isStanddown && (
          <span className="shrink-0 flex items-center justify-center w-11 h-11">
            <Chevron open={isOpen} className="text-lg text-plex-text-muted" />
          </span>
        )}
      </button>

      {/* Tide highlights always visible on card */}
      {(bestRegion?.tideHighlights || []).length > 0 && (
        <div className="flex flex-wrap gap-1 px-3 pb-1">
          {bestRegion.tideHighlights.map((hl) => <FlagChip key={hl} label={hl} />)}
        </div>
      )}

      {/* Expanded: event list → location slots */}
      {isOpen && (
        <div className="px-2 pb-2 border-t border-plex-border/20 mt-1 pt-1.5">
          <EventDrillList events={allEvents} driveMap={driveMap} typeMap={typeMap}
            date={date} onShowOnMap={onShowOnMap} />
        </div>
      )}
    </div>
  );
}

// ── BestBetBanner (updated: side-by-side on sm+, pick ② muted) ───────────────

function BestBetBanner({ picks, todayStr, tomorrowStr, onPickClick }) {
  if (!picks || picks.length === 0) return null;

  return (
    <div className="mb-3" data-testid="best-bet-banner">
      <div className="flex flex-col sm:flex-row gap-1.5">
        {picks.map((pick) => {
          const eventKey = resolveEventKey(pick.event, todayStr, tomorrowStr);
          const navigable = pick.event != null && eventKey != null;
          const lowConf = pick.confidence === 'low';

          const borderClass = lowConf ? 'border-plex-border' : 'border-amber-500/50';
          const bgClass = lowConf ? 'bg-plex-surface/30' : 'bg-amber-500/5';
          const cursorClass = navigable ? 'cursor-pointer hover:bg-plex-surface/50' : 'cursor-default';

          const pick1 = picks[0];
          const isNearestGood = pick.rank === 2
            && pick1?.nearestDriveMinutes != null && pick1.nearestDriveMinutes > 60
            && pick.nearestDriveMinutes != null && pick.nearestDriveMinutes <= 60;
          const rankLabel = pick.rank === 1
            ? '① BEST BET'
            : isNearestGood ? '② NEAREST GOOD' : '② ALSO GOOD';
          const rankColour = lowConf ? 'text-plex-text-muted' : 'text-amber-400';

          return (
            <button
              key={pick.rank}
              data-testid={`best-bet-pick-${pick.rank}`}
              disabled={!navigable}
              className={`flex-1 text-left rounded px-3 py-2.5 border transition-colors
                ${borderClass} ${bgClass} ${cursorClass}`}
              onClick={navigable ? () => onPickClick(eventKey) : undefined}
            >
              <div className="flex items-center gap-2 mb-0.5">
                <span className={`font-bold uppercase tracking-wider ${rankColour}`}
                  style={{ fontSize: '11px' }}>
                  {rankLabel}
                </span>
                {lowConf && (
                  <span className="text-plex-text-muted italic" style={{ fontSize: '11px' }}>
                    (low confidence)
                  </span>
                )}
                {pick.region && (
                  <span className="text-plex-text-muted ml-auto" style={{ fontSize: '11px' }}>
                    {pick.region}
                  </span>
                )}
              </div>
              {pick.dayName && pick.eventType && (
                <p className="text-plex-text-secondary leading-snug mb-0.5"
                  style={{ fontSize: '13px' }}>
                  {pick.dayName} {pick.eventType}
                  {pick.eventTime && <> · {pick.eventTime}</>}
                  {pick.nearestDriveMinutes != null && pick.nearestDriveMinutes > 0
                    && <> · {pick.nearestDriveMinutes} min drive</>}
                </p>
              )}
              <p className="font-medium leading-snug text-plex-text"
                style={{ fontSize: '14px' }}>
                {pick.headline}
              </p>
              {pick.detail && (
                <p className="text-plex-text-secondary mt-0.5 leading-relaxed" style={{ fontSize: '12px' }}>
                  {pick.detail}
                </p>
              )}
            </button>
          );
        })}
      </div>
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
    nearestDriveMinutes: PropTypes.number,
    dayName: PropTypes.string,
    eventType: PropTypes.string,
    eventTime: PropTypes.string,
  })),
  todayStr: PropTypes.string.isRequired,
  tomorrowStr: PropTypes.string.isRequired,
  onPickClick: PropTypes.func.isRequired,
};

/**
 * Redacted placeholder shown to LITE users in place of the real best-bet banner.
 * Uses opacity + pointer-events to grey out a dummy card.
 */
function BestBetPlaceholder() {
  return (
    <div className="mb-3" data-testid="best-bet-placeholder">
      <div className="opacity-45 pointer-events-none">
        <div className="flex gap-2 overflow-x-auto pb-1">
          <div className="flex-1 text-left rounded px-3 py-2.5 border border-plex-border bg-plex-surface/30">
            <p className="font-bold text-plex-gold/60" style={{ fontSize: '11px' }}>
              ① BEST BET
            </p>
            <p className="mt-1">
              <span className="text-transparent bg-plex-text-muted/20 rounded select-none">Best bet recommendation</span>
            </p>
            <p className="mt-0.5">
              <span className="text-transparent bg-plex-text-muted/20 rounded select-none" style={{ fontSize: '12px' }}>Detailed analysis and driving directions</span>
            </p>
          </div>
        </div>
      </div>
      <p className="text-plex-text-secondary mt-1" style={{ fontSize: '12px' }}>Upgrade to Pro</p>
    </div>
  );
}

// Aurora is now rendered as 🌌 grid columns inside HeatmapGrid (not a separate row).

// ── DISMISSED_AT_KEY ──────────────────────────────────────────────────────────
const DISMISSED_AT_KEY = 'briefing-dismissed-at';

// ── DailyBriefing (main export) ───────────────────────────────────────────────

/**
 * Collapsible daily briefing card displayed above the map view.
 *
 * Mobile (<sm): compact event-summary rows (always visible) + expandable day-card view.
 * Desktop (sm+): heatmap grid (3 day-columns × regions) always visible.
 * Aurora tonight section displayed when the aurora state machine is active.
 */
/* eslint-enable react/prop-types */

export default function DailyBriefing({ locations, onShowOnMap, onEvaluationScoresChange }) {
  const { role } = useAuth();
  const canSeeBestBets = role === 'ADMIN' || role === 'PRO_USER';
  const canRunEvaluation = role === 'ADMIN' || role === 'PRO_USER';
  const [briefing, setBriefing] = useState(null);
  const [loading, setLoading] = useState(true);
  const [dismissedAt, setDismissedAt] = useState(() => sessionStorage.getItem(DISMISSED_AT_KEY));
  const [isExpanded, setIsExpanded] = useState(false);
  const [selectedDayIndex, setSelectedDayIndex] = useState(0);
  const [openCardKeys, setOpenCardKeys] = useState(new Set()); // "date-regionName"
  const [qualityTier, setQualityTier] = useLocalStorageState('plannerQualityTier', 2);
  const intervalRef = useRef(null);

  // Evaluation state: keyed by "regionName|date|targetType|locationName"
  const [evaluationScores, setEvaluationScores] = useState(new Map());
  // { regionName, date, targetType, completed, total, failed, status: 'running'|'complete'|'error', evaluatedAt }
  const [evaluationProgress, setEvaluationProgress] = useState(null);
  // Stores evaluatedAt timestamps keyed by "regionName|date|targetType"
  const [evaluationTimestamps, setEvaluationTimestamps] = useState(new Map());
  const evalCleanupRef = useRef(null);

  // Active evaluation model for briefing (SHORT_TERM run type)
  const [activeModelName, setActiveModelName] = useState(null); // e.g. 'HAIKU'

  // Astro conditions: per-date scores keyed by locationName
  const [astroScoresByDate, setAstroScoresByDate] = useState({}); // { date: { locName: score } }
  const [astroAvailableDates, setAstroAvailableDates] = useState([]);

  /** Starts a Claude evaluation for the given drill-down cell. */
  const handleRunEvaluation = useCallback((regionName, date, targetType) => {
    // Clean up any previous subscription
    if (evalCleanupRef.current) evalCleanupRef.current();

    setEvaluationProgress({ regionName, date, targetType, completed: 0, total: 0, failed: 0, status: 'running' });

    const cleanup = subscribeToBriefingEvaluation(
      regionName, date, targetType,
      // onLocationScored
      (result) => {
        setEvaluationScores((prev) => {
          const next = new Map(prev);
          next.set(`${regionName}|${date}|${targetType}|${result.locationName}`, result);
          return next;
        });
      },
      // onProgress
      (progress) => {
        setEvaluationProgress((prev) => prev ? {
          ...prev, completed: progress.completed, total: progress.total, failed: progress.failed,
        } : prev);
      },
      // onComplete
      (data) => {
        setEvaluationProgress((prev) => prev ? {
          ...prev, completed: data.completed, total: data.total, failed: data.failed,
          status: 'complete', evaluatedAt: data.evaluatedAt,
        } : prev);
        if (data.evaluatedAt) {
          setEvaluationTimestamps((prev) => {
            const next = new Map(prev);
            next.set(`${regionName}|${date}|${targetType}`, data.evaluatedAt);
            return next;
          });
        }
      },
      // onLocationError
      () => {},
      // onError
      () => {
        setEvaluationProgress((prev) => prev ? { ...prev, status: 'error' } : prev);
      },
    );
    evalCleanupRef.current = cleanup;
  }, []);

  /** Stops any running SSE evaluation (called when drill-down closes or tab switches). */
  const handleStopEvaluation = useCallback(() => {
    if (evalCleanupRef.current) {
      evalCleanupRef.current();
      evalCleanupRef.current = null;
    }
  }, []);

  // Clean up SSE on unmount
  useEffect(() => {
    return () => { if (evalCleanupRef.current) evalCleanupRef.current(); };
  }, []);

  // Lift scores to parent whenever they change
  useEffect(() => {
    onEvaluationScoresChange?.(evaluationScores);
  }, [evaluationScores, onEvaluationScoresChange]);

  /** Per-user drive times: locationId → minutes from the user_drive_time table. */
  const [userDriveTimes, setUserDriveTimes] = useState({});
  useEffect(() => {
    getDriveTimes().then(setUserDriveTimes).catch(() => {});
  }, []);

  /** Map from location name → drive minutes (per-user). */
  const driveMap = useMemo(() => {
    const m = new Map();
    const idToName = {};
    (locations || []).forEach((loc) => { idToName[loc.id] = loc.name; });
    Object.entries(userDriveTimes).forEach(([locId, mins]) => {
      const name = idToName[locId];
      if (name && mins != null) m.set(name, mins);
    });
    return m;
  }, [locations, userDriveTimes]);

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

  useEffect(() => {
    if (briefing && dismissedAt && briefing.generatedAt > dismissedAt) {
      setDismissedAt(null);
      sessionStorage.removeItem(DISMISSED_AT_KEY);
    }
  }, [briefing, dismissedAt]);

  useEffect(() => {
    fetchBriefing();
    intervalRef.current = setInterval(fetchBriefing, POLL_INTERVAL_MS);
    function handleFocus() { fetchBriefing(); }
    window.addEventListener('focus', handleFocus);
    return () => {
      clearInterval(intervalRef.current);
      window.removeEventListener('focus', handleFocus);
    };
  }, [fetchBriefing]);

  // Fetch astro available dates once on mount.
  useEffect(() => {
    getAstroAvailableDates().then(setAstroAvailableDates).catch(() => {});
  }, []);

  // Fetch active model for cost estimates (briefing uses SHORT_TERM).
  useEffect(() => {
    if (canRunEvaluation) {
      getAvailableModels()
        .then((data) => setActiveModelName(data.configs?.SHORT_TERM ?? null))
        .catch(() => {});
    }
  }, [canRunEvaluation]);

  // Fetch astro conditions for each visible date in the heatmap.
  const astroDayDates = useMemo(() => {
    if (!briefing) return [];
    const events = selectUpcomingEvents(briefing.days);
    return [...new Set(events.map((e) => e.date))];
  }, [briefing]);

  useEffect(() => {
    if (astroDayDates.length === 0) return;
    const astroDates = astroDayDates.filter((d) => astroAvailableDates.includes(d));
    if (astroDates.length === 0) {
      setAstroScoresByDate({});
      return;
    }
    Promise.all(astroDates.map((d) =>
      getAstroConditions(d).then((scores) => ({ date: d, scores })).catch(() => ({ date: d, scores: [] })),
    )).then((results) => {
      const byDate = {};
      for (const { date, scores } of results) {
        const byName = {};
        scores.forEach((s) => { byName[s.locationName] = s; });
        byDate[date] = byName;
      }
      setAstroScoresByDate(byDate);
    });
  }, [astroDayDates, astroAvailableDates]);

  const handlePickClick = useCallback(() => {
    setIsExpanded(true);
  }, []);

  // Upcoming events — computed before early returns (Rules of Hooks).
  const upcomingEvents = useMemo(() => {
    if (!briefing) return [];
    return selectUpcomingEvents(briefing.days);
  }, [briefing]);

  const dayDates = useMemo(() => [...new Set(upcomingEvents.map((e) => e.date))], [upcomingEvents]);


  const sortedRegions = useMemo(() => {
    if (!briefing) return [];
    return getSortedRegions(upcomingEvents, briefing.days);
  }, [briefing, upcomingEvents]);

  // todayStr / tomorrowStr needed by auroraDates and sliderCounts memos.
  const todayStr = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(new Date());
  const tomorrowStr = (() => {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(d);
  })();

  // Determine which dates qualify for an aurora column (PRO/ADMIN only).
  const auroraDates = useMemo(() => {
    if (!canSeeBestBets) return new Set();
    const dates = new Set();
    if (briefing?.auroraTonight) dates.add(todayStr);
    if (briefing?.auroraTomorrow && briefing.auroraTomorrow.label !== 'Quiet') dates.add(tomorrowStr);
    return dates;
  }, [canSeeBestBets, briefing, todayStr, tomorrowStr]);

  // Augment events with AURORA sub-columns.
  const heatmapEvents = useMemo(() => {
    const hasAurora = auroraDates.size > 0;
    if (!hasAurora) return upcomingEvents;
    const result = [];
    const coveredDates = new Set();
    for (let i = 0; i < upcomingEvents.length; i++) {
      const ev = upcomingEvents[i];
      result.push(ev);
      const nextDate = i + 1 < upcomingEvents.length ? upcomingEvents[i + 1].date : null;
      if (nextDate !== ev.date) {
        coveredDates.add(ev.date);
        if (hasAurora && auroraDates.has(ev.date)) {
          result.push({ date: ev.date, targetType: 'AURORA' });
        }
      }
    }
    // Inject standalone aurora columns for dates with no solar events (e.g. today's aurora
    // when all today's solar events have already passed and been filtered out).
    if (hasAurora) {
      for (const aDate of auroraDates) {
        if (!coveredDates.has(aDate)) {
          // Insert before events of later dates, or at end
          const insertIdx = result.findIndex((e) => e.date > aDate);
          const entry = { date: aDate, targetType: 'AURORA' };
          if (insertIdx >= 0) result.splice(insertIdx, 0, entry);
          else result.push(entry);
        }
      }
    }
    return result;
  }, [upcomingEvents, auroraDates]);

  /** Slider cell counts: total cells and visible cells at current qualityTier. */
  const sliderCounts = useMemo(() => {
    if (!briefing) return { showing: 0, total: 0 };
    let total = 0;
    let showing = 0;
    for (const regionName of sortedRegions) {
      for (const { date, targetType } of heatmapEvents) {
        if (targetType === 'AURORA') {
          const isTonight = date === todayStr && briefing.auroraTonight;
          const isTmrw = date === tomorrowStr && briefing.auroraTomorrow;
          if (!isTonight && !isTmrw) continue;
          const auroraRegion = isTonight
            ? (briefing.auroraTonight.regions || []).find((r) => r.regionName === regionName) : null;
          const tier = isTonight
            ? computeAuroraCellTier(auroraRegion, false)
            : computeAuroraCellTier({ verdict: 'GO' }, true);
          if (tier > 5) continue; // disabled — don't count
          total += 1;
          if (isCellVisible(tier, qualityTier)) showing += 1;
          continue;
        }
        const day = briefing.days.find((d) => d.date === date);
        if (!day) continue;
        const es = (day.eventSummaries || []).find((e) => e.targetType === targetType);
        if (!es) continue;
        const region = (es.regions || []).find((r) => r.regionName === regionName);
        if (!region) continue;
        total += 1;
        const tier = computeCellTier(region);
        if (isCellVisible(tier, qualityTier)) showing += 1;
      }
    }
    return { showing, total };
  }, [briefing, sortedRegions, heatmapEvents, qualityTier, todayStr, tomorrowStr]);

  if (loading) {
    return (
      <div data-testid="daily-briefing-loading" className="card mb-4">
        <p className="text-plex-text-secondary animate-pulse" style={{ fontSize: '13px' }}>
          Loading planner...
        </p>
      </div>
    );
  }

  if (!briefing) {
    return (
      <div data-testid="daily-briefing-empty" className="card mb-4">
        <p className="text-plex-text-secondary" style={{ fontSize: '13px' }}>
          Briefing data is being prepared — refreshes automatically every 2 hours.
        </p>
      </div>
    );
  }

  const isDismissed = dismissedAt != null && briefing.generatedAt <= dismissedAt;
  if (isDismissed) {
    return (
      <button
        data-testid="briefing-minimised-pill"
        className="mb-4 px-3 py-1 rounded-full font-semibold text-plex-text-secondary border border-plex-border hover:bg-plex-surface transition-colors"
        style={{ fontSize: '12px' }}
        onClick={restore}
        title="Restore PhotoCast Planner"
      >
        📋 Planner
      </button>
    );
  }

  // Mobile compact summary rows — enriched with dayLabel for display.
  const mobileEvents = upcomingEvents.map(({ date, targetType }) => {
    const day = briefing.days.find((d) => d.date === date);
    const es = (day?.eventSummaries || []).find((e) => e.targetType === targetType);
    return es ? { es, dayLabel: getDayLabel(date, todayStr, tomorrowStr), date } : null;
  }).filter(Boolean);

  const toggleCard = (cardKey) => {
    setOpenCardKeys((prev) => {
      const next = new Set(prev);
      if (next.has(cardKey)) next.delete(cardKey);
      else next.add(cardKey);
      return next;
    });
  };

  const selectedDate = dayDates[Math.min(selectedDayIndex, dayDates.length - 1)];

  return (
    <div data-testid="daily-briefing" className="card mb-4 overflow-hidden">
      {/* ── Header ── */}
      <div className="flex items-center gap-2">
        <button
          data-testid="briefing-toggle"
          className="flex-1 flex items-center justify-between gap-3 text-left"
          onClick={() => setIsExpanded((v) => !v)}
        >
          <span className="font-semibold text-plex-text-secondary uppercase tracking-wide"
            style={{ fontSize: '12px' }}>
            PhotoCast Planner
          </span>
          <span className="flex items-center gap-2 text-plex-text-muted" style={{ fontSize: '12px' }}>
            {briefing.stale
              ? <span className="text-amber-400" title="Last refresh failed — showing cached data">stale data</span>
              : briefing.partialFailure
                ? <span title={`${briefing.failedLocationCount} location(s) failed`}>{formatAge(briefing.generatedAt)}</span>
                : formatAge(briefing.generatedAt)}
            {briefing.bestBetModel && <span className="text-plex-text-muted opacity-60">by {briefing.bestBetModel}</span>}
            <Chevron open={isExpanded} className="text-base text-plex-text-muted" />
          </span>
        </button>
        <button
          data-testid="briefing-minimise"
          className="shrink-0 text-plex-text-muted hover:text-plex-text transition-colors px-1"
          style={{ fontSize: '12px' }}
          onClick={dismiss}
          title="Minimise PhotoCast Planner"
          aria-label="Minimise PhotoCast Planner"
        >
          ✕
        </button>
      </div>

      {/* ── Best bet banner — ADMIN and PRO only; placeholder for LITE ── */}
      {canSeeBestBets && briefing.bestBets && briefing.bestBets.length > 0 ? (
        <BestBetBanner
          picks={briefing.bestBets}
          todayStr={todayStr}
          tomorrowStr={tomorrowStr}
          onPickClick={handlePickClick}
        />
      ) : !canSeeBestBets && briefing.bestBets?.length > 0 ? (
        <BestBetPlaceholder />
      ) : null}

      {/* ── Quality threshold slider (desktop + mobile) ── */}
      {dayDates.length > 0 && (
        <QualitySlider
          value={qualityTier}
          onChange={setQualityTier}
          showing={sliderCounts.showing}
          total={sliderCounts.total}
        />
      )}

      {/* ── Mobile section (sm:hidden) ── */}
      <div className="sm:hidden">
        {/* Compact event-summary rows — always visible */}
        <div className="mt-1" data-testid="briefing-collapsed-events">
          {mobileEvents.length === 0 ? (
            <p className="text-plex-text-muted italic mt-1" style={{ fontSize: '12px' }}>
              No upcoming events
            </p>
          ) : (
            mobileEvents.map(({ es, dayLabel, date }) => {
              const eventKey = `${date}-${es.targetType}`;
              return (
                <div key={eventKey} data-event-key={eventKey}>
                  <EventSummaryRow
                    dayLabel={dayLabel}
                    es={es}
                    isOpen={isExpanded}
                    onToggle={() => setIsExpanded((v) => !v)}
                  />
                </div>
              );
            })
          )}
        </div>

        {/* Expanded: day-card view */}
        {isExpanded && (
          <div className="mt-2 space-y-1" data-testid="briefing-expanded">
            {dayDates.length === 0 ? (
              <p className="text-plex-text-muted italic" style={{ fontSize: '12px' }}>
                No upcoming events
              </p>
            ) : (
              <>
                {/* Sunrise section */}
                {(() => {
                  const selectedDay = briefing.days.find((d) => d.date === selectedDate);
                  const sunriseEs = (selectedDay?.eventSummaries || []).find((es) => es.targetType === 'SUNRISE');
                  const sunsetEs = (selectedDay?.eventSummaries || []).find((es) => es.targetType === 'SUNSET');

                  const renderSection = (es, label, emoji) => {
                    if (!es || isEventPast(es)) return null;
                    const visibleRegions = sortedRegions.filter((regionName) => {
                      const region = (es.regions || []).find((r) => r.regionName === regionName);
                      if (!region) return false;
                      return isCellVisible(computeCellTier(region), qualityTier);
                    });
                    if (visibleRegions.length === 0) return null;
                    return (
                      <div key={label} className="mb-2">
                        <div className="text-plex-text-secondary mb-1 px-1 flex items-center gap-1"
                          style={{ fontSize: '12px' }}>
                          <span>{emoji}</span>
                          <span className="font-medium">{label}</span>
                        </div>
                        {visibleRegions.map((regionName) => {
                          const cardKey = `${selectedDate}-${regionName}-${es.targetType}`;
                          return (
                            <MobileRegionCard
                              key={cardKey}
                              date={selectedDate}
                              regionName={regionName}
                              briefingDays={briefing.days}
                              driveMap={driveMap}
                              typeMap={typeMap}
                              isOpen={openCardKeys.has(cardKey)}
                              onToggle={() => toggleCard(cardKey)}
                              onShowOnMap={onShowOnMap}
                            />
                          );
                        })}
                      </div>
                    );
                  };

                  // Aurora mobile section — tonight only, PRO/ADMIN
                  const auroraSection = (() => {
                    if (!canSeeBestBets || selectedDate !== todayStr || !briefing.auroraTonight) return null;
                    const at = briefing.auroraTonight;
                    const visibleRegions = sortedRegions.filter((regionName) => {
                      const ar = (at.regions || []).find((r) => r.regionName === regionName);
                      const tier = computeAuroraCellTier(ar, false);
                      return isCellVisible(tier, qualityTier);
                    });
                    if (visibleRegions.length === 0) return null;
                    const levelLabel = { MINOR: 'Minor', MODERATE: 'Moderate', STRONG: 'Strong' };
                    return (
                      <div className="mb-2">
                        <div className="text-plex-text-secondary mb-1 px-1 flex items-center gap-1"
                          style={{ fontSize: '12px' }}>
                          <span>🌌</span>
                          <span className="font-medium">Aurora tonight</span>
                          <span className="text-indigo-400 font-medium" style={{ fontSize: '11px' }}>
                            {levelLabel[at.alertLevel] || at.alertLevel}
                            {at.kp != null && ` Kp ${at.kp.toFixed(1)}`}
                          </span>
                        </div>
                        {visibleRegions.map((regionName) => {
                          const ar = (at.regions || []).find((r) => r.regionName === regionName);
                          if (!ar) return null;
                          const isGo = ar.verdict === 'GO';
                          return (
                            <div key={`${selectedDate}-${regionName}-aurora`}
                              className={`rounded border px-2 py-1.5 mb-1 ${isGo
                                ? 'border-indigo-500/30 bg-indigo-500/10'
                                : 'border-plex-border/30 bg-plex-surface/20 opacity-40'}`}>
                              <div className="flex items-center justify-between">
                                <span className="font-medium text-plex-text" style={{ fontSize: '13px' }}>
                                  {regionName}
                                </span>
                                <VerdictPill verdict={ar.verdict} />
                              </div>
                              {isGo && (
                                <div className="text-plex-text-secondary mt-0.5" style={{ fontSize: '11px' }}>
                                  Clear {ar.clearLocationCount}/{ar.totalDarkSkyLocations}
                                  {ar.bestBortleClass != null && ` · Bortle ${ar.bestBortleClass}`}
                                </div>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    );
                  })();

                  return (
                    <>
                      {renderSection(sunriseEs, 'Sunrise', '🌅')}
                      {renderSection(sunsetEs, 'Sunset', '🌇')}
                      {auroraSection}
                    </>
                  );
                })()}

                {/* Other days pills */}
                {dayDates.length > 1 && (
                  <div className="mt-2 pt-1 border-t border-plex-border/20">
                    <p className="text-plex-text-muted mb-1" style={{ fontSize: '11px' }}>
                      Other days
                    </p>
                    <div className="flex flex-wrap gap-1.5">
                      {dayDates.map((date, idx) => {
                        if (idx === selectedDayIndex) return null;
                        const goCount = sortedRegions.filter((r) => {
                          const cd = getDayCellData(date, r, briefing.days);
                          return cd?.bestVerdict === 'GO';
                        }).length;
                        const label = goCount > 0
                          ? `${getDayLabel(date, todayStr, tomorrowStr)} · ${goCount} GO`
                          : `${getDayLabel(date, todayStr, tomorrowStr)} · washout`;
                        const isWashout = goCount === 0;
                        return (
                          <button
                            key={date}
                            data-testid="mobile-other-day-pill"
                            className={`px-2 py-1 rounded-full border text-plex-text-secondary transition-colors hover:bg-plex-surface
                              ${isWashout ? 'opacity-50 border-plex-border/40' : 'border-plex-border'}`}
                            style={{ fontSize: '11px' }}
                            onClick={() => {
                              setSelectedDayIndex(idx);
                              setOpenCardKeys(new Set());
                            }}
                          >
                            {label}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        )}
      </div>

      {/* ── Desktop heatmap grid — always visible on sm+ ── */}
      <HeatmapGrid
        events={heatmapEvents}
        sortedRegions={sortedRegions}
        briefingDays={briefing.days}
        qualityTier={qualityTier}
        driveMap={driveMap}
        typeMap={typeMap}
        todayStr={todayStr}
        tomorrowStr={tomorrowStr}
        onShowOnMap={onShowOnMap}
        evaluationScores={evaluationScores}
        evaluationProgress={evaluationProgress}
        evaluationTimestamps={evaluationTimestamps}
        onRunEvaluation={handleRunEvaluation}
        onStopEvaluation={handleStopEvaluation}
        canRunEvaluation={canRunEvaluation}
        astroScoresByDate={astroScoresByDate}
        auroraTonight={briefing.auroraTonight || null}
        auroraTomorrow={briefing.auroraTomorrow || null}
        activeModelName={activeModelName}
      />
    </div>
  );
}

DailyBriefing.propTypes = {
  locations: PropTypes.arrayOf(
    PropTypes.shape({
      name: PropTypes.string.isRequired,
      locationType: PropTypes.string,
    }),
  ),
  onShowOnMap: PropTypes.func,
  onEvaluationScoresChange: PropTypes.func,
};
