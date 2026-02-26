import { useState, useEffect } from 'react';
import { getHealth } from '../api/healthApi.js';

/**
 * Polls backend health every 30 seconds.
 * @returns {string|null} "UP", "DOWN", or null (unknown/initializing)
 */
export function useHealthStatus() {
  const [status, setStatus] = useState(null);

  useEffect(() => {
    // Poll immediately on mount
    const poll = async () => {
      const s = await getHealth();
      setStatus(s);
    };

    poll();

    // Then poll every 5 seconds
    const interval = setInterval(poll, 5000);
    return () => clearInterval(interval);
  }, []);

  return status;
}
