import { useState, useEffect } from 'react';
import { getHealth } from '../api/healthApi.js';

/**
 * Polls backend health every 30 seconds.
 *
 * @returns {{ status: string|null, degraded: string[], checkedAt: Date|null }}
 */
export function useHealthStatus() {
  const [health, setHealth] = useState({ status: null, degraded: [], checkedAt: null });

  useEffect(() => {
    const poll = async () => {
      const result = await getHealth();
      setHealth({ ...result, checkedAt: new Date() });
    };

    poll();

    const interval = setInterval(poll, 30000);
    return () => clearInterval(interval);
  }, []);

  return health;
}
