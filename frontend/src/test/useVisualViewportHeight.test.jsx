import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import useVisualViewportHeight from '../hooks/useVisualViewportHeight.js';

/**
 * The visual-viewport hook the tall Ask sheet sizes itself from, with `window.visualViewport`
 * present, absent (jsdom, and any engine without it) and ignored (`enabled` false).
 *
 * <p>Rendered through a probe that prints the hook's two figures: `BottomSheet`'s own tests
 * (`BottomSheetTall.test.jsx`) cover the wiring, and the hook's maths and subscriptions are easiest to
 * pin with the numbers in front of you.
 */

function Probe({ enabled = true }) {
  const { height, bottomInset } = useVisualViewportHeight(enabled);
  return <output data-testid="vv">{`${height}|${bottomInset}`}</output>;
}

const figures = () => screen.getByTestId('vv').textContent;

/** A stand-in `visualViewport`: an event target with the two numbers the hook reads. */
function stubViewport(height, offsetTop = 0) {
  const viewport = Object.assign(new EventTarget(), { height, offsetTop });
  window.visualViewport = viewport;
  return viewport;
}

afterEach(() => {
  delete window.visualViewport;
  window.innerHeight = 768;
});

describe('useVisualViewportHeight — without a visualViewport', () => {
  it('falls back to the layout viewport, with no inset', () => {
    window.innerHeight = 800;
    render(<Probe />);
    expect(figures()).toBe('800|0');
  });

  it('follows a window resize', () => {
    window.innerHeight = 800;
    render(<Probe />);
    act(() => {
      window.innerHeight = 600;
      window.dispatchEvent(new Event('resize'));
    });
    expect(figures()).toBe('600|0');
  });
});

describe('useVisualViewportHeight — with a visualViewport', () => {
  it('reports the visible height, and no inset while the keyboard is down', () => {
    window.innerHeight = 844;
    stubViewport(844);
    render(<Probe />);
    expect(figures()).toBe('844|0');
  });

  it('shortens by the keyboard and reports the gap beneath it as the inset', () => {
    window.innerHeight = 844;
    const viewport = stubViewport(844);
    render(<Probe />);
    act(() => {
      viewport.height = 508;
      viewport.dispatchEvent(new Event('resize'));
    });
    expect(figures()).toBe('508|336');
  });

  it('counts the page panning inside the visual viewport (offsetTop) out of the inset', () => {
    window.innerHeight = 844;
    const viewport = stubViewport(508);
    render(<Probe />);
    act(() => {
      viewport.offsetTop = 100;
      viewport.dispatchEvent(new Event('scroll'));
    });
    expect(figures()).toBe('508|236');
  });

  it('clamps a negative inset to zero', () => {
    window.innerHeight = 700;
    stubViewport(800);
    render(<Probe />);
    expect(figures()).toBe('800|0');
  });

  it('rounds, so sub-pixel jitter is not a change', () => {
    window.innerHeight = 844;
    const viewport = stubViewport(508.4);
    render(<Probe />);
    expect(figures()).toBe('508|336');
    act(() => {
      viewport.height = 508.2;
      viewport.dispatchEvent(new Event('resize'));
    });
    expect(figures()).toBe('508|336');
  });

  it('follows an orientation change, which resizes the WINDOW as well as the viewport', () => {
    window.innerHeight = 844;
    stubViewport(844);
    render(<Probe />);
    act(() => {
      window.innerHeight = 390;
      window.visualViewport.height = 390;
      window.dispatchEvent(new Event('resize'));
    });
    expect(figures()).toBe('390|0');
  });

  it('takes every listener off on unmount, the same functions it put on', () => {
    window.innerHeight = 844;
    const viewport = stubViewport(844);
    const added = vi.spyOn(viewport, 'addEventListener');
    const removed = vi.spyOn(viewport, 'removeEventListener');
    const windowAdded = vi.spyOn(window, 'addEventListener');
    const windowRemoved = vi.spyOn(window, 'removeEventListener');
    const { unmount } = render(<Probe />);
    unmount();

    const pairs = (spy) => spy.mock.calls.map(([type, fn]) => [type, fn]);
    expect(pairs(removed)).toEqual(expect.arrayContaining(pairs(added)));
    expect(added.mock.calls.map(([type]) => type).sort()).toEqual(['resize', 'scroll']);
    const windowResize = (spy) => spy.mock.calls.filter(([type]) => type === 'resize').map(([, fn]) => fn);
    expect(windowResize(windowRemoved)).toEqual(expect.arrayContaining(windowResize(windowAdded)));
    expect(windowResize(windowAdded).length).toBeGreaterThan(0);
  });
});

describe('useVisualViewportHeight — disabled', () => {
  it('subscribes to nothing, so a sheet that does not want it pays nothing', () => {
    const viewport = stubViewport(300);
    const added = vi.spyOn(viewport, 'addEventListener');
    render(<Probe enabled={false} />);
    expect(added).not.toHaveBeenCalled();
  });

  it('ignores the visual viewport and its events entirely', () => {
    window.innerHeight = 844;
    const viewport = stubViewport(300);
    render(<Probe enabled={false} />);
    expect(figures()).toBe('844|0');
    act(() => {
      viewport.height = 200;
      viewport.dispatchEvent(new Event('resize'));
    });
    expect(figures()).toBe('844|0');
  });
});
