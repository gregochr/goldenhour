import { describe, it, expect } from 'vitest';
import { confidenceScalar, fieldBloomAllowed, fieldConfidence } from '../utils/confidenceUtils.js';

/** The UNRATED mute (stood-down-field plan D3): a haze cap and a bloom gate, for that verdict only. */
describe('fieldConfidence', () => {
  it('caps an UNRATED window at the low tier whatever its own tier', () => {
    expect(fieldConfidence('high', 'UNRATED')).toBe(confidenceScalar('low'));
    expect(fieldConfidence('medium', 'UNRATED')).toBe(confidenceScalar('low'));
    expect(fieldConfidence('low', 'UNRATED')).toBe(confidenceScalar('low'));
  });

  it('never raises a scalar: UNRATED is never above the tier it was given', () => {
    ['high', 'medium', 'low'].forEach((tier) => {
      expect(fieldConfidence(tier, 'UNRATED')).toBeLessThanOrEqual(confidenceScalar(tier));
    });
  });

  it.each(['MAYBE', 'WORTH_IT', 'STAND_DOWN', 'AWAITING', null, undefined])(
    'leaves a %s verdict at the tier scalar',
    (verdict) => {
      expect(fieldConfidence('high', verdict)).toBe(confidenceScalar('high'));
      expect(fieldConfidence('medium', verdict)).toBe(confidenceScalar('medium'));
      expect(fieldConfidence('low', verdict)).toBe(confidenceScalar('low'));
    },
  );
});

describe('fieldBloomAllowed', () => {
  it('is false only for UNRATED', () => {
    expect(fieldBloomAllowed('UNRATED')).toBe(false);
    ['MAYBE', 'WORTH_IT', 'STAND_DOWN', 'AWAITING', null, undefined].forEach((v) => {
      expect(fieldBloomAllowed(v)).toBe(true);
    });
  });
});
