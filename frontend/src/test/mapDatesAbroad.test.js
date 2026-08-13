/**
 * The map's dates, read from a device that is NOT in the UK.
 *
 * <p>This file exists because a UK-pinned test cannot tell "the UK calendar" from "the browser's
 * calendar" — under `Europe/London` the two are the same string, so every assertion passes either
 * way and proves nothing about which one the code asked for. Only a non-UK zone separates them,
 * and that separation is the entire point of the change these tests guard.
 *
 * <p>The scenario is real rather than hypothetical: this is a UK-only app, so the reader here is a
 * UK photographer abroad, or one whose device is simply on the wrong zone. Their forecast payload
 * is still keyed to `Europe/London`, because that is what the backend's `ForecastHorizon.today`
 * produces. A map that judged those dates on `America/New_York` was a day out **all day** — an
 * unbounded error, where the UTC basis it replaced was wrong by at most an hour.
 *
 * ⚠️ Pinned to `America/New_York` on purpose, and the offset (UTC−4 in August) is what does the
 * work: every instant below is chosen so the New York date and the UK date genuinely differ. Do not
 * "harmonise" this with the other date test files by moving it to Europe/London — that would delete
 * the only coverage of the defect.
 */
process.env.TZ = 'America/New_York';

import { describe, it, expect, vi, afterEach } from 'vitest';
import { ukDateStr, ukDateStrOffset, ukDayOffset, resolveAuroraNight } from '../utils/mapDates.js';
import { computeAutoSelection, formatDateLabel } from '../utils/conversions.js';

/**
 * 21:00 UK on 13 August 2026 — an ordinary UK evening, around sunset, when someone would actually
 * be looking at this app. New York is four hours behind in August (EDT), so 16:00 on the 13th, and
 * both calendars agree on the date; this is the control.
 */
const UK_EVENING = '2026-08-13T20:00:00Z';

/**
 * 02:00 UK on 14 August 2026. New York is on 21:00 of the 13th (EDT, UTC−4), so the two calendars
 * DISAGREE: UK 2026-08-14, New York 2026-08-13. Every headline assertion uses this instant.
 */
const UK_SMALL_HOURS = '2026-08-14T01:00:00Z';

function freeze(iso) {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

afterEach(() => {
  vi.useRealTimers();
});

describe('the zone fixture itself', () => {
  it('really is on a zone that disagrees with the UK, or nothing below proves anything', () => {
    // Guards the guard. If the TZ pin ever stops taking effect, these tests would quietly become
    // duplicates of the UK-pinned ones instead of failing, so the disagreement is asserted outright.
    freeze(UK_SMALL_HOURS);

    expect(new Date().toLocaleDateString('en-CA')).toBe('2026-08-13');
    expect(ukDateStr()).toBe('2026-08-14');
  });
});

describe('ukDateStr abroad', () => {
  it('reads the UK date while the device is still on the previous day', () => {
    freeze(UK_SMALL_HOURS);

    expect(ukDateStr()).toBe('2026-08-14');
  });

  it('agrees with the device during the UK evening, when the two do coincide', () => {
    freeze(UK_EVENING);

    expect(ukDateStr()).toBe('2026-08-13');
    expect(new Date().toLocaleDateString('en-CA')).toBe('2026-08-13');
  });

  it('steps from the UK date, not the device date', () => {
    freeze(UK_SMALL_HOURS);

    expect(ukDateStrOffset(1)).toBe('2026-08-15');
    expect(ukDateStrOffset(-1)).toBe('2026-08-13');
  });
});

describe('relative day labels abroad', () => {
  it('calls the UK today "Today", not the device today', () => {
    // The visible symptom this fixes: the date strip marks a chip today with `ukDateStr` and then
    // calls `formatDateLabel` for every other chip. On two bases, 13 and 14 August could BOTH have
    // read "Today" on the same strip.
    freeze(UK_SMALL_HOURS);

    expect(ukDayOffset('2026-08-14')).toBe(0);
    expect(formatDateLabel('2026-08-14')).toBe('Today');
  });

  it('calls the UK yesterday neither Today nor Tomorrow', () => {
    freeze(UK_SMALL_HOURS);

    expect(ukDayOffset('2026-08-13')).toBe(-1);
    expect(formatDateLabel('2026-08-13')).not.toBe('Today');
    expect(formatDateLabel('2026-08-13')).not.toBe('Tomorrow');
  });

  it('calls the UK tomorrow "Tomorrow"', () => {
    freeze(UK_SMALL_HOURS);

    expect(formatDateLabel('2026-08-15')).toBe('Tomorrow');
  });
});

describe('computeAutoSelection abroad', () => {
  /** A landscape location whose sunset sits on the given UK date. */
  function locationWithSunsetOn(ukDate, utcInstant) {
    return {
      name: 'Test',
      lat: 55,
      lon: -1.7,
      locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[ukDate, { sunset: { solarEventTime: utcInstant } }]]),
    };
  }

  it('looks up the forecast under the UK date the backend keyed it to', () => {
    // The payload has no 2026-08-13 entry at this instant — only the UK's today, the 14th. Reading
    // `forecastsByDate` on the device calendar found nothing and returned null, so the map fell
    // back to its plain default and the auto-selection silently did not happen.
    freeze(UK_SMALL_HOURS);
    const loc = locationWithSunsetOn('2026-08-14', '2026-08-14T19:45:00');

    expect(computeAutoSelection([loc], new Date())).toEqual({
      date: '2026-08-14',
      eventType: 'SUNSET',
    });
  });

  it('rolls forward once the sunset buffer has passed (control — not zone-discriminating)', () => {
    // ⚠️ NOT load-bearing, and labelled so rather than left to imply otherwise. At 22:00 UK the New
    // York calendar reads the same date, so this assertion passes on the old browser-zone basis too
    // — it survives a full revert. It cannot be fixed at this zone either: the UK and New York
    // calendars diverge only while the UK clock reads 00:00–05:00, and the sunset-plus-buffer
    // branch is unreachable then. Making it discriminating needs a zone EAST of the UK (in
    // Pacific/Auckland, 21:00 BST is 08:00 the next day), i.e. another file. Kept as a plain
    // regression guard on the buffer logic.
    freeze('2026-08-13T21:00:00Z');
    const loc = locationWithSunsetOn('2026-08-13', '2026-08-13T19:00:00');

    expect(computeAutoSelection([loc], new Date())).toEqual({
      date: '2026-08-14',
      eventType: 'SUNRISE',
    });
  });
});

describe('resolveAuroraNight abroad', () => {
  it('still prefers the backend night, which was never a calendar question', () => {
    freeze(UK_SMALL_HOURS);

    expect(resolveAuroraNight({ currentNightDate: '2026-08-13' })).toBe('2026-08-13');
  });

  it('falls back to the UK date rather than the device date', () => {
    freeze(UK_SMALL_HOURS);

    expect(resolveAuroraNight(null)).toBe('2026-08-14');
  });
});
