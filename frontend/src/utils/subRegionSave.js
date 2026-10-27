import { saveSubRegions } from '../api/subRegionApi.js';
import { clearSwrCache } from './swrCache.js';

/**
 * Saves the reader's sub-region picks and, once the server has accepted them, drops every cached
 * briefing and forecast body (sub-regions-plan §5.4 S4): a body painted from the SWR cache was shaped
 * by the OLD picks. The caller then refetches (`App`'s `onSubRegionsSaved`).
 *
 * <p>The cache is cleared only on success: a refused save (400, 403, 409) changed nothing, so the
 * cached bodies are still right.
 *
 * @param {number[]} areaIds the sub-region ids to hold
 * @returns {Promise<import('../api/subRegionApi.js').SubRegionState>} the new state, from the PUT
 */
export async function persistSubRegions(areaIds) {
  const state = await saveSubRegions(areaIds);
  clearSwrCache();
  return state;
}
