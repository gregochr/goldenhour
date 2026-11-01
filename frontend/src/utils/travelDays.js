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

/** Weeks the calendar always shows: six, so the grid keeps its height from month to month. */
export const CALENDAR_WEEKS = 6;

const WEEKDAY = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'short' });
const DAY_MONTH = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', day: 'numeric', month: 'short' });
const YEAR = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', year: 'numeric' });
const MONTH_LONG = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', month: 'long' });
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
 * The year to add after a date: " 2027" when the date is not in the current UK year, else nothing. With no
 * {@code now} the year is never added (the plain form the calendar maths and its tests use).
 */
function yearSuffix(iso, now) {
  if (!now) return '';
  return iso.slice(0, 4) === ukDateStr(now).slice(0, 4) ? '' : ` ${YEAR.format(noon(iso))}`;
}

/**
 * An ISO date as "Mon 2 Nov", with the year ("Mon 2 Nov 2027") when it is not in the current UK year.
 *
 * @param {string} iso the date
 * @param {Date} [now] the real clock; when given, a date outside the current UK year carries its year
 * @returns {string} the label
 */
export function formatDay(iso, now = undefined) {
  return `${WEEKDAY.format(noon(iso))} ${DAY_MONTH.format(noon(iso))}${yearSuffix(iso, now)}`;
}

/**
 * An ISO date as "2 Nov" (the day and month, no weekday), with the year when it is not in the current UK year.
 *
 * @param {string} iso the date
 * @param {Date} [now] the real clock
 * @returns {string} the label
 */
export function formatDayMonth(iso, now = undefined) {
  return `${DAY_MONTH.format(noon(iso))}${yearSuffix(iso, now)}`;
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
 * @param {Date} [now] the real clock; a date outside the current UK year carries its year
 * @returns {string} the text
 */
export function rangeText(range, now = undefined) {
  return range.endDate === range.startDate
    ? formatDay(range.startDate, now)
    : `${formatDay(range.startDate, now)} → ${formatDay(range.endDate, now)}`;
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
  if (range.endDate < today) return `Ended ${formatDayMonth(range.endDate, now)}`;
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

/** A number as two digits. */
function pad2(n) {
  return String(n).padStart(2, '0');
}

/**
 * The month an ISO date falls in.
 *
 * @param {string} iso the date
 * @returns {{year: number, month0: number}} the year and the zero-based month
 */
export function monthOf(iso) {
  return { year: Number(iso.slice(0, 4)), month0: Number(iso.slice(5, 7)) - 1 };
}

/**
 * A month shifted by whole months, wrapping the year either way.
 *
 * @param {{year: number, month0: number}} month the month
 * @param {number} n months to move (negative goes back)
 * @returns {{year: number, month0: number}} the other month
 */
export function shiftMonth({ year, month0 }, n) {
  const total = year * 12 + month0 + n;
  return { year: Math.floor(total / 12), month0: ((total % 12) + 12) % 12 };
}

/**
 * Whether two months are the same one.
 *
 * @param {{year: number, month0: number}} a one month
 * @param {{year: number, month0: number}} b the other
 * @returns {boolean} true when they match
 */
export function sameMonth(a, b) {
  return a.year === b.year && a.month0 === b.month0;
}

/**
 * A month's name and year as the calendar header and its live region say them.
 *
 * @param {{year: number, month0: number}} month the month
 * @returns {{month: string, year: string}} e.g. {@code {month: 'November', year: '2026'}}
 */
export function monthLabel({ year, month0 }) {
  return { month: MONTH_LONG.format(noon(`${year}-${pad2(month0 + 1)}-01`)), year: String(year) };
}

/**
 * The first of a month, as an ISO date.
 *
 * @param {{year: number, month0: number}} month the month
 * @returns {string} the 1st
 */
export function firstOfMonth({ year, month0 }) {
  return `${year}-${pad2(month0 + 1)}-01`;
}

/**
 * The calendar's cells for a month: always {@link CALENDAR_WEEKS} weeks (42 cells), Monday first, from the Monday on
 * or before the 1st. Days outside the month are marked so the view can dim them.
 *
 * @param {{year: number, month0: number}} month the month to show
 * @param {Date} [now] the real clock, for today and past
 * @returns {Array<{iso: string, day: number, inMonth: boolean, isToday: boolean, isPast: boolean, col: number,
 *   weekday: string}>} the cells in reading order; {@code col} is 0 for Monday to 6 for Sunday
 */
export function monthCells(month, now = new Date()) {
  const today = ukDateStr(now);
  const first = firstOfMonth(month);
  const gridStart = shiftDateStr(first, -((weekdayIndex(first) + 6) % 7));
  return Array.from({ length: CALENDAR_WEEKS * 7 }, (_, i) => {
    const iso = shiftDateStr(gridStart, i);
    return {
      iso,
      day: Number(iso.slice(8)),
      inMonth: sameMonth(monthOf(iso), month),
      isToday: iso === today,
      isPast: iso < today,
      col: i % 7,
      weekday: WEEKDAY.format(noon(iso)),
    };
  });
}

/**
 * A date moved by whole months, keeping the day number but never spilling into the next month (31 Jan + 1 month is
 * 28 Feb, or 29 in a leap year).
 *
 * @param {string} iso the date
 * @param {number} n months to move (negative goes back)
 * @returns {string} the other date
 */
export function addMonthsClamped(iso, n) {
  const target = shiftMonth(monthOf(iso), n);
  const last = new Date(Date.UTC(target.year, target.month0 + 1, 0)).getUTCDate();
  return `${target.year}-${pad2(target.month0 + 1)}-${pad2(Math.min(Number(iso.slice(8)), last))}`;
}

/**
 * Whether a calendar day's bar joins its neighbours: it does when the day to the left (or right) is in the same week
 * row and belongs to the same range. Two ranges that touch never join, so a press always has one target.
 *
 * @param {Array<object>} ranges the ranges
 * @param {string} iso the day
 * @param {number} col the day's column, 0 (Monday) to 6 (Sunday)
 * @returns {{joinLeft: boolean, joinRight: boolean}} which sides join
 */
export function joinFlags(ranges, iso, col) {
  const own = rangeForDay(ranges, iso);
  if (own == null) return { joinLeft: false, joinRight: false };
  const sameAs = (other) => rangeForDay(ranges, other)?.id === own.id;
  return {
    joinLeft: col > 0 && sameAs(shiftDateStr(iso, -1)),
    joinRight: col < 6 && sameAs(shiftDateStr(iso, 1)),
  };
}

/**
 * A draft put in date order.
 *
 * @param {{start: string, end: string|null}|null} draft the draft, or null
 * @returns {{start: string, end: string|null}|null} the ordered span ({@code end} stays null for a start-only
 *   draft), or null
 */
export function draftSpan(draft) {
  if (!draft) return null;
  if (!draft.end) return { start: draft.start, end: null };
  return draft.end < draft.start ? { start: draft.end, end: draft.start } : { start: draft.start, end: draft.end };
}

/**
 * Whether a draft touches any existing range.
 *
 * @param {Array<object>} ranges the ranges
 * @param {{start: string, end: string|null}|null} span an ordered span
 * @returns {boolean} true when at least one draft day is already away
 */
export function draftOverlaps(ranges, span) {
  if (!span) return false;
  const end = span.end ?? span.start;
  return ranges.some((range) => range.startDate <= end && range.endDate >= span.start);
}

/**
 * The words in the draft bar: the dates (one for a single day) and what follows them.
 *
 * @param {{start: string, end: string|null}} span an ordered span
 * @param {Date} [now] the real clock; a date outside the current UK year carries its year
 * @returns {{dates: string, detail: string}} "Sat 31 Oct → Mon 2 Nov" and "3 days"; for a start-only draft the start
 *   and "pick an end date"
 */
export function draftText(span, now = undefined) {
  if (!span.end) return { dates: formatDay(span.start, now), detail: 'pick an end date' };
  const dates = span.end === span.start
    ? formatDay(span.start, now)
    : `${formatDay(span.start, now)} → ${formatDay(span.end, now)}`;
  return { dates, detail: lengthText(span.start, span.end) };
}

/**
 * The classes for one calendar cell. Tailwind 4 decides which of two same-property utilities wins by the order of
 * the generated stylesheet, never by className order, so this emits exactly one background, text colour, opacity,
 * ring and radius per side, resolved in the design's cascade: background base, away, selected range, draft; opacity
 * outside the month, then past (a draft day is always full); ring today, then draft.
 *
 * @param {{away: boolean, selected: boolean, draft: boolean, today: boolean, past: boolean, outside: boolean,
 *   joinLeft: boolean, joinRight: boolean, cursor: boolean}} state what is true of the cell
 * @returns {string} the class list
 */
export function cellClasses(state) {
  const {
    away, selected, draft, today, past, outside, joinLeft, joinRight, cursor,
  } = state;
  let bg = 'bg-plex-surface-light';
  if (draft) bg = 'bg-tide/18';
  else if (selected) bg = 'bg-tide/90';
  else if (away) bg = 'bg-tide/55';

  let opacity = 'opacity-100';
  if (!draft && past) opacity = 'opacity-45';
  else if (!draft && outside) opacity = 'opacity-35';

  let ring = '';
  if (draft) ring = 'inset-ring-[1.5px] inset-ring-tide/90';
  else if (today) ring = 'inset-ring-[1.5px] inset-ring-plex-text';

  const text = away || selected || draft || today ? 'text-plex-text' : 'text-plex-text-secondary';
  const hover = away || selected || draft ? '' : 'hover:bg-white/8';
  return [
    'flex min-h-9 cursor-pointer select-none items-center justify-center font-mono text-[12px] max-[639px]:min-h-11',
    joinLeft ? '-ml-[3px] rounded-l-none' : 'rounded-l-[5px]',
    joinRight ? 'rounded-r-none' : 'rounded-r-[5px]',
    bg, text, opacity, ring, hover,
    cursor ? 'group-focus-visible:outline-2 group-focus-visible:outline-offset-1 group-focus-visible:outline-plex-text' : '',
  ].filter(Boolean).join(' ');
}

/**
 * A calendar cell's accessible name: "Mon 2 Nov, away (London — work)", with "today" after the date on today's cell,
 * then ", selected" for a day of the open range and ", in draft" for a day of the draft.
 *
 * @param {{iso: string, isToday: boolean}} cell the cell
 * @param {object|null} range the range holding the day, or null
 * @param {Date} [now] the real clock; a day outside the current UK year carries its year
 * @param {{selected?: boolean, inDraft?: boolean}} [marks] what else is true of the day
 * @returns {string} the name
 */
export function cellLabel(cell, range, now = undefined, marks = {}) {
  const parts = [formatDay(cell.iso, now)];
  if (cell.isToday) parts.push('today');
  if (range) parts.push(range.note ? `away (${range.note})` : 'away');
  if (marks.selected) parts.push('selected');
  if (marks.inDraft) parts.push('in draft');
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
