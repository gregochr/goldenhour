import { describe, it, expect, beforeEach, vi } from 'vitest';
import { readSwrCache, writeSwrCache, clearSwrCache } from '../utils/swrCache.js';

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

  it('keeps entries role-independent', () => {
    writeSwrCache('briefing:PRO_USER', { tier: 'pro' });
    writeSwrCache('briefing:LITE_USER', { tier: 'lite' });
    expect(readSwrCache('briefing:PRO_USER')).toEqual({ tier: 'pro' });
    expect(readSwrCache('briefing:LITE_USER')).toEqual({ tier: 'lite' });
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
    localStorage.setItem('photocast_swr:briefing:PRO_USER', '{not json');
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
      expect(localStorage.getItem('photocast_swr:briefing:PRO_USER')).not.toBeNull();

      // 13h later, beyond a 12h window: the read is a miss AND the bytes are released, so the
      // other cache is not starved by an entry this reader has already refused to use.
      vi.setSystemTime(new Date('2026-07-24T21:00:00Z'));
      expect(readSwrCache('briefing:PRO_USER', 12 * 60 * 60 * 1000)).toBeNull();
      expect(localStorage.getItem('photocast_swr:briefing:PRO_USER')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('evicts a corrupt entry on read', () => {
    localStorage.setItem('photocast_swr:briefing:PRO_USER', JSON.stringify({ no: 'ts field' }));
    expect(readSwrCache('briefing:PRO_USER')).toBeNull();
    expect(localStorage.getItem('photocast_swr:briefing:PRO_USER')).toBeNull();
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
