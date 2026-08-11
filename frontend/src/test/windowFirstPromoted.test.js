import { describe, it, expect } from 'vitest';
import {
  MIN_COINCIDENCE_BADGES, UNKNOWN_RANK, buildPromotedStrip,
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

/** Rank 1, the rarest kind there is — used to prove an unranked badge cannot outrank it. */
const SUPERMOON = badge({ type: 'SUPERMOON', label: 'Supermoon', rarityRank: 1 });

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
  it('counts a badge the attribute rows already promoted out of the card header', () => {
    const fresh = badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 });
    const pane = paneFor([[TODAY, 'SUNRISE', { badges: [badge(), fresh], topRarityRank: 8 }]]);
    // Precondition: the card's own header list really has been reduced below the threshold.
    expect(pane[0].card.badges.length).toBeLessThan(MIN_COINCIDENCE_BADGES);
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
