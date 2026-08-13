/**
 * Tests for the map's next-solar-event auto-selection.
 *
 * ⚠️ REBUILT ON A PINNED CLOCK AND A PINNED TIMEZONE. Every fixture here used to come from
 * `new Date()` and every expectation from `new Date().toLocaleDateString('en-CA')` — so the file
 * derived both sides from the same wall clock and agreed with itself under any zone, including a
 * wrong one. That made it blind to the defect this change fixes: `computeAutoSelection` looks up
 * `forecastsByDate`, whose keys are backend dates on the UK calendar, and it was reading them with
 * the browser's. It also meant a test running across local midnight could compare two different
 * days.
 *
 * Nothing in this repo pins `TZ` (dev machines are `Europe/London`, CI is UTC), so the pin below is
 * what makes this file the same test everywhere. `mapDatesAbroad.test.js` is its counterpart: it
 * pins a NON-UK zone and asserts this function still answers on the UK calendar, which is the one
 * thing a UK-pinned file can never show.
 */
process.env.TZ = 'Europe/London';

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { computeAutoSelection } from '../utils/conversions.js';

/** A BST day, so UK local time is UTC+1 and the two calendars are distinguishable. */
const TODAY = '2026-08-13';
const TOMORROW = '2026-08-14';

/** A Date at hh:mm UK on {@link TODAY}. The TZ pin above makes local time UK time. */
function ukAt(hour, minute = 0) {
  return new Date(2026, 7, 13, hour, minute, 0, 0);
}

/**
 * Converts a Date to a naive UTC datetime string (no 'Z' suffix), matching how the backend
 * serialises LocalDateTime via Jackson — which is what `computeAutoSelection` parses.
 */
function toBackendFormat(date) {
  return date.toISOString().slice(0, 19);
}

/** A minimal location carrying a sunset time on TODAY. */
function makeLocation({ sunsetTime, locationType = ['LANDSCAPE'] } = {}) {
  const forecastsByDate = new Map();
  if (sunsetTime) {
    forecastsByDate.set(TODAY, { sunset: { solarEventTime: sunsetTime } });
  }
  return { name: 'Test', lat: 55, lon: -1.7, forecastsByDate, locationType };
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(ukAt(12));
});

afterEach(() => {
  vi.useRealTimers();
});

describe('computeAutoSelection', () => {
  describe('returns null when no usable data', () => {
    it('returns null for empty locations array', () => {
      expect(computeAutoSelection([], ukAt(12))).toBeNull();
    });

    it('returns null when no location has today sunset data', () => {
      const loc = makeLocation({ sunsetTime: null });
      expect(computeAutoSelection([loc], ukAt(12))).toBeNull();
    });

    it('returns null when all locations are WILDLIFE (no sunset data used)', () => {
      const loc = makeLocation({ sunsetTime: toBackendFormat(ukAt(18)), locationType: ['WILDLIFE'] });
      expect(computeAutoSelection([loc], ukAt(12))).toBeNull();
    });
  });

  describe('today + SUNSET when sunset has not passed', () => {
    it('returns today + SUNSET when now is before sunset', () => {
      const loc = makeLocation({ sunsetTime: toBackendFormat(ukAt(20)) });

      expect(computeAutoSelection([loc], ukAt(14))).toEqual({ date: TODAY, eventType: 'SUNSET' });
    });

    it('returns today + SUNSET when now is exactly at sunset', () => {
      // The boundary: now === sunset is still inside the 30-minute afterglow buffer.
      const sunset = ukAt(18, 12);
      const loc = makeLocation({ sunsetTime: toBackendFormat(sunset) });

      expect(computeAutoSelection([loc], sunset)).toEqual({ date: TODAY, eventType: 'SUNSET' });
    });

    it('returns today + SUNSET 29 minutes after sunset, one minute inside the buffer', () => {
      const sunset = ukAt(18);
      const now = new Date(sunset.getTime() + 29 * 60 * 1000);
      const loc = makeLocation({ sunsetTime: toBackendFormat(sunset) });

      expect(computeAutoSelection([loc], now)).toEqual({ date: TODAY, eventType: 'SUNSET' });
    });
  });

  describe('tomorrow + SUNRISE when sunset + buffer has passed', () => {
    it('returns tomorrow + SUNRISE 31 minutes after sunset, one minute past the buffer', () => {
      const sunset = ukAt(18);
      const now = new Date(sunset.getTime() + 31 * 60 * 1000);
      const loc = makeLocation({ sunsetTime: toBackendFormat(sunset) });

      expect(computeAutoSelection([loc], now)).toEqual({ date: TOMORROW, eventType: 'SUNRISE' });
    });

    it('returns tomorrow + SUNRISE well after sunset', () => {
      const loc = makeLocation({ sunsetTime: toBackendFormat(ukAt(18)) });

      expect(computeAutoSelection([loc], ukAt(22))).toEqual({ date: TOMORROW, eventType: 'SUNRISE' });
    });

    it('rolls to the next month correctly on the last day of one', () => {
      // The tomorrow step is date arithmetic, so the month boundary is its real edge case.
      vi.setSystemTime(new Date(2026, 7, 31, 22));
      const sunset = new Date(2026, 7, 31, 18);
      const forecastsByDate = new Map([
        ['2026-08-31', { sunset: { solarEventTime: toBackendFormat(sunset) } }],
      ]);
      const loc = { name: 'Test', lat: 55, lon: -1.7, forecastsByDate, locationType: ['LANDSCAPE'] };

      expect(computeAutoSelection([loc], new Date(2026, 7, 31, 22)))
        .toEqual({ date: '2026-09-01', eventType: 'SUNRISE' });
    });

    it('steps a whole day across the 25-hour day the clocks go back', () => {
      // 2026-10-25 is the last Sunday in October: BST ends and the local day is 25 hours long, so
      // adding 24h of milliseconds to an instant would land back on the 25th.
      const sunset = new Date(2026, 9, 25, 16, 45);
      const forecastsByDate = new Map([
        ['2026-10-25', { sunset: { solarEventTime: toBackendFormat(sunset) } }],
      ]);
      const loc = { name: 'Test', lat: 55, lon: -1.7, forecastsByDate, locationType: ['LANDSCAPE'] };

      expect(computeAutoSelection([loc], new Date(2026, 9, 25, 20)))
        .toEqual({ date: '2026-10-26', eventType: 'SUNRISE' });
    });
  });

  describe('uses earliest sunset across multiple locations', () => {
    /** Two landscape locations on TODAY with the given sunset instants. */
    function twoLocations(earlier, later) {
      return [
        { name: 'A', lat: 55, lon: -1, locationType: ['LANDSCAPE'],
          forecastsByDate: new Map([[TODAY, { sunset: { solarEventTime: toBackendFormat(earlier) } }]]) },
        { name: 'B', lat: 56, lon: -2, locationType: ['LANDSCAPE'],
          forecastsByDate: new Map([[TODAY, { sunset: { solarEventTime: toBackendFormat(later) } }]]) },
      ];
    }

    it('flips to tomorrow once the EARLIEST sunset plus buffer has passed', () => {
      const earlier = ukAt(17, 30);
      const now = new Date(earlier.getTime() + 35 * 60 * 1000); // past earlier + buffer, before later

      expect(computeAutoSelection(twoLocations(earlier, ukAt(19)), now))
        .toEqual({ date: TOMORROW, eventType: 'SUNRISE' });
    });

    it('stays on today while within the earliest sunset buffer', () => {
      const earlier = ukAt(17, 30);
      const now = new Date(earlier.getTime() + 10 * 60 * 1000);

      expect(computeAutoSelection(twoLocations(earlier, ukAt(19)), now))
        .toEqual({ date: TODAY, eventType: 'SUNSET' });
    });
  });

  describe('WILDLIFE locations are excluded', () => {
    it('ignores a WILDLIFE sunset when choosing the reference time', () => {
      // Wildlife has an early sunset, landscape has none: the answer must be null rather than the
      // wildlife time, because wildlife locations are not sky-colour subjects.
      const wildlife = makeLocation({ sunsetTime: toBackendFormat(ukAt(17)), locationType: ['WILDLIFE'] });
      const landscape = makeLocation({ sunsetTime: null, locationType: ['LANDSCAPE'] });

      expect(computeAutoSelection([wildlife, landscape], ukAt(14))).toBeNull();
    });

    it('uses the non-wildlife sunset when both kinds are present', () => {
      const wildlife = makeLocation({ sunsetTime: toBackendFormat(ukAt(17)), locationType: ['WILDLIFE'] });
      const landscape = makeLocation({ sunsetTime: toBackendFormat(ukAt(20)), locationType: ['LANDSCAPE'] });

      expect(computeAutoSelection([wildlife, landscape], ukAt(14)))
        .toEqual({ date: TODAY, eventType: 'SUNSET' });
    });
  });
});
