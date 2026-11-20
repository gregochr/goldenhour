import { describe, it, expect } from 'vitest';
import { byMeanThenName } from '../utils/windowFirstRegions.js';

/**
 * The region comparator the Map's panel and jump menu share: verdict-eligible first, the served mean best first,
 * the name as the tiebreak.
 */

describe('byMeanThenName', () => {
  const row = (name, meanRating, verdictEligible) => ({ name, meanRating, verdictEligible });

  it('puts an unrated region after a rated one when neither is verdict-eligible', () => {
    expect(byMeanThenName(row('A', null, false), row('B', 3, false))).toBeGreaterThan(0);
    expect(byMeanThenName(row('B', 3, false), row('A', null, false))).toBeLessThan(0);
  });

  it('does not count an eligible region with no mean as eligible', () => {
    // Ineligible-but-rated outranks eligible-but-unrated, from either side of the comparison.
    expect(byMeanThenName(row('C', null, true), row('D', 4, false))).toBeGreaterThan(0);
    expect(byMeanThenName(row('D', 4, false), row('C', null, true))).toBeLessThan(0);
  });

  it('puts the better mean first when both are eligible', () => {
    expect(byMeanThenName(row('A', 2, true), row('B', 4, true))).toBeGreaterThan(0);
    expect(byMeanThenName(row('B', 4, true), row('A', 2, true))).toBeLessThan(0);
  });

  it('puts an eligible region ahead of an ineligible one with a better mean', () => {
    expect(byMeanThenName(row('A', 2, true), row('B', 5, false))).toBeLessThan(0);
    expect(byMeanThenName(row('B', 5, false), row('A', 2, true))).toBeGreaterThan(0);
  });

  it('falls back to the name on an equal mean', () => {
    expect(byMeanThenName(row('B', 3, true), row('A', 3, true))).toBeGreaterThan(0);
    expect(byMeanThenName(row('A', 3, true), row('B', 3, true))).toBeLessThan(0);
  });
});
