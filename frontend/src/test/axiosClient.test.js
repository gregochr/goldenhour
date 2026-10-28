import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as AuthApi from '../api/authApi.js';
import apiClient, { REWIND_HEADER, SKIP_AUTH_REFRESH } from '../api/axiosClient.js';
import { setRewind } from '../utils/rewind.js';

vi.mock('../api/authApi.js');

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';

describe('axiosClient interceptors', () => {
  let dispatchSpy;

  beforeEach(() => {
    [TOKEN_KEY, REFRESH_KEY].forEach(k => localStorage.removeItem(k));
    dispatchSpy = vi.spyOn(window, 'dispatchEvent');
  });

  afterEach(() => {
    [TOKEN_KEY, REFRESH_KEY].forEach(k => localStorage.removeItem(k));
    dispatchSpy.mockRestore();
    vi.restoreAllMocks();
  });

  describe('request interceptor', () => {
    it('attaches Authorization header when token exists', async () => {
      localStorage.setItem(TOKEN_KEY, 'my-jwt');
      const adapter = vi.fn().mockResolvedValue({ status: 200, data: {} });

      await apiClient.get('/api/test', { adapter });

      const config = adapter.mock.calls[0][0];
      expect(config.headers['Authorization']).toBe('Bearer my-jwt');
    });

    it('does not attach Authorization header when no token', async () => {
      const adapter = vi.fn().mockResolvedValue({ status: 200, data: {} });

      await apiClient.get('/api/test', { adapter });

      const config = adapter.mock.calls[0][0];
      expect(config.headers['Authorization']).toBeUndefined();
    });
  });

  describe('request interceptor — the admin rewind header', () => {
    afterEach(() => setRewind(null));

    it('sends no X-Rewind-To while the page is live', async () => {
      const adapter = vi.fn().mockResolvedValue({ status: 200, data: {} });
      await apiClient.get('/api/briefing', { adapter });
      expect(adapter.mock.calls[0][0].headers[REWIND_HEADER]).toBeUndefined();
    });

    it('sends the rewound instant as X-Rewind-To on a GET while a rewind is set', async () => {
      setRewind('2026-10-04T05:58:00Z');
      const adapter = vi.fn().mockResolvedValue({ status: 200, data: {} });
      await apiClient.get('/api/briefing', { adapter });
      expect(adapter.mock.calls[0][0].headers[REWIND_HEADER]).toBe('2026-10-04T05:58:00Z');
    });

    it('reads the rewind per request, so a poll made after it was set carries it and one after it was cleared does not', async () => {
      const adapter = vi.fn().mockResolvedValue({ status: 200, data: {} });
      await apiClient.get('/api/briefing', { adapter });
      setRewind('2026-10-04T05:58:00Z');
      await apiClient.get('/api/briefing', { adapter });
      setRewind(null);
      await apiClient.get('/api/briefing', { adapter });
      expect(adapter.mock.calls.map((c) => c[0].headers[REWIND_HEADER]))
        .toEqual([undefined, '2026-10-04T05:58:00Z', undefined]);
    });

    it('never sends it on a write — a rewind only reads', async () => {
      setRewind('2026-10-04T05:58:00Z');
      const adapter = vi.fn().mockResolvedValue({ status: 200, data: {} });
      await apiClient.post('/api/briefing/run', {}, { adapter });
      await apiClient.put('/api/user/settings/map-colours', {}, { adapter });
      expect(adapter.mock.calls.map((c) => c[0].headers[REWIND_HEADER])).toEqual([undefined, undefined]);
    });

    it('never sends it under /api/admin/ — the admin screens read the real clock', async () => {
      setRewind('2026-10-04T05:58:00Z');
      const adapter = vi.fn().mockResolvedValue({ status: 200, data: {} });
      await apiClient.get('/api/admin/rewind/events', { adapter });
      await apiClient.get('/api/admin/scheduler/jobs', { adapter });
      expect(adapter.mock.calls.map((c) => c[0].headers[REWIND_HEADER])).toEqual([undefined, undefined]);
    });
  });

  describe('response interceptor — refresh on 401', () => {
    it('dispatches session-expired event when refresh fails', async () => {
      localStorage.setItem(TOKEN_KEY, 'expired-jwt');
      localStorage.setItem(REFRESH_KEY, 'old-refresh');

      AuthApi.refreshAccessToken.mockRejectedValue(new Error('Refresh failed'));

      const adapter = vi.fn().mockRejectedValue({
        response: { status: 401 },
        config: { headers: {}, _retried: false },
      });

      // The original request rejects with its own 401 and is not retried with an empty token.
      await expect(apiClient.get('/api/test', { adapter })).rejects.toMatchObject({
        response: { status: 401 },
      });
      expect(adapter).toHaveBeenCalledTimes(1);

      // Should have dispatched the session-expired event
      const expiredEvents = dispatchSpy.mock.calls.filter(
        ([event]) => event.type === 'goldenhour:session-expired'
      );
      expect(expiredEvents.length).toBe(1);

      // Should have cleared tokens from localStorage
      expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
      expect(localStorage.getItem(REFRESH_KEY)).toBeNull();
    });

    it('dispatches token-refreshed and stores new tokens on a successful refresh', async () => {
      localStorage.setItem(TOKEN_KEY, 'expired-jwt');
      localStorage.setItem(REFRESH_KEY, 'good-refresh');

      AuthApi.refreshAccessToken.mockResolvedValue({
        accessToken: 'fresh-jwt',
        refreshToken: 'rotated-refresh',
      });

      // Reject the first attempt with 401, then succeed on the retry.
      let call = 0;
      const adapter = vi.fn((config) => {
        call += 1;
        if (call === 1) {
          return Promise.reject({ response: { status: 401 }, config });
        }
        return Promise.resolve({ status: 200, data: { ok: true }, config, headers: {} });
      });

      const res = await apiClient.get('/api/test', { adapter });
      expect(res.data).toEqual({ ok: true });

      // localStorage rolled forward to the fresh tokens...
      expect(localStorage.getItem(TOKEN_KEY)).toBe('fresh-jwt');
      expect(localStorage.getItem(REFRESH_KEY)).toBe('rotated-refresh');

      // ...and AuthContext (and any other in-memory consumer, e.g. the health SSE
      // stream) is notified so it can re-sync instead of stranding on the old token.
      const refreshedEvents = dispatchSpy.mock.calls.filter(
        ([event]) => event.type === 'goldenhour:token-refreshed'
      );
      expect(refreshedEvents.length).toBe(1);

      // The retry carried the fresh token.
      expect(adapter.mock.calls[1][0].headers['Authorization']).toBe('Bearer fresh-jwt');
    });

    it('rejects a 401 on a request flagged skipAuthRefresh without calling refreshAccessToken', async () => {
      localStorage.setItem(TOKEN_KEY, 'jwt');
      localStorage.setItem(REFRESH_KEY, 'refresh');

      const adapter = vi.fn((config) => Promise.reject({ response: { status: 401 }, config }));

      await expect(apiClient.post('/api/auth/login', {}, { adapter, [SKIP_AUTH_REFRESH]: true }))
        .rejects.toMatchObject({ response: { status: 401 } });

      expect(AuthApi.refreshAccessToken).not.toHaveBeenCalled();
      expect(adapter).toHaveBeenCalledTimes(1);
      const expiredEvents = dispatchSpy.mock.calls.filter(
        ([event]) => event.type === 'goldenhour:session-expired'
      );
      expect(expiredEvents.length).toBe(0);
    });

    describe('a 401 with no refresh token stored', () => {
      const reject401 = (config) => Promise.reject({ response: { status: 401 }, config });
      const expired = () => dispatchSpy.mock.calls.filter(
        ([event]) => event.type === 'goldenhour:session-expired'
      );

      it('A1: signs the dead session out when the request carried the stored token', async () => {
        localStorage.setItem(TOKEN_KEY, 'old');
        localStorage.setItem('goldenhour_role', 'PRO_USER');
        const adapter = vi.fn(reject401);

        await expect(apiClient.get('/api/test', { adapter })).rejects.toMatchObject({
          response: { status: 401 },
        });

        expect(adapter).toHaveBeenCalledTimes(1);
        expect(AuthApi.refreshAccessToken).not.toHaveBeenCalled();
        expect(expired()).toHaveLength(1);
        expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
        expect(localStorage.getItem('goldenhour_role')).toBeNull();
      });

      it('A2: two concurrent such 401s raise exactly one session-expired', async () => {
        localStorage.setItem(TOKEN_KEY, 'old');
        const adapter = vi.fn(reject401);

        const results = await Promise.allSettled([
          apiClient.get('/api/a', { adapter }),
          apiClient.get('/api/b', { adapter }),
        ]);

        expect(results.map((r) => r.status)).toEqual(['rejected', 'rejected']);
        expect(expired()).toHaveLength(1);
      });

      it('A3: does nothing when the request carried no Authorization header', async () => {
        localStorage.setItem('goldenhour_role', 'PRO_USER');
        const adapter = vi.fn(reject401);

        await expect(apiClient.get('/api/test', { adapter })).rejects.toMatchObject({
          response: { status: 401 },
        });

        expect(expired()).toHaveLength(0);
        expect(localStorage.getItem('goldenhour_role')).toBe('PRO_USER');
      });

      it('A4: leaves a newer stored token alone when the failed request carried an older one', async () => {
        localStorage.setItem(TOKEN_KEY, 'old');
        // The request goes out with `old`; meanwhile a fresh sign-in stores `newer`.
        const adapter = vi.fn((config) => {
          localStorage.setItem(TOKEN_KEY, 'newer');
          return reject401(config);
        });

        await expect(apiClient.get('/api/test', { adapter })).rejects.toMatchObject({
          response: { status: 401 },
        });

        expect(expired()).toHaveLength(0);
        expect(localStorage.getItem(TOKEN_KEY)).toBe('newer');
      });

      it('A5: a skipAuthRefresh 401 or an already-retried 401 does not expire the session', async () => {
        localStorage.setItem(TOKEN_KEY, 'old');
        const adapter = vi.fn(reject401);

        await expect(apiClient.post('/api/auth/reset-password', {}, { adapter, [SKIP_AUTH_REFRESH]: true }))
          .rejects.toMatchObject({ response: { status: 401 } });
        await expect(apiClient.get('/api/test', { adapter, _retried: true }))
          .rejects.toMatchObject({ response: { status: 401 } });

        expect(expired()).toHaveLength(0);
        expect(localStorage.getItem(TOKEN_KEY)).toBe('old');
      });
    });

    it('does not dispatch session-expired for non-401 errors', async () => {
      localStorage.setItem(TOKEN_KEY, 'my-jwt');

      const adapter = vi.fn().mockRejectedValue({
        response: { status: 500 },
        config: { headers: {} },
      });

      try {
        await apiClient.get('/api/test', { adapter });
      } catch {
        // Expected to throw
      }

      const expiredEvents = dispatchSpy.mock.calls.filter(
        ([event]) => event.type === 'goldenhour:session-expired'
      );
      expect(expiredEvents.length).toBe(0);
    });
  });

  describe('response interceptor — password change required', () => {
    const raisedEvents = () => dispatchSpy.mock.calls.filter(
      ([event]) => event.type === 'goldenhour:password-change-required',
    );

    it('raises goldenhour:password-change-required on a 403 PASSWORD_CHANGE_REQUIRED, then rejects as usual', async () => {
      localStorage.setItem(TOKEN_KEY, 'my-jwt');
      localStorage.setItem(REFRESH_KEY, 'my-refresh');
      const error = {
        response: { status: 403, data: { error: 'Password change required', code: 'PASSWORD_CHANGE_REQUIRED' } },
        config: { headers: {} },
      };
      const adapter = vi.fn().mockRejectedValue(error);

      await expect(apiClient.get('/api/briefing', { adapter })).rejects.toBe(error);

      expect(raisedEvents()).toHaveLength(1);
      expect(adapter).toHaveBeenCalledTimes(1);
      expect(AuthApi.refreshAccessToken).not.toHaveBeenCalled();
    });

    it('stays silent for a 403 with another code, or none (a role refusal is not a password gate)', async () => {
      for (const data of [{ error: 'Forbidden', code: 'NOT_ENTITLED' }, { error: 'Forbidden' }, undefined]) {
        const adapter = vi.fn().mockRejectedValue({
          response: { status: 403, data },
          config: { headers: {} },
        });
        await expect(apiClient.get('/api/ask', { adapter })).rejects.toBeDefined();
      }

      expect(raisedEvents()).toHaveLength(0);
    });
  });
});
