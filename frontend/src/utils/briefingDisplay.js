import { formatEventTimeUk } from './conversions.js';

/**
 * Shared briefing display vocabulary — the ordering, classification, and
 * formatting helpers that DailyBriefing and HeatmapGrid previously carried as
 * file-local copies (which had already drifted apart). One definition each.
 */

/** Sort order for triage verdicts: GO first, MARGINAL second, STANDDOWN last. */
export const VERDICT_ORDER = { GO: 0, MARGINAL: 1, STANDDOWN: 2 };

/** Sort order for the unified display signal, used for region rollups. */
export const DISPLAY_ORDER = { WORTH_IT: 0, MAYBE: 1, STAND_DOWN: 2, AWAITING: 3 };

/**
 * Location type icon lookup.
 *
 * <p>Re-exported rather than redeclared. This copy had drifted: it used 💧 for WATERFALL where
 * the map, the popup, the badges and the admin list all used 💦.
 */
export { locationTypeIcons } from './locationTypes.js';

/** Window past the solar event during which it still counts as current. */
export const AFTERGLOW_MS = 30 * 60 * 1000;

/**
 * Decides whether a slot belongs in the dimmed "poor"/standdown section. After
 * the Gate 2 redesign the {@code displayVerdict} already incorporates Claude's
 * rating — a triage-STANDDOWN slot that Claude rated 3-5★ stays in the main
 * list. Falls back to the legacy verdict check for slots that pre-date the
 * displayVerdict field.
 *
 * @param {{ displayVerdict?: string, verdict?: string }} slot
 * @returns {boolean}
 */
export function isPoorSlot(slot) {
  if (slot.displayVerdict) {
    return slot.displayVerdict === 'STAND_DOWN' || slot.displayVerdict === 'AWAITING';
  }
  return slot.verdict === 'STANDDOWN';
}

/**
 * Tide-priority rank for heatmap drill-down slots:
 *   1. King tide + GO
 *   2. Tide-aligned + GO
 *   3. Other GO
 *   4. Tide-aligned + MARGINAL
 *   5. Other MARGINAL
 *   6. STANDDOWN (filtered out by caller)
 *
 * @param {{ verdict?: string, tideAligned?: boolean, flags?: string[] }} slot
 * @returns {number}
 */
export function slotSortKey(slot) {
  const v = VERDICT_ORDER[slot.verdict] ?? 3;
  const hasKing = (slot.flags || []).some((f) => f.toLowerCase().includes('king'));
  if (v === 0 && hasKing) return 0; // GO + king
  if (v === 0 && slot.tideAligned) return 1; // GO + tide
  if (v === 0) return 2; // GO plain
  if (v === 1 && slot.tideAligned) return 3; // MARGINAL + tide
  if (v === 1) return 4; // MARGINAL plain
  return 5;
}

/**
 * Heatmap drill-down slot ordering: tide-priority rank (see slotSortKey),
 * then A–Z within each group.
 *
 * @param {Array} slots
 * @returns {Array} a new sorted array
 */
export function sortedSlotsByTidePriority(slots) {
  return [...slots].sort((a, b) => {
    const diff = slotSortKey(a) - slotSortKey(b);
    return diff !== 0 ? diff : a.locationName.localeCompare(b.locationName);
  });
}

/**
 * WMO weather code → emoji ladder.
 *
 * @param {number|null|undefined} code
 * @returns {string} emoji, or empty string when code is null
 */
export function weatherCodeToIcon(code) {
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
 * Wind speed m/s → whole mph for briefing rows. Deliberately NOT
 * {@code conversions.mpsToMph}, which keeps one decimal place — briefing
 * surfaces show integers ("12mph", not "12.3mph").
 *
 * @param {number|null|undefined} ms
 * @returns {number|null}
 */
export function msToMph(ms) {
  if (ms == null) return null;
  return Math.round(ms * 2.237);
}

/**
 * Drive duration in minutes → "45 min" / "1h 5min" / "2h". Deliberately NOT
 * {@code conversions.formatDuration}, which takes milliseconds and renders
 * seconds precision.
 *
 * @param {number|null|undefined} minutes
 * @returns {string|null}
 */
export function formatDriveDuration(minutes) {
  if (minutes == null) return null;
  if (minutes < 60) return `${minutes} min`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m > 0 ? `${h}h ${m}min` : `${h}h`;
}

/**
 * Event-time ISO string → UK "HH:mm"; empty string when null.
 *
 * @param {string|null|undefined} isoString
 * @returns {string}
 */
export function formatTime(isoString) {
  if (!isoString) return '';
  return formatEventTimeUk(isoString) ?? '';
}

/**
 * First {@code solarEventTime} found in an event summary's regioned or
 * unregioned slots, falling back to the summary's own event time.
 *
 * <p>The slot walk stays first so a populated event resolves exactly as it always has. The
 * fallback matters when the walk finds nothing: {@code BriefingHonestyFilter} empties the slot
 * list of any region with zero Claude coverage, which used to leave the whole event timeless —
 * see {@link isEventPast} for what that cost. Still null for a payload cached before the backend
 * carried {@code solarEventTime}.
 *
 * @param {{ regions?: Array, unregioned?: Array, solarEventTime?: string }} es event summary
 * @returns {string|null}
 */
export function getEventTime(es) {
  for (const r of es.regions || []) {
    for (const s of r.slots || []) {
      if (s.solarEventTime) return s.solarEventTime;
    }
  }
  for (const s of es.unregioned || []) {
    if (s.solarEventTime) return s.solarEventTime;
  }
  return es.solarEventTime || null;
}

/** Local hour at which a sunrise with no resolvable time is assumed to have happened. */
const SUNRISE_ELAPSED_BY_HOUR = 12;

/** Today's ISO date in the forecast's own timezone. */
function londonToday() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(new Date());
}

/** Current hour (0–23) in the forecast's own timezone. */
function londonHour() {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/London', hour: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date());
  return Number(parts.find((p) => p.type === 'hour')?.value);
}

/**
 * Last-resort pastness for an event carrying no time at all, decided from its date.
 *
 * <p>Only reachable for a payload cached before the backend carried {@code solarEventTime}, so it
 * is a floor rather than the mechanism — but without it a stored briefing keeps the old defect
 * until the next refresh cycle overwrites it, which can be most of a day.
 *
 * <p>Noon is not arbitrary: {@code BriefingHierarchyBuilder.isEventType} already classifies a slot
 * as sunrise or sunset by whether its hour is under 12, so it is this project's own boundary
 * between the two. A same-day SUNSET stays current, because no fixed hour separates it from
 * tomorrow — the date comparison catches it at midnight instead. Erring toward "still current"
 * costs at most one event slot between the event and midnight — under three hours in June, closer
 * to eight in December — where erring the other way would hide an event that has not happened yet.
 *
 * <p>Coarse in the same direction for a sunrise: with no time in the payload it cannot know the
 * sun rose at 04:15, so between dawn and noon an elapsed sunrise still counts as current. That is
 * the safe way round, and the backend field removes the guesswork entirely once a payload carries it.
 */
function isUndatedEventPast(dateStr, targetType) {
  if (!dateStr) return false;
  const today = londonToday();
  if (dateStr < today) return true;
  if (dateStr > today) return false;
  return targetType === 'SUNRISE' && londonHour() >= SUNRISE_ELAPSED_BY_HOUR;
}

/**
 * True when the event summary's solar event (plus the afterglow window) is in the past.
 *
 * <p>An event with no resolvable time still counts as current, but only when the caller cannot
 * say which day it belongs to. That default used to be unconditional, and it was load-bearing in
 * the wrong direction: a zero-coverage day serves with every slot withdrawn, so a sunrise many
 * hours gone read as upcoming and consumed one of the six event slots the Plan screen renders —
 * pushing the far end of the window off the screen entirely. Pass {@code dateStr} wherever it is
 * known, which is every caller in this codebase.
 *
 * @param {{ regions?: Array, unregioned?: Array, solarEventTime?: string, targetType?: string }} es
 *        event summary
 * @param {string} [dateStr] ISO date (YYYY-MM-DD) of the day this summary belongs to
 * @returns {boolean}
 */
export function isEventPast(es, dateStr = null) {
  const t = getEventTime(es);
  if (!t) return isUndatedEventPast(dateStr, es?.targetType);
  return new Date(t + 'Z').getTime() + AFTERGLOW_MS < Date.now();
}
