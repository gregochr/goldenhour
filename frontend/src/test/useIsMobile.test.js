import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useIsMobile } from '../hooks/useIsMobile.js';

describe('useIsMobile', () => {
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

  it('returns true when viewport is mobile-sized', () => {
    currentMatches = true;
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(true);
  });

  it('returns false when viewport is desktop-sized', () => {
    currentMatches = false;
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);
  });

  it('updates when media query changes', () => {
    currentMatches = false;
    const { result } = renderHook(() => useIsMobile());
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
    const { unmount } = renderHook(() => useIsMobile());
    expect(listeners.length).toBe(1);
    unmount();
    expect(listeners.length).toBe(0);
  });
});
