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

/**
 * Returns a 3-night Kp preview (tonight, T+1, T+2) for the night selector popup.
 * Reads cached NOAA data — no Claude cost.
 *
 * @returns {Promise<{nights: Array}>} preview with per-night Kp data
 */
export async function getAuroraForecastPreview() {
  const response = await axios.get(`${BASE_URL}/forecast/preview`);
  return response.data;
}

/**
 * Runs aurora forecasts for the selected nights and stores results to the database.
 * Each viable night triggers one Claude API call.
 *
 * @param {string[]} nights - ISO date strings (e.g. ['2026-03-21', '2026-03-22'])
 * @returns {Promise<{nights: Array, totalClaudeCalls: number, estimatedCost: string}>}
 */
export async function runAuroraForecast(nights) {
  const response = await axios.post(`${BASE_URL}/forecast/run`, { nights });
  return response.data;
}

/**
 * Fetches stored aurora forecast results for the map view for a given night.
 *
 * @param {string} date - ISO date string (e.g. '2026-03-21')
 * @returns {Promise<Array>} list of location results with stars, summary, and factor breakdown
 */
export async function getAuroraForecastResults(date) {
  const response = await axios.get(`${BASE_URL}/forecast/results`, { params: { date } });
  return response.data;
}

/**
 * Returns all ISO dates for which stored aurora forecast results exist.
 * Used to determine whether the Aurora toggle should appear on the map.
 *
 * @returns {Promise<string[]>} sorted list of ISO date strings
 */
export async function getAuroraForecastAvailableDates() {
  try {
    const response = await axios.get(`${BASE_URL}/forecast/results/available-dates`);
    return response.data;
  } catch (err) {
    if (err.response?.status === 403 || err.response?.status === 401) {
      return []; // Free-tier — no stored aurora results available
    }
    throw err;
  }
}
