import { describe, it, expect, vi } from 'vitest';
import { isLiteAlertShown } from '../utils/auroraLiteAlert.js';

const NOW = new Date('2026-01-15T22:00:00Z');
const alertOf = (extra = {}) => ({
  level: 'MODERATE', active: true, triggerType: 'forecast', allOvercast: false,
  currentNightEndsAt: '2026-01-16T06:00:00Z', ...extra,
});

describe('isLiteAlertShown (aurora-lite-alert-plan.md §2.3)', () => {
  it('shows an active MODERATE or STRONG alert on its night', () => {
    expect(isLiteAlertShown(alertOf(), NOW)).toBe(true);
    expect(isLiteAlertShown(alertOf({ level: 'STRONG' }), NOW)).toBe(true);
  });

  it('shows nothing for no alert, an inactive one, or a level that is not alert-worthy', () => {
    expect(isLiteAlertShown(null, NOW)).toBe(false);
    expect(isLiteAlertShown(alertOf({ active: false }), NOW)).toBe(false);
    expect(isLiteAlertShown(alertOf({ level: 'MINOR' }), NOW)).toBe(false);
    expect(isLiteAlertShown(alertOf({ level: 'QUIET' }), NOW)).toBe(false);
  });

  it('shows nothing once the night has ended, and fails open with no end', () => {
    expect(isLiteAlertShown(alertOf({ currentNightEndsAt: '2026-01-15T21:59:59Z' }), NOW)).toBe(false);
    expect(isLiteAlertShown(alertOf({ currentNightEndsAt: null }), NOW)).toBe(true);
  });

  it('hides under full overcast unless STRONG; an unknown overcast fails open', () => {
    expect(isLiteAlertShown(alertOf({ allOvercast: true }), NOW)).toBe(false);
    expect(isLiteAlertShown(alertOf({ allOvercast: true, level: 'STRONG' }), NOW)).toBe(true);
    expect(isLiteAlertShown(alertOf({ allOvercast: undefined }), NOW)).toBe(true);
  });

  it('reads the app clock when no instant is given', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-01-16T07:00:00Z'));
    try {
      expect(isLiteAlertShown(alertOf())).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});
