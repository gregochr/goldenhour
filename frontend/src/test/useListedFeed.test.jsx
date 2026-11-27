import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import useListedFeed from '../hooks/useListedFeed.js';

/**
 * What Further ahead lists and what is new in it, derived once (one-screen-plan.md O2 review round 1): the reconciled
 * feed less the entries a lane draws. The jump and the heading both read this, so these cases are the one place the
 * "same number, and only what is listed" rule is pinned.
 */
const BANDS = { list: 5, announce: 7.5, interrupt: 10 };
const arrival = (id, over = {}) => ({
  id, type: 'supermoon', family: 'sun-moon', kind: 'ALMANAC', interim: false, bits: 8.2, enteredWindow: '2026-08-08',
  startDate: '2026-09-01', endDate: '2026-09-01', title: id, ...over,
});
const feed = (entries) => ({ entries, counts: { fixed: entries.length, forecast: 0, byFamily: {} }, bands: BANDS });
const run = (events, graduated = new Set(), seen = '2026-08-01') => renderHook(
  () => useListedFeed(events, graduated, seen),
).result.current;

describe('useListedFeed', () => {
  it('counts every new arrival when nothing graduated, and hands back the served array', () => {
    const events = feed([arrival('a'), arrival('b')]);
    const out = run(events);
    expect(out.badge).toEqual({ band: 'announce', count: 2 });
    expect(out.listedEntries).toBe(events.entries);
    expect(out.sinceEntry.id).toBe('a');
  });

  it('does not count a new entry a lane already draws, which Further ahead does not list', () => {
    const out = run(feed([arrival('a'), arrival('b')]), new Set(['a']));
    expect(out.listedEntries.map((e) => e.id)).toEqual(['b']);
    expect(out.badge).toEqual({ band: 'announce', count: 1 });
    expect(out.sinceEntry.id).toBe('b');
  });

  it('has no badge and no since-line when the only new entry graduated', () => {
    const out = run(feed([arrival('a')]), new Set(['a']));
    expect(out.badge).toBeNull();
    expect(out.sinceEntry).toBeNull();
  });

  it('does not count a Met Office warning entry the poll has not confirmed (withdrawn or not yet polled)', () => {
    const warning = arrival('weather-warning:w1', {
      type: 'weather-warning', family: 'warning', meta: { warningId: 'w1', level: 'YELLOW' },
    });
    const out = run(feed([warning]));
    expect(out.events.entries).toEqual([]);
    expect(out.badge).toBeNull();
  });

  it('is null-safe before the feed arrives and for no graduated set', () => {
    const out = renderHook(() => useListedFeed(null, null, undefined)).result.current;
    expect(out.events).toBeNull();
    expect(out.listedEntries).toBeUndefined();
    expect(out.badge).toBeNull();
  });
});
