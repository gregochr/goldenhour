import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import {
  ANY_TIER_ID,
  PLAN_REACH_KEY,
  REACH_TIERS,
  WEEKDAY_TIER_ID,
  WEEKEND_TIER_ID,
  defaultTierIdFor,
  formatLensCount,
  gateSpotsByReach,
  isFarSpot,
  isWeekend,
  readStoredTierId,
  spotPassesReach,
  tierById,
  writeStoredTierId,
} from '../utils/reachLens.js';
import { formatDriveDurationCompact } from '../utils/briefingDisplay.js';

/** 2026-08-04 is a Tuesday; 2026-08-08 a Saturday; 2026-08-09 a Sunday; 2026-08-07 a Friday. */
const TUESDAY = '2026-08-04';
const FRIDAY = '2026-08-07';
const SATURDAY = '2026-08-08';
const SUNDAY = '2026-08-09';

const spot = (driveMinutes, name = 'Bamburgh') => ({ locationName: name, driveMinutes });

describe('reachLens — the tiers', () => {
  it('labels every tier with the threshold it actually gates at', () => {
    // THE defect this phase can most damage a user with: the design's own mock labels a tier
    // "45 min" and gates it at 30 (`Plan Window First v2.html:457` against `:494`), so a chip
    // reading 45 hides a 40-minute drive. The label is derived from the threshold precisely so no
    // second number exists to drift — this pins the pairing rather than either half.
    expect(REACH_TIERS.map((t) => [t.limitMinutes, t.label])).toEqual([
      [45, '45 min'],
      [90, '1h 30min'],
      [150, '2h 30min'],
      [null, 'Any'],
    ]);
  });

  it('derives every tier\'s shortLabel from its own threshold, and gives Any none', () => {
    // Iterated, never a hand table: a retuned tier must carry its own compact form with it.
    for (const tier of REACH_TIERS) {
      expect(tier.shortLabel).toBe(formatDriveDurationCompact(tier.limitMinutes));
    }
    expect(REACH_TIERS.map((t) => t.shortLabel)).toEqual(['45m', '1h30', '2h30', null]);
  });

  it('gives the widest tier no threshold at all, rather than a very large one', () => {
    // A null limit short-circuits `gateSpotsByReach` — no arithmetic runs, so "Any" cannot become
    // a gate through a rounding or a unit mistake in some future edit.
    expect(tierById(ANY_TIER_ID).limitMinutes).toBeNull();
  });

  it('orders the tiers tightest first, which is the order they are drawn in', () => {
    expect(REACH_TIERS.slice(0, 3).map((t) => t.limitMinutes)).toEqual([45, 90, 150]);
  });

  it('returns null for a tier id this build does not have', () => {
    // The read path leans on this to reject a value stored by a future build the user rolled back.
    expect(tierById('999')).toBeNull();
    expect(tierById(undefined)).toBeNull();
  });
});

describe('reachLens — the day-derived default', () => {
  it('reads Saturday and Sunday as the weekend', () => {
    expect(isWeekend(SATURDAY)).toBe(true);
    expect(isWeekend(SUNDAY)).toBe(true);
  });

  it('reads Friday and Tuesday as weekdays', () => {
    // Friday is deliberately not the weekend: the bar is one control over windows spanning four
    // days, so a Friday-evening judgement could not be expressed by a single default.
    expect(isWeekend(FRIDAY)).toBe(false);
    expect(isWeekend(TUESDAY)).toBe(false);
  });

  it('treats an unparseable date as a weekday, never as a weekend', () => {
    // An unparseable string is not evidence of a weekend, and the weekday tier is the tighter of
    // the two — the direction that cannot over-claim how far someone said they would drive.
    expect(isWeekend('not-a-date')).toBe(false);
    expect(defaultTierIdFor('not-a-date')).toBe(WEEKDAY_TIER_ID);
  });

  it('defaults a weekday to 45 min and a weekend to 2h 30min', () => {
    expect(defaultTierIdFor(TUESDAY)).toBe(WEEKDAY_TIER_ID);
    expect(tierById(defaultTierIdFor(TUESDAY)).label).toBe('45 min');
    expect(defaultTierIdFor(SATURDAY)).toBe(WEEKEND_TIER_ID);
    expect(tierById(defaultTierIdFor(SATURDAY)).label).toBe('2h 30min');
  });

  it('reads the day at noon UTC, so no timezone can move it', () => {
    // A midnight read would put a UTC-negative host on the previous day and hand a Saturday user
    // the weekday default. Noon has twelve hours of slack in both directions.
    expect(isWeekend(SATURDAY)).toBe(true);
    expect(isWeekend('2026-08-10')).toBe(false); // the Monday immediately after
  });
});

describe('reachLens — persistence', () => {
  beforeEach(() => localStorage.clear());

  it('returns null when nothing has been stored', () => {
    expect(readStoredTierId(TUESDAY)).toBeNull();
  });

  it('returns null when the stored JSON is corrupt', () => {
    localStorage.setItem(PLAN_REACH_KEY, '{not json');
    expect(readStoredTierId(TUESDAY)).toBeNull();
  });

  it('returns null when the stamp is not today, and does not write the expiry back', () => {
    // Plan §5: "reach expires at the day roll". A read that re-persisted the expired value would
    // turn a today-only setting into a permanent one the moment the page was opened.
    localStorage.setItem(PLAN_REACH_KEY, JSON.stringify({ reach: '150', reachDay: '2026-08-03' }));

    expect(readStoredTierId(TUESDAY)).toBeNull();
    expect(JSON.parse(localStorage.getItem(PLAN_REACH_KEY)))
      .toEqual({ reach: '150', reachDay: '2026-08-03' });
  });

  it('returns null for a tier id this build no longer has', () => {
    localStorage.setItem(PLAN_REACH_KEY, JSON.stringify({ reach: '999', reachDay: TUESDAY }));
    expect(readStoredTierId(TUESDAY)).toBeNull();
  });

  it('returns a choice made today', () => {
    localStorage.setItem(PLAN_REACH_KEY, JSON.stringify({ reach: '150', reachDay: TUESDAY }));
    expect(readStoredTierId(TUESDAY)).toBe('150');
  });

  it('writes the tier with the day it was chosen on', () => {
    writeStoredTierId('90', TUESDAY);
    expect(JSON.parse(localStorage.getItem(PLAN_REACH_KEY)))
      .toEqual({ reach: '90', reachDay: TUESDAY });
  });

  it('writes only its own two fields, echoing nothing that was already there', () => {
    // This key holds reach and NOTHING else, so the write is a whole-value overwrite that never
    // reads storage back. The read-modify-write it replaced re-persisted whatever it found —
    // harmless in fact, since nothing else writes here, but it is the shape CodeQL's
    // `js/clear-text-storage-of-sensitive-data` flags: that model has no notion of keys, so a
    // getItem feeding a setItem is a conduit from every other write in the app. P11's rating floor
    // gets its own key, which is also where its different expiry policy belongs.
    localStorage.setItem(
      PLAN_REACH_KEY,
      JSON.stringify({ reach: '45', reachDay: '2026-08-01', smuggled: 'must not survive' }),
    );
    writeStoredTierId('150', TUESDAY);

    expect(JSON.parse(localStorage.getItem(PLAN_REACH_KEY)))
      .toEqual({ reach: '150', reachDay: TUESDAY });
  });

  it('never reads storage on the write path at all', () => {
    // The assertion above would still pass on an implementation that read, filtered and rewrote.
    // What must hold is that no read happens, because the read IS the flagged conduit.
    const getItem = vi.spyOn(Storage.prototype, 'getItem');
    writeStoredTierId('90', TUESDAY);
    expect(getItem).not.toHaveBeenCalled();
    getItem.mockRestore();
  });

  it('overwrites a corrupt existing value rather than refusing to store', () => {
    localStorage.setItem(PLAN_REACH_KEY, 'garbage');
    writeStoredTierId('90', TUESDAY);
    expect(readStoredTierId(TUESDAY)).toBe('90');
  });

  it('survives a storage write that throws', () => {
    // Private browsing and a full quota both throw from setItem. The choice still applies for the
    // session; only its durability is lost.
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    expect(() => writeStoredTierId('90', TUESDAY)).not.toThrow();
    setItem.mockRestore();
  });

  it('survives a storage read that throws', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(readStoredTierId(TUESDAY)).toBeNull();
    getItem.mockRestore();
  });

  afterEach(() => vi.restoreAllMocks());
});

describe('reachLens — the far mark', () => {
  it('marks a drive beyond the default and not one exactly on it', () => {
    // The boundary is the same one `gateSpotsByReach` uses, and inclusively: a 45-minute drive is
    // within "45 min", so it is not far either.
    expect(isFarSpot(44, 45)).toBe(false);
    expect(isFarSpot(45, 45)).toBe(false);
    expect(isFarSpot(46, 45)).toBe(true);
  });

  it('marks nothing when the drive time is unknown', () => {
    // Plan §2.5 rule 1: an absent drive time is unknown, never out of reach. This is the normal
    // first-run state, and marking it far would assert a distance nobody measured.
    expect(isFarSpot(null, 45)).toBe(false);
    expect(isFarSpot(undefined, 45)).toBe(false);
  });

  it('marks nothing when there is no default to measure against', () => {
    expect(isFarSpot(500, null)).toBe(false);
  });
});

describe('reachLens — the gate', () => {
  it('keeps a drive exactly on the threshold and drops the one a minute past it', () => {
    const spots = [spot(44, 'A'), spot(45, 'B'), spot(46, 'C')];
    expect(gateSpotsByReach(spots, 45).map((s) => s.locationName)).toEqual(['A', 'B']);
  });

  it('passes a spot with no drive time through every tier', () => {
    // Rule 1 again, and this is the half that keeps a user with no home postcode from watching the
    // control silently empty the page.
    expect(gateSpotsByReach([spot(null, 'Unknown')], 45)).toHaveLength(1);
  });

  it('returns the input untouched under Any', () => {
    const spots = [spot(500, 'Far'), spot(2, 'Near')];
    expect(gateSpotsByReach(spots, null)).toBe(spots);
  });

  it('preserves the incoming order, which the footer states', () => {
    // `compareSpots` has already ordered these and the footer's sentence names that order — a
    // filter that re-sorted would make the claim false without changing a count.
    const spots = [spot(40, 'Second'), spot(10, 'First'), spot(44, 'Third')];
    expect(gateSpotsByReach(spots, 45).map((s) => s.locationName))
      .toEqual(['Second', 'First', 'Third']);
  });

  it('returns an empty list rather than undefined when everything is gated', () => {
    expect(gateSpotsByReach([spot(200)], 45)).toEqual([]);
  });
});

describe('reachLens — the readout is the count only (screen-spec.md D7)', () => {
  it('says N spots, in the plural', () => {
    expect(formatLensCount({ spotCount: 34, reachedCount: 34, windowCount: 6 }))
      .toEqual({ lead: '34', rest: 'spots' });
  });

  it('agrees with itself in the singular', () => {
    expect(formatLensCount({ spotCount: 1, reachedCount: 1, windowCount: 1 }))
      .toEqual({ lead: '1', rest: 'spot' });
  });

  it('says N of M spots only when the rating floor removed some', () => {
    expect(formatLensCount({ spotCount: 42, reachedCount: 138, windowCount: 6 }))
      .toEqual({ lead: '42', rest: 'of 138 spots' });
    expect(formatLensCount({ spotCount: 0, reachedCount: 1, windowCount: 2 }))
      .toEqual({ lead: '0', rest: 'of 1 spot' });
  });

  it('never says reach, windows, the tier, a default or a rating', () => {
    // The pressed chips beside the count already say them: the line repeats none of it.
    const { lead, rest } = formatLensCount({ spotCount: 42, reachedCount: 138, windowCount: 6 });
    expect(`${lead} ${rest}`).not.toMatch(/reach|window|default|min|★/i);
  });

  it('says a window that drew no spots, and nothing when there are no windows to count in', () => {
    expect(formatLensCount({ spotCount: 0, reachedCount: 0, windowCount: 1 }))
      .toEqual({ lead: '0', rest: 'spots' });
    expect(formatLensCount({ spotCount: 0, reachedCount: 0, windowCount: 0 })).toBeNull();
  });
});

describe('spotPassesReach', () => {
  const at = (driveMinutes, driveStatus = null) => ({ driveMinutes, driveStatus });

  it('passes within the tier and at the boundary, fails beyond', () => {
    expect(spotPassesReach(at(30), 45, true)).toBe(true);
    expect(spotPassesReach(at(45), 45, true)).toBe(true);
    expect(spotPassesReach(at(46), 45, true)).toBe(false);
  });

  it('passes everything under Any', () => {
    expect(spotPassesReach(at(500), null, true)).toBe(true);
    expect(spotPassesReach(at(null, 'UNROUTABLE'), null, true)).toBe(true);
  });

  it('passes an unknown drive, but not an unroutable one for a measured reader', () => {
    expect(spotPassesReach(at(null), 45, true)).toBe(true);
    expect(spotPassesReach(at(null, 'PENDING'), 45, true)).toBe(true);
    expect(spotPassesReach(at(null, 'UNROUTABLE'), 45, true)).toBe(false);
    expect(spotPassesReach(at(null, 'UNROUTABLE'), 45, false)).toBe(true);
  });

  it('agrees with gateSpotsByReach for every case', () => {
    const spots = [at(10), at(45), at(90), at(null), at(null, 'UNROUTABLE')];
    for (const measured of [true, false]) {
      expect(gateSpotsByReach(spots, 45, { measured }))
        .toEqual(spots.filter((s) => spotPassesReach(s, 45, measured)));
    }
  });
});
