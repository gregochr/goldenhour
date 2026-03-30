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
 * @returns {function} Cleanup function to close the EventSource.
 */
export function subscribeToRunProgress(runId, onTaskUpdate, onRunSummary, onRunComplete, onError) {
  return createEventSource(
    `${BASE_URL}/forecast/run/${runId}/progress`,
    {},
    {
      'task-update': onTaskUpdate,
      'run-summary': onRunSummary,
      'run-complete': onRunComplete,
    },
    { onError, closeOn: 'run-complete' },
  );
}

/**
 * Subscribes to run-complete notifications for the map view via SSE.
 * Fires a single event per completed run (lightweight, no per-location detail).
 *
 * @param {function} onRunComplete - Called with run complete data.
 * @param {function} onError - Called on connection error.
 * @returns {function} Cleanup function to close the EventSource.
 */
export function subscribeToRunNotifications(onRunComplete, onError) {
  return createEventSource(
    `${BASE_URL}/forecast/run/notifications`,
    {},
    { 'run-complete': onRunComplete },
    { onError },
  );
}

/**
 * Retries failed tasks from a previous run.
 *
 * @param {number} runId - The job run ID whose failed tasks to retry.
 * @returns {Promise<{status: string, runType: string, jobRunId: number}>} New run response.
 */
export async function retryFailed(runId) {
  const token = localStorage.getItem('goldenhour_token');
  const response = await fetch(`${BASE_URL}/forecast/run/${runId}/retry-failed`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  if (!response.ok) {
    throw new Error('Retry failed');
  }
  return response.json();
}
