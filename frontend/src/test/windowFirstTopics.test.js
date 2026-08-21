import { describe, it, expect } from 'vitest';
import {
  REGION_SCOPED_TOPIC_TYPES, WHOLE_SKY_TOPIC_TYPES,
  buildTopicIndex, isWholeSkyTopic, topicWindowKeys, windowTopics,
} from '../utils/windowFirstTopics.js';

/**
 * The window ↔ hot-topic join and its one filter (plan-matrix A8).
 *
 * <p>Two branches an obvious implementation gets wrong, and both are pinned here by fixtures shaped
 * like the real payload rather than like the rule:
 *
 * <ul>
 *   <li>a NIGHT topic buckets onto <b>two</b> windows — its own evening and the NEXT morning — so a
 *       {@code topic.date === card.date} equality silently loses aurora, NLC, meteor and supermoon
 *       from every morning card while the evening card still joins and every naive test passes;</li>
 *   <li>aurora and NLC serve <b>populated</b> {@code regions} lists that are not eligibility
 *       rosters, so an unexempted scope intersection deletes aurora from every away plan.</li>
 * </ul>
 */

/** A served hot topic, in the shape `/api/briefing` sends. */
function topic(overrides = {}) {
  return {
    type: 'KING_TIDE',
    label: 'King tide',
    detail: 'Highest water of the year',
    description: 'A perigean spring tide — the moon at its closest.',
    date: '2026-08-05',
    eventType: 'SUNRISE',
    regions: ['Northumberland & Tyneside'],
    rarityRank: 3,
    ...overrides,
  };
}

/** A window badge, as `PlanWindowProjector` builds it from that same topic. */
function badge(overrides = {}) {
  return {
    type: 'KING_TIDE', label: 'King tide', detail: 'Highest water of the year', rarityRank: 3,
    ...overrides,
  };
}

const NE = 'Northumberland & Tyneside';
const LAKES = 'The Lake District';

describe('topicWindowKeys — PlanWindowProjector.keysFor, replicated', () => {
  it('buckets a SUNRISE topic onto its own morning', () => {
    expect(topicWindowKeys(topic({ eventType: 'SUNRISE', date: '2026-08-05' })))
      .toEqual(['2026-08-05:SUNRISE']);
  });

  it('buckets a SUNSET topic onto its own evening', () => {
    expect(topicWindowKeys(topic({ eventType: 'SUNSET', date: '2026-08-05' })))
      .toEqual(['2026-08-05:SUNSET']);
  });

  it('buckets a NIGHT topic onto its evening AND the next morning', () => {
    // The branch a `topic.date === card.date` equality loses. A night that begins on the 5th ends
    // on the 6th, and the reader planning a 04:40 aurora shoot is looking at the 6th's card.
    expect(topicWindowKeys(topic({ type: 'AURORA', eventType: 'NIGHT', date: '2026-08-05' })))
      .toEqual(['2026-08-05:SUNSET', '2026-08-06:SUNRISE']);
  });

  it('crosses a month boundary on the NIGHT case', () => {
    expect(topicWindowKeys(topic({ eventType: 'NIGHT', date: '2026-08-31' })))
      .toEqual(['2026-08-31:SUNSET', '2026-09-01:SUNRISE']);
  });

  it('buckets a topic with no solar anchor nowhere', () => {
    // `HotTopicEventEnricher` leaves `eventType` null for a topic with no anchor, and the projector
    // skips it. Storm surge and clearance topics reach the briefing this way.
    expect(topicWindowKeys(topic({ eventType: null }))).toEqual([]);
    expect(topicWindowKeys(topic({ eventType: 'DAWN_CHORUS' }))).toEqual([]);
  });

  it('buckets a topic with no date nowhere, rather than keying on undefined', () => {
    expect(topicWindowKeys(topic({ date: null }))).toEqual([]);
    expect(topicWindowKeys(null)).toEqual([]);
  });
});

describe('isWholeSkyTopic — the exemption is by TYPE', () => {
  /**
   * The backend's whole roster, copied by hand from `TopicRarity.RANK_BY_TYPE`.
   *
   * <p>⚠️ A LITERAL, deliberately not derived from the two exported sets. Driving the assertions off
   * the sets makes them their own fixture: the first cut of this file did exactly that with
   * `it.each([...SET])`, and the set was missing `SNOW_TOPS` — a live, genuinely region-scoped
   * strategy — so a name could go missing and nothing could notice. With a third list in play,
   * adding a type to a set does not satisfy the union test on its own, and neither does adding it
   * here: both have to move, which is the point.
   */
  const SHIPPED_TOPIC_TYPES = [
    'ECLIPSE', 'SUPERMOON', 'EQUINOX', 'KING_TIDE', 'AURORA', 'METEOR', 'STORM_SURGE',
    'INVERSION', 'SNOW_TOPS', 'SNOW_MIST', 'SNOW_FRESH', 'DUST', 'CLEARANCE', 'BLUEBELL',
    'NLC', 'SPRING_TIDE',
  ];

  it.each([...WHOLE_SKY_TOPIC_TYPES])('exempts %s', (type) => {
    expect(isWholeSkyTopic(type)).toBe(true);
  });

  it.each([...REGION_SCOPED_TOPIC_TYPES])('scopes %s', (type) => {
    expect(isWholeSkyTopic(type)).toBe(false);
  });

  it('⚠️ classifies EVERY shipped type, because the filter fails open', () => {
    // The rule this file exists to protect. `isWholeSkyTopic` exempts anything it does not
    // recognise, so a name missing from the region-scoped set is not a loud failure — it is a
    // region-scoped topic silently surviving every away plan while its sibling is filtered.
    const classified = [...WHOLE_SKY_TOPIC_TYPES, ...REGION_SCOPED_TOPIC_TYPES].sort();
    expect(classified).toEqual([...SHIPPED_TOPIC_TYPES].sort());
  });

  it('classifies every shipped type exactly ONCE', () => {
    const overlap = [...WHOLE_SKY_TOPIC_TYPES].filter((t) => REGION_SCOPED_TOPIC_TYPES.has(t));
    expect(overlap).toEqual([]);
  });

  it('scopes SNOW_TOPS, which an eight-name set had lost', () => {
    // Named on its own rather than left to the union test, because it is the miss that happened:
    // `SnowTopsHotTopicStrategy` builds through `PerDateHotTopicBuilder`, whose every day's topic
    // carries "only its regions" — the same eligibility semantics as its SNOW_FRESH sibling.
    expect(isWholeSkyTopic('SNOW_TOPS')).toBe(false);
    expect(isWholeSkyTopic('SNOW_FRESH')).toBe(false);
  });

  it('exempts an UNRECOGNISED type, so a new topic kind fails quietly and additively', () => {
    // `badgeChannel`'s rule applied to a filter rather than to a colour. Showing a topic the scope
    // might not have wanted is a mild over-claim; deleting one outright is a silent loss. It is
    // also why the union test above has to exist.
    expect(isWholeSkyTopic('MURMURATION')).toBe(true);
  });
});

describe('windowTopics — the join', () => {
  it('joins a badge to its topic on type and label', () => {
    const rows = windowTopics('2026-08-05:SUNRISE', [badge()], buildTopicIndex([topic()]), [NE]);
    expect(rows).toHaveLength(1);
    expect(rows[0].topic.description).toBe('A perigean spring tide — the moon at its closest.');
  });

  it('joins a NIGHT topic to the badge on the NEXT morning\'s card', () => {
    const aurora = topic({
      type: 'AURORA', label: 'Aurora', eventType: 'NIGHT', date: '2026-08-05', rarityRank: 1,
    });
    const rows = windowTopics(
      '2026-08-06:SUNRISE',
      [badge({ type: 'AURORA', label: 'Aurora', rarityRank: 1 })],
      buildTopicIndex([aurora]),
      [NE],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0].topic.type).toBe('AURORA');
  });

  it('keeps a badge whose topic cannot be found, carrying a null topic', () => {
    // The degrade: the backend put the badge on this window, and the client has no scope
    // information to judge it by — so dropping it would be a filter applied on a missing join.
    const rows = windowTopics('2026-08-05:SUNRISE', [badge()], buildTopicIndex([]), [NE]);
    expect(rows).toHaveLength(1);
    expect(rows[0].topic).toBeNull();
  });

  it('orders rarest first', () => {
    const rows = windowTopics(
      '2026-08-05:SUNRISE',
      [
        badge({ type: 'DUST', label: 'Sahara dust', rarityRank: 5 }),
        badge({ type: 'AURORA', label: 'Aurora', rarityRank: 1 }),
        badge({ type: 'INVERSION', label: 'Inversion', rarityRank: 3 }),
      ],
      buildTopicIndex([]),
      [NE],
    );
    expect(rows.map((r) => r.badge.label)).toEqual(['Aurora', 'Inversion', 'Sahara dust']);
  });

  it('⚠️ leaves a rarity TIE in the payload\'s own order, as the two sibling rankers do', () => {
    // The fixture is deliberately REVERSE-alphabetical. An earlier cut added a label tiebreak here
    // and pinned it with an already-sorted pair, so the assertion passed on `Array.prototype.sort`'s
    // stability alone and could not tell the two orderings apart. The tiebreak is now gone —
    // `windowFirstRows` ranks the same badges on rank alone — so this fixture proves the card and
    // the window row agree rather than proving a sort ran.
    const rows = windowTopics(
      '2026-08-05:SUNRISE',
      [
        badge({ type: 'SNOW_TOPS', label: 'Snow on the tops', rarityRank: 9 }),
        badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 9 }),
      ],
      buildTopicIndex([]),
      [NE],
    );
    expect(rows.map((r) => r.badge.label)).toEqual(['Snow on the tops', 'Fresh snow']);
  });

  it('sorts a badge with NO rarity rank last', () => {
    // `rarityRank` is `undefined` for a badge type the rarity table has no entry for, and for a
    // payload cached before the field existed; an absent rank must not read as the rarest thing on
    // the card.
    const rows = windowTopics(
      '2026-08-05:SUNRISE',
      [
        badge({ type: 'DUST', label: 'Sahara dust', rarityRank: undefined }),
        badge({ type: 'AURORA', label: 'Aurora', rarityRank: 1 }),
      ],
      buildTopicIndex([]),
      [NE],
    );
    expect(rows.map((r) => r.badge.label)).toEqual(['Aurora', 'Sahara dust']);
  });

  it('⚠️ does NOT fall back to a type-only match when the labels disagree', () => {
    // A type-only fallback was written and removed. The joined topic is where `regions` comes from,
    // so it decides whether a badge is DROPPED — and with two topics of one type on one window (an
    // ordinary snow day) the fallback resolves badge B to topic A's regions and deletes a badge
    // whose own regions ARE in scope. A miss must yield null, and a null topic is kept unfiltered.
    const inScope = topic({
      type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 11, regions: [LAKES],
    });
    const outOfScope = topic({
      type: 'SNOW_FRESH', label: 'Snow overnight', rarityRank: 11, regions: [NE],
    });
    const rows = windowTopics(
      '2026-08-05:SUNRISE',
      [badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 11 })],
      buildTopicIndex([outOfScope, inScope]),
      [LAKES],
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].topic.regions).toEqual([LAKES]);
    expect(rows[0].regionsInScope).toBe(1);
  });
});

describe('windowTopics — the scope filter', () => {
  it('drops a region-scoped topic when the scope intersection empties', () => {
    // Planning from the Lake District drops a Northumberland king tide by itself — the design's
    // own example, and the whole reason this filter exists.
    const rows = windowTopics(
      '2026-08-05:SUNRISE', [badge()], buildTopicIndex([topic({ regions: [NE] })]), [LAKES],
    );
    expect(rows).toEqual([]);
  });

  it('keeps a region-scoped topic when one of its regions is in scope, and counts them', () => {
    const rows = windowTopics(
      '2026-08-05:SUNRISE',
      [badge()],
      buildTopicIndex([topic({ regions: [NE, LAKES, 'Yorkshire Coast'] })]),
      [LAKES, 'Yorkshire Coast'],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0].regionsInScope).toBe(2);
  });

  it('keeps a WHOLE-SKY topic under an away origin its populated regions do not name', () => {
    // ⚠️ The live hazard. Aurora's `regions` is Bortle-enrichment coverage and NLC's is
    // where-it-is-clear-tonight — both populated, neither an eligibility roster — so an unexempted
    // intersection deletes aurora from every away plan while every naive test passes.
    const aurora = topic({
      type: 'AURORA', label: 'Aurora', rarityRank: 1, regions: [NE, 'Galloway'],
    });
    const rows = windowTopics(
      '2026-08-05:SUNRISE',
      [badge({ type: 'AURORA', label: 'Aurora', rarityRank: 1 })],
      buildTopicIndex([aurora]),
      [LAKES],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0].wholeSky).toBe(true);
    // No scope count either: the number would describe a set that means something else.
    expect(rows[0].regionsInScope).toBeNull();
  });

  it('keeps a region-scoped topic that serves an EMPTY regions list', () => {
    // Empty is "not stated", not "nowhere" — `HotTopic.regions` is documented as "may be empty".
    const rows = windowTopics(
      '2026-08-05:SUNRISE', [badge()], buildTopicIndex([topic({ regions: [] })]), [LAKES],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0].regionsInScope).toBeNull();
  });

  it('keeps a region-scoped badge whose topic is missing entirely', () => {
    const rows = windowTopics('2026-08-05:SUNRISE', [badge()], buildTopicIndex([]), [LAKES]);
    expect(rows).toHaveLength(1);
  });

  it('filters nothing while the scope is unknown', () => {
    // A cold mount: the locations payload has not landed, so `scopeRegions` is empty. Blanking
    // every region-scoped topic and then quietly restoring them reads as a bug, not as loading.
    const rows = windowTopics(
      '2026-08-05:SUNRISE', [badge()], buildTopicIndex([topic({ regions: [NE] })]), [],
    );
    expect(rows).toHaveLength(1);
  });

  it('matches region names byte-identically, never normalised', () => {
    // `heatSpots.js`' rule: a trim here would make " North East" on one payload join
    // "North East" on the other, and the two are different keys on purpose.
    const rows = windowTopics(
      '2026-08-05:SUNRISE',
      [badge()],
      buildTopicIndex([topic({ regions: [' Northumberland & Tyneside'] })]),
      [NE],
    );
    expect(rows).toEqual([]);
  });
});

describe('buildTopicIndex — degrade', () => {
  it('indexes nothing from a null topic list rather than throwing', () => {
    expect(buildTopicIndex(null).size).toBe(0);
  });

  it('puts a NIGHT topic under both of its keys', () => {
    const index = buildTopicIndex([topic({ eventType: 'NIGHT', date: '2026-08-05' })]);
    expect([...index.keys()].sort()).toEqual(['2026-08-05:SUNSET', '2026-08-06:SUNRISE']);
  });

  it('collects two topics anchored to the same window under one key', () => {
    const index = buildTopicIndex([
      topic({ type: 'SNOW_FRESH', label: 'Fresh snow' }),
      topic({ type: 'SNOW_MIST', label: 'Snow mist' }),
    ]);
    expect(index.get('2026-08-05:SUNRISE')).toHaveLength(2);
  });
});
