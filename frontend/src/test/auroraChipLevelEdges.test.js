import { describe, it, expect } from 'vitest';
import { auroraChipLevel } from '../utils/auroraChipLevel.js';

describe('auroraChipLevel edges', () => {
  it('is null for a missing row', () => {
    expect(auroraChipLevel(null, '2026-11-07', 'STRONG')).toBeNull();
    expect(auroraChipLevel(undefined, null, null)).toBeNull();
  });

  it('is moderate for an aurora row with no date when the alert names no night', () => {
    expect(auroraChipLevel({ eventType: 'AURORA' }, undefined, 'STRONG')).toBe('moderate');
    expect(auroraChipLevel({ eventType: 'AURORA', date: undefined }, null, 'EXTREME')).toBe('moderate');
  });

  it('is strong only for the alert night at a strong level', () => {
    const row = { eventType: 'AURORA', date: '2026-11-07' };
    expect(auroraChipLevel(row, '2026-11-07', 'STRONG')).toBe('strong');
    expect(auroraChipLevel(row, '2026-11-07', 'MODERATE')).toBe('moderate');
    expect(auroraChipLevel(row, '2026-11-08', 'STRONG')).toBe('moderate');
    expect(auroraChipLevel({ eventType: 'SUNSET', date: '2026-11-07' }, '2026-11-07', 'STRONG')).toBeNull();
  });
});
