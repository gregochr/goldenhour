// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { createLandMask } from '../utils/landMask.js';

describe('createLandMask without a window', () => {
  it('is created and declines to build, rather than throwing, where there is no window at all', () => {
    expect(typeof window).toBe('undefined');
    const map = { getZoom: () => 7, project: () => ({ x: 0, y: 0 }) };
    const mask = createLandMask(map);
    expect(mask.get()).toBeNull();
    expect(() => mask.invalidate()).not.toThrow();
  });
});
