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
    if (!parsed || typeof parsed.ts !== 'number') return null;
    if (maxAgeMs != null && Date.now() - parsed.ts > maxAgeMs) return null;
    return parsed.value ?? null;
  } catch {
    return null;
  }
}

/**
 * Writes a value with the current timestamp. Silently no-ops on quota / private-mode errors.
 *
 * @param {string} key - cache key (without the internal prefix)
 * @param {*} value - JSON-serialisable value to cache
 */
export function writeSwrCache(key, value) {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify({ ts: Date.now(), value }));
  } catch {
    // Quota exceeded or storage unavailable — caching is best-effort, ignore.
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
