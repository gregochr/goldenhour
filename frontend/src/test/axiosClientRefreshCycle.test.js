import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Deliberately does NOT mock authApi: the real refresh request goes through the real
// interceptor, which is the cycle that used to deadlock (the refresh's own 401 re-entered
// the refresh path and awaited itself).

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';
const TIMEOUT_MS = 500;

/** Races a promise against a short timeout so a hang fails this one test fast. */
function settle(promise) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('HUNG: request did not settle')), TIMEOUT_MS);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

function reject401(config) {
  return Promise.reject({ response: { status: 401, data: {} }, config });
}

function ok(config, data) {
  return Promise.resolve({ status: 200, data, config, headers: {} });
}

describe('axiosClient — refresh cycle against the real authApi', () => {
  let apiClient;
  let authApi;
  let adapter;
  let dispatchSpy;

  const urls = () => adapter.mock.calls.map(([config]) => config.url);
  const count = (url) => urls().filter((u) => u === url).length;
  const events = (type) => dispatchSpy.mock.calls.filter(([event]) => event.type === type);

  beforeEach(async () => {
    vi.resetModules();
    localStorage.clear();
    apiClient = (await import('../api/axiosClient.js')).default;
    authApi = await import('../api/authApi.js');
    adapter = vi.fn();
    apiClient.defaults.adapter = adapter;
    dispatchSpy = vi.spyOn(window, 'dispatchEvent');
  });

  afterEach(() => {
    dispatchSpy.mockRestore();
    localStorage.clear();
  });

  it('(a) rejects with the original 401 when the refresh token is refused, with no retry', async () => {
    localStorage.setItem(TOKEN_KEY, 'old');
    localStorage.setItem(REFRESH_KEY, 'old-r');
    adapter.mockImplementation(reject401);

    await expect(settle(apiClient.get('/api/briefing'))).rejects.toMatchObject({
      response: { status: 401 },
    });

    expect(adapter).toHaveBeenCalledTimes(2);
    expect(count('/api/briefing')).toBe(1);
    expect(count('/api/auth/refresh')).toBe(1);
    expect(events('goldenhour:session-expired')).toHaveLength(1);
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_KEY)).toBeNull();
  });

  it('(a2) a 401 during logout, with storage already cleared, posts no /refresh and does not expire a session',
    async () => {
      // AuthContext.logout captures the refresh token, clears storage, THEN posts /logout. The server has
      // already bumped the credential version, so an in-flight request can 401 in that window.
      localStorage.setItem(TOKEN_KEY, 'old');
      localStorage.setItem(REFRESH_KEY, 'old-r');
      const captured = localStorage.getItem(REFRESH_KEY);
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(REFRESH_KEY);
      adapter.mockImplementation(reject401);

      const results = await settle(Promise.allSettled([
        authApi.logout(captured),
        apiClient.get('/api/briefing'),
      ]));

      expect(results.map((r) => r.status)).toEqual(['rejected', 'rejected']);
      expect(count('/api/auth/refresh')).toBe(0);
      expect(count('/api/auth/logout')).toBe(1);
      expect(count('/api/briefing')).toBe(1);
      expect(events('goldenhour:session-expired')).toHaveLength(0);
    });

  it('(b) two concurrent 401s share one refresh and both reject', async () => {
    localStorage.setItem(TOKEN_KEY, 'old');
    localStorage.setItem(REFRESH_KEY, 'old-r');
    adapter.mockImplementation(reject401);

    const results = await settle(Promise.allSettled([
      apiClient.get('/api/briefing'),
      apiClient.get('/api/almanac'),
    ]));

    expect(results.map((r) => r.status)).toEqual(['rejected', 'rejected']);
    expect(results.map((r) => r.reason.response.status)).toEqual([401, 401]);
    expect(count('/api/auth/refresh')).toBe(1);
    expect(adapter).toHaveBeenCalledTimes(3);
    expect(events('goldenhour:session-expired')).toHaveLength(1);
  });

  it('(c) retries with the new token after a successful refresh', async () => {
    localStorage.setItem(TOKEN_KEY, 'old');
    localStorage.setItem(REFRESH_KEY, 'old-r');
    adapter.mockImplementation((config) => {
      if (config.url === '/api/auth/refresh') {
        return ok(config, { accessToken: 'new', refreshToken: 'new-r' });
      }
      return config.headers['Authorization'] === 'Bearer new'
        ? ok(config, { fine: true })
        : reject401(config);
    });

    const res = await settle(apiClient.get('/api/briefing'));

    expect(res.data).toEqual({ fine: true });
    const briefingCalls = adapter.mock.calls.filter(([c]) => c.url === '/api/briefing');
    expect(briefingCalls).toHaveLength(2);
    expect(briefingCalls[1][0].headers['Authorization']).toBe('Bearer new');
    expect(events('goldenhour:token-refreshed')).toHaveLength(1);
    expect(events('goldenhour:session-expired')).toHaveLength(0);
    expect(localStorage.getItem(TOKEN_KEY)).toBe('new');
    expect(localStorage.getItem(REFRESH_KEY)).toBe('new-r');
  });

  it('(d) a refused login rejects with 401 and never touches the refresh path', async () => {
    localStorage.setItem(TOKEN_KEY, 'old');
    localStorage.setItem(REFRESH_KEY, 'old-r');
    adapter.mockImplementation(reject401);

    await expect(settle(authApi.login('u', 'bad', 't'))).rejects.toMatchObject({
      response: { status: 401 },
    });

    expect(adapter).toHaveBeenCalledTimes(1);
    expect(count('/api/auth/refresh')).toBe(0);
    expect(events('goldenhour:session-expired')).toHaveLength(0);
  });

  it('(e) a direct refresh refused with 401 rejects promptly with no nested refresh', async () => {
    localStorage.setItem(TOKEN_KEY, 'old');
    localStorage.setItem(REFRESH_KEY, 'old-r');
    adapter.mockImplementation(reject401);

    await expect(settle(authApi.refreshAccessToken('x'))).rejects.toMatchObject({
      response: { status: 401 },
    });

    expect(adapter).toHaveBeenCalledTimes(1);
    expect(count('/api/auth/refresh')).toBe(1);
  });

  describe('A6 — unauthenticated auth calls never refresh or sign anyone out', () => {
    const calls = [
      ['register', () => authApi.register('u', 'e@x.com', 't', true, true)],
      ['resendVerification', () => authApi.resendVerification('e@x.com')],
      ['verifyEmail', () => authApi.verifyEmail('tok')],
      ['setPasswordForNewUser', () => authApi.setPasswordForNewUser('tok', 'pw')],
      ['requestPasswordReset', () => authApi.requestPasswordReset('e@x.com', 't')],
      ['resetPassword', () => authApi.resetPassword('tok', 'pw')],
      ['submitWaitlist', () => authApi.submitWaitlist('e@x.com')],
    ];

    it.each(calls)('%s: a 401 rejects with no refresh and no session-expired', async (_name, call) => {
      localStorage.setItem(TOKEN_KEY, 'other-users-token');
      localStorage.setItem(REFRESH_KEY, 'other-users-r');
      adapter.mockImplementation(reject401);

      await expect(settle(call())).rejects.toMatchObject({ response: { status: 401 } });

      expect(adapter).toHaveBeenCalledTimes(1);
      expect(count('/api/auth/refresh')).toBe(0);
      expect(events('goldenhour:session-expired')).toHaveLength(0);
      expect(localStorage.getItem(TOKEN_KEY)).toBe('other-users-token');
      expect(localStorage.getItem(REFRESH_KEY)).toBe('other-users-r');
    });
  });

  describe('a refreshed token that cannot be persisted', () => {
    function refreshOkThenBearerNew(config) {
      if (config.url === '/api/auth/refresh') {
        return ok(config, { accessToken: 'new', refreshToken: 'new-r' });
      }
      return config.headers['Authorization'] === 'Bearer new'
        ? ok(config, { fine: true })
        : reject401(config);
    }

    function failStorageWrites() {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('full', 'QuotaExceededError');
      });
      vi.spyOn(console, 'warn').mockImplementation(() => {});
    }

    afterEach(() => vi.restoreAllMocks());

    it('B1: the retry carries the new token although storage still holds the old one', async () => {
      localStorage.setItem(TOKEN_KEY, 'old');
      localStorage.setItem(REFRESH_KEY, 'old-r');
      adapter.mockImplementation(refreshOkThenBearerNew);
      failStorageWrites();

      const res = await settle(apiClient.get('/api/briefing'));

      expect(res.data).toEqual({ fine: true });
      const briefingCalls = adapter.mock.calls.filter(([c]) => c.url === '/api/briefing');
      expect(briefingCalls).toHaveLength(2);
      expect(briefingCalls[1][0].headers['Authorization']).toBe('Bearer new');
      expect(localStorage.getItem(TOKEN_KEY)).toBe('old');
      expect(events('goldenhour:session-expired')).toHaveLength(0);
    });

    it('B2: a concurrent waiter retries with the new token too', async () => {
      localStorage.setItem(TOKEN_KEY, 'old');
      localStorage.setItem(REFRESH_KEY, 'old-r');
      adapter.mockImplementation(refreshOkThenBearerNew);
      failStorageWrites();

      const results = await settle(Promise.all([
        apiClient.get('/api/briefing'),
        apiClient.get('/api/almanac'),
      ]));

      expect(results.map((r) => r.data)).toEqual([{ fine: true }, { fine: true }]);
      expect(count('/api/auth/refresh')).toBe(1);
      const retries = adapter.mock.calls.filter(([c]) => c.url !== '/api/auth/refresh' && c.headers['Authorization'] === 'Bearer new');
      expect(retries).toHaveLength(2);
      retries.forEach(([c]) => expect(c.headers['Authorization']).toBe('Bearer new'));
    });

    it('B3: a fresh request without a handed-down token still uses the storage token', async () => {
      localStorage.setItem(TOKEN_KEY, 'stored');
      adapter.mockImplementation((config) => ok(config, {}));

      await settle(apiClient.get('/api/briefing'));

      expect(adapter.mock.calls[0][0].headers['Authorization']).toBe('Bearer stored');
    });
  });
});
