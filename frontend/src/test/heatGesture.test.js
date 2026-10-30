import { describe, it, expect } from 'vitest';
import { gestureTransform } from '../utils/heatGesture.js';

describe('gestureTransform (performance review F17 / P4b)', () => {
  it('is the identity scale at the drawn zoom, and passes the projected corner through', () => {
    expect(gestureTransform(9, 9, { x: -40, y: -25 })).toEqual({ offset: { x: -40, y: -25 }, scale: 1 });
  });

  it('doubles per zoom level', () => {
    expect(gestureTransform(9, 10, { x: 12, y: 34 })).toEqual({ offset: { x: 12, y: 34 }, scale: 2 });
    expect(gestureTransform(10, 9, { x: 0, y: 0 }).scale).toBe(0.5);
  });

  it('scales by the fractional ratio mid-pinch', () => {
    expect(gestureTransform(9, 9.5, { x: 0, y: 0 }).scale).toBeCloseTo(Math.SQRT2, 12);
  });

  it('keeps the corner registered: a point d px right of the corner lands d*scale right of the offset', () => {
    const { offset, scale } = gestureTransform(9, 11, { x: 100, y: 50 });
    expect(offset.x + 30 * scale).toBe(100 + 120);
    expect(offset.y + 10 * scale).toBe(50 + 40);
  });
});
