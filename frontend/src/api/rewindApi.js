import apiClient from './axiosClient.js';

const BASE_URL = '/api/admin/rewind';

/**
 * The recent solar events an admin can rewind the app to (admin only).
 *
 * @returns {Promise<{ now: string, briefingGeneratedAt: string|null, maxAgeDays: number,
 *   events: Array<{ date: string, eventType: string, earliest: string, latest: string,
 *   rewindTo: string, passed: boolean, inBriefing: boolean, locationCount: number }> }>}
 */
export async function getRewindEvents() {
  const response = await apiClient.get(`${BASE_URL}/events`);
  return response.data;
}
