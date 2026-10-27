import { describe, it, expect } from 'vitest';
import { resolveRegionDisplay } from '../utils/tierUtils.js';

describe('resolveRegionDisplay', () => {
  it('prefers displayVerdict when present', () => {
    expect(resolveRegionDisplay({ displayVerdict: 'WORTH_IT', verdict: 'STANDDOWN' }))
      .toBe('WORTH_IT');
  });

  it('maps legacy verdict GO → WORTH_IT', () => {
    expect(resolveRegionDisplay({ verdict: 'GO' })).toBe('WORTH_IT');
  });

  it('maps legacy verdict MARGINAL → MAYBE', () => {
    expect(resolveRegionDisplay({ verdict: 'MARGINAL' })).toBe('MAYBE');
  });

  it('maps legacy verdict STANDDOWN → STAND_DOWN', () => {
    expect(resolveRegionDisplay({ verdict: 'STANDDOWN' })).toBe('STAND_DOWN');
  });

  it('passes UNRATED through as itself, and prefers it over a GO triage verdict (U2)', () => {
    expect(resolveRegionDisplay({ displayVerdict: 'UNRATED', verdict: 'GO' })).toBe('UNRATED');
  });

  it('returns AWAITING for null region', () => {
    expect(resolveRegionDisplay(null)).toBe('AWAITING');
  });

  it('returns AWAITING when neither field is set', () => {
    expect(resolveRegionDisplay({})).toBe('AWAITING');
  });
});
