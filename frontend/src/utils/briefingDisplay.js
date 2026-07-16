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

/** Location type icon lookup. */
export const LOCATION_TYPE_ICONS = {
  LANDSCAPE: '🏔️',
  WILDLIFE: '🐾',
  SEASCAPE: '🌊',
  WATERFALL: '💧',
};

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
 * Simple slot ordering: verdict rank (GO → MARGINAL → STANDDOWN), then A–Z.
 * Used by the Plan tab's event drill-down lists.
 *
 * @param {Array} slots
 * @returns {Array} a new sorted array
 */
export function sortedSlotsByVerdict(slots) {
  return [...slots].sort((a, b) => {
    const vd = (VERDICT_ORDER[a.verdict] ?? 3) - (VERDICT_ORDER[b.verdict] ?? 3);
    return vd !== 0 ? vd : a.locationName.localeCompare(b.locationName);
  });
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
 * then A–Z within each group. Deliberately richer than
 * {@link sortedSlotsByVerdict} — the drill-down surfaces tide opportunity.
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
 * unregioned slots, or null.
 *
 * @param {{ regions?: Array, unregioned?: Array }} es event summary
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
  return null;
}

/**
 * True when the event summary's solar event (plus the afterglow window) is in
 * the past. Events with no resolvable time count as current.
 *
 * @param {{ regions?: Array, unregioned?: Array }} es event summary
 * @returns {boolean}
 */
export function isEventPast(es) {
  const t = getEventTime(es);
  if (!t) return false;
  return new Date(t + 'Z').getTime() + AFTERGLOW_MS < Date.now();
}
