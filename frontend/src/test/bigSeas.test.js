import { describe, it, expect, afterEach } from 'vitest';
import { bigSeasFor, bigSeasCopy, bigSeasDayName } from '../utils/bigSeas.js';
import { degreesToCompass, degreesToCompass16 } from '../utils/conversions.js';
import { setRewind } from '../utils/rewind.js';
import { buildMapEvents } from '../utils/mapEvents.js';
import {
  SATURDAY, SUNDAY, MONDAY_NOT_ALIGNED, THURSDAY_MORNING, solarRow, astroRow, weekEvents,
} from './bigSeasFixtures.js';

const DAYS = [SATURDAY, SUNDAY];

function rowOf(events, id) {
  return events.find((r) => r.id === id);
}

describe('bigSeasFor: which day the line speaks of', () => {
  const events = weekEvents();

  it('is the FULL form on a row of the big-seas day itself', () => {
    const sunrise = rowOf(events, 'solar:2026-10-24:SUNRISE');
    const sel = bigSeasFor(DAYS, sunrise, events, THURSDAY_MORNING);
    expect(sel.form).toBe('full');
    expect(sel.day).toBe(SATURDAY);
    expect(sel.aligned).toBe(false);
  });

  it("is the FULL form on that day's night row too", () => {
    const night = rowOf(events, 'astro:2026-10-24:ASTRO');
    const sel = bigSeasFor(DAYS, night, events, THURSDAY_MORNING);
    expect(sel.form).toBe('full');
    expect(sel.day).toBe(SATURDAY);
    expect(sel.aligned).toBe(false);
    expect(sel.jumpRow.id).toBe('solar:2026-10-24:SUNSET');
  });

  it('is COMPACT on an earlier day, naming the first later big-seas day', () => {
    const thursday = rowOf(events, 'solar:2026-10-22:SUNSET');
    const sel = bigSeasFor(DAYS, thursday, events, THURSDAY_MORNING);
    expect(sel.form).toBe('compact');
    expect(sel.day).toBe(SATURDAY);
    expect(sel.aligned).toBe(false);
    expect(sel.jumpRow.id).toBe('solar:2026-10-24:SUNSET');
  });

  it('picks the earliest later day even when the served list is out of order', () => {
    const thursday = rowOf(events, 'solar:2026-10-22:SUNRISE');
    const sel = bigSeasFor([SUNDAY, SATURDAY], thursday, events, THURSDAY_MORNING);
    expect(sel.day).toBe(SATURDAY);
  });

  it('is null after the last big-seas day', () => {
    const monday = rowOf(events, 'solar:2026-10-26:SUNRISE');
    expect(bigSeasFor(DAYS, monday, events, THURSDAY_MORNING).form).toBeNull();
  });

  it('is null on a past row, even on the big-seas day', () => {
    const past = solarRow('2026-10-24', 'SUNRISE', { past: true, served: false });
    expect(bigSeasFor(DAYS, past, events, THURSDAY_MORNING).form).toBeNull();
  });

  it('is null with nothing served, an empty list, or no row on screen', () => {
    const sunrise = rowOf(events, 'solar:2026-10-24:SUNRISE');
    expect(bigSeasFor(undefined, sunrise, events, THURSDAY_MORNING).form).toBeNull();
    expect(bigSeasFor([], sunrise, events, THURSDAY_MORNING).form).toBeNull();
    expect(bigSeasFor(DAYS, null, events, THURSDAY_MORNING).form).toBeNull();
  });

  describe('under a Rewound app clock', () => {
    afterEach(() => setRewind(null));

    it("an elapsed today gives way to the next day's COMPACT line", () => {
      // Saturday's window ended 17:15Z. Rewound to 18:30Z that evening, the Saturday sunrise row
      // (still on screen) must not speak of a window that has gone: Sunday's compact form instead.
      setRewind('2026-10-24T18:30:00Z');
      const sunrise = rowOf(events, 'solar:2026-10-24:SUNRISE');
      const sel = bigSeasFor(DAYS, sunrise, events);
      expect(sel.form).toBe('compact');
      expect(sel.day).toBe(SUNDAY);
      expect(sel.jumpRow.id).toBe('solar:2026-10-25:SUNRISE');
    });

    it('the same row is FULL again when rewound to before the window ends', () => {
      setRewind('2026-10-24T17:00:00Z');
      const sunrise = rowOf(events, 'solar:2026-10-24:SUNRISE');
      expect(bigSeasFor(DAYS, sunrise, events).form).toBe('full');
    });

    it('is null once the last window has gone', () => {
      setRewind('2026-10-25T10:00:00Z');
      const sunday = rowOf(events, 'solar:2026-10-25:SUNSET');
      expect(bigSeasFor(DAYS, sunday, events).form).toBeNull();
    });
  });
});

describe('bigSeasFor: the jump row', () => {
  const events = weekEvents();

  it('is null when the reader is already on the aligned event, and the line is aligned', () => {
    const sunset = rowOf(events, 'solar:2026-10-24:SUNSET');
    const sel = bigSeasFor(DAYS, sunset, events, THURSDAY_MORNING);
    expect(sel.form).toBe('full');
    expect(sel.aligned).toBe(true);
    expect(sel.jumpRow).toBeNull();
  });

  it("is the aligned event's row when the reader is on the other event of the day", () => {
    const sunrise = rowOf(events, 'solar:2026-10-24:SUNRISE');
    expect(bigSeasFor(DAYS, sunrise, events, THURSDAY_MORNING).jumpRow)
      .toBe(rowOf(events, 'solar:2026-10-24:SUNSET'));
  });

  it('is null when the day carries no aligned event', () => {
    const sunrise = rowOf(events, 'solar:2026-10-26:SUNRISE');
    const sel = bigSeasFor([MONDAY_NOT_ALIGNED], sunrise, events, THURSDAY_MORNING);
    expect(sel.form).toBe('full');
    expect(sel.aligned).toBe(false);
    expect(sel.jumpRow).toBeNull();
  });

  it('is null when the list has no such row (index -1), and the line still shows', () => {
    // Sunday has a SUNRISE alignment but this list has no 25 Oct row, as today+3 and +4 may not.
    const short = events.filter((r) => r.date !== '2026-10-25');
    const thursday = rowOf(short, 'solar:2026-10-22:SUNSET');
    const sel = bigSeasFor([SUNDAY], thursday, short, THURSDAY_MORNING);
    expect(sel.form).toBe('compact');
    expect(sel.day).toBe(SUNDAY);
    expect(sel.jumpRow).toBeNull();
  });

  it('is not aligned on the right date but a night row, and jumps to the aligned solar row', () => {
    const night = astroRow('2026-10-25');
    const list = [...events, night];
    const sel = bigSeasFor([SUNDAY], night, list, THURSDAY_MORNING);
    expect(sel.aligned).toBe(false);
    expect(sel.jumpRow.id).toBe('solar:2026-10-25:SUNRISE');
  });
});

describe('bigSeasFor: elapsed rows and the window boundary', () => {
  it('has no jump row when the aligned row has gone past (HW after sunset, read from the night row)', () => {
    const events = weekEvents().map((r) => (
      r.id === 'solar:2026-10-24:SUNSET' ? { ...r, past: true, served: false } : r
    ));
    const night = rowOf(events, 'astro:2026-10-24:ASTRO');
    const sel = bigSeasFor(DAYS, night, events, THURSDAY_MORNING);
    expect(sel.form).toBe('full');
    expect(sel.aligned).toBe(false);
    expect(sel.jumpRow).toBeNull();
  });

  describe('at the end of the window', () => {
    afterEach(() => setRewind(null));

    it('drops Saturday at exactly its windowEnd, and keeps it one second before', () => {
      const events = weekEvents();
      const sunrise = rowOf(events, 'solar:2026-10-24:SUNRISE');
      setRewind('2026-10-24T17:14:59Z');
      expect(bigSeasFor(DAYS, sunrise, events).form).toBe('full');
      setRewind('2026-10-24T17:15:00Z');
      const sel = bigSeasFor(DAYS, sunrise, events);
      expect(sel.form).toBe('compact');
      expect(sel.day).toBe(SUNDAY);
    });
  });
});

describe('bigSeasCopy: the words, verbatim from the handoff', () => {
  const events = weekEvents();
  const sel = (id, days = DAYS) => bigSeasFor(days, rowOf(events, id), events, THURSDAY_MORNING);

  it('FULL, not aligned: no gap clause, the action names the aligned event', () => {
    expect(bigSeasCopy(sel('solar:2026-10-24:SUNRISE'))).toEqual({
      line1: '15:30–18:15 · high water 16:58',
      line2: 'Waves 4.1 m from NNE onto 3 piers and lighthouses · watch from high ground, not the pier',
      action: 'On the sunset ›',
    });
  });

  it('FULL, aligned: the gap clause, no action', () => {
    expect(bigSeasCopy(sel('solar:2026-10-24:SUNSET'))).toEqual({
      line1: '15:30–18:15 · high water 16:58, 49 min before sunset',
      line2: 'Waves 4.1 m from NNE onto 3 piers and lighthouses · watch from high ground, not the pier',
      action: null,
    });
  });

  it('says AFTER for a high water after the event, on the GMT side of the clock change', () => {
    expect(bigSeasCopy(sel('solar:2026-10-25:SUNRISE')).line1)
      .toBe('06:49–09:00 · high water 07:31, 42 min after sunrise');
  });

  it('COMPACT, aligned day: "high water near sunset" and "See Saturday"', () => {
    expect(bigSeasCopy(sel('solar:2026-10-22:SUNSET'))).toEqual({
      line1: 'Saturday 15:30–18:15 · high water near sunset',
      line2: 'Waves 4.1 m from NNE onto 3 piers and lighthouses',
      action: 'See Saturday ›',
    });
  });

  it('COMPACT, day not aligned: the high-water clock time, and no action', () => {
    expect(bigSeasCopy(sel('solar:2026-10-22:SUNSET', [MONDAY_NOT_ALIGNED]))).toEqual({
      line1: 'Monday 10:00–12:30 · high water 11:15',
      line2: 'Waves 3.2 m from N onto 3 piers and lighthouses',
      action: null,
    });
  });

  it("N = 1 names the structure by its label", () => {
    expect(bigSeasCopy(sel('solar:2026-10-25:SUNSET', [SUNDAY])).line2)
      .toBe('Waves 3.4 m from NE onto Roker Pier · watch from high ground, not the pier');
  });

  it('is null when there is no line', () => {
    expect(bigSeasCopy({ form: null, day: null, aligned: false, jumpRow: null })).toBeNull();
    expect(bigSeasCopy(null)).toBeNull();
  });
});

describe('the 16-point compass', () => {
  const NAMES = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];

  it.each(NAMES.map((name, i) => [i * 22.5, name]))('%s degrees is %s', (deg, name) => {
    expect(degreesToCompass16(deg)).toBe(name);
  });

  it('wraps: 360 is N and -22.5 is NNW', () => {
    expect(degreesToCompass16(360)).toBe('N');
    expect(degreesToCompass16(-22.5)).toBe('NNW');
  });

  it('turns over at the half-point: 11.24 is N, 11.25 is NNE (and 348.75 is N, 348.74 NNW)', () => {
    expect(degreesToCompass16(11.24)).toBe('N');
    expect(degreesToCompass16(11.25)).toBe('NNE');
    expect(degreesToCompass16(348.74)).toBe('NNW');
    expect(degreesToCompass16(348.75)).toBe('N');
  });

  it('leaves the 8-point helper alone', () => {
    expect(degreesToCompass(22)).toBe('N');
    expect(degreesToCompass(23)).toBe('NE');
  });
});

describe('bigSeasDayName', () => {
  it('names the weekday of a calendar date, either side of the clock change', () => {
    expect(bigSeasDayName('2026-10-24')).toBe('Saturday');
    expect(bigSeasDayName('2026-10-25')).toBe('Sunday');
  });
});

describe('buildMapEvents stamps the aligned row', () => {
  const window = (date, targetType) => ({
    date, targetType, label: 'Sat 24 Oct', time: '07:00', bestRating: 3,
  });
  const build = (bigSeas, now = undefined) => buildMapEvents({
    solarWindows: [
      window('2026-10-24', 'SUNRISE'), window('2026-10-24', 'SUNSET'),
      window('2026-10-25', 'SUNRISE'), window('2026-10-25', 'SUNSET'),
    ],
    forecastDates: ['2026-10-24', '2026-10-25'],
    todayStr: '2026-10-24',
    tomorrowStr: '2026-10-25',
    bigSeas,
    ...(now ? { now } : {}),
  });

  it("stamps bigSeasAligned only on the row each day's alignedEvent names", () => {
    const rows = build(DAYS, THURSDAY_MORNING);
    expect(rows.filter((r) => r.bigSeasAligned).map((r) => r.id))
      .toEqual(['solar:2026-10-24:SUNSET', 'solar:2026-10-25:SUNRISE']);
  });

  it('stamps nothing for a day that is not aligned, or with nothing served', () => {
    expect(build([MONDAY_NOT_ALIGNED], THURSDAY_MORNING).some((r) => r.bigSeasAligned)).toBe(false);
    expect(build(undefined, THURSDAY_MORNING).some((r) => r.bigSeasAligned)).toBe(false);
  });

  describe('against the app clock', () => {
    afterEach(() => setRewind(null));

    it('stamps nothing for a day whose window has ended, so the bolt and the line agree', () => {
      // Saturday ends 17:15Z. At 18:00Z only Sunday's sunrise still earns a bolt.
      const rows = build(DAYS, new Date('2026-10-24T18:00:00Z'));
      expect(rows.filter((r) => r.bigSeasAligned).map((r) => r.id)).toEqual(['solar:2026-10-25:SUNRISE']);
      // ...and exactly at the end, as the line does.
      const atEnd = build(DAYS, new Date('2026-10-24T17:15:00Z'));
      expect(atEnd.filter((r) => r.bigSeasAligned).map((r) => r.id)).toEqual(['solar:2026-10-25:SUNRISE']);
    });

    it('reads appNow() when no clock is passed, so a Rewind moves it', () => {
      setRewind('2026-10-24T18:00:00Z');
      expect(build(DAYS).filter((r) => r.bigSeasAligned).map((r) => r.id))
        .toEqual(['solar:2026-10-25:SUNRISE']);
    });
  });

  it('never stamps a PAST row, even one whose big-seas window is still open', () => {
    // Saturday's sunset has elapsed (a past row, from pastSolarWindows) while the window runs on
    // past it; the row is not a place to look, so it earns no bolt.
    const now = new Date('2026-10-24T16:30:00Z');
    const rows = buildMapEvents({
      solarWindows: [],
      forecastDates: ['2026-10-24'],
      pastSolarWindows: [{ date: '2026-10-24', targetType: 'SUNSET', earliestEventTime: '2026-10-24T16:00:00' }],
      isEventTimePast: (t) => new Date(`${t}Z`).getTime() < now.getTime(),
      todayStr: '2026-10-24',
      tomorrowStr: '2026-10-25',
      bigSeas: [SATURDAY],
      now,
    });
    const sunset = rows.find((r) => r.id === 'solar:2026-10-24:SUNSET');
    expect(sunset.past).toBe(true);
    expect(sunset.bigSeasAligned).toBeUndefined();
  });
});
