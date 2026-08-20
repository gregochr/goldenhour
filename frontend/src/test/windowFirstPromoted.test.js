import { describe, it, expect } from 'vitest';
import {
  MIN_COINCIDENCE_BADGES, SOLO_PROMOTION_RANK, UNKNOWN_RANK, buildPromotedStrip,
} from '../utils/windowFirstPromoted.js';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildPaneItems } from '../utils/windowFirstAway.js';

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';
const DAY_AFTER = '2026-08-06';

/**
 * A badge as `BriefingWindow.Badge` serialises one — facts always present, often empty, and
 * `rarityRank` always an int on the wire (`TopicRarity.rankOf` never returns null).
 */
function badge(overrides = {}) {
  return {
    type: 'SNOW_TOPS',
    label: 'Snow on the fells',
    detail: 'The tops held their snow overnight.',
    facts: [{ key: 'snow line', value: '~850 m', dir: null, emphasis: true, optional: false }],
    eventTime: '05:31',
    rarityRank: 8,
    ...overrides,
  };
}

/**
 * Rank 4 — rarer than snow, so it wins a straight contest against the default badge above.
 *
 * <p>⚠️ The label and the fact are the shapes `AuroraHotTopicStrategy` really emits: the label is
 * "Aurora possible", and the headline fact is `HotTopicFact.metric(null, …)` — **keyless** and
 * emphasised. An earlier version of this fixture invented `{ key: 'Kp', value: '5.7' }`, a shape the
 * backend never produces, and that invention hid a real defect through the whole suite, eleven
 * mutants and a browser pass: the figure's lead-in fell back to the topic's own label, so the strip
 * printed "Aurora possible" twice. Keep these fixtures shaped like the producer.
 */
const AURORA = badge({
  type: 'AURORA',
  label: 'Aurora possible',
  rarityRank: 4,
  facts: [{
    key: null,
    value: 'Kp 5 · glow reaches ~57°N and north',
    dir: null,
    emphasis: true,
    optional: false,
  }],
});

/** `NlcHotTopicStrategy`'s real shape: keyless facts carrying a window, with a compass `dir`. */
const NLC = badge({
  type: 'NLC',
  label: 'Noctilucent cloud season',
  rarityRank: 14,
  facts: [{
    key: null,
    value: 'after dusk · 23:14–01:02',
    dir: 'N',
    emphasis: false,
    optional: false,
  }],
});

/** Rank 3 — the rarest kind used in this file, and a different channel again. */
const KING_TIDE = badge({
  type: 'KING_TIDE',
  label: 'King tide',
  rarityRank: 3,
  facts: [{ key: 'high water', value: '5.8 m', dir: null, emphasis: true, optional: false }],
});

/** Rank 2 — used to prove an unranked badge cannot outrank a rare one. */
const SUPERMOON = badge({ type: 'SUPERMOON', label: 'Supermoon', rarityRank: 2 });

/**
 * Rank 1, and the only kind that clears {@link SOLO_PROMOTION_RANK}.
 *
 * <p>Shaped like `EclipseHotTopicStrategy` really emits it: an emphasised `max` fact carrying
 * coverage and the sun's altitude with a compass `dir`, a composed `rarityNote`, and a
 * `safetyNote`. The figures are the ones the reduction returns for the Northumberland coast.
 */
const ECLIPSE = badge({
  type: 'ECLIPSE',
  label: 'Deep partial eclipse',
  detail: '90% of the sun covered, and it sits only 13° up',
  rarityRank: 1,
  eventTime: '19:06',
  note: 'a clear low western horizon matters more than the last 2%',
  rarityNote: 'nothing comparable from the UK until 2081 · 1h 51m of it',
  safetyNote: 'Certified solar filter on the lens — not only over your eye',
  facts: [{
    key: 'max', value: '90% covered · sun only 13° up', dir: 'W', emphasis: true, optional: false,
  }],
});

function summary(targetType, window = {}) {
  return {
    targetType,
    regions: [],
    unregioned: [],
    window: { verdict: 'WORTH_IT', badges: [], ...window },
  };
}

function day(date, summaries) {
  return { date, eventSummaries: summaries };
}

function events(...pairs) {
  return pairs.map(([date, targetType]) => ({ date, targetType }));
}

/**
 * The pane items exactly as the provider derives them — through `buildWindowCards` and then
 * `buildPaneItems`, never hand-written.
 *
 * <p>This is the file's most load-bearing decision. The strip counts `card.allBadges` and reads
 * `card.topRarityRank`, and both are fields `buildWindowCards` has to put there; a hand-written card
 * fixture would keep every test below green while the descriptor stopped carrying either of them.
 * Building the chain for real means the two modules cannot drift apart silently.
 *
 * @param {Array} spec  `[[date, targetType, windowOverrides]]`, in the order the pane draws them
 * @param {Set}   away  dates the operator is away, folded back in as rows
 */
function paneFor(spec, away = new Set(), travelRanges = []) {
  const byDate = new Map();
  for (const [date, targetType, window] of spec) {
    if (!byDate.has(date)) byDate.set(date, []);
    byDate.get(date).push(summary(targetType, window));
  }
  const days = [...byDate.entries()].map(([date, summaries]) => day(date, summaries));
  const upcoming = events(...spec.map(([date, targetType]) => [date, targetType]));
  const cards = buildWindowCards(upcoming, days, TODAY, TOMORROW, away, new Map());
  return buildPaneItems(upcoming, cards, away, travelRanges);
}

describe('buildPromotedStrip — what earns a strip', () => {
  it('exports the spec\'s own threshold, which is two attributes', () => {
    expect(MIN_COINCIDENCE_BADGES).toBe(2);
  });

  it('exports the solo-promotion rank, and it is rank 1 alone', () => {
    // Not a free parameter. Rank 1 is exactly one kind on the backend (a test there pins the
    // count), and it is also the largest value that leaves the two "one badge earns no strip"
    // tests below green — their fixtures sit at rank 4. Raising it to 2 would admit SUPERMOON,
    // which happens several times a year, and the pane's lede would become its wallpaper.
    expect(SOLO_PROMOTION_RANK).toBe(1);
  });

  it('returns nothing when the pane has no windows at all', () => {
    expect(buildPromotedStrip([])).toBeNull();
    expect(buildPromotedStrip(null)).toBeNull();
  });

  it('returns nothing for a window carrying no badges', () => {
    expect(buildPromotedStrip(paneFor([[TODAY, 'SUNSET', { badges: [] }]]))).toBeNull();
  });

  // One below the threshold. This is the case that matters most: `topRarityRank` is populated on a
  // single-badge window too (`rarestRank` is a min, null only for an EMPTY list), so a strip keyed
  // on that field rather than on the badge count would fire here.
  it('returns nothing for a window carrying one badge, even though it has a rarity rank', () => {
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [AURORA], topRarityRank: 4 }]]);
    expect(pane[0].card.topRarityRank).toBe(4);
    expect(buildPromotedStrip(pane)).toBeNull();
  });

  it('promotes a window carrying two badges', () => {
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [badge(), AURORA], topRarityRank: 4 }]]);
    expect(buildPromotedStrip(pane).windowKey).toBe(`${TODAY}:SUNSET`);
  });

  // One above the threshold, and it must not split into two strips or drop the third topic.
  it('promotes a window carrying three badges and names all three', () => {
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [badge(), AURORA, KING_TIDE], topRarityRank: 3 }],
    ]);
    expect(buildPromotedStrip(pane).topics.map((t) => t.label))
      .toEqual(['King tide', 'Aurora possible', 'Snow on the fells']);
  });

  // The row promotion runs first and removes a factful snow badge from `card.badges`. A winter dawn
  // carrying two snow topics is a real coincidence and the commonest same-channel one, so counting
  // the filtered list would delete it. `allBadges` is what makes this pass.
  it('⚠️ counts the WHOLE badge list, which is the only list there is now', () => {
    // This used to be "counts a badge the attribute rows already promoted out of the card header":
    // the strip read `allBadges` because `badges` had been reduced by the row promotion, and a
    // winter dawn carrying SNOW_TOPS + SNOW_FRESH would otherwise have read as a single-badge
    // window. M2 deleted the promotion and `badges` with it, so the two lists are one — and this
    // still has to count that pair, which is what the assertion pins.
    const fresh = badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 });
    const pane = paneFor([[TODAY, 'SUNRISE', { badges: [badge(), fresh], topRarityRank: 8 }]]);
    expect(pane[0].card.allBadges).toHaveLength(MIN_COINCIDENCE_BADGES);
    expect(buildPromotedStrip(pane).topics.map((t) => t.label))
      .toEqual(['Snow on the fells', 'Fresh snow']);
  });

  it('ignores an away row, which carries no window', () => {
    const away = new Set([TOMORROW]);
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [badge(), AURORA] }],
    ], away);
    expect(pane.some((item) => item.kind === 'away')).toBe(true);
    // The away day's window never became a card, so its badges are not on the pane to promote.
    expect(buildPromotedStrip(pane)).toBeNull();
  });
});

describe('buildPromotedStrip — which coincidence wins', () => {
  it('promotes the rarest coincidence when two windows carry one', () => {
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [badge(), badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 })], topRarityRank: 8 }],
      [TOMORROW, 'SUNRISE', { badges: [KING_TIDE, AURORA], topRarityRank: 3 }],
    ]);
    expect(buildPromotedStrip(pane).windowKey).toBe(`${TOMORROW}:SUNRISE`);
  });

  // The pane's only ordering spine is time, so an equal rarity leaves the strip on the window the
  // reader reaches first. Written with the LATER window listed first in the badge lists as well, so
  // a comparator that happened to prefer the last-seen equal would be caught.
  it('leaves the strip on the earlier window when two coincidences tie on rarity', () => {
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [AURORA, badge()], topRarityRank: 4 }],
      [TOMORROW, 'SUNRISE', { badges: [AURORA, badge()], topRarityRank: 4 }],
    ]);
    expect(buildPromotedStrip(pane).windowKey).toBe(`${TODAY}:SUNSET`);
  });

  it('reads topRarityRank rather than recomputing it from the badges', () => {
    // Deliberately inconsistent: window A's badges are ranked 8 and 10, but the payload says 1. Only
    // a reader that trusts the published field picks A.
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [badge(), badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 })], topRarityRank: 1 }],
      [TOMORROW, 'SUNRISE', { badges: [KING_TIDE, AURORA], topRarityRank: 3 }],
    ]);
    expect(buildPromotedStrip(pane).windowKey).toBe(`${TODAY}:SUNSET`);
  });

  // A payload cached before `topRarityRank` existed replays with the key absent entirely — a
  // different input from null, and the one legacy `daily_briefing_cache` JSON actually produces.
  it('recomputes the rank from the badges when topRarityRank is missing from the payload', () => {
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [badge(), badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 })] }],
      [TOMORROW, 'SUNRISE', { badges: [KING_TIDE, AURORA] }],
    ]);
    expect(pane[0].card.topRarityRank).toBeUndefined();
    expect(buildPromotedStrip(pane).windowKey).toBe(`${TOMORROW}:SUNRISE`);
  });

  it('recomputes the rank when topRarityRank is present but null', () => {
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [badge(), badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 })], topRarityRank: null }],
      [TOMORROW, 'SUNRISE', { badges: [KING_TIDE, AURORA], topRarityRank: null }],
    ]);
    expect(buildPromotedStrip(pane).windowKey).toBe(`${TOMORROW}:SUNRISE`);
  });
});

describe('buildPromotedStrip — UNKNOWN_RANK wire semantics', () => {
  it('exports Integer.MAX_VALUE, which is what the backend actually sends', () => {
    expect(UNKNOWN_RANK).toBe(2147483647);
  });

  // Not "is dropped": the topic landed on the window, so it IS one of the two attributes. Only its
  // ranking is unknown.
  it('counts an unranked topic toward the coincidence', () => {
    const unranked = badge({ type: 'MYSTERY', label: 'Something new', rarityRank: UNKNOWN_RANK });
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [unranked, badge({ type: 'OTHER', label: 'Another', rarityRank: UNKNOWN_RANK })] }]]);
    expect(buildPromotedStrip(pane).topics.map((t) => t.label))
      .toEqual(['Something new', 'Another']);
  });

  it('sorts an unranked topic last within its own strip', () => {
    const unranked = badge({ type: 'MYSTERY', label: 'Something new', rarityRank: UNKNOWN_RANK });
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [unranked, AURORA], topRarityRank: 4 }]]);
    expect(buildPromotedStrip(pane).topics.map((t) => t.label)).toEqual(['Aurora possible', 'Something new']);
  });

  it('loses a rarity contest to any ranked coincidence', () => {
    const unranked = badge({ type: 'MYSTERY', label: 'Something new', rarityRank: UNKNOWN_RANK });
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [unranked, badge({ type: 'OTHER', label: 'Another', rarityRank: UNKNOWN_RANK })] }],
      [TOMORROW, 'SUNRISE', { badges: [badge(), badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 })] }],
    ]);
    expect(buildPromotedStrip(pane).windowKey).toBe(`${TOMORROW}:SUNRISE`);
  });

  // Rank 0 would be rarer than SUPERMOON, so a badge missing its rank would hand the strip to
  // whichever window had the most broken data. The window with SUPERMOON must still win.
  it('treats a missing rarityRank as unknown rather than as rank zero', () => {
    const noRank = badge({ type: 'MYSTERY', label: 'Something new' });
    delete noRank.rarityRank;
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [noRank, badge({ type: 'OTHER', label: 'Another' })] }],
      [TOMORROW, 'SUNRISE', { badges: [SUPERMOON, AURORA] }],
    ]);
    expect(buildPromotedStrip(pane).windowKey).toBe(`${TOMORROW}:SUNRISE`);
  });
});

describe('buildPromotedStrip — what the strip says', () => {
  it('names the window exactly as the card names it, kicker included', () => {
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [badge(), AURORA] }]]);
    const strip = buildPromotedStrip(pane);
    // The lead card's own composition — `card.kicker` + `card.when` — so the strip and the card it
    // points at cannot name one window two ways.
    expect(pane[0].card.kicker).toBe('Tonight');
    expect(pane[0].card.when).toBe('Sunset');
    expect(strip.when).toBe('Tonight Sunset');
  });

  it('names a non-lead window by its day and event', () => {
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [badge(), AURORA] }],
    ]);
    expect(buildPromotedStrip(pane).when).toBe('Tomorrow sunrise');
  });

  it('takes its channel from the rarest topic, not the first in the payload', () => {
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [badge(), KING_TIDE], topRarityRank: 3 }]]);
    expect(buildPromotedStrip(pane).channel).toBe('tide');
  });

  it('takes the snow channel when snow is the rarest of the pair', () => {
    const nlc = badge({ type: 'NLC', label: 'NLC', rarityRank: 14 });
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [nlc, badge()], topRarityRank: 8 }]]);
    expect(buildPromotedStrip(pane).channel).toBe('snow');
  });

  it('leads each figure with the topic\'s emphasised fact', () => {
    const twoFacts = badge({
      facts: [
        { key: 'mist', value: 'humidity 94%', dir: null, emphasis: false, optional: true },
        { key: 'snow line', value: '~850 m', dir: null, emphasis: true, optional: false },
      ],
    });
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [twoFacts, AURORA], topRarityRank: 4 }]]);
    const snow = buildPromotedStrip(pane).topics.find((t) => t.label === 'Snow on the fells');
    expect(snow.figureLabel).toBe('snow line');
    expect(snow.figureValue).toBe('~850 m');
  });

  // ⚠️ §6 bans counts of our own data, and `MeteorHotTopicStrategy.addClearSkyFact` emits exactly
  // one — "clear at 3 of 7 dark-sky locations" — as a FACT rather than in `detail` the way aurora
  // and NLC do. It is appended last with `emphasis: false` while the ZHR leads with
  // `emphasis: true`, so the emphasis preference is what keeps it off the strip. Written against
  // the real fact order and flags `MeteorHotTopicStrategy` produces.
  it('leads a meteor topic with its ZHR, never with the clear-location count §6 bans', () => {
    const meteor = badge({
      type: 'METEOR',
      label: 'Meteor shower',
      rarityRank: 5,
      facts: [
        { key: 'ZHR', value: '~100 at peak', dir: null, emphasis: true, optional: false },
        { key: 'radiant', value: 'best 02:00–04:00', dir: 'NE', emphasis: false, optional: false },
        { key: 'moon', value: '18% · dark enough', dir: null, emphasis: false, optional: true },
        {
          key: null,
          value: 'clear at 3 of 7 dark-sky locations',
          dir: null,
          emphasis: false,
          optional: false,
        },
      ],
    });
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [meteor, badge()], topRarityRank: 5 }]]);
    const strip = buildPromotedStrip(pane);
    const shower = strip.topics.find((t) => t.label === 'Meteor shower');
    expect(shower.figureValue).toBe('~100 at peak');
    // The whole descriptor, not just this figure: no part of the strip may carry the count.
    expect(JSON.stringify(strip)).not.toContain('dark-sky locations');
  });

  it('falls back to the first fact when the topic marks none as the headline', () => {
    const noEmphasis = badge({
      facts: [{ key: 'mist', value: 'humidity 94%', dir: null, emphasis: false, optional: true }],
    });
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [noEmphasis, AURORA], topRarityRank: 4 }]]);
    const snow = buildPromotedStrip(pane).topics.find((t) => t.label === 'Snow on the fells');
    expect(snow.figureValue).toBe('humidity 94%');
  });

  // ⚠️ The regression this file previously asserted the WRONG WAY ROUND. `HotTopicFact.key` is
  // nullable, and the two live producers of a keyless HEADLINE fact are aurora and NLC — both NIGHT
  // topics, which the projector buckets onto the same two windows, so aurora × NLC is the most
  // reachable coincidence there is. Falling back to the topic's own label printed that label twice
  // on one 131px element: once in the kicker, once as the figure's lead-in.
  it('gives a keyless fact no lead-in, rather than repeating the topic name it already shows', () => {
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [AURORA, NLC], topRarityRank: 4 }]]);
    const strip = buildPromotedStrip(pane);
    expect(strip.topics.map((t) => t.label)).toEqual(['Aurora possible', 'Noctilucent cloud season']);
    expect(strip.topics.map((t) => t.figureLabel)).toEqual([null, null]);
    expect(strip.topics.map((t) => t.figureValue))
      .toEqual(['Kp 5 · glow reaches ~57°N and north', 'after dusk · 23:14–01:02']);
    // The rule stated directly: no figure's lead-in may be a topic name the kicker already shows.
    // (Asserted on the rendered fields rather than over the whole descriptor — `topicKey` embeds the
    // label by construction, so a stringify-and-count would report every label twice and fail for a
    // reason that has nothing to do with what is on screen.)
    const names = new Set(strip.topics.map((t) => t.label));
    for (const topic of strip.topics) {
      expect(names.has(topic.figureLabel)).toBe(false);
    }
  });

  // The other half of the same rule: a fact that DOES carry a key still shows it.
  it('keeps a fact\'s own lead-in when it has one', () => {
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [badge(), AURORA], topRarityRank: 4 }]]);
    const snow = buildPromotedStrip(pane).topics.find((t) => t.label === 'Snow on the fells');
    expect(snow.figureLabel).toBe('snow line');
    expect(snow.figureValue).toBe('~850 m');
  });

  it('carries no figure for a topic with no facts, and still names it', () => {
    const factless = badge({ type: 'EQUINOX', label: 'Equinox', rarityRank: 2, facts: [] });
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [factless, badge()], topRarityRank: 2 }]]);
    const equinox = buildPromotedStrip(pane).topics.find((t) => t.label === 'Equinox');
    expect(equinox.figureValue).toBeNull();
    expect(equinox.figureLabel).toBeNull();
  });

  // Pinned against the CARD's formatted time rather than against the payload's, because
  // `formatTime` renders the zone-less UTC instant in Europe/London — a 21:11 event is 22:11 in
  // BST. Formatting it a second time here is how the two surfaces would come to disagree by an
  // hour, which is a defect this arm has already shipped once on the rail footer's age.
  it('carries the window time the card carries, already formatted', () => {
    const pane = paneFor([[TODAY, 'SUNSET', {
      badges: [badge(), AURORA], eventTime: `${TODAY}T21:11:00`,
    }]]);
    expect(pane[0].card.time).toBe('22:11');
    expect(buildPromotedStrip(pane).time).toBe(pane[0].card.time);
  });

  it('carries an empty time when the window has none, rather than a placeholder', () => {
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [badge(), AURORA] }]]);
    expect(buildPromotedStrip(pane).time).toBe('');
  });
});

describe('buildPromotedStrip — adjacency to the card it points at', () => {
  it('is adjacent when the promoted window is the pane\'s first item', () => {
    const pane = paneFor([[TODAY, 'SUNSET', { badges: [badge(), AURORA] }]]);
    expect(buildPromotedStrip(pane).adjacent).toBe(true);
  });

  it('is not adjacent when another window\'s card comes first', () => {
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [badge(), AURORA] }],
    ]);
    expect(buildPromotedStrip(pane).adjacent).toBe(false);
  });

  // The case that makes `paneItems` the right input rather than `windowCards`: the promoted window
  // IS the first card, but an away row sits above it, so the strip is not next to it.
  it('is not adjacent when an away row sits above the promoted card', () => {
    const away = new Set([TODAY]);
    const pane = paneFor([
      [TODAY, 'SUNSET', { badges: [] }],
      [TOMORROW, 'SUNRISE', { badges: [badge(), AURORA] }],
      [DAY_AFTER, 'SUNRISE', { badges: [] }],
    ], away, [{ startDate: TODAY, endDate: TODAY, note: null }]);
    expect(pane[0].kind).toBe('away');
    expect(buildPromotedStrip(pane).adjacent).toBe(false);
  });
});

describe('buildPromotedStrip — the solo-rarity rule', () => {
  it('gives a lone rank-1 topic the strip, and says the rule that put it there', () => {
    const strip = buildPromotedStrip(paneFor([[TODAY, 'SUNSET', { badges: [ECLIPSE] }]]));

    expect(strip).not.toBeNull();
    expect(strip.reason).toBe('rarity');
    expect(strip.topics).toHaveLength(1);
    expect(strip.topics[0].label).toBe('Deep partial eclipse');
  });

  it('still refuses a lone topic that is merely rare, not rank 1', () => {
    // SUPERMOON is rank 2 — the rarest kind after the eclipse, and still a few times a year.
    // This is the assertion that keeps the rule narrow.
    expect(buildPromotedStrip(paneFor([[TODAY, 'SUNSET', { badges: [SUPERMOON] }]]))).toBeNull();
    expect(buildPromotedStrip(paneFor([[TODAY, 'SUNSET', { badges: [AURORA] }]]))).toBeNull();
  });

  it('lets the rarer window win, even when the commoner one is a pair', () => {
    // The rule an adversarial review corrected. An earlier cut gave any coincidence strict
    // precedence, so this rank-3 king tide took the strip from the rank-1 eclipse — and since the
    // descriptor sources the warning from the WINNING card alone, the eclipse's safety warning
    // left the strip with it.
    const strip = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [ECLIPSE] }],
      [TOMORROW, 'SUNRISE', { badges: [KING_TIDE, AURORA] }],
    ]));

    expect(strip.reason).toBe('rarity');
    expect(strip.topics.map((t) => t.label)).toEqual(['Deep partial eclipse']);
    expect(strip.safetyNote).toContain('solar filter');
  });

  it('gives a tie on rank to the coincidence, which has a relationship to name', () => {
    // Both windows rank 1: one is the eclipse alone, the other is the eclipse paired with a king
    // tide. Naming the pair is the strip's original job, so the pair takes it — and the warning
    // rides along either way.
    const strip = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [ECLIPSE] }],
      [TOMORROW, 'SUNRISE', { badges: [ECLIPSE, KING_TIDE] }],
    ]));

    expect(strip.reason).toBe('coincidence');
    expect(strip.topics).toHaveLength(2);
    expect(strip.safetyNote).toContain('solar filter');
  });

  it('still lets a rarer coincidence beat a commoner one', () => {
    // The original rule, unchanged: between two pairs, the rarer wins.
    const strip = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [NLC, badge()] }],
      [TOMORROW, 'SUNRISE', { badges: [KING_TIDE, AURORA] }],
    ]));

    expect(strip.reason).toBe('coincidence');
    expect(strip.topics[0].label).toBe('King tide');
  });

  it('carries the rarity line and the topic\'s own sentence on a solo strip', () => {
    const strip = buildPromotedStrip(paneFor([[TODAY, 'SUNSET', { badges: [ECLIPSE] }]]));

    expect(strip.rarityNote).toBe('nothing comparable from the UK until 2081 · 1h 51m of it');
    // The topic's editorial NOTE, never its `detail`. `detail` restates the measurements the
    // headline figure already carries, and printing both put the same two numbers on one strip
    // twice — a browser found that, and both unit assertions had passed.
    expect(strip.why).toBe('a clear low western horizon matters more than the last 2%');
    expect(strip.why).not.toContain('91%');
    expect(strip.why).not.toContain('13°');
  });

  it('carries neither on a coincidence — a sentence about one topic is not about the pair', () => {
    const strip = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [KING_TIDE, AURORA] }],
    ]));

    expect(strip.reason).toBe('coincidence');
    expect(strip.why).toBeNull();
    expect(strip.rarityNote).toBeNull();
  });

  it('carries the safety warning under EITHER rule', () => {
    // The exception to the rule above, and it is not symmetric with it. A warning is not a
    // description of the strip's subject — it is a hazard of acting on it — so a coincidence that
    // happens to contain an eclipse is still an eclipse.
    const solo = buildPromotedStrip(paneFor([[TODAY, 'SUNSET', { badges: [ECLIPSE] }]]));
    const pair = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [ECLIPSE, KING_TIDE] }],
    ]));

    const warning = 'Certified solar filter on the lens — not only over your eye';
    expect(solo.safetyNote).toBe(warning);
    expect(pair.reason).toBe('coincidence');
    expect(pair.safetyNote).toBe(warning);
  });

  it('carries no warning when no topic on the window has one', () => {
    const strip = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [KING_TIDE, AURORA] }],
    ]));

    expect(strip.safetyNote).toBeNull();
  });

  it('a lone unranked topic earns nothing, rather than being read as rank 0', () => {
    const unranked = badge({ type: 'MYSTERY', label: 'Something new', rarityRank: undefined });

    expect(buildPromotedStrip(paneFor([[TODAY, 'SUNSET', { badges: [unranked] }]]))).toBeNull();
  });

  it('shows the TOPIC\'s own clock on a rarity strip, not the window\'s', () => {
    // The eclipse peaks at 19:06; its window is the sunset one, whose clock is 20:42. A rarity
    // strip's subject is the topic, so printing the window's time as the only time on it would
    // send the reader out an hour and a half late.
    const strip = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [ECLIPSE], eventTime: `${TODAY}T20:42:00` }],
    ]));

    expect(strip.reason).toBe('rarity');
    expect(strip.time).toBe('19:06');
  });

  it('keeps the WINDOW\'s clock on a coincidence strip, whose subject is the window', () => {
    const strip = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [KING_TIDE, AURORA], eventTime: `${TODAY}T20:42:00` }],
    ]));

    expect(strip.reason).toBe('coincidence');
    // 21:42, not 20:42: `buildWindowCards` formats the ISO instant in the runner's local zone.
    // The value under test is WHICH clock is chosen, not how it is formatted.
    expect(strip.time).toBe('21:42');
  });

  it('falls back to the window clock when a solo topic carries no time of its own', () => {
    const timeless = badge({ type: 'ECLIPSE', label: 'Deep partial eclipse', rarityRank: 1, eventTime: null });
    const strip = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [timeless], eventTime: `${TODAY}T20:42:00` }],
    ]));

    expect(strip.time).toBe('21:42');
  });

  it('picks the earlier window when two solo rarities tie', () => {
    const strip = buildPromotedStrip(paneFor([
      [TODAY, 'SUNSET', { badges: [ECLIPSE] }],
      [DAY_AFTER, 'SUNSET', { badges: [ECLIPSE] }],
    ]));

    expect(strip.windowKey).toContain(TODAY);
  });
});
