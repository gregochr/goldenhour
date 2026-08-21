import { windowKey } from './heatSpots.js';

/**
 * The window ↔ hot-topic join, and the one filter the client is allowed to apply to it.
 *
 * <h2>Two rules, one util (plan-matrix §4 A8)</h2>
 *
 * <p>The v3 card names every topic landing on its night, and the M2 popup adds each topic's
 * science note and scope line. Both need the same two things, so both read this module — a second
 * copy of either rule is how one surface silently disagrees with the other.
 *
 * <h3>Rule 1 — the join replicates {@code PlanWindowProjector.keysFor} exactly</h3>
 *
 * <p>A badge on window {@code (date, targetType)} matches a {@code hotTopics} entry whose served
 * {@code eventType} + {@code date} bucket onto that window: {@code SUNRISE} → {@code (date,
 * SUNRISE)}, {@code SUNSET} → {@code (date, SUNSET)}, and {@code NIGHT} → <b>both</b>
 * {@code (date, SUNSET)} and {@code (date + 1, SUNRISE)}.
 *
 * <p>⚠️ <b>That last case is the one an obvious implementation loses.</b> A naive
 * {@code topic.date === card.date} equality drops every NIGHT topic — aurora, NLC, meteor,
 * supermoon — from the morning card it also belongs to, and every naive test passes because the
 * evening card still joins. The key is the served {@code eventType} field, never a client list of
 * which types are nocturnal: the backend decides that per topic and can change its mind.
 *
 * <h3>Rule 2 — the scope filter is exempt BY TYPE, not by whether {@code regions} is populated</h3>
 *
 * <p>{@code HotTopic.regions} is not a uniform eligibility roster; its meaning differs per
 * strategy. For a tide, an inversion, dust or snow it names where the conditions are — so
 * intersecting it with the plan's current scope is the right question, and an empty intersection
 * means the topic is not about anywhere the reader is planning from. For aurora it is Bortle
 * enrichment coverage, and for NLC it is where the sky happens to be clear tonight — both
 * <em>populated</em> lists that mean something else, so the same intersection test would delete
 * aurora from every away plan while every naive test passed.
 *
 * <p>So {@link WHOLE_SKY_TOPIC_TYPES} is a type map, following the {@code topicCertainty}
 * precedent. An <b>unrecognised</b> type is exempt too, which is the {@code badgeChannel} rule
 * applied to a filter rather than to a colour: a new topic kind fails quietly and additively,
 * because showing a topic the scope might not want is a mild over-claim while deleting one
 * outright is a silent loss. A region-scoped topic that serves an <b>empty</b> {@code regions}
 * list is likewise kept — empty is "not stated", not "nowhere".
 *
 * <p>Region names are compared <b>byte-identically, never normalised</b> — the rule
 * {@code heatSpots.js} records for the same two payloads.
 *
 * <h3>Two consequences worth knowing rather than rediscovering</h3>
 *
 * <p><b>The filter runs at HOME too, and that is the spec.</b> The scope is
 * {@code planOrigin.scopeRegions}, which at home is the planning area — every region within
 * {@code GLANCE_MINUTES}, plus every region whose drive is not known. The bundle defines its own
 * origin as {@code HOME = "every region in your area"} with the same 180-minute rule, and A8 says
 * "the current scope" rather than "the origin". It is also the SAME call that frames every card's
 * field, so the topic list and the picture it sits on agree by construction. The side-effect is
 * real and accepted: an account with drive times can see one topic fewer than an account without
 * them, because an unmeasured region is kept (that is {@code areaRegions}' documented degrade).
 *
 * <p><b>A dropped topic is silent this phase.</b> A8's scope note — "<n> region(s) in scope" — is a
 * popup element and arrives with M2, and the Hot Topics door below the matrix renders
 * {@code briefing.hotTopics} unfiltered, so a topic the card dropped is still listed there. That
 * divergence is the design's own ("Topics re-filter, so the topic list changes without any
 * per-region table") — the door answers "what is happening" and the card answers "what is happening
 * on this window, in the area you are planning from".
 */

/**
 * The topic types whose {@code regions} list is not an eligibility roster.
 *
 * <p>At this latitude the whole planning area sees an aurora, an NLC display, a meteor peak, a
 * supermoon, an equinox alignment or an eclipse, or none of it does — so none of them may be
 * scoped away by a region intersection. See the class comment for what their {@code regions}
 * actually carries.
 */
export const WHOLE_SKY_TOPIC_TYPES = new Set([
  'AURORA', 'NLC', 'METEOR', 'SUPERMOON', 'EQUINOX', 'ECLIPSE',
]);

/**
 * The topic types whose {@code regions} names where the conditions are, and which the scope
 * intersection therefore governs.
 *
 * <p>Exported for its own test rather than because the filter reads it: the filter asks
 * {@link isWholeSkyTopic}, which is <b>fail-open</b> — an unrecognised type is exempt by falling
 * through rather than by being absent from a list, the {@code badgeChannel} rule applied to a filter
 * instead of to a colour.
 *
 * <p>⚠️ <b>Fail-open makes an INCOMPLETE list silent, so the two sets must together cover the
 * backend's whole roster</b> ({@code TopicRarity.RANK_BY_TYPE}, sixteen types). An earlier cut
 * listed eight and omitted {@code SNOW_TOPS} — a live strategy whose {@code regions} is a genuine
 * eligibility roster ({@code PerDateHotTopicBuilder} dates each day's topic "carrying only its
 * regions") — so "Snow on the tops" was exempt from the intersection while its sibling
 * {@code SNOW_FRESH}, on the same window with the same regions, was filtered. Two members of one
 * phenomenon treated oppositely, from a set that had lost a name.
 *
 * <p>The mirror is checked by {@code windowFirstTopics.test.js}, which holds its own literal copy of
 * the sixteen and asserts the union — so adding a name to one of these sets cannot satisfy the test
 * on its own, and neither can adding it to the test's list.
 */
export const REGION_SCOPED_TOPIC_TYPES = new Set([
  'KING_TIDE', 'SPRING_TIDE', 'STORM_SURGE', 'INVERSION', 'DUST',
  'SNOW_TOPS', 'SNOW_MIST', 'SNOW_FRESH', 'CLEARANCE', 'BLUEBELL',
]);

/** The three event anchors a topic can carry, as the backend spells them. */
const EVENT_SUNRISE = 'SUNRISE';
const EVENT_SUNSET = 'SUNSET';
const EVENT_NIGHT = 'NIGHT';

/** Whether a topic type is exempt from the scope intersection — see the class comment. */
export function isWholeSkyTopic(type) {
  const t = String(type || '').toUpperCase();
  return WHOLE_SKY_TOPIC_TYPES.has(t) || !REGION_SCOPED_TOPIC_TYPES.has(t);
}

/** The next calendar day, in the same `YYYY-MM-DD` form. Noon UTC, so no timezone can move it. */
function nextDay(dateStr) {
  const d = new Date(`${dateStr}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/**
 * The window keys a served topic buckets onto — {@code PlanWindowProjector.keysFor}, replicated.
 *
 * @param {?{date: string, eventType: ?string}} topic a served {@code HotTopic}
 * @returns {string[]} `date:targetType` keys; empty when the topic has no solar anchor
 */
export function topicWindowKeys(topic) {
  const date = topic?.date;
  if (!date) return [];
  switch (topic.eventType) {
    case EVENT_SUNRISE: return [windowKey(date, EVENT_SUNRISE)];
    case EVENT_SUNSET: return [windowKey(date, EVENT_SUNSET)];
    case EVENT_NIGHT: return [
      windowKey(date, EVENT_SUNSET),
      windowKey(nextDay(date), EVENT_SUNRISE),
    ];
    default: return [];
  }
}

/**
 * Indexes the served hot topics by the window keys they bucket onto.
 *
 * <p>Built once per render rather than per card: the join is O(topics) and the lookup O(1), where
 * a per-card scan would be O(cards × topics) over the same list six times.
 *
 * @param {Array<object>} hotTopics the served {@code briefing.hotTopics}
 * @returns {Map<string, Array<object>>} window key → the topics anchored to it, in served order
 */
export function buildTopicIndex(hotTopics) {
  const index = new Map();
  for (const topic of Array.isArray(hotTopics) ? hotTopics : []) {
    for (const key of topicWindowKeys(topic)) {
      const list = index.get(key);
      if (list) list.push(topic);
      else index.set(key, [topic]);
    }
  }
  return index;
}

/**
 * The topic a badge came from, or null.
 *
 * <p>{@code PlanWindowProjector} builds a badge from a topic's own {@code type} and {@code label}
 * ({@code bucketTopics}), so the pair is the exact key and an exact match is the only match.
 *
 * <p>⚠️ <b>There is deliberately NO type-only fallback, and one was written and removed.</b> The
 * argument for it was that a label edited between the two halves of one response should still find
 * the science note — but the joined topic is not only a note, it is where {@code regions} comes
 * from, and {@code regions} decides whether the badge is <em>dropped</em>. With two topics of one
 * type on one window (a snow day carrying two snow kinds is the ordinary case) a type-only fallback
 * resolves badge B to topic A's regions, so a badge whose own regions ARE in scope is deleted. It
 * turned a "lose the note" degrade into a "lose the topic" one, silently. A miss now yields null,
 * and a null topic is <b>kept unfiltered</b> — the honest answer when the client has no scope
 * information to judge by.
 */
function topicForBadge(badge, candidates) {
  if (!badge || !candidates) return null;
  return candidates.find((t) => t?.type === badge.type && t?.label === badge.label) ?? null;
}

/**
 * The badges a card may show for one window, joined to their topics, scope-filtered, rarest first.
 *
 * <p>A badge with no matching topic is <b>kept</b> and carries {@code topic: null}: the backend put
 * it on this window, and the client has no scope information to judge it by, so dropping it would
 * be a filter applied on the strength of a missing join. The M2 popup renders such a row from the
 * badge's own fields with no science note.
 *
 * <p>An empty {@code scopeNames} means the scope is not known yet — the locations payload has not
 * landed — and nothing is filtered. The alternative would blank every region-scoped topic on a
 * cold mount and then quietly restore them, which reads as a bug rather than as loading.
 *
 * @param {string} key       the window's `date:targetType` key
 * @param {Array<object>} badges    the window's badges ({@code card.allBadges} — before any row
 *                                  promotion, so the matrix names every topic on the night)
 * @param {Map<string, Array<object>>} topicIndex from {@link buildTopicIndex}
 * @param {Array<string>} scopeNames the region names the page is scoped to
 * @returns {Array<{badge: object, topic: ?object, wholeSky: boolean, regionsInScope: ?number,
 *          scopedRegions: string[]}>} the topics to render, rarest first
 */
export function windowTopics(key, badges, topicIndex, scopeNames) {
  const candidates = topicIndex?.get?.(key) ?? [];
  const scope = new Set(Array.isArray(scopeNames) ? scopeNames : []);
  const rows = [];
  for (const badge of Array.isArray(badges) ? badges : []) {
    if (!badge) continue;
    const topic = topicForBadge(badge, candidates);
    const wholeSky = isWholeSkyTopic(badge.type);
    let regionsInScope = null;
    // ⚠️ The NAMES as well as the count, and they are the same array. The popup's scope note states
    // the count and offers the list as its tooltip; publishing the un-intersected `topic.regions`
    // there put a nine-region list under a label reading "1 region in scope" — the intersection
    // stated in the text and the roster stated in the tooltip, from one element. One filter, one
    // answer, and the caller cannot re-derive it differently because it is handed both.
    let scopedRegions = [];
    if (!wholeSky && scope.size > 0) {
      const regions = Array.isArray(topic?.regions) ? topic.regions : [];
      // Byte-identical membership, never normalised — `heatSpots.js` records why.
      if (regions.length > 0) {
        scopedRegions = regions.filter((name) => scope.has(name));
        regionsInScope = scopedRegions.length;
        if (regionsInScope === 0) continue;
      }
    }
    rows.push({ badge, topic, wholeSky, regionsInScope, scopedRegions });
  }
  // Rarest first — the payload's own `rarityRank` (1 = rarest), an absent rank sorting last.
  //
  // ⚠️ NO further tiebreak, and that is deliberate: `windowFirstRows.buildWindowRows` ranks the SAME
  // badges with the same one key and lets `Array.prototype.sort`'s stability (spec-guaranteed since
  // ES2019) hold the payload's order for a tie. A label tiebreak here was written first and removed
  // — it is a better ordering in the abstract, and it would have ordered two equal-rarity snow
  // topics one way on the card and the other way in the window row eight pixels below it. (The
  // promoted strip's `rankOfBadge` was the third surface under this rule and went at M5; the rule
  // binds the two that are left, and binds anything that joins them.)
  return rows.sort((a, b) => (a.badge.rarityRank ?? Number.MAX_SAFE_INTEGER)
    - (b.badge.rarityRank ?? Number.MAX_SAFE_INTEGER));
}
