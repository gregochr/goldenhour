// A reader's device is not in the UK, and a pick names a UK window. Pinned above the imports, as the
// other "Abroad" files are: a file-scope assignment is applied after the suite's own `TZ = 'UTC'`.
process.env.TZ = 'America/New_York';

import { describe, it, expect } from 'vitest';
import { buildPickCards, shortWindow } from '../utils/askModel.js';
import { briefing, pick } from './askFixtures.js';

describe('the zone fixture itself', () => {
  it('is in force — New York, where a UTC-midnight date is still the day before', () => {
    // If this fails the file has quietly become a duplicate of askModel.test.js.
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/New_York');
    expect(new Date('2026-10-10').getDate()).toBe(9);
  });
});

describe('askModel on a device west of the UK', () => {
  it.each([
    ['2026-10-10', 'SUNRISE', 'Sat AM'],
    ['2026-10-11', 'SUNSET', 'Sun PM'],
  ])('%s %s is still %s — the weekday comes from the UK date string, not the device’s calendar',
    (date, targetType, expected) => {
      expect(shortWindow({ date, targetType })).toBe(expected);
    });

  it('names the full weekday for the accessible label from the same date string', () => {
    const [card] = buildPickCards([pick({ date: '2026-10-05' })], briefing().days, null);

    expect(card.label).toBe('Pick 1, Whitby, Monday sunset, 5 stars');
    expect(card.dayWord).toBe('Mon');
  });

  it('prints the event time on the UK clock, not the device’s', () => {
    // 17:41 UTC is 18:41 BST; New York would say 13:41.
    const [card] = buildPickCards([pick()], briefing().days, null);

    expect(card.eventTime).toBe('18:41');
  });
});
