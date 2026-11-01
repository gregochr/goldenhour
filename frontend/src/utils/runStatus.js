/**
 * The Runs group's status vocabulary (runs-redesign-plan.md §4): a served status becomes a label, a
 * {@code StatusDot} tone and whether the dot carries the Running halo. A pure lookup: the server decides every
 * status (R-D1), and nothing here derives one.
 */

/** A status nothing here recognises reads as itself, in the quiet ink. */
function unknownStatus(status) {
  return { label: status == null || status === '' ? '—' : String(status), tone: 'off', halo: false };
}

/** The served run statuses ({@code RunListStatus}). Running is tide with a halo; Degraded and Partial are amber. */
const RUN_STATUS = {
  RUNNING: { label: 'Running', tone: 'tide', halo: true },
  DONE: { label: 'Done', tone: 'go', halo: false },
  DEGRADED: { label: 'Degraded', tone: 'amber', halo: false },
  PARTIAL: { label: 'Partial', tone: 'amber', halo: false },
  FAILED: { label: 'Failed', tone: 'coral', halo: false },
};

/**
 * The batch-status vocabulary ({@code ForecastBatchEntity.BatchStatus}): Submitted (tide, halo), Completed (go),
 * Failed and Expired (coral), Cancelled (ink-3; the handoff does not list it).
 */
const BATCH_STATUS = {
  SUBMITTED: { label: 'Submitted', tone: 'tide', halo: true },
  COMPLETED: { label: 'Completed', tone: 'go', halo: false },
  FAILED: { label: 'Failed', tone: 'coral', halo: false },
  EXPIRED: { label: 'Expired', tone: 'coral', halo: false },
  CANCELLED: { label: 'Cancelled', tone: 'off', halo: false },
};

/**
 * The look of a served run status.
 *
 * @param {string|null|undefined} status {@code RUNNING}, {@code DONE}, {@code DEGRADED}, {@code PARTIAL} or
 *   {@code FAILED}
 * @returns {{label: string, tone: 'go'|'amber'|'coral'|'tide'|'off', halo: boolean}}
 */
export function runStatusView(status) {
  return RUN_STATUS[status] ?? unknownStatus(status);
}

/**
 * The look of an Anthropic batch's status.
 *
 * @param {string|null|undefined} status {@code SUBMITTED}, {@code COMPLETED}, {@code FAILED}, {@code EXPIRED} or
 *   {@code CANCELLED}
 * @returns {{label: string, tone: 'go'|'amber'|'coral'|'tide'|'off', halo: boolean}}
 */
export function batchStatusView(status) {
  return BATCH_STATUS[status] ?? unknownStatus(status);
}

/** The statuses that need an admin's attention (the Need attention chip), as the server counts them. */
const ATTENTION_STATUSES = new Set(['DEGRADED', 'PARTIAL', 'FAILED']);

/**
 * Whether a served status needs attention. The same set the server's {@code attention} filter and
 * {@code attentionCount} use; the client never counts with it, only asks whether an open item still matches a chip.
 *
 * @param {string|null|undefined} status a served run status
 * @returns {boolean}
 */
export function needsAttentionStatus(status) {
  return ATTENTION_STATUSES.has(status);
}

/** The text colour of a row or panel note by its served tone: info is tide, a warning amber, an error coral. */
const NOTE_TONE_CLASS = {
  INFO: 'text-tide',
  WARN: 'text-verdict-marginal',
  ERROR: 'text-plex-coral',
};

/**
 * The text class of a note's served tone.
 *
 * @param {string|null|undefined} tone {@code INFO}, {@code WARN} or {@code ERROR}
 * @returns {string}
 */
export function noteToneClass(tone) {
  return NOTE_TONE_CLASS[tone] ?? 'text-plex-text-secondary';
}

/**
 * The look of a pipeline run's own status ({@code PipelineRunStatus}: RUNNING, COMPLETED, FAILED, DEGRADED). The
 * cycle detail serves these rather than {@code RunListStatus}; COMPLETED reads as Done, the rest as the list says.
 *
 * @param {string|null|undefined} status the cycle detail's {@code run.status}
 * @returns {{label: string, tone: string, halo: boolean}}
 */
export function cycleStatusView(status) {
  return runStatusView(status === 'COMPLETED' ? 'DONE' : status);
}
