// How a finished forecast run reads from its `run-complete` payload (RunProgressTracker's
// buildRunCompleteEvent: status, failed, reason, ...). One place, because three surfaces decide from
// it (the Job Runs panel, the map popup's Run Forecast and the app-wide completion banner) and a run
// that failed before it had any task reports `total: 0, failed: 0` — a count alone calls it clean.

/** Shown when a run failed outright and the server gave no reason. */
export const RUN_FAILED_FALLBACK = 'The forecast run failed.';

/**
 * Whether the run failed as a whole: the server marked it FAILED, or gave a run-level reason. The
 * popup and the banner treat this, and only this, as "show an error, not a success".
 *
 * @param {object|null|undefined} data - The run-complete payload.
 * @returns {boolean}
 */
export const failedOutright = (data) => data?.status === 'FAILED' || Boolean(data?.reason);

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
