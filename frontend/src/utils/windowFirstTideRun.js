/**
 * The tide run across the strip — which windows are live, and which of them to take.
 *
 * <h2>Count first, then how well those spots are served</h2>
 *
 * <p>tide-plan-card-plan.md §3 C1 task 3, the spec's §3. A phase of the tide runs on a roughly
 * fortnightly beat, so a good phase puts the coast live on several consecutive windows — and once
 * more than one is live, the count alone cannot say which to take: the spec's own worked example has
 * three live windows all matching the same nine locations, where the count is silent and only HOW
 * WELL those nine are served (the served {@code tideAlignmentQuality}, C0, averaged over the matched
 * spots) tells them apart. Ranking by count alone would silently pick the first of three identical
 * numbers.
 *
 * <p><b>Nothing here reads a level, an offset or a threshold.</b> The only number compared is the
 * served quality {@code windowFirstCards.js#tideFit} already averaged; this module ranks, it does
 * not derive (tide-plan-card-plan.md §2, §4 #1/#2).
 *
 * <p>A genuine tie — equal `matched`, equal `meanQuality` (or both windows carrying no quality at
 * all) — keeps the EARLIER window, because the loop below only replaces the running best with one
 * that is strictly better: ties never win a later window, which is the "window index ASC" clause of
 * the spec's own ranking read as "leave the incumbent standing".
 *
 * <p>{@code bestKey} is null whenever there is nothing to be best OF — a single live window states
 * its own count and needs no emphasis, and the spec is explicit that the emphasis exists only where
 * {@code live > 1}.
 */

/**
 * Whether tide-fit `a` outranks the running best `b` — `matched DESC, meanQuality DESC (nulls
 * last), window index ASC`. Only ever asked with `a` later in strip order than `b` (the caller walks
 * forward), so answering `false` on a tie is what leaves the earlier window in place.
 *
 * @param {{matched: number, meanQuality: ?number}} a a later live window's tide fit
 * @param {{matched: number, meanQuality: ?number}} b the running best's tide fit
 * @returns {boolean} true when `a` should replace `b`
 */
function outranks(a, b) {
  if (a.matched !== b.matched) return a.matched > b.matched;
  const qa = a.meanQuality ?? -Infinity;
  const qb = b.meanQuality ?? -Infinity;
  return qa > qb;
}

/**
 * The run over one rendered strip.
 *
 * @param {Array<{key: string, away?: boolean, tideFit?: ?{coastal: number, matched: number,
 *   live: boolean, meanQuality: ?number}}>} cards the strip's own descriptors
 *   ({@code windowFirstStrip.js#buildHeatStripCards}), in chronological order
 * @returns {{liveKeys: Set<string>, bestKey: ?string, liveCount: number}} which cards are live, the
 *   one to take (null unless more than one is live), and how many are live
 */
export function tideRun(cards) {
  const liveKeys = new Set();
  let bestKey = null;
  let bestFit = null;

  for (const card of Array.isArray(cards) ? cards : []) {
    if (!card || card.away) continue;
    const fit = card.tideFit;
    if (!fit?.live) continue;
    liveKeys.add(card.key);
    if (bestFit === null || outranks(fit, bestFit)) {
      bestFit = fit;
      bestKey = card.key;
    }
  }

  const liveCount = liveKeys.size;
  return { liveKeys, bestKey: liveCount > 1 ? bestKey : null, liveCount };
}
