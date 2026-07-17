import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as AuthApi from '../api/authApi.js';
import apiClient from '../api/axiosClient.js';

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

  describe('response interceptor — refresh on 401', () => {
    it('dispatches session-expired event when refresh fails', async () => {
      localStorage.setItem(TOKEN_KEY, 'expired-jwt');
      localStorage.setItem(REFRESH_KEY, 'old-refresh');

      AuthApi.refreshAccessToken.mockRejectedValue(new Error('Refresh failed'));

      const adapter = vi.fn().mockRejectedValue({
        response: { status: 401 },
        config: { headers: {}, _retried: false },
      });

      try {
        await apiClient.get('/api/test', { adapter });
      } catch {
        // Expected to throw
      }

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
});
