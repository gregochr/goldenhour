import apiClient from './axiosClient.js';

const BASE_URL = '/api/admin/travel-days';

/**
 * Fetches the travel-day ranges. ADMIN only. By default only current and upcoming ranges; with
 * {@code includePast} every range, newest start first.
 *
 * @param {{includePast?: boolean}} [options] pass {@code includePast: true} for ranges that have already ended
 * @returns {Promise<Array>} list of { id, startDate, endDate, note }
 */
export async function fetchTravelDays(options = {}) {
  const response = await apiClient.get(BASE_URL, options.includePast ? { params: { includePast: true } } : undefined);
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
 * Replaces a travel-day range's dates and note. ADMIN only.
 *
 * @param {number} id - the range id
 * @param {object} range - { startDate, endDate, note }
 * @returns {Promise<object>} the updated range
 */
export async function updateTravelDay(id, range) {
  const response = await apiClient.put(`${BASE_URL}/${id}`, range);
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
