import { useEffect, useRef, useState } from 'react';
import { getAuroraViewline } from '../api/auroraApi.js';

const POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

/**
 * Polls the aurora viewline endpoint every 5 minutes.
 *
 * Only fetches when `enabled` is true (PRO/ADMIN user with aurora active at
 * MODERATE or STRONG level).
 *
 * @param {boolean} enabled whether to poll
 * @returns {{ viewline: object|null }}
 */
export function useAuroraViewline(enabled) {
  const [viewline, setViewline] = useState(null);
  const intervalRef = useRef(null);

  useEffect(() => {
    if (!enabled) {
      setViewline(null);
      return;
    }

    async function fetchViewline() {
      try {
        const data = await getAuroraViewline();
        setViewline(data);
      } catch {
        // Transient error — retain existing viewline
      }
    }

    fetchViewline();
    intervalRef.current = setInterval(fetchViewline, POLL_INTERVAL_MS);

    return () => {
      clearInterval(intervalRef.current);
    };
  }, [enabled]);

  return { viewline };
}
