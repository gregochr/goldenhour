/**
 * Date spellings for the Data tabs (Manage → Places and People; data-tabs-redesign-plan.md §4).
 *
 * <p>Every backend instant goes through {@link parseUtcInstant} (a bare LocalDateTime is UTC, not the reader's
 * clock) and every "today" through {@link appNow} (so Rewind moves it), on the UK calendar. One module, so the
 * tabs cannot each hand-roll a zone-less format.
 */
import { formatInstantUk, parseUtcInstant } from './conversions.js';
import { ukDateStr, ukDayOffset } from './mapDates.js';
import { appNow } from './rewind.js';

const DATE_ONLY = { day: 'numeric', month: 'short', year: 'numeric' };
const CLOCK = { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' };

/** The em dash every empty date shows. */
export const NO_DATE = '—';

/** Whether the instant falls on the UK day {@code now} is on. */
function isUkToday(instant, now) {
  return ukDayOffset(ukDateStr(instant), now) === 0;
}

/**
 * A date for a table cell: "19 Oct 2026", or "Today, 07:48" when it is the UK today.
 *
 * @param {string|Date|null|undefined} value a backend instant
 * @param {Date} [now] the instant "today" is read from; injectable for tests
 * @returns {string} an em dash when there is no usable instant
 */
export function formatDataDate(value, now = appNow()) {
  const instant = parseUtcInstant(value);
  if (!instant) return NO_DATE;
  if (isUkToday(instant, now)) return `Today, ${formatInstantUk(instant, CLOCK)}`;
  return formatInstantUk(instant, DATE_ONLY);
}

/**
 * A date with its time: "2 Oct 2026, 08:14", or "Today, 07:48" when it is the UK today.
 *
 * @param {string|Date|null|undefined} value a backend instant
 * @param {Date} [now] the instant "today" is read from; injectable for tests
 * @returns {string} an em dash when there is no usable instant
 */
export function formatDataDateTime(value, now = appNow()) {
  const instant = parseUtcInstant(value);
  if (!instant) return NO_DATE;
  if (isUkToday(instant, now)) return `Today, ${formatInstantUk(instant, CLOCK)}`;
  return formatInstantUk(instant, { ...DATE_ONLY, ...CLOCK });
}

/**
 * A plain date with no today rule: "19 Oct 2026" (an invitation's dates, which read the same every day).
 *
 * @param {string|Date|null|undefined} value a backend instant
 * @returns {string} an em dash when there is no usable instant
 */
export function formatPlainDate(value) {
  return formatInstantUk(value, DATE_ONLY) ?? NO_DATE;
}

/**
 * Whole UK calendar days since the instant, never negative (a clock skew reads as today).
 *
 * @param {string|Date|null|undefined} value a backend instant
 * @param {Date} [now] the instant "today" is read from; injectable for tests
 * @returns {number|null} null when there is no usable instant
 */
export function daysSince(value, now = appNow()) {
  const instant = parseUtcInstant(value);
  if (!instant) return null;
  return Math.max(0, -ukDayOffset(ukDateStr(instant), now));
}

/**
 * How long ago, in whole UK days: "Today", "1 day", "29 days".
 *
 * @param {string|Date|null|undefined} value a backend instant
 * @param {Date} [now] the instant "today" is read from; injectable for tests
 * @returns {string} an em dash when there is no usable instant
 */
export function formatWaiting(value, now = appNow()) {
  const days = daysSince(value, now);
  if (days == null) return NO_DATE;
  if (days === 0) return 'Today';
  return days === 1 ? '1 day' : `${days} days`;
}
