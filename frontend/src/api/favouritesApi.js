import apiClient from './axiosClient.js';

const BASE_URL = '/api/user/settings/favourites';

/**
 * @typedef {object} FavouritesBody
 * @property {Array<{locationId: number, createdAt: string}>} places the starred places, oldest first
 * @property {Array<{areaId: number, createdAt: string}>} areas the starred areas, oldest first
 * @property {number[]} locationIds the starred places plus the enabled members of the starred areas, expanded by the
 *        server (the client never walks area membership)
 * @property {Array<{key: string, createdAt: string}>} dates the watched almanac dates (one-screen-plan.md D16), oldest
 *        watch first; a key is {@code type:startDate}, for example {@code king-tide:2026-12-03}
 */

/**
 * The reader's favourites (find-and-favourites-plan.md D2). Personal data, never cached. PRO_USER and ADMIN only: a
 * Lite reader's call is a 403, so the caller does not make it.
 *
 * @returns {Promise<FavouritesBody>}
 */
export async function fetchFavourites() {
  const response = await apiClient.get(BASE_URL);
  return response.data;
}

/**
 * Stars or un-stars one place or area, or watches or un-watches one almanac date. Idempotent on the server; every method
 * answers with the whole merged list. A new star or watch at the cap of 25 is a 409 whose {@code error} sentence is the
 * one to show.
 *
 * @param {'place'|'area'|'date'} kind what is starred
 * @param {number|string} id the location or area id, or the watched date's key
 * @param {boolean} on true to star or watch, false to undo
 * @returns {Promise<FavouritesBody>}
 */
export async function setFavourite(kind, id, on) {
  const path = `${BASE_URL}/${{ area: 'areas', date: 'dates' }[kind] ?? 'places'}/${id}`;
  const response = on ? await apiClient.post(path) : await apiClient.delete(path);
  return response.data;
}
