import React from 'react';
import { describe, it, expect, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import useAskSurface from '../hooks/useAskSurface.js';
import { installViewport, resetViewport } from './askViewport.js';

/**
 * The width band Ask's surfaces choose by (plan §2.6): phone below 640, tablet 640–1023, desktop from
 * 1024. The boundaries are the point — each is tested either side, because a band that is off by one
 * pixel puts an iPad in portrait on the wrong surface.
 */

function Probe() {
  return <output data-testid="band">{useAskSurface()}</output>;
}

const band = () => screen.getByTestId('band').textContent;

afterEach(resetViewport);

describe('useAskSurface', () => {
  it.each([
    [320, 'phone'],
    [639, 'phone'],
    [640, 'tablet'],
    [834, 'tablet'],
    [1023, 'tablet'],
    [1024, 'desktop'],
    [1600, 'desktop'],
  ])('is %i px wide → %s', (width, expected) => {
    installViewport(width);
    render(<Probe />);
    expect(band()).toBe(expected);
  });

  it('follows the viewport when it is resized across a boundary', () => {
    const viewport = installViewport(1024);
    render(<Probe />);
    expect(band()).toBe('desktop');
    act(() => viewport.resize(1023));
    expect(band()).toBe('tablet');
    act(() => viewport.resize(639));
    expect(band()).toBe('phone');
    act(() => viewport.resize(640));
    expect(band()).toBe('tablet');
  });
});
