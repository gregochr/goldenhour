import React from 'react';
import {
  describe, it, expect, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { useChromeBlHeight } from '../hooks/useChromeBlHeight.js';
import { useChromeTlHeight } from '../hooks/useChromeTlHeight.js';

/**
 * The hook that publishes the bottom-left chrome stack's height (hot-topics-by-day-plan §3 M2). It
 * shares its mechanism with `useChromeTlHeight`, whose own tests cover that mechanism at length; this
 * file pins what is specific to the bottom-left twin: its variable, its independence from the
 * top-left one, and the nonce the label layers repaint on.
 */
function Frame({ show = true }) {
  const [blRef, blNonce] = useChromeBlHeight();
  const [tlRef, tlNonce] = useChromeTlHeight();
  return (
    <div data-testid="frame">
      <div data-testid="tl" ref={tlRef}>tl</div>
      {show && <div data-testid="bl" ref={blRef}>bl</div>}
      <output data-testid="bl-nonce">{blNonce}</output>
      <output data-testid="tl-nonce">{tlNonce}</output>
    </div>
  );
}

describe('useChromeBlHeight', () => {
  const OriginalRO = globalThis.ResizeObserver;
  let observers;
  let heights;
  let original;

  beforeEach(() => {
    observers = [];
    heights = { bl: 190, tl: 60 };
    globalThis.ResizeObserver = class {
      constructor(cb) { this.cb = cb; observers.push(this); }

      observe() {}

      disconnect() { this.disconnected = true; }
    };
    original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() { return heights[this.dataset?.testid] ?? 0; },
    });
  });

  afterEach(() => {
    globalThis.ResizeObserver = OriginalRO;
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  });

  it('publishes --wf-chrome-bl-h on the frame, follows resizes, and clears on unmount', () => {
    const { unmount } = render(<Frame />);
    const frame = screen.getByTestId('frame');
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('190px');
    heights.bl = 64;
    act(() => { observers.forEach((o) => o.cb()); });
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('64px');
    unmount();
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('');
  });

  it('keeps the two variables apart: each hook writes only its own', () => {
    render(<Frame />);
    const frame = screen.getByTestId('frame');
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('60px');
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('190px');
  });

  it('moves its nonce when the height changes, and only then; the top-left nonce stays put', () => {
    render(<Frame />);
    expect(screen.getByTestId('bl-nonce')).toHaveTextContent('0');
    act(() => { observers.forEach((o) => o.cb()); });
    expect(screen.getByTestId('bl-nonce')).toHaveTextContent('0');
    heights.bl = 64;
    act(() => { observers.forEach((o) => o.cb()); });
    expect(screen.getByTestId('bl-nonce')).toHaveTextContent('1');
    expect(screen.getByTestId('tl-nonce')).toHaveTextContent('0');
  });

  it('publishes nothing at zero height (a hidden pane) and attaches when the stack mounts later', () => {
    heights.bl = 0;
    const { rerender } = render(<Frame show={false} />);
    const frame = screen.getByTestId('frame');
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('');
    rerender(<Frame show />);
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('');
    heights.bl = 120;
    act(() => { observers.forEach((o) => o.cb()); });
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('120px');
  });
});
