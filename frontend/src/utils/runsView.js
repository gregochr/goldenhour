import { parseUtcInstant } from './conversions.js';
import { formatCostGbp } from './formatCost.js';
import { modelLabel } from './modelLabels.js';
import { UK_ZONE, ukDateStr, ukDateStrOffset } from './mapDates.js';

/**
 * Pure view-model helpers for the Runs tab (runs-redesign-plan.md §5.2).
 *
 * <p>⚠️ Every clock read here is the REAL clock ({@code new Date()}), never {@code appNow()} (R-D7): Operations does
 * not follow an admin's Rewind. A function that needs "now" takes it as an argument defaulting to
 * {@code new Date()}, so a test can pin it and a caller never reaches for the rewound clock.
 */

/** The group names as the chips and the panel meta say them. */
const GROUP_LABELS = {
  CYCLES: 'Cycle',
  BATCHES: 'Batch',
  TIDE: 'Tide',
  BRIEFING: 'Briefing',
  AURORA: 'Aurora',
  OTHER: 'Other',
};

/** The group's word for the panel's meta line ("Batch", "Tide"). */
export function groupLabel(group) {
  return GROUP_LABELS[group] ?? (group ? String(group) : '');
}

const WEEKDAY_FORMAT = new Intl.DateTimeFormat('en-GB', { timeZone: UK_ZONE, weekday: 'short' });
const DAY_MONTH_FORMAT = new Intl.DateTimeFormat('en-GB', { timeZone: UK_ZONE, day: 'numeric', month: 'short' });
const CLOCK_FORMAT = new Intl.DateTimeFormat('en-GB', {
  timeZone: UK_ZONE, hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
});

/**
 * An instant's UK clock time, "15:02".
 *
 * @param {string|Date|null} instant a served instant (the bare {@code LocalDateTime} form is read as UTC)
 * @returns {string} the time, or an empty string for none
 */
export function formatClockUk(instant) {
  const d = parseUtcInstant(instant);
  return d ? CLOCK_FORMAT.format(d) : '';
}

/**
 * The Started column: "Today 15:00", "Yesterday 19:40", else "Thu 29 Oct 11:02", all on the UK calendar and clock.
 *
 * @param {string|Date|null} instant when the run started
 * @param {Date} [now] the real clock; defaults to {@code new Date()} (never {@code appNow()}, R-D7)
 * @returns {string} the label, or an empty string for none
 */
export function formatRunStarted(instant, now = new Date()) {
  const d = parseUtcInstant(instant);
  if (!d) return '';
  const day = ukDateStr(d);
  const clock = CLOCK_FORMAT.format(d);
  if (day === ukDateStr(now)) return `Today ${clock}`;
  if (day === ukDateStrOffset(-1, now)) return `Yesterday ${clock}`;
  return `${WEEKDAY_FORMAT.format(d)} ${DAY_MONTH_FORMAT.format(d)} ${clock}`;
}

/**
 * A length of time in the list's compact form: "48s", "1m 12s", "52m", "2h 41m".
 *
 * @param {number|null|undefined} seconds the whole seconds
 * @returns {string} the text, or an em dash for none
 */
export function formatSeconds(seconds) {
  if (seconds == null || Number.isNaN(seconds) || seconds < 0) return '—';
  const total = Math.floor(seconds);
  if (total >= 3600) return `${Math.floor(total / 3600)}h ${Math.floor((total % 3600) / 60)}m`;
  if (total >= 60) {
    const minutes = Math.floor(total / 60);
    const rest = total % 60;
    return rest === 0 ? `${minutes}m` : `${minutes}m ${rest}s`;
  }
  return `${total}s`;
}

/**
 * The Duration column: "{x} so far" while the run is running, the served duration once it has finished, an em dash
 * when there is none (an ASK run never has one).
 *
 * @param {{status: string, startedAt: string, durationSeconds: number|null}} item a list item
 * @param {Date} [now] the real clock; defaults to {@code new Date()} (R-D7)
 * @returns {string}
 */
export function runDurationText(item, now = new Date()) {
  if (item.status === 'RUNNING') {
    const started = parseUtcInstant(item.startedAt);
    if (!started) return '—';
    const elapsed = Math.floor((now.getTime() - started.getTime()) / 1000);
    return elapsed < 0 ? '—' : `${formatSeconds(elapsed)} so far`;
  }
  return formatSeconds(item.durationSeconds);
}

/** Whether a list item is a batch run in flight (its result reads "{succeeded} / {locationsProcessed}"). */
function isRunningBatch(item) {
  return item.kind === 'JOB' && item.status === 'RUNNING' && item.runType === 'SCHEDULED_BATCH';
}

/**
 * The Result column (plan §5.2): cycles "{done} of {total} batches"; a running batch "{succeeded} /
 * {locationsProcessed}"; a finished briefing "Built"; an aurora evaluation "{succeeded} scored"; otherwise
 * "{ok} / {total}", or an em dash for 0/0.
 *
 * @param {object} item a list item
 * @returns {string}
 */
export function runResultText(item) {
  if (item.kind === 'CYCLE') return `${item.batchesDone ?? 0} of ${item.batchesTotal ?? 0} batches`;
  const succeeded = item.succeeded ?? 0;
  if (isRunningBatch(item)) return `${succeeded} / ${item.locationsProcessed || '?'}`;
  if (item.group === 'BRIEFING' && item.status === 'DONE') return 'Built';
  if (item.runType === 'AURORA_EVALUATION') return `${succeeded} scored`;
  const total = succeeded + (item.failed ?? 0);
  return total === 0 ? '—' : `${succeeded} / ${total}`;
}

/**
 * The summary's Spend figure in pounds to the penny ("£18.40"), from the served micro-dollars and rate.
 *
 * @param {number|null|undefined} microDollars the spend in micro-dollars
 * @param {number|null|undefined} rate GBP per USD
 * @returns {string} the amount, or an em dash when there is none to show
 */
export function formatSpendGbp(microDollars, rate) {
  if (!microDollars || microDollars <= 0 || !rate) return '\u2014';
  return `\u00a3${((microDollars / 1_000_000) * rate).toFixed(2)}`;
}

/** The summary's Spend in dollars to the cent ("$23.31"), or an empty string when there is none. */
export function formatSpendUsd(microDollars) {
  if (!microDollars || microDollars <= 0) return '';
  return `$${(microDollars / 1_000_000).toFixed(2)}`;
}

/** The cost cell: GBP from the served rate, an em dash when there is none or it is zero. */
export function runCostText(item) {
  return formatCostGbp(item.costMicroDollars, item.costGbpRate);
}

/**
 * The batch scope line of a batch run ("T+1 · SUNRISE · 29 locations · Haiku 4.5 · 7 regions"), from its
 * {@code batchSummary}. A model whose label already ends "(ET)" is not suffixed twice.
 *
 * @param {object|null|undefined} summary the run's {@code batchSummary}
 * @returns {string} the line, or an empty string for none
 */
export function batchSummaryLine(summary) {
  if (!summary) return '';
  const label = modelLabel(summary.evaluationModel);
  const events = summary.eventTypes && summary.eventTypes.length > 0 ? ` · ${summary.eventTypes.join(' / ')}` : '';
  const thinking = summary.extendedThinking && !label.endsWith('(ET)') ? ' (ET)' : '';
  return `${summary.horizonRange}${events} · ${summary.locationCount} locations · ${label}${thinking}`
    + ` · ${summary.regionCount} regions`;
}

/**
 * Line 2 of a Run cell: a cycle's "Cycle #412 · intraday · 4 batches"; a job run's "Manual" or "Scheduled" with its
 * scope (a batch's summary line, or "No detail available" for a finished batch without one).
 *
 * @param {object} item a list item
 * @returns {string}
 */
export function runScopeText(item) {
  if (item.kind === 'CYCLE') {
    const count = item.batchCount ?? item.batchesTotal ?? 0;
    const type = item.cycleType ? ` · ${item.cycleType.toLowerCase()}` : '';
    return `Cycle #${item.id}${type} · ${count} ${count === 1 ? 'batch' : 'batches'}`;
  }
  const parts = [item.triggeredManually ? 'Manual' : 'Scheduled'];
  if (item.runType === 'SCHEDULED_BATCH') {
    if (item.batchSummary) parts.push(batchSummaryLine(item.batchSummary));
    else if (item.status !== 'RUNNING') parts.push('No detail available');
  } else if ((item.locationsProcessed ?? 0) > 0) {
    parts.push(`${item.locationsProcessed} locations`);
  }
  return parts.join(' · ');
}

/**
 * A row's accessible name, reading the whole row: "Afternoon refresh, Today 15:00, Running, 2 of 4 batches".
 *
 * @param {object} item a list item
 * @param {string} statusLabel the status's words
 * @param {Date} [now] the real clock (R-D7)
 * @returns {string}
 */
export function runRowLabel(item, statusLabel, now = new Date()) {
  const parts = [item.name, formatRunStarted(item.startedAt, now), statusLabel, runResultText(item)];
  if (item.note) parts.push(item.note);
  return parts.filter((part) => part !== '').join(', ');
}

/** A row's id: a cycle and a job run can share a number, so the kind leads ({@code cycle-412}, {@code job-9876}). */
export function runRowId(item) {
  return `${item.kind === 'CYCLE' ? 'cycle' : 'job'}-${item.id}`;
}

/**
 * Shortens a long id to its head and tail ("msgbatch_01JBXK…7Q"), for a title that must not wrap. A short id comes
 * back whole.
 *
 * @param {string|null|undefined} id the id
 * @returns {string}
 */
export function midTruncate(id) {
  if (!id) return '';
  return id.length <= 20 ? id : `${id.slice(0, 14)}…${id.slice(-2)}`;
}

/**
 * The Anthropic batch id of a batch run: the served batch row's, else the "Anthropic batch: {id}" a top-level batch
 * run writes into its notes.
 *
 * @param {{run: {notes?: string|null}, batch?: {anthropicBatchId?: string}|null}} detail the job-run detail
 * @returns {string|null}
 */
export function anthropicBatchId(detail) {
  const fromBatch = detail?.batch?.anthropicBatchId;
  if (fromBatch) return fromBatch;
  const notes = detail?.run?.notes;
  if (detail?.run?.runType === 'SCHEDULED_BATCH' && typeof notes === 'string' && notes.startsWith('Anthropic batch: ')) {
    return notes.slice('Anthropic batch: '.length).trim() || null;
  }
  return null;
}
