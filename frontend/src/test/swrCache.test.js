import { describe, it, expect, beforeEach, vi } from 'vitest';
import { readSwrCache, writeSwrCache, clearSwrCache } from '../utils/swrCache.js';

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
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceeded');
    });
    try {
      expect(() => writeSwrCache('briefing:PRO_USER', { v: 1 })).not.toThrow();
    } finally {
      spy.mockRestore();
    }
  });
});
