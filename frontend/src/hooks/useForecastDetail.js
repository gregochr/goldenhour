import { useEffect, useState } from 'react';
import { getForecastDetail, peekForecastDetail, cacheForecastDetail } from '../api/forecastApi.js';

/**
 * Resolves the full detail object for a (slim) forecast slot with the precedence:
 *   1. object already carries full detail ({@code lowCloud !== undefined}) → use it as-is, never
 *      fetch;
 *   2. rich slim row ({@code id != null}) → fetch (and cache, in {@code forecastApi}) the detail
 *      row; report {@code detailLoading} while in flight;
 *   3. sparse cached-only row ({@code id == null}) → nothing to fetch, use the slot itself.
 *
 * <p>The one resolution the location sheet (overlay convergence C2) and the since-deleted `MarkerPopupContent` (which can be
 * handed a slot with NO id and so never fetches) share. The cache
 * is `forecastApi`'s — cleared on logout — so a second surface paints from it with no second
 * request. A FAILED fetch is not memoised (it is remembered for this mount only, so the next mount
 * retries).
 *
 * @param {?object} forecast the slim forecast slot, or null
 * @returns {{detail: ?object, detailLoading: boolean}} the resolved detail and its load state
 */
export default function useForecastDetail(forecast) {
  const hasInlineDetail = forecast != null && forecast.lowCloud !== undefined;
  const id = forecast != null ? forecast.id : null;
  const needsFetch = !hasInlineDetail && id != null;

  // Re-render trigger only; the detail is derived from the cache during render so it always tracks
  // the current id (no stale carry-over when the slot changes). `failedId` is the id whose fetch
  // failed in THIS mount: a failure is deliberately not cached, so the next mount retries.
  const [, setTick] = useState(0);
  const [failedId, setFailedId] = useState(null);

  useEffect(() => {
    if (!needsFetch) return undefined;
    // A fetch (this instance's, cancelled by an id flip, or a concurrent surface's) may have filled
    // the cache between this render's commit and this effect. Force one re-read so the loading
    // state cannot strand. It cannot loop: a tick-only re-render leaves the deps unchanged.
    if (peekForecastDetail(id).cached) {
      (async () => setTick((n) => n + 1))();
      return undefined;
    }
    let cancelled = false;
    getForecastDetail(id)
      .then((data) => {
        // An unmounted (or re-keyed) instance writes neither the cache nor state.
        if (cancelled) return;
        cacheForecastDetail(id, data);
        setTick((n) => n + 1);
      })
      .catch(() => {
        if (!cancelled) setFailedId(id);
      });
    return () => { cancelled = true; };
  }, [needsFetch, id]);

  if (hasInlineDetail) return { detail: forecast, detailLoading: false };
  if (!needsFetch) return { detail: forecast, detailLoading: false };
  const { cached, detail } = peekForecastDetail(id);
  if (cached) return { detail, detailLoading: false };
  // Failed this mount: say nothing more about it (no skeleton, no badges); a remount asks again.
  if (failedId === id) return { detail: null, detailLoading: false };
  return { detail: null, detailLoading: true };
}
