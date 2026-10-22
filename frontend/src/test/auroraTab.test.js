import { describe, it, expect } from 'vitest';
import { auroraTabOffered } from '../utils/auroraTab.js';

const NOW = new Date('2026-08-14T01:00:00Z');
const STRONG = {
  active: true, level: 'STRONG', simulated: false, currentNightEndsAt: '2026-08-14T04:00:00Z',
};

describe('auroraTabOffered (overlay convergence C3)', () => {
  it('offers the tab to PRO and ADMIN on a real MODERATE or STRONG alert', () => {
    expect(auroraTabOffered(STRONG, 'PRO_USER', NOW)).toBe(true);
    expect(auroraTabOffered(STRONG, 'ADMIN', NOW)).toBe(true);
    expect(auroraTabOffered({ ...STRONG, level: 'MODERATE' }, 'PRO_USER', NOW)).toBe(true);
  });

  it('never offers it to a LITE account, and refuses an unknown or missing role', () => {
    expect(auroraTabOffered(STRONG, 'LITE_USER', NOW)).toBe(false);
    expect(auroraTabOffered(STRONG, null, NOW)).toBe(false);
    expect(auroraTabOffered(STRONG, undefined, NOW)).toBe(false);
    expect(auroraTabOffered(STRONG, 'SOMETHING_NEW', NOW)).toBe(false);
  });

  it('never offers it on a quiet, inactive or sub-MODERATE alert, or no status', () => {
    expect(auroraTabOffered({ ...STRONG, active: false }, 'PRO_USER', NOW)).toBe(false);
    expect(auroraTabOffered({ ...STRONG, level: 'QUIET' }, 'PRO_USER', NOW)).toBe(false);
    expect(auroraTabOffered({ ...STRONG, level: 'MINOR' }, 'PRO_USER', NOW)).toBe(false);
    expect(auroraTabOffered(null, 'PRO_USER', NOW)).toBe(false);
    expect(auroraTabOffered(undefined, 'PRO_USER', NOW)).toBe(false);
  });

  it('never offers it on a simulated alert', () => {
    expect(auroraTabOffered({ ...STRONG, simulated: true }, 'ADMIN', NOW)).toBe(false);
  });

  it('needs active to be true, not merely truthy', () => {
    expect(auroraTabOffered({ ...STRONG, active: 'yes' }, 'PRO_USER', NOW)).toBe(false);
  });

  it('never offers it once the status\'s night has ended - and offers it right up to the end', () => {
    expect(auroraTabOffered(STRONG, 'PRO_USER', new Date('2026-08-14T03:59:59Z'))).toBe(true);
    expect(auroraTabOffered(STRONG, 'PRO_USER', new Date('2026-08-14T04:00:00Z'))).toBe(false);
    expect(auroraTabOffered(STRONG, 'PRO_USER', new Date('2026-08-14T09:00:00Z'))).toBe(false);
  });

  it('believes a status that carries no night end (a backend older than the field)', () => {
    const { currentNightEndsAt, ...bare } = STRONG;
    expect(currentNightEndsAt).toBeTruthy();
    expect(auroraTabOffered(bare, 'PRO_USER', NOW)).toBe(true);
  });
});
