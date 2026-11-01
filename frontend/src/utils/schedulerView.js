import { parseUtcInstant } from './conversions.js';
import { UK_ZONE, ukDateStr, ukDayOffset } from './mapDates.js';
import { describeSolarRule } from './schedulerSolar.js';

/**
 * Pure view-model helpers for the Scheduler tab (runs-redesign-plan.md §5.5, R4).
 *
 * <p>⚠️ Every clock read here is the REAL clock ({@code new Date()}), never {@code appNow()} (R-D7): Operations does
 * not follow an admin's Rewind. A function that needs "now" takes it as an argument defaulting to
 * {@code new Date()}, so a test can pin it.
 */

/** The schedule types the screen knows how to show and edit; anything else reads as "unrecognised". */
export const SCHEDULE_CRON = 'CRON';
export const SCHEDULE_FIXED_DELAY = 'FIXED_DELAY';
export const SCHEDULE_SOLAR = 'SOLAR_OFFSET';

/** The served job statuses. */
export const STATUS_ACTIVE = 'ACTIVE';
export const STATUS_PAUSED = 'PAUSED';
export const STATUS_OFF = 'DISABLED_BY_CONFIG';

const DAY_NAMES = {
  MON: 'Monday', TUE: 'Tuesday', WED: 'Wednesday', THU: 'Thursday', FRI: 'Friday', SAT: 'Saturday', SUN: 'Sunday',
};

/**
 * The current UK timezone label: "BST" in summer, "GMT" in winter. {@code Intl} handles the changeover.
 *
 * @param {Date} [now] the real clock
 * @returns {string} "BST" or "GMT"
 */
export function getUKTimezoneLabel(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: UK_ZONE,
    timeZoneName: 'short',
  }).formatToParts(now);
  return parts.find((p) => p.type === 'timeZoneName')?.value || 'GMT';
}

/** A UTC hour and minute, today, as a UK local "HH:MM". */
function formatUTCAsUK(utcHour, utcMinute = 0) {
  const date = new Date();
  date.setUTCHours(utcHour, utcMinute, 0, 0);
  return date.toLocaleTimeString('en-GB', {
    timeZone: UK_ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

/**
 * A human-readable description of a cron expression, with times converted to UK local time (BST or GMT). A job whose
 * cron already runs in Europe/London shows its hours as-is, labelled "UK time". A cron this cannot read comes back
 * as it was.
 *
 * @param {string} cron a 6-field Spring cron expression
 * @param {string} [timeZone] the zone the cron runs in; absent or "UTC" means UTC
 * @returns {string}
 */
export function describeCron(cron, timeZone = undefined) {
  if (!cron) return '';
  const parts = cron.split(' ');
  if (parts.length < 6) return cron;

  const [sec, min, hour, dom, mon, dow] = parts;

  if (sec === '0' && dom === '*' && mon === '*') {
    const isUK = timeZone === 'Europe/London';
    const tzLabel = isUK ? 'UK time' : getUKTimezoneLabel();
    const minVal = parseInt(min, 10) || 0;
    const timeStr = hour.split(',')
      .map((h) => (isUK
        ? `${String(parseInt(h, 10)).padStart(2, '0')}:${String(minVal).padStart(2, '0')}`
        : formatUTCAsUK(parseInt(h, 10), minVal)))
      .join(', ');
    if (dow === '*') return `Daily at ${timeStr} ${tzLabel}`;
    return `Every ${DAY_NAMES[dow] || dow} at ${timeStr} ${tzLabel}`;
  }
  return cron;
}

/**
 * A human-readable description of a fixed delay.
 *
 * @param {number} ms the delay in milliseconds
 * @returns {string} "Every 5 minutes", or an empty string for no delay
 */
export function describeFixedDelay(ms) {
  if (!ms || ms <= 0) return '';
  if (ms >= 3_600_000) {
    const hours = ms / 3_600_000;
    return `Every ${hours === 1 ? 'hour' : `${hours} hours`}`;
  }
  if (ms >= 60_000) {
    const mins = ms / 60_000;
    return `Every ${mins === 1 ? 'minute' : `${mins} minutes`}`;
  }
  return `Every ${ms / 1000} seconds`;
}

/**
 * A relative time: "3 min ago", or with {@code future} "in 3 min". An empty value reads "Never".
 *
 * @param {string} isoString an ISO 8601 instant
 * @param {{future?: boolean, now?: Date}} [options]
 * @returns {string}
 */
export function relativeTime(isoString, { future = false, now = new Date() } = {}) {
  if (!isoString) return 'Never';
  const diff = now.getTime() - new Date(isoString).getTime();
  if (future) {
    const ahead = -diff;
    if (ahead <= 0) return 'just now';
    const seconds = Math.floor(ahead / 1000);
    if (seconds < 60) return `in ${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `in ${minutes} min`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `in ${hours}h`;
    return `in ${Math.floor(hours / 24)}d`;
  }
  if (diff < 0) return 'just now';
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

const CLOCK_FORMAT = new Intl.DateTimeFormat('en-GB', {
  timeZone: UK_ZONE, hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
});
const WEEKDAY_FORMAT = new Intl.DateTimeFormat('en-GB', { timeZone: UK_ZONE, weekday: 'short' });
const DAY_MONTH_FORMAT = new Intl.DateTimeFormat('en-GB', { timeZone: UK_ZONE, day: 'numeric', month: 'short' });

/** How many days ahead a time still reads as just the weekday ("Sun 02:30"); beyond it the date is added. */
const WEEKDAY_ONLY_DAYS = 6;

/**
 * The Next column's time, on the UK clock: "12:17" today, "Sun 02:30" inside the next six days, else
 * "Sat 7 Nov 06:00".
 *
 * @param {string|null|undefined} instant the next fire instant
 * @param {Date} [now] the real clock
 * @returns {string} the time, or an empty string for none
 */
export function formatNextFire(instant, now = new Date()) {
  const d = parseUtcInstant(instant);
  if (!d) return '';
  const clock = CLOCK_FORMAT.format(d);
  const ahead = ukDayOffset(ukDateStr(d), now);
  if (ahead === 0) return clock;
  if (ahead > 0 && ahead <= WEEKDAY_ONLY_DAYS) return `${WEEKDAY_FORMAT.format(d)} ${clock}`;
  return `${WEEKDAY_FORMAT.format(d)} ${DAY_MONTH_FORMAT.format(d)} ${clock}`;
}

/**
 * The words for a job's schedule and the type under them.
 *
 * @param {object} job a served job
 * @returns {{words: string, type: string, recognised: boolean}} {@code recognised} is false for a schedule type this
 *   screen does not know ("Unrecognised schedule type…" then shows in the panel)
 */
export function describeSchedule(job) {
  if (job.scheduleType === SCHEDULE_SOLAR) {
    return {
      words: describeSolarRule(job.solarEvent, job.solarOffsetMinutes) || 'Sun-relative rule incomplete',
      type: 'Sun-relative',
      recognised: true,
    };
  }
  if (job.scheduleType === SCHEDULE_CRON) {
    return { words: describeCron(job.cronExpression, job.timeZone) || job.cronExpression, type: 'Cron', recognised: true };
  }
  if (job.scheduleType === SCHEDULE_FIXED_DELAY) {
    return { words: describeFixedDelay(job.fixedDelayMs), type: 'Fixed delay', recognised: true };
  }
  return { words: 'Unrecognised schedule type', type: job.scheduleType || '(none)', recognised: false };
}

/** The served job statuses as a label and a {@code StatusDot} tone ("Disabled by Config" is now "Off in config"). */
const STATUS_VIEW = {
  [STATUS_ACTIVE]: { label: 'Active', tone: 'go' },
  [STATUS_PAUSED]: { label: 'Paused', tone: 'amber' },
  [STATUS_OFF]: { label: 'Off in config', tone: 'off' },
};

/**
 * The look of a job's status.
 *
 * @param {string} status {@code ACTIVE}, {@code PAUSED} or {@code DISABLED_BY_CONFIG}
 * @returns {{label: string, tone: string}} an unknown status reads as itself in the quiet ink
 */
export function jobStatusView(status) {
  return STATUS_VIEW[status] ?? { label: status || '—', tone: 'off' };
}

/** The type chips' predicates by value; {@code all} is every job. */
const TYPE_PREDICATES = {
  all: () => true,
  [SCHEDULE_SOLAR]: (job) => job.scheduleType === SCHEDULE_SOLAR,
  [SCHEDULE_CRON]: (job) => job.scheduleType === SCHEDULE_CRON,
  [SCHEDULE_FIXED_DELAY]: (job) => job.scheduleType === SCHEDULE_FIXED_DELAY,
};

/**
 * Whether a job passes the filters: its schedule type chip AND its status chip.
 *
 * @param {object} job a served job
 * @param {{type: string, status: string|null}} filters {@code type} is {@code all} or a schedule type; {@code status}
 *   is null or {@code PAUSED} or {@code DISABLED_BY_CONFIG}
 * @returns {boolean}
 */
export function jobMatchesFilters(job, filters) {
  const type = TYPE_PREDICATES[filters.type] ?? TYPE_PREDICATES.all;
  return type(job) && (filters.status == null || job.status === filters.status);
}

/**
 * How many jobs a type chip would show.
 *
 * @param {Array<object>} jobs every served job
 * @param {string} type {@code all} or a schedule type
 * @returns {number}
 */
export function countByType(jobs, type) {
  const predicate = TYPE_PREDICATES[type] ?? TYPE_PREDICATES.all;
  return jobs.filter(predicate).length;
}

/** A served instant as epoch milliseconds, or null when it is empty or unparseable. */
function instantMs(value) {
  const d = parseUtcInstant(value);
  return d ? d.getTime() : null;
}

/**
 * Sorts jobs for the list.
 *
 * <p>By next fire (the default, soonest first), a job that is not active (paused, off in config) or has no next
 * fire sorts after the active ones whichever way it is sorted, in name order. By name it is a plain name order.
 *
 * @param {Array<object>} jobs the served jobs
 * @param {{key: 'next'|'name', dir: 'asc'|'desc'}} sort the sort
 * @returns {Array<object>} a new, sorted array
 */
export function sortJobs(jobs, sort) {
  const direction = sort.dir === 'desc' ? -1 : 1;
  const byName = (a, b) => a.displayName.localeCompare(b.displayName);
  if (sort.key === 'name') return [...jobs].sort((a, b) => direction * byName(a, b));
  const next = (job) => (job.status === STATUS_ACTIVE ? instantMs(job.nextFireTime) : null);
  return [...jobs].sort((a, b) => {
    const aNext = next(a);
    const bNext = next(b);
    if (aNext == null || bNext == null) {
      if (aNext == null && bNext == null) return byName(a, b);
      return aNext == null ? 1 : -1;
    }
    return aNext === bNext ? byName(a, b) : direction * (aNext - bNext);
  });
}
