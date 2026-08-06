import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import {
  ANY_TIER_ID,
  REACH_LENS_KEY,
  REACH_TIERS,
  WEEKDAY_TIER_ID,
  WEEKEND_TIER_ID,
  defaultTierIdFor,
  formatLensReadout,
  gateSpotsByReach,
  isFarSpot,
  isWeekend,
  readStoredTierId,
  tierById,
  writeStoredTierId,
} from '../utils/reachLens.js';

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
    localStorage.setItem(REACH_LENS_KEY, '{not json');
    expect(readStoredTierId(TUESDAY)).toBeNull();
  });

  it('returns null when the stamp is not today, and does not write the expiry back', () => {
    // Plan §5: "reach expires at the day roll". A read that re-persisted the expired value would
    // turn a today-only setting into a permanent one the moment the page was opened.
    localStorage.setItem(REACH_LENS_KEY, JSON.stringify({ reach: '150', reachDay: '2026-08-03' }));

    expect(readStoredTierId(TUESDAY)).toBeNull();
    expect(JSON.parse(localStorage.getItem(REACH_LENS_KEY)))
      .toEqual({ reach: '150', reachDay: '2026-08-03' });
  });

  it('returns null for a tier id this build no longer has', () => {
    localStorage.setItem(REACH_LENS_KEY, JSON.stringify({ reach: '999', reachDay: TUESDAY }));
    expect(readStoredTierId(TUESDAY)).toBeNull();
  });

  it('returns a choice made today', () => {
    localStorage.setItem(REACH_LENS_KEY, JSON.stringify({ reach: '150', reachDay: TUESDAY }));
    expect(readStoredTierId(TUESDAY)).toBe('150');
  });

  it('writes the tier with the day it was chosen on', () => {
    writeStoredTierId('90', TUESDAY);
    expect(JSON.parse(localStorage.getItem(REACH_LENS_KEY)))
      .toEqual({ reach: '90', reachDay: TUESDAY });
  });

  it('keeps fields it does not own, because P11 shares this key under a different expiry', () => {
    // The rating floor "persists" where reach "expires at the day roll" (plan §5), so a blind
    // overwrite here would silently drop a setting with a different policy.
    localStorage.setItem(REACH_LENS_KEY, JSON.stringify({ rating: 3, reach: '45', reachDay: '2026-08-01' }));
    writeStoredTierId('150', TUESDAY);

    expect(JSON.parse(localStorage.getItem(REACH_LENS_KEY)))
      .toEqual({ rating: 3, reach: '150', reachDay: TUESDAY });
  });

  it('overwrites a corrupt existing value rather than refusing to store', () => {
    localStorage.setItem(REACH_LENS_KEY, 'garbage');
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

describe('reachLens — the readout', () => {
  const base = { tierLabel: '45 min', isDefault: true, weekend: false, spotCount: 34, windowCount: 6 };

  it('names the tier, which day derived it, and how much is drawn', () => {
    expect(formatLensReadout(base)).toBe('45 min · weekday default · 34 spots across 6 windows');
  });

  it('says weekend when the weekend derived it', () => {
    expect(formatLensReadout({ ...base, tierLabel: '2h 30min', weekend: true }))
      .toBe('2h 30min · weekend default · 34 spots across 6 windows');
  });

  it('drops the default clause once the user has chosen something else', () => {
    // The clause is a claim about where the value came from. On a deliberate choice it is false,
    // and the "today only" pill beside the control is what marks that state instead.
    expect(formatLensReadout({ ...base, tierLabel: 'Any', isDefault: false }))
      .toBe('Any · 34 spots across 6 windows');
  });

  it('never says "within reach", whatever the tier', () => {
    // Plan §2.5 rule 2: the word reach belongs to a set that was actually gated, and this number
    // is drawn cards — true with or without a gate, which is exactly why it uses the other word.
    expect(formatLensReadout({ ...base, tierLabel: 'Any', isDefault: false }))
      .not.toMatch(/reach/i);
  });

  it('agrees with itself in the singular', () => {
    expect(formatLensReadout({ ...base, spotCount: 1, windowCount: 1 }))
      .toBe('45 min · weekday default · 1 spot across 1 window');
  });

  it('says nothing about counts when there are no windows to count in', () => {
    // "0 spots across 0 windows" is a true sentence nobody needs, on a page that is already
    // visibly empty.
    expect(formatLensReadout({ ...base, spotCount: 0, windowCount: 0 }))
      .toBe('45 min · weekday default');
  });

  it('still states a window that drew no spots', () => {
    // Distinct from the case above: the window exists and the lens emptied it, which is a fact
    // about the control rather than about the forecast.
    expect(formatLensReadout({ ...base, spotCount: 0, windowCount: 1 }))
      .toBe('45 min · weekday default · 0 spots across 1 window');
  });
});
