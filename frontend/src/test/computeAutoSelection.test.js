import { describe, it, expect } from 'vitest';
import { computeAutoSelection } from '../utils/conversions.js';

/**
 * Builds a minimal location object with a sunset time for today's date.
 */
function makeLocation({ sunsetTime, locationType = ['LANDSCAPE'] } = {}) {
  const todayStr = new Date().toLocaleDateString('en-CA');
  const forecastsByDate = new Map();
  if (sunsetTime) {
    forecastsByDate.set(todayStr, { sunset: { solarEventTime: sunsetTime } });
  }
  return { name: 'Test', lat: 55, lon: -1.7, forecastsByDate, locationType };
}

/** Returns a Date at hh:mm today in local time. */
function todayAt(hour, minute = 0) {
  const d = new Date();
  d.setHours(hour, minute, 0, 0);
  return d;
}

/** Returns today's date string YYYY-MM-DD in local time. */
function todayStr() {
  return new Date().toLocaleDateString('en-CA');
}

/** Returns tomorrow's date string YYYY-MM-DD in local time. */
function tomorrowStr() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return d.toLocaleDateString('en-CA');
}

describe('computeAutoSelection', () => {
  describe('returns null when no usable data', () => {
    it('returns null for empty locations array', () => {
      expect(computeAutoSelection([], new Date())).toBeNull();
    });

    it('returns null when no location has today sunset data', () => {
      const loc = makeLocation({ sunsetTime: null });
      expect(computeAutoSelection([loc], new Date())).toBeNull();
    });

    it('returns null when all locations are WILDLIFE (no sunset data used)', () => {
      const loc = makeLocation({ sunsetTime: todayAt(18, 0).toISOString(), locationType: ['WILDLIFE'] });
      expect(computeAutoSelection([loc], new Date())).toBeNull();
    });
  });

  describe('today + SUNSET when sunset has not passed', () => {
    it('returns today + SUNSET when now is before sunset', () => {
      const sunset = todayAt(20, 0); // 8 PM
      const now = todayAt(14, 0);    // 2 PM — well before
      const loc = makeLocation({ sunsetTime: sunset.toISOString() });
      expect(computeAutoSelection([loc], now)).toEqual({
        date: todayStr(),
        eventType: 'SUNSET',
      });
    });

    it('returns today + SUNSET when now is exactly at sunset', () => {
      const sunset = todayAt(18, 12);
      const loc = makeLocation({ sunsetTime: sunset.toISOString() });
      // now === sunset → still within the 30-min window
      expect(computeAutoSelection([loc], sunset)).toEqual({
        date: todayStr(),
        eventType: 'SUNSET',
      });
    });

    it('returns today + SUNSET when now is 29 min after sunset (within buffer)', () => {
      const sunset = todayAt(18, 0);
      const now = new Date(sunset.getTime() + 29 * 60 * 1000);
      const loc = makeLocation({ sunsetTime: sunset.toISOString() });
      expect(computeAutoSelection([loc], now)).toEqual({
        date: todayStr(),
        eventType: 'SUNSET',
      });
    });
  });

  describe('tomorrow + SUNRISE when sunset + buffer has passed', () => {
    it('returns tomorrow + SUNRISE when now is 31 min after sunset (past buffer)', () => {
      const sunset = todayAt(18, 0);
      const now = new Date(sunset.getTime() + 31 * 60 * 1000);
      const loc = makeLocation({ sunsetTime: sunset.toISOString() });
      expect(computeAutoSelection([loc], now)).toEqual({
        date: tomorrowStr(),
        eventType: 'SUNRISE',
      });
    });

    it('returns tomorrow + SUNRISE well after sunset', () => {
      const sunset = todayAt(18, 0);
      const now = todayAt(22, 0); // 10 PM
      const loc = makeLocation({ sunsetTime: sunset.toISOString() });
      expect(computeAutoSelection([loc], now)).toEqual({
        date: tomorrowStr(),
        eventType: 'SUNRISE',
      });
    });
  });

  describe('uses earliest sunset across multiple locations', () => {
    it('uses the earlier of two sunset times', () => {
      const earlierSunset = todayAt(17, 30);
      const laterSunset = todayAt(19, 0);
      // now is after earlier sunset + buffer but before later sunset
      const now = new Date(earlierSunset.getTime() + 35 * 60 * 1000);

      const todayStr_ = todayStr();
      const loc1 = { name: 'A', lat: 55, lon: -1, locationType: ['LANDSCAPE'],
        forecastsByDate: new Map([[todayStr_, { sunset: { solarEventTime: earlierSunset.toISOString() } }]]) };
      const loc2 = { name: 'B', lat: 56, lon: -2, locationType: ['LANDSCAPE'],
        forecastsByDate: new Map([[todayStr_, { sunset: { solarEventTime: laterSunset.toISOString() } }]]) };

      // Should flip to tomorrow because earliest sunset + buffer is passed
      expect(computeAutoSelection([loc1, loc2], now)).toEqual({
        date: tomorrowStr(),
        eventType: 'SUNRISE',
      });
    });

    it('stays today+SUNSET when now is within earliest sunset buffer', () => {
      const earlierSunset = todayAt(17, 30);
      const laterSunset = todayAt(19, 0);
      const now = new Date(earlierSunset.getTime() + 10 * 60 * 1000); // 10 min after earlier

      const todayStr_ = todayStr();
      const loc1 = { name: 'A', lat: 55, lon: -1, locationType: ['LANDSCAPE'],
        forecastsByDate: new Map([[todayStr_, { sunset: { solarEventTime: earlierSunset.toISOString() } }]]) };
      const loc2 = { name: 'B', lat: 56, lon: -2, locationType: ['LANDSCAPE'],
        forecastsByDate: new Map([[todayStr_, { sunset: { solarEventTime: laterSunset.toISOString() } }]]) };

      expect(computeAutoSelection([loc1, loc2], now)).toEqual({
        date: todayStr(),
        eventType: 'SUNSET',
      });
    });
  });

  describe('WILDLIFE locations are excluded', () => {
    it('ignores sunset from WILDLIFE locations when selecting reference time', () => {
      // Wildlife loc has an early sunset; landscape has none.
      // Should return null (no usable sunset) rather than using wildlife's time.
      const wildlifeLoc = makeLocation({
        sunsetTime: todayAt(17, 0).toISOString(),
        locationType: ['WILDLIFE'],
      });
      const landscapeLoc = makeLocation({ sunsetTime: null, locationType: ['LANDSCAPE'] });
      expect(computeAutoSelection([wildlifeLoc, landscapeLoc], todayAt(14, 0))).toBeNull();
    });

    it('uses non-wildlife location sunset when mixed location types are present', () => {
      const wildlifeLoc = makeLocation({
        sunsetTime: todayAt(17, 0).toISOString(),
        locationType: ['WILDLIFE'],
      });
      const landscapeLoc = makeLocation({
        sunsetTime: todayAt(20, 0).toISOString(),
        locationType: ['LANDSCAPE'],
      });
      const now = todayAt(14, 0);
      expect(computeAutoSelection([wildlifeLoc, landscapeLoc], now)).toEqual({
        date: todayStr(),
        eventType: 'SUNSET',
      });
    });
  });
});
