/**
 * Indexed lookup for briefing evaluation scores.
 *
 * Briefing scores arrive as a Map keyed {@code "regionName|date|targetType|locationName"}.
 * The map view looks a score up per marker by (locationName, date, eventType); a naive linear
 * scan of the whole Map for every marker is O(markers x scores) on each render. These helpers
 * build a hash index once so each per-marker lookup is O(1), preserving the original scan's exact
 * matching (suffix {@code "|date|eventType|locationName"}) and first-match-wins ordering.
 */

/**
 * Reference linear scan — the ground-truth semantics the index mirrors. Returns the first
 * (insertion-order) entry whose key ends with {@code "|date|eventType|locationName"}, or null.
 *
 * @param {Map<string, object>|null|undefined} briefingScores - keyed score map.
 * @param {string} locationName - location display name.
 * @param {string} date - target date (YYYY-MM-DD).
 * @param {string} eventType - 'SUNRISE' or 'SUNSET'.
 * @returns {object|null} the matched score, or null when nothing matches.
 */
export function getBriefingScore(briefingScores, locationName, date, eventType) {
  if (!briefingScores || briefingScores.size === 0) return null;
  const suffix = `|${date}|${eventType}|${locationName}`;
  for (const [key, result] of briefingScores) {
    if (key.endsWith(suffix)) return result;
  }
  return null;
}

/**
 * Builds an O(1) lookup index from a briefing-score map. Each index key is the score key's last
 * three {@code |}-delimited fields — exactly the {@code "date|eventType|locationName"} tail the
 * scan's {@code endsWith} matches — so any leading field(s) are dropped whatever they contain.
 * (Taking the last three rather than "everything after the first pipe" keeps the index faithful
 * to the scan even if a region name itself contains a pipe; regions are user-created.) The
 * first-inserted entry wins on collision, mirroring the scan's insertion-order first-match.
 *
 * @param {Map<string, object>|null|undefined} briefingScores - keyed score map.
 * @returns {Map<string, object>} index keyed by {@code "date|eventType|locationName"}.
 */
export function buildBriefingScoreIndex(briefingScores) {
  const index = new Map();
  if (!briefingScores || briefingScores.size === 0) return index;
  for (const [key, result] of briefingScores) {
    const parts = key.split('|');
    // Fewer than 4 fields means no leading field to strip, so the scan's "|"-prefixed
    // suffix could never match this key either — skip it.
    if (parts.length < 4) continue;
    const tail = parts.slice(-3).join('|');
    if (!index.has(tail)) index.set(tail, result);
  }
  return index;
}

/**
 * O(1) lookup against a {@link buildBriefingScoreIndex} index. Returns the matched score or null
 * (never undefined), matching {@link getBriefingScore}.
 *
 * @param {Map<string, object>|null|undefined} index - index from buildBriefingScoreIndex.
 * @param {string} locationName - location display name.
 * @param {string} date - target date (YYYY-MM-DD).
 * @param {string} eventType - 'SUNRISE' or 'SUNSET'.
 * @returns {object|null} the matched score, or null when nothing matches.
 */
export function lookupBriefingScore(index, locationName, date, eventType) {
  if (!index || index.size === 0) return null;
  return index.get(`${date}|${eventType}|${locationName}`) ?? null;
}
