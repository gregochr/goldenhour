import apiClient from './axiosClient.js';

const BASE_URL = '/api/nlc';

/**
 * Fetches the current noctilucent-cloud sighting signal.
 *
 * This is a REACTIVE community signal (crowdsourced observer reports, e.g.
 * NLCNET), NOT a geophysical forecast — no public model reaches the mesosphere
 * (~80km). The backend is expected to return an object only when there is a
 * genuinely relevant, fresh sighting; otherwise it returns null / an object
 * with `active: false`.
 *
 * Returns null if the request fails or returns 403 (free-tier user), matching
 * getAuroraStatus().
 *
 * @returns {Promise<object|null>} the sighting object or null
 */
export async function getNlcSighting() {
  try {
    const response = await apiClient.get(`${BASE_URL}/sighting`);
    return response.data;
  } catch (err) {
    if (err.response?.status === 403 || err.response?.status === 401) {
      return null; // Free-tier user — don't show NLC UI
    }
    throw err;
  }
}
