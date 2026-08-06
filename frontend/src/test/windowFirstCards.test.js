import { describe, it, expect } from 'vitest';
import { badgeChannel, buildWindowCards } from '../utils/windowFirstCards.js';

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';

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

/** The common case: one rated sunset today. */
const oneWindow = (window = {}) => ({
  events: events([TODAY, 'SUNSET']),
  days: [day(TODAY, [summary('SUNSET', window)])],
});

function build({ events: ev, days }, travel = new Set()) {
  return buildWindowCards(ev, days, TODAY, TOMORROW, travel);
}

describe('buildWindowCards', () => {
  it('builds one card per rendered window, in the order it was given', () => {
    const days = [
      day(TODAY, [summary('SUNRISE'), summary('SUNSET')]),
      day(TOMORROW, [summary('SUNRISE')]),
    ];
    const cards = build({
      events: events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET'], [TOMORROW, 'SUNRISE']), days,
    });

    expect(cards.map((c) => c.key)).toEqual([
      `${TODAY}:SUNRISE`, `${TODAY}:SUNSET`, `${TOMORROW}:SUNRISE`,
    ]);
  });

  describe('the lead card', () => {
    it('leads today\'s FIRST window, and only that one', () => {
      // Both terms matter. `date === todayStr` alone fires twice before dawn, when today's sunrise
      // and its sunset are both still upcoming.
      const days = [day(TODAY, [summary('SUNRISE'), summary('SUNSET')])];
      const cards = build({ events: events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET']), days });

      expect(cards.map((c) => c.lead)).toEqual([true, false]);
    });

    it('leads nothing once today\'s last window has passed', () => {
      // `index === 0` alone would move the gold onto tomorrow's sunrise — at which point the rail's
      // gold tile has already gone, so the two surfaces would contradict each other on screen.
      const days = [day(TOMORROW, [summary('SUNRISE')])];
      const cards = build({ events: events([TOMORROW, 'SUNRISE']), days });

      expect(cards[0].lead).toBe(false);
    });
  });

  describe('the kicker', () => {
    it('says Tonight on a lead sunset, and drops the day from the title', () => {
      const [card] = build(oneWindow());
      expect(card.kicker).toBe('Tonight');
      expect(card.when).toBe('Sunset');
    });

    it('says nothing on a lead sunrise, and keeps the day in the title', () => {
      // "Tonight" is wrong for a sunrise and the alternative would be vocabulary the product speaks
      // nowhere else, which §6 bans. Silence, and the day moves into the title.
      const days = [day(TODAY, [summary('SUNRISE')])];
      const [card] = build({ events: events([TODAY, 'SUNRISE']), days });

      expect(card.kicker).toBeNull();
      expect(card.when).toBe('Today sunrise');
    });

    it('never appears on a card that is not the lead', () => {
      const days = [day(TODAY, [summary('SUNSET')]), day(TOMORROW, [summary('SUNSET')])];
      const cards = build({ events: events([TODAY, 'SUNSET'], [TOMORROW, 'SUNSET']), days });

      expect(cards[1].kicker).toBeNull();
      expect(cards[1].when).toBe('Tomorrow sunset');
    });

    it('names the weekday beyond tomorrow', () => {
      const later = '2026-08-07';
      const days = [day(later, [summary('SUNRISE')])];
      const [card] = build({ events: events([later, 'SUNRISE']), days });

      expect(card.when).toBe('Friday sunrise');
    });
  });

  describe('the header meta', () => {
    it('states the best rating as a whole star', () => {
      // bestRating is an Integer in 1..5. "4.0★" asserts a precision the field cannot carry, and
      // the plan already leans on the distinction when gating the second pick.
      const [card] = build(oneWindow({ bestRating: 4 }));
      expect(card.bestRating).toBe(4);
    });

    it('carries no rating at all when nothing in the window is rated', () => {
      // Null means "nothing here is rated", which is a different statement from a low rating — so
      // the card omits the star rather than printing a placeholder for it.
      const [card] = build(oneWindow());
      expect(card.bestRating).toBeNull();
    });
  });

  describe('the verdict', () => {
    it.each([
      ['WORTH_IT', 'Worth it'],
      ['MAYBE', 'Maybe'],
      ['STAND_DOWN', 'Poor'],
      ['AWAITING', 'Awaiting'],
    ])('labels %s as "%s"', (verdict, label) => {
      const [card] = build(oneWindow({ verdict }));
      expect(card.verdictLabel).toBe(label);
    });

    it('falls back to Awaiting when the window is missing entirely', () => {
      // A legacy payload cached before windows existed. Awaiting is the honest reading — no rating
      // and no triage signal — and it must never be shown as a poor forecast.
      const days = [day(TODAY, [{ targetType: 'SUNSET', regions: [], unregioned: [] }])];
      const [card] = build({ events: events([TODAY, 'SUNSET']), days });

      expect(card.verdict).toBe('AWAITING');
      expect(card.verdictLabel).toBe('Awaiting');
    });
  });

  describe('confidence', () => {
    it('carries the window\'s own tier on a recommendation', () => {
      const [card] = build(oneWindow({ verdict: 'WORTH_IT', confidence: 'low' }));
      expect(card.confidence).toBe('low');
    });

    it.each(['STAND_DOWN', 'AWAITING'])('drops it on a %s window', (verdict) => {
      // Confidence qualifies a recommendation. Decaying a Poor badge would say "we are unsure it is
      // bad", which is not what the channel means and not a claim the derivation supports.
      const [card] = build(oneWindow({ verdict, confidence: 'high' }));
      expect(card.confidence).toBeNull();
    });

    it('is null rather than undefined when the backend omitted it', () => {
      // The field is NON_NULL on the wire, so the key is absent, not null. The distinction reaches
      // the badge: it decides whether the horizon inference is applied at all.
      const [card] = build(oneWindow({ verdict: 'WORTH_IT' }));
      expect(card.confidence).toBeNull();
    });
  });

  describe('the pick', () => {
    it('lowercases the kind and carries the region, prose and location', () => {
      const [card] = build(oneWindow({
        pick: {
          kind: 'BEST',
          regionName: 'The Yorkshire Dales',
          headline: 'Breaking clear',
          detail: 'Low cloud clears.',
          averageRating: 4.0,
          locationName: 'Malham Cove',
          locationId: 7,
        },
      }));

      expect(card.pick).toEqual({
        kind: 'best',
        regionName: 'The Yorkshire Dales',
        headline: 'Breaking clear',
        detail: 'Low cloud clears.',
        locationName: 'Malham Cove',
        locationId: 7,
      });
    });

    it('maps ALSO to also', () => {
      const [card] = build(oneWindow({
        pick: { kind: 'ALSO', regionName: 'R', headline: 'H', averageRating: 3.5 },
      }));
      expect(card.pick.kind).toBe('also');
    });

    it('nulls a detail and a location the payload omitted, rather than leaving them undefined', () => {
      // A headline-present, detail-absent pick is a shipped state, and locationName is absent when
      // the region has nothing rated. Both keys are simply missing on the wire.
      const [card] = build(oneWindow({
        pick: { kind: 'BEST', regionName: 'R', headline: 'H', averageRating: 3.5 },
      }));

      expect(card.pick.detail).toBeNull();
      expect(card.pick.locationName).toBeNull();
      expect(card.pick.locationId).toBeNull();
    });

    it('is null on the windows that are neither pick, which is most of them', () => {
      expect(build(oneWindow())[0].pick).toBeNull();
    });

    it('never carries averageRating, which is a different statistic from the header star', () => {
      // averageRating is the region average, canopy-INCLUSIVE; bestRating is a max over non-canopy
      // slots. Showing both under one glyph puts two different numbers one row apart.
      const [card] = build(oneWindow({
        bestRating: 5,
        pick: { kind: 'BEST', regionName: 'R', headline: 'H', averageRating: 3.2 },
      }));

      expect(card.pick.averageRating).toBeUndefined();
      expect(card.bestRating).toBe(5);
    });
  });

  describe('away days', () => {
    it('draws no card for a day the operator is away', () => {
      // A travel day still carries slots — the pipeline skips evaluation, not collection — so the
      // projector turns it into STAND_DOWN and a naive list would put a "Poor" card under a rail
      // tile reading "Not forecast". The rail already explains the absence directly above.
      const days = [day(TODAY, [summary('SUNSET')]), day(TOMORROW, [summary('SUNSET')])];
      const cards = build(
        { events: events([TODAY, 'SUNSET'], [TOMORROW, 'SUNSET']), days }, new Set([TOMORROW]),
      );

      expect(cards.map((c) => c.date)).toEqual([TODAY]);
    });

    it('still leads the first LIVE window when an earlier day is away', () => {
      const days = [day(TODAY, [summary('SUNSET')]), day(TOMORROW, [summary('SUNSET')])];
      const cards = build(
        { events: events([TODAY, 'SUNSET'], [TOMORROW, 'SUNSET']), days }, new Set([TODAY]),
      );

      // Tomorrow is now index 0, but it is not today, so nothing leads.
      expect(cards).toHaveLength(1);
      expect(cards[0].lead).toBe(false);
    });
  });

  describe('the clock', () => {
    it('prefers the window\'s own event time', () => {
      const [card] = build(oneWindow({ eventTime: `${TODAY}T20:11:00` }));
      // The wire carries UTC; the card shows BST.
      expect(card.time).toBe('21:11');
    });

    it('falls back to a slot time for a payload cached before windows existed', () => {
      const days = [day(TODAY, [{
        targetType: 'SUNSET',
        regions: [{ regionName: 'R', slots: [{ solarEventTime: `${TODAY}T20:11:00` }] }],
        unregioned: [],
      }])];
      const [card] = build({ events: events([TODAY, 'SUNSET']), days });

      expect(card.time).toBe('21:11');
    });

    it('leaves the time empty rather than inventing one', () => {
      expect(build(oneWindow())[0].time).toBe('');
    });
  });

  describe('degrade paths', () => {
    it('returns nothing for an empty event list, and tolerates a null payload', () => {
      expect(buildWindowCards([], [], TODAY, TOMORROW, new Set())).toEqual([]);
      expect(buildWindowCards(null, null, TODAY, TOMORROW, undefined)).toEqual([]);
    });

    it('survives an event whose day the briefing has no entry for', () => {
      const [card] = build({ events: events([TODAY, 'SUNSET']), days: [] });
      expect(card.verdict).toBe('AWAITING');
      expect(card.badges).toEqual([]);
    });

    it('gives every card a badges array, so the renderer never guards', () => {
      const days = [day(TODAY, [{ targetType: 'SUNSET', regions: [], unregioned: [], window: { verdict: 'MAYBE' } }])];
      expect(build({ events: events([TODAY, 'SUNSET']), days })[0].badges).toEqual([]);
    });

    it('gives every card a rows array, so the renderer never guards', () => {
      const days = [day(TODAY, [{ targetType: 'SUNSET', regions: [], unregioned: [], window: { verdict: 'MAYBE' } }])];
      expect(build({ events: events([TODAY, 'SUNSET']), days })[0].rows).toEqual([]);
    });
  });

  describe('attribute rows and the badges they consume', () => {
    /** A snow topic as the backend serialises one, with the facts its strategy emits. */
    const snowBadge = (overrides = {}) => ({
      type: 'SNOW_TOPS',
      label: 'Snow on the fells',
      detail: 'Tops white above the valleys',
      facts: [{ key: 'snow line', value: '~650 m', emphasis: true, optional: false }],
      eventTime: '05:31',
      rarityRank: 8,
      ...overrides,
    });

    const TIDE = {
      locationName: 'Whitby',
      state: 'MID',
      direction: 'FALLING',
      nearestType: 'HW',
      nearestTime: '19:28',
      nearestOffset: '1h43 before sunset',
      range: '4.9 m',
      rangeAnomaly: '1.2 m above an average tide',
      seas: null,
      curve: [0, 1],
      windowPosition: 0.5,
      windowLevel: 0.5,
    };

    it('turns the window tide rollup into the card\'s first row', () => {
      const [card] = build(oneWindow({ tide: TIDE }));

      expect(card.rows.map((r) => r.channel)).toEqual(['tide']);
    });

    it('drops a promoted topic from the header, so no card names it twice', () => {
      // The rule the whole duplication question turns on. Asserting only that the row exists would
      // pass with the badge still beside it, which is the failure.
      const [card] = build(oneWindow({ badges: [snowBadge()] }));

      expect(card.rows.map((r) => r.channel)).toEqual(['snow']);
      expect(card.badges).toEqual([]);
    });

    it('keeps a factless topic as a badge, because a row would only repeat its label', () => {
      const [card] = build(oneWindow({ badges: [snowBadge({ facts: [] })] }));

      expect(card.rows).toEqual([]);
      expect(card.badges.map((b) => b.label)).toEqual(['Snow on the fells']);
    });

    it('keeps a topic the two-row cap dropped, so the budget costs a row and never a fact', () => {
      const fresh = snowBadge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 });
      const tops = snowBadge();

      const [card] = build(oneWindow({ tide: TIDE, badges: [fresh, tops] }));

      expect(card.rows).toHaveLength(2);
      expect(card.badges.map((b) => b.label)).toEqual(['Fresh snow']);
    });

    it('leaves every non-promotable badge in the header untouched', () => {
      const nlc = snowBadge({ type: 'NLC', label: '✦ NLC', rarityRank: 14 });
      const tide = snowBadge({ type: 'KING_TIDE', label: 'King tide', rarityRank: 3 });

      const [card] = build(oneWindow({ badges: [nlc, tide] }));

      expect(card.rows).toEqual([]);
      expect(card.badges.map((b) => b.label)).toEqual(['✦ NLC', 'King tide']);
    });
  });
});

describe('badgeChannel', () => {
  it.each([
    ['SPRING_TIDE', 'tide'],
    ['KING_TIDE', 'tide'],
    ['STORM_SURGE', 'tide'],
    ['NLC', 'nlc'],
    ['NOCTILUCENT_CLOUD', 'nlc'],
    ['AURORA', 'aurora'],
    ['SNOW_FRESH', 'snow'],
    ['SNOW_TOPS', 'snow'],
    ['FROST', 'snow'],
  ])('routes %s to the %s channel', (type, channel) => {
    expect(badgeChannel(type)).toBe(channel);
  });

  it('gives an unrecognised type the neutral badge rather than the nearest colour', () => {
    // A badge's colour names its channel, so a wrong colour is a wrong claim. New topic types are
    // additive and fail quietly rather than being forced into a channel they do not belong to.
    expect(badgeChannel('BLUEBELL')).toBe('plain');
    expect(badgeChannel('INVERSION')).toBe('plain');
  });

  it('tolerates a missing type', () => {
    expect(badgeChannel(undefined)).toBe('plain');
    expect(badgeChannel(null)).toBe('plain');
  });
});
