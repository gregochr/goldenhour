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
 * Reads a refused response's JSON body, or null when there is none (the backend answers the
 * "nothing to retry" refusal with a 404 and an empty body, and a proxy may answer with HTML).
 */
async function readJsonBody(response) {
  try {
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}

/**
 * Retries failed tasks from a previous run.
 *
 * A refused request rejects with an {@code Error} shaped like an axios one, so
 * {@code utils/apiError.js}'s {@code apiErrorMessage} reads it: {@code err.status} and
 * {@code err.response = { status, data }}, where {@code data} is the parsed JSON body or null when
 * the body was empty or not JSON. A request that never got an answer rejects with a plain
 * {@code Error} carrying no {@code status}.
 *
 * @param {number} runId - The job run ID whose failed tasks to retry.
 * @returns {Promise<{status: string, runType: string, jobRunId: number}>} New run response.
 */
export async function retryFailed(runId) {
  const token = localStorage.getItem('goldenhour_token');
  let response;
  try {
    response = await fetch(`${BASE_URL}/forecast/run/${runId}/retry-failed`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });
  } catch (cause) {
    throw new Error('Could not reach the server.', { cause });
  }
  if (!response.ok) {
    const data = await readJsonBody(response);
    const err = new Error(`Retry failed (HTTP ${response.status})`);
    err.status = response.status;
    err.response = { status: response.status, data };
    throw err;
  }
  return response.json();
}
