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
 * Mirrors useAuroraStatus so the two banners behave identically.
 *
 * @returns {{ sighting: object|null, loading: boolean }}
 */
export function useNlcSighting() {
  const [sighting, setSighting] = useState(null);
  const [loading, setLoading] = useState(true);
  const intervalRef = useRef(null);

  useEffect(() => {
    async function fetchSighting() {
      try {
        const data = await getNlcSighting();
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
