import { UK_ZONE } from './mapDates.js';
import { STATUS_ACTIVE, formatClock, formatFireLabel } from './schedulerView.js';

/**
 * Pure helpers for the Scheduler's three schedule editors (scheduler-redesign-plan.md S4b): the slider's bounds, the
 * cron editor's UK times and day toggles, the fixed-delay presets and the save toast.
 *
 * <p>⚠️ Nothing here builds a cron expression. The client reads a stored cron into UK clock times for display and
 * sends the times to {@code POST .../schedule-preview}; the cron that is saved is the one the server built (D8).
 * Every clock read takes "now" as an argument defaulting to the real clock, never {@code appNow()}.
 */

/** The days of the week as the server's {@code daysOfWeek} spells them, Monday first. */
export const DAY_CODES = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

/** The day toggles' labels. */
export const DAY_LABELS = { MON: 'Mon', TUE: 'Tue', WED: 'Wed', THU: 'Thu', FRI: 'Fri', SAT: 'Sat', SUN: 'Sun' };

/** The sun-relative slider's smallest offset in minutes. */
export const SLIDER_MIN = 30;

/** The sun-relative slider's largest offset in minutes (8 hours). */
export const SLIDER_MAX = 480;

/** The sun-relative slider's step in minutes. */
export const SLIDER_STEP = 5;

/** The slider's fixed range. A stored offset the grid cannot hold never moves it (see {@link snapToGrid}). */
export const SLIDER_BOUNDS = Object.freeze({ min: SLIDER_MIN, max: SLIDER_MAX, step: SLIDER_STEP });

/**
 * The on-grid offset nearest a value, clamped to 30 to 480: where the slider's thumb sits for a stored offset the grid
 * cannot hold, and the value the offset becomes once the admin moves the slider or changes the event.
 *
 * @param {number} offset minutes
 * @returns {number} a multiple of 5 from 30 to 480
 */
export function snapToGrid(offset) {
  const steps = Math.round((offset - SLIDER_MIN) / SLIDER_STEP);
  return Math.min(SLIDER_MAX, Math.max(SLIDER_MIN, SLIDER_MIN + steps * SLIDER_STEP));
}

/**
 * Whether the slider's grid can hold an offset exactly.
 *
 * @param {number} offset minutes
 * @returns {boolean} true for a multiple of 5 from 30 to 480
 */
export function isOnGrid(offset) {
  return Number.isInteger(offset) && snapToGrid(offset) === offset;
}

/**
 * A duration in the compact words the panel uses, without the event: 243 gives "4h 3m", 0 gives "0m".
 *
 * @param {number} minutes whole minutes
 * @returns {string} the span
 */
export function formatOffsetSpan(minutes) {
  const total = Math.round(minutes);
  const h = Math.floor(total / 60);
  const m = total % 60;
  return [h > 0 ? `${h}h` : '', m > 0 || h === 0 ? `${m}m` : ''].filter(Boolean).join(' ');
}

/** The Spring cron's day-of-week field as day codes: {@code *} is null days, a list of names or digits its codes. */
function parseDays(dow) {
  if (dow === '*') return { days: null };
  const days = [];
  for (const part of dow.split(',')) {
    const upper = part.toUpperCase();
    if (DAY_CODES.includes(upper)) days.push(upper);
    else if (/^[0-7]$/.test(part)) days.push(DAY_CODES[(parseInt(part, 10) + 6) % 7]);
    else return null;
  }
  return { days: [...new Set(days)] };
}

/**
 * Reads a stored cron as daily or weekly clock times: {@code 0 M H1,H2 * * DOW} with one minute, a list of hours and
 * a day-of-week field that is {@code *} or a list of days. Anything else (every hour, ranges, a day of the month, a
 * month) is null and keeps the raw cron field.
 *
 * @param {string|null|undefined} cron a 6-field Spring cron expression
 * @returns {{minute: number, hours: number[], days: string[]|null}|null} the reading, or null when it is not a clock
 *   schedule; {@code days} is null for a daily one
 */
export function parseClockCron(cron) {
  const parts = (cron ?? '').trim().split(/\s+/);
  if (parts.length !== 6) return null;
  const [sec, min, hour, dom, mon, dow] = parts;
  if (sec !== '0' || dom !== '*' || mon !== '*') return null;
  if (!/^\d{1,2}$/.test(min) || !/^\d{1,2}(,\d{1,2})*$/.test(hour)) return null;
  const minute = parseInt(min, 10);
  const hours = hour.split(',').map((h) => parseInt(h, 10));
  if (minute > 59 || hours.some((h) => h > 23)) return null;
  const parsed = parseDays(dow);
  if (parsed == null) return null;
  return { minute, hours: [...new Set(hours)].sort((a, b) => a - b), days: parsed.days };
}

const pad = (n) => String(n).padStart(2, '0');

const UK_PARTS = new Intl.DateTimeFormat('en-GB', {
  timeZone: UK_ZONE, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  hourCycle: 'h23',
});

/** UK's offset from UTC in minutes at an instant (60 in summer time, 0 in winter). */
function ukOffsetMinutes(instant) {
  const at = new Date(instant);
  const p = Object.fromEntries(UK_PARTS.formatToParts(at).map((part) => [part.type, part.value]));
  const local = Date.UTC(Number(p.year), Number(p.month) - 1, Number(p.day), Number(p.hour), Number(p.minute));
  return Math.round((local - Math.floor(at.getTime() / 60_000) * 60_000) / 60_000);
}

/**
 * What the cron editor opens with, in UK time, or null when the stored cron is not a daily or weekly clock schedule
 * (the raw field is used instead). A cron that runs in {@code Europe/London} is shown as it is; a UTC one is converted
 * at the one offset in force at the job's next fire. A UTC cron whose times fall on different UK days is null.
 *
 * @param {object} job the served job
 * @param {Date} [now] the real clock, the reference when the job has no next fire
 * @returns {{times: string[], days: string[]|null}|null} the UK times, and the days for a weekly job
 */
export function cronEditorModel(job, now = new Date()) {
  const parsed = parseClockCron(job.cronExpression);
  if (!parsed) return null;
  if (job.timeZone === UK_ZONE) {
    return { times: parsed.hours.map((h) => `${pad(h)}:${pad(parsed.minute)}`), days: parsed.days };
  }
  // Every hour is converted at the ONE offset in force at the reference instant (the next fire), not each at its own.
  const offset = ukOffsetMinutes(job.nextFires?.[0] || job.nextFireTime || now);
  const converted = parsed.hours.map((h) => {
    const total = h * 60 + parsed.minute + offset;
    const wrapped = ((total % 1440) + 1440) % 1440;
    return { time: `${pad(Math.floor(wrapped / 60))}:${pad(wrapped % 60)}`, dayShift: Math.floor(total / 1440) };
  });
  const shift = converted[0].dayShift;
  if (converted.some((c) => c.dayShift !== shift)) return null;
  const days = parsed.days == null
    ? null
    : parsed.days.map((d) => DAY_CODES[(DAY_CODES.indexOf(d) + shift + 7) % 7]);
  const times = converted.map((c) => c.time).sort();
  return { times, days };
}

const TIME_SHAPE = /^(\d{1,2}):(\d{2})$/;

/**
 * Reads the cron editor's text: comma-separated 24-hour times. Each is checked for shape and range here, for an
 * immediate message; whether the set can be saved (one shared minute, say) is the server's, through the preview.
 *
 * @param {string} text the field's value
 * @returns {{ok: true, times: string[]}|{ok: false, message: string}} {@code HH:MM} times without repeats, in the
 *   order typed, or the message to show
 */
export function parseTimesInput(text) {
  const entries = (text ?? '').split(',').map((entry) => entry.trim()).filter((entry) => entry !== '');
  if (entries.length === 0) return { ok: false, message: 'Enter at least one time, e.g. 06:15.' };
  const times = [];
  for (const entry of entries) {
    const match = TIME_SHAPE.exec(entry);
    if (!match) return { ok: false, message: `"${entry}" isn't a time. Use 24-hour HH:MM, e.g. 06:15.` };
    if (Number(match[1]) > 23 || Number(match[2]) > 59) {
      return { ok: false, message: `"${entry}" is out of range. Times run from 00:00 to 23:59.` };
    }
    const time = `${pad(Number(match[1]))}:${match[2]}`;
    if (!times.includes(time)) times.push(time);
  }
  return { ok: true, times };
}

/** The fixed-delay presets, in milliseconds, with their labels. */
export const DELAY_PRESETS = [
  { ms: 60_000, label: '1 min' },
  { ms: 300_000, label: '5 min' },
  { ms: 600_000, label: '10 min' },
  { ms: 1_800_000, label: '30 min' },
  { ms: 3_600_000, label: '1 hour' },
];

/** A delay's duration in short words ("2 min 30 s", "1 h 30 min"), for a stored delay that is not a preset. */
function delayLabel(ms) {
  if (ms % 1000 !== 0) return `${ms} ms`;
  const total = ms / 1000;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return [h ? `${h} h` : '', m ? `${m} min` : '', s ? `${s} s` : ''].filter(Boolean).join(' ');
}

/**
 * The fixed-delay editor's choices: the presets, plus the job's current delay when it is not one of them (labelled
 * with its duration, in its place by size) so the editor never rounds a stored delay to a preset.
 *
 * @param {number} currentMs the job's stored delay in milliseconds
 * @returns {Array<{value: string, label: string}>} the options, shortest first
 */
export function delayOptions(currentMs) {
  const options = DELAY_PRESETS.map((p) => ({ ms: p.ms, label: p.label }));
  if (Number.isFinite(currentMs) && currentMs > 0 && !options.some((o) => o.ms === currentMs)) {
    options.push({ ms: currentMs, label: delayLabel(currentMs) });
  }
  return options.sort((a, b) => a.ms - b.ms).map((o) => ({ value: String(o.ms), label: o.label }));
}

/**
 * The neighbours line under the cron editor.
 *
 * @param {Array<{displayName: string, at: string, minutesApart: number}>} neighbours the preview's neighbours
 * @returns {string} "Neighbours: Tide Refresh 02:00 (15 min apart) · …", or "Nothing within 30 minutes."
 */
export function neighboursText(neighbours) {
  if (!neighbours || neighbours.length === 0) return 'Nothing within 30 minutes.';
  const entries = neighbours.map((n) => {
    const gap = n.minutesApart === 0 ? 'at the same time' : `${n.minutesApart} min apart`;
    return `${n.displayName} ${formatClock(n.at)} (${gap})`;
  });
  return `Neighbours: ${entries.join(' · ')}`;
}

/**
 * The save toast (D16), read from the <em>reloaded</em> job's served next fire, never computed here.
 *
 * @param {object|null|undefined} job the job as re-read after the save
 * @param {Date} [now] the real clock
 * @returns {string} "Saved. Next fire Tomorrow 12:27.", or "Saved." when the job has no next fire
 */
export function savedMessage(job, now = new Date()) {
  const next = job?.status === STATUS_ACTIVE ? (job.nextFires?.[0] || job.nextFireTime) : null;
  const label = next ? formatFireLabel(next, now) : '';
  return label ? `Saved. Next fire ${label}.` : 'Saved.';
}
