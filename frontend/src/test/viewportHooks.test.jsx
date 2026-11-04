import React from 'react';
import { renderToString } from 'react-dom/server';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useElementWidth from '../hooks/useElementWidth.js';
import useStickyViewportFill from '../hooks/useStickyViewportFill.js';
import { useIsCoarsePointer } from '../hooks/useIsCoarsePointer.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { useIsWide } from '../hooks/useIsWide.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** A ResizeObserver double that records what it watches and lets a test fire it. */
function stubResizeObserver() {
  const instances = [];
  class FakeResizeObserver {
    constructor(callback) {
      this.callback = callback;
      this.observed = [];
      this.disconnected = false;
      instances.push(this);
    }
    observe(node) {
      this.observed.push(node);
    }
    disconnect() {
      this.disconnected = true;
    }
  }
  vi.stubGlobal('ResizeObserver', FakeResizeObserver);
  return instances;
}

describe('useElementWidth', () => {
  it('reports the minimum width when there is no element yet', () => {
    const ref = { current: null };
    const { result } = renderHook(() => useElementWidth(ref, 120));
    expect(result.current).toBe(120);
  });

  it('measures the element once, floored, and never goes below the minimum', () => {
    stubResizeObserver();
    const wideRef = { current: { clientWidth: 400.9 } };
    const wide = renderHook(() => useElementWidth(wideRef, 120));
    expect(wide.result.current).toBe(400);

    const narrowRef = { current: { clientWidth: 80 } };
    const narrow = renderHook(() => useElementWidth(narrowRef, 120));
    expect(narrow.result.current).toBe(120);
  });

  it('follows the observer using the entry content width, and observes the element', () => {
    const instances = stubResizeObserver();
    const node = { clientWidth: 300 };
    const ref = { current: node };
    const { result } = renderHook(() => useElementWidth(ref, 100));
    expect(instances).toHaveLength(1);
    expect(instances[0].observed).toEqual([node]);

    act(() => {
      instances[0].callback([{ contentRect: { width: 250.7 } }]);
    });
    expect(result.current).toBe(250);
  });

  it('uses only the last entry when the observer batches several', () => {
    const instances = stubResizeObserver();
    const ref = { current: { clientWidth: 300 } };
    const { result } = renderHook(() => useElementWidth(ref, 100));

    act(() => {
      instances[0].callback([{ contentRect: { width: 500 } }, { contentRect: { width: 220 } }]);
    });
    expect(result.current).toBe(220);
  });

  it('falls back to clientWidth when an entry carries no content rect', () => {
    const instances = stubResizeObserver();
    const node = { clientWidth: 300 };
    const ref = { current: node };
    const { result } = renderHook(() => useElementWidth(ref, 100));
    node.clientWidth = 340;

    act(() => {
      instances[0].callback([{}]);
    });
    expect(result.current).toBe(340);

    node.clientWidth = 360;
    act(() => {
      instances[0].callback([]);
    });
    expect(result.current).toBe(360);
  });

  it('disconnects the observer on unmount', () => {
    const instances = stubResizeObserver();
    const ref = { current: { clientWidth: 300 } };
    const { unmount } = renderHook(() => useElementWidth(ref, 100));
    expect(instances).toHaveLength(1);
    expect(instances[0].disconnected).toBe(false);
    unmount();
    expect(instances[0].disconnected).toBe(true);
  });

  it('still takes the one measurement where ResizeObserver does not exist', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    const ref = { current: { clientWidth: 321 } };
    const { result, unmount } = renderHook(() => useElementWidth(ref, 100));
    expect(result.current).toBe(321);
    expect(() => unmount()).not.toThrow();
  });

  it('raises a larger minimum over a smaller held width', () => {
    stubResizeObserver();
    const ref = { current: { clientWidth: 150 } };
    const { result, rerender } = renderHook(({ min }) => useElementWidth(ref, min), { initialProps: { min: 100 } });
    expect(result.current).toBe(150);
    rerender({ min: 200 });
    expect(result.current).toBe(200);
  });
});

describe('useStickyViewportFill', () => {
  let rafCallbacks;

  beforeEach(() => {
    rafCallbacks = [];
    vi.stubGlobal('requestAnimationFrame', (cb) => {
      rafCallbacks.push(cb);
      return rafCallbacks.length;
    });
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
  });

  const makeNode = (initialTop) => {
    const node = document.createElement('div');
    let top = initialTop;
    node.getBoundingClientRect = () => ({ top });
    return { node, setTop: (value) => { top = value; } };
  };

  const flushFrame = () => {
    const callbacks = rafCallbacks;
    rafCallbacks = [];
    act(() => callbacks.forEach((cb) => cb()));
  };

  it('publishes the dock top, rounded, as --wf-dock-top straight away', () => {
    stubResizeObserver();
    const { node } = makeNode(96.6);
    const ref = { current: node };
    renderHook(() => useStickyViewportFill(ref, true));
    expect(node.style.getPropertyValue('--wf-dock-top')).toBe('97px');
  });

  it('never publishes a negative top once the dock has stuck', () => {
    stubResizeObserver();
    const { node } = makeNode(-300);
    const ref = { current: node };
    renderHook(() => useStickyViewportFill(ref, true));
    expect(node.style.getPropertyValue('--wf-dock-top')).toBe('0px');
  });

  it('does nothing when disabled or when there is no node', () => {
    const { node } = makeNode(50);
    const ref = { current: node };
    renderHook(() => useStickyViewportFill(ref, false));
    expect(node.style.getPropertyValue('--wf-dock-top')).toBe('');
    expect(() => renderHook(() => useStickyViewportFill({ current: null }, true))).not.toThrow();
  });

  it('re-measures on scroll and resize, coalescing repeated events into one frame', () => {
    stubResizeObserver();
    const { node, setTop } = makeNode(80);
    const ref = { current: node };
    renderHook(() => useStickyViewportFill(ref, true));

    setTop(40);
    window.dispatchEvent(new Event('scroll'));
    window.dispatchEvent(new Event('scroll'));
    window.dispatchEvent(new Event('resize'));
    expect(rafCallbacks).toHaveLength(1);
    flushFrame();
    expect(node.style.getPropertyValue('--wf-dock-top')).toBe('40px');

    setTop(10);
    window.dispatchEvent(new Event('resize'));
    flushFrame();
    expect(node.style.getPropertyValue('--wf-dock-top')).toBe('10px');
  });

  it('re-measures when the body resizes (a banner arriving above the shell)', () => {
    const instances = stubResizeObserver();
    const { node, setTop } = makeNode(0);
    const ref = { current: node };
    renderHook(() => useStickyViewportFill(ref, true));
    expect(instances[0].observed).toEqual([document.body]);

    setTop(48);
    act(() => instances[0].callback());
    flushFrame();
    expect(node.style.getPropertyValue('--wf-dock-top')).toBe('48px');
  });

  it('works without ResizeObserver', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    const { node } = makeNode(33);
    const ref = { current: node };
    const { unmount } = renderHook(() => useStickyViewportFill(ref, true));
    expect(node.style.getPropertyValue('--wf-dock-top')).toBe('33px');
    expect(() => unmount()).not.toThrow();
  });

  it('on unmount removes the property and listeners, disconnects, and cancels a pending frame', () => {
    const instances = stubResizeObserver();
    const { node, setTop } = makeNode(60);
    const ref = { current: node };
    const { unmount } = renderHook(() => useStickyViewportFill(ref, true));
    window.dispatchEvent(new Event('scroll'));
    expect(rafCallbacks).toHaveLength(1);

    unmount();

    expect(node.style.getPropertyValue('--wf-dock-top')).toBe('');
    expect(globalThis.cancelAnimationFrame).toHaveBeenCalledWith(1);
    expect(instances[0].disconnected).toBe(true);

    setTop(5);
    rafCallbacks = [];
    window.dispatchEvent(new Event('scroll'));
    expect(rafCallbacks).toHaveLength(0);
  });

  it('does not cancel a frame on unmount when none is pending', () => {
    stubResizeObserver();
    const { node } = makeNode(60);
    const ref = { current: node };
    const { unmount } = renderHook(() => useStickyViewportFill(ref, true));
    unmount();
    expect(globalThis.cancelAnimationFrame).not.toHaveBeenCalled();
  });
});

describe('media-query hooks', () => {
  let listeners;
  let matches;

  beforeEach(() => {
    listeners = [];
    matches = false;
    vi.stubGlobal(
      'matchMedia',
      vi.fn((query) => ({
        matches,
        media: query,
        addEventListener: (_e, handler) => listeners.push(handler),
        removeEventListener: (_e, handler) => {
          listeners = listeners.filter((h) => h !== handler);
        },
      })),
    );
  });

  describe('useIsCoarsePointer', () => {
    it('asks for "(pointer: coarse)" and reports its match', () => {
      matches = true;
      const { result } = renderHook(() => useIsCoarsePointer());
      expect(globalThis.matchMedia).toHaveBeenCalledWith('(pointer: coarse)');
      expect(result.current).toBe(true);
    });

    it('is false for a fine pointer and follows a change event, then stops listening on unmount', () => {
      const { result, unmount } = renderHook(() => useIsCoarsePointer());
      expect(result.current).toBe(false);

      act(() => listeners.forEach((h) => h({ matches: true })));
      expect(result.current).toBe(true);

      unmount();
      expect(listeners).toHaveLength(0);
    });

    it('reads as not coarse where matchMedia is not implemented, leaving hover on', () => {
      vi.stubGlobal('matchMedia', undefined);
      const { result } = renderHook(() => useIsCoarsePointer());
      expect(result.current).toBe(false);
    });

    it('tolerates a media query list with no event API (older engines)', () => {
      vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })));
      const { result, unmount } = renderHook(() => useIsCoarsePointer());
      expect(result.current).toBe(true);
      expect(() => unmount()).not.toThrow();
    });

    it('answers false when rendered with no window at all (server render)', () => {
      const Probe = () => <span>{String(useIsCoarsePointer())}</span>;
      const realWindow = globalThis.window;
      vi.stubGlobal('window', undefined);
      let html;
      try {
        html = renderToString(<Probe />);
      } finally {
        vi.stubGlobal('window', realWindow);
      }
      expect(html).toContain('false');
    });
  });

  describe.each([
    ['useIsMobile', useIsMobile],
    ['useIsWide', useIsWide],
  ])('%s', (_name, hook) => {
    it('answers false when rendered with no window at all (server render)', () => {
      matches = true; // would be true if the guard did not short-circuit before matchMedia
      const Probe = () => <span>{String(hook())}</span>;
      const realWindow = globalThis.window;
      vi.stubGlobal('window', undefined);
      let html;
      try {
        html = renderToString(<Probe />);
      } finally {
        vi.stubGlobal('window', realWindow);
      }
      expect(html).toContain('false');
    });
  });
});
