import { describe, it, expect } from 'vitest';
import { AWAY_STATE_LABEL, buildHeatStripCards } from '../utils/windowFirstStrip.js';

/**
 * The heat strip's thumbnail descriptors.
 *
 * <p>The rule this file exists to protect is that a thumbnail says what its own window's CARD says.
 * The day rail it replaced rolled its own day peak up from a different field, and a payload where
 * the two disagreed put "Awaiting" above cards reading "Worth it" — one screen, two answers. So
 * every verdict assertion below is really an assertion that nothing was re-derived.
 *
 * <p>The other half is the away day, which has no card at all: it keeps its slot in the six because
 * a missing thumbnail would silently renumber the shape of the week, and it keeps its sun time
 * because sunrise and sunset are almanac and true whether or not a forecast ran.
 */

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';

/** A card as `buildWindowCards` shapes it — only the fields the strip reads. */
function card(overrides = {}) {
  return {
    key: `${TODAY}:SUNSET`,
    date: TODAY,
    targetType: 'SUNSET',
    kicker: 'Tonight',
    when: 'Sunset',
    time: '21:11',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    confidence: 'high',
    pick: null,
    ...overrides,
  };
}

const build = (events, cards, travel = new Set(), days = []) => buildHeatStripCards(
  events, cards, travel, days, TODAY, TOMORROW,
);

describe('buildHeatStripCards — one thumbnail per rendered window', () => {
  it('walks the events, not the cards, so the six slots are the six windows', () => {
    const events = [
      { date: TODAY, targetType: 'SUNSET' },
      { date: TOMORROW, targetType: 'SUNRISE' },
    ];
    const cards = [card(), card({ key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE', kicker: null, when: 'Tomorrow sunrise', time: '05:20' })];

    expect(build(events, cards).map((c) => c.key))
      .toEqual([`${TODAY}:SUNSET`, `${TOMORROW}:SUNRISE`]);
  });

  it('takes the verdict word from the card, never from a second roll-up', () => {
    // The whole reason the strip replaced the rail: the rail read `BriefingDay.peak` while the card
    // read the window projection, and the two could disagree on one screen. Mutate the card's
    // verdict and the thumbnail must follow it.
    const events = [{ date: TODAY, targetType: 'SUNSET' }];
    const [only] = build(events, [card({ verdict: 'MAYBE', verdictLabel: 'Maybe' })]);

    expect(only.verdict).toBe('MAYBE');
    expect(only.verdictLabel).toBe('Maybe');
  });

  it('names the window the way the card names it, kicker included', () => {
    // The accessible name is built from this string, and the card's header is built from the same
    // two fields — so a lead sunset reads "Tonight Sunset" in both places or the strip is naming a
    // window the row below it calls something else.
    const events = [{ date: TODAY, targetType: 'SUNSET' }];
    expect(build(events, [card()])[0].label).toBe('Tonight Sunset');
  });

  it('marks a sunrise as one, which is what the arrow glyph draws', () => {
    const events = [{ date: TOMORROW, targetType: 'SUNRISE' }];
    const cards = [card({ key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE' })];

    expect(build(events, cards)[0].sunrise).toBe(true);
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [card()])[0].sunrise).toBe(false);
  });

  it('takes the time from the card, not from the day it belongs to', () => {
    // The ordinary path, and it was pinned nowhere: both other assertions about `time` in this file
    // are on the AWAY arm, and every component fixture hands `time` in as a literal. Swapping
    // `card.time` for `''` left the whole suite green. The card's time is the window projection's
    // own answer; the event summary is the fallback for a payload cached before windows existed,
    // and the two can differ — here the day says 20:00 and the window says 21:11.
    const events = [{ date: TODAY, targetType: 'SUNSET' }];
    const days = [{
      date: TODAY,
      eventSummaries: [{ targetType: 'SUNSET', solarEventTime: `${TODAY}T19:00:00` }],
    }];

    expect(build(events, [card()], new Set(), days)[0].time).toBe('21:11');
  });

  it('carries the weekday abbreviation for the top row', () => {
    // 2026-08-04 is a Tuesday. Read on the UTC calendar at noon, so no zone can move it a day.
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [card()])[0].dow).toBe('Tue');
  });

  it('carries BOTH pick kinds, because the matrix rides each on its own card border', () => {
    // Exactly two picks exist across the whole forecast. P2's strip flagged the Best one alone,
    // because a 55px tile had room for one word; the v3 card carries the pick as a fieldset legend
    // in its border, so the runner-up gets its own and the boolean became a kind.
    const events = [
      { date: TODAY, targetType: 'SUNSET' },
      { date: TOMORROW, targetType: 'SUNRISE' },
    ];
    const cards = [
      card({ pick: { kind: 'best', regionName: 'A' } }),
      card({
        key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE',
        pick: { kind: 'also', regionName: 'B' },
      }),
    ];

    expect(build(events, cards).map((c) => c.pickKind)).toEqual(['best', 'also']);
  });

  it('carries no pick kind when the window has no pick at all', () => {
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [card()])[0].pickKind).toBeNull();
  });

  it('carries no pick kind when the origin scoped the pick away', () => {
    // `buildWindowCards` withholds a pick naming a region an away origin has scoped out (plan
    // §2.12), and this fold must not reconstruct one — an away plan legitimately shows no legend
    // at all (plan-matrix D-5). The card arrives with `pick: null`, so the assertion is that
    // nothing here invents one from some other field.
    const [only] = build([{ date: TODAY, targetType: 'SUNSET' }], [card({ pick: null })]);
    expect(only.pickKind).toBeNull();
  });

  it('carries the card\'s pool and its head, so the spread and the best line read one list', () => {
    // The design's requirement that the picture, the count and the name share a pool. Folded
    // rather than re-derived: `buildWindowCards` is the only place a pool is built.
    const pool = [{ locationName: 'Bamburgh Beach', rating: 5, driveMinutes: 40 }];
    const [only] = build(
      [{ date: TODAY, targetType: 'SUNSET' }], [card({ pool, bestReach: pool[0] })],
    );
    expect(only.pool).toBe(pool);
    expect(only.bestReach).toBe(pool[0]);
  });

  it('carries an EMPTY pool and no head on an away day', () => {
    // There is no card behind an away window, so there is no pool — and the away cell draws
    // neither derived row rather than an empty histogram claiming nothing is in reach.
    const [only] = build(
      [{ date: TODAY, targetType: 'SUNSET' }], [], new Set([TODAY]),
      [{ date: TODAY, eventSummaries: [{ targetType: 'SUNSET' }] }],
    );
    expect(only.away).toBe(true);
    expect(only.pool).toEqual([]);
    expect(only.bestReach).toBeNull();
  });

  it('carries the window\'s badges BEFORE row promotion, so the matrix names every topic', () => {
    // `card.badges` is the promoted-out remainder the window ROW renders; `card.allBadges` is the
    // whole set. A card naming only the remainder would silently lose a snow topic that became a
    // row — the design's "nothing is collapsed behind a +2", from the other direction.
    const all = [{ type: 'SNOW_FRESH', label: 'Fresh snow' }, { type: 'AURORA', label: 'Aurora' }];
    const [only] = build(
      [{ date: TODAY, targetType: 'SUNSET' }],
      [card({ allBadges: all, badges: [all[1]] })],
    );
    expect(only.badges).toBe(all);
  });

  it('carries the window\'s confidence tier, which feeds the field\'s haze', () => {
    const events = [{ date: TODAY, targetType: 'SUNSET' }];
    expect(build(events, [card({ confidence: 'low' })])[0].confidence).toBe('low');
    expect(build(events, [card({ confidence: null })])[0].confidence).toBeNull();
  });
});

describe('buildHeatStripCards — the away day, which has no card', () => {
  const AWAY_DAYS = [{
    date: TOMORROW,
    eventSummaries: [{ targetType: 'SUNRISE', solarEventTime: `${TOMORROW}T04:20:00` }],
  }];

  it('keeps its slot rather than renumbering the shape of the week', () => {
    const events = [
      { date: TODAY, targetType: 'SUNSET' },
      { date: TOMORROW, targetType: 'SUNRISE' },
    ];
    const built = build(events, [card()], new Set([TOMORROW]), AWAY_DAYS);

    expect(built).toHaveLength(2);
    expect(built[1].away).toBe(true);
  });

  it('says "Not forecast" — the arm\'s own word, and no verdict at all', () => {
    // §6 bans invented vocabulary, and `windowFirstAway.js` already speaks this phrase about the
    // very same windows. A null verdict is what stops the thumbnail taking a verdict colour: a
    // travel day's slots ARE projected, so borrowing that verdict would print "Poor" about a day
    // nobody forecast.
    const events = [{ date: TOMORROW, targetType: 'SUNRISE' }];
    const [only] = build(events, [], new Set([TOMORROW]), AWAY_DAYS);

    expect(only.verdictLabel).toBe(AWAY_STATE_LABEL);
    expect(only.verdictLabel).toBe('Not forecast');
    expect(only.verdict).toBeNull();
  });

  it('keeps its sun time, because that is almanac and not a forecast', () => {
    // 04:20 UTC in the payload reads 05:20 on the clock, because the shared formatter renders a
    // backend instant in Europe/London and 5 August is BST. The suite runs in UTC (`setup.js`), so
    // a formatter that dropped the zone would print 04:20 here and this would catch it.
    const events = [{ date: TOMORROW, targetType: 'SUNRISE' }];
    expect(build(events, [], new Set([TOMORROW]), AWAY_DAYS)[0].time).toBe('05:20');
  });

  it('names itself from the day, since there is no card to borrow a label from', () => {
    const events = [{ date: TOMORROW, targetType: 'SUNRISE' }];
    expect(build(events, [], new Set([TOMORROW]), AWAY_DAYS)[0].label).toBe('Tomorrow sunrise');
  });

  it('renders without a time rather than guessing when the day carries none', () => {
    // A payload cached before the summary carried its own `solarEventTime`. Silence is the honest
    // answer; a synthesised sunrise would be a claim nothing behind it supports.
    const events = [{ date: TOMORROW, targetType: 'SUNRISE' }];
    const days = [{ date: TOMORROW, eventSummaries: [{ targetType: 'SUNRISE' }] }];

    expect(build(events, [], new Set([TOMORROW]), days)[0].time).toBe('');
  });
});

describe('buildHeatStripCards — movement', () => {
  const events = [{ date: TODAY, targetType: 'SUNSET' }];

  it('folds the card\'s movement through untouched, rather than re-deriving it', () => {
    // The card already picked this window's leading region to rank it. Deriving it a second time
    // here would give the thumbnail's chip and the card's own order two chances to name different
    // regions on one screen — the class of divergence this whole module exists to prevent.
    const movement = { regionName: 'Cumbria', delta: -0.4 };

    expect(build(events, [card({ movement })])[0].movement).toBe(movement);
  });

  it('is null when the card carries none', () => {
    expect(build(events, [card()])[0].movement).toBeNull();
  });

  it('suppresses movement on an AWAY day', () => {
    // A travel day is not evaluated, so there is nothing on it that could have moved. A chip there
    // would state a change on a night nobody forecast — the same rule that already strips the
    // verdict word and the Best-bet flag from an away thumbnail.
    const away = build(events, [card({ movement: { regionName: 'Cumbria', delta: 0.6 } })],
      new Set([TODAY]))[0];

    expect(away.away).toBe(true);
    expect(away.movement).toBeNull();
  });
});

describe('buildHeatStripCards — hotRegionName (field-geography plan §2.3)', () => {
  // ⚠️ THE test a component-level fixture cannot replace: `WindowFirstHeatStrip.test.jsx` hands
  // fixture cards straight to the component, so a field dropped from THIS fold — the whitelist
  // `buildHeatStripCards` builds — would leave `data-hot` never firing in production while every
  // component test stayed green. `windowFirstCards.test.js` pins where the value comes from
  // (`topRegion`'s reuse); this pins that it survives the strip's own fold.
  const events = [{ date: TODAY, targetType: 'SUNSET' }];

  it('folds the card\'s hotRegionName through untouched', () => {
    expect(build(events, [card({ hotRegionName: 'Cumbria' })])[0].hotRegionName).toBe('Cumbria');
  });

  it('is null when the card carries none', () => {
    expect(build(events, [card({ hotRegionName: null })])[0].hotRegionName).toBeNull();
  });

  it('is null on an away day, which has no card to fold it from', () => {
    const AWAY_DAYS = [{
      date: TODAY,
      eventSummaries: [{ targetType: 'SUNSET', solarEventTime: `${TODAY}T20:11:00` }],
    }];
    const [only] = build(events, [], new Set([TODAY]), AWAY_DAYS);

    expect(only.away).toBe(true);
    expect(only.hotRegionName).toBeNull();
  });
});

describe('buildHeatStripCards — absence', () => {
  it('returns an empty list for no events, rather than undefined', () => {
    expect(build([], [])).toEqual([]);
    expect(buildHeatStripCards(null, null, undefined, null, TODAY, TOMORROW)).toEqual([]);
  });

  it('falls back to Awaiting for a live window with no card, rather than throwing', () => {
    // Defensive: `buildWindowCards` builds one for every non-travel event, so this shape does not
    // reach production. It is here because the alternative to a fallback is a blank thumbnail with
    // a null verdict, which reads exactly like an away day and would be a lie.
    const [only] = build([{ date: TODAY, targetType: 'SUNSET' }], []);

    expect(only.verdict).toBe('AWAITING');
    expect(only.verdictLabel).toBe('Awaiting');
  });
});
