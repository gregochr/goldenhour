import { formatElapsedSince, formatRelativeTimeUk, parseUtcInstant } from './conversions.js';
import { formatClockUk } from './runsView.js';

/**
 * Pure view-model helpers for the cycle panel and the live strip (runs-redesign-plan.md §5.2, R2b).
 *
 * <p>⚠️ Every clock read is the REAL clock (R-D7): {@code formatRelativeTimeUk} and {@code formatElapsedSince} read
 * {@code Date.now()}, never {@code appNow()}, so a set Rewind moves none of them.
 */

/** Phase labels as the panel says them. An unknown phase reads as its own key. */
export const PHASE_LABEL = {
  STABILITY_RECLASSIFY: 'Re-classify (cost-gate)',
  FORECAST_BATCH_SUBMIT: 'Submit batches',
  FORECAST_BATCH_WAIT: 'Wait for completion',
  RETRY_FAILED: 'Retry failed requests',
  BRIEFING: 'Briefing (gloss)',
};

/** The short names the strip's bar and "Runs after {phase}" use. */
export const PHASE_SHORT = {
  STABILITY_RECLASSIFY: 'Re-classify',
  FORECAST_BATCH_SUBMIT: 'Submit',
  FORECAST_BATCH_WAIT: 'Wait',
  RETRY_FAILED: 'Retry',
  BRIEFING: 'Briefing',
};

/** The order a cycle runs its phases in, for the ones that have not started yet (the server serves only started ones). */
const PHASE_ORDER = Object.keys(PHASE_LABEL);

/** The label of a phase key, falling back to the key itself for one this build does not know. */
export function phaseLabel(phase) {
  return PHASE_LABEL[phase] || phase;
}

/** A phase's short name, falling back to its label. */
export function phaseShort(phase) {
  return PHASE_SHORT[phase] || phaseLabel(phase);
}

/**
 * The phases the panel and strip draw, in {@code sequenceOrder}. While the cycle is RUNNING, the known phases that come
 * after the last started one and have not started are appended as not yet run ({@code status: 'PENDING'}); re-classify
 * is never invented for a NIGHTLY cycle, which does not record it.
 *
 * @param {{run: object, phases: Array<object>}} detail the cycle detail
 * @returns {Array<object>} the served phases, then any pending ones
 */
export function phaseRows(detail) {
  const served = [...(detail.phases ?? [])].sort((a, b) => a.sequenceOrder - b.sequenceOrder);
  if (detail.run?.status !== 'RUNNING') return served;
  const seen = new Set(served.map((p) => p.phase));
  const lastIndex = Math.max(-1, ...served.map((p) => PHASE_ORDER.indexOf(p.phase)));
  const pending = PHASE_ORDER
    .filter((phase, index) => index > lastIndex && !seen.has(phase)
      && (phase !== 'STABILITY_RECLASSIFY' || recordsReclassify(detail.run.cycleType)))
    .map((phase, index) => ({
      phase, sequenceOrder: (served.at(-1)?.sequenceOrder ?? 0) + index + 1, status: 'PENDING',
      startedAt: null, completedAt: null, durationSeconds: null, detail: null,
    }));
  return [...served, ...pending];
}

/**
 * Whether a cycle type records the re-classify phase: every type but NIGHTLY ({@code CycleType.recordsReclassifyPhase}:
 * INTRADAY and EVENING). Used only to decide whether a not-yet-started re-classify is worth drawing.
 */
function recordsReclassify(cycleType) {
  return cycleType !== 'NIGHTLY';
}

/** The batches still SUBMITTED, with the figures the waiting box reads. */
export function waitingBatches(detail) {
  const batches = detail.batches ?? [];
  const waiting = batches.filter((b) => b.status === 'SUBMITTED');
  const earliest = waiting
    .map((b) => b.submittedAt).filter(Boolean)
    .sort((a, b) => (parseUtcInstant(a)?.getTime() ?? 0) - (parseUtcInstant(b)?.getTime() ?? 0))[0] ?? null;
  return { waiting, total: batches.length, submittedAt: earliest };
}

/** "Cycle #412 · intraday · Running · started 15:00 (1h 38m ago)". */
export function cycleMetaLine(run, statusLabel) {
  const started = formatClockUk(run.triggerTime);
  const relative = formatRelativeTimeUk(run.triggerTime);
  const when = started ? `started ${started}${relative ? ` (${relative})` : ''}` : '';
  return [`Cycle #${run.id}`, run.cycleType ? run.cycleType.toLowerCase() : null, statusLabel, when]
    .filter(Boolean).join(' · ');
}

/** The elapsed time of a running thing, "1h 35m", or an empty string when its start is unknown. */
export function elapsedText(startedAt) {
  return formatElapsedSince(startedAt) ?? '';
}

/** A request count total across a cycle's batches. */
export function totalRequests(batches) {
  return (batches ?? []).reduce((sum, b) => sum + (b.requestCount ?? 0), 0);
}

/** The names the list serves for a cycle row, used only when the row is not on the loaded page (a deep link). */
const CYCLE_NAME = {
  NIGHTLY: 'Overnight cycle',
  INTRADAY: 'Afternoon refresh',
  EVENING: 'Evening refresh',
};

/**
 * A cycle's name: the list row's served name when the row is loaded, else the same wording by cycle type.
 *
 * @param {{id: number, cycleType?: string}} run the cycle detail's run
 * @param {{name?: string}|null} [item] the cycle's list row, when it is on the loaded page
 * @returns {string}
 */
export function cycleName(run, item = null) {
  return item?.name || CYCLE_NAME[run.cycleType] || `Cycle #${run.id}`;
}
