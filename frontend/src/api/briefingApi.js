import axios from 'axios';

const BASE_URL = '/api/briefing';

/**
 * Fetches the cached daily briefing.
 *
 * Returns null if no briefing has been generated yet (204 No Content).
 *
 * @returns {Promise<object|null>} the briefing response or null
 */
export async function getDailyBriefing() {
  const response = await axios.get(BASE_URL);
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
  const response = await axios.post(`${BASE_URL}/run`);
  return response.data;
}
