/**
 * `hooks/useClockBoundary.js` (performance-review-2026-10 P4e): re-renders once the app clock passes a
 * boundary, in short slices, surviving sleep and hidden tabs, and never under a Rewind.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useClockBoundary } from '../hooks/useClockBoundary.js';
import { setRewind } from '../utils/rewind.js';

const T0 = Date.parse('2026-06-15T10:00:00Z');
const AT = T0 + 10 * 60 * 1000;

function probe(at) {
  let renders = 0;
  const hook = renderHook(() => {
    renders += 1;
    useClockBoundary(at);
  });
  return { hook, renders: () => renders };
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
  vi.setSystemTime(T0);
});
afterEach(() => {
  setRewind(null);
  vi.useRealTimers();
});

describe('useClockBoundary', () => {
  it('waits in slices without re-rendering, then re-renders once at the boundary', async () => {
    const { renders } = probe(AT);
    const first = renders();
    await act(async () => { vi.advanceTimersByTime(9 * 60 * 1000); });
    expect(renders()).toBe(first);
    await act(async () => { vi.advanceTimersByTime(60 * 1000 + 100); });
    expect(renders()).toBe(first + 1);
    await act(async () => { vi.advanceTimersByTime(60 * 60 * 1000); });
    expect(renders()).toBe(first + 1);
  });

  it('a slept machine (clock jumps, no timer fired) flips on the next slice', async () => {
    const { renders } = probe(AT);
    const first = renders();
    vi.setSystemTime(AT + 5 * 60 * 1000);
    await act(async () => { vi.advanceTimersByTime(30000); });
    expect(renders()).toBe(first + 1);
  });

  it('a tab becoming visible past the boundary flips at once, and before it does not', async () => {
    const { renders } = probe(AT);
    const first = renders();
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    try {
      await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
      expect(renders()).toBe(first);
      vi.setSystemTime(AT + 1000);
      await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
      expect(renders()).toBe(first + 1);
    } finally {
      delete document.visibilityState;
    }
  });

  it('arms nothing under a Rewind, for a null boundary or for one already passed', () => {
    const before = vi.getTimerCount();
    setRewind('2026-06-14T21:00:00Z');
    probe(AT);
    setRewind(null);
    probe(null);
    probe(T0 - 1000);
    expect(vi.getTimerCount()).toBe(before);
  });

  it('clears its timer on unmount', () => {
    const before = vi.getTimerCount();
    const { hook } = probe(AT);
    expect(vi.getTimerCount()).toBe(before + 1);
    hook.unmount();
    expect(vi.getTimerCount()).toBe(before);
  });
});
