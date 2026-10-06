// A device EAST of the UK, the opposite failure to askModelAbroad.test.js: here noon UTC is already the
// NEXT day, so a weekday read through the device's own calendar names Sunday for a Saturday pick.
// Pinned above the imports, as the other "Abroad" files are.
process.env.TZ = 'Pacific/Auckland';

import { describe, it, expect } from 'vitest';
import { buildPickCards, shortWindow } from '../utils/askModel.js';
import { briefing, pick } from './askFixtures.js';

describe('the zone fixture itself', () => {
  it('is in force — Auckland, where noon UTC on the 10th is already the 11th', () => {
    // If this fails the file has quietly become a duplicate of askModel.test.js.
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('Pacific/Auckland');
    expect(new Date('2026-10-10T12:00:00Z').getDate()).toBe(11);
  });
});

describe('askModel on a device east of the UK', () => {
  it.each([
    ['2026-10-10', 'SUNRISE', 'Sat AM'],
    ['2026-10-10', 'SUNSET', 'Sat PM'],
    ['2026-10-11', 'SUNRISE', 'Sun AM'],
  ])('%s %s is still %s', (date, targetType, expected) => {
    expect(shortWindow({ date, targetType })).toBe(expected);
  });

  it('names the full weekday for the accessible label from the date string, not the device calendar', () => {
    const [card] = buildPickCards([pick({ date: '2026-10-05' })], briefing().days, null);

    expect(card.label).toBe('Pick 1, Whitby, Monday sunset, 5 stars');
    expect(card.dayWord).toBe('Mon');
  });

  it('prints the event time on the UK clock, not Auckland’s', () => {
    // 17:41 UTC is 18:41 BST; Auckland would say 06:41 the next morning.
    const [card] = buildPickCards([pick()], briefing().days, null);

    expect(card.eventTime).toBe('18:41');
  });
});
