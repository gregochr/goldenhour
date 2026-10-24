/**
 * `utils/mapEvents.js` — the daylight High-water row (hot-topics-by-day-plan §3 M3).
 *
 * <p>One row per served big-seas day flagged {@code daylightHighWater} whose window is still ahead,
 * between that date's sunrise and sunset, carrying the date's {@code inForecastDomain} (without it
 * {@code isForwardableRow} refuses the row and a selection of it would leave the parent date and the
 * Map pane out of step). The clock is fixed: Thursday 22 Oct 2026, 09:40Z (BST); Friday's window ends
 * 14:00 UK / 13:00Z.
 */
import { describe, it, expect, afterEach } from 'vitest';
import {
  buildMapEvents, findEvIndex, isForwardableRow, isHighWaterRow, isNightKind, EVENT_KIND, HIGH_WATER,
} from '../utils/mapEvents.js';
import { formatEventTimeUk } from '../utils/conversions.js';
import { setRewind } from '../utils/rewind.js';
import { FRIDAY_HW, SATURDAY } from './bigSeasFixtures.js';

const TODAY = '2026-10-22';
const TOMORROW = '2026-10-23';
const FRI = '2026-10-23';
const NOW = new Date('2026-10-22T09:40:00Z');

function solarWindow(date, targetType) {
  return {
    key: `${date}:${targetType}`,
    date,
    targetType,
    label: `${date} ${targetType}`,
    time: targetType === 'SUNRISE' ? '07:20' : '17:40',
    bestRating: 4,
    confidenceTier: 'high',
    badges: [],
  };
}

const args = (extra = {}) => ({
  solarWindows: [solarWindow(FRI, 'SUNRISE'), solarWindow(FRI, 'SUNSET')],
  forecastDates: [TODAY, FRI],
  todayStr: TODAY,
  tomorrowStr: TOMORROW,
  formatTimeUk: formatEventTimeUk,
  bigSeas: [FRIDAY_HW],
  now: NOW,
  ...extra,
});

afterEach(() => setRewind(null));

describe('buildMapEvents: the daylight High-water row', () => {
  it('is inserted for a flagged day, between that date\'s sunrise and sunset, in order', () => {
    const events = buildMapEvents(args());
    expect(events.map((e) => e.id)).toEqual([
      `solar:${TODAY}:SUNRISE`,
      `solar:${TODAY}:SUNSET`,
      `solar:${FRI}:SUNRISE`,
      `hw:${FRI}:HIGH_WATER`,
      `solar:${FRI}:SUNSET`,
    ]);
  });

  it('has the row shape: kind, type, the UK clock time, day words, no badges, no score', () => {
    const row = buildMapEvents(args()).find((e) => e.kind === EVENT_KIND.HW);
    expect(row).toMatchObject({
      kind: 'hw',
      eventType: HIGH_WATER,
      date: FRI,
      time: '12:40',
      label: 'Tomorrow high water',
      dayLabel: 'Tomorrow',
      badges: [],
      scored: false,
      served: false,
      bestRating: null,
    });
    // The served instants the expiry timer and the strip's tick read ride on the row, verbatim.
    expect(row.windowEnd).toBe(FRIDAY_HW.windowEnd);
    expect(row.hwTime).toBe(FRIDAY_HW.hwTime);
    expect(row.daylightStart).toBe(FRIDAY_HW.daylightStart);
    expect(row.daylightEnd).toBe(FRIDAY_HW.daylightEnd);
    expect(isHighWaterRow(row)).toBe(true);
  });

  it('is found by findEvIndex(list, HIGH_WATER, date)', () => {
    const events = buildMapEvents(args());
    expect(findEvIndex(events, 'HIGH_WATER', FRI)).toBe(3);
    expect(findEvIndex(events, 'HIGH_WATER', TODAY)).toBe(-1);
  });

  it('is NOT inserted without the flag: an aligned day, or an unflagged one', () => {
    expect(buildMapEvents(args({ bigSeas: [SATURDAY] })).some((e) => e.kind === 'hw')).toBe(false);
    expect(buildMapEvents(args({ bigSeas: [{ ...FRIDAY_HW, daylightHighWater: false }] }))
      .some((e) => e.kind === 'hw')).toBe(false);
    expect(buildMapEvents(args({ bigSeas: undefined })).some((e) => e.kind === 'hw')).toBe(false);
  });

  it('is gone once the day\'s windowEnd has passed, under the app clock', () => {
    const before = buildMapEvents(args({ now: new Date('2026-10-23T12:59:59Z') }));
    expect(before.some((e) => e.kind === 'hw')).toBe(true);
    const after = buildMapEvents(args({ now: new Date('2026-10-23T13:00:00Z') }));
    expect(after.some((e) => e.kind === 'hw')).toBe(false);
  });

  it('is gone under a Rewound appNow past the window (the default clock is the app clock)', () => {
    setRewind('2026-10-23T13:30:00Z');
    const rest = args();
    delete rest.now;
    expect(buildMapEvents(rest).some((e) => e.kind === 'hw')).toBe(false);
    setRewind('2026-10-23T09:00:00Z');
    expect(buildMapEvents(rest).some((e) => e.kind === 'hw')).toBe(true);
  });

  it('carries its date\'s inForecastDomain, so isForwardableRow accepts it', () => {
    const row = buildMapEvents(args()).find((e) => e.kind === 'hw');
    expect(row.inForecastDomain).toBe(true);
    expect(isForwardableRow(row, { todayStr: TODAY })).toBe(true);
  });

  it('takes the domain from the date\'s solar rows, not from a default', () => {
    // Served windows for the date but the forecast dates do not list it: the solar rows say true (a
    // rendered window is a real forecast date), and the High-water row agrees with them.
    const served = buildMapEvents(args({ forecastDates: [TODAY] }));
    const solar = served.find((e) => e.id === `solar:${FRI}:SUNRISE`);
    expect(solar.inForecastDomain).toBe(true);
    expect(served.find((e) => e.kind === 'hw').inForecastDomain).toBe(solar.inForecastDomain);
  });

  it('a date with NO solar row computes the flag as solarRow would (forecast dates), never a default', () => {
    const inDomain = buildMapEvents(args({ solarWindows: [], forecastDates: [TODAY, FRI] }));
    const hwIn = inDomain.find((e) => e.kind === 'hw');
    expect(hwIn).toBeDefined();
    expect(hwIn.inForecastDomain).toBe(true);
    // A date the list holds only for a night (outside the forecast domain) has no solar row: the flag is
    // computed from the forecast dates, so false, and a false flag means no row (below).
    const outOfDomain = buildMapEvents(args({
      solarWindows: [],
      forecastDates: [TODAY],
      astroAvailableDates: [FRI],
      astroConditionsByDate: new Map([[FRI, [{ locationName: 'X', stars: 4 }]]]),
    }));
    // The date is in the list (its night), but no solar row and outside the forecast domain: App would
    // refuse it, so no High-water row is offered at all.
    expect(outOfDomain.some((e) => e.kind === 'astro' && e.date === FRI)).toBe(true);
    expect(outOfDomain.some((e) => e.kind === 'hw')).toBe(false);
    // And a date the list does not hold at all gets no row.
    expect(buildMapEvents(args({ solarWindows: [], forecastDates: [TODAY] }))
      .some((e) => e.kind === 'hw')).toBe(false);
  });

  it('is forwarded by the calendar like a solar window, not by the night test', () => {
    const row = buildMapEvents(args()).find((e) => e.kind === 'hw');
    expect(isForwardableRow(row, { todayStr: TODAY })).toBe(true);
    // Once the date is past it is not (the window would have ended long before, but the rule is the date's).
    expect(isForwardableRow(row, { todayStr: '2026-10-24' })).toBe(false);
  });

  it('sits before the date\'s night rows', () => {
    const events = buildMapEvents(args({
      astroAvailableDates: [FRI],
      astroConditionsByDate: new Map([[FRI, [{ locationName: 'X', stars: 4 }]]]),
    }));
    const ids = events.map((e) => e.id);
    expect(ids.indexOf(`hw:${FRI}:HIGH_WATER`)).toBeLessThan(ids.indexOf(`solar:${FRI}:SUNSET`));
    expect(ids.indexOf(`solar:${FRI}:SUNSET`)).toBeLessThan(ids.findIndex((id) => id.startsWith('astro:')));
  });

  it('is not stamped bigSeasAligned (that mark is a solar event the high water lands on)', () => {
    const events = buildMapEvents(args());
    expect(events.find((e) => e.kind === 'hw').bigSeasAligned).toBeUndefined();
    expect(events.every((e) => !e.bigSeasAligned)).toBe(true);
  });
});

describe('isNightKind', () => {
  it('is true for astro and aurora only: the High-water row is neither a night nor a solar window', () => {
    expect(isNightKind('astro')).toBe(true);
    expect(isNightKind('aur')).toBe(true);
    expect(isNightKind('solar')).toBe(false);
    expect(isNightKind('hw')).toBe(false);
  });
});
