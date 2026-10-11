import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

vi.mock('../api/auroraApi.js', () => ({
  getAuroraViewline: vi.fn(),
  getAuroraForecastViewline: vi.fn(),
}));

import { getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';

const liveViewline = {
  points: [{ longitude: -5, latitude: 49 }],
  summary: 'Visible across the whole of the UK',
  southernmostLatitude: 49,
  active: true,
  isForecast: false,
  kp: null,
  asOf: '2026-10-10T21:40:00Z',
};

const forecastViewline = {
  points: [{ longitude: -12, latitude: 45 }, { longitude: 4, latitude: 47 }],
  summary: 'Visible across the whole of the UK',
  southernmostLatitude: 45,
  active: true,
  isForecast: true,
  kp: 6.67,
  asOf: '2026-10-10T18:40:00Z',
};

/** A later forecast, drawn for a revised Kp, so the two forecast answers cannot be confused. */
const revisedForecastViewline = {
  ...forecastViewline,
  points: [{ longitude: -12, latitude: 42 }, { longitude: 4, latitude: 44 }],
  southernmostLatitude: 42,
  kp: 7.0,
};

const inactive = { points: [], summary: 'No significant aurora in UK range', active: false, isForecast: false };
const inactiveForecast = { points: [], summary: 'No forecast available', active: false, isForecast: true };

/** Flush all pending microtasks (resolved promises). */
function flushPromises() {
  return act(() => new Promise((r) => setTimeout(r, 0)));
}

/** A request this test settles by hand, so it — not the scheduler — decides which lands first. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Settles a hand-held request inside an AWAITED `act`, so the hook's write — if it makes one — has
 * run and committed before the test reads it. The `await` is the load-bearing part: an un-awaited
 * `act(() => settle())` lets a late-answer test pass because the late write lands after `act` has
 * returned.
 */
async function land(settle) {
  await act(async () => { settle(); });
}

describe('useAuroraViewline', () => {
  beforeEach(() => {
    // Reset rather than clear: a `mockReturnValueOnce` a failed test never consumed would otherwise
    // be the next test's first answer.
    vi.resetAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe('selection: always live, the forecast as the fallback, never both', () => {
    it('offers the live line when both are drawable', async () => {
      getAuroraViewline.mockResolvedValue(liveViewline);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();

      expect(result.current.viewline).toBe(liveViewline);
      expect(result.current.source).toBe('live');
    });

    it('offers the forecast line while the live answer is inactive', async () => {
      getAuroraViewline.mockResolvedValue(inactive);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();

      expect(result.current.viewline).toBe(forecastViewline);
      expect(result.current.source).toBe('forecast');
    });

    it('offers the forecast line while the live request is still pending', async () => {
      const liveRequest = deferred();
      getAuroraViewline.mockReturnValue(liveRequest.promise);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();
      expect(result.current.source).toBe('forecast');

      await land(() => liveRequest.resolve(liveViewline));
      expect(result.current.viewline).toBe(liveViewline);
      expect(result.current.source).toBe('live');
    });

    it('offers the forecast line when the live request fails', async () => {
      getAuroraViewline.mockRejectedValue(new Error('network'));
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();

      expect(result.current.source).toBe('forecast');
    });

    it('offers nothing when neither is drawable', async () => {
      getAuroraViewline.mockResolvedValue(inactive);
      getAuroraForecastViewline.mockResolvedValue(inactiveForecast);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();

      expect(result.current).toEqual({ viewline: null, source: null });
    });

    it('offers nothing when both come back null (a LITE reader\'s 403)', async () => {
      getAuroraViewline.mockResolvedValue(null);
      getAuroraForecastViewline.mockResolvedValue(null);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();

      expect(result.current).toEqual({ viewline: null, source: null });
    });
  });

  describe('polling', () => {
    it('asks both endpoints on mount, and both again every 5 minutes', async () => {
      getAuroraViewline.mockResolvedValue(liveViewline);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      renderHook(() => useAuroraViewline(true));
      await flushPromises();
      expect(getAuroraViewline).toHaveBeenCalledTimes(1);
      expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);

      await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
      expect(getAuroraViewline).toHaveBeenCalledTimes(2);
      expect(getAuroraForecastViewline).toHaveBeenCalledTimes(2);

      await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
      expect(getAuroraViewline).toHaveBeenCalledTimes(3);
      expect(getAuroraForecastViewline).toHaveBeenCalledTimes(3);
    });

    it('follows a revised forecast on the next poll with nothing else changing', async () => {
      getAuroraViewline.mockResolvedValue(inactive);
      getAuroraForecastViewline.mockResolvedValueOnce(forecastViewline);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();
      expect(result.current.viewline.kp).toBe(6.67);

      getAuroraForecastViewline.mockResolvedValueOnce(revisedForecastViewline);
      await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });

      expect(result.current.viewline.kp).toBe(7.0);
      expect(result.current.source).toBe('forecast');
    });

    it('hands the map to the forecast when a later live poll answers inactive', async () => {
      getAuroraViewline.mockResolvedValueOnce(liveViewline);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();
      expect(result.current.source).toBe('live');

      getAuroraViewline.mockResolvedValueOnce(inactive);
      await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });

      expect(result.current.source).toBe('forecast');
    });

    it('keeps an endpoint\'s previous line when its next poll fails', async () => {
      getAuroraViewline.mockResolvedValueOnce(liveViewline);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();

      getAuroraViewline.mockRejectedValueOnce(new Error('network'));
      await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });

      expect(result.current.viewline).toBe(liveViewline);
    });

    it('keeps the forecast endpoint\'s previous line when its next poll fails', async () => {
      getAuroraViewline.mockResolvedValue(inactive);
      getAuroraForecastViewline.mockResolvedValueOnce(forecastViewline);

      const { result } = renderHook(() => useAuroraViewline(true));
      await flushPromises();

      getAuroraForecastViewline.mockRejectedValueOnce(new Error('network'));
      await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });

      expect(result.current.viewline).toBe(forecastViewline);
      expect(result.current.source).toBe('forecast');
    });

    it('does not ask the forecast endpoint, nor offer a forecast line, with includeForecast false', async () => {
      getAuroraViewline.mockResolvedValue(inactive);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const { result } = renderHook(() => useAuroraViewline(true, { includeForecast: false }));
      await flushPromises();
      await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });

      expect(getAuroraForecastViewline).not.toHaveBeenCalled();
      expect(getAuroraViewline).toHaveBeenCalledTimes(2);
      expect(result.current).toEqual({ viewline: null, source: null });
    });

    it('stops polling on unmount', async () => {
      getAuroraViewline.mockResolvedValue(liveViewline);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const { unmount } = renderHook(() => useAuroraViewline(true));
      await flushPromises();
      unmount();

      await act(async () => { vi.advanceTimersByTime(15 * 60 * 1000); });
      expect(getAuroraViewline).toHaveBeenCalledTimes(1);
      expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
    });
  });

  describe('enabled', () => {
    it('asks nothing while disabled', async () => {
      const { result } = renderHook(() => useAuroraViewline(false));
      await flushPromises();

      expect(getAuroraViewline).not.toHaveBeenCalled();
      expect(getAuroraForecastViewline).not.toHaveBeenCalled();
      expect(result.current).toEqual({ viewline: null, source: null });
    });

    it('withholds the line in the render that disables it, and starts the next alert with none', async () => {
      getAuroraViewline.mockResolvedValue(liveViewline);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const seen = [];
      const { result, rerender } = renderHook(({ enabled }) => {
        const out = useAuroraViewline(enabled);
        seen.push({ enabled, viewline: out.viewline });
        return out;
      }, { initialProps: { enabled: true } });
      await flushPromises();
      expect(result.current.viewline).toBe(liveViewline);

      rerender({ enabled: false });
      // Every render with the hook disabled, including the first (before any effect ran), is empty.
      expect(seen.filter((s) => !s.enabled).every((s) => s.viewline === null)).toBe(true);

      // A later alert asks afresh and is not handed the ended alert's line while it waits.
      const liveRequest = deferred();
      getAuroraViewline.mockReturnValue(liveRequest.promise);
      getAuroraForecastViewline.mockReturnValue(deferred().promise);
      rerender({ enabled: true });
      await flushPromises();
      expect(result.current.viewline).toBeNull();
    });

    it('drops a response that lands after the hook was disabled', async () => {
      const liveRequest = deferred();
      getAuroraViewline.mockReturnValue(liveRequest.promise);
      getAuroraForecastViewline.mockResolvedValue(forecastViewline);

      const { result, rerender } = renderHook(({ enabled }) => useAuroraViewline(enabled), {
        initialProps: { enabled: true },
      });
      await flushPromises();
      rerender({ enabled: false });

      await land(() => liveRequest.resolve(liveViewline));

      // Re-enabled with both new requests still out: the old run's late answer must not be there.
      getAuroraViewline.mockReturnValue(deferred().promise);
      getAuroraForecastViewline.mockReturnValue(deferred().promise);
      rerender({ enabled: true });
      await flushPromises();

      expect(result.current).toEqual({ viewline: null, source: null });
    });

    it('drops a FORECAST response that lands after the hook was disabled', async () => {
      const forecastRequest = deferred();
      getAuroraViewline.mockResolvedValue(inactive);
      getAuroraForecastViewline.mockReturnValue(forecastRequest.promise);

      const { result, rerender } = renderHook(({ enabled }) => useAuroraViewline(enabled), {
        initialProps: { enabled: true },
      });
      await flushPromises();
      rerender({ enabled: false });

      await land(() => forecastRequest.resolve(forecastViewline));

      getAuroraViewline.mockReturnValue(deferred().promise);
      getAuroraForecastViewline.mockReturnValue(deferred().promise);
      rerender({ enabled: true });
      await flushPromises();

      expect(result.current).toEqual({ viewline: null, source: null });
    });
  });
});
