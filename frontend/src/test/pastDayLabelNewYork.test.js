/**
 * `windowFirstCards.pastDayLabel` read from a device WEST of the UK
 * (outcome-rating-plan.md §3 O3a step 1). The companion of `pastDayLabelAbroad.test.js`, which is
 * pinned east of UTC: a zone can only be pinned once per file, and the two directions catch
 * different mistakes.
 *
 * <p>Reading a date string with `new Date('2026-10-16')` (UTC midnight) through local getters is
 * correct in Auckland (00:00Z is 13:00 the same day) and wrong in New York, where it is 20:00 on the
 * 15th and prints "Thu 15" for Friday the 16th.
 *
 * <p>⚠️ Do not move this to Europe/London or UTC: it would delete the only coverage of that defect.
 */
process.env.TZ = 'America/New_York';

import { describe, it, expect } from 'vitest';
import { pastDayLabel } from '../utils/windowFirstCards.js';

describe('pastDayLabel — device on America/New_York', () => {
  it('the pin is in force: UTC midnight on the 16th is still the 15th on this device', () => {
    expect(new Date('2026-10-16T00:00:00Z').getDate()).toBe(15);
  });

  it('names the date itself, not the device\'s calendar', () => {
    expect(pastDayLabel('2026-10-16', '2026-10-20')).toBe('Fri 16');
    expect(pastDayLabel('2026-10-01', '2026-10-20')).toBe('Thu 1');
  });

  it('keeps Today and Yesterday on the date arithmetic', () => {
    expect(pastDayLabel('2026-10-20', '2026-10-20')).toBe('Today');
    expect(pastDayLabel('2026-10-19', '2026-10-20')).toBe('Yesterday');
  });
});
