import apiClient from './axiosClient.js';

const BASE_URL = '/api/briefing';

/**
 * Fetches the cached daily briefing.
 *
 * Returns null if no briefing has been generated yet (204 No Content).
 *
 * @returns {Promise<object|null>} the briefing response or null
 */
export async function getDailyBriefing() {
  const response = await apiClient.get(BASE_URL);
  if (response.status === 204) {
    return null;
  }
  return response.data;
}

/**
 * Triggers an immediate briefing refresh (admin only).
 *
 * @returns {Promise<{status: string}>} Status message.
 */
export async function runBriefing() {
  const response = await apiClient.post(`${BASE_URL}/run`);
  return response.data;
}

/**
 * Fetches the Close to home panel for the current user.
 *
 * <p>Its own endpoint rather than part of the briefing payload, because it answers a different
 * question about differently owned data — the caller's home postcode and their own drive times.
 * Deliberately NOT ETag-revalidated server-side: an ETag would persist per-user data to a browser
 * cache that JavaScript cannot evict on logout.
 *
 * @returns {Promise<object>} the panel; windows is empty when no home is set or nothing is in reach
 */
export async function getCloseToHome() {
  const response = await apiClient.get(`${BASE_URL}/close-to-home`);
  return response.data;
}
