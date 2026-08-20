import { badgeChannel } from './windowFirstCards.js';

/**
 * The Plan pane's promoted strip — the one thing worth putting above the list.
 *
 * <h2>Two rules earn a strip, and they are separate predicates</h2>
 *
 * <p><b>A coincidence.</b> Plan §2.6 and the design README §3 both define it the same way: <b>two
 * attributes landing on the same window</b>. One attribute is a row inside a window card; two earn
 * the full-width strip. That predicate is {@link MIN_COINCIDENCE_BADGES} badges on one window and
 * nothing else, and it is unchanged.
 *
 * <p><b>Or a lone topic rare enough not to need a partner</b> — {@link SOLO_PROMOTION_RANK}, which
 * is exactly one kind. See that constant for why the threshold is 1 and not 2. The descriptor says
 * which rule fired via {@code reason}, because the component renders the two differently and must
 * not infer it from {@code topics.length}.
 *
 * <p><b>Rarity chooses between them; the shape only breaks ties.</b> The rarest eligible window
 * wins outright whichever rule made it eligible — so a twice-a-century event does not lose the
 * pane's lede to a commoner pair that happens to be two things instead of one. Where two tie on
 * rank the coincidence takes it, because naming a relationship is the strip's original job and a
 * lone topic has none to name; where they still tie, the earlier window holds it.
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
 * <h2>Ordering: rarity chooses, then shape, then time</h2>
 *
 * <p>Rarest wins, across both rules. Where two tie on rank a coincidence beats a solo; where two of
 * the same shape tie, the EARLIER window takes it, because the pane's only ordering spine is time
 * (see {@code windowFirstAway.js}, and CLAUDE.md's record of a multi-day tide run that was collapsed
 * out of date order and reverted). `paneItems` is already in date order and the eligible list is
 * built coincidences-first, so both tie-breaks fall out of position and need no comparator.
 *
 * <h2>Nothing here disagrees with the attribute rows</h2>
 *
 * <p>{@code buildWindowRows} orders promoted rows by the same {@code rarityRank}, with the same
 * "absent sorts last" degrade, and says so in its own comment. {@code windowFirstTopics.windowTopics}
 * — the matrix card's topic row, added at M1 — is the THIRD, on the same one key for the same
 * reason: it briefly carried a label tie-break as well, which is a better ordering in the abstract
 * and would have put two equal-rarity snow topics in one order on the card and the other in the
 * window row beneath it. All three surfaces therefore rank the same topics the same way by
 * construction rather than by review — which is what §6a asks whoever lands P7b to check.
 */

/** Two attributes. The definition is the spec's, and the whole predicate for a COINCIDENCE. */
export const MIN_COINCIDENCE_BADGES = 2;

/**
 * The rarity rank at or below which one topic earns the strip on its own.
 *
 * <h2>This is a second rule beside the coincidence rule, not a loosening of it</h2>
 *
 * <p>{@link MIN_COINCIDENCE_BADGES} is untouched and still means exactly what it meant: two
 * attributes on one window is a coincidence. What is added is a separate, narrower question — is
 * this ONE topic rare enough that holding the pane's lede back for want of a second attribute to
 * pair it with would be the wrong call? For an eclipse deep enough to be worth driving for, which
 * happens over these islands roughly twice a century, it plainly is.
 *
 * <p><b>Why 1 and not 2.</b> Rank 1 is exactly one kind — ECLIPSE — and the backend pins that count
 * with a test rather than the name, so a second kind arriving at rank 1 fails there first. Rank 2
 * is SUPERMOON, which happens a few times a year; a strip that appeared for every supermoon would
 * stop being the pane's lede and become its wallpaper. The threshold is also what keeps the two
 * existing "a single badge gets no strip" tests green unedited — their fixtures sit at rank 4 —
 * and that is a constraint on the design rather than a happy accident.
 *
 * <p>Duplicated from {@code TopicRarity.SOLO_PROMOTION_RANK} rather than sent on the wire, the same
 * way {@link UNKNOWN_RANK} duplicates its backend counterpart and for the same reason: it is a
 * property of an ordinal both sides already have to agree on.
 */
export const SOLO_PROMOTION_RANK = 1;

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
 *
 * <p><b>⚠️ That preference is doing §6 work, not just typographic work, and it is the reason this
 * function is not simply {@code facts[0]}.</b> {@code MeteorHotTopicStrategy.addClearSkyFact} emits
 * <em>"clear at 3 of 7 dark-sky locations"</em> as a fact — a count of our own data, which §6 bans
 * outright and which three surfaces in this arm have already had to drop. It is appended last and
 * deliberately carries {@code emphasis: false}, while the shower's ZHR is first and carries
 * {@code emphasis: true}; so preferring emphasis keeps the banned claim out of the strip's 15px
 * figure. Aurora and NLC put their own "clear at X of Y" in {@code detail}, which this strip never
 * renders. <b>Residual, stated rather than defended against:</b> a future strategy that marked a
 * count fact as the emphasised one would put it on the strip, and the client cannot tell a count
 * from a measurement without sniffing the string — which would be a guess. The guard is the
 * backend's own emphasis flag, and {@code windowFirstPromoted.test.js} pins the meteor shape.
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
function describe(card, rank, adjacent, reason) {
  const ordered = [...card.allBadges].sort((a, b) => rankOfBadge(a) - rankOfBadge(b));
  const lead = ordered[0];
  return {
    windowKey: card.key,
    // Which rule put this strip on the pane. The component renders the two differently, and it must
    // not infer the reason from `topics.length` — a coincidence's second badge could in principle be
    // filtered out upstream, and a strip would then silently change shape rather than disappear.
    reason,
    // Only the solo-rarity branch may carry these two. On a coincidence the strip describes a
    // PAIR, and both are claims about a single topic: rendering one topic's sentence as though it
    // described both is the defect the "no why clause" rule was written against.
    rarityNote: reason === 'rarity' ? (lead?.rarityNote || null) : null,
    // `note`, NOT `detail`. `detail` summarises the same measurements the headline figure already
    // shows — for the eclipse, "91% of the sun covered, and it sits only 13° up" beside a figure
    // reading "91% covered · sun only 13° up" — so rendering it printed the same two numbers twice
    // on one strip, forty pixels apart. Found in a browser; every unit test asserted the two
    // separately and both were individually correct. `note` is the topic's editorial aside by
    // definition ("a clear low western horizon matters more than the last 2%"), which is exactly
    // the register the design's italic serif line is written in.
    why: reason === 'rarity' ? (lead?.note || null) : null,
    // The exception, and it is not symmetric with the two above: a warning is not a description of
    // the strip's subject, it is a hazard of acting on it. It rides whichever topic carries one,
    // under either rule, because a coincidence containing an eclipse is still an eclipse.
    safetyNote: ordered.map((b) => b?.safetyNote).find(Boolean) || null,
    // The window in the pane's own words, composed exactly as the card composes `windowLabel` for
    // its spot strip — "Tonight Sunset" on the lead card, "Thursday sunrise" elsewhere. Sharing the
    // composition is what stops the strip naming a window differently from the card it points at.
    when: [card.kicker, card.when].filter(Boolean).join(' '),
    // The WINDOW's time on a coincidence, the TOPIC's own on a solo rarity — and the difference is
    // the difference between what the two strips are about. A coincidence strip's subject is the
    // window (two things landed on it), so the window's clock is the right one. A rarity strip's
    // subject is one topic, and printing "Tonight Sunset 20:42" as the only time on a strip whose
    // whole content is an eclipse peaking at 19:08 sends the reader out an hour and a half late.
    //
    // `BriefingWindow.Badge`'s own Javadoc names these as different anchors and says to render one
    // or the other, never both — so this picks, rather than showing two clocks.
    time: (reason === 'rarity' ? lead?.eventTime : card.time) || card.time || '',
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
        // The fact's own lead-in label ("snow line") when it has one, and NOTHING when it does not.
        //
        // ⚠️ This used to fall back to the topic's own label so that no figure was ever unlabelled,
        // and that was wrong in the most reachable case there is. `HotTopicFact.key` is nullable and
        // the two live producers of a keyless HEADLINE fact are `AuroraHotTopicStrategy`
        // (`HotTopicFact.metric(null, "Kp 5 · glow reaches ~57°N and north")`) and
        // `NlcHotTopicStrategy` (`new HotTopicFact(null, "after dusk · 23:14–01:02", …)`) — both
        // NIGHT topics, which `PlanWindowProjector.keysFor` buckets onto the SAME two windows, so
        // aurora × NLC is the ordinary coincidence rather than an exotic one. The fallback printed
        // `Aurora possible` in the kicker and `Aurora possible` again as the figure's lead-in, on a
        // 131px element whose entire content is two names and two numbers — precisely the
        // duplication `windowFirstRows.js` refuses to build a row for, citing §6.
        //
        // The original justification cited `SnowTopsHotTopicStrategy`, which does emit a keyless
        // fact — but it is second and un-emphasised, behind `metric("snow line", …)`, so it can
        // never be the headline. A keyless value is self-describing ("Kp 5 · glow reaches ~57°N and
        // north" needs no lead-in); an unlabelled figure is the honest render, not a gap to fill.
        figureLabel: fact ? (fact.key || null) : null,
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
 * @returns {?object} one strip descriptor, or null when no window earns one under either rule
 */
export function buildPromotedStrip(paneItems) {
  const items = paneItems || [];
  const all = items
    .filter((item) => item?.kind === 'card' && item.card)
    .map((item) => item.card)
    .filter((card) => (card.allBadges || []).length > 0);

  // Two predicates, written separately so each stays readable on its own and neither can be
  // changed by accident while editing the other.
  const eligible = [
    ...all
      .filter((card) => card.allBadges.length >= MIN_COINCIDENCE_BADGES)
      .map((card) => ({ card, reason: 'coincidence' })),
    ...all
      .filter((card) => card.allBadges.length === 1 && rankOfWindow(card) <= SOLO_PROMOTION_RANK)
      .map((card) => ({ card, reason: 'rarity' })),
  ];
  if (eligible.length === 0) return null;

  // Rarity decides. An earlier cut had `rarest(coincidences) ?? rarest(soloRarities)`, giving any
  // coincidence strict precedence — so a rank-3 king tide paired with a rank-5 aurora took the
  // strip from a rank-1 eclipse on another day, and the eclipse's safety warning went with it,
  // because `describe` sources the warning from the winning card alone. An adversarial review
  // found it. Ranking across both sets is also the simpler rule to state.
  let winner = eligible[0];
  let best = rankOfWindow(winner.card);
  for (const candidate of eligible.slice(1)) {
    const rank = rankOfWindow(candidate.card);
    if (rank < best) {
      winner = candidate;
      best = rank;
    } else if (rank === best && winner.reason === 'rarity' && candidate.reason === 'coincidence') {
      // Equal rank: the pair wins, because naming a relationship is what the strip is for. The
      // `eligible` list is built coincidences-first over a date-ordered pane, so an equal-rank
      // contest between two of the SAME kind is already settled by position and needs no branch.
      winner = candidate;
    }
  }

  const first = items[0];
  const adjacent = first?.kind === 'card' && first.card?.key === winner.card.key;
  return describe(winner.card, best, adjacent, winner.reason);
}

