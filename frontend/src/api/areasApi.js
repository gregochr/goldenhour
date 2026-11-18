import apiClient from './axiosClient.js';

const BASE_URL = '/api/areas';

/**
 * @typedef {object} AreaDirectoryEntry
 * @property {number} id
 * @property {string} name
 * @property {'NAMED'|'SUB_REGION'} kind
 * @property {string[]} aliases other names, shortest first
 * @property {?number} regionId set for a sub-region
 * @property {?string} regionName
 * @property {number[]} locationIds the ENABLED member locations
 */

/**
 * The areas Find offers: every named area and sub-region with its other names and enabled members.
 *
 * <p>Role-free roster data, the same for every reader, so it is ETag-shared. It carries no rating or verdict.
 * This is the reader's directory, not the admin list at {@code GET /api/areas}.
 *
 * @returns {Promise<AreaDirectoryEntry[]>}
 */
export async function fetchAreaDirectory() {
  const response = await apiClient.get(`${BASE_URL}/directory`);
  return response.data;
}

/**
 * @typedef {object} AreaWindowOutlook
 * @property {string} date
 * @property {'SUNRISE'|'SUNSET'} targetType
 * @property {number} ratedMembers members with a rating this reader may see
 * @property {number} totalMembers enabled members
 * @property {number} gatedMembers members whose rating is hidden from this reader (a Lite reader's picks)
 * @property {number} [topRating] absent when no member is rated
 * @property {number} [topLocationId]
 */

/**
 * This reader's own figure per area and window: counts and a top rating, with gated members counted and never
 * rated. Personal, so never cached. It is not a verdict and not a Best pick.
 *
 * @returns {Promise<Array<{areaId: number, windows: AreaWindowOutlook[]}>>}
 */
export async function fetchAreaOutlook() {
  const response = await apiClient.get(`${BASE_URL}/outlook`);
  return response.data;
}
