const BASE_URL = '/api';
const TOKEN_KEY = 'goldenhour_token';

/**
 * Subscribes to live progress updates for a specific forecast run via SSE.
 * Reads the JWT from localStorage at connection time so the token is always
 * current, even if the axios interceptor has silently refreshed it.
 *
 * @param {number} runId - The job run ID.
 * @param {function} onTaskUpdate - Called with task update data on each state change.
 * @param {function} onRunSummary - Called with run summary data after each task update.
 * @param {function} onRunComplete - Called with run complete data when the run finishes.
 * @param {function} onError - Called on connection error.
 * @returns {function} Cleanup function to close the EventSource.
 */
export function subscribeToRunProgress(runId, onTaskUpdate, onRunSummary, onRunComplete, onError) {
  const token = localStorage.getItem(TOKEN_KEY);
  const url = `${BASE_URL}/forecast/run/${runId}/progress?token=${encodeURIComponent(token)}`;
  const source = new EventSource(url);

  source.addEventListener('task-update', (event) => {
    try {
      onTaskUpdate(JSON.parse(event.data));
    } catch {
      // ignore parse errors
    }
  });

  source.addEventListener('run-summary', (event) => {
    try {
      onRunSummary(JSON.parse(event.data));
    } catch {
      // ignore parse errors
    }
  });

  source.addEventListener('run-complete', (event) => {
    try {
      onRunComplete(JSON.parse(event.data));
    } catch {
      // ignore parse errors
    }
    source.close();
  });

  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED) {
      return;
    }
    // Don't close — let EventSource's built-in reconnect handle transient errors.
    onError?.();
  };

  return () => source.close();
}

/**
 * Subscribes to run-complete notifications for the map view via SSE.
 * Fires a single event per completed run (lightweight, no per-location detail).
 * Reads the JWT from localStorage at connection time.
 *
 * @param {function} onRunComplete - Called with run complete data.
 * @param {function} onError - Called on connection error.
 * @returns {function} Cleanup function to close the EventSource.
 */
export function subscribeToRunNotifications(onRunComplete, onError) {
  const token = localStorage.getItem(TOKEN_KEY);
  const url = `${BASE_URL}/forecast/run/notifications?token=${encodeURIComponent(token)}`;
  const source = new EventSource(url);

  source.addEventListener('run-complete', (event) => {
    try {
      onRunComplete(JSON.parse(event.data));
    } catch {
      // ignore parse errors
    }
  });

  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED) {
      return;
    }
    // Don't close — let EventSource's built-in reconnect handle transient errors.
    onError?.();
  };

  return () => source.close();
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
