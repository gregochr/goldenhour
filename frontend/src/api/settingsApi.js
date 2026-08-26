import apiClient from './axiosClient.js';

const BASE_URL = '/api/user/settings';

/**
 * Fetches the current user's settings (profile + home location).
 * @returns {Promise<Object>} UserSettingsResponse
 */
export async function getSettings() {
  const response = await apiClient.get(BASE_URL);
  return response.data;
}

/**
 * Looks up a UK postcode, returning coordinates and place name.
 * @param {string} postcode
 * @returns {Promise<Object>} PostcodeLookupResult
 */
export async function lookupPostcode(postcode) {
  const response = await apiClient.post(`${BASE_URL}/home/lookup`, { postcode });
  return response.data;
}

/**
 * Saves the user's confirmed home location.
 * @param {string} postcode
 * @param {number} latitude
 * @param {number} longitude
 * @param {number|null} [localRadiusMiles] Close to home radius in miles, or null to leave the
 *        stored value alone — omitting it is not a reset.
 * @returns {Promise<Object>} UserSettingsResponse
 */
export async function saveHome(postcode, latitude, longitude, localRadiusMiles = null) {
  const response = await apiClient.put(`${BASE_URL}/home`, {
    postcode, latitude, longitude, localRadiusMiles,
  });
  return response.data;
}

/**
 * Recalculates drive times from the user's home to all locations.
 * @returns {Promise<Object>} DriveTimeRefreshResponse
 */
export async function refreshDriveTimes() {
  const response = await apiClient.post(`${BASE_URL}/drive-times/refresh`);
  return response.data;
}

/**
 * Fetches the current user's per-location drive times.
 * @returns {Promise<Object>} Map of locationId → minutes
 */
export async function getDriveTimes() {
  const response = await apiClient.get(`${BASE_URL}/drive-times`);
  return response.data;
}

/**
 * Saves the caller's map colour preferences.
 *
 * <p>Its own endpoint rather than fields on `saveHome`: a colour preference is not home-derived,
 * so folding it into that request would deserialise the home fields to null and wipe a saved
 * postcode.
 *
 * @param {'temp'|'verdict'} mapColourScale which ramp paints the map
 * @returns {Promise<Object>} UserSettingsResponse
 */
export async function saveMapColourPreferences(mapColourScale) {
  const response = await apiClient.put(`${BASE_URL}/map-colours`, { mapColourScale });
  return response.data;
}

/**
 * Fetches the caller's reach — drive minutes and distance — for the whole enabled roster.
 *
 * <p>The per-user half of the window-first spot strip's two-contract join. It is deliberately
 * separate from `/api/briefing`, which is ETag-revalidated and therefore persists its body to a
 * browser HTTP cache JavaScript cannot evict on logout; `HttpCachingConfig` keeps
 * `/api/user/settings*` out of that filter for exactly this reason. The client joins the two on
 * `locationId`.
 *
 * <p>Every enabled location is present, with null figures for a caller who has saved no home
 * postcode — the normal first-run state. An absent figure means "unknown", never "out of reach".
 *
 * @returns {Promise<Array<{locationId: number, driveMinutes?: number, distanceMiles?: number}>>}
 */
export async function getReach() {
  const response = await apiClient.get(`${BASE_URL}/reach`);
  return response.data;
}
