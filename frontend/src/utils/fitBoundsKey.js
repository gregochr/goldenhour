/**
 * Builds a namespaced key for a MapView fit-bounds handoff.
 *
 * <p>MapView drives its {@code FitBoundsController} off a single {@code key}, and the controller
 * only re-fits the map when that key changes. Two independent handoff sources write the key — the
 * Plan-tab region handoff and the map-overlay focus — each from its own monotonic nonce counter.
 * Without namespacing, those counters can produce the same integer in succession (both start low
 * and increment independently), leaving the key unchanged and silently skipping a re-fit.
 * Prefixing the source name guarantees a region and a focus handoff can never collide on the same
 * key even when their nonces are equal.
 *
 * @param {string} source - the handoff source, e.g. {@code 'region'} or {@code 'focus'}
 * @param {number|null|undefined} nonce - the source's monotonic nonce (defaults to 0)
 * @returns {string} a key unique to the (source, nonce) pair, e.g. {@code 'region:3'}
 */
export function fitBoundsKey(source, nonce) {
  return `${source}:${nonce ?? 0}`;
}
