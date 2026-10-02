import createEventSource from '../utils/createEventSource.js';
import apiClient from './axiosClient.js';

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
 * @returns {function} Cleanup function to close the EventSource.
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
 * Retries failed tasks from a previous run, through the shared axios client so an expired token is
 * refreshed on the way and a refusal rejects with the shape {@code utils/apiError.js} reads
 * ({@code err.response.status}, {@code err.response.data}).
 *
 * @param {number} runId - The job run ID whose failed tasks to retry.
 * @returns {Promise<{status: string, runType: string, jobRunId: number}>} New run response.
 */
export async function retryFailed(runId) {
  const { data } = await apiClient.post(`${BASE_URL}/forecast/run/${runId}/retry-failed`);
  return data;
}
