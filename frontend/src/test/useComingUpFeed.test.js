import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useComingUpFeed, { SILENT_RETRY_MS } from '../hooks/useComingUpFeed.js';

vi.mock('../api/almanacApi.js', () => ({ getAlmanac: vi.fn() }));

import { getAlmanac } from '../api/almanacApi.js';

const TODAY = '2026-08-28';

/** Flush the fetch effect's async IIFE. */
function flush() {
  return act(() => Promise.resolve());
}

describe('useComingUpFeed', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('stores the wrapped response as-is once it arrives', async () => {
    const wrapped = { builtFor: TODAY, bands: null, counts: null, conditions: [], entries: [] };
    getAlmanac.mockResolvedValue(wrapped);

    const { result } = renderHook(() => useComingUpFeed(true, TODAY));
    await flush();

    expect(result.current.status).toBe('ready');
    expect(result.current.events).toEqual(wrapped);
  });

  it('degrades a reverted bare-array response to the wrapped empty shape, not a pass-through', () => {
    // The guard is `typeof data === 'object'`, and `typeof [] === 'object'` in JS — so this proves
    // the array check is its own clause. A pass-through here would store the raw array as `events`
    // instead of `{ entries: [] }`, silently defeating the shape the rest of the pane depends on.
    getAlmanac.mockResolvedValue([{ type: 'meteor', title: 'Perseids' }]);

    const { result } = renderHook(() => useComingUpFeed(true, TODAY));

    return flush().then(() => {
      expect(result.current.status).toBe('ready');
      expect(result.current.events).toEqual({ entries: [] });
    });
  });

  it('degrades a null response to the wrapped empty shape', async () => {
    getAlmanac.mockResolvedValue(null);

    const { result } = renderHook(() => useComingUpFeed(true, TODAY));
    await flush();

    expect(result.current.events).toEqual({ entries: [] });
  });

  it('refetches silently when the polled warning set changes: no loading state, the new feed replaces the old', async () => {
    const first = { entries: [{ id: 'a' }] };
    const second = { entries: [{ id: 'a' }, { id: 'weather-warning:w1' }] };
    getAlmanac.mockResolvedValueOnce(first).mockResolvedValueOnce(second);

    const { result, rerender } = renderHook(({ key }) => useComingUpFeed(true, TODAY, key), {
      initialProps: { key: '' },
    });
    await flush();
    expect(result.current.events).toEqual(first);

    rerender({ key: 'w1@1.0@AMBER' });
    // Never goes back to loading: the pane keeps showing the feed while the refetch is in flight.
    expect(result.current.status).toBe('ready');
    await flush();

    expect(getAlmanac).toHaveBeenCalledTimes(2);
    expect(result.current.status).toBe('ready');
    expect(result.current.events).toEqual(second);
  });

  it('does not refetch when the warning key is unchanged, or on a re-render with the same key', async () => {
    getAlmanac.mockResolvedValue({ entries: [] });

    const { rerender } = renderHook(({ key }) => useComingUpFeed(true, TODAY, key), { initialProps: { key: 'k' } });
    await flush();
    rerender({ key: 'k' });
    await flush();

    expect(getAlmanac).toHaveBeenCalledTimes(1);
  });

  it('a failed silent refetch keeps the feed on screen instead of blanking it with an error', async () => {
    const first = { entries: [{ id: 'a' }] };
    getAlmanac.mockResolvedValueOnce(first).mockRejectedValueOnce(new Error('offline'));

    const { result, rerender } = renderHook(({ key }) => useComingUpFeed(true, TODAY, key), {
      initialProps: { key: '' },
    });
    await flush();
    rerender({ key: 'w1@1.0@AMBER' });
    await flush();

    expect(result.current.status).toBe('ready');
    expect(result.current.events).toEqual(first);
  });

  it('a silent refetch that fails before the first success still reports the error', async () => {
    getAlmanac.mockRejectedValueOnce(new Error('offline')).mockRejectedValueOnce(new Error('offline'));

    const { result, rerender } = renderHook(({ key }) => useComingUpFeed(true, TODAY, key), {
      initialProps: { key: '' },
    });
    await flush();
    expect(result.current.status).toBe('error');
    rerender({ key: 'w1@1.0@AMBER' });
    await flush();

    expect(result.current.status).toBe('error');
  });

  it('a failed silent refetch is retried after the backoff even though the key never changes again', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      const first = { entries: [{ id: 'a' }] };
      const second = { entries: [{ id: 'a' }, { id: 'weather-warning:w1' }] };
      getAlmanac.mockResolvedValueOnce(first).mockRejectedValueOnce(new Error('offline'))
        .mockResolvedValueOnce(second);

      const { result, rerender } = renderHook(({ key }) => useComingUpFeed(true, TODAY, key), {
        initialProps: { key: '' },
      });
      await flush();
      rerender({ key: 'w1@1.0@AMBER@' });
      await flush();
      expect(result.current.events).toEqual(first);
      expect(getAlmanac).toHaveBeenCalledTimes(2);

      await act(async () => { vi.advanceTimersByTime(SILENT_RETRY_MS); });
      await flush();

      expect(getAlmanac).toHaveBeenCalledTimes(3);
      expect(result.current.events).toEqual(second);
      expect(result.current.status).toBe('ready');
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps retrying while failing and stops once it succeeds', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      getAlmanac.mockResolvedValueOnce({ entries: [] }).mockRejectedValue(new Error('offline'));
      const { rerender } = renderHook(({ key }) => useComingUpFeed(true, TODAY, key), { initialProps: { key: '' } });
      await flush();
      rerender({ key: 'k' });
      await flush();
      await act(async () => { vi.advanceTimersByTime(SILENT_RETRY_MS); });
      await flush();
      await act(async () => { vi.advanceTimersByTime(SILENT_RETRY_MS); });
      await flush();

      expect(getAlmanac).toHaveBeenCalledTimes(4);
    } finally {
      vi.useRealTimers();
    }
  });

  it('a first fetch superseded by a failing silent one does not show a hard error, and recovers on the retry', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      let releaseFirst;
      getAlmanac.mockImplementationOnce(() => new Promise((resolve) => { releaseFirst = resolve; }))
        .mockRejectedValueOnce(new Error('offline'))
        .mockResolvedValueOnce({ entries: [{ id: 'z' }] });
      const { result, rerender } = renderHook(({ key }) => useComingUpFeed(true, TODAY, key), {
        initialProps: { key: '' },
      });
      await flush();
      rerender({ key: 'k' });
      await flush();
      releaseFirst({ entries: [] });
      await flush();
      // The first answer was discarded as superseded; the silent one failed: still loading, not errored.
      expect(result.current.status).toBe('loading');

      await act(async () => { vi.advanceTimersByTime(SILENT_RETRY_MS); });
      await flush();

      expect(result.current.status).toBe('ready');
      expect(result.current.events).toEqual({ entries: [{ id: 'z' }] });
    } finally {
      vi.useRealTimers();
    }
  });

  it('a new day still shows the loading state, with or without a warning change', async () => {
    getAlmanac.mockResolvedValue({ entries: [] });
    const { result, rerender } = renderHook(({ day }) => useComingUpFeed(true, day, 'k'), {
      initialProps: { day: TODAY },
    });
    await flush();

    rerender({ day: '2026-08-29' });
    expect(result.current.status).toBe('loading');
    await flush();

    expect(getAlmanac).toHaveBeenCalledTimes(2);
    expect(result.current.status).toBe('ready');
  });

  it('does not fetch while disabled', () => {
    renderHook(() => useComingUpFeed(false, TODAY));
    expect(getAlmanac).not.toHaveBeenCalled();
  });
});
