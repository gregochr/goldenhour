import apiClient from './axiosClient.js';

/**
 * Fetches the enabled locations that at least one measured home or region base has no route to
 * (reach-scoped-card-plan R1b). ADMIN only; the server answers 403 to anyone else.
 *
 * <p>Admin data on its own no-store path: never cached by the browser, never ETag-shared.
 *
 * @returns {Promise<Array<{locationId: number, name: string, unroutableHomes: number, judgedHomes: number,
 *   unroutableBases: number, judgedBases: number}>>} the locations, in name order
 */
export async function fetchUnroutableLocations() {
  const response = await apiClient.get('/api/admin/drive-times/unroutable');
  return response.data;
}
