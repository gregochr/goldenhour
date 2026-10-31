import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useIsWide } from '../hooks/useIsWide.js';

describe('useIsWide', () => {
  let listeners;
  let currentMatches;

  beforeEach(() => {
    listeners = [];
    currentMatches = false;

    vi.stubGlobal('matchMedia', vi.fn((query) => ({
      matches: currentMatches,
      media: query,
      addEventListener: (_event, handler) => { listeners.push(handler); },
      removeEventListener: (_event, handler) => {
        listeners = listeners.filter((h) => h !== handler);
      },
    })));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // The Operations sidebar switch rides this query; the rest of this file stubs `matchMedia`
  // and cannot see it.
  it('asks for exactly "(min-width: 1280px)"', () => {
    renderHook(() => useIsWide());
    expect(globalThis.matchMedia).toHaveBeenCalledWith('(min-width: 1280px)');
  });

  it('returns true when viewport is wide', () => {
    currentMatches = true;
    const { result } = renderHook(() => useIsWide());
    expect(result.current).toBe(true);
  });

  it('returns false when viewport is narrow', () => {
    currentMatches = false;
    const { result } = renderHook(() => useIsWide());
    expect(result.current).toBe(false);
  });

  it('updates when media query changes', () => {
    currentMatches = false;
    const { result } = renderHook(() => useIsWide());
    expect(result.current).toBe(false);

    act(() => {
      listeners.forEach((h) => h({ matches: true }));
    });
    expect(result.current).toBe(true);

    act(() => {
      listeners.forEach((h) => h({ matches: false }));
    });
    expect(result.current).toBe(false);
  });

  it('cleans up listener on unmount', () => {
    currentMatches = false;
    const { unmount } = renderHook(() => useIsWide());
    expect(listeners.length).toBe(1);
    unmount();
    expect(listeners.length).toBe(0);
  });
});
