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

  it('flags only the forecast\'s BEST pick, never the runner-up', () => {
    // Exactly two picks exist across the whole forecast and the Also is a runner-up. Flagging both
    // would put two "best" claims on one strip.
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

    expect(build(events, cards).map((c) => c.bestBet)).toEqual([true, false]);
  });

  it('carries no best-bet flag when the window has no pick at all', () => {
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [card()])[0].bestBet).toBe(false);
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
