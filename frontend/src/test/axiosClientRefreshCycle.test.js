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
});
