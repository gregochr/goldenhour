import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getAuroraAlert, getAuroraStatus } from '../api/auroraApi.js';
import { useAuth } from './AuthContext.jsx';
import { useVisiblePoll } from '../hooks/useVisiblePoll.js';

const POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

/**
 * The longest the provider waits for a night's end before looking at the wall clock again. Browser
 * timers count on a clock that stops while the device sleeps, so one timer set for dawn can fire
 * hours after a laptop lid opens again; looking once a minute bounds that to a minute of waking. It
 * also keeps every delay far below the 2³¹−1 ms a timer can hold — a longer one fires at once, which
 * a device clock months behind the backend's would otherwise hit.
 */
const NIGHT_END_RECHECK_MS = 60 * 1000;

const AuroraStatusContext = createContext({ status: null, liteAlert: null, loading: false });

/**
 * Fetches aurora status once (5-minute poll while visible + refetch on return or focus after a minute) and shares it via context,
 * so the multiple consumers ({@code AuroraBanner}, {@code MapView}, {@code StartRunPanel}) no
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
  const { role } = useAuth();
  // A LITE reader is not allowed `/api/aurora/status` (Pro and Admin only), so it polls the role-free
  // `/api/aurora/alert` instead (docs/engineering/aurora-lite-alert-plan.md §2.3) and the answer lands in
  // `liteAlert`; `status` stays null for LITE, so every Pro-shaped consumer sees what it always saw.
  const isLite = role === 'LITE_USER';
  const [status, setStatus] = useState(null);
  const [liteAlert, setLiteAlert] = useState(null);
  const [loading, setLoading] = useState(true);
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
  // ⚠️ The effect is keyed on the role, which picks the endpoint, so a request from a run the change
  // superseded must not land its answer over the new one: each request carries the run number (`runRef`) it started under, as
  // `useAuroraViewline` does. The counters still span runs (one numbering for whichever endpoint is polled).
  const requestedRef = useRef(0);
  const appliedRef = useRef(0);

  // A status answers for the night in progress only until its `currentNightEndsAt`
  // (`mapDates.resolveAuroraNight`), and while the polls are failing nothing is sure to ask again at
  // that instant: a failed poll changes no state, and `MapView` is memoised, so its night list
  // re-reads the night only when something re-renders it. So the provider re-renders once the end has
  // passed, and every consumer with it — App's date clamp and the map's night list re-read the night
  // in the same commit, as they do when a fresh status lands.
  const [nightEndedTick, setNightEnded] = useState(0);
  const nightEndsAt = liteAlert?.currentNightEndsAt ?? status?.currentNightEndsAt ?? null;
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

  // Which run of the role-keyed fetch a request belongs to. Bumped when `isLite` changes (and on unmount), so
  // a request from a superseded run drops its answer; declared BEFORE the poll so its effect runs first.
  const runRef = useRef(0);
  useEffect(() => {
    runRef.current += 1;
    return () => { runRef.current += 1; };
  }, [isLite]);

  const fetchStatus = useCallback(async () => {
    const run = runRef.current;
    requestedRef.current += 1;
    const request = requestedRef.current;
    try {
      const data = isLite ? await getAuroraAlert() : await getAuroraStatus();
      if (run !== runRef.current) return; // this run was superseded (the role changed)
      if (request < appliedRef.current) return; // a newer answer is already on screen
      appliedRef.current = request;
      // An unchanged reply is the same object (the API functions memoise the parse), so this is a
      // no-op then and the context value below does not change.
      if (isLite) setLiteAlert(data);
      else setStatus(data); // null for 403 (free-tier) — consumers render nothing
    } catch {
      // Network errors are transient — retain existing status
    } finally {
      if (run === runRef.current) setLoading(false);
    }
  }, [isLite]);

  // A change of `isLite` changes `fetchStatus`, which `useVisiblePoll` fetches on at once.
  useVisiblePoll(fetchStatus, POLL_INTERVAL_MS);

  // `nightEndedTick` is in the deps although the value does not read it: the provider re-renders when a night
  // ends precisely so that every consumer re-reads the clock, and a memoised value that did not change would
  // stop the re-render at the provider. It moves once per night end, not per poll.
  // eslint-disable-next-line react-hooks/exhaustive-deps -- see above
  const value = useMemo(() => ({ status, liteAlert, loading }), [status, liteAlert, loading, nightEndedTick]);

  return (
    <AuroraStatusContext.Provider value={value}>
      {children}
    </AuroraStatusContext.Provider>
  );
}

AuroraStatusProvider.propTypes = {
  children: PropTypes.node,
};

/**
 * Reads the shared aurora status. Returns {@code { status: null, liteAlert: null, loading: false }}
 * when used outside an {@link AuroraStatusProvider}, so consumers degrade gracefully. {@code status}
 * is the Pro-shaped status (Pro and Admin only); {@code liteAlert} is the level-only alert a LITE
 * reader gets instead (null for everyone else).
 *
 * @returns {{ status: object|null, liteAlert: object|null, loading: boolean }}
 */
export function useAuroraStatusContext() {
  return useContext(AuroraStatusContext);
}
