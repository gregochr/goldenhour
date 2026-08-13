/**
 * Tests for the "has this solar event already happened?" lookup behind the admin run dialog.
 *
 * <p>Pinned to `America/New_York` for one specific reason: `solarEventTime` is a backend
 * `LocalDateTime` and arrives BARE, so `new Date(bare)` reads it as the device's local time. Under
 * the suite's UTC pin that misreading is a no-op and every assertion here would pass on the broken
 * form; four hours west it is a four-hour error, which is larger than the margin between a sunset
 * and the hour after it.
 */
process.env.TZ = 'America/New_York';

import { describe, it, expect } from 'vitest';
import { latestSolarEventTimes, hasEventPassed } from '../utils/solarEventTimes.js';

/**
 * A briefing shaped like the real payload: two days, each with both events, and slots spread
 * across regions and the unregioned bucket. The sunset times deliberately differ by 25 minutes
 * across regions — that spread is the whole point, since the roster spans latitudes.
 */
const BRIEFING = {
  days: [
    {
      date: '2026-06-21',
      eventSummaries: [
        {
          targetType: 'SUNRISE',
          solarEventTime: '2026-06-21T03:35:00',
          regions: [{ slots: [{ solarEventTime: '2026-06-21T03:35:00' }] }],
          unregioned: [{ solarEventTime: '2026-06-21T03:50:00' }],
        },
        {
          targetType: 'SUNSET',
          // The summary's own field is the EARLIEST by design; the Lakes set here, Ayrshire below.
          solarEventTime: '2026-06-21T21:06:00',
          regions: [
            { slots: [{ solarEventTime: '2026-06-21T21:06:00' }] },
            { slots: [{ solarEventTime: '2026-06-21T21:25:00' }] },
          ],
          unregioned: [],
        },
      ],
    },
    {
      date: '2026-06-22',
      eventSummaries: [
        { targetType: 'SUNSET', regions: [{ slots: [{ solarEventTime: '2026-06-22T21:26:00' }] }] },
      ],
    },
  ],
};

describe('the zone fixture itself', () => {
  it('is pinned west of the UK, or the bare-instant assertions below prove nothing', () => {
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/New_York');
  });
});

describe('latestSolarEventTimes', () => {
  it('takes the LATEST across the roster, not the summary\'s own earliest field', () => {
    // The trap this module exists to avoid. `eventSummary.solarEventTime` is 21:06 and looks
    // exactly right; using it would call the event over while the sun was still up in Ayrshire,
    // and a slot wrongly marked past can be neither run knowingly nor deselected.
    const times = latestSolarEventTimes(BRIEFING);

    expect(times.get('2026-06-21|SUNSET').toISOString()).toBe('2026-06-21T21:25:00.000Z');
  });

  it('reads bare backend instants as UTC rather than as the device\'s clock', () => {
    // `new Date('2026-06-21T21:25:00')` on this zone is 01:25Z the NEXT day — four hours adrift,
    // and on the wrong side of midnight.
    const times = latestSolarEventTimes(BRIEFING);

    expect(times.get('2026-06-21|SUNSET').toISOString()).toBe('2026-06-21T21:25:00.000Z');
    expect(new Date('2026-06-21T21:25:00').toISOString()).toBe('2026-06-22T01:25:00.000Z');
  });

  it('counts unregioned slots, which can hold the latest of the day', () => {
    const times = latestSolarEventTimes(BRIEFING);

    expect(times.get('2026-06-21|SUNRISE').toISOString()).toBe('2026-06-21T03:50:00.000Z');
  });

  it('keys each day separately', () => {
    const times = latestSolarEventTimes(BRIEFING);

    expect(times.get('2026-06-22|SUNSET').toISOString()).toBe('2026-06-22T21:26:00.000Z');
  });

  it('makes no entry for an event whose slots carry no times', () => {
    const times = latestSolarEventTimes({
      days: [{ date: '2026-06-21', eventSummaries: [{ targetType: 'SUNSET', regions: [{ slots: [{}] }] }] }],
    });

    expect(times.has('2026-06-21|SUNSET')).toBe(false);
  });

  it('returns an empty map for a null, empty or malformed payload', () => {
    // The degrade path, and the one the caller depends on: no entry means "make no claim".
    expect(latestSolarEventTimes(null).size).toBe(0);
    expect(latestSolarEventTimes({}).size).toBe(0);
    expect(latestSolarEventTimes({ days: [] }).size).toBe(0);
    expect(latestSolarEventTimes({ days: [{ eventSummaries: [] }] }).size).toBe(0);
    expect(latestSolarEventTimes({ days: [{ date: '2026-06-21', eventSummaries: [{}] }] }).size).toBe(0);
  });
});

describe('hasEventPassed', () => {
  const times = latestSolarEventTimes(BRIEFING);

  it('is false one minute before the latest event, while the sun is still up somewhere', () => {
    expect(hasEventPassed(times, '2026-06-21', 'SUNSET', new Date('2026-06-21T21:24:00Z'))).toBe(false);
  });

  it('is false AT the event instant — past means past, not reached', () => {
    expect(hasEventPassed(times, '2026-06-21', 'SUNSET', new Date('2026-06-21T21:25:00Z'))).toBe(false);
  });

  it('is true one minute after', () => {
    expect(hasEventPassed(times, '2026-06-21', 'SUNSET', new Date('2026-06-21T21:26:00Z'))).toBe(true);
  });

  it('is still false after the EARLIEST region has set, which a summary read would have called past', () => {
    // 21:10 is past the Lakes' 21:06 and before Ayrshire's 21:25. This is the defect in one line.
    expect(hasEventPassed(times, '2026-06-21', 'SUNSET', new Date('2026-06-21T21:10:00Z'))).toBe(false);
  });

  it('makes no claim when the date is unknown, however late the hour', () => {
    // The safe direction: an unknown event is never past, so the slot stays selectable and the
    // backend's own already-past gate decides.
    expect(hasEventPassed(times, '2026-12-25', 'SUNSET', new Date('2026-12-25T23:59:00Z'))).toBe(false);
  });

  it('makes no claim when there are no times at all', () => {
    expect(hasEventPassed(null, '2026-06-21', 'SUNSET', new Date('2026-06-21T23:59:00Z'))).toBe(false);
    expect(hasEventPassed(new Map(), '2026-06-21', 'SUNSET', new Date('2026-06-21T23:59:00Z'))).toBe(false);
  });

  it('does not confuse the two event types on the same day', () => {
    const afterSunriseBeforeSunset = new Date('2026-06-21T12:00:00Z');

    expect(hasEventPassed(times, '2026-06-21', 'SUNRISE', afterSunriseBeforeSunset)).toBe(true);
    expect(hasEventPassed(times, '2026-06-21', 'SUNSET', afterSunriseBeforeSunset)).toBe(false);
  });
});
