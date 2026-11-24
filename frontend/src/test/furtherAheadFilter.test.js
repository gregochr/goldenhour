import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  DEFAULT_FURTHER_AHEAD_FILTER, FURTHER_AHEAD_FILTER_KEY, readFurtherAheadFilter, writeFurtherAheadFilter,
} from '../utils/furtherAheadFilter.js';
import { FILTER_CHIPS } from '../utils/comingUpFeed.js';

/**
 * The remembered Further ahead filter (one-screen-plan.md D7): one chip id under {@code furtherAheadFilter}, read
 * inside {@code try/catch}, validated against the chips, and never a reason for the section to fail.
 */
beforeEach(() => { localStorage.clear(); });
afterEach(() => { vi.restoreAllMocks(); });

describe('furtherAheadFilter', () => {
  it('is stored under furtherAheadFilter, as the plan names it', () => {
    expect(FURTHER_AHEAD_FILTER_KEY).toBe('furtherAheadFilter');
  });

  it('reads All with nothing stored', () => {
    expect(readFurtherAheadFilter()).toBe(DEFAULT_FURTHER_AHEAD_FILTER);
    expect(DEFAULT_FURTHER_AHEAD_FILTER).toBe('all');
  });

  it.each(FILTER_CHIPS.map((chip) => chip.id))('round-trips the %s chip', (id) => {
    writeFurtherAheadFilter(id);
    expect(localStorage.getItem('furtherAheadFilter')).toBe(id);
    expect(readFurtherAheadFilter()).toBe(id);
  });

  it.each([['an unknown chip', 'tides-only'], ['an empty string', ''], ['another case', 'Coastal'], ['a number', '3']])(
    'falls back to All for %s', (_name, stored) => {
      localStorage.setItem('furtherAheadFilter', stored);
      expect(readFurtherAheadFilter()).toBe('all');
    },
  );

  it('falls back to All when storage throws on read (a private window, blocked site data)', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('SecurityError'); });
    expect(readFurtherAheadFilter()).toBe('all');
  });

  it('swallows a failed write: the filter still applies for the visit, it is just not remembered', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('QuotaExceededError'); });
    expect(() => writeFurtherAheadFilter('coastal')).not.toThrow();
  });
});
