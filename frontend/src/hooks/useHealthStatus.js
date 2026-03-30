import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext.jsx';

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
  });

  useEffect(() => {
    if (!token) return;

    const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
    const url = `${baseUrl}/api/status/stream?token=${encodeURIComponent(token)}`;
    const es = new EventSource(url);

    es.addEventListener('status', (event) => {
      try {
        const data = JSON.parse(event.data);
        setHealth({
          status: data.status,
          degraded: data.degraded || [],
          checkedAt: new Date(),
          build: data.build || null,
          session: data.session || null,
          database: data.database || null,
          services: data.services || null,
        });
      } catch {
        // Ignore malformed events
      }
    });

    es.onerror = () => {
      setHealth((prev) => ({
        ...prev,
        status: prev.status === null ? 'DOWN' : prev.status,
      }));
    };

    return () => es.close();
  }, [token]);

  return health;
}
