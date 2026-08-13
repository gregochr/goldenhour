/**
 * The suite's own timezone, asserted rather than assumed.
 *
 * <p>`setup.js` pins `process.env.TZ = 'UTC'` so the zone is a property of the repository and not of
 * the machine that ran it. That pin is the sort of thing that fails SILENTLY: if it were deleted, or
 * a future Node stopped honouring a runtime change to `process.env.TZ`, every test would go back to
 * reading the developer's own zone and the suite would stay green — on their machine. The whole
 * point is that it also stays green on a machine in New York, and nothing else in the suite would
 * notice the difference.
 *
 * <p>So this file's job is to fail loudly in that case. It is deliberately the only file that
 * asserts the DEFAULT: the five files that pin their own zone are testing something else, and their
 * pins still win (setup files run before a test module is evaluated, so a file-scope assignment is
 * applied second). `mapDatesAbroad.test.js` guards that half already — its "the zone fixture itself"
 * test asserts the New York/UK disagreement outright, so a global pin that defeated a per-file one
 * would fail there rather than turning those tests into duplicates.
 */
import { describe, it, expect } from 'vitest';

describe('the test environment\'s timezone', () => {
  it('is pinned to UTC, so the suite does not read the machine it runs on', () => {
    expect(process.env.TZ).toBe('UTC');
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('UTC');
  });

  it('is in force during BST, when Europe/London and UTC would otherwise disagree', () => {
    // August, so a London-zoned run is UTC+1 and this offset would be -60. The check is worth more
    // than the resolvedOptions one above: it is the arithmetic every date derivation in the app
    // actually goes through, and it separates a genuine pin from a cosmetic label.
    expect(new Date('2026-08-13T12:00:00Z').getTimezoneOffset()).toBe(0);
  });

  it('leaves a bare local format on the UTC calendar, which is what fixtures were written against', () => {
    // The failure this file exists for, in the shape it took: a component formatting an instant with
    // no `timeZone` renders the DEVICE's date. `2026-04-01T00:00:00Z` is "31 Mar 2026" in New York
    // and "1 Apr 2026" here — an assertion written on a UK laptop that passed for the wrong reason.
    expect(new Date('2026-04-01T00:00:00Z').toLocaleDateString('en-GB', {
      day: 'numeric', month: 'short', year: 'numeric',
    })).toBe('1 Apr 2026');
  });
});
