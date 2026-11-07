import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useSpotPeek from '../hooks/useSpotPeek.js';

const OPEN_MS = 180;

function anchor() {
  const el = document.createElement('button');
  document.body.appendChild(el);
  return el;
}

describe('useSpotPeek — edges', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  it('opens nothing for a missing anchor', () => {
    const { result } = renderHook(() => useSpotPeek());

    act(() => result.current.open(null, null, { title: 'x' }));
    act(() => { vi.advanceTimersByTime(1000); });

    expect(result.current.peek).toBeNull();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('places the panel without a bounds element and carries the content onto the peek', () => {
    const { result } = renderHook(() => useSpotPeek());
    const a = anchor();

    act(() => result.current.open(a, null, { title: 'Bamburgh' }));
    expect(result.current.peek).toBeNull();
    act(() => { vi.advanceTimersByTime(OPEN_MS); });

    expect(result.current.peek).toMatchObject({ title: 'Bamburgh' });
  });

  it('keeps the panel up when the pointer re-enters the same card, but takes it down for a different one', () => {
    const { result } = renderHook(() => useSpotPeek());
    const a = anchor();
    const b = anchor();
    act(() => result.current.open(a, null, { title: 'A' }));
    act(() => { vi.advanceTimersByTime(OPEN_MS); });
    expect(result.current.peek).toMatchObject({ title: 'A' });

    act(() => result.current.open(a, null, { title: 'A2' }));
    expect(result.current.peek).toMatchObject({ title: 'A' });

    act(() => result.current.open(b, null, { title: 'B' }));
    expect(result.current.peek).toBeNull();
    act(() => { vi.advanceTimersByTime(OPEN_MS); });
    expect(result.current.peek).toMatchObject({ title: 'B' });
  });

  it('closes on Escape but not on any other key', () => {
    const { result } = renderHook(() => useSpotPeek());
    act(() => result.current.open(anchor(), null, { title: 'A' }));
    act(() => { vi.advanceTimersByTime(OPEN_MS); });

    act(() => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); });
    expect(result.current.peek).not.toBeNull();

    act(() => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); });
    expect(result.current.peek).toBeNull();
  });
});
