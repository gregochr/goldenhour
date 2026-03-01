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

/**
 * Updates a user's email address. Requires ADMIN role.
 *
 * @param {number} userId - The user's primary key.
 * @param {string} email - The new email address.
 * @returns {Promise<{message: string}>} Confirmation message.
 */
export async function updateUserEmail(userId, email) {
  const response = await axios.put(`/api/users/${userId}/email`, { email });
  return response.data;
}

/**
 * Updates a user's role. Requires ADMIN role.
 *
 * @param {number} userId - The user's primary key.
 * @param {string} role - The new role (ADMIN, PRO_USER, or LITE_USER).
 * @returns {Promise<{message: string}>} Confirmation message.
 */
export async function updateUserRole(userId, role) {
  const response = await axios.put(`/api/users/${userId}/role`, { role });
  return response.data;
}

/**
 * Enables or disables a user account. Requires ADMIN role.
 *
 * @param {number} userId - The user's primary key.
 * @param {boolean} enabled - Whether the user should be enabled.
 * @returns {Promise<{message: string}>} Confirmation message.
 */
export async function updateUserEnabled(userId, enabled) {
  const response = await axios.put(`/api/users/${userId}/enabled`, { enabled });
  return response.data;
}

/**
 * Permanently deletes a user account. Requires ADMIN role.
 *
 * @param {number} userId - The user's primary key.
 * @returns {Promise<{message: string}>} Confirmation message.
 */
export async function deleteUser(userId) {
  const response = await axios.delete(`/api/users/${userId}`);
  return response.data;
}

/**
 * Resends a verification email for a pending user. Requires ADMIN role.
 *
 * @param {number} userId - The user's primary key.
 * @returns {Promise<{message: string}>} Confirmation message.
 */
export async function resendVerification(userId) {
  const response = await axios.post(`/api/users/${userId}/resend-verification`);
  return response.data;
}
