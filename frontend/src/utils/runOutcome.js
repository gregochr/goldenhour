// How a finished run reads from its `run-complete` payload (RunProgressTracker's
// buildRunCompleteEvent: status, failed, reason, ...). One place, for the Job Runs panel: a run
// that failed before it had any task reports `total: 0, failed: 0` — a count alone calls it clean.

/**
 * Whether the run failed outright: nothing it did stands. That is the server's FAILED status, and
 * only that. A PARTIAL run that carries a reason (the backend aborted it after some places had already
 * completed) is NOT outright: what completed was written and must still be refreshed into view.
 *
 * <p>A payload with a reason and no status at all (the real backend always sends one, so this is a
 * hand-built or foreign payload) is outright only when it reports no completed place.
 *
 * @param {object|null|undefined} data - The run-complete payload.
 * @returns {boolean}
 */
export const failedOutright = (data) => {
  if (data?.status === 'FAILED') return true;
  if (data?.status) return false;
  return Boolean(data?.reason) && !(data?.completed > 0);
};

/**
 * Whether the run was cut short by a run-level failure after some places had completed: it carries a
 * reason and is not outright-failed. The panel shows the reason.
 *
 * @param {object|null|undefined} data - The run-complete payload.
 * @returns {boolean}
 */
export const stoppedEarly = (data) => Boolean(data?.reason)
  && !failedOutright(data)
  && (data?.status === 'PARTIAL' || !data?.status);

/**
 * Whether the run finished, was not cut short, and still had places fail: a PARTIAL run with no
 * run-level reason (some places updated or triaged, some failed). It reports a failed place, is
 * neither failed outright nor stopped early, is not still RUNNING, and something stands: the server
 * says PARTIAL, or a place completed or was triaged. A run with failed places and nothing completed
 * or triaged is FAILED and is not this. Such a run is not a clean success, so the panel header
 * shows it amber, with the failed count, because what did complete was written.
 *
 * @param {object|null|undefined} data - The run-complete payload.
 * @returns {boolean}
 */
export const completedWithFailures = (data) => data?.failed > 0
  && data?.status !== 'RUNNING'
  && !failedOutright(data)
  && !stoppedEarly(data)
  && (data?.status === 'PARTIAL' || data?.completed > 0 || data?.triaged > 0);

/**
 * Whether a finished run needs the admin's attention and so stays on screen: any failed task, a
 * FAILED or PARTIAL status, or a run-level reason.
 *
 * @param {object|null|undefined} data - The run-complete payload.
 * @returns {boolean}
 */
export const needsAttention = (data) => data?.failed > 0
  || data?.status === 'FAILED'
  || data?.status === 'PARTIAL'
  || Boolean(data?.reason);
