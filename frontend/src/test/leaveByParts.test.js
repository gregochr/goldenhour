import { describe, it, expect } from 'vitest';
import { SETUP_MINUTES, leaveBy, leaveByParts } from '../utils/leaveBy.js';

/**
 * The departure WITH its day — the half of leave-by that only the P8 location sheet needs.
 *
 * <p>Every instant is an explicit UTC literal, in the bare shape the backend serialises a
 * {@code LocalDateTime} in. The suite runs on UTC (`setup.js`), so a BST fixture is what separates
 * "the UK clock" from "the runner's clock"; the device-zone separation is `leaveByAbroad.test.js`,
 * which carries this function too — and has to, because a day label read on the wrong calendar is
 * the exact defect this function exists to prevent.
 *
 * <p><b>What breaks if these fail:</b> the sheet prints a departure time on the wrong day, or
 * prints no day at all for a drive that wraps past midnight — which is a reader setting an alarm
 * for the wrong night after a four-hour drive.
 */

/** 04:40 BST on 14 August 2026 — the plan's §4.6 sunrise, as its UTC instant. */
const AUGUST_SUNRISE = '2026-08-14T03:40:00';

/** 21:11 BST on 14 August 2026 — an ordinary August sunset, in UTC. */
const AUGUST_SUNSET = '2026-08-14T20:11:00';

/** 08:10 GMT on 14 January 2026 — a winter sunrise, where UK time and UTC agree. */
const JANUARY_SUNRISE = '2026-01-14T08:10:00';

describe('leaveByParts', () => {
  it('answers the same clock time leaveBy does, across the cases that separate them', () => {
    // The two share one subtraction on purpose (`departure`), and this is the pin that keeps them
    // sharing it: a second copy of the arithmetic would drift on exactly one of these rows and
    // nothing else in the suite compares them. The wrap row is the one that matters most — it is
    // where a UTC-formatted copy would answer 23:35 against this one's 00:35.
    const cases = [
      [AUGUST_SUNSET, 66],
      [AUGUST_SUNRISE, 0],
      [JANUARY_SUNRISE, 66],
      [AUGUST_SUNRISE, 225],
      [AUGUST_SUNSET, 600],
    ];
    for (const [event, drive] of cases) {
      expect(leaveByParts(event, drive).time).toBe(leaveBy(event, drive));
    }
  });

  it('names no day for an ordinary drive that stays on the event\'s own day', () => {
    // 21:11 BST − 1h6 − 20 min = 19:45, same UK day. `dayWord` is the sheet's branch, so a
    // regression that marked every row would show up here rather than only on the wrap.
    const parts = leaveByParts(AUGUST_SUNSET, 66);
    expect(parts.time).toBe('19:45');
    expect(parts.date).toBe('2026-08-14');
    expect(parts.sameDay).toBe(true);
  });

  it('names the PREVIOUS day when the drive wraps past UK midnight', () => {
    // 04:40 BST sunrise = 03:40 UTC. 3h45 drive + 20 min setup leaves at 23:35 UTC on the 13th,
    // which is 00:35 on the reader's clock — still the 14th. So this drive does NOT wrap, and is
    // the control for the one below: the wrap needs the departure to cross UK midnight, not UTC's.
    const near = leaveByParts(AUGUST_SUNRISE, 225);
    expect(near.time).toBe('00:35');
    expect(near.sameDay).toBe(true);

    // 4h20 + 20 min = 4h40 back from 04:40 BST lands at 00:00 BST — the boundary itself, and still
    // the 14th. One minute more crosses it.
    expect(leaveByParts(AUGUST_SUNRISE, 260).time).toBe('00:00');
    expect(leaveByParts(AUGUST_SUNRISE, 260).sameDay).toBe(true);

    const wrapped = leaveByParts(AUGUST_SUNRISE, 261);
    expect(wrapped.time).toBe('23:59');
    expect(wrapped.date).toBe('2026-08-13');
    expect(wrapped.sameDay).toBe(false);
  });

  it('measures the day on the UK calendar, not on UTC\'s', () => {
    // The single most valuable row here. This departure is 23:35 UTC on the 13th and 00:35 BST on
    // the 14th: a `sameDay` computed from UTC dates would answer false and the sheet would print
    // "Thu 00:35" over a Friday sunrise the departure is genuinely on. Under GMT the two calendars
    // agree, which is why the fixture is deliberately in BST.
    const parts = leaveByParts(AUGUST_SUNRISE, 225);
    expect(new Date(`${AUGUST_SUNRISE}Z`).getTime() - (225 + SETUP_MINUTES) * 60000)
      .toBe(new Date('2026-08-13T23:35:00Z').getTime());
    expect(parts.sameDay).toBe(true);
  });

  it('wraps two days back when the drive is long enough, without special-casing one day', () => {
    // Not reachable from any current payload, and asserted anyway: `sameDay` is a comparison rather
    // than a "minus one day" rule, so a 30-hour drive names the 12th and not a wrong 13th. The
    // sheet's marker is derived from `date`, so this is the property that keeps it honest if a
    // future origin puts a genuinely distant base on the roster.
    const parts = leaveByParts(AUGUST_SUNRISE, 30 * 60);
    expect(parts.date).toBe('2026-08-12');
    expect(parts.sameDay).toBe(false);
  });

  it('is null on exactly the terms leaveBy is null', () => {
    // Four absences, and each one means something different on screen (see `leaveBy`'s Javadoc);
    // all four are silence rather than a guess, and the sheet renders no departure line at all.
    for (const bad of [null, undefined, NaN, -1]) {
      expect(leaveByParts(AUGUST_SUNSET, bad)).toBeNull();
      expect(leaveBy(AUGUST_SUNSET, bad)).toBeNull();
    }
    expect(leaveByParts(null, 60)).toBeNull();
    expect(leaveByParts('not a time', 60)).toBeNull();
  });

  it('keeps the setup term, and takes a caller\'s override', () => {
    // The same two rows `leaveBy.test.js` pins, because the shared helper is where the term is
    // applied — a refactor that dropped it from one path would be invisible to the other's tests.
    expect(leaveByParts(AUGUST_SUNSET, 66, 0).time).toBe('20:05');
    expect(leaveByParts(AUGUST_SUNRISE, 0).time).toBe('04:20');
  });
});
