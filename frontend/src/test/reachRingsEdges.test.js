import { describe, it, expect, beforeEach, vi } from 'vitest';

describe('reachRings edges', () => {
  let mod;

  beforeEach(async () => {
    vi.resetModules();
    mod = await import('../utils/reachRings.js');
  });

  it('defines exactly the 25 mi and 50 mi tiers, with km derived from miles', () => {
    expect(mod.RING_TIERS).toHaveLength(2);
    expect(mod.RING_TIERS[0]).toEqual({ mi: 25, km: 25 * 1.609344, minutes: 45 });
    expect(mod.RING_TIERS[1]).toEqual({ mi: 50, km: 50 * 1.609344, minutes: 90 });
    expect(mod.RING_TIERS[0].km).toBeCloseTo(40.2336, 4);
    expect(mod.RING_TIERS[1].km).toBeCloseTo(80.4672, 4);
  });
});
