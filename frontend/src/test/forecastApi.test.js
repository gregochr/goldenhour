import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import axios from 'axios';
import * as AuthApi from '../api/authApi.js';

// Import forecastApi to register its interceptors
import '../api/forecastApi.js';

vi.mock('../api/authApi.js');

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';

describe('forecastApi axios interceptors', () => {
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

      await axios.get('/api/test', { adapter });

      const config = adapter.mock.calls[0][0];
      expect(config.headers['Authorization']).toBe('Bearer my-jwt');
    });

    it('does not attach Authorization header when no token', async () => {
      const adapter = vi.fn().mockResolvedValue({ status: 200, data: {} });

      await axios.get('/api/test', { adapter });

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
        await axios.get('/api/test', { adapter });
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

    it('does not dispatch session-expired for non-401 errors', async () => {
      localStorage.setItem(TOKEN_KEY, 'my-jwt');

      const adapter = vi.fn().mockRejectedValue({
        response: { status: 500 },
        config: { headers: {} },
      });

      try {
        await axios.get('/api/test', { adapter });
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
