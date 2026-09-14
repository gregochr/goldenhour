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
 * <p><b>Answers are applied in the order their requests were made, not the order they land.</b>
 * A poll and a focus, or two focuses, can have two requests out at once, and each can wait on live
 * NOAA fetches of its own, which the backend caches separately for 1 to 15 minutes — so the older
 * request can land second. Publishing whatever landed last let a status taken before an alert
 * ended put the banner back up and switch the viewline back on until the next poll or focus, and
 * let one taken before an alert began hide the banner, clear the map's live scores and — with no
 * stored aurora run to keep the mode available — bounce the Map tab out of aurora mode. So each
 * request is numbered, and an answer older than the one already applied is dropped.
 *
 * @param {{children: React.ReactNode}} props
 */
export function AuroraStatusProvider({ children }) {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const intervalRef = useRef(null);
  // The two halves of that ordering: how many requests have been made, and the number of the
  // newest one whose answer has been applied. Refs rather than locals in the effect, so one
  // numbering spans StrictMode's second run of the effect, which makes its own request while the
  // first run's is still out.
  //
  // Deliberately not `useComingUpFeed`'s shape, where only the most recently MADE request may
  // write — a shape that is right there and wrong here, for a reason to check before copying
  // either. Its requests can ask different questions: two overlap only across a date roll, so the
  // older one asks for yesterday's feed and its answer is wrong, not just older. Every status
  // request asks the same question, so an older answer is only older, and dropping it while a
  // newer request is out would leave the status older than it needs to be if that request then
  // failed. Here only an applied answer moves the mark, so a failed request blocks nothing.
  //
  // ⚠️ Both rest on what is true today: the effect has no dependencies, so every request asks one
  // unkeyed question, and the catch writes nothing. The cleanup cancels nothing, so if the effect
  // ever gains a dependency (a role, say), a request from a run that the change superseded would
  // still carry a number, and could land an answer to the old question over the new one. It would
  // then need a per-run `cancelled` as well, as `useAuroraViewline` has.
  const requestedRef = useRef(0);
  const appliedRef = useRef(0);

  useEffect(() => {
    async function fetchStatus() {
      requestedRef.current += 1;
      const request = requestedRef.current;
      try {
        const data = await getAuroraStatus();
        if (request < appliedRef.current) return; // a newer answer is already on screen
        appliedRef.current = request;
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
