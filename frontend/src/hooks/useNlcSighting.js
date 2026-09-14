import { useEffect, useRef, useState } from 'react';
import { getNlcSighting } from '../api/nlcApi.js';

// Sightings are low-frequency and only meaningful during a short summer season,
// so a gentler cadence than the 5-minute aurora poll is fine.
const POLL_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes

/**
 * Polls the NLC sighting endpoint every 10 minutes and on window focus.
 *
 * Returns null when the user is not eligible (free-tier, 403), before the first
 * successful fetch, or when there is no active sighting to surface.
 *
 * Shaped like the aurora status provider (`AuroraStatusProvider`, which `useAuroraStatus` reads)
 * — the same poll-and-focus fetch and the same "a failed fetch keeps what is on screen" — so the
 * two banners behave alike.
 *
 * <p><b>Answers are applied in the order their requests were made, not the order they land.</b>
 * A poll and a focus, or two focuses, can have two requests out at once, and a request that finds
 * the backend's NLCNET cache stale scrapes the page before it answers — so the older request can
 * land second. Publishing whatever landed last let a report taken before it aged out put the
 * banner back up until the next poll or focus — even over a newer report the reader had
 * dismissed, since the dismissal is keyed by `reportedAt` and an older report reads as unseen —
 * and let a "nothing to show" taken before a report arrived take the banner down. So each request
 * is numbered, and an answer older than the one already applied is dropped. A null (401/403: a
 * reader the banner is not for) is an answer like any other here — applied, and it moves the mark
 * — unlike the Plan briefing's 204, which is not applied at all.
 *
 * <p>Request order stands in for data order, and not perfectly: a newer request whose scrape
 * fails fast answers from the cache as it stood, so it can land first and drop an older request's
 * slower, successful scrape until the next poll or focus. Accepted — it needs a fast failure to
 * race a slow success, and its cure is one scrape at a time on the backend, not a rule here.
 *
 * @returns {{ sighting: object|null, loading: boolean }}
 */
export function useNlcSighting() {
  const [sighting, setSighting] = useState(null);
  const [loading, setLoading] = useState(true);
  const intervalRef = useRef(null);
  // The two halves of that ordering: how many requests have been made, and the number of the
  // newest one whose answer has been applied. Refs rather than locals in the effect, so one
  // numbering spans StrictMode's second run of the effect, which makes its own request while the
  // first run's is still out.
  //
  // Deliberately not `useComingUpFeed`'s shape, where only the most recently MADE request may
  // write. Under that rule an older answer landing while a newer request is out is dropped even if
  // the newer one then fails, leaving the banner older than it needs to be. Here only an applied
  // answer moves the mark, so a failed request blocks nothing.
  const requestedRef = useRef(0);
  const appliedRef = useRef(0);

  useEffect(() => {
    async function fetchSighting() {
      requestedRef.current += 1;
      const request = requestedRef.current;
      try {
        const data = await getNlcSighting();
        if (request < appliedRef.current) return; // a newer answer is already on screen
        appliedRef.current = request;
        setSighting(data); // null for 403 (free-tier) — component returns null
      } catch {
        // Network errors are transient — retain existing sighting
      } finally {
        setLoading(false);
      }
    }

    fetchSighting();
    intervalRef.current = setInterval(fetchSighting, POLL_INTERVAL_MS);

    function handleFocus() {
      fetchSighting();
    }
    window.addEventListener('focus', handleFocus);

    return () => {
      clearInterval(intervalRef.current);
      window.removeEventListener('focus', handleFocus);
    };
  }, []);

  return { sighting, loading };
}
