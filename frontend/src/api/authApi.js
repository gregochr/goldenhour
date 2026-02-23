import axios from 'axios';

const BASE_URL = '/api/auth';

/**
 * Authenticates a user and returns JWT tokens.
 *
 * @param {string} username - The login name.
 * @param {string} password - The plain-text password.
 * @returns {Promise<{accessToken: string, refreshToken: string, role: string, expiresAt: string}>}
 */
export async function login(username, password) {
  const response = await axios.post(`${BASE_URL}/login`, { username, password });
  return response.data;
}

/**
 * Exchanges a valid refresh token for a new access token.
 *
 * @param {string} refreshToken - The raw refresh token string.
 * @returns {Promise<{accessToken: string, expiresAt: string}>}
 */
export async function refreshAccessToken(refreshToken) {
  const response = await axios.post(`${BASE_URL}/refresh`, { refreshToken });
  return response.data;
}

/**
 * Revokes the refresh token and logs the user out.
 *
 * @param {string} refreshToken - The raw refresh token to revoke.
 * @returns {Promise<void>}
 */
export async function logout(refreshToken) {
  await axios.post(`${BASE_URL}/logout`, { refreshToken });
}

/**
 * Changes the authenticated user's password and clears the first-login flag.
 * Requires a valid access token (attached automatically by the global axios interceptor).
 *
 * @param {string} newPassword - The new plain-text password.
 * @returns {Promise<{message: string}>}
 */
export async function changePassword(newPassword) {
  const response = await axios.post(`${BASE_URL}/change-password`, { newPassword });
  return response.data;
}
