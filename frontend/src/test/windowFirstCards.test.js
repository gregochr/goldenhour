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
      // Paired with a Best sibling: an Also with no surviving Best is suppressed by the orphaned-
      // Also invariant below, which is a different concern from the kind-mapping this test covers.
      const days = [
        day(TODAY, [summary('SUNSET', {
          pick: { kind: 'BEST', regionName: 'B', headline: 'Best headline', averageRating: 4.0 },
        })]),
        day(TOMORROW, [summary('SUNSET', {
          pick: { kind: 'ALSO', regionName: 'R', headline: 'H', averageRating: 3.5 },
        })]),
      ];
      const cards = build({ events: events([TODAY, 'SUNSET'], [TOMORROW, 'SUNSET']), days });
      expect(cards[1].pick.kind).toBe('also');
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
      // averageRating is the region's MEAN over its voting slots; bestRating is a MAX over non-canopy
      // slots. Showing both under one glyph puts two different numbers one row apart.
      const [card] = build(oneWindow({
        bestRating: 5,
        pick: { kind: 'BEST', regionName: 'R', headline: 'H', averageRating: 3.2 },
      }));

      expect(card.pick.averageRating).toBeUndefined();
      expect(card.bestRating).toBe(5);
    });
  });

  describe('the orphaned Also invariant', () => {
    // D3 (plan-verdict-consolidation-plan.md §1): the backend's pick pool can rank a BEST onto a
    // window this client never receives — a stale SWR-cached payload, a serve-time race, or (pre
    // Phase 1) the projector ranking picks over more dates than the rail renders — while an Also
    // on a rendered window comes through untouched. An Also with no surviving Best badges a
    // runner-up to a plan nobody on screen can see. Belt-and-braces, mirroring the rail's own
    // suppression in windowFirstRail.js — kept even after the backend fix, since it is the
    // stale-cache defence, not a workaround for the backend bug.

    it('drops an Also pick when no Best pick survives into the built cards', () => {
      const [card] = build(oneWindow({
        pick: { kind: 'ALSO', regionName: 'R', headline: 'H', averageRating: 3.5 },
      }));

      expect(card.pick).toBeNull();
    });

    it('drops the Also even when other rendered cards carry no pick at all', () => {
      const days = [
        day(TODAY, [summary('SUNSET')]),
        day(TOMORROW, [summary('SUNSET', {
          pick: { kind: 'ALSO', regionName: 'R', headline: 'H', averageRating: 3.5 },
        })]),
      ];
      const cards = build({ events: events([TODAY, 'SUNSET'], [TOMORROW, 'SUNSET']), days });

      expect(cards[1].pick).toBeNull();
    });

    it('keeps the Also when its Best sibling is among the built cards', () => {
      const days = [
        day(TODAY, [summary('SUNSET', {
          pick: { kind: 'BEST', regionName: 'A', headline: 'Best headline', averageRating: 4.5 },
        })]),
        day(TOMORROW, [summary('SUNSET', {
          pick: { kind: 'ALSO', regionName: 'B', headline: 'Also headline', averageRating: 4.0 },
        })]),
      ];
      const cards = build({ events: events([TODAY, 'SUNSET'], [TOMORROW, 'SUNSET']), days });

      expect(cards[0].pick.kind).toBe('best');
      expect(cards[1].pick.kind).toBe('also');
    });

    it('never drops a Best pick, even alone', () => {
      const [card] = build(oneWindow({
        pick: { kind: 'BEST', regionName: 'R', headline: 'H', averageRating: 4.0 },
      }));

      expect(card.pick.kind).toBe('best');
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

  describe('the reach lens', () => {
    /** Three locations at 19, 41 and 66 minutes — either side of the 45-minute tier. */
    const THREE_SPOTS = [
      { locationId: 1, locationName: 'Simonside', claudeRating: 3, canopy: false },
      { locationId: 2, locationName: 'Blyth Beach', claudeRating: 4, canopy: false },
      { locationId: 3, locationName: 'Sycamore Gap', claudeRating: 5, canopy: false },
    ];
    const REACH = new Map([
      [1, { driveMinutes: 19, distanceMiles: 10 }],
      [2, { driveMinutes: 41, distanceMiles: 19 }],
      [3, { driveMinutes: 66, distanceMiles: 47 }],
    ]);

    const withSpots = (slots) => ({
      events: events([TODAY, 'SUNSET']),
      days: [day(TODAY, [{
        targetType: 'SUNSET',
        regions: [{ regionName: 'Northumberland & Tyneside', slots }],
        unregioned: [],
        window: { verdict: 'WORTH_IT', badges: [] },
      }])],
    });

    const buildWithLens = (slots, reachById, lens) => buildWindowCards(
      events([TODAY, 'SUNSET']), withSpots(slots).days, TODAY, TOMORROW, new Set(), reachById, lens,
    );

    it('drops a spot beyond the tier and keeps the one exactly on it', () => {
      const [card] = buildWithLens(THREE_SPOTS, REACH, { limitMinutes: 41, defaultLimitMinutes: 45 });
      expect(card.spots.map((s) => s.locationName)).toEqual(['Blyth Beach', 'Simonside']);
    });

    it('states how many the lens chose from, so the strip can say "N of M"', () => {
      const [card] = buildWithLens(THREE_SPOTS, REACH, { limitMinutes: 45, defaultLimitMinutes: 45 });
      expect(card.spots).toHaveLength(2);
      expect(card.reachTotal).toBe(3);
    });

    it('gates nothing under Any, and the two counts are then equal', () => {
      const [card] = buildWithLens(THREE_SPOTS, REACH, { limitMinutes: null, defaultLimitMinutes: 45 });
      expect(card.spots).toHaveLength(3);
      expect(card.reachTotal).toBe(3);
    });

    it('carries the ungated set for the drill-down, which owns a reach control of its own', () => {
      // Handing the sheet the gated list would give it a widening control with nothing to reveal.
      const [card] = buildWithLens(THREE_SPOTS, REACH, { limitMinutes: 41, defaultLimitMinutes: 45 });
      expect(card.spots.map((s) => s.locationName)).toEqual(['Blyth Beach', 'Simonside']);
      expect(card.allSpots.map((s) => s.locationName))
        .toEqual(['Sycamore Gap', 'Blyth Beach', 'Simonside']);
    });

    it('counts the same array it carries, so the two can never describe different populations', () => {
      const [card] = buildWithLens(THREE_SPOTS, REACH, { limitMinutes: 41, defaultLimitMinutes: 45 });
      expect(card.reachTotal).toBe(card.allSpots.length);
    });

    it('gates nothing at all with no lens, rather than at some assumed distance', () => {
      const [card] = buildWithLens(THREE_SPOTS, REACH);
      expect(card.spots).toHaveLength(3);
      expect(card.spots.every((s) => s.far === false)).toBe(true);
    });

    it('passes a spot with no drive time through every tier', () => {
      // Plan §2.5 rule 1, at the level the card is built: this is the first-run user's whole
      // experience of the control, and a gate here would empty their page.
      const [card] = buildWithLens(THREE_SPOTS, new Map(), { limitMinutes: 45, defaultLimitMinutes: 45 });
      expect(card.spots).toHaveLength(3);
    });

    it('marks the spots beyond today\'s default, and only those', () => {
      const [card] = buildWithLens(THREE_SPOTS, REACH, { limitMinutes: null, defaultLimitMinutes: 45 });
      expect(card.spots.filter((s) => s.far).map((s) => s.locationName)).toEqual(['Sycamore Gap']);
    });

    describe('what the header may claim', () => {
      it('counts what is within reach once the tier gated a fully measured set', () => {
        const [card] = buildWithLens(THREE_SPOTS, REACH, { limitMinutes: 45, defaultLimitMinutes: 45 });
        expect(card.withinReachCount).toBe(2);
      });

      it('claims nothing under Any, where the word describes no act', () => {
        const [card] = buildWithLens(THREE_SPOTS, REACH, { limitMinutes: null, defaultLimitMinutes: 45 });
        expect(card.withinReachCount).toBeNull();
      });

      it('claims nothing when one drawn spot\'s drive time is unknown', () => {
        // Rule 1 lets an unknown through every tier, so a drawn set can be part measured and part
        // unknown. Calling the whole of it "within reach" is the same over-claim as counting a set
        // nothing filtered — the one thing P5 and P6 both refused to do.
        const partial = new Map([[1, { driveMinutes: 19, distanceMiles: 10 }]]);
        const [card] = buildWithLens(THREE_SPOTS, partial, {
          limitMinutes: 45, defaultLimitMinutes: 45,
        });

        expect(card.spots).toHaveLength(3);
        expect(card.withinReachCount).toBeNull();
      });

      it('claims nothing when this user has no drive times at all', () => {
        const [card] = buildWithLens(THREE_SPOTS, new Map(), {
          limitMinutes: 45, defaultLimitMinutes: 45,
        });
        expect(card.withinReachCount).toBeNull();
      });

      it('claims nothing when the tier emptied the window', () => {
        // Zero would render "0 within reach" beside a card that already says so at greater length,
        // and `[].every()` is vacuously true, so the guard is load-bearing rather than cosmetic.
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: 5, defaultLimitMinutes: 45,
        });
        expect(card.spots).toHaveLength(0);
        expect(card.withinReachCount).toBeNull();
      });

      it('claims nothing while a rating floor is also gating', () => {
        // The clause names ONE axis and the drawn set was gated on two: "2 within reach" over a
        // strip a 4★ floor trimmed to one counts neither what the reader can drive to nor what is
        // on screen. Withholding it is the answer the other three conditions already give.
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: 45, defaultLimitMinutes: 45, tierId: '45', floorId: '4', minRating: 4,
        });
        expect(card.spots.map((s) => s.locationName)).toEqual(['Blyth Beach']);
        expect(card.withinReachCount).toBeNull();
      });
    });

    describe('the rating floor beside it', () => {
      it('drops a spot below the floor and keeps the one exactly on it', () => {
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: null, defaultLimitMinutes: 45, tierId: 'any', floorId: '4', minRating: 4,
        });
        expect(card.spots.map((s) => s.locationName)).toEqual(['Sycamore Gap', 'Blyth Beach']);
      });

      it('runs AFTER reach, and reports the pool it chose from', () => {
        // The order is what makes the bar's "42 of 138" mean something: `reachedTotal` is what
        // reach left, and `spots` is what the floor kept of it. Reversing them would give the bar a
        // denominator no control on screen produced.
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: 45, defaultLimitMinutes: 45, tierId: '45', floorId: '4', minRating: 4,
        });

        expect(card.reachTotal).toBe(3);
        expect(card.reachedTotal).toBe(2);
        expect(card.spots).toHaveLength(1);
      });

      it('gates nothing at all with no floor in the lens', () => {
        // The same defensive default reach takes: a caller that supplies no floor must get an
        // ungated page, never a silent one at some assumed rating.
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: null, defaultLimitMinutes: 45,
        });
        expect(card.spots).toHaveLength(3);
        expect(card.reachedTotal).toBe(3);
      });

      it('drops an unrated spot, which the reach gate would have passed', () => {
        const unrated = [{ locationId: 1, locationName: 'Simonside', claudeRating: null, canopy: false }];
        const [card] = buildWithLens(unrated, REACH, {
          limitMinutes: null, defaultLimitMinutes: 45, tierId: 'any', floorId: '3', minRating: 3,
        });
        expect(card.spots).toHaveLength(0);
      });

      it('leaves the window\'s own best star untouched over a strip the floor emptied', () => {
        // §5c`:913-918`, and the hazard plan §5f named when it kept the floor off the bar: a card
        // reading "best N★" over a strip its own control had emptied. It is a real state — here the
        // projection says 3★ and a 4★ floor leaves nothing — and it is the right one. The star is
        // the WINDOW's best and is never re-derived from a gated set, exactly as it is not for
        // reach. What §5f feared was a floor removing the best spot, and a floor cannot: it removes
        // from below, so either the best survives it or the strip is empty and says so.
        const poor = [{ locationId: 1, locationName: 'Simonside', claudeRating: 3, canopy: false }];
        const [card] = buildWindowCards(
          events([TODAY, 'SUNSET']),
          [day(TODAY, [{
            targetType: 'SUNSET',
            regions: [{ regionName: 'Northumberland & Tyneside', slots: poor }],
            unregioned: [],
            window: { verdict: 'WORTH_IT', badges: [], bestRating: 3 },
          }])],
          TODAY, TOMORROW, new Set(), REACH,
          { limitMinutes: null, defaultLimitMinutes: 45, tierId: 'any', floorId: '4', minRating: 4 },
        );

        expect(card.spots).toHaveLength(0);
        expect(card.bestRating).toBe(3);
      });

      it('describes the emptied window rather than leaving the card silent', () => {
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: null, defaultLimitMinutes: 45, tierId: 'any', floorId: '4', minRating: 4,
        });
        const empty = buildWithLens(
          [{ locationId: 1, locationName: 'Simonside', claudeRating: 2, canopy: false }],
          REACH,
          { limitMinutes: null, defaultLimitMinutes: 45, tierId: 'any', floorId: '4', minRating: 4 },
        )[0];

        // A window with something left carries no empty state at all...
        expect(card.lensEmpty).toBeNull();
        // ...and one the floor emptied names the floor and offers the step down.
        expect(empty.lensEmpty.headline).toBe('Nothing at 4★+ in this window.');
        expect(empty.lensEmpty.actions).toEqual([
          { kind: 'rating', id: 'any', label: 'Drop to any rating' },
        ]);
      });
    });
  });
});

describe('badgeChannel', () => {
  it.each([
    ['ECLIPSE', 'eclipse'],
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

  it('matches ECLIPSE exactly, so no loose substring test can capture it first', () => {
    // The other five tests are substring matches on the whole type string, and they run in source
    // order. ECLIPSE is matched first and by equality so that a later kind naming a different body
    // — a lunar eclipse over a coast, say — cannot be silently claimed by whichever loose test it
    // happens to hit, which is the failure mode `badgeChannel`'s own comment warns about.
    expect(badgeChannel('LUNAR_ECLIPSE_TIDE')).toBe('tide');
    expect(badgeChannel('eclipse')).toBe('eclipse');
  });
});

describe('buildWindowCards — topMeanRating, the Order control\'s ranking key', () => {
  const withRegions = (regions) => ({
    events: events([TODAY, 'SUNSET']),
    days: [day(TODAY, [{ targetType: 'SUNSET', regions, unregioned: [], window: { verdict: 'WORTH_IT', badges: [] } }])],
  });

  describe('movement — the leading region\'s change since the previous run', () => {
    it('reports the delta of the SAME region topMeanRating names, and names it', () => {
      // The chip sits on a thumbnail whose verdict and star already describe the leading region,
      // so a delta taken from anywhere else in the window would qualify a claim it is not about.
      const [card] = build(withRegions([
        { regionName: 'A', meanRating: 2.4, meanRatingDelta: 1.9 },
        { regionName: 'B', meanRating: 4.6, meanRatingDelta: -0.3 },
      ]));

      expect(card.topMeanRating).toBe(4.6);
      expect(card.movement).toEqual({ regionName: 'B', delta: -0.3 });
    });

    it('is null when the leading region carries no delta', () => {
      // Silence, never a zero. `movement.js` renders null as nothing at all and 0 as `—`, and the
      // two are different claims.
      expect(build(withRegions([{ regionName: 'A', meanRating: 4.6 }]))[0].movement).toBeNull();
    });

    it('carries a MEASURED zero through rather than nulling it', () => {
      expect(build(withRegions([
        { regionName: 'A', meanRating: 4.6, meanRatingDelta: 0 },
      ]))[0].movement).toEqual({ regionName: 'A', delta: 0 });
    });

    it('is null when no region carries a mean, so there is no leader to describe', () => {
      // A window nobody has scored has no leading region — a delta attached to one of its regions
      // anyway would be movement in a window with no rating to have moved.
      expect(build(withRegions([{ regionName: 'A', meanRatingDelta: 0.6 }]))[0].movement).toBeNull();
    });

    it('ignores a non-numeric delta rather than propagating it to the chip', () => {
      expect(build(withRegions([
        { regionName: 'A', meanRating: 4.6, meanRatingDelta: Number.NaN },
      ]))[0].movement).toBeNull();
    });

    it('breaks a tie on the NAME, exactly as the region rail does', () => {
      // ⚠️ A cross-module invariant, and one P6 made observable. `buildRegionRows` ranks the open
      // row's rail with `a.name.localeCompare(b.name)` on a tie; before P6 this function published
      // only a NUMBER, so a payload-order tiebreak here was invisible — equal means are equal.
      // Publishing the region's name and its delta means a divergent tiebreak puts one region on
      // the thumbnail's chip and a different one at rank 1 of the rail eight pixels below, each
      // with its own movement figure. Payload order here is Northumberland first; the rail would
      // pick Cumbria.
      const [card] = build(withRegions([
        { regionName: 'Northumberland', meanRating: 3.4, meanRatingDelta: 0.6 },
        { regionName: 'Cumbria', meanRating: 3.4, meanRatingDelta: -0.2 },
      ]));

      expect(card.movement).toEqual({ regionName: 'Cumbria', delta: -0.2 });
      expect(card.topMeanRating).toBe(3.4);
    });

    it('takes the delta of the sky region, never of an excluded wood', () => {
      // The same exclusion `topMeanRating` applies. A wood scores on inverted polarity, so its
      // movement is a claim about mist rather than about the sky the thumbnail is painting.
      const [card] = build({
        events: events([TODAY, 'SUNSET']),
        days: [day(TODAY, [{
          targetType: 'SUNSET',
          regions: [
            {
              regionName: 'Sky',
              meanRating: 4.2,
              meanRatingDelta: 0.2,
              slots: [{ locationName: 'A', canopy: false }],
            },
            {
              regionName: 'Woods',
              meanRating: 4.8,
              meanRatingDelta: -1.5,
              slots: [{ locationName: 'B', canopy: true }],
            },
          ],
          unregioned: [],
          window: { verdict: 'WORTH_IT', badges: [] },
        }])],
      });

      expect(card.movement).toEqual({ regionName: 'Sky', delta: 0.2 });
    });
  });

  it('takes the BEST of the window\'s region means', () => {
    const [card] = build(withRegions([
      { regionName: 'A', meanRating: 2.4 },
      { regionName: 'B', meanRating: 4.6 },
      { regionName: 'C', meanRating: 3.1 },
    ]));

    expect(card.topMeanRating).toBe(4.6);
  });

  it('is null when no region carries a mean, so an unrated window can rank last', () => {
    // Deliberately not 0. A zero would sort an unlooked-at window among the poor ones, and
    // AWAITING is the absence of a forecast rather than a bad one — `windowFirstOrder.js` reads
    // the null and ranks it last.
    expect(build(withRegions([{ regionName: 'A' }, { regionName: 'B', meanRating: null }]))[0]
      .topMeanRating).toBeNull();
  });

  it('is null for a window with no regions at all', () => {
    expect(build(withRegions([]))[0].topMeanRating).toBeNull();
  });

  it('ignores a non-numeric mean rather than propagating NaN into the sort', () => {
    const [card] = build(withRegions([
      { regionName: 'A', meanRating: Number.NaN },
      { regionName: 'B', meanRating: 3 },
    ]));

    expect(card.topMeanRating).toBe(3);
  });

  it('EXCLUDES an all-woodland region, which the backend\'s own ranking drops too', () => {
    // ⚠️ The defect this is here for. `PlanWindowProjector.rank` filters out a region holding no
    // non-canopy slot before it ranks anything, and `BriefingRegion.meanRating` falls back to canopy
    // slots PER REGION — so an all-wood region publishes a mean derived from inverted-polarity
    // scores (a canopy GO means heavy cloud and mist). Reduced over the raw region list, the wood
    // at 4.8 outranks the sky at 4.2 and takes rank 1 under `Order · Best`, beneath its own header
    // reading "Poor" and beside a thumbnail that paints no heat there at all — the field is
    // sky-gated. Precisely the defect P1's review caught in the field, one surface along.
    const [card] = build({
      events: events([TODAY, 'SUNSET']),
      days: [day(TODAY, [{
        targetType: 'SUNSET',
        regions: [
          { regionName: 'Sky', meanRating: 4.2, slots: [{ locationName: 'A', canopy: false }] },
          { regionName: 'Woods', meanRating: 4.8, slots: [{ locationName: 'B', canopy: true }] },
        ],
        unregioned: [],
        window: { verdict: 'WORTH_IT', badges: [] },
      }])],
    });

    expect(card.topMeanRating).toBe(4.2);
  });

  it('keeps a region that has a sky slot beside its wood', () => {
    // The gate is the PRESENCE of a sky slot, never whether one is rated — the projector\'s rule.
    // A rated-keyed test would hand an ordinary misty sunrise to the wood, because the fog that
    // leaves every sky slot unrated is the same fog that scores the wood well.
    const [card] = build({
      events: events([TODAY, 'SUNSET']),
      days: [day(TODAY, [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'Mixed',
          meanRating: 4.8,
          slots: [{ locationName: 'A', canopy: true }, { locationName: 'B', canopy: false }],
        }],
        unregioned: [],
        window: { verdict: 'WORTH_IT', badges: [] },
      }])],
    });

    expect(card.topMeanRating).toBe(4.8);
  });

  it('keeps the woods when the WHOLE window is canopy, rather than ranking on nothing', () => {
    // The projector\'s `canopyCounts` fallback, mirrored. With no sky answer anywhere in the window
    // there is nothing to prefer, and dropping every region would leave the window unrankable — it
    // would sort last as though unforecast, which is a different and false claim.
    const [card] = build({
      events: events([TODAY, 'SUNSET']),
      days: [day(TODAY, [{
        targetType: 'SUNSET',
        regions: [{ regionName: 'Woods', meanRating: 4.8, slots: [{ locationName: 'B', canopy: true }] }],
        unregioned: [],
        window: { verdict: 'WORTH_IT', badges: [] },
      }])],
    });

    expect(card.topMeanRating).toBe(4.8);
  });

  it('keeps a region carrying no slots at all, as the projector\'s own filter does', () => {
    // `r.slots().isEmpty() || …anyMatch(s -> !s.canopy())` — an empty region makes no canopy claim
    // either way, and `BriefingHonestyFilter` empties slot lists on zero-coverage regions.
    const [card] = build({
      events: events([TODAY, 'SUNSET']),
      days: [day(TODAY, [{
        targetType: 'SUNSET',
        regions: [
          { regionName: 'Empty', meanRating: 3.9, slots: [] },
          { regionName: 'Sky', meanRating: 2.0, slots: [{ locationName: 'A', canopy: false }] },
        ],
        unregioned: [],
        window: { verdict: 'WORTH_IT', badges: [] },
      }])],
    });

    expect(card.topMeanRating).toBe(3.9);
  });

  it('is a DIFFERENT quantity from bestRating, which is one location\'s score', () => {
    // Ranking six windows by a single best spot would put a window with one exceptional location
    // above one where a whole region is good — the opposite of "which window is the best bet". The
    // two are allowed to disagree, and this is where that is stated.
    const [card] = build({
      events: events([TODAY, 'SUNSET']),
      days: [day(TODAY, [{
        targetType: 'SUNSET',
        regions: [{ regionName: 'A', meanRating: 2.5 }],
        unregioned: [],
        window: { verdict: 'WORTH_IT', badges: [], bestRating: 5 },
      }])],
    });

    expect(card.bestRating).toBe(5);
    expect(card.topMeanRating).toBe(2.5);
  });
});
