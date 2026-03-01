import axios from 'axios';

const BASE_URL = '/api';

/**
 * Fetches all regions ordered alphabetically by name.
 *
 * @returns {Promise<Array<{id: number, name: string, enabled: boolean, createdAt: string}>>}
 */
export async function fetchRegions() {
  const response = await axios.get(`${BASE_URL}/regions`);
  return response.data;
}

/**
 * Adds a new region.
 *
 * @param {object} data - Region data.
 * @param {string} data.name - Human-readable region name.
 * @returns {Promise<object>} The saved region entity.
 */
export async function addRegion(data) {
  const response = await axios.post(`${BASE_URL}/regions`, data);
  return response.data;
}

/**
 * Updates the name of an existing region.
 *
 * @param {number} id - Region primary key.
 * @param {object} data - Updated name.
 * @returns {Promise<object>} The updated region entity.
 */
export async function updateRegion(id, data) {
  const response = await axios.put(`${BASE_URL}/regions/${id}`, data);
  return response.data;
}

/**
 * Toggles the enabled state of a region.
 *
 * @param {number} id - Region primary key.
 * @param {boolean} enabled - Whether the region should be enabled.
 * @returns {Promise<object>} The updated region entity.
 */
export async function setRegionEnabled(id, enabled) {
  const response = await axios.put(`${BASE_URL}/regions/${id}/enabled`, { enabled });
  return response.data;
}
