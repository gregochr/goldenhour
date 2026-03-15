import { useState, useEffect } from 'react';
import { subscribeToRunNotifications } from '../api/runProgressApi';

/**
 * Hook that subscribes to run-complete SSE notifications.
 * Returns the last completed run data so the map view can show a toast.
 *
 * @param {string|null} token - JWT access token. No subscription when null.
 * @returns {{ lastCompletedRun: object|null }}
 */
export function useRunNotifications(token) {
  const [lastCompletedRun, setLastCompletedRun] = useState(null);

  useEffect(() => {
    if (!token) return;

    const cleanup = subscribeToRunNotifications(
      token,
      (data) => setLastCompletedRun(data),
      () => {} // silently handle errors — EventSource will auto-reconnect
    );

    return cleanup;
  }, [token]);

  return { lastCompletedRun };
}
