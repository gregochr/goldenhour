import { useEffect, useState } from 'react';
import { getAuroraForecastResults, getAuroraLocations } from '../api/auroraApi.js';
import { isRealAlert, ratedFromLive, ratedFromStored } from '../utils/auroraBannerScores.js';
import { resolveAuroraNight } from '../utils/mapDates.js';

/** What is published before anything has answered, and after the alert goes away. */
const NONE = Object.freeze({ ready: false, spots: Object.freeze([]) });

function published(spots) {
  return { ready: spots.length > 0, spots };
}

/**
 * Chooses what to publish from the two answers, or null when nothing should change.
 *
 * <p>The live scoring wins when it answered with at least one rated row — the banner is ABOUT the live
 * alert (docs/engineering/aurora-banner-plan.md §4 D2). Otherwise the stored run for the night in
 * progress serves. ⚠️ A failure is not an answer: if the live read failed, a stored read that ALSO
 * came back empty proves nothing, so nothing is published and the banner stays on whatever it had
 * (pending, never "no scores"). A stored read that carries rated rows is real data for the same night
 * and serves a live read that failed.
 */
function choose(live, stored) {
  // `ratedFrom*` answers null for a body that is not a list (a captive portal's 200): a failed read,
  // exactly like a rejection, never an answer of "no rated site".
  const liveRows = live.status === 'fulfilled' ? ratedFromLive(live.value) : null;
  if (liveRows && liveRows.length > 0) return published(liveRows);
  const storedRows = stored.status === 'fulfilled' ? ratedFromStored(stored.value) : null;
  // Empty is an answer only when nothing failed: live said none, and stored said none or carries some.
  if (storedRows && (storedRows.length > 0 || liveRows)) return published(storedRows);
  return null;
}

/**
 * Tonight's per-location aurora ratings, for the banner's thumbnail and its best line.
 *
 * <h2>Gated like the Map tab's live fetch</h2>
 * <p>Nothing is asked unless the status is alert-worthy and NOT simulated. A simulated alert is an
 * admin preview that must cost nothing and show nothing real (plan §4 D9); a LITE reader has a null
 * status, so reaches neither request. Both sources are asked in parallel — live
 * (`getAuroraLocations`, no date: the NOAA-triggered state for the night in progress) and stored
 * (`getAuroraForecastResults` for {@link resolveAuroraNight}, never the civil date) — and re-asked
 * whenever the status changes, which is every 5-minute poll, as the Map tab does.
 *
 * <h2>Late and failed answers</h2>
 * <p>An answer for a superseded status is dropped. A failure publishes nothing and keeps what was
 * held, so a flaky poll never blanks a field already on screen. When the alert ends, what was held is
 * discarded, so a later alert never opens on the earlier one's sites.
 *
 * @param {?object} status the shared aurora status ({@code useAuroraStatus().status})
 * @returns {{ready: boolean, spots: Array<{id: ?number, name: string, lat: number, lng: number,
 *   rid: string, stars: number}>}} `ready` is `spots.length > 0`
 */
export function useAuroraScores(status) {
  const eligible = isRealAlert(status);
  const [held, setHeld] = useState(NONE);
  const [wasEligible, setWasEligible] = useState(eligible);
  if (eligible !== wasEligible) {
    setWasEligible(eligible);
    if (!eligible) setHeld(NONE);
  }

  useEffect(() => {
    if (!eligible) return undefined;
    let cancelled = false;
    Promise.allSettled([
      getAuroraLocations({ maxBortle: 9, minStars: 1 }),
      getAuroraForecastResults(resolveAuroraNight(status)),
    ]).then(([live, stored]) => {
      if (cancelled) return;
      const next = choose(live, stored);
      if (next) setHeld(next);
    });
    return () => { cancelled = true; };
  }, [status, eligible]);

  return eligible ? held : NONE;
}
