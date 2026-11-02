import { parseUtcInstant } from './conversions.js';
import { formatClockUk, formatSeconds, runDurationText } from './runsView.js';
import { shiftDateStr } from './mapDates.js';
import { formatDay, formatDayMonth } from './travelDays.js';

/**
 * Pure view-model helpers for the Runs Day view (runs-deep-dive-plan.md §4 D2). The server serves every row, status,
 * estimate, count and figure (DD-1); what lives here is layout and wording: colours per type, the strip's lanes and
 * coordinates, filter predicates over the served type and status, and the text of a figure.
 *
 * <p>⚠️ Every clock read is the real clock ({@code new Date()}), never {@code appNow()} (R-D7): a function that needs
 * "now" takes it as an argument, defaulting to {@code new Date()}.
 */

/**
 * The display types' colours, as literal class names (Tailwind scans for whole strings): the swatch beside a row, a
 * strip block's fill and its stroke. The tokens are {@code --color-run-*} in {@code index.css}.
 */
const TYPE_STYLE = {
  OVERNIGHT: { swatch: 'bg-run-overnight', fill: 'fill-run-overnight', stroke: 'stroke-run-overnight' },
  AFTERNOON: { swatch: 'bg-run-afternoon', fill: 'fill-run-afternoon', stroke: 'stroke-run-afternoon' },
  EVENING: { swatch: 'bg-run-evening', fill: 'fill-run-evening', stroke: 'stroke-run-evening' },
  ASK_READY: { swatch: 'bg-run-ask', fill: 'fill-run-ask', stroke: 'stroke-run-ask' },
  BRIEFING: { swatch: 'bg-run-briefing', fill: 'fill-run-briefing', stroke: 'stroke-run-briefing' },
  SKY_EVAL: { swatch: 'bg-run-sky', fill: 'fill-run-sky', stroke: 'stroke-run-sky' },
  OTHER: { swatch: 'bg-run-other', fill: 'fill-run-other', stroke: 'stroke-run-other' },
};

/** The look of a served display type; one nothing here knows reads as Other. */
export function typeStyle(type) {
  return TYPE_STYLE[type] ?? TYPE_STYLE.OTHER;
}

/** Whether a served display type is one of the three forecast cycles. */
export function isCycleType(type) {
  return type === 'OVERNIGHT' || type === 'AFTERNOON' || type === 'EVENING';
}

/** The strip's five lanes, top to bottom. */
export const LANES = [
  { key: 'CYCLES', label: 'Cycles' },
  { key: 'ASK_READY', label: 'Ask ready answers' },
  { key: 'BRIEFING', label: 'Daily briefing' },
  { key: 'SKY_EVAL', label: 'Sky eval' },
  { key: 'OTHER', label: 'Other jobs' },
];

/** The lane index of a served display type. */
export function laneOf(type) {
  const key = isCycleType(type) ? 'CYCLES' : type;
  const index = LANES.findIndex((lane) => lane.key === key);
  return index === -1 ? LANES.length - 1 : index;
}

/** The filter chips, in order: the hash's key, the server's {@code filterCounts} key and the words. */
export const FILTER_CHIPS = [
  { key: 'all', countKey: 'ALL', label: 'All' },
  { key: 'cycles', countKey: 'CYCLES', label: 'Cycles' },
  { key: 'ask_ready', countKey: 'ASK_READY', label: 'Ask ready answers' },
  { key: 'briefing', countKey: 'BRIEFING', label: 'Briefing' },
  { key: 'sky_eval', countKey: 'SKY_EVAL', label: 'Sky eval' },
  { key: 'other', countKey: 'OTHER', label: 'Other' },
];

/** Whether a served status is one the Need attention chip keeps: Partial or Failed (the server's own set). */
export function rowNeedsAttention(row) {
  return row.status === 'PARTIAL' || row.status === 'FAILED';
}

/**
 * Whether a served row is kept by a filter chip. The counts on the chips are the server's ({@code filterCounts});
 * this only applies the same predicate to the rows already served.
 *
 * @param {{type: string, status: string}} row the served row
 * @param {string} filter {@code all}, {@code cycles}, {@code ask_ready}, {@code briefing}, {@code sky_eval},
 *   {@code other} or {@code attention}
 * @returns {boolean}
 */
export function rowMatchesFilter(row, filter) {
  switch (filter) {
    case 'cycles': return isCycleType(row.type);
    case 'ask_ready': return row.type === 'ASK_READY';
    case 'briefing': return row.type === 'BRIEFING';
    case 'sky_eval': return row.type === 'SKY_EVAL';
    case 'other': return row.type === 'OTHER';
    case 'attention': return rowNeedsAttention(row);
    default: return true;
  }
}

const WEEKDAY_LONG = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'long' });

/** An ISO date at noon UTC, where no zone can move its day. */
function noon(iso) {
  return new Date(`${iso}T12:00:00Z`);
}

/**
 * Whole days from {@code iso} up to {@code today}: 0 for today, 1 for yesterday. Both are UK calendar dates.
 */
export function daysAgo(iso, today) {
  return Math.round((noon(today) - noon(iso)) / 86_400_000);
}

/**
 * The date button's two parts: the relative word ("Today", "Yesterday", else the weekday) and the date
 * ("Mon 2 Nov" for the first two, "26 Oct" otherwise; the year only outside the current one).
 *
 * @param {string} iso the shown day
 * @param {string} today the UK today
 * @param {Date} [now] the real clock, for the year
 * @returns {{word: string, date: string}}
 */
export function dayButtonParts(iso, today, now = new Date()) {
  const ago = daysAgo(iso, today);
  if (ago === 0) return { word: 'Today', date: formatDay(iso, now) };
  if (ago === 1) return { word: 'Yesterday', date: formatDay(iso, now) };
  return { word: WEEKDAY_LONG.format(noon(iso)), date: formatDayMonth(iso, now) };
}

/** The small word beside the Day tab: "Today", "Yesterday", else "Mon 26 Oct". */
export function dayTabWord(iso, today, now = new Date()) {
  const ago = daysAgo(iso, today);
  if (ago === 0) return 'Today';
  if (ago === 1) return 'Yesterday';
  return formatDay(iso, now);
}

/** The next day, by calendar date (never plus 24 hours: a UK day is 23, 24 or 25). */
export function nextDay(iso) {
  return shiftDateStr(iso, 1);
}

/** The previous day, by calendar date. */
export function previousDay(iso) {
  return shiftDateStr(iso, -1);
}

/** Pounds to the penny ("£1.38") from a served amount, or an em dash for none. */
export function formatGbp(value) {
  return value == null ? '—' : `£${Number(value).toFixed(2)}`;
}

/** Dollars to the cent ("$1.85") from a served amount, or an em dash for none. */
export function formatUsd(value) {
  return value == null ? '—' : `$${Number(value).toFixed(2)}`;
}

/** The "~" a served estimate carries, or nothing. */
function approx(estimated) {
  return estimated ? '~' : '';
}

/** The Start cell: "02:56", or "~12:56" when the server says the time is an estimate. */
export function rowStartText(row) {
  return `${approx(row.estimated)}${formatClockUk(row.startedAt)}`;
}

/** The Duration cell: the served duration, "~" for a scheduled row (the median of its last 30), "x so far" running. */
export function rowDurationText(row, now = new Date()) {
  const text = runDurationText(row, now);
  return row.status === 'SCHEDULED' && text !== '—' ? `~${text}` : text;
}

/** The Cost cell: "£1.38", "~£0.70" for a scheduled row, an em dash when unknown. */
export function rowCostText(row) {
  if (row.costGbp == null) return '—';
  return `${row.status === 'SCHEDULED' ? '~' : ''}${formatGbp(row.costGbp)}`;
}

/** Whether a row's cost is still accruing or unknown ("so far"): never for a row still to come. */
export function rowCostIsOpen(row) {
  return row.costComplete === false && row.status !== 'SCHEDULED';
}

/** An instant's UK clock with seconds, "02:56:42". */
const CLOCK_SECONDS = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/London', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});

/** The facts' clock: "02:56:42", or an empty string for none. */
export function formatClockSecondsUk(instant) {
  const d = parseUtcInstant(instant);
  return d ? CLOCK_SECONDS.format(d) : '';
}

/** A call's summed time in milliseconds, "304ms", "36m 51s"; an em dash for none. */
export function formatMs(ms) {
  if (ms == null) return '—';
  return ms < 1000 ? `${Math.round(ms)}ms` : formatSeconds(Math.round(ms / 1000));
}

/** "in 3h 14m" / "in 12m": the wait until an instant, to the minute, never under one. */
export function untilText(instant, now = new Date()) {
  const at = parseUtcInstant(instant);
  if (!at) return '';
  const minutes = Math.max(1, Math.round((at.getTime() - now.getTime()) / 60_000));
  const hours = Math.floor(minutes / 60);
  return `in ${hours > 0 ? `${hours}h ` : ''}${minutes % 60}m`;
}

/**
 * The strip's x scale: the served UK day, midnight to midnight (23, 24 or 25 hours; B10).
 *
 * @param {{dayStartUtc: string, dayEndUtc: string}} day the served day
 * @returns {{start: number, end: number, span: number}} epoch milliseconds
 */
export function stripScale(day) {
  const start = Date.parse(day.dayStartUtc);
  const end = Date.parse(day.dayEndUtc);
  return { start, end, span: Math.max(1, end - start) };
}

/** A position on the strip as a fraction of the day, clamped to 0..1. */
export function stripFraction(ms, scale) {
  return Math.min(1, Math.max(0, (ms - scale.start) / scale.span));
}

/**
 * The strip's x-axis ticks, every three hours from midnight, with a closing one at the day's end, labelled on the UK
 * clock (so a 25-hour day's labels are true, not evenly 3-hourly numbers).
 *
 * @returns {Array<{ms: number, label: string, fraction: number}>}
 */
export function stripTicks(scale) {
  const THREE_HOURS = 3 * 3_600_000;
  const ticks = [];
  for (let ms = scale.start; ms < scale.end; ms += THREE_HOURS) {
    ticks.push({ ms, label: formatClockUk(new Date(ms)), fraction: stripFraction(ms, scale) });
  }
  ticks.push({ ms: scale.end, label: '00:00', fraction: 1 });
  return ticks;
}

/**
 * One row as a strip block: its lane and its left and right edges as fractions of the day. A finished run ends when it
 * ended, a running one now, a scheduled one after its median duration; a run with no end is a sliver.
 *
 * @param {object} row the served row
 * @param {{start: number, end: number, span: number}} scale the day's scale
 * @param {number} nowMs the served "now" (today) or the end of the day
 * @returns {{id: string, lane: number, x0: number, x1: number, row: object}|null} null for a row with no start
 */
export function stripBlock(row, scale, nowMs) {
  const startedAt = parseUtcInstant(row.startedAt);
  if (!startedAt) return null;
  const start = startedAt.getTime();
  let end = start;
  if (row.endedAt) end = parseUtcInstant(row.endedAt)?.getTime() ?? start;
  else if (row.status === 'RUNNING') end = nowMs;
  else if (row.durationSeconds != null) end = start + row.durationSeconds * 1000;
  return { id: row.id, lane: laneOf(row.type), x0: stripFraction(start, scale), x1: stripFraction(Math.max(start, end), scale), row };
}
