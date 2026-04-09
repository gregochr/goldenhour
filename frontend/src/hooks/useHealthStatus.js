import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext.jsx';
import createEventSource from '../utils/createEventSource.js';

/**
 * Connects to the SSE status stream and pushes health updates into state.
 * EventSource reconnects automatically on error — no manual retry needed.
 *
 * @returns {{ status: string|null, degraded: string[], checkedAt: Date|null, build: object|null, session: object|null, database: object|null, services: object|null }}
 */
export function useHealthStatus() {
  const { token } = useAuth();
  const [health, setHealth] = useState({
    status: null, degraded: [], checkedAt: null,
    build: null, session: null, database: null, services: null,
    startedAt: null,
  });

  useEffect(() => {
    if (!token) return;

    return createEventSource(
      '/api/status/stream',
      {},
      {
        status: (data) => {
          setHealth({
            status: data.status,
            degraded: data.degraded || [],
            checkedAt: new Date(),
            build: data.build || null,
            session: data.session || null,
            database: data.database || null,
            services: data.services || null,
            startedAt: data.startedAt || null,
          });
        },
      },
      {
        getToken: () => token,
        onError: () => {
          setHealth((prev) => ({
            ...prev,
            status: 'DOWN',
            checkedAt: new Date(),
          }));
        },
      },
    );
  }, [token]);

  return health;
}
