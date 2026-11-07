import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import apiClient, { REWIND_HEADER } from '../api/axiosClient.js';
import * as AuthApi from '../api/authApi.js';
import { setRewind } from '../utils/rewind.js';

vi.mock('../api/authApi.js');

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';

describe('axiosClient — edge branches', () => {
  let dispatchSpy;

  beforeEach(() => {
    localStorage.clear();
    dispatchSpy = vi.spyOn(window, 'dispatchEvent');
  });

  afterEach(() => {
    setRewind(null);
    vi.restoreAllMocks();
    localStorage.clear();
  });

  const events = (type) => dispatchSpy.mock.calls.filter(([e]) => e.type === type);
  const requestInterceptor = () => apiClient.interceptors.request.handlers[0].fulfilled;

  it('builds a headers object for a bare config and defaults it to a GET with no url', () => {
    localStorage.setItem(TOKEN_KEY, 'tok');
    setRewind('2026-10-04T05:58:00Z');

    const out = requestInterceptor()({});

    expect(out.headers.Authorization).toBe('Bearer tok');
    expect(out.headers[REWIND_HEADER]).toBe('2026-10-04T05:58:00Z');
  });

  it('builds a headers object for a bare config even when only a rewind is set', () => {
    setRewind('2026-10-04T05:58:00Z');

    const out = requestInterceptor()({});

    expect(out.headers).toEqual({ [REWIND_HEADER]: '2026-10-04T05:58:00Z' });
  });

  it('does not expire the session when a 401 request carried an empty bearer token', async () => {
    localStorage.setItem(TOKEN_KEY, 'tok');
    const adapter = vi.fn((config) => Promise.reject({
      response: { status: 401, data: {} },
      config: { ...config, headers: { Authorization: 'Bearer ' } },
    }));

    await expect(apiClient.get('/api/x', { adapter })).rejects.toBeDefined();

    expect(events('goldenhour:session-expired')).toHaveLength(0);
    expect(localStorage.getItem(TOKEN_KEY)).toBe('tok');
  });

  it('warns with the thrown value itself when the persist failure has no name', async () => {
    localStorage.setItem(TOKEN_KEY, 'old');
    localStorage.setItem(REFRESH_KEY, 'old-r');
    AuthApi.refreshAccessToken.mockResolvedValue({ accessToken: 'new', refreshToken: 'new-r' });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw 'disk-full';
    });
    const adapter = vi.fn((config) => (config.headers.Authorization === 'Bearer new'
      ? Promise.resolve({ status: 200, data: { ok: true }, config, headers: {} })
      : Promise.reject({ response: { status: 401, data: {} }, config })));

    const res = await apiClient.get('/api/x', { adapter });

    expect(res.data).toEqual({ ok: true });
    expect(warn).toHaveBeenCalledWith('[auth] token refreshed but could not be persisted: disk-full');
    expect(events('goldenhour:session-expired')).toHaveLength(0);
  });
});
