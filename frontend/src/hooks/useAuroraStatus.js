import { useEffect, useRef, useState } from 'react';
import { getAuroraStatus } from '../api/auroraApi.js';

const POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

/**
 * Polls the aurora status endpoint every 5 minutes and on window focus.
 *
 * Returns null when the user is not eligible (free-tier, 403) or before
 * the first successful fetch.
 *
 * @returns {{ status: object|null, loading: boolean }}
 */
export function useAuroraStatus() {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const intervalRef = useRef(null);

  async function fetchStatus() {
    try {
      const data = await getAuroraStatus();
      setStatus(data); // null for 403 (free-tier) — component returns null
    } catch {
      // Network errors are transient — retain existing status
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    fetchStatus();
    intervalRef.current = setInterval(fetchStatus, POLL_INTERVAL_MS);

    function handleFocus() {
      fetchStatus();
    }
    window.addEventListener('focus', handleFocus);

    return () => {
      clearInterval(intervalRef.current);
      window.removeEventListener('focus', handleFocus);
    };
  }, []);

  return { status, loading };
}
