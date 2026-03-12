import axios from 'axios';

/**
 * Fetches tide height statistics for all coastal locations.
 *
 * @returns {Promise<Object>} Map of location name to TideStats object.
 */
export async function fetchAllTideStats() {
  const res = await axios.get('/api/tides/stats/all');
  return res.data;
}

/**
 * Fetches tide extremes for a location on a given date.
 *
 * @param {string} locationName - The location name.
 * @param {string} date - ISO date string (YYYY-MM-DD).
 * @returns {Promise<Array>} Array of tide extreme objects.
 */
export async function fetchTidesForDate(locationName, date) {
  const res = await axios.get('/api/tides', { params: { locationName, date } });
  return res.data;
}

/**
 * Fetches tide statistics for a single location.
 *
 * @param {string} locationName - The location name.
 * @returns {Promise<Object|null>} TideStats object or null if no data.
 */
export async function fetchTideStats(locationName) {
  const res = await axios.get('/api/tides/stats', { params: { locationName } });
  return res.status === 204 ? null : res.data;
}
