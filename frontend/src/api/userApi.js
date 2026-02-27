import axios from 'axios';

/**
 * Resets the password for a user, generating a temporary password server-side.
 * Sets passwordChangeRequired so the user must change it on next login.
 * Requires ADMIN role.
 *
 * @param {number} userId - The user's primary key.
 * @returns {Promise<{temporaryPassword: string}>} The generated temporary password.
 */
export async function resetUserPassword(userId) {
  const response = await axios.put(`/api/users/${userId}/reset-password`);
  return response.data;
}
