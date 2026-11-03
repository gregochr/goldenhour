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

/** A row's height, px: 24 on desktop and tablet, 44 (a touch target) on a phone. */
export const DESKTOP_ROW_HEIGHT = 24;
export const PHONE_ROW_HEIGHT = 44;

/** The row height for the viewport; geometry takes it as a parameter, never a constant. */
export const rowHeightFor = (isMobile) => (isMobile ? PHONE_ROW_HEIGHT : DESKTOP_ROW_HEIGHT);

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
 * @param {number} [minute] the wall-clock minute, 0-59
 * @returns {number} epoch milliseconds
 */
export function ukWallInstant(date, hour, minute = 0) {
  const [y, m, d] = date.split('-').map(Number);
  const guess = Date.UTC(y, m - 1, d, hour, minute);
  const first = guess - ukOffsetMinutes(guess) * MS_PER_MINUTE;
  const second = guess - ukOffsetMinutes(first) * MS_PER_MINUTE;
  return second;
}

/**
 * The gridlines: one every {@code stepHours} UK wall-clock hours from {@code fromHour} to {@code toHour}, each with its
 * UK label ("03:00", "02:30"). The last one is the axis end, labelled by {@code toHour} ("24:00" for the whole day). On
 * a 23- or 25-hour day the whole-day lines are unevenly spaced in time (correctly: a wall-clock hour is missing or
 * doubled).
 *
 * @param {{startMs: number, endMs: number}} axis the axis (the whole day, or the close-up)
 * @param {number} [stepHours] the spacing in wall-clock hours (0.5 for the close-up)
 * @param {{fromHour?: number, toHour?: number}} [range] the wall-clock span the axis covers, default 0 to 24
 * @returns {Array<{ms: number, label: string}>}
 */
export function gridlines(axis, stepHours = GRID_STEP_HOURS, { fromHour = 0, toHour = HOURS_PER_DAY } = {}) {
  const date = ukDateStr(new Date(axis.startMs));
  const label = (minutes) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
  const lines = [];
  const stepMinutes = Math.round(stepHours * 60);
  for (let minutes = fromHour * 60; minutes < toHour * 60; minutes += stepMinutes) {
    lines.push({ ms: ukWallInstant(date, Math.floor(minutes / 60), minutes % 60), label: label(minutes) });
  }
  lines.push({ ms: axis.endMs, label: label(toHour * 60) });
  return lines;
}

/** The close-up's UK wall-clock span (D12: "02:00-05:00 close-up"), where the overnight cluster is readable. */
export const CLOSE_UP_FROM_HOUR = 2;
export const CLOSE_UP_TO_HOUR = 5;

/** The close-up's gridline spacing, in hours (every 30 minutes). */
export const CLOSE_UP_STEP_HOURS = 0.5;

/** The two zoom levels of the picture. */
export const ZOOM_DAY = 'day';
export const ZOOM_CLOSE_UP = 'closeup';

/**
 * The close-up axis: 02:00 to 05:00 UK wall-clock on the served date, so it is the right three hours on a clock-change
 * day too (it starts and ends after the 01:00 change, where every wall time exists once).
 *
 * @param {{dayStart: string, dayEnd: string}} day the served day
 * @returns {{startMs: number, endMs: number}|null} null when the day has no axis
 */
export function closeUpAxis(day) {
  const whole = dayAxis(day);
  if (!whole) return null;
  const date = ukDateStr(new Date(whole.startMs));
  return { startMs: ukWallInstant(date, CLOSE_UP_FROM_HOUR), endMs: ukWallInstant(date, CLOSE_UP_TO_HOUR) };
}

/**
 * The axis and gridlines for a zoom level.
 *
 * @param {object} day the served day
 * @param {string} zoom {@link ZOOM_DAY} or {@link ZOOM_CLOSE_UP}
 * @returns {{axis: {startMs: number, endMs: number}, grid: Array<{ms: number, label: string}>}|null}
 */
export function pictureAxis(day, zoom) {
  if (zoom === ZOOM_CLOSE_UP) {
    const axis = closeUpAxis(day);
    return axis ? { axis, grid: gridlines(axis, CLOSE_UP_STEP_HOURS, { fromHour: CLOSE_UP_FROM_HOUR, toHour: CLOSE_UP_TO_HOUR }) } : null;
  }
  const axis = dayAxis(day);
  return axis ? { axis, grid: gridlines(axis) } : null;
}

/** Where the reader's zoom choice is remembered (a convenience, never in the address). */
export const ZOOM_STORAGE_KEY = 'photocast.scheduler.dayZoom';

/** The remembered zoom, or the whole day when nothing usable is stored or storage is unavailable. */
export function readZoom() {
  try {
    const value = window.localStorage.getItem(ZOOM_STORAGE_KEY);
    return value === ZOOM_CLOSE_UP ? ZOOM_CLOSE_UP : ZOOM_DAY;
  } catch {
    return ZOOM_DAY;
  }
}

/** Remembers the zoom; a storage that is unavailable or full is ignored. */
export function writeZoom(zoom) {
  try {
    window.localStorage.setItem(ZOOM_STORAGE_KEY, zoom);
  } catch {
    // Not remembered: the choice still applies for this visit.
  }
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
 * The shape of a scheduled fire's mark (D12): a thin tick when the mark would touch either neighbour (the next fire
 * closer than its own bar, the previous fire's bar reaching it, or either closer than 3px), a diamond when its bar
 * would be narrower than 6px, else a bar. Both fires of a crowded pair are ticks.
 *
 * @param {{widthPx: number, gapPrevPx?: number, gapNextPx?: number, prevWidthPx?: number}} mark the bar's width, the
 *   gap to the previous and next mark on the row, and the previous mark's bar width
 * @returns {'tick'|'diamond'|'bar'}
 */
export function markShape({
  widthPx, gapPrevPx = Infinity, gapNextPx = Infinity, prevWidthPx = 0,
}) {
  const hitsNext = gapNextPx < Math.max(widthPx, TICK_MIN_GAP_PX);
  const hitsPrev = gapPrevPx < Math.max(prevWidthPx, TICK_MIN_GAP_PX);
  if (hitsNext || hitsPrev) return 'tick';
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

/** Whether a fire is in flight and drawn growing to Now (only on today, where Now is on the axis). */
const growsToNow = (fire, isToday) => isToday && fire?.status === 'RUNNING' && instantMs(fire.startedAt) != null
  && instantMs(fire.completedAt) == null;

/** Where a fire's mark starts: its slot, or for a running fire the moment it actually started. */
export function fireStartMs(fire) {
  return fire?.status === 'RUNNING' ? instantMs(fire.startedAt) ?? instantMs(fire.at) : instantMs(fire?.at);
}

/**
 * The bar's width in px for a fire: its duration across the axis, 0 when it has none (it is then a diamond). A running
 * fire on today grows from its start to Now.
 *
 * @param {object} fire the served fire
 * @param {{startMs: number, endMs: number}} axis the day axis
 * @param {number} plotWidth the plot width in px
 * @param {{nowMs?: number, isToday?: boolean}} [clock] the clock, for a running fire
 */
export function fireWidthPx(fire, axis, plotWidth, { nowMs = 0, isToday = false } = {}) {
  const span = axis.endMs - axis.startMs;
  if (growsToNow(fire, isToday)) {
    return Math.max(0, ((nowMs - instantMs(fire.startedAt)) / span) * plotWidth);
  }
  const seconds = fireDurationSeconds(fire);
  if (seconds == null) return 0;
  return (seconds * 1000 / span) * plotWidth;
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
const NEUTRAL_KIND = { fill: 'fill-plex-text-muted', stroke: 'stroke-plex-text-muted', swatch: 'bg-plex-text-muted' };
const KIND_STYLES = {
  SOLAR_OFFSET: { fill: 'fill-scheduler-sun', stroke: 'stroke-scheduler-sun', swatch: 'bg-scheduler-sun' },
  CRON: { fill: 'fill-tide', stroke: 'stroke-tide', swatch: 'bg-tide' },
};

/** The mark colour for a schedule type; a job whose type is not (yet) known is drawn neutral, never as cron. */
export function kindStyle(scheduleType) {
  return KIND_STYLES[scheduleType] ?? NEUTRAL_KIND;
}

/** Whether a fire is still to come (served {@code SCHEDULED}). */
export const isFuture = (fire) => fire?.status === 'SCHEDULED';

/**
 * The picture's rows: one per {@code SCHEDULED} job that fires that day, sorted by first fire, then name. The server
 * serves a paused job's would-be fires (status {@code PAUSED}, kept apart in {@code wouldBe}), so a job the
 * {@code /jobs} list says is paused keeps a row whether or not the day lists it (a muted "Paused" when it has no
 * would-be fire); a paused fixed-delay job is repeating, not a row. The job's schedule type, paused state and Claude
 * flag come from the list, which the view already loads; until it has, rows are neutral.
 *
 * @param {object|null} day the served day
 * @param {Array<object>} jobs the served jobs list, joined by job key
 * @returns {Array<{jobKey: string, name: string, fires: Array<object>, wouldBe: Array<object>, scheduleType: string|null,
 *   paused: boolean, usesClaude: boolean, alias: string|null}>}
 */
export function buildRows(day, jobs) {
  const list = jobs ?? [];
  const byKey = new Map(list.map((job) => [job.jobKey, job]));
  const entries = (day?.jobs ?? []).filter((entry) => entry.kind === 'SCHEDULED');
  const known = new Set((day?.jobs ?? []).map((entry) => entry.jobKey));
  const missing = day == null ? [] : list
    .filter((job) => job.status === 'PAUSED' && job.scheduleType !== 'FIXED_DELAY' && !known.has(job.jobKey))
    .map((job) => ({ jobKey: job.jobKey, displayName: job.displayName, fires: [] }));
  const rows = [...entries, ...missing]
    .map((entry) => {
      const meta = byKey.get(entry.jobKey);
      const sorted = [...(entry.fires ?? [])].sort((a, b) => (instantMs(a.at) ?? 0) - (instantMs(b.at) ?? 0));
      // A paused job's would-be fires arrive as fires with the status PAUSED: they are drawn apart, never as runs.
      const fires = sorted.filter((fire) => fire.status !== 'PAUSED');
      const wouldBe = sorted.filter((fire) => fire.status === 'PAUSED');
      return {
        jobKey: entry.jobKey,
        name: entry.displayName,
        fires,
        wouldBe,
        scheduleType: meta?.scheduleType ?? null,
        paused: meta?.status === 'PAUSED',
        usesClaude: meta?.catalogue?.usesClaude === true,
        alias: meta?.catalogue?.alias ?? null,
      };
    })
    .filter((row) => row.fires.length > 0 || row.wouldBe.length > 0 || row.paused);
  const first = (row) => {
    const head = row.fires[0] ?? row.wouldBe[0];
    return head ? instantMs(head.at) ?? Infinity : Infinity;
  };
  return rows.sort((a, b) => first(a) - first(b) || a.name.localeCompare(b.name));
}

/** A row's last fire that has happened (not still to run), the newest by time. */
export function lastHappened(row) {
  const done = row.fires.filter((fire) => !isFuture(fire) && fire.status !== 'MISSED');
  return done.length > 0 ? done[done.length - 1] : null;
}

/**
 * A row label's accessible name: "{name}, last run OK at 01:00, took 55s, missed at 04:00, paused, uses Claude, {alias}".
 * A row with nothing run yet says when it fires next; a row whose only fire was missed says so (never "no fires").
 *
 * @param {object} row a row from {@link buildRows}
 * @returns {string}
 */
export function rowAriaLabel(row) {
  const parts = [row.name];
  const last = lastHappened(row);
  const missed = row.fires.filter((fire) => fire.status === 'MISSED');
  if (last) {
    const took = formatFireDuration(last);
    parts.push(`last run ${fireStatusStyle(last.status).label.toLowerCase()} at ${formatClock(last.at)}${took ? `, took ${took}` : ''}`);
  }
  if (missed.length > 0) parts.push(`missed at ${missed.map((fire) => formatClock(fire.at)).join(' and ')}`);
  if (!last && missed.length === 0) {
    const next = row.fires.find(isFuture);
    if (next) parts.push(`next fire ${formatClock(next.at)}`);
    else if (!row.paused) parts.push('no fires');
  }
  if (row.paused) parts.push('paused');
  const wouldBe = row.wouldBe ?? [];
  if (wouldBe.length > 0) {
    parts.push(`would-be ${wouldBe.length === 1 ? 'fire' : 'fires'} at ${wouldBe.map((fire) => formatClock(fire.at)).join(' and ')}`);
  }
  if (row.usesClaude) parts.push('uses Claude');
  if (row.alias) parts.push(row.alias);
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
 * order. The list shows the newest {@code limit}, oldest of those first; {@code more} is the older ones, including the
 * problems the server left out of its own cap ({@code problemsOmitted}).
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
  // The newest few, shown oldest first: a day's late failure must not be hidden behind a flood of early ones.
  const items = all.slice(Math.max(0, all.length - limit));
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
