import apiClient from './axiosClient.js';

const BASE_URL = '/api';

/**
 * Fetches all regions ordered alphabetically by name.
 *
 * @returns {Promise<Array<{id: number, name: string, enabled: boolean, createdAt: string}>>}
 */
export async function fetchRegions() {
  const response = await apiClient.get(`${BASE_URL}/regions`);
  return response.data;
}

/**
 * Fetches the shared region-base drive-time matrix.
 *
 * <p><b>Shared, user-independent data</b> — how far every location is from each region's base
 * town, the same answer for every reader, which is what lets it be ETag-revalidated. It is NOT the
 * per-user reach map (`GET /api/user/settings/reach`), which measures the same journeys from the
 * reader's own home and must never be cached; the two are kept apart on the client too, in
 * `planOrigin.js`.
 *
 * @returns {Promise<Object<string, Object<string, number>>>} region id to location id to minutes
 */
export async function fetchRegionDriveTimes() {
  const response = await apiClient.get(`${BASE_URL}/regions/drive-times`);
  return response.data;
}

/**
 * Sets or clears a region's base town — the origin the Plan tab can plan from.
 *
 * <p>Its own endpoint rather than three more fields on the rename, so a rename can never clear a
 * base by omission (and discard its whole drive-time matrix with it).
 *
 * @param {number} id - Region primary key.
 * @param {object} base - `{baseName, baseLat, baseLon}`; all null clears the base.
 * @returns {Promise<object>} The updated region entity.
 */
export async function setRegionBase(id, base) {
  const response = await apiClient.put(`${BASE_URL}/regions/${id}/base`, base);
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
  const response = await apiClient.post(`${BASE_URL}/regions`, data);
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
  const response = await apiClient.put(`${BASE_URL}/regions/${id}`, data);
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
  const response = await apiClient.put(`${BASE_URL}/regions/${id}/enabled`, { enabled });
  return response.data;
}
