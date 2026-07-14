import apiClient from './axiosClient.js';

/**
 * Fetches all waitlist email submissions ordered oldest-first. Requires ADMIN role.
 *
 * @returns {Promise<Array<{id: number, email: string, submittedAt: string}>>} The waitlist entries.
 */
export async function getWaitlist() {
  const response = await apiClient.get('/api/admin/waitlist');
  return response.data;
}
