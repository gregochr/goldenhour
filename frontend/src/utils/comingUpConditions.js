/**
 * The recurring-conditions strip's presentation layer (design README §2/§2.1, plan §7 P4) —
 * {@code ComingUpCondition[]} from {@code GET /api/almanac} into the small pieces of derived state
 * the component needs. Every fact, label and score already arrived server-computed
 * ({@code ComingUpConditionsBuilder}); what is left here is genuinely client-only: which family
 * token a condition's swatch uses, and the expansion panel's own not-listed/below/on-Plan
 * counts (computed from the served occurrence list, never re-derived server-side because it is pure
 * arithmetic over data already on the wire — the same "filter/map" class {@code comingUpFeed.js}'s
 * header comment already licenses).
 */

/**
 * Maps a condition's machine type to the {@code --color-topic-*} family token its swatch and accent
 * use (plan D6). Unlike a chronology entry, a condition has no served {@code family} field — there
 * are only ever three rows at first ship, so the mapping is a fixed table rather than a served key.
 *
 * @type {Record<string, string>}
 */
export const CONDITION_FAMILY = {
  COASTAL_TIDES: 'coastal',
  DUST: 'dust',
  VALLEY_INVERSIONS: 'air',
};

/**
 * The family token for a condition, defaulting to {@code night-sky} for a type this table does not
 * (yet) know — a condition must always paint some accent colour, and a silently blank one would be
 * a worse failure than a slightly wrong one on a type this codebase has not shipped yet.
 *
 * @param {string} type the condition's {@code type}
 * @returns {string} a {@code --color-topic-*} suffix
 */
export function familyOf(type) {
  return CONDITION_FAMILY[type] ?? 'night-sky';
}

/**
 * The expansion panel's header line (design §2.1): "every date · N not listed, M below[, K on
 * Plan]" — the "on Plan" clause only appears when at least one occurrence carries that status,
 * matching the design's own conditional clause.
 *
 * <p>The three status words are the same three the occurrence rows below print, so the header and
 * its rows can never describe one status two ways.
 *
 * @param {Array<{status: string}>} occurrences a condition's served occurrences
 * @returns {string} the header line
 */
export function occurrenceCountsLine(occurrences) {
  const list = Array.isArray(occurrences) ? occurrences : [];
  const heldBack = list.filter((o) => o.status === 'heldBack').length;
  const promoted = list.filter((o) => o.status === 'promoted').length;
  const insidePlan = list.filter((o) => o.status === 'insidePlan').length;
  const tail = insidePlan > 0 ? `, ${insidePlan} on Plan` : '';
  return `every date · ${heldBack} not listed, ${promoted} below${tail}`;
}

/**
 * Whether the strip should show its quiet "figures are provisional" marker (README's "say so in
 * the UI" clause, §11.8) — true while ANY served condition's scoring is interim.
 *
 * @param {?Array<{interim: boolean}>} conditions the served conditions, or null/undefined
 * @returns {boolean}
 */
export function anyConditionInterim(conditions) {
  return Array.isArray(conditions) && conditions.some((c) => c.interim);
}

/**
 * A plain-English word for a surprisal score, captioning the raw `bits` figure the server sends
 * on a peak or occurrence rather than showing the bare information-theory unit on its own
 * (lunar-eclipse plan §2.9: "no more bits"). No backend counterpart, unlike the retired
 * `rarityWord` this replaces: `bits` here is the entry's or occurrence's own combined total
 * (rarity + magnitude), so a three-way frequency phrase would double-count what
 * `ComingUpConditionsBuilder`'s `quantLabel`/`rateLabel` already say about how OFTEN something
 * happens — this is purely a client-side bucket over the design's own three-word scale (README
 * §6): `exceptional` / `above usual` / `typical`.
 *
 * @param {number} bits the surprisal score
 * @returns {string} 'exceptional' | 'above usual' | 'typical'
 */
export function bitsWord(bits) {
  if (bits >= 7.0) return 'exceptional';
  if (bits >= 4.5) return 'above usual';
  return 'typical';
}
