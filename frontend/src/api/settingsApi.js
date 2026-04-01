import axios from 'axios';

const BASE_URL = '/api/user/settings';

/**
 * Fetches the current user's settings (profile + home location).
 * @returns {Promise<Object>} UserSettingsResponse
 */
export async function getSettings() {
  const response = await axios.get(BASE_URL);
  return response.data;
}

/**
 * Looks up a UK postcode, returning coordinates and place name.
 * @param {string} postcode
 * @returns {Promise<Object>} PostcodeLookupResult
 */
export async function lookupPostcode(postcode) {
  const response = await axios.post(`${BASE_URL}/home/lookup`, { postcode });
  return response.data;
}

/**
 * Saves the user's confirmed home location.
 * @param {string} postcode
 * @param {number} latitude
 * @param {number} longitude
 * @returns {Promise<Object>} UserSettingsResponse
 */
export async function saveHome(postcode, latitude, longitude) {
  const response = await axios.put(`${BASE_URL}/home`, { postcode, latitude, longitude });
  return response.data;
}

/**
 * Recalculates drive times from the user's home to all locations.
 * @returns {Promise<Object>} DriveTimeRefreshResponse
 */
export async function refreshDriveTimes() {
  const response = await axios.post(`${BASE_URL}/drive-times/refresh`);
  return response.data;
}

/**
 * Fetches the current user's per-location drive times.
 * @returns {Promise<Object>} Map of locationId → minutes
 */
export async function getDriveTimes() {
  const response = await axios.get(`${BASE_URL}/drive-times`);
  return response.data;
}
