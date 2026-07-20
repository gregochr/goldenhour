import { describe, it, expect } from 'vitest';
import {
  daysOut,
  horizonConfidence,
  resolveConfidence,
  confidenceTreatment,
  scaleRgbaAlpha,
  CONFIDENCE_TREATMENT,
} from '../utils/confidenceUtils.js';

describe('confidenceUtils', () => {
  describe('daysOut', () => {
    it('counts whole days between two ISO dates (UTC)', () => {
      expect(daysOut('2026-07-20', '2026-07-20')).toBe(0);
      expect(daysOut('2026-07-21', '2026-07-20')).toBe(1);
      expect(daysOut('2026-07-23', '2026-07-20')).toBe(3);
      expect(daysOut('2026-07-19', '2026-07-20')).toBe(-1);
    });

    it('spans month and year boundaries', () => {
      expect(daysOut('2026-08-01', '2026-07-31')).toBe(1);
      expect(daysOut('2027-01-01', '2026-12-31')).toBe(1);
    });

    it('returns null for missing or malformed input', () => {
      expect(daysOut(null, '2026-07-20')).toBeNull();
      expect(daysOut('2026-07-20', null)).toBeNull();
      expect(daysOut('not-a-date', '2026-07-20')).toBeNull();
      expect(daysOut('2026-07', '2026-07-20')).toBeNull();
    });
  });

  describe('horizonConfidence', () => {
    it('maps horizon to tiers: 0-1 high, 2-3 medium, 4+ low', () => {
      expect(horizonConfidence(0)).toBe('high');
      expect(horizonConfidence(1)).toBe('high');
      expect(horizonConfidence(2)).toBe('medium');
      expect(horizonConfidence(3)).toBe('medium');
      expect(horizonConfidence(4)).toBe('low');
      expect(horizonConfidence(7)).toBe('low');
    });

    it('returns null when horizon is unknown', () => {
      expect(horizonConfidence(null)).toBeNull();
    });
  });

  describe('resolveConfidence (fail-soft)', () => {
    it('prefers a valid backend-supplied confidence over horizon', () => {
      // Backend says low even though it is today — the derived signal wins.
      expect(resolveConfidence({ confidence: 'low' }, 0)).toBe('low');
      expect(resolveConfidence({ confidence: 'high' }, 5)).toBe('high');
    });

    it('falls back to horizon when the field is missing (legacy payload)', () => {
      expect(resolveConfidence({}, 0)).toBe('high');
      expect(resolveConfidence({}, 3)).toBe('medium');
      expect(resolveConfidence(null, 5)).toBe('low');
    });

    it('ignores an unrecognised confidence value and falls back to horizon', () => {
      expect(resolveConfidence({ confidence: 'bogus' }, 2)).toBe('medium');
    });

    it('defaults to medium when nothing is known, and never throws', () => {
      expect(resolveConfidence(null, null)).toBe('medium');
      expect(resolveConfidence(undefined, undefined)).toBe('medium');
    });
  });

  describe('confidenceTreatment', () => {
    it('dims fill by tier and flags only low as provisional', () => {
      expect(confidenceTreatment('high').fillScale).toBe(1.0);
      expect(confidenceTreatment('high').provisional).toBe(false);
      expect(confidenceTreatment('medium').provisional).toBe(false);
      expect(confidenceTreatment('medium').fillScale).toBeLessThan(1);
      expect(confidenceTreatment('low').provisional).toBe(true);
      expect(confidenceTreatment('low').fillScale).toBeLessThan(confidenceTreatment('medium').fillScale);
    });

    it('defaults an unknown tier to the neutral medium treatment', () => {
      expect(confidenceTreatment('nonsense')).toBe(CONFIDENCE_TREATMENT.medium);
      expect(confidenceTreatment(undefined)).toBe(CONFIDENCE_TREATMENT.medium);
    });
  });

  describe('scaleRgbaAlpha', () => {
    it('multiplies the alpha channel', () => {
      expect(scaleRgbaAlpha('rgba(138,174,114,0.18)', 0.5)).toBe('rgba(138, 174, 114, 0.09)');
    });

    it('leaves the colour unchanged when scale >= 1', () => {
      expect(scaleRgbaAlpha('rgba(1,2,3,0.4)', 1)).toBe('rgba(1,2,3,0.4)');
      expect(scaleRgbaAlpha('rgba(1,2,3,0.4)', 1.5)).toBe('rgba(1,2,3,0.4)');
    });

    it('is a no-op on non-rgba strings', () => {
      expect(scaleRgbaAlpha('var(--x)', 0.5)).toBe('var(--x)');
      expect(scaleRgbaAlpha(undefined, 0.5)).toBeUndefined();
    });

    it('clamps the resulting alpha to [0,1]', () => {
      expect(scaleRgbaAlpha('rgba(0,0,0,0.9)', 0.0)).toBe('rgba(0, 0, 0, 0)');
    });
  });
});
