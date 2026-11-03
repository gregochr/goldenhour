import { describeCron, describeFixedDelay, formatClock } from './schedulerView.js';
import {
  fireStatusStyle,
  formatFireDuration,
  instantMs,
  isFuture,
  lastHappened,
  onAxis,
  repeatingShape,
  timeToX,
} from './schedulerDay.js';

/**
 * Pure view-model helpers for the Day picture's overlays (scheduler-redesign-plan.md D12, S5b): the Repeating section's
 * rows, the watchdog windows, the dependency links and the tooltips' fields. The geometry itself (time to x, shapes,
 * axes) stays in {@code schedulerDay.js}.
 *
 * <p>⚠️ Every clock read is an argument or the served data, never {@code appNow()} (R-D7).
 */

const MS_PER_MINUTE = 60_000;
const MS_PER_DAY = 86_400_000;
const MS_PER_HOUR = 3_600_000;

/**
 * "every 1 min", "every 5 min", "every 2 hours": how often a repeating job runs, in the one short form the picture
 * uses, from the served schedule. A fixed delay reads from its milliseconds; a cron of the shape "every N minutes"
 * does too; any other cron falls back to {@link describeCron}'s words (or the raw cron).
 *
 * @param {{scheduleType?: string, cronExpression?: string|null, delayMs?: number|null, timeZone?: string|null}|null} schedule
 * @returns {string}
 */
export function cadenceWords(schedule) {
  if (!schedule) return '';
  const every = (ms) => {
    if (ms >= MS_PER_HOUR) {
      const hours = ms / MS_PER_HOUR;
      return `every ${hours === 1 ? 'hour' : `${hours} hours`}`;
    }
    if (ms >= MS_PER_MINUTE) return `every ${ms / MS_PER_MINUTE} min`;
    return `every ${ms / 1000} sec`;
  };
  if (schedule.scheduleType === 'FIXED_DELAY') {
    return schedule.delayMs > 0 ? every(schedule.delayMs) : describeFixedDelay(schedule.delayMs).toLowerCase();
  }
  const parts = (schedule.cronExpression ?? '').split(' ');
  if (parts.length >= 6 && parts[0] === '0' && parts.slice(2).every((part) => part === '*')) {
    if (parts[1] === '*') return every(MS_PER_MINUTE);
    const step = /^\*\/(\d{1,2})$/.exec(parts[1]);
    if (step) return every(Number(step[1]) * MS_PER_MINUTE);
  }
  const words = describeCron(schedule.cronExpression, schedule.timeZone ?? undefined);
  return words ? words.charAt(0).toLowerCase() + words.slice(1) : '';
}

/**
 * The rows of the Repeating section: one per {@code REPEATING} job, a job the {@code /jobs} list says is off in config
 * left out. {@code ticks} are the served slot instants as epoch ms (null when the server listed none, so the picture
 * draws a band); {@code problems} are the failed and partial fires.
 *
 * @param {object|null} day the served day
 * @param {Array<object>} jobs the served jobs list, joined by job key
 * @returns {Array<{jobKey: string, name: string, schedule: object, cadence: string, ticks: Array<number>|null,
 *   problems: Array<{status: string, at: string, atMs: number, summary: string}>, problemsOmitted: number,
 *   paused: boolean, usesClaude: boolean, alias: string|null}>}
 */
export function buildRepeatingRows(day, jobs) {
  const byKey = new Map((jobs ?? []).map((job) => [job.jobKey, job]));
  const rows = (day?.jobs ?? [])
    .filter((entry) => entry.kind === 'REPEATING')
    .filter((entry) => byKey.get(entry.jobKey)?.status !== 'DISABLED_BY_CONFIG')
    .map((entry) => {
      const meta = byKey.get(entry.jobKey);
      const ticks = Array.isArray(entry.ticks)
        ? entry.ticks.map(instantMs).filter((ms) => ms != null).sort((a, b) => a - b)
        : null;
      return {
        jobKey: entry.jobKey,
        name: entry.displayName,
        schedule: entry.schedule ?? null,
        cadence: cadenceWords(entry.schedule),
        ticks,
        problems: (entry.problems ?? [])
          .map((problem) => ({ ...problem, atMs: instantMs(problem.at) }))
          .filter((problem) => problem.atMs != null),
        problemsOmitted: entry.problemsOmitted ?? 0,
        paused: meta?.status === 'PAUSED',
        usesClaude: meta?.catalogue?.usesClaude === true,
        alias: meta?.catalogue?.alias ?? null,
      };
    });
  // The rarest first (an hourly cron above a minute poller), then by name.
  const interval = (row) => {
    if (row.ticks != null) return row.ticks.length === 0 ? Infinity : MS_PER_DAY / row.ticks.length;
    return row.schedule?.scheduleType === 'FIXED_DELAY' ? row.schedule.delayMs ?? 0 : 0;
  };
  return rows.sort((a, b) => interval(b) - interval(a) || a.name.localeCompare(b.name));
}

/**
 * How a repeating row's marks are drawn on an axis: its ticks (those on the axis) when the server listed them and the
 * closest two are at least 3px apart, otherwise one band ({@link repeatingShape}).
 *
 * @param {{ticks: Array<number>|null}} row a row from {@link buildRepeatingRows}
 * @param {{startMs: number, endMs: number}} axis the axis shown
 * @param {number} plotWidth the plot width in px
 * @returns {{shape: 'ticks'|'band', ticks: Array<number>}}
 */
export function repeatingLayout(row, axis, plotWidth) {
  if (row.ticks == null) return { shape: 'band', ticks: [] };
  const visible = row.ticks.filter((ms) => onAxis(ms, axis));
  let closest = Infinity;
  for (let i = 1; i < visible.length; i += 1) {
    closest = Math.min(closest, timeToX(visible[i], axis, plotWidth) - timeToX(visible[i - 1], axis, plotWidth));
  }
  return { shape: repeatingShape(closest), ticks: visible };
}

/**
 * A repeating job's nominal interval in ms: a fixed delay's own, a cron's median gap between its served ticks (or its
 * "every N minutes" when none were served), else Infinity (unknown, so nothing is judged against it).
 *
 * @param {object} row a row from {@link buildRepeatingRows}
 * @returns {number}
 */
export function repeatingIntervalMs(row) {
  if (row.schedule?.scheduleType === 'FIXED_DELAY') return row.schedule.delayMs > 0 ? row.schedule.delayMs : Infinity;
  if (row.ticks != null && row.ticks.length > 1) {
    const gaps = row.ticks.slice(1).map((ms, i) => ms - row.ticks[i]).sort((a, b) => a - b);
    return gaps[Math.floor(gaps.length / 2)];
  }
  const parts = (row.schedule?.cronExpression ?? '').split(' ');
  if (parts.length >= 6 && parts[0] === '0' && parts.slice(2).every((part) => part === '*')) {
    if (parts[1] === '*') return MS_PER_MINUTE;
    const step = /^\*\/(\d{1,2})$/.exec(parts[1]);
    if (step) return Number(step[1]) * MS_PER_MINUTE;
  }
  return Infinity;
}

/** A repeating row's accessible name: "{name}, every 5 min, 2 problems, paused, uses Claude, {alias}". */
export function repeatingAriaLabel(row) {
  const parts = [row.name];
  if (row.cadence) parts.push(row.cadence);
  const problems = row.problems.length + row.problemsOmitted;
  if (problems > 0) parts.push(`${problems} ${problems === 1 ? 'problem' : 'problems'}`);
  if (row.paused) parts.push('paused');
  if (row.usesClaude) parts.push('uses Claude');
  if (row.alias) parts.push(row.alias);
  return parts.join(', ');
}

/** The words for a watchdog window's event: "Sunrise", or the evening refresh's "2 h budget from the first batch". */
function watchdogEvent(entry) {
  if (entry.event) return `${entry.event.charAt(0)}${entry.event.slice(1).toLowerCase()}`;
  if (entry.budgetMinutes == null) return '';
  const minutes = entry.budgetMinutes;
  const budget = minutes % 60 === 0 ? `${minutes / 60} h` : `${minutes} min`;
  return `${budget} budget from the first batch`;
}

/**
 * Whether a fire can carry a watchdog window: it ran (or is still to run), so there is a cycle for the watchdog to
 * watch. Never a SKIPPED, MISSED or PAUSED fire, nor a FAILED one with no pipeline run behind it.
 *
 * @param {{status: string, runLinked?: boolean}} fire a served fire
 * @returns {boolean}
 */
export function carriesWatchdog(fire) {
  if (fire.status === 'FAILED') return fire.runLinked === true;
  return ['SUCCEEDED', 'PARTIAL', 'RUNNING', 'SCHEDULED'].includes(fire.status);
}

/**
 * The watchdog windows to draw, each keyed to its cycle job's row and to the one fire it belongs to ({@code ownerAtMs}:
 * the newest eligible fire at or before the window's end). A solar cycle's window is the served {@code opensAt} to
 * {@code closesAt}. The evening refresh's deadline is the served {@code deadlineAt} (the watchdog's own rule, from the
 * run's first batch), never worked out here; with none, nothing is drawn, and its window runs from the owning fire.
 *
 * @param {object|null} day the served day
 * @param {Array<object>} rows the scheduled rows ({@code buildRows}), whose fires own the windows
 * @returns {Array<{jobKey: string, startMs: number, deadlineMs: number, ownerAtMs: number, event: string,
 *   text: string, budgetOnly: boolean}>}
 */
export function watchdogWindows(day, rows) {
  const windows = [];
  (day?.watchdog ?? []).forEach((entry) => {
    const row = rows.find((r) => r.jobKey === entry.jobKey);
    if (!row) return;
    const opens = instantMs(entry.opensAt);
    const closes = instantMs(entry.closesAt);
    const deadline = opens != null && closes != null ? closes : instantMs(entry.deadlineAt);
    if (deadline == null) return;
    const owner = row.fires
      .filter(carriesWatchdog)
      .filter((fire) => (instantMs(fire.at) ?? Infinity) <= deadline)
      .reduce((newest, fire) => (newest == null || instantMs(fire.at) > instantMs(newest.at) ? fire : newest), null);
    if (!owner) return;
    const start = opens ?? instantMs(owner.at);
    windows.push({
      jobKey: entry.jobKey,
      startMs: start,
      deadlineMs: deadline,
      ownerAtMs: instantMs(owner.at),
      event: watchdogEvent(entry),
      budgetOnly: opens == null,
      text: `Watchdog window ${formatClock(new Date(start).toISOString())}–${formatClock(new Date(deadline).toISOString())}`,
    });
  });
  return windows;
}

/**
 * The links between jobs the catalogue declares, for the arrows: {@code collect} ("results collected by": a submitter
 * to the poller that collects its batches) and {@code stagger} ("followed 30 min later by": a shared rate limit). Only
 * links whose two jobs both have a row on the picture are returned, and nothing the catalogue does not declare.
 *
 * @param {Array<object>} jobs the served jobs list (each with its {@code catalogue})
 * @param {Iterable<string>} rowKeys the job keys that have a row
 * @returns {Array<{kind: 'collect'|'stagger', from: string, to: string}>}
 */
export function buildLinks(jobs, rowKeys) {
  const keys = new Set(rowKeys);
  const links = [];
  (jobs ?? []).forEach((job) => {
    const entry = job.catalogue;
    if (!entry || !keys.has(job.jobKey)) return;
    [['collect', entry.resultsCollectedBy], ['stagger', entry.staggeredBefore]].forEach(([kind, targets]) => {
      (targets ?? []).forEach((to) => {
        if (keys.has(to) && to !== job.jobKey) links.push({ kind, from: job.jobKey, to });
      });
    });
  });
  return links;
}

/**
 * The selected job and every job linked to it, or null when nothing is selected or the selected job has no row (the
 * picture then dims nothing).
 *
 * @param {string|null} selectedKey the job whose panel is open
 * @param {Array<{from: string, to: string}>} links from {@link buildLinks}
 * @param {Iterable<string>} rowKeys the job keys that have a row
 * @returns {Set<string>|null}
 */
export function relatedKeys(selectedKey, links, rowKeys) {
  if (selectedKey == null || !new Set(rowKeys).has(selectedKey)) return null;
  const related = new Set([selectedKey]);
  links.forEach((link) => {
    if (link.from === selectedKey) related.add(link.to);
    if (link.to === selectedKey) related.add(link.from);
  });
  return related;
}

/**
 * The curve of a "results collected by" arrow: from the foot of the submitting fire down to the collecting row, ending
 * on the row's top edge (the design's geometry).
 *
 * @param {number} x the arrow's start x, at the end of the submitting fire's bar
 * @param {number} fromY the submitting row's centre
 * @param {number} toY the collecting row's centre
 * @param {number} toX where the arrow lands on the collecting row
 * @returns {string} an SVG path
 */
export function collectArrowPath(x, fromY, toY, toX) {
  const sy = fromY + 7;
  const ty = toY - 6;
  const dy = ty - sy;
  return `M${x} ${sy} C ${x} ${sy + dy * 0.55}, ${toX} ${ty - dy * 0.35}, ${toX} ${ty}`;
}

/**
 * The sideways curve of a stagger arrow, from the end of one job's fire to the next job's fire.
 *
 * @param {number} x the end of the first fire
 * @param {number} fromY the first row's centre
 * @param {number} toX the second fire's x
 * @param {number} toY the second row's centre
 * @returns {string} an SVG path
 */
export function staggerArrowPath(x, fromY, toX, toY) {
  const startX = x + 4;
  const endX = toX - 9;
  return `M${startX} ${fromY} C ${Math.max(startX + 24, endX)} ${fromY}, ${endX - 24} ${toY}, ${endX} ${toY}`;
}

/**
 * The tooltip's fields for one thing on the picture; every field is optional text and the layer prints those present:
 * {@code name}, {@code status} ({@code {label, tone}}), {@code time}, {@code duration}, {@code note}, {@code alias},
 * {@code watchdog} ("Watchdog window 06:40-08:00") and {@code watchdogEvent} (its event, on the next line). There is
 * deliberately no "~£ per run": that figure is in {@code /jobs/{key}/detail}, not in the data this picture has.
 *
 * @typedef {object} TipContent
 */

/** A scheduled fire's tooltip. */
export function fireTip(row, fire, watchdog = null) {
  const style = fireStatusStyle(fire.status);
  return {
    name: row.name,
    status: { label: style.label, tone: style.tone },
    time: formatClock(fire.at),
    duration: formatFireDuration(fire),
    note: fire.summary ?? '',
    alias: row.alias ?? '',
    watchdog: watchdog?.text ?? '',
    watchdogEvent: watchdog?.event ?? '',
  };
}

/** A paused job's would-be fire's tooltip. */
export function wouldBeTip(row, fire) {
  return {
    name: row.name,
    status: { label: 'Paused', tone: 'off' },
    time: formatClock(fire.at),
    note: 'Would have fired, but the job is paused.',
    alias: row.alias ?? '',
  };
}

/** A scheduled row's tooltip: its last run (or next fire), what that said, and its watchdog window. */
export function rowTip(row, watchdogs = []) {
  const last = lastHappened(row);
  const next = row.fires.find(isFuture);
  const shown = last ?? next ?? null;
  const style = shown ? fireStatusStyle(shown.status) : null;
  const watchdog = watchdogs[0];
  let status = null;
  if (row.paused) status = { label: 'Paused', tone: 'off' };
  else if (style) status = { label: style.label, tone: style.tone };
  return {
    name: row.name,
    status,
    time: shown ? formatClock(shown.at) : row.wouldBe.map((fire) => formatClock(fire.at)).join(', '),
    duration: last ? formatFireDuration(last) : '',
    note: shown?.summary ?? '',
    alias: row.alias ?? '',
    watchdog: watchdog?.text ?? '',
    watchdogEvent: watchdog?.event ?? '',
  };
}

/** A repeating row's tooltip: how often it runs, its problems ("today" only on today), and its alias. */
export function repeatingRowTip(row, isToday = true) {
  const problems = row.problems.length + row.problemsOmitted;
  const when = isToday ? 'today' : 'on this day';
  return {
    name: row.name,
    status: row.paused ? { label: 'Paused', tone: 'off' } : null,
    time: row.cadence,
    note: problems > 0 ? `${problems} ${problems === 1 ? 'problem' : 'problems'} ${when}` : '',
    alias: row.alias ?? '',
  };
}

/** A repeating job's failed or partial fire: its tooltip gives the reason. */
export function problemTip(row, problem) {
  const style = fireStatusStyle(problem.status);
  return {
    name: row.name,
    status: { label: style.label, tone: style.tone },
    time: formatClock(problem.at),
    note: problem.summary ?? '',
    alias: row.alias ?? '',
  };
}

/** A watchdog window's tooltip: "Watchdog window HH:MM-HH:MM" and its event. */
export function watchdogTip(row, span) {
  return {
    name: row.name,
    watchdog: span.text,
    watchdogEvent: span.event,
    alias: row.alias ?? '',
  };
}
