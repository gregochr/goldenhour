import React from 'react';
import { describe, it, expect, afterEach, beforeEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import useInViewOnce from '../hooks/useInViewOnce.js';

/**
 * Whether an element has ever been on screen. jsdom has no IntersectionObserver, so one is stubbed here to be driven;
 * the "no observer" case is the default jsdom state.
 */
function Probe({ mounted = true }) {
  const [ref, seen] = useInViewOnce();
  return mounted ? <div ref={ref} data-testid="probe" data-seen={String(seen)} /> : <p data-seen={String(seen)} data-testid="gone" />;
}

let observers;

const install = () => {
  observers = [];
  globalThis.IntersectionObserver = class {
    constructor(callback) { this.callback = callback; this.nodes = []; this.disconnected = false; observers.push(this); }

    observe(node) { this.nodes.push(node); }

    disconnect() { this.disconnected = true; }
  };
};

beforeEach(() => { observers = []; });
afterEach(() => { delete globalThis.IntersectionObserver; });

describe('useInViewOnce', () => {
  it('is false until the element first intersects, then true for good, and stops observing', () => {
    install();
    render(<Probe />);
    expect(screen.getByTestId('probe')).toHaveAttribute('data-seen', 'false');
    expect(observers).toHaveLength(1);
    expect(observers[0].nodes).toEqual([screen.getByTestId('probe')]);

    act(() => observers[0].callback([{ isIntersecting: false }]));
    expect(screen.getByTestId('probe')).toHaveAttribute('data-seen', 'false');

    act(() => observers[0].callback([{ isIntersecting: true }]));
    expect(screen.getByTestId('probe')).toHaveAttribute('data-seen', 'true');
    expect(observers[0].disconnected).toBe(true);
  });

  it('stays true when the element scrolls out again', () => {
    install();
    render(<Probe />);
    act(() => observers[0].callback([{ isIntersecting: true }]));
    act(() => observers[0].callback([{ isIntersecting: false }]));
    expect(screen.getByTestId('probe')).toHaveAttribute('data-seen', 'true');
  });

  it('observes nothing while the element is not mounted, and attaches when it arrives', () => {
    install();
    const { rerender } = render(<Probe mounted={false} />);
    expect(observers).toHaveLength(0);
    rerender(<Probe />);
    expect(observers).toHaveLength(1);
  });

  it('disconnects when the element goes', () => {
    install();
    const { rerender } = render(<Probe />);
    rerender(<Probe mounted={false} />);
    expect(observers[0].disconnected).toBe(true);
  });

  it('fails open without an IntersectionObserver: a cheap fetch is better early than never', () => {
    render(<Probe />);
    expect(screen.getByTestId('probe')).toHaveAttribute('data-seen', 'true');
  });
});
