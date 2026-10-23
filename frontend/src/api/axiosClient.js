import axios from 'axios';
import { refreshAccessToken } from './authApi.js';
import { getRewindTo } from '../utils/rewind.js';

/**
 * Shared axios instance for all authenticated API calls.
 *
 * Owns the JWT request interceptor (attaches the access token) and the
 * response interceptor (single-flight 401 refresh). Every API module and
 * component imports this instance rather than the global axios singleton, so
 * the interceptors are explicit dependencies instead of import-order side
 * effects.
 *
 * ETag note: the read GET endpoints send ETags + `Cache-Control: private, no-cache`
 * (backend HttpCachingConfig). The browser's own HTTP cache revalidates these below
 * the XHR layer and hands axios a full 200 with a populated body on a 304 — so this
 * client needs no conditional-request handling and gets the bandwidth saving for free.
 * Do NOT store the ETag in JS and attach a manual `If-None-Match`: axios's default
 * `validateStatus` rejects a bare 304 as an error, which would surface an empty body
 * to callers like `useForecasts` and break the cold load.
 */
const apiClient = axios.create();

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';
/** The backend's rewind header (`RewindFilter.HEADER`). */
export const REWIND_HEADER = 'X-Rewind-To';
/**
 * Per-request config flag: a 401 on this request must never trigger refresh-and-retry. Set by the
 * auth calls (login, refresh, logout), which the access token does not authenticate.
 */
export const SKIP_AUTH_REFRESH = 'skipAuthRefresh';

// Attach the JWT access token to every outgoing request — and, while an admin has the app rewound
// (`utils/rewind.js`), the instant it is rendering as of. On GETs only: the backend honours the
// header on nothing else, and a rewind is a way of looking, never a way of writing. Never under
// `/api/admin/`: the admin screens, the rewind menu itself among them, read the real clock. Read
// per request rather than once, because the briefing provider polls every ten minutes and a rewind
// has to hold for every poll, not only the first fetch after it was set.
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers = config.headers ?? {};
    config.headers['Authorization'] = `Bearer ${token}`;
  }
  const rewindTo = getRewindTo();
  if (rewindTo && (config.method ?? 'get').toLowerCase() === 'get'
      && !String(config.url ?? '').startsWith('/api/admin/')) {
    config.headers = config.headers ?? {};
    config.headers[REWIND_HEADER] = rewindTo;
  }
  return config;
});

// On 401, attempt a single token refresh and queue concurrent requests.
let refreshPromise = null;

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config;
    // The refresh request goes through this same client, so its own 401 (an expired refresh
    // token) must never re-enter the refresh path below, or it would await itself and hang
    // every request. Login and logout opt out too: a refresh cannot fix their 401.
    if (original?.[SKIP_AUTH_REFRESH]) {
      return Promise.reject(error);
    }
    const storedRefresh = localStorage.getItem(REFRESH_KEY);

    if (error.response?.status !== 401 || original._retried || !storedRefresh) {
      return Promise.reject(error);
    }

    original._retried = true;

    // If a refresh is already in flight, wait for it then retry with the new token.
    if (refreshPromise) {
      const refreshed = await refreshPromise;
      // A failed refresh already cleared the tokens and raised session-expired; retrying would
      // only send `Bearer null`, so surface the original 401.
      if (!refreshed) return Promise.reject(error);
      original.headers['Authorization'] = `Bearer ${localStorage.getItem(TOKEN_KEY)}`;
      return apiClient(original);
    }

    // First 401 triggers the refresh; concurrent 401s await the same promise.
    refreshPromise = refreshAccessToken(storedRefresh)
      .then((data) => {
        // Persisting the new tokens is guarded separately from the refresh call itself. A
        // storage failure here (quota, private mode) would otherwise be caught by the .catch()
        // below, which cannot tell it from a rejected refresh — so it would clear the tokens and
        // fire session-expired, logging the user out at the exact moment the server had just
        // issued them a valid token. The refresh succeeded; a failure to cache it is not an auth
        // failure, and the in-memory retry below still carries the new token.
        try {
          localStorage.setItem(TOKEN_KEY, data.accessToken);
          if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken);
        } catch (err) {
          console.warn(`[auth] token refreshed but could not be persisted: ${err?.name ?? err}`);
        }
        // Notify AuthContext so its in-memory token stays in sync with localStorage.
        // Long-lived consumers keyed on the AuthContext token (e.g. the health SSE
        // stream) otherwise stay bound to the now-expired token until a page reload.
        window.dispatchEvent(new Event('goldenhour:token-refreshed'));
        return true;
      })
      .catch(() => {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(REFRESH_KEY);
        localStorage.removeItem('goldenhour_role');
        window.dispatchEvent(new Event('goldenhour:session-expired'));
        return false;
      })
      .finally(() => {
        refreshPromise = null;
      });

    const refreshed = await refreshPromise;
    if (!refreshed) return Promise.reject(error);
    original.headers['Authorization'] = `Bearer ${localStorage.getItem(TOKEN_KEY)}`;
    return apiClient(original);
  }
);

export default apiClient;
