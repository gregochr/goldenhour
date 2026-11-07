import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

// Loaded afresh in each test so the module-level afterglow constant runs during a test.
let formatTime;
let getEventTime;
let isEventPast;
let isEventTimePast;

beforeEach(async () => {
  vi.resetModules();
  ({ formatTime, getEventTime, isEventPast, isEventTimePast } = await import('../utils/briefingDisplay.js'));
});

afterEach(() => {
  vi.useRealTimers();
});

/** Pins the clock (Date only) to an instant. */
function at(iso) {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

describe('getEventTime — where a time is looked for', () => {
  it('finds a time in an unregioned slot when no region slot has one', () => {
    const es = {
      regions: [{ slots: [{ locationName: 'A' }] }],
      unregioned: [{ locationName: 'B' }, { solarEventTime: '2026-07-16T20:30:00' }],
      solarEventTime: '2026-07-16T21:00:00',
    };
    expect(getEventTime(es)).toBe('2026-07-16T20:30:00');
  });

  it('prefers a region slot over an unregioned one, and an unregioned one over the summary itself', () => {
    expect(getEventTime({
      regions: [{ slots: [{ solarEventTime: '2026-07-16T20:00:00' }] }],
      unregioned: [{ solarEventTime: '2026-07-16T20:30:00' }],
      solarEventTime: '2026-07-16T21:00:00',
    })).toBe('2026-07-16T20:00:00');
    expect(getEventTime({
      regions: [],
      unregioned: [{ solarEventTime: '2026-07-16T20:30:00' }],
      solarEventTime: '2026-07-16T21:00:00',
    })).toBe('2026-07-16T20:30:00');
  });

  it('falls back to the summary\'s own time, then to null', () => {
    expect(getEventTime({ regions: [], unregioned: [{}], solarEventTime: '2026-07-16T21:00:00' }))
      .toBe('2026-07-16T21:00:00');
    expect(getEventTime({})).toBeNull();
  });
});

describe('formatTime', () => {
  it('is empty for a missing or empty instant', () => {
    expect(formatTime(null)).toBe('');
    expect(formatTime('')).toBe('');
    expect(formatTime(undefined)).toBe('');
  });
});

describe('isEventPast — an event with no time, judged from its date', () => {
  const sunrise = { targetType: 'SUNRISE', regions: [], unregioned: [] };
  const sunset = { targetType: 'SUNSET', regions: [], unregioned: [] };

  it('treats a sunrise as gone from noon UK time and not a minute before', () => {
    // 2026-06-15 is British Summer Time: 10:59 UTC is 11:59 UK, and 11:00 UTC is 12:00 UK.
    at('2026-06-15T10:59:00Z');
    expect(isEventPast(sunrise, '2026-06-15')).toBe(false);
    at('2026-06-15T11:00:00Z');
    expect(isEventPast(sunrise, '2026-06-15')).toBe(true);
  });

  it('keeps a same-day sunset current all day, and a past day gone, and a future day ahead', () => {
    at('2026-06-15T22:00:00Z');
    expect(isEventPast(sunset, '2026-06-15')).toBe(false);
    expect(isEventPast(sunset, '2026-06-14')).toBe(true);
    expect(isEventPast(sunset, '2026-06-16')).toBe(false);
  });

  it('is current when the caller cannot say which day it belongs to', () => {
    at('2026-06-15T22:00:00Z');
    expect(isEventPast(sunrise)).toBe(false);
  });
});

describe('isEventTimePast', () => {
  it('is false for a missing time and honours the half-hour afterglow', () => {
    at('2026-07-16T20:45:00Z');
    expect(isEventTimePast(null)).toBe(false);
    expect(isEventTimePast('')).toBe(false);
    expect(isEventTimePast('2026-07-16T20:16:00')).toBe(false);
    expect(isEventTimePast('2026-07-16T20:14:00')).toBe(true);
  });

  it('flips exactly thirty minutes after the event', () => {
    at('2026-07-16T20:30:00Z');
    expect(isEventTimePast('2026-07-16T20:00:00')).toBe(false);
    at('2026-07-16T20:30:00.001Z');
    expect(isEventTimePast('2026-07-16T20:00:00')).toBe(true);
  });
});
