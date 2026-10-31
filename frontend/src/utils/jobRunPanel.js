import { anthropicBatchId } from './runsView.js';
import { formatInstantUk } from './conversions.js';
import { modelLabel } from './modelLabels.js';

/**
 * Pure view-model helpers for the Runs tab's job-run panel (runs-redesign-plan.md §2.1 and §5.2): what the old
 * {@code JobRunDetail} and {@code JobRunsGrid} showed, regrouped into the panel's sections.
 */

/** The run types whose figures come from an Anthropic batch rather than per-call rows. */
const BATCH_RUN_TYPES = new Set(['SCHEDULED_BATCH', 'BATCH_NEAR_TERM', 'BATCH_FAR_TERM']);

/** Whether a run type is an Anthropic batch run (it has a batch token summary). */
export function isBatchRunType(runType) {
  return BATCH_RUN_TYPES.has(runType);
}

/** Most diagnosed calls listed individually; the tally above them counts every one. */
export const MAX_DIAGNOSED_ROWS = 25;

/**
 * The heading of a failed call: the service, then what is known about why it failed.
 *
 * <ul>
 *   <li>An error type, when the row has one, follows the service. It already carries the status
 *       ({@code anthropic_401}), so the status is not printed a second time.
 *   <li>Otherwise a status follows the service, but NOT for a Claude row. Every Claude call logged before the real
 *       status was recorded carries a placeholder 500 with no error type; showing that bare status would read last
 *       week's rejected key as an Anthropic server error. For those rows the heading is the service alone. A weather
 *       row's status is always a real HTTP status.
 * </ul>
 *
 * @param {{service: string, statusCode?: number|null, errorType?: string|null}} call
 * @returns {string}
 */
export function failedCallHeading(call) {
  const has = (value) => value !== null && value !== undefined && value !== '';
  if (has(call.errorType)) {
    return `${call.service} · ${call.errorType}`;
  }
  if (has(call.statusCode) && call.service !== 'ANTHROPIC') {
    return `${call.service} · ${call.statusCode}`;
  }
  return call.service;
}

/**
 * Groups API calls by service: count, average duration, error count, tokens and cost for each.
 *
 * @param {Array<object>} apiCalls the run's {@code api_call_log} rows
 * @returns {Array<{service: string, count: number, avgMs: number, errorCount: number, costMicroDollars: number,
 *   inputTokens: number, outputTokens: number, cacheWriteTokens: number, cacheReadTokens: number}>}
 */
export function serviceStats(apiCalls) {
  const byService = new Map();
  for (const call of apiCalls) {
    if (!byService.has(call.service)) {
      byService.set(call.service, {
        service: call.service,
        count: 0,
        totalMs: 0,
        errorCount: 0,
        costMicroDollars: 0,
        inputTokens: 0,
        outputTokens: 0,
        cacheWriteTokens: 0,
        cacheReadTokens: 0,
      });
    }
    const s = byService.get(call.service);
    s.count += 1;
    s.totalMs += call.durationMs || 0;
    s.costMicroDollars += call.costMicroDollars || 0;
    s.inputTokens += call.inputTokens || 0;
    s.outputTokens += call.outputTokens || 0;
    s.cacheWriteTokens += call.cacheCreationInputTokens || 0;
    s.cacheReadTokens += call.cacheReadInputTokens || 0;
    if (!call.succeeded) s.errorCount += 1;
  }
  return [...byService.values()].map(({ totalMs, ...rest }) => ({ ...rest, avgMs: Math.round(totalMs / rest.count) }));
}

/**
 * The Anthropic evaluation breakdown: how many calls per date, event and model, in that sort order. A call missing
 * any of the three is left out.
 *
 * @param {Array<object>} apiCalls the run's {@code api_call_log} rows
 * @returns {Array<{date: string, event: string, model: string, count: number}>}
 */
export function evaluationRows(apiCalls) {
  const byKey = new Map();
  for (const call of apiCalls) {
    if (call.service !== 'ANTHROPIC') continue;
    if (!call.targetDate || !call.targetType || !call.evaluationModel) continue;
    const key = `${call.targetDate}|${call.targetType}|${call.evaluationModel}`;
    if (!byKey.has(key)) {
      byKey.set(key, { date: call.targetDate, event: call.targetType, model: call.evaluationModel, count: 0 });
    }
    byKey.get(key).count += 1;
  }
  return [...byKey.values()].sort((a, b) => (
    a.date.localeCompare(b.date) || a.event.localeCompare(b.event) || a.model.localeCompare(b.model)
  ));
}

/** A "YYYY-MM-DD" date as "Sat 31 Oct", read as a calendar date (no zone can move it). */
export function formatIsoDay(isoDate) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(isoDate ?? '')) return isoDate ?? '';
  const noon = new Date(`${isoDate}T12:00:00Z`);
  const weekday = noon.toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
  const dayMonth = noon.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' });
  return `${weekday} ${dayMonth}`;
}

/** Whole calendar days from one ISO date to another, inclusive; 0 when either is absent. */
function inclusiveDays(min, max) {
  if (!min || !max) return 0;
  return Math.floor((new Date(max) - new Date(min)) / (1000 * 60 * 60 * 24)) + 1;
}

/** The strategy names of a run as the panel shows them: {@code EVALUATE_ALL} reads as {@code JFDI}. */
export function strategyTags(activeStrategies) {
  if (!activeStrategies) return [];
  return activeStrategies.split(',').map((s) => s.trim()).filter(Boolean)
    .map((name) => ({ name, label: name === 'EVALUATE_ALL' ? 'JFDI' : name, jfdi: name === 'EVALUATE_ALL' }));
}

/** A run's model label: the batch scope's (with its extended-thinking mark) when it has one, else the run's own. */
function modelText(run) {
  const summary = run.batchSummary;
  if (summary?.evaluationModel) {
    const label = modelLabel(summary.evaluationModel);
    return summary.extendedThinking && !label.endsWith('(ET)') ? `${label} (ET)` : label;
  }
  return modelLabel(run.evaluationModel);
}

/**
 * The Scope section's rows, each only when it has data (plan §5.2): Locations, Dates, Model, Strategies (rendered by
 * the caller from {@code strategies}), Completed, App version and the full Notes; plus an in-progress batch's
 * progress, the Anthropic batch id, and a manual or scheduled source.
 *
 * @param {object} detail the job-run detail ({@code run}, {@code status}, {@code triggeredManually}, {@code batch})
 * @returns {{items: Array<{label: string, value: string, mono?: boolean, testId?: string}>,
 *   strategies: Array<{name: string, label: string, jfdi: boolean}>}}
 */
export function scopeItems(detail) {
  const { run } = detail;
  const items = [];
  const push = (label, value, extra = {}) => {
    if (value != null && value !== '') items.push({ label, value, ...extra });
  };

  const summary = run.batchSummary;
  if (summary?.locationCount != null && summary.regionCount != null) {
    push('Locations', `${summary.locationCount} in ${summary.regionCount} regions`, { testId: 'job-run-locations' });
  } else if ((run.locationsProcessed ?? 0) > 0) {
    push('Locations', String(run.locationsProcessed), { testId: 'job-run-locations' });
  }

  const days = inclusiveDays(run.minTargetDate, run.maxTargetDate);
  const events = summary?.eventTypes && summary.eventTypes.length > 0 ? ` · ${summary.eventTypes.join(' / ')}` : '';
  if (days > 0) {
    push('Dates', `${formatIsoDay(run.minTargetDate)} – ${formatIsoDay(run.maxTargetDate)} · `
      + `${days} ${days === 1 ? 'day' : 'days'}${events}`, { testId: 'job-run-dates' });
  } else if (summary?.horizonRange) {
    push('Dates', `${summary.horizonRange}${events}`, { testId: 'job-run-dates' });
  }

  push('Model', modelText(run), { testId: 'job-run-model' });

  if (detail.batch?.status) {
    // The batch's own status, in its own vocabulary (Submitted, Completed, ...); the caller words it.
    push('Batch status', detail.batch.status, { testId: 'job-run-batch-status', batchStatus: true });
  }
  push('Anthropic batch', anthropicBatchId(detail), { mono: true, testId: 'job-run-batch-id' });

  if (detail.status === 'RUNNING' && isBatchRunType(run.runType)) {
    push('Progress', `${run.succeeded ?? 0} / ${run.locationsProcessed || '?'} succeeded`, { testId: 'job-run-progress' });
  }

  push('Completed', run.completedAt ? formatInstantUk(run.completedAt, {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'short',
  }) : '', { testId: 'job-run-completed' });
  push('App version', run.appVersion, { mono: true, testId: 'job-run-app-version' });
  push('Notes', run.notes, { testId: 'job-run-notes' });

  return { items, strategies: strategyTags(run.activeStrategies) };
}
