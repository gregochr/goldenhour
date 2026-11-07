import { describe, it, expect, vi } from 'vitest';

describe('heatGeometry edges', () => {
  it('frames northern England and the Scottish border by default', async () => {
    vi.resetModules();
    const { BBOX } = await import('../utils/heatGeometry.js');
    expect(BBOX).toEqual({
      type: 'MultiPoint',
      coordinates: [
        [-3.85, 53.8],
        [-0.28, 53.8],
        [-0.28, 55.88],
        [-3.85, 55.88],
      ],
    });
  });

  it('falls back to that frame for no spots', async () => {
    vi.resetModules();
    const { bbox, BBOX } = await import('../utils/heatGeometry.js');
    expect(bbox([])).toBe(BBOX);
    expect(bbox(null)).toBe(BBOX);
  });

  it('has no mean for an empty list', async () => {
    vi.resetModules();
    const { mean } = await import('../utils/heatGeometry.js');
    expect(mean([])).toBeUndefined();
    expect(mean([], (v) => v)).toBeUndefined();
    expect(mean([1, 2, 6])).toBe(3);
    expect(mean([{ a: 2 }, { a: 4 }], (v) => v.a)).toBe(3);
  });
});
