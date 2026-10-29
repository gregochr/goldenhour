import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import useChipTipPosition, { TOOLTIP_WIDTH_FALLBACK } from '../hooks/useChipTipPosition.js';

const map = {
  getContainer: () => ({
    getBoundingClientRect: () => ({
      left: 10, top: 20, width: 800, height: 500,
    }),
  }),
};

describe('useChipTipPosition', () => {
  let frames;
  beforeEach(() => {
    frames = [];
    vi.stubGlobal('requestAnimationFrame', (cb) => { frames.push(cb); return frames.length; });
    vi.stubGlobal('cancelAnimationFrame', (id) => { frames[id - 1] = null; });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('clamps against the 240px fallback when the node reports no width', () => {
    const { result } = renderHook(() => useChipTipPosition(map));
    const tip = document.createElement('div');
    result.current.place({ clientX: 790, clientY: 5 });
    result.current.attach(tip);
    // x: min(790 - 10 + 13, 800 - 240 - 8) = 552; y: max(6, 5 - 20 - 10) = 6.
    expect(TOOLTIP_WIDTH_FALLBACK).toBe(240);
    expect(tip.style.transform).toBe('translate(552px, 6px)');
  });

  it('cancels a pending frame when placed directly, and on unmount', () => {
    const { result, unmount } = renderHook(() => useChipTipPosition(map));
    const tip = document.createElement('div');
    result.current.attach(tip);
    result.current.move({ clientX: 100, clientY: 100 });
    expect(frames.filter(Boolean)).toHaveLength(1);
    result.current.place({ clientX: 200, clientY: 100 });
    expect(frames.filter(Boolean)).toHaveLength(0);
    expect(tip.style.transform).toBe('translate(203px, 70px)');
    result.current.move({ clientX: 300, clientY: 100 });
    unmount();
    expect(frames.filter(Boolean)).toHaveLength(0);
  });
});
