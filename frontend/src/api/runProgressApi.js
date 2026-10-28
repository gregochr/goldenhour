import createEventSource from '../utils/createEventSource.js';

const BASE_URL = '/api';

/**
 * Subscribes to live progress updates for a specific forecast run via SSE.
 *
 * @param {number} runId - The job run ID.
 * @param {function} onTaskUpdate - Called with task update data on each state change.
 * @param {function} onRunSummary - Called with run summary data after each task update.
 * @param {function} onRunComplete - Called with run complete data when the run finishes.
 * @param {function} onError - Called on connection error.
 * @param {function} [onRunExpired] - Called when the server no longer holds the run (evicted, or
 *   lost to a restart): the stream ends and nothing more will arrive.
 * @returns {function} Cleanup function to close the stream.
 */
export function subscribeToRunProgress(runId, onTaskUpdate, onRunSummary, onRunComplete, onError, onRunExpired) {
  return createEventSource(
    `${BASE_URL}/forecast/run/${runId}/progress`,
    {},
    {
      'task-update': onTaskUpdate,
      'run-summary': onRunSummary,
      'run-complete': onRunComplete,
      'run-expired': onRunExpired,
    },
    { onError, closeOn: ['run-complete', 'run-expired'] },
  );
}
