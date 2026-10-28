import apiClient, { SKIP_AUTH_REFRESH } from './axiosClient.js';

const BASE_URL = '/api/auth';

/**
 * Authenticates a user and returns JWT tokens.
 *
 * @param {string} username - The login name.
 * @param {string} password - The plain-text password.
 * @param {string} turnstileToken - Cloudflare Turnstile verification token.
 * @returns {Promise<{accessToken: string, refreshToken: string, role: string, expiresAt: string}>}
 */
export async function login(username, password, turnstileToken) {
  const response = await apiClient.post(`${BASE_URL}/login`, { username, password, turnstileToken },
    { [SKIP_AUTH_REFRESH]: true });
  return response.data;
}

/**
 * Exchanges a valid refresh token for a new access token.
 *
 * @param {string} refreshToken - The raw refresh token string.
 * @returns {Promise<{accessToken: string, expiresAt: string}>}
 */
export async function refreshAccessToken(refreshToken) {
  const response = await apiClient.post(`${BASE_URL}/refresh`, { refreshToken }, { [SKIP_AUTH_REFRESH]: true });
  return response.data;
}

/**
 * Revokes the refresh token and logs the user out.
 *
 * @param {string} refreshToken - The raw refresh token to revoke.
 * @returns {Promise<void>}
 */
export async function logout(refreshToken) {
  await apiClient.post(`${BASE_URL}/logout`, { refreshToken }, { [SKIP_AUTH_REFRESH]: true });
}

/**
 * Changes the authenticated user's password and clears the first-login flag.
 * Requires a valid access token (attached automatically by the apiClient interceptor).
 *
 * The server re-verifies the current password: a wrong or missing one is a 400, and the per-account
 * attempt budget being spent is a 429.
 *
 * @param {string} currentPassword - The password the reader signs in with today (the temporary one, on first login).
 * @param {string} newPassword - The new plain-text password.
 * @param {string} turnstileToken - Cloudflare Turnstile verification token.
 * @returns {Promise<{message: string}>}
 */
export async function changePassword(currentPassword, newPassword, turnstileToken) {
  const response = await apiClient.post(`${BASE_URL}/change-password`,
    { currentPassword, newPassword, turnstileToken });
  return response.data;
}

/**
 * Registers a new user with email verification.
 *
 * @param {string} username - The desired login name.
 * @param {string} email - The user's email address.
 * @param {string} turnstileToken - Cloudflare Turnstile verification token.
 * @param {boolean} marketingEmailOptIn - Whether the user opts in to marketing emails.
 * @param {boolean} termsAccepted - Whether the user accepted the Terms & Conditions and Privacy Policy.
 * @param {string|null} [inviteToken] - A waitlist invitation token from the emailed link; sent only when present.
 * @returns {Promise<{message: string, email: string}>}
 */
export async function register(username, email, turnstileToken, marketingEmailOptIn, termsAccepted,
  inviteToken = null) {
  const response = await apiClient.post(`${BASE_URL}/register`, {
    username, email, turnstileToken, marketingEmailOptIn: String(marketingEmailOptIn),
    termsAccepted: String(termsAccepted),
    ...(inviteToken ? { inviteToken } : {}),
  }, { [SKIP_AUTH_REFRESH]: true });
  return response.data;
}

/**
 * Updates the authenticated user's marketing email opt-in preference.
 *
 * @param {boolean} optIn - Whether to opt in to marketing emails.
 * @returns {Promise<{marketingEmailOptIn: boolean}>}
 */
export async function updateMarketingEmailOptIn(optIn) {
  const response = await apiClient.put(`${BASE_URL}/marketing-emails`, { optIn });
  return response.data;
}

/**
 * Resends a verification email for a pending registration.
 *
 * @param {string} email - The email address to resend verification to.
 * @returns {Promise<{message: string}>}
 */
export async function resendVerification(email) {
  const response = await apiClient.post(`${BASE_URL}/resend-verification`, { email }, { [SKIP_AUTH_REFRESH]: true });
  return response.data;
}

/**
 * Verifies an email address using the token from the verification link.
 *
 * @param {string} token - The verification token from the email link.
 * @returns {Promise<{userId: number, verified: boolean}>}
 */
export async function verifyEmail(token) {
  const response = await apiClient.post(`${BASE_URL}/verify-email`, { token }, { [SKIP_AUTH_REFRESH]: true });
  return response.data;
}

/**
 * Sets the password for a newly verified user and auto-logs them in.
 *
 * The verification token identifies the account: the backend resolves the user from it, so a
 * guessed user id cannot be used to overwrite someone else's password. The single-use emailed
 * token is the proof of a real person, so no Turnstile token is sent.
 *
 * @param {string} token - The verification token from the email link (same one passed to verifyEmail).
 * @param {string} password - The chosen password.
 * @returns {Promise<{accessToken: string, refreshToken: string, role: string, expiresAt: string}>}
 */
export async function setPasswordForNewUser(token, password) {
  const response = await apiClient.post(`${BASE_URL}/set-password`, { token, password }, { [SKIP_AUTH_REFRESH]: true });
  return response.data;
}

/**
 * Submits an email address to the waitlist when early-access registration is full.
 *
 * @param {string} email - The email address to add to the waitlist.
 * @param {string} turnstileToken - The Turnstile response token (a failing or missing one is a 400).
 * @returns {Promise<{message: string}>} A 429 means the attempt bounds were reached.
 */
export async function submitWaitlist(email, turnstileToken) {
  const response = await apiClient.post('/api/waitlist', { email, turnstileToken }, { [SKIP_AUTH_REFRESH]: true });
  return response.data;
}

/**
 * Asks for a password-reset link. The server answers 200 with the same sentence for every valid
 * address, known or not, so the caller must not read anything into a success; a refused or
 * invalid request is a 400 with its own {@code error}.
 *
 * @param {string} email - The address the account was registered with.
 * @param {string} turnstileToken - Cloudflare Turnstile token; empty when the widget could not load
 *     (the server then applies its bounded fallback).
 * @returns {Promise<{message: string}>}
 */
export async function requestPasswordReset(email, turnstileToken) {
  const response = await apiClient.post(`${BASE_URL}/forgot-password`, { email, turnstileToken }, { [SKIP_AUTH_REFRESH]: true });
  return response.data;
}

/**
 * Sets a new password with the token from an emailed reset link and signs the reader in. The
 * single-use emailed token is the proof of a real person, so no Turnstile token is sent. A bad,
 * used or expired token is a 400 whose {@code error} is one of three fixed sentences.
 *
 * @param {string} token - The token from the reset link's query string.
 * @param {string} password - The chosen password.
 * @returns {Promise<{accessToken: string, refreshToken: string, role: string, username: string}>}
 */
export async function resetPassword(token, password) {
  const response = await apiClient.post(`${BASE_URL}/reset-password`, { token, password }, { [SKIP_AUTH_REFRESH]: true });
  return response.data;
}
