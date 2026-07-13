import apiClient from './axiosClient.js';

const BASE_URL = '/api/admin/travel-days';

/**
 * Fetches all travel-day ranges, soonest first. ADMIN only.
 *
 * @returns {Promise<Array>} list of { id, startDate, endDate, note }
 */
export async function fetchTravelDays() {
  const response = await apiClient.get(BASE_URL);
  return response.data;
}

/**
 * Fetches travel-day ranges via the read-only endpoint available to any
 * authenticated user. Used by the briefing and map views to render the
 * "forecast not executed — travel day" overlay.
 *
 * @returns {Promise<Array>} list of { id, startDate, endDate, note }
 */
export async function fetchTravelDayRanges() {
  const response = await apiClient.get('/api/travel-days');
  return response.data;
}

/**
 * Creates a new travel-day range. ADMIN only.
 *
 * @param {object} range - { startDate, endDate, note }
 * @returns {Promise<object>} the created range
 */
export async function addTravelDay(range) {
  const response = await apiClient.post(BASE_URL, range);
  return response.data;
}

/**
 * Deletes a travel-day range by id. ADMIN only.
 *
 * @param {number} id - the range id
 * @returns {Promise<void>}
 */
export async function deleteTravelDay(id) {
  await apiClient.delete(`${BASE_URL}/${id}`);
}
