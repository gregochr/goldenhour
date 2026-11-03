import { parseUtcInstant } from './conversions.js';
import { UK_ZONE, ukDateStr, ukDayOffset } from './mapDates.js';
import { describeSolarRuleCompact } from './schedulerSolar.js';
import { formatSeconds } from './runsView.js';

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
 * The current UK timezone label: exactly "BST" in summer, "GMT" in winter. It is derived from London's UTC offset at
 * that instant (0 is GMT, anything else BST), not from {@code Intl}'s short name, which some browsers spell "GMT+0" or
 * "GMT+1".
 *
 * @param {Date} [now] the real clock
 * @returns {string} "BST" or "GMT"
 */
export function getUKTimezoneLabel(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: UK_ZONE, year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', hourCycle: 'h23',
  }).formatToParts(now);
  const n = (type) => Number(parts.find((p) => p.type === type)?.value);
  const local = Date.UTC(n('year'), n('month') - 1, n('day'), n('hour'), n('minute'));
  const utc = Math.floor(now.getTime() / 60_000) * 60_000;
  return local - utc === 0 ? 'GMT' : 'BST';
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

/** A single day-of-week field as a DAY_NAMES key: "*" stays "*", MON or 1 become "MON"; anything else is null. */
function normaliseDay(dow) {
  if (dow === '*') return '*';
  const upper = dow.toUpperCase();
  if (DAY_NAMES[upper]) return upper;
  if (/^[0-7]$/.test(dow)) return Object.keys(DAY_NAMES)[(parseInt(dow, 10) + 6) % 7];
  return null;
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

  if (sec === '0' && dom === '*' && mon === '*' && /^\d{1,2}$/.test(min)) {
    const minVal = parseInt(min, 10);
    const isUK = timeZone === 'Europe/London';
    const tzLabel = isUK ? 'UK time' : getUKTimezoneLabel();
    const dayKey = normaliseDay(dow);
    if (hour === '*') {
      return dow === '*' ? `Every hour at :${String(minVal).padStart(2, '0')}` : cron;
    }
    if (dayKey === null || !/^\d{1,2}(,\d{1,2})*$/.test(hour)) return cron;
    const timeStr = hour.split(',')
      .map((h) => (isUK
        ? `${String(parseInt(h, 10)).padStart(2, '0')}:${String(minVal).padStart(2, '0')}`
        : formatUTCAsUK(parseInt(h, 10), minVal)))
      .join(', ');
    if (dayKey === '*') return `Daily at ${timeStr} ${tzLabel}`;
    return `Every ${DAY_NAMES[dayKey]} at ${timeStr} ${tzLabel}`;
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
      words: describeSolarRuleCompact(job.solarEvent, job.solarOffsetMinutes) || 'Sun-relative rule incomplete',
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
 * Whether a job passes the filters: its schedule type chip AND its status chip AND, when on, the "Uses Claude" and
 * "Last run failed" chips.
 *
 * @param {object} job a served job
 * @param {{type: string, status: string|null, claude?: boolean, failed?: boolean}} filters {@code type} is
 *   {@code all} or a schedule type; {@code status} is null or {@code PAUSED} or {@code DISABLED_BY_CONFIG}
 * @returns {boolean}
 */
export function jobMatchesFilters(job, filters) {
  const type = TYPE_PREDICATES[filters.type] ?? TYPE_PREDICATES.all;
  return type(job)
    && (filters.status == null || job.status === filters.status)
    && (!filters.claude || job.catalogue?.usesClaude === true)
    && (!filters.failed || job.lastFire?.status === 'FAILED');
}

/**
 * Whether a job matches the search box: the text in its name, its plain alias, what it does or its purpose tag.
 * Filtering the served words for display is a display filter, not a derivation.
 *
 * @param {object} job a served job
 * @param {string} text the search text
 * @returns {boolean} true for blank text
 */
export function jobMatchesSearch(job, text) {
  const needle = text.trim().toLowerCase();
  if (needle === '') return true;
  const c = job.catalogue;
  // "Uses Claude" is a chip, so the search the placeholder suggests ("Claude") finds those jobs too.
  if (needle === 'claude' && c?.usesClaude === true) return true;
  return [job.displayName, c?.alias, c?.what, c?.purpose].some((field) => (field ?? '').toLowerCase().includes(needle));
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

/** The groups of the All jobs list, in order: the type, its heading and the subtitle under it. */
export const JOB_GROUPS = [
  { type: SCHEDULE_SOLAR, label: 'Sun-relative', subtitle: 'fires a set time before sunrise or sunset' },
  { type: SCHEDULE_CRON, label: 'Cron', subtitle: 'fixed clock times' },
  { type: SCHEDULE_FIXED_DELAY, label: 'Fixed delay', subtitle: 'repeats every few minutes, all day' },
];

/** The trailing group for a job whose schedule type this screen does not know, so it is never hidden. */
const OTHER_GROUP = { type: 'OTHER', label: 'Other', subtitle: 'schedule type not recognised' };

/**
 * Splits jobs into the list's groups (Sun-relative, Cron, Fixed delay, then Other), each sorted by next fire. Empty
 * groups are left out.
 *
 * @param {Array<object>} jobs the jobs to show
 * @returns {Array<{type: string, label: string, subtitle: string, jobs: Array<object>}>}
 */
export function groupJobs(jobs) {
  const known = JOB_GROUPS.map((group) => group.type);
  const byNext = { key: 'next', dir: 'asc' };
  return [...JOB_GROUPS, OTHER_GROUP]
    .map((group) => ({
      ...group,
      jobs: sortJobs(
        jobs.filter((job) => (group === OTHER_GROUP ? !known.includes(job.scheduleType) : job.scheduleType === group.type)),
        byNext,
      ),
    }))
    .filter((group) => group.jobs.length > 0);
}

/**
 * The UK clock time of an instant, "HH:MM".
 *
 * @param {string|null|undefined} instant an ISO instant
 * @returns {string} the time, or an empty string for none
 */
export function formatClock(instant) {
  const d = parseUtcInstant(instant);
  return d ? CLOCK_FORMAT.format(d) : '';
}

/**
 * A fire's label for the panel's next fires: "Today 12:27", "Tomorrow 12:27", else "Wed 4 Nov 12:25".
 *
 * @param {string|null|undefined} instant the fire instant
 * @param {Date} [now] the real clock
 * @returns {string}
 */
export function formatFireLabel(instant, now = new Date()) {
  const d = parseUtcInstant(instant);
  if (!d) return '';
  const clock = CLOCK_FORMAT.format(d);
  const ahead = ukDayOffset(ukDateStr(d), now);
  if (ahead === 0) return `Today ${clock}`;
  if (ahead === 1) return `Tomorrow ${clock}`;
  return `${WEEKDAY_FORMAT.format(d)} ${DAY_MONTH_FORMAT.format(d)} ${clock}`;
}

/** The served fire statuses as a label and a {@code StatusDot} tone. */
const FIRE_STATUS_VIEW = {
  SUCCEEDED: { label: 'OK', tone: 'go' },
  PARTIAL: { label: 'Partial', tone: 'amber' },
  FAILED: { label: 'Failed', tone: 'coral' },
  SKIPPED: { label: 'Skipped', tone: 'off' },
  RUNNING: { label: 'Running', tone: 'tide', halo: true },
};

/**
 * The look of a fire's status.
 *
 * @param {string} status {@code SUCCEEDED}, {@code PARTIAL}, {@code FAILED}, {@code SKIPPED} or {@code RUNNING}
 * @returns {{label: string, tone: string, halo?: boolean}} an unknown status reads as itself in the quiet ink
 */
export function fireStatusView(status) {
  return FIRE_STATUS_VIEW[status] ?? { label: status || '—', tone: 'off' };
}

/**
 * How long a fire took, in the one duration form the app uses ({@code formatSeconds}: "55s", "2m 5s", "1h 2m"), or
 * "<1s". Empty for a fire still running or with no end.
 *
 * @param {{firedAt: string, completedAt: string|null}} fire the served last fire
 * @returns {string}
 */
export function fireTook(fire) {
  const start = instantMs(fire?.firedAt);
  const end = instantMs(fire?.completedAt);
  if (start == null || end == null || end < start) return '';
  const total = Math.round((end - start) / 1000);
  return total < 1 ? '<1s' : formatSeconds(total);
}
