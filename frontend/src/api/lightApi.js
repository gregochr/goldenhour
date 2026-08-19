import apiClient from './axiosClient.js';

/**
 * Today's light at the caller's home — the masthead's light rule and its labelled time row.
 *
 * <p>Deliberately its own endpoint under `/api/user/settings`, not a field on the shared briefing
 * payload: the response names the caller's home postcode, and `/api/briefing` is ETag-revalidated,
 * which persists a body to a browser HTTP cache JavaScript cannot evict on logout. Same rule that
 * keeps "Close to home" and the reach lens off that payload.
 *
 * <p>Resolves to `null` for a caller who has saved no home postcode — the normal first-run state,
 * answered by a `204` rather than an error, because the masthead has a designed empty state for it.
 * A failed request resolves to `null` too: the band degrades to its nudge, which is a worse first
 * impression than the rule but a better one than a broken header.
 *
 * @returns {Promise<{label: string, shortLabel: string, civilDawn: string, sunrise: string,
 *   sunset: string, civilDusk: string, stops: Array<{key: string, position: number}>}|null>}
 */
export async function getTodaysLight() {
  const response = await apiClient.get('/api/user/settings/light');
  // 204 leaves axios with an empty-string body. Checking for the stops rather than for the status
  // also rejects a malformed 200, which is the shape the renderer actually depends on.
  return response.data?.stops ? response.data : null;
}
