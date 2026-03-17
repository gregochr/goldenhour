import { useState, useEffect } from 'react';
import { subscribeToRunNotifications } from '../api/runProgressApi';

/**
 * Hook that subscribes to run-complete SSE notifications.
 * Returns the last completed run data so the map view can show a toast.
 * The JWT is read from localStorage at connection time by the API layer.
 *
 * @param {boolean} enabled - Whether to subscribe (e.g. only when authenticated).
 * @returns {{ lastCompletedRun: object|null }}
 */
export function useRunNotifications(enabled) {
  const [lastCompletedRun, setLastCompletedRun] = useState(null);

  useEffect(() => {
    if (!enabled) return;

    const cleanup = subscribeToRunNotifications(
      (data) => setLastCompletedRun(data),
      () => {} // silently handle errors — EventSource will auto-reconnect
    );

    return cleanup;
  }, [enabled]);

  return { lastCompletedRun };
}
