import apiClient from './axiosClient.js';

/**
 * @typedef {object} SubRegionPolicy
 * @property {number} liteLimit the saved limit on a Lite reader's sub-region picks
 * @property {number} maxLimit the largest limit that can be saved (the number of sub-regions, at most 24)
 * @property {number} liteUsersOverLimit Lite readers holding more picks than `candidateLimit`
 * @property {number} candidateLimit the limit `liteUsersOverLimit` was counted against
 * @property {string|null} updatedAt
 * @property {string|null} updatedBy
 */

/**
 * Fetches the Lite sub-region pick limit. Requires ADMIN role.
 *
 * @param {number} [candidate] a limit being considered: the response then counts the Lite readers it
 *   would leave over their limit. Omit it for the saved limit.
 * @returns {Promise<SubRegionPolicy>}
 */
export async function getSubRegionPolicy(candidate) {
  const params = candidate === undefined ? undefined : { candidate };
  const response = await apiClient.get('/api/admin/sub-region-policy', { params });
  return response.data;
}

/**
 * Saves the Lite sub-region pick limit. Requires ADMIN role. 400 unless it is 1..the number of sub-regions.
 *
 * @param {number} liteLimit the new limit
 * @returns {Promise<SubRegionPolicy>} the saved policy, with the number of Lite readers now over it
 */
export async function saveSubRegionPolicy(liteLimit) {
  const response = await apiClient.put('/api/admin/sub-region-policy', { liteLimit });
  return response.data;
}
