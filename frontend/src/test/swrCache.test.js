import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  cacheGeneration,
  readSwrCache,
  storageKey,
  writeSwrCache,
  clearSwrCache,
} from '../utils/swrCache.js';

/**
 * Makes `setItem` reject, spying on whichever object actually owns it.
 *
 * <p>The owner differs by Node major version, and getting it wrong fails silently rather than
 * loudly — which is how the "storage rejects" test below spent its life green without ever
 * exercising a rejection. Under CI's Node 22 the global `localStorage` is jsdom's `Storage`
 * instance and `setItem` lives on `Storage.prototype`; under Node 23+ a built-in global
 * `localStorage` shadows jsdom's and carries its own `setItem`. A spy on the wrong object never
 * intercepts, `writeSwrCache` succeeds, and the assertions quietly describe nothing.
 *
 * <p>Every caller therefore also asserts the spy was actually called, so vacuity is impossible
 * in either environment rather than merely unlikely.
 *
 * @param {string} [name] the DOMException-style error name to throw
 * @returns {import('vitest').MockInstance} the spy, for restore + call assertions
 */
function rejectSetItem(name = 'QuotaExceededError') {
  const owner = Object.prototype.hasOwnProperty.call(localStorage, 'setItem')
    ? localStorage
    : (Object.getPrototypeOf(localStorage) ?? Storage.prototype);
  return vi.spyOn(owner, 'setItem').mockImplementation(() => {
    const err = new Error('exceeded the quota');
    err.name = name;
    throw err;
  });
}

describe('swrCache', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('round-trips a written value', () => {
    writeSwrCache('briefing:PRO_USER', { best: 'Bamburgh', days: [1, 2, 3] });
    expect(readSwrCache('briefing:PRO_USER')).toEqual({ best: 'Bamburgh', days: [1, 2, 3] });
  });

  it('returns null for a missing key', () => {
    expect(readSwrCache('nope')).toBeNull();
  });

  it('retires the other role variant of a namespace rather than keeping both', () => {
    // This deliberately REVERSES what this test asserted before. One user has one role at a time,
    // so a second role variant of the same namespace is an orphan nothing will ever read — and
    // read-triggered eviction can never collect it, because collection requires reading that exact
    // key. Two multi-megabyte orphans exceed the whole iOS budget on their own.
    writeSwrCache('briefing:PRO_USER', { tier: 'pro' });
    writeSwrCache('briefing:LITE_USER', { tier: 'lite' });

    expect(readSwrCache('briefing:PRO_USER')).toBeNull();
    expect(readSwrCache('briefing:LITE_USER')).toEqual({ tier: 'lite' });
  });

  it('does NOT touch a different namespace', () => {
    // The load-bearing half of the sweep: it must retire siblings without evicting the other live
    // cache. Getting this boundary wrong would delete the entry the app is actually using.
    writeSwrCache('forecasts:PRO_USER', { rows: 3 });
    writeSwrCache('briefing:PRO_USER', { tier: 'pro' });

    expect(readSwrCache('forecasts:PRO_USER')).toEqual({ rows: 3 });
    expect(readSwrCache('briefing:PRO_USER')).toEqual({ tier: 'pro' });
  });

  it('retires entries written under a previous key generation', () => {
    // Simulates what a returning user's browser holds after a shape change ships. Written with the
    // raw v1 key, since the module can only produce current-generation keys.
    localStorage.setItem('photocast_swr:briefing:PRO_USER', JSON.stringify({ ts: Date.now(), value: { old: true } }));
    writeSwrCache('forecasts:PRO_USER', { rows: 1 });

    // Swept even though it is a DIFFERENT namespace — a superseded generation is collectable
    // wholesale, unlike a sibling role.
    expect(localStorage.getItem('photocast_swr:briefing:PRO_USER')).toBeNull();
  });

  it('refuses a write whose generation was captured before a clear — the post-logout re-plant', () => {
    // The cross-account leak. `logout` awaits a network round-trip, sweeps, and only THEN clears
    // the token, so there is a window where the sweep has run and the tree is still mounted. A
    // revalidation resolving inside it used to re-plant the previous account's payload under the
    // previous account's role key, right after the cache was supposedly cleared.
    const gen = cacheGeneration();          // captured as the fetch starts
    clearSwrCache();                        // user logs out mid-flight
    const landed = writeSwrCache('briefing:PRO_USER', { secret: 'previous account' }, gen);

    expect(landed).toBe(false);
    expect(readSwrCache('briefing:PRO_USER')).toBeNull();
  });

  it('allows a write whose generation is still current', () => {
    // The guard must not break the ordinary path it sits on.
    const gen = cacheGeneration();
    expect(writeSwrCache('briefing:PRO_USER', { ok: true }, gen)).toBe(true);
    expect(readSwrCache('briefing:PRO_USER')).toEqual({ ok: true });
  });

  it('clears entries from older key generations too, not just the current one', () => {
    // NO intervening writeSwrCache: the write path's own sweep also collects superseded
    // generations, so a write here would remove the legacy key before clearSwrCache ever saw it
    // and the assertion would hold whether or not clearSwrCache scans ROOT. Mutating ROOT->PREFIX
    // in clearSwrCache left the suite green until this was isolated.
    localStorage.setItem('photocast_swr:legacy:ADMIN', JSON.stringify({ ts: Date.now(), value: 1 }));
    localStorage.setItem(storageKey('briefing:ADMIN'), JSON.stringify({ ts: Date.now(), value: 2 }));

    clearSwrCache();

    expect(localStorage.getItem('photocast_swr:legacy:ADMIN')).toBeNull();
    expect(readSwrCache('briefing:ADMIN')).toBeNull();
  });

  it('treats entries older than maxAgeMs as a miss but honours fresh ones', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-07-24T08:00:00Z'));
      writeSwrCache('briefing:PRO_USER', { v: 1 });

      // 6h later, within a 12h window → hit
      vi.setSystemTime(new Date('2026-07-24T14:00:00Z'));
      expect(readSwrCache('briefing:PRO_USER', 12 * 60 * 60 * 1000)).toEqual({ v: 1 });

      // 13h after write, beyond the 12h window → miss
      vi.setSystemTime(new Date('2026-07-24T21:00:00Z'));
      expect(readSwrCache('briefing:PRO_USER', 12 * 60 * 60 * 1000)).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('returns null (not a throw) on corrupt stored JSON', () => {
    localStorage.setItem(storageKey('briefing:PRO_USER'), '{not json');
    expect(readSwrCache('briefing:PRO_USER')).toBeNull();
  });

  it('clearSwrCache removes only prefixed entries, leaving other keys intact', () => {
    writeSwrCache('briefing:PRO_USER', { v: 1 });
    writeSwrCache('briefing:LITE_USER', { v: 2 });
    localStorage.setItem('goldenhour_token', 'keep-me');

    clearSwrCache();

    expect(readSwrCache('briefing:PRO_USER')).toBeNull();
    expect(readSwrCache('briefing:LITE_USER')).toBeNull();
    expect(localStorage.getItem('goldenhour_token')).toBe('keep-me');
  });

  it('write is a no-op (no throw) when storage rejects', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const spy = rejectSetItem();
    try {
      expect(() => writeSwrCache('briefing:PRO_USER', { v: 1 })).not.toThrow();
      expect(spy).toHaveBeenCalled();
    } finally {
      spy.mockRestore();
      warn.mockRestore();
    }
  });

  // ── Quota blindness ────────────────────────────────────────────────────────
  // The two callers write ~1.3 MB (briefing) and a larger forecasts payload. iOS Safari caps
  // localStorage near 5 MB and commonly accounts for it as UTF-16, so the pair can exceed the
  // budget on the device the instant-paint feature exists for. These pin the three behaviours
  // that make that failure survivable and visible instead of silent and sticky.

  it('evicts a stale entry rather than leaving it to occupy the budget', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-07-24T08:00:00Z'));
      writeSwrCache('briefing:PRO_USER', { v: 1 });
      expect(localStorage.getItem(storageKey('briefing:PRO_USER'))).not.toBeNull();

      // 13h later, beyond a 12h window: the read is a miss AND the bytes are released, so the
      // other cache is not starved by an entry this reader has already refused to use.
      vi.setSystemTime(new Date('2026-07-24T21:00:00Z'));
      expect(readSwrCache('briefing:PRO_USER', 12 * 60 * 60 * 1000)).toBeNull();
      expect(localStorage.getItem(storageKey('briefing:PRO_USER'))).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('evicts a corrupt entry on read', () => {
    localStorage.setItem(storageKey('briefing:PRO_USER'), JSON.stringify({ no: 'ts field' }));
    expect(readSwrCache('briefing:PRO_USER')).toBeNull();
    expect(localStorage.getItem(storageKey('briefing:PRO_USER'))).toBeNull();
  });

  it('reports whether the write landed, so a dropped cache is representable', () => {
    expect(writeSwrCache('briefing:PRO_USER', { v: 1 })).toBe(true);

    const spy = rejectSetItem();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      expect(writeSwrCache('briefing:LITE_USER', { v: 2 })).toBe(false);
      expect(spy).toHaveBeenCalled();
      // Observable at all — before this, a quota failure was indistinguishable from a first visit.
      expect(warn).toHaveBeenCalledTimes(1);
      expect(warn.mock.calls[0][0]).toContain('QuotaExceededError');
      expect(warn.mock.calls[0][0]).toContain('briefing:LITE_USER');
    } finally {
      spy.mockRestore();
      warn.mockRestore();
    }
  });

  it('does not leave the previous generation behind when a refresh write fails', () => {
    writeSwrCache('briefing:PRO_USER', { generation: 'old' });

    const spy = rejectSetItem();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      expect(writeSwrCache('briefing:PRO_USER', { generation: 'new' })).toBe(false);
      expect(spy).toHaveBeenCalled();
    } finally {
      spy.mockRestore();
      warn.mockRestore();
    }

    // The caller believes it just refreshed this key. Serving the superseded copy on the next
    // load would be worse than a cold start, and it would keep starving the other cache.
    expect(readSwrCache('briefing:PRO_USER')).toBeNull();
  });

  it('names a non-serialisable value as a caller bug, not a storage condition', () => {
    const cyclic = { a: 1 };
    cyclic.self = cyclic;
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      expect(writeSwrCache('briefing:PRO_USER', cyclic)).toBe(false);
      expect(warn.mock.calls[0][0]).toContain('could not serialise');
    } finally {
      warn.mockRestore();
    }
  });
});
