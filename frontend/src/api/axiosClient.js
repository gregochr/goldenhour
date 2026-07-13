import axios from 'axios';
import { refreshAccessToken } from './authApi.js';

/**
 * Shared axios instance for all authenticated API calls.
 *
 * Owns the JWT request interceptor (attaches the access token) and the
 * response interceptor (single-flight 401 refresh). Every API module and
 * component imports this instance rather than the global axios singleton, so
 * the interceptors are explicit dependencies instead of import-order side
 * effects.
 */
const apiClient = axios.create();

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';

// Attach the JWT access token to every outgoing request.
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers = config.headers ?? {};
    config.headers['Authorization'] = `Bearer ${token}`;
  }
  return config;
});

// On 401, attempt a single token refresh and queue concurrent requests.
let refreshPromise = null;

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config;
    const storedRefresh = localStorage.getItem(REFRESH_KEY);

    if (error.response?.status !== 401 || original._retried || !storedRefresh) {
      return Promise.reject(error);
    }

    original._retried = true;

    // If a refresh is already in flight, wait for it then retry with the new token.
    if (refreshPromise) {
      await refreshPromise;
      original.headers['Authorization'] = `Bearer ${localStorage.getItem(TOKEN_KEY)}`;
      return apiClient(original);
    }

    // First 401 triggers the refresh; concurrent 401s await the same promise.
    refreshPromise = refreshAccessToken(storedRefresh)
      .then((data) => {
        localStorage.setItem(TOKEN_KEY, data.accessToken);
        if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken);
      })
      .catch(() => {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(REFRESH_KEY);
        localStorage.removeItem('goldenhour_role');
        window.dispatchEvent(new Event('goldenhour:session-expired'));
      })
      .finally(() => {
        refreshPromise = null;
      });

    await refreshPromise;
    original.headers['Authorization'] = `Bearer ${localStorage.getItem(TOKEN_KEY)}`;
    return apiClient(original);
  }
);

export default apiClient;
