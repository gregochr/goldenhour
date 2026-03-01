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

/**
 * Registers a new user with email verification.
 *
 * @param {string} username - The desired login name.
 * @param {string} email - The user's email address.
 * @param {string} turnstileToken - Cloudflare Turnstile verification token.
 * @returns {Promise<{message: string, email: string}>}
 */
export async function register(username, email, turnstileToken) {
  const response = await axios.post(`${BASE_URL}/register`, { username, email, turnstileToken });
  return response.data;
}

/**
 * Resends a verification email for a pending registration.
 *
 * @param {string} email - The email address to resend verification to.
 * @returns {Promise<{message: string}>}
 */
export async function resendVerification(email) {
  const response = await axios.post(`${BASE_URL}/resend-verification`, { email });
  return response.data;
}

/**
 * Verifies an email address using the token from the verification link.
 *
 * @param {string} token - The verification token from the email link.
 * @returns {Promise<{userId: number, verified: boolean}>}
 */
export async function verifyEmail(token) {
  const response = await axios.post(`${BASE_URL}/verify-email`, { token });
  return response.data;
}

/**
 * Sets the password for a newly verified user and auto-logs them in.
 *
 * @param {number} userId - The user's ID from the verification step.
 * @param {string} password - The chosen password.
 * @returns {Promise<{accessToken: string, refreshToken: string, role: string, expiresAt: string}>}
 */
export async function setPasswordForNewUser(userId, password) {
  const response = await axios.post(`${BASE_URL}/set-password`, { userId, password });
  return response.data;
}
