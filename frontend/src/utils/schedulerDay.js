import { parseUtcInstant } from './conversions.js';
import { UK_ZONE, shiftDateStr, ukDateStr, ukDayOffset } from './mapDates.js';
import { formatClock } from './schedulerView.js';
import { formatSeconds } from './runsView.js';

/**
 * Pure geometry and view-model helpers for the Scheduler's Day picture (scheduler-redesign-plan.md D12, S5a).
 *
 * <p>The day axis is the UK civil day built from the two served instants ({@code dayStart}, {@code dayEnd}), so it
 * is 23 or 25 hours long on a clock-change day and every time-to-x mapping goes through instants, never "minutes
 * since 00:00". Gridlines sit at UK wall-clock hours and are labelled from them.
 *
 * <p>⚠️ Every clock read here is the real clock ({@code new Date()}) or an argument, never {@code appNow()} (R-D7).
 */

/** Spacing of the gridlines, in UK wall-clock hours. */
export const GRID_STEP_HOURS = 3;

/** A bar narrower than this many px is drawn as a diamond (D12). */
export const DIAMOND_MIN_BAR_PX = 6;

/** The half-width of a diamond, px. */
export const DIAMOND_HALF_PX = 6;

/** Marks closer together than this many px are drawn as thin ticks, not diamonds or bars (D12). */
export const TICK_MIN_GAP_PX = 3;

/** The most "Needs a look" rows shown before "and n more". */
export const NEEDS_A_LOOK_LIMIT = 8;

/** How many days either side of today the day navigation reaches (the server answers 400 beyond it). */
export const DAY_RANGE_DAYS = 7;

const MS_PER_MINUTE = 60_000;
const HOURS_PER_DAY = 24;

const LONDON_PARTS = new Intl.DateTimeFormat('en-GB', {
  timeZone: UK_ZONE, year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', hourCycle: 'h23',
});
const WEEKDAY = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'short' });
const DAY_MONTH = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', day: 'numeric', month: 'short' });

/** A served instant as epoch milliseconds, or null when empty or unparseable. */
export function instantMs(value) {
  const d = parseUtcInstant(value);
  return d ? d.getTime() : null;
}

/**
 * The day axis: the half-open span {@code [startMs, endMs)} of the served UK civil day.
 *
 * @param {{dayStart: string, dayEnd: string}} day the served day
 * @returns {{startMs: number, endMs: number}|null} null when either instant is missing or the span is empty
 */
export function dayAxis(day) {
  const startMs = instantMs(day?.dayStart);
  const endMs = instantMs(day?.dayEnd);
  return startMs != null && endMs != null && endMs > startMs ? { startMs, endMs } : null;
}

/**
 * An instant's x position across the axis, in px from the plot's left edge. Not clamped: an instant outside the axis
 * lands outside {@code [0, width]}.
 *
 * @param {number} ms the instant
 * @param {{startMs: number, endMs: number}} axis the axis (the whole day, or any window)
 * @param {number} width the plot width in px
 * @returns {number}
 */
export function timeToX(ms, axis, width) {
  return ((ms - axis.startMs) / (axis.endMs - axis.startMs)) * width;
}

/** Whether an instant is on the axis. */
export function onAxis(ms, axis) {
  return ms != null && ms >= axis.startMs && ms <= axis.endMs;
}

/** London's UTC offset at an instant, in minutes (0 in winter, 60 in summer). */
function ukOffsetMinutes(ms) {
  const parts = LONDON_PARTS.formatToParts(new Date(ms));
  const n = (type) => Number(parts.find((p) => p.type === type)?.value);
  const local = Date.UTC(n('year'), n('month') - 1, n('day'), n('hour'), n('minute'));
  return Math.round((local - Math.floor(ms / MS_PER_MINUTE) * MS_PER_MINUTE) / MS_PER_MINUTE);
}

/**
 * The instant of a UK wall-clock hour on a UK date. A wall time that exists once (every multiple of the grid step,
 * which sits clear of the 01:00-02:00 change) resolves exactly.
 *
 * @param {string} date the UK date, {@code YYYY-MM-DD}
 * @param {number} hour the wall-clock hour, 0-23
 * @returns {number} epoch milliseconds
 */
export function ukWallInstant(date, hour) {
  const [y, m, d] = date.split('-').map(Number);
  const guess = Date.UTC(y, m - 1, d, hour);
  const first = guess - ukOffsetMinutes(guess) * MS_PER_MINUTE;
  const second = guess - ukOffsetMinutes(first) * MS_PER_MINUTE;
  return second;
}

/**
 * The gridlines: one every {@code stepHours} UK wall-clock hours from 00:00 to the day's end, each with its UK label.
 * The last one is the day's end, labelled "24:00". On a 23- or 25-hour day the lines are unevenly spaced in time
 * (correctly: a wall-clock hour is missing or doubled).
 *
 * @param {{startMs: number, endMs: number}} axis the day axis
 * @param {number} [stepHours] the spacing in wall-clock hours
 * @returns {Array<{ms: number, label: string}>}
 */
export function gridlines(axis, stepHours = GRID_STEP_HOURS) {
  const date = ukDateStr(new Date(axis.startMs));
  const lines = [];
  for (let hour = 0; hour < HOURS_PER_DAY; hour += stepHours) {
    lines.push({ ms: ukWallInstant(date, hour), label: `${String(hour).padStart(2, '0')}:00` });
  }
  lines.push({ ms: axis.endMs, label: '24:00' });
  return lines;
}

/**
 * The night bands: from the day's start to sunrise, and from sunset to the day's end. A missing sun event (the sun
 * does not rise or set that day) leaves its band out.
 *
 * @param {{startMs: number, endMs: number}} axis the day axis
 * @param {string|null} sunrise the served sunrise instant
 * @param {string|null} sunset the served sunset instant
 * @returns {Array<{startMs: number, endMs: number}>}
 */
export function nightBands(axis, sunrise, sunset) {
  const rise = instantMs(sunrise);
  const set = instantMs(sunset);
  const bands = [];
  if (rise != null && rise > axis.startMs) bands.push({ startMs: axis.startMs, endMs: Math.min(rise, axis.endMs) });
  if (set != null && set < axis.endMs) bands.push({ startMs: Math.max(set, axis.startMs), endMs: axis.endMs });
  return bands;
}

/**
 * The shape of a scheduled fire's mark (D12): a thin tick when its neighbour is closer than 3 px, a diamond when its
 * bar would be narrower than 6 px, else a bar.
 *
 * @param {{widthPx: number, gapPx?: number}} mark the bar's width and the gap to the next mark on the row
 * @returns {'tick'|'diamond'|'bar'}
 */
export function markShape({ widthPx, gapPx = Infinity }) {
  if (gapPx < TICK_MIN_GAP_PX) return 'tick';
  return widthPx < DIAMOND_MIN_BAR_PX ? 'diamond' : 'bar';
}

/**
 * How a run of repeating marks is drawn: individual ticks, or one continuous band when they would sit closer than 3 px.
 * (Used by the Repeating section, S5b.)
 *
 * @param {number} gapPx the px between two consecutive fires
 * @returns {'ticks'|'band'}
 */
export function repeatingShape(gapPx) {
  return gapPx < TICK_MIN_GAP_PX ? 'band' : 'ticks';
}

/** A fire's start-to-end duration in whole seconds, or null (not started, not finished, or the wrong way round). */
export function fireDurationSeconds(fire) {
  const start = instantMs(fire?.startedAt);
  const end = instantMs(fire?.completedAt);
  if (start == null || end == null || end < start) return null;
  return Math.round((end - start) / 1000);
}

/** A fire's duration in the one form the app uses ("55s", "2m 5s"), "<1s", or an empty string for none. */
export function formatFireDuration(fire) {
  const seconds = fireDurationSeconds(fire);
  if (seconds == null) return '';
  return seconds < 1 ? '<1s' : formatSeconds(seconds);
}

/** The bar's width in px for a fire: its duration across the axis, 0 when it has none (it is then a diamond). */
export function fireWidthPx(fire, axis, plotWidth) {
  const seconds = fireDurationSeconds(fire);
  if (seconds == null) return 0;
  return (seconds * 1000 / (axis.endMs - axis.startMs)) * plotWidth;
}

/**
 * Colours, words and hollowness for a fire's status. Class names are whole literals so Tailwind keeps them.
 * {@code hollow} marks a fire that has not (or did not) happen: still to run, or missed.
 */
const STATUS_STYLES = {
  SUCCEEDED: { label: 'OK', tone: 'go', fill: 'fill-plex-ok', stroke: 'stroke-plex-ok', hollow: false },
  PARTIAL: { label: 'Partial', tone: 'amber', fill: 'fill-verdict-marginal', stroke: 'stroke-verdict-marginal', hollow: false },
  FAILED: { label: 'Failed', tone: 'coral', fill: 'fill-plex-coral', stroke: 'stroke-plex-coral', hollow: false },
  SKIPPED: { label: 'Skipped', tone: 'off', fill: 'fill-plex-text-faint', stroke: 'stroke-plex-text-faint', hollow: false },
  RUNNING: { label: 'Running', tone: 'tide', fill: 'fill-tide', stroke: 'stroke-tide', hollow: false },
  MISSED: { label: 'Missed', tone: 'coral', fill: 'fill-transparent', stroke: 'stroke-plex-coral', hollow: true },
  SCHEDULED: { label: 'To run', tone: 'off', fill: 'fill-transparent', stroke: 'stroke-plex-text-muted', hollow: true },
};

/**
 * The look of a fire's status on the picture.
 *
 * @param {string} status a served fire status
 * @returns {{label: string, tone: string, fill: string, stroke: string, hollow: boolean}} an unknown status reads as
 *   a quiet, hollow "to run"
 */
export function fireStatusStyle(status) {
  return STATUS_STYLES[status] ?? { ...STATUS_STYLES.SCHEDULED, label: status || '—' };
}

/** A row's mark colour by schedule type: sun-relative gold, cron tide. Whole literals for Tailwind. */
const KIND_STYLES = {
  SOLAR_OFFSET: { fill: 'fill-scheduler-sun', stroke: 'stroke-scheduler-sun', swatch: 'bg-scheduler-sun' },
  CRON: { fill: 'fill-tide', stroke: 'stroke-tide', swatch: 'bg-tide' },
};

/** The mark colour for a schedule type (a job whose type is unknown reads as cron). */
export function kindStyle(scheduleType) {
  return KIND_STYLES[scheduleType] ?? KIND_STYLES.CRON;
}

/** Whether a fire is still to come (served {@code SCHEDULED}). */
export const isFuture = (fire) => fire?.status === 'SCHEDULED';

/**
 * The picture's rows: one per {@code SCHEDULED} job that fires that day (or is paused, which keeps its dashed row),
 * sorted by first fire, then name. The job's schedule type, paused state and Claude flag come from the {@code /jobs}
 * list, which the view already loads.
 *
 * @param {object|null} day the served day
 * @param {Array<object>} jobs the served jobs list, joined by job key
 * @returns {Array<{jobKey: string, name: string, fires: Array<object>, scheduleType: string|null, paused: boolean,
 *   usesClaude: boolean, alias: string|null}>}
 */
export function buildRows(day, jobs) {
  const byKey = new Map((jobs ?? []).map((job) => [job.jobKey, job]));
  const rows = (day?.jobs ?? [])
    .filter((entry) => entry.kind === 'SCHEDULED')
    .map((entry) => {
      const meta = byKey.get(entry.jobKey);
      const fires = [...(entry.fires ?? [])].sort((a, b) => (instantMs(a.at) ?? 0) - (instantMs(b.at) ?? 0));
      return {
        jobKey: entry.jobKey,
        name: entry.displayName,
        fires,
        scheduleType: meta?.scheduleType ?? null,
        paused: meta?.status === 'PAUSED',
        usesClaude: meta?.catalogue?.usesClaude === true,
        alias: meta?.catalogue?.alias ?? null,
      };
    })
    .filter((row) => row.fires.length > 0 || row.paused);
  const first = (row) => (row.fires.length > 0 ? instantMs(row.fires[0].at) ?? Infinity : Infinity);
  return rows.sort((a, b) => first(a) - first(b) || a.name.localeCompare(b.name));
}

/** A row's last fire that has happened (not still to run), the newest by time. */
export function lastHappened(row) {
  const done = row.fires.filter((fire) => !isFuture(fire) && fire.status !== 'MISSED');
  return done.length > 0 ? done[done.length - 1] : null;
}

/**
 * A row label's accessible name: "{name}, last run OK at 01:00, took 55s", with "uses Claude" and "paused". A row with
 * nothing run yet says when it fires next ("next fire 12:27").
 *
 * @param {object} row a row from {@link buildRows}
 * @returns {string}
 */
export function rowAriaLabel(row) {
  const parts = [row.name];
  const last = lastHappened(row);
  if (last) {
    const took = formatFireDuration(last);
    parts.push(`last run ${fireStatusStyle(last.status).label.toLowerCase()} at ${formatClock(last.at)}${took ? `, took ${took}` : ''}`);
  } else {
    const next = row.fires.find(isFuture);
    parts.push(next ? `next fire ${formatClock(next.at)}` : 'no fires');
  }
  if (row.paused) parts.push('paused');
  if (row.usesClaude) parts.push('uses Claude');
  return parts.join(', ');
}

/**
 * The summary bar's counts over the scheduled (non-repeating) jobs' fires.
 *
 * @param {object|null} day the served day
 * @returns {{succeeded: number, partial: number, failed: number, missed: number, running: number, skipped: number,
 *   toRun: number}}
 */
export function summaryCounts(day) {
  const counts = { succeeded: 0, partial: 0, failed: 0, missed: 0, running: 0, skipped: 0, toRun: 0 };
  const key = {
    SUCCEEDED: 'succeeded', PARTIAL: 'partial', FAILED: 'failed', MISSED: 'missed', RUNNING: 'running', SKIPPED: 'skipped',
    SCHEDULED: 'toRun',
  };
  (day?.jobs ?? []).filter((entry) => entry.kind === 'SCHEDULED').forEach((entry) => {
    (entry.fires ?? []).forEach((fire) => {
      if (key[fire.status]) counts[key[fire.status]] += 1;
    });
  });
  return counts;
}

/** The statuses "Needs a look" lists. */
const NEEDS_LOOK_STATUSES = ['FAILED', 'PARTIAL', 'MISSED'];

/**
 * "Needs a look": FAILED, PARTIAL and MISSED fires of the scheduled rows, plus the repeating jobs' problems, in time
 * order. The list shows the first {@code limit}; {@code more} is everything beyond them, including the problems the
 * server left out of its own cap ({@code problemsOmitted}).
 *
 * @param {object|null} day the served day
 * @param {number} [limit] the most rows shown
 * @returns {{items: Array<object>, total: number, more: number}} each item is {@code {jobKey, name, at, status,
 *   summary, repeating}}
 */
export function needsALook(day, limit = NEEDS_A_LOOK_LIMIT) {
  const all = [];
  let omitted = 0;
  (day?.jobs ?? []).forEach((entry) => {
    if (entry.kind === 'SCHEDULED') {
      (entry.fires ?? []).filter((fire) => NEEDS_LOOK_STATUSES.includes(fire.status)).forEach((fire) => {
        all.push({
          jobKey: entry.jobKey, name: entry.displayName, at: fire.at, status: fire.status, summary: fire.summary ?? '', repeating: false,
        });
      });
    } else {
      (entry.problems ?? []).forEach((problem) => {
        all.push({
          jobKey: entry.jobKey, name: entry.displayName, at: problem.at, status: problem.status, summary: problem.summary ?? '', repeating: true,
        });
      });
      omitted += entry.problemsOmitted ?? 0;
    }
  });
  all.sort((a, b) => (instantMs(a.at) ?? 0) - (instantMs(b.at) ?? 0));
  const items = all.slice(0, limit);
  return { items, total: all.length + omitted, more: all.length + omitted - items.length };
}

/** An ISO date's "Fri 30 Oct". */
function shortDate(date) {
  const noon = new Date(`${date}T12:00:00Z`);
  return `${WEEKDAY.format(noon)} ${DAY_MONTH.format(noon)}`;
}

/**
 * The Day tab's sub-label and the day navigation's word: "Today", "Tomorrow", "Yesterday", else "Fri 30 Oct".
 *
 * @param {string} date the viewed UK date
 * @param {string} today today's UK date
 * @returns {string}
 */
export function dayWord(date, today) {
  const offset = ukDayOffset(date, new Date(`${today}T12:00:00Z`));
  if (offset === 0) return 'Today';
  if (offset === 1) return 'Tomorrow';
  if (offset === -1) return 'Yesterday';
  return shortDate(date);
}

/**
 * The day's name in the link into Runs ("Open {today|yesterday|Fri 30 Oct} in Runs →"), and null for a future day,
 * which has nothing in Runs yet (the link is hidden).
 *
 * @param {string} date the viewed UK date
 * @param {string} today today's UK date
 * @returns {string|null}
 */
export function runsLinkLabel(date, today) {
  if (date > today) return null;
  const word = dayWord(date, today);
  return word === 'Today' || word === 'Yesterday' ? word.toLowerCase() : word;
}

/** The weekday and date of a viewed day ("Mon 2 Nov"), for the navigation. */
export function dayDateLabel(date) {
  return shortDate(date);
}

/**
 * Whether a date is within the navigation's reach of today ({@code ±7 days}, the server's own limit).
 *
 * @param {string} date an ISO date
 * @param {string} today today's UK date
 */
export function dayInRange(date, today) {
  return date >= shiftDateStr(today, -DAY_RANGE_DAYS) && date <= shiftDateStr(today, DAY_RANGE_DAYS);
}
