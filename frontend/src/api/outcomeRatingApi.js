import apiClient from './axiosClient.js';

const BASE_URL = '/api';

/**
 * The reader's own outcome ratings (`docs/engineering/outcome-rating-plan.md` §2 #1) — "how did that
 * sunrise or sunset actually look", recorded from the Map tab's callout on a window that has passed.
 *
 * <p>Nothing here is cached. The rows are personal, and `/api/outcome/ratings*` is on
 * `HttpCachingConfigTest`'s never-filtered list, so the browser never persists a body a logout
 * cannot reach; `hooks/useOutcomeRatings.js` holds them in component state alone and never touches
 * `utils/swrCache.js`.
 */

/**
 * The caller's own ratings with an event date in `[from, to]` — at most 31 days (the server's cap,
 * 400 beyond it). A LITE account has none and gets `[]`.
 *
 * @param {string} from first event date, `YYYY-MM-DD`
 * @param {string} to last event date, `YYYY-MM-DD`
 * @returns {Promise<Array<{locationId: number, locationName: string, eventDate: string,
 *   eventType: string, rating: ?number, updatedAt: string, forecastRunAt: ?string,
 *   pipelineRunId: ?number, forecastRating: ?number, seen: ?string}>>} `seen` is absent when the
 *   reader gave no tag
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
 * @param {?string} [body.seen] what the reader says they saw (valley-mist-plan M3): `COLOUR`, `MIST`,
 *   `INVERSION`, `FLAT` or `NOT_SEEN`. THREE states, and the difference is the contract: left out
 *   (`undefined`) the server keeps the earlier tag; `null` clears it; a name sets it. `NOT_SEEN`
 *   goes with a null rating only, a sighting with a star only (400 otherwise).
 * @returns {Promise<{locationId: number, eventDate: string, eventType: string, rating: ?number,
 *   updatedAt: string, seen: ?string}>} the stored row (`seen` is absent when there is no tag)
 */
export async function putRating({
  locationId, eventDate, eventType, rating, seen,
}) {
  const body = {
    locationId, eventDate, eventType, rating,
  };
  // `undefined` is "not sent" (JSON drops it); `null` is a deliberate clear and must go on the wire.
  if (seen !== undefined) body.seen = seen;
  const response = await apiClient.put(`${BASE_URL}/outcome/ratings`, body);
  return response.data;
}

/**
 * Every user's rating of one (location, date, event) — the Admin's "All ratings" list
 * (`docs/engineering/outcome-rating-plan.md` §2 #7). ADMIN only: any other role is refused 403, so
 * the caller never asks for it.
 *
 * <p>The rows come newest first with {@code mine} true on the caller's own; the forecast the ratings
 * were recorded against rides the head. A slot nobody has rated still answers, with an empty
 * {@code ratings} and the run the slot resolves to. Per-slot and never cached — the list is its own
 * fetch (on the box's first expand), not part of the range read `fetchMyRatings` makes.
 *
 * @param {number} locationId
 * @param {string} date `YYYY-MM-DD`
 * @param {'SUNRISE'|'SUNSET'} eventType
 * @returns {Promise<{eventTime: ?string, forecastRunAt: ?string, pipelineRunId: ?number,
 *   ratings: Array<{username: string, rating: ?number, updatedAt: string, mine: boolean,
 *   seen: ?string}>}>}
 */
export async function fetchAllRatingsForSlot(locationId, date, eventType) {
  const response = await apiClient.get(`${BASE_URL}/outcome/ratings/all`, {
    params: { locationId, date, eventType },
  });
  return response.data;
}
