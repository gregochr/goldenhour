import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useComingUpFeed from '../hooks/useComingUpFeed.js';

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

  it('does not fetch while disabled', () => {
    renderHook(() => useComingUpFeed(false, TODAY));
    expect(getAlmanac).not.toHaveBeenCalled();
  });
});
