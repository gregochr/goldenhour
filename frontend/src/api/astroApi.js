import axios from 'axios';

const BASE_URL = '/api/astro';

/**
 * Fetches astro observing conditions for all dark-sky locations on a given date.
 *
 * @param {string} date - ISO date string (e.g. '2026-04-01')
 * @returns {Promise<Array>} list of condition scores, one per location
 */
export async function getAstroConditions(date) {
  const response = await axios.get(`${BASE_URL}/conditions`, { params: { date } });
  return response.data;
}

/**
 * Fetches all dates that have stored astro condition results.
 *
 * @returns {Promise<string[]>} distinct forecast dates in ascending order
 */
export async function getAstroAvailableDates() {
  const response = await axios.get(`${BASE_URL}/conditions/available-dates`);
  return response.data;
}
