import apiClient from './axiosClient.js';

const BASE_URL = '/api';

/**
 * The reader's own outcome ratings (`docs/engineering/outcome-rating-plan.md` §2 #1) — "how did that
 * sunrise or sunset actually look", recorded from the Map tab's callout on a window that has passed.
 *
 * <p>Nothing here is cached. The rows are personal, and `/api/outcome/ratings*` is on
 * `HttpCachingConfigTest`'s never-filtered list, so the browser never persists a body a logout
 * cannot reach; `hooks/useOutcomeRatings.js` holds them in component state alone and never touches
 * `utils/swrCache.js`. This is a different record from the older `actual_outcome` one that
 * `forecastApi.js`'s `fetchOutcomes`/`recordOutcome` read and write.
 */

/**
 * The caller's own ratings with an event date in `[from, to]` — at most 31 days (the server's cap,
 * 400 beyond it). A LITE account has none and gets `[]`.
 *
 * @param {string} from first event date, `YYYY-MM-DD`
 * @param {string} to last event date, `YYYY-MM-DD`
 * @returns {Promise<Array<{locationId: number, locationName: string, eventDate: string,
 *   eventType: string, rating: ?number, updatedAt: string, forecastRunAt: ?string,
 *   pipelineRunId: ?number, forecastRating: ?number}>>}
 */
export async function fetchMyRatings(from, to) {
  const response = await apiClient.get(`${BASE_URL}/outcome/ratings`, { params: { from, to } });
  return response.data;
}

/**
 * Records, or replaces, the caller's rating of one (location, date, event). `rating` 1..5 is a
 * star; `null` is "didn't see it" — a row's existence is the answer, so null is a value, never an
 * absence. Pro and Admin only: a LITE account is refused 403, a window that has not passed (or is
 * older than the server keeps evidence for) 400.
 *
 * @param {object} body
 * @param {number} body.locationId
 * @param {string} body.eventDate `YYYY-MM-DD`
 * @param {'SUNRISE'|'SUNSET'} body.eventType
 * @param {?number} body.rating 1..5, or null for "not seen"
 * @returns {Promise<{locationId: number, eventDate: string, eventType: string, rating: ?number,
 *   updatedAt: string}>} the stored row
 */
export async function putRating({
  locationId, eventDate, eventType, rating,
}) {
  const response = await apiClient.put(`${BASE_URL}/outcome/ratings`, {
    locationId, eventDate, eventType, rating,
  });
  return response.data;
}
