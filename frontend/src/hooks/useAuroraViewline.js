import { useEffect, useRef, useState } from 'react';
import { getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';

const POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

/**
 * Polls the aurora viewline endpoint every 5 minutes.
 *
 * Only fetches when `enabled` is true (PRO/ADMIN user with aurora active at
 * MODERATE or STRONG level).
 *
 * When `triggerType` is `'forecast'`, fetches the forecast viewline once (no poll).
 * Otherwise fetches the live OVATION viewline with 5-minute polling.
 *
 * @param {boolean} enabled whether to poll
 * @param {string|null} triggerType 'forecast' or 'realtime' (or null)
 * @returns {{ viewline: object|null }}
 */
export function useAuroraViewline(enabled, triggerType = null) {
  const [viewline, setViewline] = useState(null);
  const intervalRef = useRef(null);

  useEffect(() => {
    if (!enabled) {
      setViewline(null);
      return;
    }

    const isForecast = triggerType === 'forecast';

    async function fetchViewline() {
      try {
        const data = isForecast
          ? await getAuroraForecastViewline()
          : await getAuroraViewline();
        setViewline(data);
      } catch {
        // Transient error — retain existing viewline
      }
    }

    fetchViewline();

    // Forecast viewline is a deterministic table lookup — no need to poll
    if (!isForecast) {
      intervalRef.current = setInterval(fetchViewline, POLL_INTERVAL_MS);
    }

    return () => {
      clearInterval(intervalRef.current);
    };
  }, [enabled, triggerType]);

  return { viewline };
}
