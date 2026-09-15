import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getAuroraStatus } from '../api/auroraApi.js';

const POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

/**
 * The longest the provider waits for a night's end before looking at the wall clock again. Browser
 * timers count on a clock that stops while the device sleeps, so one timer set for dawn can fire
 * hours after a laptop lid opens again; looking once a minute bounds that to a minute of waking. It
 * also keeps every delay far below the 2³¹−1 ms a timer can hold — a longer one fires at once, which
 * a device clock months behind the backend's would otherwise hit.
 */
const NIGHT_END_RECHECK_MS = 60 * 1000;

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

  // A status answers for the night in progress only until its `currentNightEndsAt`
  // (`mapDates.resolveAuroraNight`), and while the polls are failing nothing is sure to ask again at
  // that instant: a failed poll changes no state, and `MapView` is memoised, so its night list
  // re-reads the night only when something re-renders it. So the provider re-renders once the end has
  // passed, and every consumer with it — App's date clamp and the map's night list re-read the night
  // in the same commit, as they do when a fresh status lands.
  const [, setNightEnded] = useState(0);
  const nightEndsAt = status?.currentNightEndsAt ?? null;
  useEffect(() => {
    const end = Date.parse(nightEndsAt);
    if (Number.isNaN(end)) return undefined; // no end, or one that does not parse
    let timer;
    // It looks at the wall clock at least once a minute rather than trusting one long timer, which a
    // sleeping device leaves hours late (`NIGHT_END_RECHECK_MS`), and re-renders on the first look
    // that finds the end passed. The first look is immediate, even for an end that already looks
    // past: the consumers read the clock when they rendered, before this effect, so the end may have
    // passed in between, and one extra render settles it.
    const look = () => {
      const remaining = end - Date.now();
      if (remaining > 0) timer = setTimeout(look, Math.min(remaining, NIGHT_END_RECHECK_MS));
      else setNightEnded((n) => n + 1);
    };
    timer = setTimeout(look, 0);
    return () => clearTimeout(timer);
  }, [nightEndsAt]);

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
