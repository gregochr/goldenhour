import apiClient from './axiosClient.js';

const ACCOUNT_URL = '/api/user/account';

/**
 * Permanently deletes the signed-in reader's own account, after a password re-check.
 *
 * <p>`DELETE` with a JSON body, as the endpoint is specified (login-registration plan §2 #7, §5 #8):
 * axios sends `data` on a DELETE, so the password rides in the body and never in a URL. Resolves
 * with nothing on 204. The caller tells the refusals apart by `error.response.status`:
 * 400 no password (unreachable from a form whose button needs one), 401 the session has changed,
 * 403 the password is wrong, 409 an administrator (another admin deletes an admin), 429 too many
 * attempts — each with the server's sentence under `error`.
 *
 * @param {string} password the account's current password
 * @returns {Promise<void>}
 */
export async function deleteAccount(password) {
  await apiClient.delete(ACCOUNT_URL, { data: { password } });
}
