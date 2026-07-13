import apiClient from './axiosClient.js';

const BASE_URL = '/api/admin/hot-topics/simulation';

/**
 * Returns the current simulation state: master enabled flag and all
 * simulatable types with their active flags.
 *
 * @returns {Promise<{enabled: boolean, types: Array}>}
 */
export async function getSimulationState() {
  const response = await apiClient.get(BASE_URL);
  return response.data;
}

/**
 * Toggles the master simulation on/off switch.
 *
 * @returns {Promise<{enabled: boolean, types: Array}>} updated state
 */
export async function toggleSimulation() {
  const response = await apiClient.post(`${BASE_URL}/toggle`);
  return response.data;
}

/**
 * Toggles an individual topic type active/inactive.
 *
 * @param {string} type - topic type identifier, e.g. "BLUEBELL"
 * @returns {Promise<{enabled: boolean, types: Array}>} updated state
 */
export async function toggleTopicType(type) {
  const response = await apiClient.post(`${BASE_URL}/type/${type}/toggle`);
  return response.data;
}
