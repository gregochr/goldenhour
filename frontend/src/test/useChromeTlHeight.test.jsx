import React from 'react';
import {
  describe, it, expect, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { useChromeTlHeight } from '../hooks/useChromeTlHeight.js';

/**
 * The hook that publishes the top-left chrome group's height on EVERY day (hot-topics-by-day-plan
 * §1.12). It moved out of {@code BigSeasLine}, whose tests of the same behaviour moved here.
 */
function Frame({ show = true }) {
  const [ref, nonce] = useChromeTlHeight();
  return (
    <div data-testid="frame">
      {show && <div data-testid="chrome" ref={ref}>group</div>}
      <output data-testid="nonce">{nonce}</output>
    </div>
  );
}

describe('useChromeTlHeight', () => {
  const OriginalRO = globalThis.ResizeObserver;
  let observers;
  let height;
  let original;

  beforeEach(() => {
    observers = [];
    height = 94;
    globalThis.ResizeObserver = class {
      constructor(cb) {
        this.cb = cb;
        this.observed = [];
        this.disconnected = false;
        observers.push(this);
      }

      observe(el) { this.observed.push(el); }

      disconnect() { this.disconnected = true; }
    };
    // jsdom has no layout: give the group a height, as the browser would measure it.
    original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() { return this.dataset?.testid === 'chrome' ? height : 0; },
    });
  });

  afterEach(() => {
    globalThis.ResizeObserver = OriginalRO;
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  });

  it('sets --wf-chrome-tl-h on the frame from the group, follows resizes, and clears on unmount', () => {
    const { unmount } = render(<Frame />);
    const frame = screen.getByTestId('frame');
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('94px');
    height = 112;
    act(() => { observers.forEach((o) => o.cb()); });
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('112px');
    unmount();
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('');
  });

  it('observes the group itself, and disconnects on unmount', () => {
    const { unmount } = render(<Frame />);
    expect(observers).toHaveLength(1);
    expect(observers[0].observed).toEqual([screen.getByTestId('chrome')]);
    expect(observers[0].disconnected).toBe(false);
    unmount();
    expect(observers[0].disconnected).toBe(true);
  });

  it('attaches when the group mounts LATER than the host (MapView mounts it after an early return)', () => {
    const { rerender } = render(<Frame show={false} />);
    const frame = screen.getByTestId('frame');
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('');
    expect(observers).toHaveLength(0);
    rerender(<Frame show />);
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('94px');
    expect(observers).toHaveLength(1);
    rerender(<Frame show={false} />);
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('');
    expect(observers[0].disconnected).toBe(true);
  });

  it('publishes nothing while the group measures zero (a hidden pane), and publishes once it is visible', () => {
    height = 0;
    render(<Frame />);
    const frame = screen.getByTestId('frame');
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('');
    height = 60;
    act(() => { observers.forEach((o) => o.cb()); });
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('60px');
    height = 0;
    act(() => { observers.forEach((o) => o.cb()); });
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('');
  });

  it('moves the repaint nonce when the published height changes, and not otherwise', () => {
    render(<Frame />);
    const nonce = () => screen.getByTestId('nonce').textContent;
    // The first publish does not count: the label layers place after mount anyway.
    expect(nonce()).toBe('0');
    act(() => { observers.forEach((o) => o.cb()); });
    expect(nonce()).toBe('0');
    height = 130;
    act(() => { observers.forEach((o) => o.cb()); });
    expect(nonce()).toBe('1');
    act(() => { observers.forEach((o) => o.cb()); });
    expect(nonce()).toBe('1');
    height = 0;
    act(() => { observers.forEach((o) => o.cb()); });
    expect(nonce()).toBe('2');
  });
});
