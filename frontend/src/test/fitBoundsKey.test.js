import { describe, it, expect } from 'vitest';
import { fitBoundsKey } from '../utils/fitBoundsKey.js';

describe('fitBoundsKey', () => {
  it('namespaces the key by source', () => {
    expect(fitBoundsKey('region', 3)).toBe('region:3');
    expect(fitBoundsKey('focus', 3)).toBe('focus:3');
  });

  it('never collides across sources for the same nonce (the bug it fixes)', () => {
    // A region handoff and a focus overlay whose independent nonce counters land on
    // the same integer must still yield DIFFERENT keys, or FitBoundsController — which
    // only re-fits when the key changes — would silently skip the second re-fit.
    expect(fitBoundsKey('region', 1)).not.toBe(fitBoundsKey('focus', 1));
    expect(fitBoundsKey('region', 0)).not.toBe(fitBoundsKey('focus', 0));
  });

  it('re-fires for the same source when its nonce advances', () => {
    expect(fitBoundsKey('region', 1)).not.toBe(fitBoundsKey('region', 2));
  });

  it('defaults a null or undefined nonce to 0', () => {
    expect(fitBoundsKey('region', null)).toBe('region:0');
    expect(fitBoundsKey('focus', undefined)).toBe('focus:0');
  });
});
