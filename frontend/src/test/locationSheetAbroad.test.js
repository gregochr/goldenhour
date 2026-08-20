/**
 * The location sheet's DAY words, read on a device east of the UK.
 *
 * <p>⚠️ Pinned to `Pacific/Auckland`, and the direction is the whole point. Every date the sheet
 * names is keyed at **noon UTC** so that no offset can drag it onto the neighbouring day — but noon
 * UTC is only safe up to ±12 hours, and Auckland is UTC+12 in August. So a `toLocaleDateString`
 * that lost its explicit `timeZone: 'UTC'` returns the FOLLOWING day here, and the two places the
 * sheet names a day would both be wrong: the row's date box, and — the one that matters — the
 * departure's day word, which exists precisely to stop a reader setting an alarm for the wrong
 * night after a four-hour drive.
 *
 * <p>The suite's own pin is UTC, where the two answers are identical; `leaveByAbroad.test.js` is
 * pinned to `America/New_York`, which is UTC−4 in August and therefore ALSO identical at noon. The
 * project's standards name this exact asymmetry: "`TZ=Pacific/Auckland` caught one of these that
 * `TZ=America/New_York` did not, because a zone east of the UK fails in the opposite direction."
 * Do not harmonise this file with either of the other two.
 */
process.env.TZ = 'Pacific/Auckland';

import { describe, it, expect } from 'vitest';
import { buildLocationSheet, buildScoreIndex, buildSlotIndex } from '../utils/locationSheet.js';

/** Friday 14 August 2026, sunset — Bamburgh's own 19:41 UTC = 20:41 BST. */
const CARD = {
  key: '2026-08-14:SUNSET',
  date: '2026-08-14',
  targetType: 'SUNSET',
  dow: 'Fri',
  sunrise: false,
  label: 'Tonight Sunset',
  time: '20:37',
  verdictLabel: 'Worth it',
  confidence: 'high',
  away: false,
};

/** Saturday 15 August 2026, sunrise — 04:38 UTC = 05:38 BST. The one a long drive wraps off. */
const SUNRISE = {
  ...CARD,
  key: '2026-08-15:SUNRISE',
  date: '2026-08-15',
  targetType: 'SUNRISE',
  dow: 'Sat',
  sunrise: true,
  label: 'Tomorrow Sunrise',
  time: '05:38',
};

const SPOT = { id: 7, name: 'Bamburgh', regionName: 'Northumberland' };

const SLOTS = buildSlotIndex([
  {
    date: '2026-08-14',
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{ confidence: 'high', slots: [{ locationId: 7, solarEventTime: '2026-08-14T19:41:00' }] }],
    }],
  },
  {
    date: '2026-08-15',
    eventSummaries: [{
      targetType: 'SUNRISE',
      regions: [{ confidence: 'high', slots: [{ locationId: 7, solarEventTime: '2026-08-15T04:38:00' }] }],
    }],
  },
]);

const build = (windows, driveMinutes) => buildLocationSheet(SPOT, windows, {
  scoreIndex: buildScoreIndex([
    { locationId: 7, date: '2026-08-14', targetType: 'SUNSET', rating: 3, summary: 'x' },
    { locationId: 7, date: '2026-08-15', targetType: 'SUNRISE', rating: 4, summary: 'y' },
  ]),
  slotIndex: SLOTS,
  scoresKnown: true,
  reachById: new Map([[7, { driveMinutes }]]),
  todayStr: '2026-08-14',
});

describe('the zone fixture itself', () => {
  it('really is pinned east of the UK by more than twelve hours, or nothing below proves anything', () => {
    // ⚠️ Asserted outright, and it has to be: every function under test names `Europe/London` or
    // `UTC` explicitly, so all of them return the SAME strings under this pin as under the suite's
    // default. Every assertion in this file would pass with the pin gone, and the file would decay
    // into a duplicate without ever failing. Same form of guard as `jobRunSlotDatesAbroad`.
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('Pacific/Auckland');
    // And the offset is what does the work: noon UTC on the 14th is already the 15th here, which is
    // the exact state that makes a zone-less `toLocaleDateString` answer the wrong day.
    expect(new Date('2026-08-14T12:00:00Z').toLocaleDateString('en-GB', { day: 'numeric' })).toBe('15');
  });
});

describe('the sheet\'s day words on a device abroad', () => {
  it('dates the row box on the UK calendar, not the device\'s', () => {
    const sheet = build([CARD], 66);
    expect(sheet.rows[0].dayNum).toBe('14');
    expect(sheet.rows[0].dow).toBe('Fri');
  });

  it('⚠️ names the DEPARTURE\'s day on the UK calendar', () => {
    // 05:38 BST sunrise − 5h30 − 20 min = 23:48 on Friday the 14th. The day word is derived from
    // that UK date; read on this device's calendar the same date renders as Saturday, so the sheet
    // would tell a reader to leave "Sat 23:48" for a Saturday sunrise — twenty-four hours late,
    // which is the failure the marker exists to prevent.
    const sheet = build([SUNRISE], 330);
    expect(sheet.rows[0].leave.time).toBe('23:48');
    expect(sheet.rows[0].leave.date).toBe('2026-08-14');
    expect(sheet.rows[0].leave.dayWord).toBe('Fri');
    // The device's own answer for the same date, which is what a lost `timeZone` would print.
    expect(new Date('2026-08-14T12:00:00Z').toLocaleDateString('en-GB', { weekday: 'short' }))
      .toBe('Sat');
  });

  it('still marks nothing for a departure that stays on the event\'s UK day', () => {
    const sheet = build([SUNRISE], 66);
    expect(sheet.rows[0].leave.time).toBe('04:12');
    expect(sheet.rows[0].leave.dayWord).toBeNull();
  });

  it('reads the UK clock for the row\'s own event time', () => {
    // 19:41 UTC is 20:41 in Britain and 07:41 the next morning here.
    expect(build([CARD], 66).rows[0].time).toBe('20:41');
  });
});
