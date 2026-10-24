import apiClient from './axiosClient.js';

const BASE_URL = '/api';

/**
 * Fetches every named area (admin only).
 *
 * @returns {Promise<Array<{id: number, name: string, aliases: string[], locationIds: number[]}>>}
 */
export async function fetchAreas() {
  const response = await apiClient.get(`${BASE_URL}/areas`);
  return response.data;
}

/**
 * Creates a named area with no members. A name that clashes with another answers 409.
 *
 * @param {{name: string, aliases: string[]}} body
 * @returns {Promise<{id: number, name: string, aliases: string[], locationIds: number[]}>}
 */
export async function createArea(body) {
  const response = await apiClient.post(`${BASE_URL}/areas`, body);
  return response.data;
}

/**
 * Renames an area and replaces its aliases. Its members are left as they are.
 *
 * @param {number} id
 * @param {{name: string, aliases: string[]}} body
 * @returns {Promise<{id: number, name: string, aliases: string[], locationIds: number[]}>}
 */
export async function updateArea(id, body) {
  const response = await apiClient.put(`${BASE_URL}/areas/${id}`, body);
  return response.data;
}

/**
 * Replaces an area's membership. The only call that changes which locations belong to it.
 *
 * @param {number} id
 * @param {number[]} locationIds
 * @returns {Promise<{id: number, name: string, aliases: string[], locationIds: number[]}>}
 */
export async function setAreaLocations(id, locationIds) {
  const response = await apiClient.put(`${BASE_URL}/areas/${id}/locations`, { locationIds });
  return response.data;
}

/**
 * Deletes an area. Its members stay locations.
 *
 * @param {number} id
 * @returns {Promise<void>}
 */
export async function deleteArea(id) {
  await apiClient.delete(`${BASE_URL}/areas/${id}`);
}

/**
 * Fetches every location's other names.
 *
 * @returns {Promise<Object<string, string[]>>} location id to aliases; locations with none are absent
 */
export async function fetchLocationAliases() {
  const response = await apiClient.get(`${BASE_URL}/locations/aliases`);
  return response.data;
}

/**
 * Replaces one location's other names. Called AFTER the location's own save, never with it.
 *
 * @param {number} id
 * @param {string[]} aliases
 * @returns {Promise<string[]>} the saved aliases
 */
export async function setLocationAliases(id, aliases) {
  const response = await apiClient.put(`${BASE_URL}/locations/${id}/aliases`, { aliases });
  return response.data;
}
