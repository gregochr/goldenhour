/**
 * The Admin list's wording rules (`components/map/outcomeRatingCopy.js`, outcome-rating-plan.md §2 #7):
 * the row timestamp (`HH:MM` today on the UK clock, else `{Dow} HH:MM`) and the footer's gap
 * (whole hours rounded to the nearest hour from an hour up, minutes below it). Both are pure, so the
 * boundaries are pinned here with fixed instants rather than through a rendered list.
 *
 * <p>Calendar: 16 Oct 2026 is a Friday; the UK is on BST (UTC+1) until 25 Oct 2026.
 */
import { describe, it, expect } from 'vitest';

import {
  adminFooter, adminHeader, adminNoRun, adminStamp,
} from '../components/map/outcomeRatingCopy.js';

const NOW_BST = new Date('2026-10-16T12:00:00Z');
const NOW_GMT = new Date('2026-12-10T12:00:00Z');

describe('adminHeader', () => {
  it('states the count when the list has been fetched, the bare title when it has not', () => {
    expect(adminHeader(null)).toBe('All ratings');
    expect(adminHeader(0)).toBe('All ratings · 0');
    expect(adminHeader(2)).toBe('All ratings · 2');
  });
});

describe('adminStamp — HH:MM today on the UK clock, else {Dow} HH:MM', () => {
  it('prints the bare clock for an instant on today\'s UK date, in BST', () => {
    expect(adminStamp('2026-10-16T09:05:00Z', NOW_BST)).toBe('10:05');
  });

  it('prints the weekday and clock for another day', () => {
    expect(adminStamp('2026-10-14T18:40:00Z', NOW_BST)).toBe('Wed 19:40');
  });

  it('reads the UK date of the instant, not the UTC one: 23:30 UTC in BST is 00:30 the NEXT UK day', () => {
    // 22:59 UTC on the 15th is 23:59 on the 15th UK — still yesterday.
    expect(adminStamp('2026-10-15T22:59:00Z', NOW_BST)).toBe('Thu 23:59');
    // One minute later it is 00:00 on the 16th UK — today, though UTC still says the 15th.
    expect(adminStamp('2026-10-15T23:00:00Z', NOW_BST)).toBe('00:00');
    expect(adminStamp('2026-10-15T23:30:00Z', NOW_BST)).toBe('00:30');
  });

  it('reads TODAY on the UK calendar too: at 23:30 UTC in BST it is already the next UK day', () => {
    const lateNow = new Date('2026-10-15T23:30:00Z'); // 00:30 on the 16th, UK
    expect(adminStamp('2026-10-16T08:00:00Z', lateNow)).toBe('09:00');
    expect(adminStamp('2026-10-15T20:00:00Z', lateNow)).toBe('Thu 21:00');
  });

  it('is not shifted in GMT: 00:00 UTC is 00:00 UK and today', () => {
    expect(adminStamp('2026-12-10T00:00:00Z', NOW_GMT)).toBe('00:00');
    expect(adminStamp('2026-12-09T23:59:00Z', NOW_GMT)).toBe('Wed 23:59');
  });

  it('treats a bare (zone-less) instant as UTC, the way the rest of the app does', () => {
    expect(adminStamp('2026-10-14T18:40:00', NOW_BST)).toBe('Wed 19:40');
  });

  it('answers an empty string for a missing or unreadable instant', () => {
    expect(adminStamp(null, NOW_BST)).toBe('');
    expect(adminStamp('not a date', NOW_BST)).toBe('');
  });
});

describe('adminFooter', () => {
  // 17 Oct 2026 (Saturday) 16:05 UK = 15:05Z.
  const RUN = '2026-10-17T15:05:00Z';
  const after = (minutes) => new Date(Date.parse(RUN) + minutes * 60000).toISOString();

  it('names the run on the UK clock and the gap in hours: "2h before sunset"', () => {
    expect(adminFooter(RUN, after(120), 'SUNSET'))
      .toBe('Rated against the run at Sat 17 Oct, 16:05 · 2h before sunset');
  });

  it('says sunrise for a sunrise window', () => {
    expect(adminFooter(RUN, after(120), 'SUNRISE'))
      .toBe('Rated against the run at Sat 17 Oct, 16:05 · 2h before sunrise');
  });

  it.each([
    [40, '40 min'],
    [59, '59 min'],
    [60, '1h'],
    [89, '1h'],
    [90, '2h'],
    [149, '2h'],
    [150, '3h'],
  ])('a gap of %i minutes reads %s', (minutes, span) => {
    expect(adminFooter(RUN, after(minutes), 'SUNSET')).toBe(
      `Rated against the run at Sat 17 Oct, 16:05 · ${span} before sunset`,
    );
  });

  it('rounds the gap to a whole minute first, so 59.6 minutes is "1h", never "60 min"', () => {
    const eventTime = new Date(Date.parse(RUN) + 59.6 * 60000).toISOString();
    expect(adminFooter(RUN, eventTime, 'SUNSET')).toContain('· 1h before sunset');
  });

  it('with no run recorded, says so and states nothing about a gap', () => {
    expect(adminFooter(null, after(120), 'SUNSET')).toBe('No forecast run recorded before this sunset');
    expect(adminFooter(null, after(120), 'SUNRISE')).toBe('No forecast run recorded before this sunrise');
    expect(adminNoRun('SUNRISE')).toBe('No forecast run recorded before this sunrise');
  });

  it('states the run alone when the event time is missing or no later than the run', () => {
    expect(adminFooter(RUN, null, 'SUNSET')).toBe('Rated against the run at Sat 17 Oct, 16:05');
    expect(adminFooter(RUN, RUN, 'SUNSET')).toBe('Rated against the run at Sat 17 Oct, 16:05');
    expect(adminFooter(RUN, after(-30), 'SUNSET')).toBe('Rated against the run at Sat 17 Oct, 16:05');
  });
});
