import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

/**
 * Edge cases for `utils/swrCache.js`: the age boundary, which entries a write sweeps away, the
 * wording of the two warnings, and the generation counter.
 *
 * <p>The module holds a generation counter, so it is loaded afresh in each test; the clock is
 * pinned (Date only) so entry age is exact.
 */

const T0 = new Date('2026-11-07T10:00:00Z');

let cache;
let warn;

/** The object that actually owns `method` on the global localStorage, as in `swrCache.test.js`. */
function ownerOf(method) {
  return Object.prototype.hasOwnProperty.call(localStorage, method)
    ? localStorage
    : (Object.getPrototypeOf(localStorage) ?? Storage.prototype);
}

beforeEach(async () => {
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(T0);
  warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.resetModules();
  cache = await import('../utils/swrCache.js');
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
  localStorage.clear();
});

describe('readSwrCache age', () => {
  it('keeps an entry exactly as old as the limit and drops it one millisecond later', () => {
    cache.writeSwrCache('briefing:A', { n: 1 });
    vi.setSystemTime(new Date(T0.getTime() + 60_000));
    expect(cache.readSwrCache('briefing:A', 60_000)).toEqual({ n: 1 });
    vi.setSystemTime(new Date(T0.getTime() + 60_001));
    expect(cache.readSwrCache('briefing:A', 60_000)).toBeNull();
    expect(localStorage.getItem(cache.storageKey('briefing:A'))).toBeNull();
  });

  it('applies no age limit when none is given, however old the entry', () => {
    cache.writeSwrCache('briefing:A', { n: 1 });
    vi.setSystemTime(new Date(T0.getTime() + 30 * 86_400_000));
    expect(cache.readSwrCache('briefing:A')).toEqual({ n: 1 });
    expect(cache.readSwrCache('briefing:A', null)).toEqual({ n: 1 });
    expect(cache.readSwrCache('briefing:A', undefined)).toEqual({ n: 1 });
  });

  it('does not touch storage on a plain miss', () => {
    const remove = vi.spyOn(ownerOf('removeItem'), 'removeItem');
    expect(cache.readSwrCache('briefing:missing', 1000)).toBeNull();
    expect(remove).not.toHaveBeenCalled();
  });

  it('evicts an entry with no timestamp rather than serving it', () => {
    localStorage.setItem(cache.storageKey('briefing:A'), JSON.stringify({ value: { n: 1 } }));
    expect(cache.readSwrCache('briefing:A')).toBeNull();
    expect(localStorage.getItem(cache.storageKey('briefing:A'))).toBeNull();
  });
});

describe('writeSwrCache sweeping', () => {
  it('removes older key generations and same-namespace siblings but nothing else', () => {
    localStorage.setItem('photocast_swr:v3:briefing:PRO_USER', 'old generation');
    localStorage.setItem(cache.storageKey('briefing:LITE_USER'), 'sibling');
    localStorage.setItem(cache.storageKey('briefingx:PRO_USER'), 'different namespace, same letters');
    localStorage.setItem(cache.storageKey('almanac:PRO_USER'), 'another namespace');
    localStorage.setItem('unrelated', 'not ours');

    expect(cache.writeSwrCache('briefing:PRO_USER', { n: 1 })).toBe(true);

    expect(localStorage.getItem('photocast_swr:v3:briefing:PRO_USER')).toBeNull();
    expect(localStorage.getItem(cache.storageKey('briefing:LITE_USER'))).toBeNull();
    expect(localStorage.getItem(cache.storageKey('briefingx:PRO_USER'))).toBe('different namespace, same letters');
    expect(localStorage.getItem(cache.storageKey('almanac:PRO_USER'))).toBe('another namespace');
    expect(localStorage.getItem('unrelated')).toBe('not ours');
    expect(cache.readSwrCache('briefing:PRO_USER')).toEqual({ n: 1 });
  });

  it('sweeps siblings of a one-letter namespace', () => {
    localStorage.setItem(cache.storageKey('a:two'), 'sibling');
    cache.writeSwrCache('a:one', { n: 1 });
    expect(localStorage.getItem(cache.storageKey('a:two'))).toBeNull();
  });

  it('leaves current-generation entries in other namespaces alone when the key has no namespace', () => {
    localStorage.setItem(cache.storageKey('other:x'), 'kept');
    localStorage.setItem('photocast_swr:v1:plain', 'superseded');
    cache.writeSwrCache('plain', { n: 1 });
    expect(localStorage.getItem(cache.storageKey('other:x'))).toBe('kept');
    expect(localStorage.getItem('photocast_swr:v1:plain')).toBeNull();
    expect(cache.readSwrCache('plain')).toEqual({ n: 1 });
  });

  it('does not evict the entry it is about to overwrite', () => {
    cache.writeSwrCache('briefing:A', { n: 1 });
    const remove = vi.spyOn(ownerOf('removeItem'), 'removeItem');
    cache.writeSwrCache('briefing:A', { n: 2 });
    expect(remove).not.toHaveBeenCalledWith(cache.storageKey('briefing:A'));
    expect(cache.readSwrCache('briefing:A')).toEqual({ n: 2 });
  });
});

describe('writeSwrCache warnings', () => {
  it('reports a value that cannot be serialised by its own message', () => {
    expect(cache.writeSwrCache('briefing:A', { n: 10n })).toBe(false);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toBe(
      '[swrCache] could not serialise "briefing:A": Do not know how to serialize a BigInt',
    );
  });

  it('reports a thrown non-error as itself', () => {
    const value = { toJSON() { throw 'boom'; } };
    expect(cache.writeSwrCache('briefing:A', value)).toBe(false);
    expect(warn.mock.calls[0][0]).toBe('[swrCache] could not serialise "briefing:A": boom');
  });

  it('survives a thrown null', () => {
    const value = { toJSON() { throw null; } };
    expect(cache.writeSwrCache('briefing:A', value)).toBe(false);
    expect(warn.mock.calls[0][0]).toBe('[swrCache] could not serialise "briefing:A": null');
  });

  it('reports the size of a dropped entry in KB and as UTF-16 MB, and names the error', () => {
    const value = 'x'.repeat(1_048_576);
    const serialisedLength = JSON.stringify({ ts: T0.getTime(), value }).length;
    const kb = (serialisedLength / 1024).toFixed(0);
    const mb = ((serialisedLength * 2) / 1_048_576).toFixed(2);
    vi.spyOn(ownerOf('setItem'), 'setItem').mockImplementation(() => {
      const err = new Error('exceeded the quota');
      err.name = 'QuotaExceededError';
      throw err;
    });
    expect(cache.writeSwrCache('briefing:A', value)).toBe(false);
    expect(warn.mock.calls[0][0]).toBe(
      `[swrCache] dropped "briefing:A" (${kb} KB, ~${mb} MB as UTF-16): QuotaExceededError. `
      + 'Instant paint is disabled for this key.',
    );
    expect(kb).toBe('1024');
    expect(mb).toBe('2.00');
  });

  it('falls back to a generic error name when storage throws something nameless', () => {
    vi.spyOn(ownerOf('setItem'), 'setItem').mockImplementation(() => {
      throw null;
    });
    expect(cache.writeSwrCache('briefing:A', { n: 1 })).toBe(false);
    expect(warn.mock.calls[0][0]).toContain('): error. Instant paint is disabled for this key.');
  });
});

describe('the generation counter', () => {
  it('moves forward by one for each clear and refuses a write begun before it', () => {
    const before = cache.cacheGeneration();
    cache.clearSwrCache();
    expect(cache.cacheGeneration()).toBe(before + 1);
    cache.clearSwrCache();
    expect(cache.cacheGeneration()).toBe(before + 2);
    expect(cache.writeSwrCache('briefing:A', { n: 1 }, before)).toBe(false);
    expect(cache.writeSwrCache('briefing:A', { n: 1 }, cache.cacheGeneration())).toBe(true);
  });

  it('clears every entry under the root, including older generations, and nothing else', () => {
    localStorage.setItem('photocast_swr:v1:old', 'x');
    localStorage.setItem(cache.storageKey('briefing:A'), 'y');
    localStorage.setItem('unrelated', 'z');
    cache.clearSwrCache();
    expect(localStorage.getItem('photocast_swr:v1:old')).toBeNull();
    expect(localStorage.getItem(cache.storageKey('briefing:A'))).toBeNull();
    expect(localStorage.getItem('unrelated')).toBe('z');
  });
});
