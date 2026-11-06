import { gateSpotsByReach } from './reachLens.js';

/**
 * The Map tab's reach helpers (docs/engineering/reach-scoped-card-plan.md R3).
 *
 * <p>The Map has no reach pool of its own: its verdict follows the scope segment ("My area" / "Everywhere") over the
 * heat spots. Under a finite drive tier the pill's words come from the server's per-tier answer, and every
 * Map figure printed beside them must describe the places that answer describes: the scope pool, narrowed to the
 * places inside the tier. This is that narrowing, and it is the Plan's own gate ({@link gateSpotsByReach}), not a
 * second rule: a place with no measured drive stays (unknown), and one the reader's drives say have no route does not.
 */

/**
 * The scope pool's spots that lie inside the drive tier.
 *
 * @param {Array<{id: *}>} pool the scope pool (heat spots), before every reader filter
 * @param {function(*): ?{driveMinutes: ?number, driveStatus: ?string}} entryFor the reach entry for a location id, from
 *        the map in force (home's, or the away origin's override)
 * @param {?number} limitMinutes the active tier's threshold, null for Any (nothing is narrowed)
 * @param {boolean} measured the reader-level {@code reachIsMeasured}
 * @returns {Array} the spots inside the tier, in order; the input itself when nothing is narrowed
 */
export function scopePoolWithinReach(pool, entryFor, limitMinutes, measured) {
  if (limitMinutes == null || !Array.isArray(pool)) return pool;
  const wrapped = pool.map((spot) => {
    const entry = spot?.id == null ? null : entryFor(spot.id);
    return { spot, driveMinutes: entry?.driveMinutes ?? null, driveStatus: entry?.driveStatus ?? null };
  });
  return gateSpotsByReach(wrapped, limitMinutes, { measured }).map((item) => item.spot);
}
