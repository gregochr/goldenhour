import axios from 'axios';

const BASE_URL = '/api/aurora';

/**
 * Fetches the current NOAA SWPC aurora alert status.
 *
 * Returns null if the request fails or returns 403 (free-tier user).
 *
 * @returns {Promise<object|null>} the status object or null
 */
export async function getAuroraStatus() {
  try {
    const response = await axios.get(`${BASE_URL}/status`);
    return response.data;
  } catch (err) {
    if (err.response?.status === 403 || err.response?.status === 401) {
      return null; // Free-tier user — don't show aurora UI
    }
    throw err;
  }
}

/**
 * Triggers a Bortle light-pollution enrichment run for all unenriched locations.
 * ADMIN only.
 *
 * @returns {Promise<object>} { status, runType, jobRunId }
 */
export async function enrichBortle() {
  const response = await axios.post(`${BASE_URL}/admin/enrich-bortle`);
  return response.data;
}

/**
 * Triggers an immediate NOAA SWPC aurora orchestration cycle.
 * ADMIN only.
 *
 * @returns {Promise<object>} { status, action }
 */
export async function triggerAuroraRun() {
  const response = await axios.post(`${BASE_URL}/admin/run`);
  return response.data;
}

/**
 * Resets the aurora state machine to IDLE and clears all cached scores.
 * ADMIN only.
 *
 * @returns {Promise<object>} { status }
 */
export async function resetAuroraState() {
  const response = await axios.post(`${BASE_URL}/admin/reset`);
  return response.data;
}

/**
 * Fetches scored aurora-eligible locations, filtered by Bortle class and star rating.
 *
 * @param {object} [params]
 * @param {number} [params.maxBortle=4] - maximum Bortle class to include
 * @param {number} [params.minStars=1]  - minimum star rating to include
 * @returns {Promise<Array>} list of scored locations
 */
export async function getAuroraLocations({ maxBortle = 4, minStars = 1 } = {}) {
  const response = await axios.get(`${BASE_URL}/locations`, {
    params: { maxBortle, minStars },
  });
  return response.data;
}
