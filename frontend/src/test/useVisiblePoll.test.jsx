import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useVisiblePoll } from '../hooks/useVisiblePoll.js';
import { setRewind } from '../utils/rewind.js';

const INTERVAL = 10 * 60 * 1000;
const GAP = 60 * 1000;

function setVisibility(state) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  document.dispatchEvent(new Event('visibilitychange'));
}

function focus() {
  window.dispatchEvent(new Event('focus'));
}

describe('useVisiblePoll', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    vi.setSystemTime(new Date('2026-10-29T12:00:00Z'));
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
  });

  afterEach(() => {
    vi.useRealTimers();
    delete document.visibilityState;
  });

  it('fetches on mount and then on every interval while visible', () => {
    const fetchFn = vi.fn();
    renderHook(() => useVisiblePoll(fetchFn, INTERVAL));
    expect(fetchFn).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(INTERVAL); });
    expect(fetchFn).toHaveBeenCalledTimes(2);
  });

  it('stops polling while the page is hidden', () => {
    const fetchFn = vi.fn();
    renderHook(() => useVisiblePoll(fetchFn, INTERVAL));
    act(() => { setVisibility('hidden'); });
    act(() => { vi.advanceTimersByTime(INTERVAL * 3); });
    expect(fetchFn).toHaveBeenCalledTimes(1);
  });

  it('refetches on becoming visible once the gap has passed, and restarts the interval', () => {
    const fetchFn = vi.fn();
    renderHook(() => useVisiblePoll(fetchFn, INTERVAL));
    act(() => { setVisibility('hidden'); });
    act(() => { vi.setSystemTime(Date.now() + GAP + 1); });
    act(() => { setVisibility('visible'); });
    expect(fetchFn).toHaveBeenCalledTimes(2);
    act(() => { vi.advanceTimersByTime(INTERVAL); });
    expect(fetchFn).toHaveBeenCalledTimes(3);
  });

  it('does not refetch on becoming visible within the gap, but still restarts the interval', () => {
    const fetchFn = vi.fn();
    renderHook(() => useVisiblePoll(fetchFn, INTERVAL));
    act(() => { setVisibility('hidden'); });
    act(() => { vi.setSystemTime(Date.now() + GAP - 1); });
    act(() => { setVisibility('visible'); });
    expect(fetchFn).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(INTERVAL); });
    expect(fetchFn).toHaveBeenCalledTimes(2);
  });

  it('follows the same gap rule for window focus', () => {
    const fetchFn = vi.fn();
    renderHook(() => useVisiblePoll(fetchFn, INTERVAL));
    act(() => { focus(); });
    expect(fetchFn).toHaveBeenCalledTimes(1);
    act(() => { vi.setSystemTime(Date.now() + GAP); });
    act(() => { focus(); });
    expect(fetchFn).toHaveBeenCalledTimes(2);
    act(() => { focus(); }); // the fetch above reset the gap
    expect(fetchFn).toHaveBeenCalledTimes(2);
  });

  it('ignores a focus while hidden', () => {
    const fetchFn = vi.fn();
    renderHook(() => useVisiblePoll(fetchFn, INTERVAL));
    act(() => { setVisibility('hidden'); });
    act(() => { vi.setSystemTime(Date.now() + GAP * 5); });
    act(() => { focus(); });
    expect(fetchFn).toHaveBeenCalledTimes(1);
  });

  it('honours a custom minGapMs', () => {
    const fetchFn = vi.fn();
    renderHook(() => useVisiblePoll(fetchFn, INTERVAL, { minGapMs: 5000 }));
    act(() => { vi.setSystemTime(Date.now() + 5000); });
    act(() => { focus(); });
    expect(fetchFn).toHaveBeenCalledTimes(2);
  });

  it('fetches at once when fetchFn changes identity, ignoring the gap', () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(({ fn }) => useVisiblePoll(fn, INTERVAL), { initialProps: { fn: first } });
    expect(first).toHaveBeenCalledTimes(1);
    rerender({ fn: second });
    expect(second).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(INTERVAL); });
    expect(first).toHaveBeenCalledTimes(1);
    expect(second).toHaveBeenCalledTimes(2);
  });

  it('fetches at once when enabled turns on, and does nothing while it is off', () => {
    const fetchFn = vi.fn();
    const { rerender } = renderHook(({ on }) => useVisiblePoll(fetchFn, INTERVAL, { enabled: on }), {
      initialProps: { on: false },
    });
    act(() => { focus(); vi.advanceTimersByTime(INTERVAL * 2); });
    expect(fetchFn).not.toHaveBeenCalled();
    rerender({ on: true });
    expect(fetchFn).toHaveBeenCalledTimes(1);
    rerender({ on: false });
    act(() => { vi.advanceTimersByTime(INTERVAL); });
    expect(fetchFn).toHaveBeenCalledTimes(1);
  });

  it('measures the gap with the wall clock, so a Rewind does not stop refetches', () => {
    setRewind('2026-01-01T12:00:00Z');
    try {
      const fetchFn = vi.fn();
      renderHook(() => useVisiblePoll(fetchFn, INTERVAL));
      act(() => { vi.setSystemTime(Date.now() + GAP + 1); });
      act(() => { focus(); });
      expect(fetchFn).toHaveBeenCalledTimes(2);
    } finally {
      setRewind(null);
    }
  });

  it('removes its listeners and interval on unmount', () => {
    const fetchFn = vi.fn();
    const { unmount } = renderHook(() => useVisiblePoll(fetchFn, INTERVAL));
    unmount();
    act(() => { vi.setSystemTime(Date.now() + GAP * 5); focus(); vi.advanceTimersByTime(INTERVAL); });
    expect(fetchFn).toHaveBeenCalledTimes(1);
  });
});
