/**
 * Leave-by, read on a device that is NOT in the UK.
 *
 * <p>A UK-pinned suite cannot tell "the UK clock" from "the browser's clock", and the UTC pin this
 * suite runs on only separates them for the seven months of BST — so a formatter that quietly read
 * the device zone would pass every winter assertion in `leaveBy.test.js`. Only a zone that is
 * neither UK nor UTC separates all three, and this is a real reader rather than a hypothetical
 * one: a UK photographer abroad, or one whose laptop is simply on the wrong zone, planning a drive
 * to a UK sunrise. The payload is UK-keyed either way, so the answer must be too.
 *
 * ⚠️ Pinned to `America/New_York`, and the offset is what does the work — UTC−4 in August, UTC−5
 * in January. Do not "harmonise" this with `leaveBy.test.js` by dropping the pin; that would delete
 * the only coverage of the device-zone defect.
 */
process.env.TZ = 'America/New_York';

import { describe, it, expect } from 'vitest';
import { leaveBy } from '../utils/leaveBy.js';

/** 04:40 BST on 14 August 2026 — the plan's §4.6 sunrise, as its UTC instant. */
const AUGUST_SUNRISE = '2026-08-14T03:40:00';

/** 21:11 BST on 14 August 2026, in UTC. */
const AUGUST_SUNSET = '2026-08-14T20:11:00';

/** 08:10 GMT on 14 January 2026, in UTC — the season where the UK and UTC agree. */
const JANUARY_SUNRISE = '2026-01-14T08:10:00';

/** What the device's own clock says about an instant, with no zone named — the wrong answer. */
function onTheDeviceClock(iso) {
  return new Date(iso).toLocaleString('en-GB', {
    hour: '2-digit', minute: '2-digit', day: 'numeric', month: 'short',
  });
}

describe('the zone fixture itself', () => {
  it('really is pinned to a non-UK zone, or nothing below proves anything', () => {
    // ⚠️ Asserted outright rather than through a date disagreement, and that is forced: `leaveBy`
    // names `Europe/London` itself, so it returns the SAME string under this pin and under the
    // suite's UTC default. Every assertion in this file would therefore pass with the pin gone,
    // and the file would decay into a duplicate of `leaveBy.test.js` without ever failing. This is
    // the `jobRunSlotDatesAbroad.test.jsx` form of the guard, chosen for the same reason.
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/New_York');
  });
});

describe('leaveBy on a device abroad', () => {
  it('gives the UK departure time for a UK sunset, not the device\'s own', () => {
    // The leave instant is 18:45 UTC. Britain reads 19:45; this device reads 14:45. A reader in
    // New York planning a drive to Bamburgh needs the first — the drive is in Northumberland.
    expect(leaveBy(AUGUST_SUNSET, 66)).toBe('19:45');
    expect(onTheDeviceClock('2026-08-14T18:45:00Z')).toBe('14 Aug, 14:45');
  });

  it('gives the UK time when the device is not even on the same day', () => {
    // §4.6's wrapping fixture, where all three clocks part company: the leave instant is 23:35 UTC
    // on the 13th, which is 00:35 on the 14th in Britain and 19:35 on the 13th here. The UK answer
    // is the only one a driver could act on, and it is a different date from the device's.
    expect(leaveBy(AUGUST_SUNRISE, 225)).toBe('00:35');
    expect(onTheDeviceClock('2026-08-13T23:35:00Z')).toBe('13 Aug, 19:35');
  });

  it('still reads the UK clock in winter, when the UK and UTC agree and this device does not', () => {
    // The case the UTC-pinned file cannot cover at all: under GMT its own assertions would pass
    // whether the zone were named or dropped, because Britain and the runner are the same clock.
    // Here they are five hours apart, so the zone is doing visible work.
    expect(leaveBy(JANUARY_SUNRISE, 66)).toBe('06:44');
    expect(onTheDeviceClock('2026-01-14T06:44:00Z')).toBe('14 Jan, 01:44');
  });

  it('refuses to guess here exactly as it does at home', () => {
    // The degrade rules are not zone-dependent, and a reader abroad is the likeliest to have no
    // drive times at all — a home postcode is UK-shaped and may never have been saved.
    expect(leaveBy(AUGUST_SUNSET, null)).toBeNull();
    expect(leaveBy(null, 66)).toBeNull();
  });
});
