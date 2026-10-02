// How a finished forecast run reads from its `run-complete` payload (RunProgressTracker's
// buildRunCompleteEvent: status, failed, reason, ...). One place, because three surfaces decide from
// it (the Job Runs panel, the map popup's Run Forecast and the app-wide completion banner) and a run
// that failed before it had any task reports `total: 0, failed: 0` — a count alone calls it clean.

/** Shown when a run failed outright and the server gave no reason. */
export const RUN_FAILED_FALLBACK = 'The forecast run failed.';

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
 * reason and is not outright-failed. The popup and banner show the reason AND keep the refresh.
 *
 * @param {object|null|undefined} data - The run-complete payload.
 * @returns {boolean}
 */
export const stoppedEarly = (data) => Boolean(data?.reason)
  && !failedOutright(data)
  && (data?.status === 'PARTIAL' || !data?.status);

/**
 * The sentence for a run that failed outright: the server's reason when present, else a fixed one.
 *
 * @param {object|null|undefined} data - The run-complete payload.
 * @returns {string}
 */
export const failureMessage = (data) => data?.reason || RUN_FAILED_FALLBACK;

/**
 * Whether a finished run needs the admin's attention and so stays on screen: any failed task (the
 * set retry-failed acts on), a FAILED or PARTIAL status, or a run-level reason.
 *
 * @param {object|null|undefined} data - The run-complete payload.
 * @returns {boolean}
 */
export const needsAttention = (data) => data?.failed > 0
  || data?.status === 'FAILED'
  || data?.status === 'PARTIAL'
  || Boolean(data?.reason);
