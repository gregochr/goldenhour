import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getAstroConditions, getAstroAvailableDates } from '../api/astroApi.js';
import { getDriveTimes } from '../api/settingsApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import HeatmapGrid from './HeatmapGrid.jsx';
import SlotLocationName from './shared/SlotLocationName.jsx';
import HotTopicStrip from './HotTopicStrip.jsx';
import BriefingSummaryStrip from './BriefingSummaryStrip.jsx';
import useLocalStorageState from '../hooks/useLocalStorageState.js';
import { computeCellTier, isCellVisible, resolveRegionDisplay } from '../utils/tierUtils.js';
import { formatEventTimeUk, formatTideHighlight, isTravelDate } from '../utils/conversions.js';

const POLL_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes

// The quality slider was retired from the Plan tab — poor cells are now muted
// passively rather than hidden, so every tier is shown (5 = include stand-down).
const SHOW_ALL_TIER = 5;

// ── Small shared components ─────────────────────────────────────────────────
/* eslint-disable react/prop-types */

/**
 * Colour pill for a display signal. Accepts either a {@code displayVerdict}
 * (preferred — already reflects Claude ratings when available) or falls back
 * to the legacy {@code verdict} prop for not-yet-migrated call sites.
 *
 * <p>Optional {@code label} prop overrides the default text for the resolved
 * signal — used by the Gate 2 honesty patch to surface
 * "Too unsettled to forecast" on STAND_DOWN regions where no Claude
 * evaluation was produced.
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

/** Inline chip for a flag string. */
function FlagChip({ label }) {  return (
    <span className="inline-block px-1.5 py-0.5 rounded bg-plex-surface border border-plex-border text-[11px] text-plex-text-secondary font-medium">
      {label}
    </span>
  );
}

/**
 * Outline/ghost pill for a slot with clear-or-mixed weather that was NOT
 * PhotoCast-evaluated — visually distinct from the solid scored verdict pill so
 * "Claude rated this" reads differently from "the sky is clear here".
 */
function UnscoredPill({ verdict }) {
  const label = verdict === 'GO' ? 'Clear · not scored'
    : verdict === 'MARGINAL' ? 'Maybe · not scored'
      : 'Not scored';
  return (
    <span
      data-testid="unscored-pill"
      className="inline-block px-2 py-0.5 rounded text-[12px] font-normal border border-plex-border text-plex-text-secondary bg-transparent"
    >
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

/** Sort order for the unified display signal, used for region rollups. */
const DISPLAY_ORDER = { WORTH_IT: 0, MAYBE: 1, STAND_DOWN: 2, AWAITING: 3 };

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

/** Calendar-chip parts: short weekday ("Sat") and day-of-month number ("4"). */
function getCalDow(dateStr) {
  return new Date(dateStr + 'T12:00:00Z').toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
}
function getCalDayNum(dateStr) {
  return new Date(dateStr + 'T12:00:00Z').toLocaleDateString('en-GB', { day: 'numeric', timeZone: 'UTC' });
}

/**
 * The summary strip mirrors the grid's day columns (one pill per day, both solar events), capped
 * shorter than the grid so it never implies a forecast further out than the model is confident
 * about. Widening the window is this one constant.
 */
const STRIP_MAX_DAYS = 4;

/**
 * Builds the summary-strip pill descriptors — one per upcoming day, rolling up that day's best
 * across its solar events. A deterministic roll-up of the same per-region verdicts the grid shows,
 * so the strip can never disagree with it. Travel days (no forecast generated) render as "Away",
 * never "All poor".
 *
 * @param {Array}  upcomingEvents  [{date, targetType}] already ordered
 * @param {Array}  briefingDays    briefing.days
 * @param {string} todayStr        today's ISO date
 * @param {string} tomorrowStr     tomorrow's ISO date
 * @param {Set}    travelDayDates  dates the operator is away
 * @returns {Array} pill descriptors for {@link BriefingSummaryStrip}
 */
function buildSummaryPills(upcomingEvents, briefingDays, todayStr, tomorrowStr, travelDayDates) {
  // Distinct upcoming dates, in horizon order, capped — away days keep their slot.
  const dates = [];
  for (const { date } of upcomingEvents) {
    if (!dates.includes(date)) dates.push(date);
    if (dates.length === STRIP_MAX_DAYS) break;
  }

  return dates.map((date) => {
    const day = briefingDays.find((d) => d.date === date);
    const events = day?.eventSummaries || [];
    const sunriseEs = events.find((e) => e.targetType === 'SUNRISE');
    const sunsetEs = events.find((e) => e.targetType === 'SUNSET');
    const base = {
      date,
      dow: getCalDow(date),
      dayNum: getCalDayNum(date),
      dayLabel: getDayLabel(date, todayStr, tomorrowStr),
      sunriseTime: sunriseEs ? formatTime(getEventTime(sunriseEs)) : '',
      sunsetTime: sunsetEs ? formatTime(getEventTime(sunsetEs)) : '',
    };

    // Away (travel / no forecast) — never "All poor".
    if (travelDayDates?.has(date)) {
      return {
        ...base, isAway: true, peak: 'away', peakLabel: '✈ Away',
        subLabel: 'Travel day', countLabel: 'No forecast', ratedCount: 0, targetType: null,
      };
    }

    // Day-best roll-up across both solar events; the click targets the event that drove it.
    const ratedRegions = new Set();
    const allRegions = new Set();
    let goEvent = null;
    let marginalEvent = null;
    for (const es of events) {
      for (const r of es.regions || []) {
        allRegions.add(r.regionName);
        if (r.verdict === 'GO') { ratedRegions.add(r.regionName); if (!goEvent) goEvent = es.targetType; }
        else if (r.verdict === 'MARGINAL') { ratedRegions.add(r.regionName); if (!marginalEvent) marginalEvent = es.targetType; }
      }
    }
    const peak = goEvent ? 'go' : marginalEvent ? 'maybe' : 'poor';
    const peakLabel = peak === 'go' ? '◎ Worth it' : peak === 'maybe' ? 'Maybe' : 'All poor';
    const ratedCount = ratedRegions.size;
    const countLabel = ratedCount > 0
      ? `${ratedCount} ${ratedCount === 1 ? 'region' : 'regions'} rated`
      : `${allRegions.size} ${allRegions.size === 1 ? 'region' : 'regions'}`;
    return {
      ...base, isAway: false, peak, peakLabel, subLabel: null, countLabel, ratedCount,
      targetType: goEvent ?? marginalEvent ?? sunsetEs?.targetType ?? sunriseEs?.targetType ?? null,
    };
  });
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
  let bestDisplay = null;
  let bestEs = null;
  let bestRegion = null;

  for (const es of day.eventSummaries || []) {
    const region = (es.regions || []).find((r) => r.regionName === regionName);
    if (!region) continue;
    allEvents.push({ es, region, past: isEventPast(es) });
    if (isEventPast(es)) continue;
    const dv = resolveRegionDisplay(region);
    const dOrder = DISPLAY_ORDER[dv] ?? 4;
    const bestOrder = bestDisplay != null ? (DISPLAY_ORDER[bestDisplay] ?? 4) : 5;
    if (dOrder < bestOrder) {
      bestDisplay = dv;
      bestEs = es;
      bestRegion = region;
    }
  }

  if (allEvents.length === 0 || !bestDisplay) return null;
  return { bestDisplay, bestEs, bestRegion, allEvents };
}

/**
 * Returns all unique region names that appear across the given upcoming events,
 * sorted by best verdict (GO first, then MARGINAL, then STANDDOWN), then A-Z.
 */
function getSortedRegions(upcomingEvents, briefingDays) {
  const regionBest = new Map(); // regionName → best DISPLAY_ORDER value
  const regionSeen = [];

  for (const { date, targetType } of upcomingEvents) {
    const day = briefingDays.find((d) => d.date === date);
    if (!day) continue;
    const es = (day.eventSummaries || []).find((e) => e.targetType === targetType);
    if (!es) continue;
    for (const region of es.regions || []) {
      const name = region.regionName;
      const v = DISPLAY_ORDER[resolveRegionDisplay(region)] ?? 4;
      if (!regionBest.has(name)) {
        regionBest.set(name, v);
        regionSeen.push(name);
      } else if (v < regionBest.get(name)) {
        regionBest.set(name, v);
      }
    }
  }

  return regionSeen.sort((a, b) => {
    const diff = (regionBest.get(a) ?? 4) - (regionBest.get(b) ?? 4);
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

  // Mostly-poor rows (nothing worth it) fade back so the eye skips to the green rows.
  const mostlyPoor = counts.GO === 0;

  return (
    <button
      data-testid="event-summary-row"
      className="w-full flex items-center gap-2 text-xs min-h-[44px] px-1 rounded hover:bg-plex-bg/30 text-left"
      style={{ opacity: mostlyPoor ? 0.4 : 1 }}
      onClick={onToggle}
    >
      <span className="w-36 shrink-0 font-medium text-plex-text" style={{ fontSize: '13px' }}>
        {emoji} {dayLabel} {eventLabel}
      </span>
      {/* Counts read at a glance — lowercase so they stop shouting. */}
      <span className="flex gap-2.5 flex-wrap flex-1 items-center" style={{ fontFamily: 'var(--font-mono)' }}>
        {counts.GO > 0 && (
          <span data-testid="go-count" style={{ fontSize: '12px', color: 'var(--color-verdict-go)' }}>
            {counts.GO} go
          </span>
        )}
        {counts.MARGINAL > 0 && (
          <span data-testid="marginal-count" style={{ fontSize: '12px', color: 'var(--color-verdict-marginal)' }}>
            {counts.MARGINAL} maybe
          </span>
        )}
        {counts.STANDDOWN > 0 && (
          <span data-testid="standdown-count" className="text-plex-text-muted" style={{ fontSize: '12px' }}>
            {counts.STANDDOWN} poor
          </span>
        )}
      </span>
      {tideAligned && (
        <span title="Tide-aligned location in this event" className="shrink-0" style={{ color: 'var(--color-tide)' }}>
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
  const upcoming = allEvents.filter((e) => {
    if (e.past) return false;
    const dv = resolveRegionDisplay(e.region);
    return dv === 'WORTH_IT' || dv === 'MAYBE';
  });
  if (upcoming.length === 0 && !auroraActive) return null;
  return (
    <div className="flex flex-wrap gap-0.5 mt-1">
      {upcoming.map(({ es, region }) => {
        const emoji = es.targetType === 'SUNRISE' ? '🌅' : '🌇';
        const dv = resolveRegionDisplay(region);
        const label = dv === 'WORTH_IT' ? 'Worth it' : 'Maybe';
        const c = dv === 'WORTH_IT'
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

// Decides whether a slot belongs in the dimmed "standdown" section. After the Gate 2
// redesign, the displayVerdict already incorporates Claude's rating — so a triage-STANDDOWN
// slot that Claude rated 3-5★ stays in the main list. Falls back to the legacy verdict
// check for slots that pre-date the displayVerdict field.
function isPoorSlot(slot) {
  if (slot.displayVerdict) {
    return slot.displayVerdict === 'STAND_DOWN' || slot.displayVerdict === 'AWAITING';
  }
  return slot.verdict === 'STANDDOWN';
}

function LocationSlotList({ slots, driveMap, typeMap, date = null, targetType = null, onShowOnMap = null }) {  const visible = sortedSlots((slots || []).filter((s) => !isPoorSlot(s)));
  const standdowns = sortedSlots((slots || []).filter(isPoorSlot));
  if (visible.length === 0 && standdowns.length === 0) return null;
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
            {slot.claudeRating != null
              ? <VerdictPill displayVerdict={slot.displayVerdict} verdict={slot.verdict} />
              : <UnscoredPill verdict={slot.verdict} />}
            <SlotLocationName
              name={slot.locationName}
              typeIcon={typeIcon}
              date={date}
              targetType={targetType}
              onShowOnMap={onShowOnMap}
            />
            <span className="text-plex-text-secondary" style={{ fontSize: '12px' }}>
              {formatTime(slot.solarEventTime)}
            </span>
            {drive && (
              <span className="text-plex-text-secondary" data-testid="slot-drive-time" style={{ fontSize: '12px' }}>
                🚗 {drive}
              </span>
            )}
            {slot.flags?.map((flag) => <FlagChip key={flag} label={flag} />)}
            {slot.claudeHeadline && (
              <span
                data-testid="slot-headline"
                className="w-full text-plex-text font-medium"
                style={{ fontSize: '12px' }}
              >
                {slot.claudeHeadline}
              </span>
            )}
          </div>
        );
      })}
      {standdowns.map((slot) => {
        const typeIcon = LOCATION_TYPE_ICONS[typeMap.get(slot.locationName)];
        const subtitle = slot.claudeHeadline || slot.standdownReason;
        return (
          <div
            key={slot.locationName}
            className="flex flex-wrap items-center gap-1.5 px-2 py-1 rounded bg-plex-bg/30 opacity-60"
            data-testid="briefing-slot-standdown"
          >
            <VerdictPill verdict={slot.verdict} />
            <SlotLocationName
              name={slot.locationName}
              typeIcon={typeIcon}
              date={date}
              targetType={targetType}
              onShowOnMap={onShowOnMap}
            />
            {subtitle && (
              <span
                data-testid="slot-standdown-reason"
                className="text-plex-text-secondary italic"
                style={{ fontSize: '12px' }}
              >
                — {subtitle}
              </span>
            )}
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
        const regionDisplay = resolveRegionDisplay(region);
        const regionIsPoor = regionDisplay === 'STAND_DOWN' || regionDisplay === 'AWAITING';
        const tappable = !past && !regionIsPoor;
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
              {tappable && (
                <Chevron open={isExpanded} className="text-plex-text-muted shrink-0" />
              )}
            </div>

            {isExpanded && (
              <LocationSlotList slots={region.slots} driveMap={driveMap} typeMap={typeMap}
                date={date} targetType={es.targetType} onShowOnMap={onShowOnMap} />
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

  const { bestDisplay, bestRegion, bestEs, allEvents } = cellData;
  const isStanddown = bestDisplay === 'STAND_DOWN' || bestDisplay === 'AWAITING';

  const cardBg = isStanddown
    ? 'border-red-500/8'
    : bestDisplay === 'WORTH_IT'
      ? 'bg-green-600/15 border-green-600/25'
      : 'bg-amber-500/15 border-amber-500/25';

  const eventLabel = bestEs?.targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
  const verdictLabel = bestDisplay === 'STAND_DOWN' ? 'Poor'
    : bestDisplay === 'AWAITING' ? 'Awaiting'
      : bestDisplay === 'WORTH_IT' ? `Worth it ${eventLabel}`
        : `Maybe ${eventLabel}`;

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
        <VerdictPill displayVerdict={bestDisplay} label={bestRegion?.verdictLabel} />
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
          {bestRegion.tideHighlights.map((hl) => <FlagChip key={hl} label={formatTideHighlight(hl)} />)}
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

function BestBetBanner({ picks, todayStr, tomorrowStr, onPickClick, onViewOnMap = null, stale = false }) {
  // Which card has its detail paragraph expanded past the 2-line clamp.
  const [expandedRank, setExpandedRank] = useState(null);
  if (!picks || picks.length === 0) return null;

  return (
    <div className="mb-3" data-testid="best-bet-banner">
      {stale && (
        <div
          data-testid="best-bet-stale"
          className="mb-1.5 flex items-center gap-1.5"
          style={{ fontSize: '11px', color: 'var(--color-verdict-marginal)' }}
          title="This run's best-bet advisor failed — showing the last successful recommendation. Conditions may have changed."
        >
          <span aria-hidden="true">⚠</span>
          <span>From an earlier forecast — today&apos;s update didn&apos;t complete, so conditions may have changed.</span>
        </div>
      )}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {picks.map((pick) => {
          const eventKey = resolveEventKey(pick.event, todayStr, tomorrowStr);
          const navigable = pick.event != null && eventKey != null;
          const lowConf = pick.confidence === 'low';
          const isPrimary = pick.rank === 1;

          const pick1 = picks[0];
          const isNearestGood = pick.rank === 2
            && pick1?.nearestDriveMinutes != null && pick1.nearestDriveMinutes > 60
            && pick.nearestDriveMinutes != null && pick.nearestDriveMinutes <= 60;
          const rankLabel = pick.rank === 1
            ? '① BEST BET'
            : isNearestGood ? '② NEAREST GOOD' : '② ALSO GOOD';
          // Lead colour belongs to the verdict family; chrome stays bone.
          const labelColour = lowConf
            ? 'var(--color-plex-text-muted)'
            : isPrimary ? 'var(--color-verdict-go)' : 'var(--color-plex-text-secondary)';
          const accentColour = isPrimary && !lowConf
            ? 'var(--color-verdict-go)' : 'var(--color-plex-border-light)';

          const expanded = expandedRank === pick.rank;
          const clampStyle = expanded
            ? {}
            : { display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' };

          return (
            <div
              key={pick.rank}
              data-testid={`best-bet-pick-${pick.rank}`}
              role={navigable ? 'button' : undefined}
              tabIndex={navigable ? 0 : undefined}
              className={`rounded border border-plex-border transition-colors
                ${navigable ? 'cursor-pointer hover:bg-plex-surface-light/40' : 'cursor-default'}`}
              style={{ padding: '14px 16px', borderLeft: `3px solid ${accentColour}` }}
              onClick={navigable ? () => onPickClick(eventKey) : undefined}
              onKeyDown={navigable ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onPickClick(eventKey); } } : undefined}
            >
              <div className="flex items-baseline justify-between gap-3">
                <span
                  className="font-semibold uppercase"
                  style={{ fontSize: '11px', letterSpacing: '0.08em', color: labelColour }}
                >
                  {rankLabel}
                </span>
                {pick.region && (
                  <span className="text-plex-text-muted text-right" style={{ fontSize: '11px', fontFamily: 'var(--font-mono)' }}>
                    {pick.region}
                  </span>
                )}
              </div>
              {lowConf && (
                <span className="text-plex-text-muted italic" style={{ fontSize: '11px' }}>
                  (low confidence)
                </span>
              )}
              {pick.dayName && pick.eventType && (
                <p className="text-plex-text-secondary" style={{ fontSize: '12px', fontFamily: 'var(--font-mono)', marginTop: '6px' }}>
                  {pick.dayName} {pick.eventType}
                  {pick.eventTime && <> · {pick.eventTime}</>}
                  {pick.nearestDriveMinutes != null && pick.nearestDriveMinutes > 0
                    && <> · {pick.nearestDriveMinutes} min drive</>}
                </p>
              )}
              <p className="font-semibold text-plex-text" style={{ fontSize: '16px', letterSpacing: '-0.01em', marginTop: '4px', textWrap: 'balance' }}>
                {pick.headline}
              </p>
              {pick.detail && (
                <>
                  <p
                    data-testid="best-bet-detail"
                    className="text-plex-text-secondary"
                    style={{ fontSize: '13px', fontFamily: 'var(--font-serif)', lineHeight: 1.55, marginTop: '8px', ...clampStyle }}
                  >
                    {pick.detail}
                  </p>
                  <button
                    type="button"
                    data-testid="best-bet-read-more"
                    className="text-plex-text-muted hover:text-plex-text underline"
                    style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', marginTop: '6px', textUnderlineOffset: '2px' }}
                    onClick={(e) => {
                      e.stopPropagation();
                      setExpandedRank(expanded ? null : pick.rank);
                    }}
                  >
                    {expanded ? 'Show less ▴' : 'Read more ▾'}
                  </button>
                </>
              )}
              {/* Jump to the bet's region on the map (macro view) — same map-pin
                  cue as the location rows (which jump to a single pin). */}
              {navigable && pick.region && onViewOnMap && (
                <div style={{ marginTop: '8px' }}>
                  <button
                    type="button"
                    data-testid="best-bet-view-on-map"
                    className="text-plex-text-muted hover:text-plex-text underline inline-flex items-center gap-1"
                    style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', textUnderlineOffset: '2px' }}
                    onClick={(e) => {
                      e.stopPropagation();
                      onViewOnMap(pick, eventKey);
                    }}
                  >
                    🗺 View on map →
                  </button>
                </div>
              )}
            </div>
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
    relationship: PropTypes.oneOf(['SAME_SLOT', 'DIFFERENT_SLOT']),
    differsBy: PropTypes.arrayOf(PropTypes.oneOf(['DATE', 'EVENT', 'REGION'])),
  })),
  todayStr: PropTypes.string.isRequired,
  tomorrowStr: PropTypes.string.isRequired,
  onPickClick: PropTypes.func.isRequired,
  onViewOnMap: PropTypes.func,
  stale: PropTypes.bool,
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

export default function DailyBriefing({ locations, onShowOnMap, onEvaluationScoresChange, onSeasonalFeaturesChange }) {
  const { role } = useAuth();
  const isPro = role === 'ADMIN' || role === 'PRO_USER';
  const [briefing, setBriefing] = useState(null);
  const [loading, setLoading] = useState(true);
  const [dismissedAt, setDismissedAt] = useState(() => sessionStorage.getItem(DISMISSED_AT_KEY));
  const [isExpanded, setIsExpanded] = useState(false);
  const [selectedDayIndex, setSelectedDayIndex] = useState(0);
  const [openCardKeys, setOpenCardKeys] = useState(new Set()); // "date-regionName"
  const qualityTier = SHOW_ALL_TIER;
  const [showAllLocations, setShowAllLocations] = useLocalStorageState('showStanddownLocations', false);
  // The full briefing grid is collapsed by default — the summary strip leads, the grid opens on
  // demand. Once opened it persists for the session (sessionStorage), so a round-trip to the full
  // Map tab and back lands the user on the same open grid rather than re-collapsing (see handoff B5).
  // A fresh session still starts collapsed.
  const [gridExpanded, setGridExpanded] = useState(() => {
    try { return sessionStorage.getItem('planGridExpanded') === '1'; } catch { return false; }
  });
  useEffect(() => {
    try {
      if (gridExpanded) sessionStorage.setItem('planGridExpanded', '1');
      else sessionStorage.removeItem('planGridExpanded');
    } catch { /* sessionStorage unavailable — persistence is best-effort */ }
  }, [gridExpanded]);
  const intervalRef = useRef(null);

  // Evaluation scores hydrated from the batch-written cached_evaluation cache.
  // Keyed by "regionName|date|targetType|locationName".
  const [evaluationScores, setEvaluationScores] = useState(new Map());

  // Astro conditions: per-date scores keyed by locationName
  const [astroScoresByDate, setAstroScoresByDate] = useState({}); // { date: { locName: score } }
  const [astroAvailableDates, setAstroAvailableDates] = useState([]);

  // Hydrate evaluation scores from backend cache on mount (batch-scored locations)
  useEffect(() => {
    getAllEvaluationScores()
      .then((views) => {
        if (!views || views.length === 0) return;
        setEvaluationScores((prev) => {
          const next = new Map(prev);
          for (const v of views) {
            if (!v.regionName || !v.locationName) continue;
            const key = `${v.regionName}|${v.date}|${v.targetType}|${v.locationName}`;
            // Don't overwrite SSE-scored results — they're fresher
            if (!next.has(key)) {
              next.set(key, {
                locationName: v.locationName,
                rating: v.rating,
                fierySkyPotential: v.fierySkyPotential,
                goldenHourPotential: v.goldenHourPotential,
                summary: v.summary,
                triageReason: v.triageReason,
                triageMessage: v.triageMessage,
              });
            }
          }
          return next;
        });
      })
      .catch(() => {});
  }, []);

  // Lift scores to parent whenever they change
  useEffect(() => {
    onEvaluationScoresChange?.(evaluationScores);
  }, [evaluationScores, onEvaluationScoresChange]);

  // Lift seasonal features to parent whenever briefing loads
  useEffect(() => {
    onSeasonalFeaturesChange?.(briefing?.seasonalFeatures ?? []);
  }, [briefing?.seasonalFeatures, onSeasonalFeaturesChange]);

  /** Per-user drive times: locationId → minutes from the user_drive_time table. */
  const [userDriveTimes, setUserDriveTimes] = useState({});
  useEffect(() => {
    getDriveTimes().then(setUserDriveTimes).catch(() => {});
  }, []);

  /** Travel-day ranges — drives the "forecast not executed (away)" overlay. */
  const [travelRanges, setTravelRanges] = useState([]);
  useEffect(() => {
    fetchTravelDayRanges().then(setTravelRanges).catch(() => {});
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
      // Inline async wrapper satisfies react-hooks/set-state-in-effect; runs synchronously this tick.
      (async () => setDismissedAt(null))();
      sessionStorage.removeItem(DISMISSED_AT_KEY);
    }
  }, [briefing, dismissedAt]);

  useEffect(() => {
    (async () => {
      await fetchBriefing();
    })();
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
      (async () => setAstroScoresByDate({}))();
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

  // Best Bet "View on map": derive the date + event from the resolved event key
  // and hand off to the Map tab, fit-bounds to the bet's region.
  const handleBetViewOnMap = useCallback((pick, eventKey) => {
    if (!onShowOnMap || !eventKey || !pick?.region) return;
    onShowOnMap({ region: pick.region, date: eventKey.slice(0, 10), eventType: eventKey.slice(11) });
  }, [onShowOnMap]);

  const handleHotTopicTap = useCallback((topic) => {
    if (topic.filterAction && onShowOnMap) {
      onShowOnMap({ filterAction: topic.filterAction, date: topic.date });
    }
  }, [onShowOnMap]);

  // Upcoming events — computed before early returns (Rules of Hooks).
  const upcomingEvents = useMemo(() => {
    if (!briefing) return [];
    return selectUpcomingEvents(briefing.days);
  }, [briefing]);

  const dayDates = useMemo(() => [...new Set(upcomingEvents.map((e) => e.date))], [upcomingEvents]);

  /** Set of upcoming date strings the operator is away (forecast not executed). */
  const travelDayDates = useMemo(
    () => new Set(dayDates.filter((d) => isTravelDate(d, travelRanges))),
    [dayDates, travelRanges],
  );


  const sortedRegions = useMemo(() => {
    if (!briefing) return [];
    return getSortedRegions(upcomingEvents, briefing.days);
  }, [briefing, upcomingEvents]);

  // todayStr / tomorrowStr needed by mobile day labels and aurora pill matching.
  const todayStr = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(new Date());
  const tomorrowStr = (() => {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(d);
  })();

  const summaryPills = useMemo(() => {
    if (!briefing) return [];
    return buildSummaryPills(upcomingEvents, briefing.days, todayStr, tomorrowStr, travelDayDates);
  }, [briefing, upcomingEvents, todayStr, tomorrowStr, travelDayDates]);

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
    return es
      ? { es, dayLabel: getDayLabel(date, todayStr, tomorrowStr), date,
          travelDay: travelDayDates.has(date) }
      : null;
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
              ? <span className="text-red-400" title="Last refresh failed — showing cached data">stale data</span>
              : briefing.partialFailure
                ? <span title={`${briefing.failedLocationCount} location(s) failed`}>{formatAge(briefing.generatedAt)}</span>
                : formatAge(briefing.generatedAt)}
            {briefing.bestBetModel && <span className="text-plex-text-muted opacity-60">by {briefing.bestBetModel}</span>}
            {/* Expand/collapse only affects the mobile day-card view; on desktop the
                heatmap is always shown, so the chevron would be a dead control. */}
            <Chevron open={isExpanded} className="sm:hidden text-base text-plex-text-muted" />
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

      {/* ── Best bet banner — ADMIN and PRO only; placeholder for LITE; empty state ──
          Switch on the explicit bestBetStatus, not an inferred empty array:
          - picks present → render them (FAILED + picks means a serve-time fallback,
            so flag them stale; SUCCESS_WITH_PICKS / legacy render normally)
          - no picks → the honest empty state (SUCCESS_NO_PICKS, or FAILED with no
            fresh-enough fallback). Empty copy is now reserved for genuinely-empty. */}
      {isPro && briefing.bestBets && briefing.bestBets.length > 0 ? (
        <BestBetBanner
          picks={briefing.bestBets}
          todayStr={todayStr}
          tomorrowStr={tomorrowStr}
          onPickClick={handlePickClick}
          onViewOnMap={handleBetViewOnMap}
          stale={briefing.bestBetStatus === 'FAILED'}
        />
      ) : !isPro && briefing.bestBets?.length > 0 ? (
        <BestBetPlaceholder />
      ) : isPro && !loading ? (
        <div
          data-testid="best-bet-empty"
          className="mb-3 text-center rounded-lg"
          style={{
            padding: '16px 20px',
            border: '1px solid rgba(255, 255, 255, 0.06)',
            background: 'rgba(255, 255, 255, 0.02)',
            color: 'var(--text-muted, rgba(255, 255, 255, 0.45))',
            fontSize: '14px',
          }}
        >
          No standout recommendations right now — conditions are similar across all regions.
        </div>
      ) : null}

      {/* ── Hot Topics strip — seasonal conditions below the Best Bet cards ── */}
      {briefing.hotTopics?.length > 0 ? (
        <HotTopicStrip
          hotTopics={briefing.hotTopics}
          isLiteUser={role === 'LITE_USER'}
          onTopicTap={handleHotTopicTap}
          auroraTonight={briefing.auroraTonight || null}
          auroraTomorrow={briefing.auroraTomorrow || null}
        />
      ) : null}

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

                  return (
                    <>
                      {renderSection(sunriseEs, 'Sunrise', '🌅')}
                      {renderSection(sunsetEs, 'Sunset', '🌇')}
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
                          return cd?.bestDisplay === 'WORTH_IT';
                        }).length;
                        const label = goCount > 0
                          ? `${getDayLabel(date, todayStr, tomorrowStr)} · ${goCount} worth it`
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

      {/* ── Desktop: summary strip leads; the full grid sits behind an expander ── */}
      <div className="hidden sm:block">
        <BriefingSummaryStrip pills={summaryPills} onPillClick={onShowOnMap} />

        {/* FULL BRIEFING divider + expander */}
        <div className="flex items-center gap-3 mt-3 mb-1">
          <span
            className="font-mono uppercase text-plex-text-muted whitespace-nowrap"
            style={{ fontSize: '11px', letterSpacing: '0.08em' }}
          >
            Full briefing
          </span>
          <span className="flex-1 border-t border-plex-border" />
          <button
            type="button"
            data-testid="grid-expander"
            aria-expanded={gridExpanded}
            onClick={() => setGridExpanded((v) => !v)}
            className="font-mono text-plex-text-secondary hover:text-plex-text border border-plex-border hover:border-plex-border-light rounded transition-colors"
            style={{ fontSize: '11px', padding: '5px 10px' }}
          >
            {gridExpanded ? 'Collapse ▴' : 'Open full table ▾'}
          </button>
        </div>

        {gridExpanded && (
          <HeatmapGrid
            events={upcomingEvents}
            sortedRegions={sortedRegions}
            briefingDays={briefing.days}
            qualityTier={qualityTier}
            driveMap={driveMap}
            typeMap={typeMap}
            todayStr={todayStr}
            tomorrowStr={tomorrowStr}
            onShowOnMap={onShowOnMap}
            evaluationScores={evaluationScores}
            isPro={isPro}
            astroScoresByDate={astroScoresByDate}
            showAllLocations={showAllLocations}
            onShowAllLocationsChange={setShowAllLocations}
            travelDayDates={travelDayDates}
          />
        )}
      </div>
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
  onSeasonalFeaturesChange: PropTypes.func,
};
