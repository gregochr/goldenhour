import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

vi.mock('../api/auroraApi.js', () => ({
  getAuroraViewline: vi.fn(),
  getAuroraForecastViewline: vi.fn(),
}));

import { getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';

const liveViewline = {
  points: [{ longitude: -5, latitude: 54 }],
  summary: 'Visible as far south as northern England',
  southernmostLatitude: 54,
  active: true,
  isForecast: false,
};

const forecastViewline = {
  points: [{ longitude: -12, latitude: 56 }, { longitude: 4, latitude: 56 }],
  summary: 'Visible as far south as central Scotland',
  southernmostLatitude: 56,
  active: true,
  isForecast: true,
};

/** Flush all pending microtasks (resolved promises). */
function flushPromises() {
  return act(() => new Promise((r) => setTimeout(r, 0)));
}

describe('useAuroraViewline', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    getAuroraViewline.mockResolvedValue(liveViewline);
    getAuroraForecastViewline.mockResolvedValue(forecastViewline);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // ---------------------------------------------------------------------------
  // Disabled state
  // ---------------------------------------------------------------------------

  it('returns null viewline when not enabled', async () => {
    const { result } = renderHook(() => useAuroraViewline(false));
    await flushPromises();

    expect(result.current.viewline).toBeNull();
    expect(getAuroraViewline).not.toHaveBeenCalled();
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------------------
  // Realtime (live) path
  // ---------------------------------------------------------------------------

  it('calls getAuroraViewline for realtime triggerType', async () => {
    const { result } = renderHook(() => useAuroraViewline(true, 'realtime'));
    await flushPromises();

    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
    expect(result.current.viewline).toEqual(liveViewline);
  });

  it('calls getAuroraViewline when triggerType is null (backwards compat)', async () => {
    const { result } = renderHook(() => useAuroraViewline(true, null));
    await flushPromises();

    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
    expect(result.current.viewline).toEqual(liveViewline);
  });

  it('polls every 5 minutes for realtime', async () => {
    renderHook(() => useAuroraViewline(true, 'realtime'));
    await flushPromises();

    expect(getAuroraViewline).toHaveBeenCalledTimes(1);

    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    expect(getAuroraViewline).toHaveBeenCalledTimes(2);

    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    expect(getAuroraViewline).toHaveBeenCalledTimes(3);
  });

  // ---------------------------------------------------------------------------
  // Forecast path
  // ---------------------------------------------------------------------------

  it('calls getAuroraForecastViewline for forecast triggerType', async () => {
    const { result } = renderHook(() => useAuroraViewline(true, 'forecast'));
    await flushPromises();

    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraViewline).not.toHaveBeenCalled();
    expect(result.current.viewline).toEqual(forecastViewline);
  });

  it('does not poll for forecast triggerType', async () => {
    renderHook(() => useAuroraViewline(true, 'forecast'));
    await flushPromises();

    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);

    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    // Still just the one initial call — no polling
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
  });

  // ---------------------------------------------------------------------------
  // Cleanup and transitions
  // ---------------------------------------------------------------------------

  it('clears viewline when disabled after being enabled', async () => {
    const { result, rerender } = renderHook(
      ({ enabled, triggerType }) => useAuroraViewline(enabled, triggerType),
      { initialProps: { enabled: true, triggerType: 'realtime' } },
    );
    await flushPromises();

    expect(result.current.viewline).toEqual(liveViewline);

    rerender({ enabled: false, triggerType: 'realtime' });
    expect(result.current.viewline).toBeNull();
  });

  it('stops polling when disabled', async () => {
    const { rerender } = renderHook(
      ({ enabled }) => useAuroraViewline(enabled, 'realtime'),
      { initialProps: { enabled: true } },
    );
    await flushPromises();

    expect(getAuroraViewline).toHaveBeenCalledTimes(1);

    rerender({ enabled: false });

    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    // No additional calls after disabling
    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
  });

  // ---------------------------------------------------------------------------
  // Error resilience
  // ---------------------------------------------------------------------------

  it('retains existing viewline on fetch error', async () => {
    const { result } = renderHook(() => useAuroraViewline(true, 'realtime'));
    await flushPromises();

    expect(result.current.viewline).toEqual(liveViewline);

    // Next poll fails
    getAuroraViewline.mockRejectedValueOnce(new Error('network'));
    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();

    // Still has the original viewline
    expect(result.current.viewline).toEqual(liveViewline);
  });
});
