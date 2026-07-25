import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getAuroraStatus } from '../api/auroraApi.js';

const POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

const AuroraStatusContext = createContext({ status: null, loading: false });

/**
 * Fetches aurora status once (5-minute poll + refetch on window focus) and shares it via context,
 * so the multiple consumers ({@code AuroraBanner}, {@code MapView}, {@code JobRunsMetricsView}) no
 * longer each fire their own request and poll — one fetch serves the whole tree.
 *
 * @param {{children: React.ReactNode}} props
 */
export function AuroraStatusProvider({ children }) {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const intervalRef = useRef(null);

  useEffect(() => {
    async function fetchStatus() {
      try {
        const data = await getAuroraStatus();
        setStatus(data); // null for 403 (free-tier) — consumers render nothing
      } catch {
        // Network errors are transient — retain existing status
      } finally {
        setLoading(false);
      }
    }

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

  return (
    <AuroraStatusContext.Provider value={{ status, loading }}>
      {children}
    </AuroraStatusContext.Provider>
  );
}

AuroraStatusProvider.propTypes = {
  children: PropTypes.node,
};

/**
 * Reads the shared aurora status. Returns {@code { status: null, loading: false }} when used
 * outside an {@link AuroraStatusProvider}, so consumers degrade gracefully.
 *
 * @returns {{ status: object|null, loading: boolean }}
 */
export function useAuroraStatusContext() {
  return useContext(AuroraStatusContext);
}
