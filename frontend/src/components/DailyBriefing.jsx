import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getDailyBriefing } from '../api/briefingApi.js';
import { useAuth } from '../context/AuthContext.jsx';

const POLL_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes

// ── Small shared components ─────────────────────────────────────────────────

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
function FlagChip({ label }) {
  return (
    <span className="inline-block px-1.5 py-0.5 rounded bg-plex-surface border border-plex-border text-[11px] text-plex-text-secondary font-medium">
      {label}
    </span>
  );
}

/** Rotating chevron icon — right at rest, down when open. */
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

// ── Pure helpers ─────────────────────────────────────────────────────────────

function formatTime(isoString) {
  if (!isoString) return '';
  return isoString.substring(11, 16);
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

function isEventPast(es) {
  const t = getEventTime(es);
  if (!t) return false;
  return new Date(t + 'Z') < new Date();
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

/**
 * Returns up to 3 day date-strings (YYYY-MM-DD) that have at least one
 * upcoming (non-past) event. Automatically skips fully-past days.
 */
function selectDayDates(briefingDays) {
  const result = [];
  for (const day of briefingDays) {
    const hasUpcoming = (day.eventSummaries || []).some((es) => !isEventPast(es));
    if (hasUpcoming) {
      result.push(day.date);
      if (result.length === 3) break;
    }
  }
  return result;
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
 * Returns all unique region names that appear across the given day dates,
 * sorted by best verdict (GO first, then MARGINAL, then STANDDOWN), then A-Z.
 */
function getSortedRegions(dayDates, briefingDays) {
  const regionBest = new Map(); // regionName → best VERDICT_ORDER value
  const regionSeen = [];

  for (const date of dayDates) {
    const day = briefingDays.find((d) => d.date === date);
    if (!day) continue;
    for (const es of day.eventSummaries || []) {
      if (isEventPast(es)) continue;
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
  }

  return regionSeen.sort((a, b) => {
    const diff = (regionBest.get(a) ?? 3) - (regionBest.get(b) ?? 3);
    return diff !== 0 ? diff : a.localeCompare(b);
  });
}

// ── EventSummaryRow (mobile collapsed: compact per-event row) ──────────────

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

function EventPips({ allEvents, auroraActive }) {
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

function LocationSlotList({ slots, driveMap, typeMap }) {
  const visible = sortedSlots((slots || []).filter((s) => s.verdict !== 'STANDDOWN'));
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

function EventDrillList({ events, driveMap, typeMap, date, onShowOnMap }) {
  const [expandedType, setExpandedType] = useState(null);

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

function HeatmapDrillDown({ date, regionName, briefingDays, driveMap, typeMap, onClose, onShowOnMap }) {
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

// ── HeatmapGrid (desktop — 3 day-columns) ────────────────────────────────────

function HeatmapGrid({
  dayDates,
  sortedRegions,
  briefingDays,
  auroraTonight,
  driveMap,
  typeMap,
  todayStr,
  tomorrowStr,
  onShowOnMap,
}) {
  const [drillDown, setDrillDown] = useState(null); // { date, regionName }

  if (sortedRegions.length === 0 || dayDates.length === 0) return null;

  const gridCols = `minmax(120px, 160px) repeat(${dayDates.length}, minmax(0, 1fr))`;

  const toggleDrillDown = (date, regionName) => {
    setDrillDown((prev) =>
      prev?.date === date && prev?.regionName === regionName ? null : { date, regionName },
    );
  };

  return (
    <div
      data-testid="briefing-heatmap"
      className="hidden sm:grid gap-1 mt-2"
      style={{ gridTemplateColumns: gridCols }}
    >
      {/* Header row */}
      <div className="px-1 py-1" style={{ fontSize: '12px', color: 'var(--color-text-secondary)' }}>
        Region
      </div>
      {dayDates.map((date) => (
        <div
          key={date}
          data-testid="heatmap-day-header"
          className="text-center py-1 px-1"
        >
          <div className="font-semibold text-plex-text" style={{ fontSize: '13px' }}>
            {getDayLabel(date, todayStr, tomorrowStr)}
          </div>
          <div className="text-plex-text-secondary" style={{ fontSize: '11px' }}>
            {getShortDate(date)}
          </div>
        </div>
      ))}

      {/* Region rows */}
      {sortedRegions.map((regionName) => (
        <React.Fragment key={regionName}>
          {/* Region label */}
          <div
            className="font-medium text-plex-text px-1 py-2 flex items-start"
          style={{ fontSize: '13px', overflowWrap: 'break-word', wordBreak: 'break-word', minWidth: 0 }}
          >
            {regionName}
          </div>

          {/* Day cells */}
          {dayDates.map((date) => {
            const cellData = getDayCellData(date, regionName, briefingDays);
            const isActive = drillDown?.date === date && drillDown?.regionName === regionName;

            if (!cellData) {
              return (
                <div key={date}
                  className="text-center py-2 text-plex-text-muted opacity-30"
                  style={{ fontSize: '12px' }}>
                  —
                </div>
              );
            }

            const { bestVerdict, bestRegion, bestEs, allEvents } = cellData;
            const isStanddown = bestVerdict === 'STANDDOWN';
            const auroraActive = !!auroraTonight;

            const cellBg = isStanddown
              ? 'border-red-500/8'
              : bestVerdict === 'GO'
                ? 'bg-green-600/20 border-green-600/20 hover:bg-green-600/35'
                : 'bg-amber-500/18 border-amber-500/20 hover:bg-amber-500/32';

            const verdictTextColour = isStanddown
              ? 'text-plex-text-muted'
              : bestVerdict === 'GO'
                ? 'text-green-300'
                : 'text-amber-300';

            const eventLabel = bestEs?.targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
            const verdictLabel = isStanddown ? 'Poor'
              : bestVerdict === 'GO' ? `GO ${eventLabel}`
                : `Marginal ${eventLabel}`;

            const hasKingTide = (bestRegion?.tideHighlights || [])
              .some((h) => h.toLowerCase().includes('king'));
            const alignedCount = (bestRegion?.slots || []).filter((s) => s.tideAligned).length;

            return (
              <button
                key={date}
                data-testid="heatmap-cell"
                disabled={isStanddown}
                className={`relative rounded border text-left p-2 transition-all
                  ${cellBg}
                  ${isStanddown ? 'cursor-default' : 'cursor-pointer hover:scale-[1.01]'}
                  ${isActive ? 'ring-1 ring-white/25' : ''}`}
                style={{
                  pointerEvents: isStanddown ? 'none' : undefined,
                  opacity: isStanddown ? 0.3 : undefined,
                  backgroundColor: isStanddown ? 'rgba(180,50,50,0.04)' : undefined,
                }}
                onClick={isStanddown ? undefined : () => toggleDrillDown(date, regionName)}
              >
                <div className={`font-medium ${verdictTextColour}`} style={{ fontSize: '12px' }}>
                  {verdictLabel}
                </div>
                {!isStanddown && bestRegion && (
                  <>
                    {bestRegion.regionTemperatureCelsius != null && (
                      <div className="text-plex-text-secondary mt-0.5" style={{ fontSize: '11px' }}>
                        {weatherCodeToIcon(bestRegion.regionWeatherCode)}
                        {Math.round(bestRegion.regionTemperatureCelsius)}°C
                        {bestRegion.regionWindSpeedMs != null
                          && ` ${msToMph(bestRegion.regionWindSpeedMs)}mph`}
                      </div>
                    )}
                    {(hasKingTide || alignedCount > 0) && (
                      <div className="flex flex-wrap gap-0.5 mt-0.5">
                        {hasKingTide && (
                          <span className="rounded px-1 bg-amber-500/20 text-amber-300 font-medium"
                            style={{ fontSize: '10px' }}>
                            King tide
                          </span>
                        )}
                        {alignedCount > 0 && (
                          <span className="rounded px-1 bg-teal-500/20 text-teal-300 font-medium"
                            style={{ fontSize: '10px' }}>
                            {alignedCount} aligned
                          </span>
                        )}
                      </div>
                    )}
                    <EventPips allEvents={allEvents} auroraActive={auroraActive} />
                  </>
                )}
                {isStanddown && bestRegion?.summary && (
                  <div className="text-red-400/50 mt-0.5" style={{ fontSize: '11px' }}>
                    {bestRegion.summary.slice(0, 30)}
                  </div>
                )}
              </button>
            );
          })}

          {/* Drill-down panel — spans all columns */}
          {drillDown?.regionName === regionName && drillDown?.date && (
            <HeatmapDrillDown
              date={drillDown.date}
              regionName={regionName}
              briefingDays={briefingDays}
              driveMap={driveMap}
              typeMap={typeMap}
              onClose={() => setDrillDown(null)}
              onShowOnMap={onShowOnMap}
            />
          )}
        </React.Fragment>
      ))}
    </div>
  );
}

// ── MobileRegionCard (one region × selected day) ─────────────────────────────

function MobileRegionCard({ date, regionName, briefingDays, driveMap, typeMap, isOpen, onToggle, onShowOnMap }) {
  const cellData = getDayCellData(date, regionName, briefingDays);
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
          const secondary = pick.rank !== 1;
          const lowConf = pick.confidence === 'low';

          const borderClass = secondary || lowConf ? 'border-plex-border' : 'border-amber-500/50';
          const bgClass = secondary || lowConf ? 'bg-plex-surface/30' : 'bg-amber-500/5';
          const opacityClass = secondary ? 'opacity-60' : '';
          const cursorClass = navigable ? 'cursor-pointer hover:bg-plex-surface/50' : 'cursor-default';

          const rankLabel = pick.rank === 1 ? '① BEST BET' : '② ALSO GOOD';
          const rankColour = secondary ? 'text-plex-text-muted' : 'text-amber-400';

          return (
            <button
              key={pick.rank}
              data-testid={`best-bet-pick-${pick.rank}`}
              disabled={!navigable}
              className={`flex-1 text-left rounded px-3 py-2.5 border transition-colors
                ${borderClass} ${bgClass} ${opacityClass} ${cursorClass}`}
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
              <p className={`font-medium leading-snug ${secondary ? 'text-plex-text-secondary' : 'text-plex-text'}`}
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
  })),
  todayStr: PropTypes.string.isRequired,
  tomorrowStr: PropTypes.string.isRequired,
  onPickClick: PropTypes.func.isRequired,
};

// ── AuroraTonightPanel (unchanged) ───────────────────────────────────────────

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
        <span className="font-semibold text-indigo-300" style={{ fontSize: '12px' }}>
          Aurora Alert
        </span>
        <span className={`font-bold ${levelColour}`} style={{ fontSize: '12px' }}>
          {levelLabel[aurora.alertLevel] || aurora.alertLevel}
          {aurora.kp != null && ` (Kp ${aurora.kp.toFixed(1)})`}
        </span>
        <span className="text-plex-text-secondary ml-auto" style={{ fontSize: '12px' }}>
          {aurora.clearLocationCount} location{aurora.clearLocationCount !== 1 ? 's' : ''} clear
        </span>
        <Chevron open={expanded} className="text-sm text-plex-text-muted ml-1" />
      </button>

      {expanded && (
        <div className="mt-2 space-y-2">
          {(aurora.regions || []).map((region) => (
            <div key={region.regionName} data-testid="aurora-region" className="pl-2">
              <span className="font-medium text-plex-text" style={{ fontSize: '12px' }}>
                {region.regionName}
              </span>
              <div className="flex flex-wrap gap-1 mt-0.5">
                {(region.locations || []).map((loc) => (
                  <span
                    key={loc.locationName}
                    className={`px-1.5 py-0.5 rounded ${
                      loc.clear
                        ? 'bg-green-500/20 text-green-300'
                        : 'bg-plex-surface text-plex-text-muted'
                    }`}
                    style={{ fontSize: '11px' }}
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

// ── AuroraTomorrowNote (unchanged) ────────────────────────────────────────────

function AuroraTomorrowNote({ aurora }) {
  if (!aurora || aurora.label === 'Quiet') return null;
  return (
    <div
      data-testid="aurora-tomorrow-note"
      className="mt-2 flex items-center gap-1.5 text-indigo-300"
      style={{ fontSize: '12px' }}
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
export default function DailyBriefing({ locations, onShowOnMap }) {
  const { role } = useAuth();
  const canSeeBestBets = role === 'ADMIN' || role === 'PRO_USER';
  const [briefing, setBriefing] = useState(null);
  const [loading, setLoading] = useState(true);
  const [dismissedAt, setDismissedAt] = useState(() => sessionStorage.getItem(DISMISSED_AT_KEY));
  const [isExpanded, setIsExpanded] = useState(false);
  const [selectedDayIndex, setSelectedDayIndex] = useState(0);
  const [openCardKeys, setOpenCardKeys] = useState(new Set()); // "date-regionName"
  const intervalRef = useRef(null);

  /** Map from location name → driveDurationMinutes. */
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

  const handlePickClick = useCallback(() => {
    setIsExpanded(true);
  }, []);

  // Day dates — computed before early returns (Rules of Hooks).
  const dayDates = useMemo(() => {
    if (!briefing) return [];
    return selectDayDates(briefing.days);
  }, [briefing]);

  const sortedRegions = useMemo(() => {
    if (!briefing) return [];
    return getSortedRegions(dayDates, briefing.days);
  }, [briefing, dayDates]);

  if (loading || !briefing) return null;

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

  const todayStr = new Date().toISOString().slice(0, 10);
  const tomorrowStr = (() => {
    const d = new Date();
    d.setUTCDate(d.getUTCDate() + 1);
    return d.toISOString().slice(0, 10);
  })();

  // Upcoming events (not-yet-past) for the mobile compact summary rows.
  const upcomingEvents = briefing.days.flatMap((day) =>
    (day.eventSummaries || [])
      .filter((es) => !isEventPast(es))
      .map((es) => ({
        es,
        dayLabel: getDayLabel(day.date, todayStr, tomorrowStr),
        date: day.date,
      })),
  );

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
            {formatAge(briefing.generatedAt)}
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

      {/* ── Best bet banner — ADMIN and PRO only ── */}
      {canSeeBestBets && briefing.bestBets && briefing.bestBets.length > 0 && (
        <BestBetBanner
          picks={briefing.bestBets}
          todayStr={todayStr}
          tomorrowStr={tomorrowStr}
          onPickClick={handlePickClick}
        />
      )}

      {/* ── Aurora tonight ── */}
      <AuroraTonightPanel aurora={briefing.auroraTonight || null} />

      {/* ── Mobile section (sm:hidden) ── */}
      <div className="sm:hidden">
        {/* Compact event-summary rows — always visible */}
        <div className="mt-1" data-testid="briefing-collapsed-events">
          {upcomingEvents.length === 0 ? (
            <p className="text-plex-text-muted italic mt-1" style={{ fontSize: '12px' }}>
              No upcoming events in the next two days
            </p>
          ) : (
            upcomingEvents.map(({ es, dayLabel, date }) => {
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
                {/* Region cards for selected day */}
                {sortedRegions.map((regionName) => {
                  const cardKey = `${selectedDate}-${regionName}`;
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
        dayDates={dayDates}
        sortedRegions={sortedRegions}
        briefingDays={briefing.days}
        auroraTonight={briefing.auroraTonight || null}
        driveMap={driveMap}
        typeMap={typeMap}
        todayStr={todayStr}
        tomorrowStr={tomorrowStr}
        onShowOnMap={onShowOnMap}
      />

      {/* ── Aurora tomorrow note ── */}
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
  onShowOnMap: PropTypes.func,
};
