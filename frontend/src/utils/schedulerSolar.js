/**
 * Pure helpers for the Scheduler screen's sun-relative (SOLAR_OFFSET) jobs and its read-only
 * solar panel (solar-relative-refresh-plan §5). Formatting and validation only — every figure
 * shown comes from the backend ({@code GET /api/admin/scheduler/solar-preview}); nothing here
 * computes a solar time.
 */

const UK_ZONE = 'Europe/London';

/** The two jobs that may be switched to a sun-relative schedule (plan A3). */
export const FORECAST_CYCLE_JOBS = ['near_term_batch_evaluation', 'intraday_forecast_refresh'];

/** All three forecast cycle jobs: the two above and the evening sunrise refresh (a plain cron job, never solar). */
export const CYCLE_JOBS = [...FORECAST_CYCLE_JOBS, 'evening_sunrise_refresh'];

/**
 * Whether a job runs a forecast cycle, whether or not it may be sun-relative.
 *
 * @param {string} jobKey - the job key
 * @returns {boolean} true for the nightly, intraday and evening cycle jobs
 */
export function isCycleJob(jobKey) {
  return CYCLE_JOBS.includes(jobKey);
}

/** Smallest offset the backend accepts, in minutes before the event. */
export const MIN_OFFSET_MINUTES = 0;

/** Largest offset the backend accepts, in minutes before the event. */
export const MAX_OFFSET_MINUTES = 720;

/**
 * Whether a job may be switched between a fixed time and a sun-relative one.
 *
 * @param {string} jobKey - the job key
 * @returns {boolean} true for the two forecast cycle jobs
 */
export function isForecastCycleJob(jobKey) {
  return FORECAST_CYCLE_JOBS.includes(jobKey);
}

/**
 * Splits an instant into UK-local display parts.
 *
 * @param {Date} date - the instant
 * @returns {object} map of part type to value
 */
function ukParts(date) {
  const out = {};
  new Intl.DateTimeFormat('en-GB', {
    timeZone: UK_ZONE,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  })
    .formatToParts(date)
    .forEach((p) => {
      out[p.type] = p.value;
    });
  return out;
}

/**
 * Formats an ISO instant as UK local time of day, e.g. "03:00 UK".
 *
 * @param {string|null|undefined} iso - ISO 8601 instant
 * @returns {string} the time, or an empty string when absent or unparseable
 */
export function formatUkTime(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const p = ukParts(date);
  return `${p.hour}:${p.minute} UK`;
}

/**
 * Formats an ISO instant as UK local date and time, e.g. "Tue 13 Oct 03:00 UK".
 *
 * @param {string|null|undefined} iso - ISO 8601 instant
 * @returns {string} the date and time, or an empty string when absent or unparseable
 */
export function formatUkDateTime(iso) {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const p = ukParts(date);
  return `${p.weekday} ${p.day} ${p.month} ${p.hour}:${p.minute} UK`;
}

/**
 * Formats a plain ISO date (yyyy-MM-dd) as e.g. "18 Jun".
 *
 * @param {string|null|undefined} isoDate - a civil date
 * @returns {string} the short date, or the input when it is not a date
 */
export function formatCivilDate(isoDate) {
  if (!isoDate) return '';
  const date = new Date(`${isoDate}T12:00:00Z`);
  if (Number.isNaN(date.getTime())) return isoDate;
  return date.toLocaleDateString('en-GB', { timeZone: 'UTC', day: 'numeric', month: 'short' });
}

/**
 * Words for a whole number of minutes: 255 gives "4 h 15 min", 240 "4 h", 45 "45 min".
 *
 * @param {number} minutes - non-negative whole minutes
 * @returns {string} the duration in words
 */
export function describeMinutes(minutes) {
  const total = Math.round(minutes);
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

/**
 * Words for a duration in seconds, rounded to the minute.
 *
 * @param {number} seconds - duration in seconds
 * @returns {string} the duration in words
 */
export function describeSeconds(seconds) {
  return describeMinutes(Math.round(seconds / 60));
}

/**
 * The rule in words, e.g. "4 h 15 min before sunrise".
 *
 * @param {string} event - SUNRISE or SUNSET
 * @param {number} offsetMinutes - minutes before the event
 * @returns {string} the rule, or an empty string when the event is not recognised
 */
export function describeSolarRule(event, offsetMinutes) {
  if (event !== 'SUNRISE' && event !== 'SUNSET') return '';
  const word = event === 'SUNRISE' ? 'sunrise' : 'sunset';
  const offset = Number(offsetMinutes);
  if (!Number.isFinite(offset)) return '';
  return offset === 0 ? `At ${word}` : `${describeMinutes(offset)} before ${word}`;
}

/**
 * Validates an offset typed into an input.
 *
 * @param {string|number} raw - the input value
 * @returns {{ok: boolean, value?: number, message?: string}} the whole-minute value, or why not
 */
export function validateOffset(raw) {
  const text = String(raw ?? '').trim();
  if (!/^\d+$/.test(text)) {
    return { ok: false, message: 'Enter a whole number of minutes.' };
  }
  const value = Number(text);
  if (value < MIN_OFFSET_MINUTES || value > MAX_OFFSET_MINUTES) {
    return {
      ok: false,
      message: `Offset must be between ${MIN_OFFSET_MINUTES} and ${MAX_OFFSET_MINUTES} minutes.`,
    };
  }
  return { ok: true, value };
}

/**
 * The first of the preview's two days whose solar fire is usable and still ahead.
 *
 * @param {object} jobPreview - one entry of the preview's {@code jobs}
 * @param {number} nowMs - the current time in epoch milliseconds
 * @returns {string|null} the fire instant, or null when neither day offers one
 */
export function nextPreviewFire(jobPreview, nowMs) {
  const days = [jobPreview?.today, jobPreview?.tomorrow];
  for (const day of days) {
    if (day && !day.fallback && day.fireInstant) {
      const t = new Date(day.fireInstant).getTime();
      if (!Number.isNaN(t) && t > nowMs) return day.fireInstant;
    }
  }
  return null;
}

/**
 * Formats a reference point as "54.776 N, 1.575 W".
 *
 * @param {{lat: number, lon: number}} point - degrees north and east
 * @returns {string} the point in words, or an empty string when absent
 */
export function formatReferencePoint(point) {
  if (!point || !Number.isFinite(point.lat) || !Number.isFinite(point.lon)) return '';
  const ns = point.lat >= 0 ? 'N' : 'S';
  const ew = point.lon >= 0 ? 'E' : 'W';
  return `${Math.abs(point.lat).toFixed(3)} ${ns}, ${Math.abs(point.lon).toFixed(3)} ${ew}`;
}
