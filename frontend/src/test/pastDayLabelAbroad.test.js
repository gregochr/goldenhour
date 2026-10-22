/**
 * `windowFirstCards.pastDayLabel` read from a device that is NOT in the UK
 * (outcome-rating-plan.md §3 O3a step 1: "pinned in UTC and under a non-UK zone").
 *
 * <p>The label is a calendar date's own name — the weekday and day of month of an ISO string — so
 * it must not move with the device's zone. A reading through local getters
 * ({@code getDate()}/{@code getDay()}) is wrong in exactly the zones east of UTC+12: noon UTC on
 * the 16th is 01:00 on the 17th in New Zealand summer time, so a local-getter implementation prints
 * "Sat 17" for Friday the 16th. {@code America/New_York} (the suite's other abroad pin) would not
 * catch it — noon UTC is 08:00 the same day there — which is why this file is pinned to
 * {@code Pacific/Auckland} instead (frontend-test-standards.md, Timezone: "a zone *east* of the UK
 * fails in the opposite direction").
 *
 * <p>⚠️ Do not move this to Europe/London or UTC: it would delete the only coverage of that defect.
 */
process.env.TZ = 'Pacific/Auckland';

import { describe, it, expect } from 'vitest';
import { pastDayLabel } from '../utils/windowFirstCards.js';

describe('pastDayLabel — device on Pacific/Auckland', () => {
  it('the pin is in force: noon UTC on the 16th is the 17th on this device', () => {
    // The guard the standards ask for, measured where the assertions are: if the pin were lost the
    // device date would equal the UTC date and the cases below would pass for the wrong reason.
    expect(new Date('2026-10-16T12:00:00Z').getDate()).toBe(17);
  });

  it('names a weekday day by the date itself, not the device\'s calendar', () => {
    // 2026-10-16 is a Friday. A local-getter implementation reads Saturday the 17th here.
    expect(pastDayLabel('2026-10-16', '2026-10-20')).toBe('Fri 16');
  });

  it('keeps Today and Yesterday on the date arithmetic, whatever the zone', () => {
    expect(pastDayLabel('2026-10-20', '2026-10-20')).toBe('Today');
    expect(pastDayLabel('2026-10-19', '2026-10-20')).toBe('Yesterday');
  });
});
