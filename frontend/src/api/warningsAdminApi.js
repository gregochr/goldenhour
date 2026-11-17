import apiClient from './axiosClient.js';

const BASE_URL = '/api/admin/warnings';

/**
 * The warnings ingest as Operations sees it: last poll, last success, failure reason, stored counts, unknown weather
 * types and every warning in force (simulated ones included). Admin only; the server answers 403 to anyone else.
 *
 * @returns {Promise<object>} the {@code WarningsAdminStatus} body
 */
export async function getWarningsStatus() {
  const response = await apiClient.get(`${BASE_URL}/status`);
  return response.data;
}

/**
 * Inserts one clearly simulated warning (a half-degree square around a point). It never reaches a non-admin surface.
 *
 * @param {{level: string, weatherTypes: string[], headline: string, durationHours: number,
 *   latitude: number, longitude: number}} request the simulation
 * @returns {Promise<object>} the stored simulated warning
 */
export async function simulateWarning(request) {
  const response = await apiClient.post(`${BASE_URL}/simulate`, request);
  return response.data;
}
