/**
 * `mapDates.resolveMapDate` — the PAST SOLAR selection licence (outcome-rating-plan.md §2 #2,
 * §3 O3a step 3).
 *
 * The clamp never returns a date before today, with one exemption for an explicit NIGHT choice. A
 * past solar window the reader stepped back onto is the second exemption, on the same terms:
 * provenance (`selectedIsPast`, set by the pick), never the value; usable only when the date is in
 * `allDates` and not after today. The night exemption's own tests live in `mapDates.test.js` and
 * are untouched by this file.
 */
import { describe, it, expect } from 'vitest';
import { resolveMapDate } from '../utils/mapDates.js';

const TODAY = '2026-10-15';
const YESTERDAY = '2026-10-14';
const TWO_AGO = '2026-10-13';
const TOMORROW = '2026-10-16';
const ALL = [TWO_AGO, YESTERDAY, TODAY, TOMORROW];

describe('resolveMapDate — selectedIsPast', () => {
  it('honours a past date when the pick carries the flag and the date is in allDates', () => {
    expect(resolveMapDate({
      selectedDate: YESTERDAY, selectedIsPast: true, autoDate: TODAY, allDates: ALL, todayStr: TODAY,
    })).toBe(YESTERDAY);
  });

  it('refuses the SAME date without the flag — a stale past pick falls through to the auto pick', () => {
    expect(resolveMapDate({
      selectedDate: YESTERDAY, selectedIsPast: false, autoDate: TODAY, allDates: ALL, todayStr: TODAY,
    })).toBe(TODAY);
  });

  it('falls through to the first today-forward date when there is no auto pick either', () => {
    expect(resolveMapDate({
      selectedDate: YESTERDAY, autoDate: null, allDates: ALL, todayStr: TODAY,
    })).toBe(TODAY);
  });

  it('refuses a flagged past date that is not in allDates', () => {
    expect(resolveMapDate({
      selectedDate: TWO_AGO, selectedIsPast: true, autoDate: TODAY,
      allDates: [YESTERDAY, TODAY], todayStr: TODAY,
    })).toBe(TODAY);
  });

  it('accepts today\'s own elapsed window as a past selection on today\'s date', () => {
    expect(resolveMapDate({
      selectedDate: TODAY, selectedIsPast: true, autoDate: TOMORROW, allDates: ALL, todayStr: TODAY,
    })).toBe(TODAY);
  });

  it('does not let the flag carry a FUTURE date (d <= today only)', () => {
    expect(resolveMapDate({
      selectedDate: TOMORROW, selectedIsPast: true, autoDate: TODAY, allDates: ALL, todayStr: TODAY,
    })).toBe(TODAY);
  });

  it('an unflagged future date is still taken, as always', () => {
    expect(resolveMapDate({
      selectedDate: TOMORROW, autoDate: TODAY, allDates: ALL, todayStr: TODAY,
    })).toBe(TOMORROW);
  });

  it('the night exemption wins when both flags arrive (and is judged by the night test)', () => {
    // Night in progress is yesterday's date; a night choice is usable while it is not over.
    expect(resolveMapDate({
      selectedDate: YESTERDAY, selectedIsNight: true, selectedIsPast: true, autoDate: TODAY,
      allDates: ALL, todayStr: TODAY, nightDate: YESTERDAY,
    })).toBe(YESTERDAY);
    // A night that is over is refused even though the past flag alone would have admitted it.
    expect(resolveMapDate({
      selectedDate: TWO_AGO, selectedIsNight: true, selectedIsPast: true, autoDate: TODAY,
      allDates: ALL, todayStr: TODAY, nightDate: YESTERDAY,
    })).toBe(TODAY);
  });

  it('an auto pick is never licensed by the past flag', () => {
    expect(resolveMapDate({
      selectedDate: null, selectedIsPast: true, autoDate: YESTERDAY, allDates: ALL, todayStr: TODAY,
    })).toBe(TODAY);
  });
});
