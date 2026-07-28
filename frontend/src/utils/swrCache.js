/**
 * Tiny stale-while-revalidate cache backed by localStorage.
 *
 * Lets a view hydrate its last-seen payload synchronously on mount so it paints
 * instantly on refresh, while the real fetch revalidates in the background and
 * swaps in fresh data. Entries are namespaced under a common prefix so they can
 * all be cleared on logout (so one session's data never leaks to the next login).
 *
 * Callers are responsible for making the `key` role-specific (e.g. `briefing:PRO_USER`)
 * when the payload differs by role.
 */

const PREFIX = 'photocast_swr:';

/**
 * Reads a cached value, or null when missing, unparseable, or older than maxAgeMs.
 *
 * @param {string} key - cache key (without the internal prefix)
 * @param {number} [maxAgeMs] - if given, entries older than this are treated as a miss
 * @returns {*} the cached value, or null
 */
export function readSwrCache(key, maxAgeMs) {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    if (raw === null) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed.ts !== 'number') return dropEntry(key);
    // Evict on the stale branch rather than just reporting a miss. These entries are megabytes
    // (the briefing measures ~2.6 MB as UTF-16), and the budget is shared: an expired entry the
    // reader has already decided not to use would otherwise go on occupying the space that
    // blocks the *other* cache's write. Leaving it gives the worst of both — no instant paint
    // from the stale copy, and no room for the fresh one.
    if (maxAgeMs != null && Date.now() - parsed.ts > maxAgeMs) return dropEntry(key);
    return parsed.value ?? null;
  } catch {
    return null;
  }
}

/** Removes one entry and returns null, so a stale/corrupt read can `return dropEntry(key)`. */
function dropEntry(key) {
  try {
    localStorage.removeItem(PREFIX + key);
  } catch { /* storage unavailable — the miss is what matters, not the eviction */ }
  return null;
}

/**
 * Writes a value with the current timestamp.
 *
 * <p>Returns whether the write landed. Callers may ignore it — caching is best-effort — but the
 * boolean exists so a dropped write is *representable*: before it, a quota failure was swallowed
 * into a bare `catch` and was indistinguishable from a first visit, from a cold cache, and from
 * a private-mode session. That matters here because the payloads are large enough to plausibly
 * exceed the budget on the device the feature is for: the two callers write roughly 1.3 MB
 * (briefing) and a larger forecasts payload, and iOS Safari caps localStorage near 5 MB while
 * commonly accounting for it as UTF-16 — so ~2.6 MB and ~4 MB against a 5 MB ceiling. When that
 * tips over, instant paint dies silently on mobile, which is precisely where it earns its keep.
 *
 * @param {string} key - cache key (without the internal prefix)
 * @param {*} value - JSON-serialisable value to cache
 * @returns {boolean} true if the entry was written, false if it was dropped
 */
export function writeSwrCache(key, value) {
  let serialised;
  try {
    serialised = JSON.stringify({ ts: Date.now(), value });
  } catch (err) {
    // A cyclic or non-serialisable value is a caller bug, not a storage condition — say so.
    console.warn(`[swrCache] could not serialise "${key}": ${err?.message ?? err}`);
    return false;
  }
  try {
    localStorage.setItem(PREFIX + key, serialised);
    return true;
  } catch (err) {
    // Do not leave a half-written or previous-generation entry occupying the budget after a
    // failed write — a stale copy under a key the caller believes it just refreshed is worse
    // than no copy, and it keeps starving the other cache.
    dropEntry(key);
    console.warn(
      `[swrCache] dropped "${key}" (${(serialised.length / 1024).toFixed(0)} KB, `
      + `~${((serialised.length * 2) / 1048576).toFixed(2)} MB as UTF-16): ${err?.name ?? 'error'}. `
      + 'Instant paint is disabled for this key.',
    );
    return false;
  }
}

/**
 * Removes every SWR cache entry. Call on logout so a cached payload from one
 * account is never shown to the next account signing in on the same browser.
 */
export function clearSwrCache() {
  try {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i);
      if (k && k.startsWith(PREFIX)) localStorage.removeItem(k);
    }
  } catch {
    // Storage unavailable — nothing to clear.
  }
}
