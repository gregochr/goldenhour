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

/** Every key this module has ever owned. Sweeping uses this so old generations stay collectable. */
const ROOT = 'photocast_swr:';

/**
 * Current key generation. Bump when the SHAPE of a cached value changes.
 *
 * <p>Without a version segment, a payload whose structure changed is indistinguishable from one
 * that did not, and the old entry sits in a budget measured in single-digit megabytes until the
 * user happens to log out. Bumping retires every previous-generation entry at the next write.
 */
const PREFIX = `${ROOT}v2:`;

/**
 * Incremented by {@link clearSwrCache}. A writer captures this before it starts fetching and hands
 * it back on write; a mismatch means the cache was cleared while the fetch was in flight, and the
 * write is refused.
 *
 * <p>This is a cross-account leak, not just hygiene. `logout` awaits a network round-trip and only
 * then sweeps, and it clears the auth token *after* that — so there is a window where the sweep has
 * already run and the tree is still mounted. A revalidation resolving inside it re-planted the
 * previous account's full payload under the previous account's role key, immediately after the
 * cache was supposedly cleared, where nothing would read it and only a *future* logout would
 * collect it. An unmount-cancellation flag does not close that window; this does.
 */
let generation = 0;

/**
 * Returns the current cache generation, to be passed back to {@link writeSwrCache}.
 *
 * @returns {number} an opaque token identifying the current cache lifetime
 */
export function cacheGeneration() {
  return generation;
}

/**
 * The full localStorage key an entry is stored under, including prefix and generation.
 *
 * <p>Exists so tests can assert that bytes were actually RELEASED — eviction is not observable
 * through {@link readSwrCache}, which returns null for "evicted" and "never written" alike —
 * without pasting the prefix as a literal they would then have to chase on every generation bump.
 *
 * @param {string} key - cache key (without the internal prefix)
 * @returns {string} the underlying storage key
 */
export function storageKey(key) {
  return PREFIX + key;
}

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
 * Retires every entry the key about to be written supersedes: any previous key *generation*, and
 * any other role variant of the same namespace.
 *
 * <p><b>The policy is one entry per namespace, at the current generation.</b> Keys are
 * `namespace:role` (`forecasts:PRO_USER`, `briefing:anon`), so 2 namespaces x 4 role suffixes gives
 * 8 reachable keys per generation — and nothing collected the 6 a single user cannot be using.
 * Read-triggered eviction cannot reach them, because it requires someone to read that exact key;
 * a key nothing reads again is never collected. Only logout swept, and only wholesale.
 *
 * <p>Swept at write time rather than on a timer or at startup, because that is the instant the
 * space is actually needed, and it costs one pass over a store holding fewer than fifteen keys.
 *
 * <p><b>This does not make the two live caches coexist, and must not be sold as if it does.</b> The
 * briefing measures ~2.6 MB and the forecasts payload is larger; against iOS Safari's ~5 MB ceiling
 * one of them still loses. What this removes is the *orphans* that made the loss permanent and
 * unexplainable. Deliberately NOT evict-the-other-namespace-and-retry: the two genuinely do not
 * fit, so that would turn today's stable outcome into alternating thrash where each write evicts
 * the other and the next load starts cold, with a zero hit rate for both.
 *
 * @param {string} key - the key being written (without the internal prefix)
 */
function sweepSupersededBy(key) {
  const sep = key.indexOf(':');
  // A key with no namespace separator supersedes only its own older generations, never a sibling.
  const nsPrefix = sep === -1 ? null : PREFIX + key.slice(0, sep + 1);
  const keep = PREFIX + key;
  try {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i);
      if (!k || !k.startsWith(ROOT) || k === keep) continue;
      const supersededGeneration = !k.startsWith(PREFIX);
      const siblingRole = nsPrefix != null && k.startsWith(nsPrefix);
      if (supersededGeneration || siblingRole) localStorage.removeItem(k);
    }
  } catch { /* storage unavailable — the write below fails too, and reports for itself */ }
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
 * @param {number} [atGeneration] - value from {@link cacheGeneration} captured before fetching;
 *   when given and no longer current, the write is refused as belonging to a cleared session
 * @returns {boolean} true if the entry was written, false if it was dropped
 */
export function writeSwrCache(key, value, atGeneration) {
  if (atGeneration != null && atGeneration !== generation) {
    // The cache was cleared while this payload was in flight — it belongs to a session that has
    // ended. Writing it would undo the logout sweep.
    return false;
  }
  let serialised;
  try {
    serialised = JSON.stringify({ ts: Date.now(), value });
  } catch (err) {
    // A cyclic or non-serialisable value is a caller bug, not a storage condition — say so.
    console.warn(`[swrCache] could not serialise "${key}": ${err?.message ?? err}`);
    return false;
  }
  sweepSupersededBy(key);
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
  // Bump FIRST, so a write racing this sweep is refused even if it lands mid-loop.
  generation += 1;
  try {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i);
      // ROOT, not PREFIX: a logout must also collect entries written by an older key generation,
      // which a returning user's browser can still be holding.
      if (k && k.startsWith(ROOT)) localStorage.removeItem(k);
    }
  } catch {
    // Storage unavailable — nothing to clear.
  }
}
