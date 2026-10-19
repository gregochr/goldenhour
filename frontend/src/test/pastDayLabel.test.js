/**
 * `windowFirstCards.pastDayLabel` — the Map tab's past-window day word
 * (outcome-rating-plan.md §2 #2, §3 O3a step 1): `Today`, `Yesterday`, else `{Dow} {d}`.
 * Pinned in UTC here (the suite default); `pastDayLabelAbroad.test.js` pins it under a non-UK zone.
 */
import { describe, it, expect } from 'vitest';
import { pastDayLabel, dayLabelFor, dowDayLabel } from '../utils/windowFirstCards.js';

const TODAY = '2026-10-15'; // a Thursday

describe('pastDayLabel', () => {
  it('Today for today\'s own date', () => {
    expect(pastDayLabel(TODAY, TODAY)).toBe('Today');
  });

  it('Yesterday for the day before', () => {
    expect(pastDayLabel('2026-10-14', TODAY)).toBe('Yesterday');
  });

  it('a short weekday and the day of month for anything older', () => {
    expect(pastDayLabel('2026-10-13', TODAY)).toBe('Tue 13');
    expect(pastDayLabel('2026-10-12', TODAY)).toBe('Mon 12');
  });

  it('prints the day of month without a leading zero', () => {
    // 2026-10-01 is a Thursday; "Thu 01" would be the leading-zero bug.
    expect(pastDayLabel('2026-10-01', '2026-10-05')).toBe('Thu 1');
  });

  it('counts "Yesterday" across a month boundary', () => {
    expect(pastDayLabel('2026-09-30', '2026-10-01')).toBe('Yesterday');
  });

  it('counts days by date, not by hours: the 25-hour BST-end day is still one day', () => {
    // BST ends 2026-10-25. Noon-pinned date arithmetic rounds to whole days; the suite is UTC so this
    // pins the arithmetic, not a zone (the abroad files pin the zone).
    expect(pastDayLabel('2026-10-24', '2026-10-25')).toBe('Yesterday');
    expect(pastDayLabel('2026-10-23', '2026-10-25')).toBe('Fri 23');
  });

  it('is a different vocabulary from dayLabelFor: no "Tomorrow", a short weekday not a long one', () => {
    expect(dayLabelFor('2026-10-13', TODAY, '2026-10-16')).toBe('Tuesday');
    expect(pastDayLabel('2026-10-13', TODAY)).toBe('Tue 13');
  });
});

describe('dowDayLabel', () => {
  it('is the calendar form for any date, yesterday and today included (a sentence about one day)', () => {
    expect(dowDayLabel('2026-10-14')).toBe('Wed 14');
    expect(dowDayLabel(TODAY)).toBe('Thu 15');
    expect(dowDayLabel('2026-10-01')).toBe('Thu 1');
  });
});
