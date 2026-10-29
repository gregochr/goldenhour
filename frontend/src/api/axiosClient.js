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
/** The `code` of the server's 403 for an account that must still change its password. */
const PASSWORD_CHANGE_REQUIRED_CODE = 'PASSWORD_CHANGE_REQUIRED';
/**
 * Internal config field: the access token a retried request must carry. Set from the refresh
 * result so the retry does not depend on the token having been persisted to storage.
 */
const ACCESS_TOKEN_FIELD = '_accessToken';

/**
 * Ends the session: clears the stored credentials and tells AuthContext, which does the rest. The
 * stored username is deliberately kept (it pre-fills the sign-in form).
 */
function expireSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_KEY);
  localStorage.removeItem('goldenhour_role');
  window.dispatchEvent(new Event('goldenhour:session-expired'));
}

/** Reads the bearer token a request was sent with, or null. Handles plain objects and AxiosHeaders. */
function sentBearerToken(config) {
  const headers = config?.headers;
  const value = headers?.Authorization ?? headers?.authorization ?? headers?.get?.('Authorization');
  if (typeof value !== 'string' || !value.startsWith('Bearer ')) return null;
  return value.slice('Bearer '.length) || null;
}

// Attach the JWT access token to every outgoing request — and, while an admin has the app rewound
// (`utils/rewind.js`), the instant it is rendering as of. On GETs only: the backend honours the
// header on nothing else, and a rewind is a way of looking, never a way of writing. Never under
// `/api/admin/`: the admin screens, the rewind menu itself among them, read the real clock. Read
// per request rather than once, because the briefing provider polls every ten minutes and a rewind
// has to hold for every poll, not only the first fetch after it was set.
apiClient.interceptors.request.use((config) => {
  const token = config[ACCESS_TOKEN_FIELD] ?? localStorage.getItem(TOKEN_KEY);
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
    // A request made with `responseType: 'text'` (the memoised polled endpoints, `utils/jsonMemo`) gets its
    // error body back as a string, because axios's default transform only parses when no responseType is
    // set — and that transform runs before this interceptor. Parse it once here, in place, before any
    // `.code` check, so every reader downstream sees the JSON object it always has.
    if (typeof error.response?.data === 'string' && error.response.data !== '') {
      try {
        error.response.data = JSON.parse(error.response.data);
      } catch {
        // not JSON (a proxy's HTML page): leave the text
      }
    }
    const original = error.config;
    // The refresh request goes through this same client, so its own 401 (an expired refresh
    // token) must never re-enter the refresh path below, or it would await itself and hang
    // every request. Login and logout opt out too: a refresh cannot fix their 401.
    if (original?.[SKIP_AUTH_REFRESH]) {
      return Promise.reject(error);
    }
    // The server holds a flagged account to the change-password page (403 PASSWORD_CHANGE_REQUIRED on
    // every other /api/ call). Tell AuthContext so a stale client flag is corrected, then reject as
    // usual. Raised here, before any API module swallows its own 403.
    if (error.response?.status === 403 && error.response?.data?.code === PASSWORD_CHANGE_REQUIRED_CODE) {
      window.dispatchEvent(new Event('goldenhour:password-change-required'));
      return Promise.reject(error);
    }
    const storedRefresh = localStorage.getItem(REFRESH_KEY);

    if (error.response?.status !== 401 || original._retried) {
      return Promise.reject(error);
    }
    if (!storedRefresh) {
      // A refused request with no renewal pass: the session is dead. Expire it only if the request
      // carried the token still in storage — that dedupes concurrent 401s (the first clears
      // storage) and stops a stale in-flight 401 wiping a newer token from a fresh sign-in or
      // another tab.
      const sent = sentBearerToken(original);
      if (sent && sent === localStorage.getItem(TOKEN_KEY)) expireSession();
      return Promise.reject(error);
    }

    original._retried = true;

    // If a refresh is already in flight, wait for it then retry with the new token.
    if (refreshPromise) {
      const token = await refreshPromise;
      // A failed refresh already cleared the tokens and raised session-expired; retrying would
      // only send `Bearer null`, so surface the original 401.
      if (!token) return Promise.reject(error);
      original[ACCESS_TOKEN_FIELD] = token;
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
        // failure. The requests waiting on this refresh are retried with the new token held in
        // memory (`_accessToken`). Later requests read storage, so if persistence failed they will
        // end in session-expired (rotation revoked the old refresh token): an honest sign-in
        // prompt. A shared in-memory token store was deliberately not built: sign-in itself writes
        // storage unguarded (AuthContext), so a browser whose storage is unusable cannot sign in at
        // all, and this only bites if storage fills between sign-in and a refresh.
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
        return data.accessToken;
      })
      .catch(() => {
        expireSession();
        return null;
      })
      .finally(() => {
        refreshPromise = null;
      });

    const token = await refreshPromise;
    if (!token) return Promise.reject(error);
    original[ACCESS_TOKEN_FIELD] = token;
    return apiClient(original);
  }
);

export default apiClient;
