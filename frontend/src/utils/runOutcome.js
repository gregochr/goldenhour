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

/** The line shown where Retry would be when the run was stopped on a rejected API key. */
export const RETRY_NOT_OFFERED_KEY = 'Retry is not offered: fix the API key, then start the run again.';
/** The line shown where Retry would be for a light-pollution run, whose failures are not forecast slots. */
export const RETRY_NOT_OFFERED_LIGHT_POLLUTION = 'Retry is not offered: press Refresh Light Pollution again.';
/** The line shown where Retry would be for any other run the server will not retry. */
export const RETRY_NOT_OFFERED_GENERIC = 'Retry is not offered for this run.';

/**
 * The line that says why Retry is not offered, from the completion payload's `retryBlockedReason`
 * (the server's one answer, which its retry endpoint also reads). A payload that says
 * `retryable: false` and names no reason is the earlier shape, which only ever meant a rejected
 * key. Any reason this does not know reads as the generic line: Retry is withdrawn either way.
 *
 * @param {object|null|undefined} data - The run-complete payload.
 * @returns {string}
 */
export const retryNotOfferedLine = (data) => {
  const reason = data?.retryBlockedReason;
  if (!reason || reason === 'API_KEY_REJECTED') return RETRY_NOT_OFFERED_KEY;
  if (reason === 'LIGHT_POLLUTION') return RETRY_NOT_OFFERED_LIGHT_POLLUTION;
  return RETRY_NOT_OFFERED_GENERIC;
};

/** How many left-out slots the note names before saying "and N more". */
const MAX_LEFT_OUT_NAMED = 3;

/** One left-out slot as "Place 2026-10-03 sunrise (reason)", leaving out any part the server did not send. */
const leftOutEntry = (slot) => {
  const present = (v) => (typeof v === 'string' && v.trim() !== '' ? v.trim() : null);
  const place = present(slot?.locationName) ?? 'A place';
  const when = [present(slot?.date), present(slot?.targetType)?.toLowerCase()].filter(Boolean).join(' ');
  const reason = present(slot?.reason)?.replace(/\.+$/, '');
  return `${place}${when ? ` ${when}` : ''}${reason ? ` (${reason})` : ''}`;
};

/**
 * What the server's 202 for a retry says was started: "Retrying 2 slots.", plus the failed slots it
 * left out (their place is disabled, gone or no longer a sky location) with the server's reason each.
 * Null when the answer carries no slot count (an older server), so nothing is claimed.
 *
 * @param {object|null|undefined} result - The retry-failed response body.
 * @returns {string|null}
 */
export const retryStartedNote = (result) => {
  const slots = result?.slots;
  if (!Number.isInteger(slots)) return null;
  const started = `Retrying ${slots} ${slots === 1 ? 'slot' : 'slots'}.`;
  const skipped = Array.isArray(result.skipped) ? result.skipped : [];
  if (skipped.length === 0) return started;
  const named = skipped.slice(0, MAX_LEFT_OUT_NAMED).map(leftOutEntry);
  const more = skipped.length - named.length;
  if (more > 0) named.push(`and ${more} more`);
  return `${started} Left out: ${named.join('; ')}.`;
};
