import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext.jsx';
import createEventSource from '../utils/createEventSource.js';

/**
 * Grace period before a dropped SSE connection is reported as DOWN. A brief drop
 * (tab waking from the background, a momentary network blip) triggers an immediate
 * reconnect, so we wait this long before flashing the "service unavailable" banner —
 * long enough to cover a reconnect but short enough to surface a genuine outage.
 */
const DOWN_GRACE_MS = 6000;

/**
 * Connects to the SSE status stream and pushes health updates into state.
 * On connection error the status is only marked DOWN after a short grace period,
 * and the connection reconnects immediately when the tab becomes visible again —
 * so returning to a backgrounded tab recovers on its own without a page reload.
 *
 * @returns {{ status: string|null, degraded: string[], checkedAt: Date|null, build: object|null, session: object|null, database: object|null, services: object|null, appVersion: string|null, startedAt: string|null }}
 */
export function useHealthStatus() {
  const { token } = useAuth();
  const [health, setHealth] = useState({
    status: null, degraded: [], checkedAt: null,
    build: null, session: null, database: null, services: null,
    appVersion: null, startedAt: null,
  });

  useEffect(() => {
    if (!token) return;

    let downTimer = null;
    const clearDownTimer = () => {
      if (downTimer) {
        clearTimeout(downTimer);
        downTimer = null;
      }
    };

    const cleanup = createEventSource(
      '/api/status/stream',
      {},
      {
        status: (data) => {
          clearDownTimer();
          setHealth({
            status: data.status,
            degraded: data.degraded || [],
            checkedAt: new Date(),
            build: data.build || null,
            session: data.session || null,
            database: data.database || null,
            services: data.services || null,
            appVersion: data.appVersion || null,
            startedAt: data.startedAt || null,
          });
        },
      },
      {
        getToken: () => token,
        reconnectOnVisible: true,
        onError: () => {
          // Debounce: don't flash DOWN on a transient drop that reconnects quickly.
          if (downTimer) return;
          downTimer = setTimeout(() => {
            downTimer = null;
            setHealth((prev) => ({
              ...prev,
              status: 'DOWN',
              checkedAt: new Date(),
            }));
          }, DOWN_GRACE_MS);
        },
      },
    );

    return () => {
      clearDownTimer();
      cleanup();
    };
  }, [token]);

  return health;
}
