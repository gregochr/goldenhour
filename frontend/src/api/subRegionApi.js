import apiClient from './axiosClient.js';

const BASE_URL = '/api/user/settings/sub-regions';

/**
 * @typedef {object} SubRegionOption
 * @property {number} areaId
 * @property {string} name
 * @property {number} locationCount enabled locations inside it
 * @property {string[]} exampleLocations up to two names, alphabetically
 */

/**
 * @typedef {object} SubRegionState
 * @property {boolean} enforced whether enforcement is on; the picker is drawn only when it is
 * @property {number} limit the admin-set limit on picks
 * @property {Array<{areaId: number, name: string, regionName: string}>} picks the reader's picks
 * @property {Array<{regionId: number, regionName: string, subRegions: SubRegionOption[]}>} options
 *   every sub-region by region; empty for a Pro or Admin reader
 * @property {number[]} suggested ids nearest the reader's home (a suggestion; nothing is saved)
 * @property {'FIRST_CHOICE'|'FREE_UNTIL'|'OPEN'|'LOCKED'|'NOT_APPLICABLE'} status
 * @property {string} [freeUntil] last free-change day (civil date), while FREE_UNTIL
 * @property {string} [nextChangeOn] first day a swap is allowed, while LOCKED
 * @property {boolean} [overLimit] true when the reader holds more picks than the limit
 * @property {string} [freeUntilIfChosenToday] FIRST_CHOICE: the grace end a save today would set
 * @property {string} [nextChangeOnIfSavedToday] the lock end a lock-stamping save today would set
 */

/**
 * Fetches the caller's sub-region picks, what they may do next, and the options. Personal: never cached.
 *
 * @returns {Promise<SubRegionState>}
 */
export async function getSubRegions() {
  const response = await apiClient.get(BASE_URL);
  return response.data;
}

/**
 * Replaces the caller's sub-region picks. 400 for an empty list, more than the limit, a duplicate or a
 * non-sub-region id; 403 for Pro and Admin; 409 `SUB_REGIONS_LOCKED` (with `nextChangeOn`) while locked.
 *
 * @param {number[]} areaIds the sub-region ids to hold
 * @returns {Promise<SubRegionState>} the new state
 */
export async function saveSubRegions(areaIds) {
  const response = await apiClient.put(BASE_URL, { areaIds });
  return response.data;
}
