import { useState, useEffect } from 'react';
import { getHealth } from '../api/healthApi.js';

/**
 * Polls backend health every 5 seconds.
 * @returns {{ status: string|null, checkedAt: Date|null }}
 */
export function useHealthStatus() {
  const [health, setHealth] = useState({ status: null, checkedAt: null });

  useEffect(() => {
    const poll = async () => {
      const s = await getHealth();
      setHealth({ status: s, checkedAt: new Date() });
    };

    poll();

    const interval = setInterval(poll, 5000);
    return () => clearInterval(interval);
  }, []);

  return health;
}
