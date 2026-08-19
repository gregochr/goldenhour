import { describe, it, expect } from 'vitest';
import { SETUP_MINUTES, leaveBy } from '../utils/leaveBy.js';

/**
 * When to leave, on the UK clock.
 *
 * <p>Every instant here is an explicit UTC literal, in the bare shape the backend serialises a
 * {@code LocalDateTime} in — which is what {@code BriefingSlot.solarEventTime} is. The suite runs
 * on UTC (`setup.js`), so a BST fixture is what separates "the UK clock" from "the runner's
 * clock": under GMT the two agree and no assertion here could tell them apart. The bigger
 * separation, a device zone that is neither, is `leaveByAbroad.test.js`.
 */

/** 04:40 BST on 14 August 2026 — the plan's §4.6 sunrise, as its UTC instant. */
const AUGUST_SUNRISE = '2026-08-14T03:40:00';

/** 21:11 BST on 14 August 2026 — an ordinary August sunset, in UTC. */
const AUGUST_SUNSET = '2026-08-14T20:11:00';

/** 08:10 GMT on 14 January 2026 — a winter sunrise, where UK time and UTC agree. */
const JANUARY_SUNRISE = '2026-01-14T08:10:00';

describe('leaveBy', () => {
  it('takes the drive and the setup off the event time', () => {
    // 21:11 BST − 1h6 drive − 20 min setup = 19:45. The arithmetic a reader would otherwise do on
    // a card that already prints "1h 6min", which is the whole reason this line exists.
    expect(leaveBy(AUGUST_SUNSET, 66)).toBe('19:45');
  });

  it('allows twenty minutes to park and set up, not zero', () => {
    // The constant is the difference between the two answers, so a change to it (or a caller that
    // stopped applying it) shows up as a real minute count rather than as a passing test.
    expect(SETUP_MINUTES).toBe(20);
    expect(leaveBy(AUGUST_SUNSET, 66)).toBe('19:45');
    expect(leaveBy(AUGUST_SUNSET, 66, 0)).toBe('20:05');
  });

  it('still allows the setup for a spot with no drive at all', () => {
    // A zero-minute drive is a real reading — a home-adjacent location — and is not the same input
    // as an absent one. It must leave the setup standing rather than collapsing to the event time.
    expect(leaveBy(AUGUST_SUNRISE, 0)).toBe('04:20');
  });

  it('reads the UK clock rather than UTC, all seven months of BST', () => {
    // The leave instant is 18:45 UTC, which is 19:45 in Britain. A formatter that dropped the zone
    // would print 18:45 — an hour early, for more than half the year. That is the defect
    // `formatInstantUk` exists to stop, and under the suite's UTC pin this is the only kind of
    // fixture that can catch it: the January case below returns the same digits either way, which
    // is why the zone's real coverage lives in `leaveByAbroad.test.js` and this is the local guard.
    expect(leaveBy(AUGUST_SUNSET, 66)).toBe('19:45');
    // GMT, where Britain and the runner agree — the control that proves the case above is about
    // the zone and not about the arithmetic.
    expect(leaveBy(JANUARY_SUNRISE, 66)).toBe('06:44');
  });

  it('crosses the UTC midnight without printing the previous day\'s clock', () => {
    // Plan §4.6's own fixture, and the reason it names this one rather than an ordinary drive: the
    // leave instant is 23:35 UTC on the 13th, which is 00:35 on the reader's clock on the 14th.
    // Subtracting first and formatting in UTC gives 23:35 — an hour out AND on the wrong side of
    // midnight. A 2h30 drive off an 05:42 sunrise crosses nothing and would pin neither error.
    expect(leaveBy(AUGUST_SUNRISE, 225)).toBe('00:35');
  });

  it('crosses the UK midnight too, and says so with the time alone', () => {
    // A 5h drive to an 04:40 sunrise genuinely leaves the evening before: 23:20 on the 13th, UK
    // time, under a card headed with the 14th. The arithmetic must survive going negative rather
    // than wrapping to 03:20 — that is what this pins.
    //
    // The bare time is NOT defended on the day being obvious; it is defended on the case being
    // out of reach. A wrap needs a drive longer than the event's hour: over 4h03 for the roster's
    // northernmost sunrise, against a bounded reach tier of 150 minutes and a longest realistic
    // drive across the catalogue of about 2h20. Every wrapping card is also already a `far` card
    // by construction (`isFarSpot` fires far below that). ⚠️ P7 moves the origin, which is exactly
    // the change that turns a 4h drive from impossible into ordinary — revisit the day marker
    // there, not before.
    expect(leaveBy(AUGUST_SUNRISE, 300)).toBe('23:20');
  });

  describe('what it refuses to guess', () => {
    it('says nothing when this user has no drive time', () => {
      // The normal first-run state, with no home postcode saved. Absence means "unknown", never
      // "leave now" and never a departure computed from a distance nobody measured.
      expect(leaveBy(AUGUST_SUNSET, null)).toBeNull();
      expect(leaveBy(AUGUST_SUNSET, undefined)).toBeNull();
    });

    it('says nothing when the drive time is not a number', () => {
      // The descriptor is joined from a payload rather than computed here, so the type is not
      // guaranteed by construction. `'66'` would otherwise concatenate into a nonsense offset.
      expect(leaveBy(AUGUST_SUNSET, '66')).toBeNull();
      expect(leaveBy(AUGUST_SUNSET, NaN)).toBeNull();
      expect(leaveBy(AUGUST_SUNSET, Infinity)).toBeNull();
    });

    it('says nothing for a negative drive rather than a time after the event', () => {
      // "Leave 22:37" for a 21:11 sunset is the one wrong answer that reads like a right one.
      expect(leaveBy(AUGUST_SUNSET, -66)).toBeNull();
    });

    it('says nothing when the slot carries no event time', () => {
      // A briefing cached before the payload carried `solarEventTime`. The card renders no line at
      // all rather than a time derived from a date alone.
      expect(leaveBy(null, 66)).toBeNull();
      expect(leaveBy(undefined, 66)).toBeNull();
      expect(leaveBy('', 66)).toBeNull();
    });

    it('says nothing for an event time it cannot parse', () => {
      expect(leaveBy('not a time', 66)).toBeNull();
      expect(leaveBy(new Date(NaN), 66)).toBeNull();
    });
  });

  it('accepts a Z-suffixed instant as the same moment as a bare one', () => {
    // Two Java types on the other side serialise the same instant two ways, and this field has
    // been both. `parseUtcInstant` reconciles them; the point here is that neither is shifted.
    expect(leaveBy('2026-08-14T20:11:00Z', 66)).toBe(leaveBy(AUGUST_SUNSET, 66));
  });
});
