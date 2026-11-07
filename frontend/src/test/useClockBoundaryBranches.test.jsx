import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useClockBoundary } from '../hooks/useClockBoundary.js';

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

function setVisibility(state) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
  vi.setSystemTime(T0);
});

afterEach(() => {
  delete document.visibilityState;
  vi.useRealTimers();
});

describe('useClockBoundary — visibility edges', () => {
  it('ignores a visibility change while the tab is hidden, even past the boundary', async () => {
    const { renders } = probe(AT);
    const first = renders();
    setVisibility('hidden');
    vi.setSystemTime(AT + 1000);

    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });

    expect(renders()).toBe(first);
  });

  it('re-asks the clock on a later visibility change after the boundary has already flipped, with no timer left over', async () => {
    const { renders } = probe(AT);
    const first = renders();
    await act(async () => { vi.advanceTimersByTime(10 * 60 * 1000 + 100); });
    expect(renders()).toBe(first + 1);
    expect(vi.getTimerCount()).toBe(0);
    setVisibility('visible');

    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });

    expect(renders()).toBe(first + 2);
    expect(vi.getTimerCount()).toBe(0);
  });
});
