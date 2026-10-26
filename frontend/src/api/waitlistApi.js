import apiClient from './axiosClient.js';

/**
 * Fetches all waitlist email submissions ordered oldest-first. Requires ADMIN role.
 *
 * @returns {Promise<Array<{id: number, email: string, submittedAt: string, invitedAt: string|null,
 *   inviteExpiresAt: string|null}>>} The waitlist entries. The invitation's token hash is never served.
 */
export async function getWaitlist() {
  const response = await apiClient.get('/api/admin/waitlist');
  return response.data;
}

/**
 * Invites a waitlisted address to register past the early-access cap. Re-inviting replaces the earlier
 * invitation. Requires ADMIN role.
 *
 * @param {number} id - The waitlist entry's id.
 * @returns {Promise<{message: string}>} A 400 (non-ASCII address), 404 or 503 (no mail sender) rejects.
 */
export async function inviteWaitlistEntry(id) {
  const response = await apiClient.post(`/api/admin/waitlist/${id}/invite`);
  return response.data;
}

/**
 * Removes a waitlist entry. Requires ADMIN role.
 *
 * @param {number} id - The waitlist entry's id.
 * @returns {Promise<void>}
 */
export async function removeWaitlistEntry(id) {
  await apiClient.delete(`/api/admin/waitlist/${id}`);
}
