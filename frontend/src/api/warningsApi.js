import apiClient from './axiosClient.js';
import { getMemoised } from '../utils/jsonMemo.js';

/**
 * Fetches the Met Office weather warnings in force that cover at least one enabled place
 * ({@code WarningsResponse}): {@code enabled}, {@code lastCheckedAt}, {@code stale}, {@code warnings},
 * {@code metOfficeUrl}, {@code attribution}.
 *
 * <p>Open to every signed-in role (warnings are the same for Lite, Pro and Admin) and never public. The path is
 * ETag-revalidated and carries no personal data; it is never given a browser max-age, so a cancellation reaches
 * the reader within one poll of the provider (the licence's ten-minute bound). The reply is memoised, so an
 * unchanged body is the same object and the provider does not re-render.
 *
 * <p>Fail-soft: a 401, 403 or 404 answers null, and the caller treats null as "no warnings to show", never as an
 * all-clear (the surfaces print nothing at all). Any other failure throws and the caller keeps what it had.
 *
 * @returns {Promise<?object>} the response, or null when the endpoint refuses the reader
 */
export async function getWarnings() {
  try {
    const response = await getMemoised(apiClient, '/api/warnings');
    return response.data ?? null;
  } catch (err) {
    const status = err.response?.status;
    if (status === 401 || status === 403 || status === 404) return null;
    throw err;
  }
}
