import { badgeChannel } from './windowFirstCards.js';

/**
 * The Plan pane's promoted strip — the one coincidence worth putting above the list.
 *
 * <h2>What a coincidence is, and why it is not `topRarityRank`</h2>
 *
 * <p>Plan §2.6 and the design README §3 both define it the same way: <b>two attributes landing on
 * the same window</b>. One attribute is a row inside a window card; two earn the full-width strip.
 * So the predicate is {@link MIN_COINCIDENCE_BADGES} badges on one window and nothing else.
 *
 * <p><b>It is emphatically not "`topRarityRank` is present".</b> {@code PlanWindowProjector.rarestRank}
 * is {@code min()} over the window's badges and returns null only for an EMPTY list, so that field is
 * populated on every single-badge window too. Selecting on it would put a strip above a page whose
 * only "coincidence" is one aurora. `topRarityRank` is the tie-break — which of several coincidences
 * wins the one strip — exactly as {@code BriefingWindow}'s own Javadoc says ("Advice for the client's
 * promoted strip; nothing here enforces the one-strip rule").
 *
 * <h2>The badges counted are the window's, not the card's</h2>
 *
 * <p>{@code card.badges} has already had the attribute rows' promotions filtered out of it
 * ({@code windowFirstCards.js} drops any badge {@code buildWindowRows} turned into a row). Counting
 * that list would make a winter dawn carrying SNOW_TOPS + SNOW_FRESH — a genuine coincidence, and
 * the commonest same-channel one — look like a single-badge window, because one of the two became a
 * row forty pixels lower. {@code card.allBadges} is the unfiltered list, carried for this the same
 * way {@code allSpots} is carried past the reach gate.
 *
 * <h2>One strip per PAGE, enforced here</h2>
 *
 * <p>§2.6 says "at most one strip; highest rarity wins; <b>enforced in code, not by convention</b>",
 * and §6 clause 3 adds the sharper half: "'at most one' passes vacuously on a page that never built
 * the strip at all". So this function returns exactly one descriptor or null, and it is the only
 * place a strip can come from — the window card is explicitly forbidden from promoting anything
 * ({@code WindowFirstWindowCard.test.jsx} pins that boundary and must keep passing unedited).
 *
 * <p>Per page rather than per window, because the strip is full-width: a per-window reading would
 * put one between every pair of cards, which is the shape §2.6's "at most one" exists to forbid.
 *
 * <h2>Ordering: rarity chooses, time breaks ties</h2>
 *
 * <p>Rarest wins. Where two coincidences tie the EARLIER window takes it, because the pane's only
 * ordering spine is time (see {@code windowFirstAway.js}, and CLAUDE.md's record of a multi-day tide
 * run that was collapsed out of date order and reverted). `paneItems` is already in date order, so
 * the tie-break is a strict {@code <} and needs no comparator of its own.
 *
 * <h2>Nothing here disagrees with the attribute rows</h2>
 *
 * <p>{@code buildWindowRows} orders promoted rows by the same {@code rarityRank}, with the same
 * "absent sorts last" degrade, and says so in its own comment. The two surfaces therefore rank the
 * same topics the same way by construction rather than by review — which is what §6a asks whoever
 * lands P7b to check.
 */

/** Two attributes. The definition is the spec's, and the whole predicate. */
export const MIN_COINCIDENCE_BADGES = 2;

/**
 * {@code TopicRarity.UNKNOWN_RANK} as it arrives on the wire — {@code Integer.MAX_VALUE}, a real
 * number, not null.
 *
 * <p>P7b "owns `UNKNOWN_RANK`'s wire semantics" (plan §5), so they are stated here rather than left
 * to be re-derived. Three rules follow, and they are deliberately different from one another:
 *
 * <ul>
 *   <li><b>An unranked kind still counts toward the coincidence.</b> A topic the rarity table has
 *       no entry for is still an attribute that landed on the window — it is the <em>ranking</em>
 *       that is unknown, not the topic. Excluding it would let a missing table row silently delete
 *       a real coincidence.</li>
 *   <li><b>It loses every rarity contest.</b> Sorting last is the backend's own stated intent
 *       ("so a new strategy cannot silently outrank an established one by being unknown").</li>
 *   <li><b>A missing or non-integer rank is treated as unknown, never as rank 0.</b> `null`,
 *       `undefined` and a key absent from a legacy cached payload all land here. Rank 0 would be
 *       rarer than SUPERMOON and would hand the strip to whichever window had the most broken
 *       data.</li>
 * </ul>
 *
 * <p>It is a distinct constant from {@code windowFirstRows.js}'s {@code Number.MAX_SAFE_INTEGER}
 * default on purpose: that one is a sort sentinel with no meaning on the wire, this one is a value
 * the backend actually sends.
 */
export const UNKNOWN_RANK = 2147483647;

/** A badge's rank, with every non-integer input collapsed onto {@link UNKNOWN_RANK}. */
function rankOfBadge(badge) {
  return Number.isInteger(badge?.rarityRank) ? badge.rarityRank : UNKNOWN_RANK;
}

/**
 * The window's rarity, preferring the payload's own answer.
 *
 * <p>{@code topRarityRank} is the field the backend published for this surface and P7b is its first
 * consumer, so it is read first. The recompute is not a second source of truth — {@code rarestRank}
 * IS {@code min(rarityRank)}, so the two agree by definition — it is the path for a payload cached
 * before the field existed, where the key is absent entirely.
 */
function rankOfWindow(card) {
  if (Number.isInteger(card.topRarityRank)) return card.topRarityRank;
  return Math.min(...card.allBadges.map(rankOfBadge));
}

/**
 * The one fact a topic leads with.
 *
 * <p>{@code emphasis} is the field that means "the headline quantity against its context"
 * ({@code HotTopicFact}'s own Javadoc), so it is preferred over position; the first fact is the
 * fallback for a topic that marks none. A topic with no facts contributes no figure rather than a
 * figure with nothing in it — the strip's job is the coincidence, and a blank value would state
 * that a measurement exists.
 */
function headlineFact(badge) {
  const facts = badge?.facts || [];
  return facts.find((f) => f?.emphasis && f.value) || facts.find((f) => f?.value) || null;
}

/** A badge's stable identity — the same key {@code windowFirstRows.js} uses, for the same reason. */
function topicKey(badge) {
  return `${badge?.type ?? ''}:${badge?.label ?? ''}`;
}

/**
 * The strip's descriptor for one card, once it has won.
 *
 * <p>The channel is the RAREST topic's, because that is the one the strip is promoting for — and it
 * is carried as a bare word for a {@code data-channel} attribute rather than as colours, so the
 * accent lives in one stylesheet rule and cannot be assembled from a template string. Plan §2.9's
 * pruning trap is exactly that: a token name built at runtime is invisible to Tailwind's scanner and
 * is emitted as the empty string however many times it is used.
 */
function describe(card, rank, adjacent) {
  const ordered = [...card.allBadges].sort((a, b) => rankOfBadge(a) - rankOfBadge(b));
  return {
    windowKey: card.key,
    // The window in the pane's own words, composed exactly as the card composes `windowLabel` for
    // its spot strip — "Tonight Sunset" on the lead card, "Thursday sunrise" elsewhere. Sharing the
    // composition is what stops the strip naming a window differently from the card it points at.
    when: [card.kicker, card.when].filter(Boolean).join(' '),
    time: card.time || '',
    channel: badgeChannel(ordered[0]?.type),
    rarityRank: rank,
    // Whether the promoted window's card is the very next thing on the pane. When it is, the strip
    // carries no route to it: a control that scrolls to the element directly beneath it has no
    // visible effect, which is the demo control §6 bans.
    adjacent,
    topics: ordered.map((badge) => {
      const fact = headlineFact(badge);
      return {
        key: topicKey(badge),
        label: badge.label || '',
        // The fact's own lead-in label ("snow line", "Kp") when it has one; the topic's label when
        // it does not, so no figure is ever unlabelled. `HotTopicFact.key` is genuinely nullable —
        // `SnowTopsHotTopicStrategy` emits one.
        figureLabel: fact ? (fact.key || badge.label || '') : null,
        figureValue: fact ? fact.value : null,
      };
    }),
  };
}

/**
 * Picks the page's one promoted strip, or nothing.
 *
 * @param {Array} paneItems the pane's ordered contents from {@code buildPaneItems} — cards and away
 *        rows. Away rows carry no window and are skipped; the pane's date order is what makes the
 *        tie-break chronological without a comparator.
 * @returns {?object} one strip descriptor, or null when no window carries a coincidence
 */
export function buildPromotedStrip(paneItems) {
  const items = paneItems || [];
  const cards = items
    .filter((item) => item?.kind === 'card' && item.card)
    .map((item) => item.card)
    .filter((card) => (card.allBadges || []).length >= MIN_COINCIDENCE_BADGES);

  if (cards.length === 0) return null;

  let winner = cards[0];
  let best = rankOfWindow(winner);
  for (const card of cards.slice(1)) {
    const rank = rankOfWindow(card);
    // Strictly less, so an equal rarity leaves the earlier window holding the strip.
    if (rank < best) {
      winner = card;
      best = rank;
    }
  }

  const first = items[0];
  return describe(winner, best, first?.kind === 'card' && first.card?.key === winner.key);
}
