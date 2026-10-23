import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import apiClient from '../api/axiosClient.js';

// Deliberately NOT mocking authApi: the refresh must travel through the real interceptor,
// which is where a refused refresh used to await itself forever.

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';
const ROLE_KEY = 'goldenhour_role';
const REFRESH_URL = '/api/auth/refresh';
const HANG_MS = 500;

/** Fails fast and clearly if a request never settles (the deadlock). */
function settle(promise) {
  const hang = new Promise((resolve) => {
    setTimeout(() => resolve({ hung: true }), HANG_MS);
  });
  return Promise.race([
    promise.then(
      (value) => ({ resolved: value }),
      (error) => ({ rejected: error }),
    ),
    hang,
  ]);
}

function unauthorised(config) {
  return Promise.reject({ response: { status: 401 }, config });
}

describe('axiosClient — a refused token refresh', () => {
  let originalAdapter;
  let dispatchSpy;
  let refreshCalls;

  const countEvents = (type) =>
    dispatchSpy.mock.calls.filter(([event]) => event?.type === type).length;

  function clearStorage() {
    [TOKEN_KEY, REFRESH_KEY, ROLE_KEY].forEach((k) => localStorage.removeItem(k));
  }

  beforeEach(() => {
    clearStorage();
    refreshCalls = 0;
    originalAdapter = apiClient.defaults.adapter;
    dispatchSpy = vi.spyOn(window, 'dispatchEvent');
  });

  afterEach(() => {
    apiClient.defaults.adapter = originalAdapter;
    clearStorage();
    dispatchSpy.mockRestore();
    vi.restoreAllMocks();
  });

  function routeWith(refreshResponse) {
    apiClient.defaults.adapter = (config) => {
      if (config.url === REFRESH_URL) {
        refreshCalls += 1;
        return refreshResponse(config);
      }
      if (config.headers['Authorization'] === 'Bearer fresh-access') {
        return Promise.resolve({ status: 200, data: { ok: true }, config });
      }
      return unauthorised(config);
    };
  }

  it('rejects the original request, signs the session out once and calls refresh once', async () => {
    localStorage.setItem(TOKEN_KEY, 'old-access');
    localStorage.setItem(REFRESH_KEY, 'revoked-refresh');
    localStorage.setItem(ROLE_KEY, 'PRO_USER');
    routeWith(unauthorised);

    const outcome = await settle(apiClient.post('/api/ask', { q: 'x' }));

    expect(outcome.hung).toBeUndefined();
    expect(outcome.rejected?.response?.status).toBe(401);
    expect(countEvents('goldenhour:session-expired')).toBe(1);
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull();
    expect(localStorage.getItem(ROLE_KEY)).toBeNull();
    expect(refreshCalls).toBe(1);
  });

  it('rejects two concurrent 401s with one refresh call and one session-expired', async () => {
    localStorage.setItem(TOKEN_KEY, 'old-access');
    localStorage.setItem(REFRESH_KEY, 'revoked-refresh');
    routeWith(unauthorised);

    const [a, b] = await Promise.all([
      settle(apiClient.get('/api/one')),
      settle(apiClient.get('/api/two')),
    ]);

    expect(a.hung).toBeUndefined();
    expect(b.hung).toBeUndefined();
    expect(a.rejected?.response?.status).toBe(401);
    expect(b.rejected?.response?.status).toBe(401);
    expect(refreshCalls).toBe(1);
    expect(countEvents('goldenhour:session-expired')).toBe(1);
  });

  it('is not wedged afterwards: a later 401 with a new refresh token refreshes and retries', async () => {
    localStorage.setItem(TOKEN_KEY, 'old-access');
    localStorage.setItem(REFRESH_KEY, 'revoked-refresh');
    routeWith(unauthorised);
    const first = await settle(apiClient.get('/api/one'));
    expect(first.rejected?.response?.status).toBe(401);

    localStorage.setItem(TOKEN_KEY, 'old-access');
    localStorage.setItem(REFRESH_KEY, 'good-refresh');
    refreshCalls = 0;
    routeWith((config) => Promise.resolve({
      status: 200,
      data: { accessToken: 'fresh-access', refreshToken: 'next-refresh' },
      config,
    }));

    const second = await settle(apiClient.get('/api/two'));

    expect(second.hung).toBeUndefined();
    expect(second.resolved?.data).toEqual({ ok: true });
    expect(refreshCalls).toBe(1);
    expect(localStorage.getItem(TOKEN_KEY)).toBe('fresh-access');
    expect(localStorage.getItem(REFRESH_KEY)).toBe('next-refresh');
  });
});
