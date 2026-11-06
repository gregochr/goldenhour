import apiClient from './axiosClient.js';

/**
 * Fetches the enabled locations that at least one measured home or region base has no route to
 * (reach-scoped-card-plan R1b). ADMIN only; the server answers 403 to anyone else.
 *
 * <p>Admin data on its own no-store path: never cached by the browser, never ETag-shared.
 *
 * @returns {Promise<Array<{locationId: number, name: string, unroutableHomes: number, judgedHomes: number,
 *   unroutableBases: number, judgedBases: number}>>} the locations, in name order
 */
export async function fetchUnroutableLocations() {
  const response = await apiClient.get('/api/admin/drive-times/unroutable');
  return response.data;
}

/**
 * Looks up places to choose as a location's drive point, by village name or postcode (reach-scoped-card-plan R1c).
 * ADMIN only. Named places come back nearest first and within 25 km of the location; a postcode comes back as the
 * one place it names. No match is an empty array, not an error; a blank query is a 400 and an unknown postcode too.
 *
 * @param {string} q the village name or postcode typed
 * @param {number} lat the location's latitude
 * @param {number} lon the location's longitude
 * @returns {Promise<Array<{name: string, detail: string, lat: number, lon: number, distanceKm: number}>>}
 */
export async function fetchDrivePointPlaces(q, lat, lon) {
  const response = await apiClient.get('/api/admin/drive-times/places', { params: { q, lat, lon } });
  return response.data;
}
