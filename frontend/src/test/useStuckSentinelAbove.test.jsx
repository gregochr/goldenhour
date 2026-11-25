import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import useStuckSentinel from '../hooks/useStuckSentinel.js';

/** `useStuckSentinel`'s `aboveOnly` mode: for an element that starts BELOW the line (a section under the lens). */
let observers;

beforeEach(() => {
  observers = [];
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback, options) { this.callback = callback; this.options = options; observers.push(this); }

    observe() {}

    disconnect() {}
  });
});
afterEach(() => vi.unstubAllGlobals());

function Probe({ aboveOnly }) {
  const [ref, passed] = useStuckSentinel(0, { aboveOnly });
  return <span ref={ref} data-testid="probe" data-passed={String(passed)} />;
}

const report = (entry) => act(() => { observers.at(-1).callback([entry]); });
const passed = () => screen.getByTestId('probe').dataset.passed;

describe('useStuckSentinel — aboveOnly', () => {
  it('stays false while the element is below the line, true only once it is wholly above it', () => {
    render(<Probe aboveOnly />);
    report({ isIntersecting: false, boundingClientRect: { top: 1200, bottom: 2800 }, rootBounds: { top: 0 } });
    expect(passed()).toBe('false');
    report({ isIntersecting: false, boundingClientRect: { top: -1603, bottom: -3 }, rootBounds: { top: 0 } });
    expect(passed()).toBe('true');
    report({ isIntersecting: true, boundingClientRect: { top: -200, bottom: 1400 }, rootBounds: { top: 0 } });
    expect(passed()).toBe('false');
  });

  it('measures against the root inset, and treats a missing root as the viewport top', () => {
    render(<Probe aboveOnly />);
    report({ isIntersecting: false, boundingClientRect: { top: -90, bottom: 40 }, rootBounds: { top: 40 } });
    expect(passed()).toBe('true');
    report({ isIntersecting: false, boundingClientRect: { top: -50, bottom: -1 }, rootBounds: null });
    expect(passed()).toBe('true');
    report({ isIntersecting: false, boundingClientRect: { top: 5, bottom: 60 }, rootBounds: null });
    expect(passed()).toBe('false');
  });

  it('treats an entry with no geometry as not passed (never throws)', () => {
    render(<Probe aboveOnly />);
    report({ isIntersecting: false });
    expect(passed()).toBe('false');
  });

  it('keeps the old rule without the option: not intersecting is stuck, wherever the sentinel is', () => {
    render(<Probe />);
    report({ isIntersecting: false });
    expect(passed()).toBe('true');
  });
});
