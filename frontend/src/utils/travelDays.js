import { shiftDateStr, ukDateStr, ukDayOffset } from './mapDates.js';

/**
 * Pure view-model helpers for the Travel days tab (runs-redesign-plan.md §5.6, R5).
 *
 * <p>⚠️ Every clock read here is the REAL clock ({@code new Date()}), never {@code appNow()} (R-D7): Operations does
 * not follow an admin's Rewind. Each function that needs "now" takes it as an argument, so a test can pin it; the
 * view passes {@code new Date()}. Dates are ISO {@code YYYY-MM-DD} strings on the UK civil calendar, and all
 * arithmetic is on the date itself (noon UTC), so a clock change can move no day.
 */

/** The longest note the server stores. */
export const NOTE_MAX_LENGTH = 200;

/** Weeks the calendar shows, from this week's Monday. */
export const CALENDAR_WEEKS = 5;

const WEEKDAY = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'short' });
const DAY_MONTH = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', day: 'numeric', month: 'short' });
const MONTH = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', month: 'short' });
const MS_PER_DAY = 86_400_000;

/** An ISO date as a Date at noon UTC, where no zone can move its day. */
function noon(iso) {
  return new Date(`${iso}T12:00:00Z`);
}

/** The ISO date's weekday, 0 (Sunday) to 6. */
function weekdayIndex(iso) {
  return noon(iso).getUTCDay();
}

/**
 * An ISO date as "Mon 2 Nov".
 *
 * @param {string} iso the date
 * @returns {string} the label
 */
export function formatDay(iso) {
  return `${WEEKDAY.format(noon(iso))} ${DAY_MONTH.format(noon(iso))}`;
}

/**
 * An ISO date as "2 Nov" (the day and month, no weekday).
 *
 * @param {string} iso the date
 * @returns {string} the label
 */
export function formatDayMonth(iso) {
  return DAY_MONTH.format(noon(iso));
}

/**
 * Whole days from {@code startIso} to {@code endIso}, both included: a single day is 1.
 *
 * @param {string} startIso the first day
 * @param {string} endIso the last day
 * @returns {number} the length in days
 */
export function lengthDays(startIso, endIso) {
  return Math.round((noon(endIso) - noon(startIso)) / MS_PER_DAY) + 1;
}

/**
 * The Length column: "3 days" or "1 day".
 *
 * @param {string} startIso the first day
 * @param {string} endIso the last day
 * @returns {string} the length in words
 */
export function lengthText(startIso, endIso) {
  const days = lengthDays(startIso, endIso);
  return `${days} ${days === 1 ? 'day' : 'days'}`;
}

/**
 * The Range column: "Mon 2 Nov → Wed 4 Nov", or one date for a single day.
 *
 * @param {{startDate: string, endDate: string}} range the range
 * @returns {string} the text
 */
export function rangeText(range) {
  return range.endDate === range.startDate
    ? formatDay(range.startDate)
    : `${formatDay(range.startDate)} → ${formatDay(range.endDate)}`;
}

/**
 * Whether the range has ended: its last day is before today (UK).
 *
 * @param {{endDate: string}} range the range
 * @param {Date} [now] the real clock
 * @returns {boolean} true when it is in the past
 */
export function isPastRange(range, now = new Date()) {
  return range.endDate < ukDateStr(now);
}

/**
 * The When column: "In 2 days", "Tomorrow", "Now" (today is inside the range), or "Ended 28 Oct".
 *
 * @param {{startDate: string, endDate: string}} range the range
 * @param {Date} [now] the real clock
 * @returns {string} the text
 */
export function whenText(range, now = new Date()) {
  const today = ukDateStr(now);
  if (range.endDate < today) return `Ended ${formatDayMonth(range.endDate)}`;
  if (range.startDate <= today) return 'Now';
  const days = ukDayOffset(range.startDate, now);
  return days === 1 ? 'Tomorrow' : `In ${days} days`;
}

/**
 * Splits the served ranges into the list's two groups: upcoming (today or later, soonest first) and past (newest
 * first). The server serves them newest start first, which is the past order already.
 *
 * @param {Array<object>} ranges every served range
 * @param {Date} [now] the real clock
 * @returns {{upcoming: Array<object>, past: Array<object>}} the groups
 */
export function groupRanges(ranges, now = new Date()) {
  const upcoming = ranges.filter((range) => !isPastRange(range, now))
    .sort((a, b) => (a.startDate < b.startDate ? -1 : a.startDate > b.startDate ? 1 : a.id - b.id));
  const past = ranges.filter((range) => isPastRange(range, now))
    .sort((a, b) => (a.startDate < b.startDate ? 1 : a.startDate > b.startDate ? -1 : b.id - a.id));
  return { upcoming, past };
}

/**
 * The range holding a date, if any (the earliest start when ranges overlap).
 *
 * @param {Array<object>} ranges the ranges
 * @param {string} iso the date
 * @returns {object|null} the range, or null
 */
export function rangeForDay(ranges, iso) {
  const hits = ranges.filter((range) => range.startDate <= iso && iso <= range.endDate);
  if (hits.length === 0) return null;
  return hits.reduce((first, range) => (range.startDate < first.startDate ? range : first));
}

/**
 * The calendar's cells: {@link CALENDAR_WEEKS} weeks from this week's Monday (UK), Monday first. The 1st of a month
 * reads "1 Nov"; every other cell its day number.
 *
 * @param {Date} [now] the real clock
 * @returns {Array<{iso: string, day: number, label: string, weekday: string, isToday: boolean, isPast: boolean}>}
 *   the cells in reading order
 */
export function calendarCells(now = new Date()) {
  const today = ukDateStr(now);
  const sinceMonday = (weekdayIndex(today) + 6) % 7;
  const first = shiftDateStr(today, -sinceMonday);
  return Array.from({ length: CALENDAR_WEEKS * 7 }, (_, i) => {
    const iso = shiftDateStr(first, i);
    const day = Number(iso.slice(8));
    return {
      iso,
      day,
      label: day === 1 ? `1 ${MONTH.format(noon(iso))}` : String(day),
      weekday: WEEKDAY.format(noon(iso)),
      isToday: iso === today,
      isPast: iso < today,
    };
  });
}

/**
 * A calendar cell's accessible name: "Mon 2 Nov, away (London — work)", with "today" after the date on today's cell.
 *
 * @param {{iso: string, isToday: boolean}} cell the cell
 * @param {object|null} range the range holding the day, or null
 * @returns {string} the name
 */
export function cellLabel(cell, range) {
  const parts = [formatDay(cell.iso)];
  if (cell.isToday) parts.push('today');
  if (range) parts.push(range.note ? `away (${range.note})` : 'away');
  return parts.join(', ');
}

/**
 * Checks a range's dates before they are sent: the messages the screen has always used, each for the field it
 * belongs under.
 *
 * @param {{startDate: string, endDate: string}} form the values
 * @returns {{startDate?: string, endDate?: string}} the errors, empty when valid
 */
export function validateRange(form) {
  const errors = {};
  if (!form.startDate || !form.endDate) {
    const message = 'Both a start and end date are required';
    if (!form.startDate) errors.startDate = message;
    if (!form.endDate) errors.endDate = message;
  } else if (form.endDate < form.startDate) {
    errors.endDate = 'End date must not be before start date';
  }
  return errors;
}

/**
 * Places a refusal from the server under the field it names: the 400 sentences are "note must be at most 200
 * characters", "endDate must not be before startDate" and "startDate and endDate are required". Anything else
 * (a 500, a network failure) has no field and is returned as a form-level message.
 *
 * @param {unknown} err the caught axios error
 * @param {string} fallback the sentence for a failure with nothing usable from the server
 * @returns {{field: 'startDate'|'endDate'|'note'|null, message: string}} where to show it, and what to say
 */
export function placeServerError(err, fallback) {
  const sentence = err?.response?.status === 400 ? err?.response?.data?.error : null;
  if (typeof sentence !== 'string' || sentence.trim() === '') return { field: null, message: fallback };
  if (/^note\b/.test(sentence)) return { field: 'note', message: sentence };
  if (/^endDate\b/.test(sentence)) return { field: 'endDate', message: sentence };
  if (/^startDate\b/.test(sentence)) return { field: 'startDate', message: sentence };
  return { field: null, message: sentence };
}
