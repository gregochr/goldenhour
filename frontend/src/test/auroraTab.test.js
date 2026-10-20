import { describe, it, expect } from 'vitest';
import { auroraTabOffered } from '../utils/auroraTab.js';

const STRONG = { active: true, level: 'STRONG', simulated: false };

describe('auroraTabOffered (overlay convergence C3)', () => {
  it('offers the tab to PRO and ADMIN on a real MODERATE or STRONG alert', () => {
    expect(auroraTabOffered(STRONG, 'PRO_USER')).toBe(true);
    expect(auroraTabOffered(STRONG, 'ADMIN')).toBe(true);
    expect(auroraTabOffered({ ...STRONG, level: 'MODERATE' }, 'PRO_USER')).toBe(true);
  });

  it('never offers it to a LITE account', () => {
    expect(auroraTabOffered(STRONG, 'LITE_USER')).toBe(false);
  });

  it('never offers it on a quiet, inactive or sub-MODERATE alert, or no status', () => {
    expect(auroraTabOffered({ ...STRONG, active: false }, 'PRO_USER')).toBe(false);
    expect(auroraTabOffered({ ...STRONG, level: 'QUIET' }, 'PRO_USER')).toBe(false);
    expect(auroraTabOffered({ ...STRONG, level: 'MINOR' }, 'PRO_USER')).toBe(false);
    expect(auroraTabOffered(null, 'PRO_USER')).toBe(false);
    expect(auroraTabOffered(undefined, 'PRO_USER')).toBe(false);
  });

  it('never offers it on a simulated alert', () => {
    expect(auroraTabOffered({ ...STRONG, simulated: true }, 'ADMIN')).toBe(false);
  });

  it('needs active to be true, not merely truthy', () => {
    expect(auroraTabOffered({ ...STRONG, active: 'yes' }, 'PRO_USER')).toBe(false);
  });
});
