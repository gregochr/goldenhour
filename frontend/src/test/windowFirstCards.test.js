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
      expect(card.allBadges).toEqual([]);
    });

    it('gives every card a badge array, so the renderer never guards', () => {
      const days = [day(TODAY, [{ targetType: 'SUNSET', regions: [], unregioned: [], window: { verdict: 'MAYBE' } }])];
      expect(build({ events: events([TODAY, 'SUNSET']), days })[0].allBadges).toEqual([]);
    });

    it('gives every card a rows array, so the renderer never guards', () => {
      const days = [day(TODAY, [{ targetType: 'SUNSET', regions: [], unregioned: [], window: { verdict: 'MAYBE' } }])];
      expect(build({ events: events([TODAY, 'SUNSET']), days })[0].rows).toEqual([]);
    });
  });

  describe('the attribute row, and the badge list beside it', () => {
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

    it('⚠️ promotes NOTHING into a row any more, and keeps every badge on one list', () => {
      // The duplication rule this used to encode — "a topic is a row or a badge, never both" — was
      // about a card with a header of chips above a row band. The popup has one list: its topic
      // rows carry the label, the detail, the science note AND the measured facts, so a snow
      // attribute row would print one topic twice. `badges` (the post-promotion remainder) went
      // with the promotion; `allBadges` is the whole population and the only list.
      const [card] = build(oneWindow({ badges: [snowBadge()] }));

      expect(card.rows).toEqual([]);
      expect(card.allBadges.map((b) => b.label)).toEqual(['Snow on the fells']);
      expect(card).not.toHaveProperty('badges');
    });

    it('⚠️ carries no window-level `topRarityRank`, which went with the promoted strip', () => {
      // M5 deleted it and the test that pinned "the card must not read it" went with the strip's own
      // suite, leaving the deletion covered by nothing — the away block got an exact key-set pin for
      // the identical class and this did not. `BriefingWindow` still SENDS the field; what must not
      // come back is a copy on the card, because every surface that orders topics ranks the BADGES
      // on their own `rarityRank` and a window-level minimum kept alive beside them is a second
      // answer waiting to disagree with the first.
      //
      // The payload carries it here on purpose: a test that omitted it would pass against a
      // `topRarityRank: win?.topRarityRank` line and prove nothing.
      const [card] = build(oneWindow({ badges: [snowBadge()], topRarityRank: 3 }));

      expect(card).not.toHaveProperty('topRarityRank');
      expect(card.allBadges[0].rarityRank).toBeDefined();
    });

    it('keeps the tide row beside a snow topic, rather than one displacing the other', () => {
      const fresh = snowBadge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 });
      const [card] = build(oneWindow({ tide: TIDE, badges: [fresh, snowBadge()] }));

      expect(card.rows.map((r) => r.channel)).toEqual(['tide']);
      expect(card.allBadges.map((b) => b.label)).toEqual(['Fresh snow', 'Snow on the fells']);
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

    describe('the pool the matrix measures, and its head', () => {
      /**
       * The reach-gated, pre-floor set the spread histogram and the best-reachable line read
       * (plan-matrix A10/A11).
       *
       * <p>The rule worth mutating against is that the RATING FLOOR does not touch it. The design's
       * own reason is that an average of things which already passed a 4★ filter always reads
       * 4-something — so a floor applied here would flatten the histogram to one band and make the
       * card's picture agree with its own control rather than with the sky.
       */
      it('is what reach left, with the rating floor NOT applied', () => {
        // 4★ floor, 45-minute tier. `spots` keeps one; the pool keeps both the tier allowed,
        // including the 3★ the floor removed.
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: 45, defaultLimitMinutes: 45, tierId: '45', floorId: '4', minRating: 4,
        });
        expect(card.spots.map((s) => s.locationName)).toEqual(['Blyth Beach']);
        expect(card.pool.map((s) => s.locationName)).toEqual(['Blyth Beach', 'Simonside']);
      });

      it('IS gated by reach, so it can never name a place beyond the tier', () => {
        // The other half of the same rule: the histogram counts places to go, and a spot outside
        // the tier is not one. Sycamore Gap at 66 minutes is beyond a 45-minute tier.
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: 45, defaultLimitMinutes: 45, tierId: '45', floorId: 'any', minRating: null,
        });
        expect(card.pool.map((s) => s.locationName)).toEqual(['Blyth Beach', 'Simonside']);
      });

      it('counts the same array the bar\'s denominator counts', () => {
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: 45, defaultLimitMinutes: 45, tierId: '45', floorId: '4', minRating: 4,
        });
        expect(card.pool).toHaveLength(card.reachedTotal);
      });

      it('excludes a canopy slot, so a rated wood never reaches the histogram', () => {
        // Plan §3 rule 12: a rating that does not mean sky colour never reaches a pool. A woodland
        // GO means heavy cloud and mist, so a wood rated 5 on a flat misty dawn would put a bar at
        // the top of the ramp on precisely the morning the sky is at its worst.
        const withWood = THREE_SPOTS.concat([
          { locationId: 4, locationName: 'Allen Banks', claudeRating: 5, canopy: true },
        ]);
        const [card] = buildWithLens(withWood, REACH, {
          limitMinutes: null, defaultLimitMinutes: 45,
        });
        expect(card.pool.map((s) => s.locationName)).not.toContain('Allen Banks');
      });

      it('takes its head from the existing comparator, never a second ordering', () => {
        // A11: the best-reachable line is the head of THIS pool under `compareSpots` — rating
        // descending, then drive. Nothing here re-ranks, so the head is `pool[0]` by construction
        // and the assertion is that identity.
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: null, defaultLimitMinutes: 45,
        });
        expect(card.bestReach).toBe(card.pool[0]);
        expect(card.bestReach.locationName).toBe('Sycamore Gap');
      });

      it('takes the NEARER of two equal ratings, which is the comparator\'s second key', () => {
        const tie = [
          { locationId: 3, locationName: 'Sycamore Gap', claudeRating: 5, canopy: false },
          { locationId: 1, locationName: 'Simonside', claudeRating: 5, canopy: false },
        ];
        const [card] = buildWithLens(tie, REACH, { limitMinutes: null, defaultLimitMinutes: 45 });
        expect(card.bestReach.locationName).toBe('Simonside');
      });

      it('re-heads when the tier removes the best-rated spot', () => {
        // What makes the line "the best you could actually reach" rather than "the best there is":
        // the window's own `bestRating` is untouched, and this follows the lens.
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: 45, defaultLimitMinutes: 45, tierId: '45',
        });
        expect(card.bestReach.locationName).toBe('Blyth Beach');
      });

      it('has NO head when nothing in the pool is rated, though the pool is not empty', () => {
        // The ordinary far-horizon state — T+4 is never evaluated — and the one the card must not
        // read as "nothing in reach". `compareSpots` sorts unrated last, so an unrated head means
        // no rating was compared at all and the order that chose it was alphabetical: naming that
        // spot "best" would claim a ranking that never ran.
        const unrated = [
          { locationId: 1, locationName: 'Simonside', claudeRating: null, canopy: false },
          { locationId: 2, locationName: 'Blyth Beach', claudeRating: null, canopy: false },
        ];
        const [card] = buildWithLens(unrated, REACH, { limitMinutes: null, defaultLimitMinutes: 45 });
        expect(card.pool).toHaveLength(2);
        expect(card.bestReach).toBeNull();
      });

      it('has no head when the pool is empty', () => {
        const [card] = buildWithLens(THREE_SPOTS, REACH, {
          limitMinutes: 5, defaultLimitMinutes: 45, tierId: '45',
        });
        expect(card.pool).toEqual([]);
        expect(card.bestReach).toBeNull();
      });

      it('keeps a rated head when only SOME of the pool is unrated', () => {
        const mixed = [
          { locationId: 1, locationName: 'Simonside', claudeRating: null, canopy: false },
          { locationId: 2, locationName: 'Blyth Beach', claudeRating: 4, canopy: false },
        ];
        const [card] = buildWithLens(mixed, REACH, { limitMinutes: null, defaultLimitMinutes: 45 });
        expect(card.bestReach.locationName).toBe('Blyth Beach');
      });

      it('is scoped by the origin BEFORE either gate, so it counts the region you asked for', () => {
        const [card] = buildWindowCards(
          events([TODAY, 'SUNSET']),
          [day(TODAY, [{
            targetType: 'SUNSET',
            regions: [
              { regionName: 'Northumberland & Tyneside', slots: [THREE_SPOTS[0]] },
              { regionName: 'The Lake District', slots: [THREE_SPOTS[2]] },
            ],
            unregioned: [],
            window: { verdict: 'WORTH_IT', badges: [] },
          }])],
          TODAY, TOMORROW, new Set(), REACH,
          {
            limitMinutes: null,
            defaultLimitMinutes: 45,
            origin: { name: 'The Lake District', baseName: 'Keswick' },
          },
        );
        expect(card.pool.map((s) => s.locationName)).toEqual(['Sycamore Gap']);
        expect(card.bestReach.locationName).toBe('Sycamore Gap');
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

      it('⚠️ carries no empty-state descriptor of its own any more', () => {
        // M2 deleted `lensEmpty`. The card's per-window sentence is now composed by the POPUP from
        // the same two thresholds (`WindowSheetDialog`'s quiet line) and the plan-wide one by
        // `planConflicts.js` — both from the lenses directly, so the descriptor had two readers and
        // then none. Pinned as an absence because a field nothing renders is the kind of thing that
        // comes back by accident.
        const empty = buildWithLens(
          [{ locationId: 1, locationName: 'Simonside', claudeRating: 2, canopy: false }],
          REACH,
          { limitMinutes: null, defaultLimitMinutes: 45, tierId: 'any', floorId: '4', minRating: 4 },
        )[0];

        expect(empty.spots).toEqual([]);
        expect(empty).not.toHaveProperty('lensEmpty');
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
    // AWAITING is the absence of a forecast rather than a bad one — every ranking in this arm reads
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

  describe('hotRegionName — reused from topRegion, never a fresh argmax (field-geography plan §2.3)', () => {
    it('names the SAME region topMeanRating and movement do', () => {
      const [card] = build(withRegions([
        { regionName: 'A', meanRating: 2.4, meanRatingDelta: 1.9 },
        { regionName: 'B', meanRating: 4.6, meanRatingDelta: -0.3 },
      ]));

      expect(card.hotRegionName).toBe('B');
      expect(card.movement.regionName).toBe('B');
    });

    it('is null when nothing in the window carries a mean — unlike the prototype\'s seeded reduce, which would brighten the first region', () => {
      expect(build(withRegions([{ regionName: 'A' }, { regionName: 'B' }]))[0].hotRegionName)
        .toBeNull();
    });

    it('breaks a tie on the NAME, exactly as topMeanRating and the region rail do', () => {
      const [card] = build(withRegions([
        { regionName: 'Northumberland', meanRating: 3.4 },
        { regionName: 'Cumbria', meanRating: 3.4 },
      ]));

      expect(card.hotRegionName).toBe('Cumbria');
    });

    it('inherits the canopy filter — an all-woodland region never brightens a sky-gated field', () => {
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

      expect(card.hotRegionName).toBe('Sky');
    });
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

/**
 * The origin's scope (plan §4.8, P7) — the frame of reference applied BEFORE either lens gate.
 *
 * <p><b>What breaks if these fail.</b> The scope decides what the page is about, so every count the
 * card prints — {@code reachTotal}, {@code reachedTotal}, the empty state's "N spots are further
 * out" — is a count OF the scope. A scope applied after the gates, or not at all, would leave a
 * card in the Lake District counting Northumberland's spots in its own explanation.
 */
describe('buildWindowCards — the origin\'s scope', () => {
  const SPOTS = [
    { locationId: 1, locationName: 'Derwentwater', claudeRating: 4, canopy: false },
    { locationId: 2, locationName: 'Bamburgh Beach', claudeRating: 5, canopy: false },
  ];
  const REACH = new Map([
    [1, { driveMinutes: 12, distanceMiles: 5 }],
    [2, { driveMinutes: 30, distanceMiles: 19 }],
  ]);
  const ORIGIN = { id: 7, name: 'Lake District', baseName: 'Keswick' };

  // Served per-region records, deliberately DIFFERENT from the window's roll-up on every field —
  // equal fixtures would let a re-point be swapped for a pass-through undetected.
  const twoRegions = () => [day(TODAY, [{
    targetType: 'SUNSET',
    regions: [
      {
        regionName: 'Lake District',
        slots: [SPOTS[0]],
        displayVerdict: 'MAYBE',
        bestRating: 4,
        meanRating: 3.2,
        confidence: 'low',
        meanRatingDelta: -0.4,
      },
      {
        regionName: 'Northumberland',
        slots: [SPOTS[1]],
        displayVerdict: 'WORTH_IT',
        bestRating: 5,
        meanRating: 4.6,
        confidence: 'high',
        meanRatingDelta: 0.6,
      },
    ],
    unregioned: [],
    window: {
      verdict: 'WORTH_IT',
      badges: [],
      bestRating: 5,
      confidence: 'high',
      pick: {
        kind: 'BEST',
        regionName: 'Northumberland',
        headline: 'Breaking clear',
        locationName: 'Bamburgh Beach',
      },
    },
  }])];

  const buildScoped = (lens) => buildWindowCards(
    events([TODAY, 'SUNSET']), twoRegions(), TODAY, TOMORROW, new Set(), REACH, lens,
  );

  it('keeps both regions at home', () => {
    const [card] = buildScoped({ limitMinutes: null, defaultLimitMinutes: 150 });
    expect(card.spots.map((s) => s.locationName).sort())
      .toEqual(['Bamburgh Beach', 'Derwentwater']);
  });

  it('narrows every window to the origin\'s own region', () => {
    const [card] = buildScoped({ limitMinutes: null, defaultLimitMinutes: 90, origin: ORIGIN });
    expect(card.spots.map((s) => s.locationName)).toEqual(['Derwentwater']);
  });

  it('⚠️ makes the card\'s own counts counts of the SCOPE, not of the roster', () => {
    const [card] = buildScoped({ limitMinutes: null, defaultLimitMinutes: 90, origin: ORIGIN });
    expect(card.reachTotal).toBe(1);
    expect(card.reachedTotal).toBe(1);
    expect(card.allSpots.map((s) => s.locationName)).toEqual(['Derwentwater']);
  });

  it('⚠️ runs BEFORE the reach gate, so the lens chooses within the region', () => {
    // Bamburgh is 30 minutes away and Derwentwater 12. Under a 20-minute reach with no scope,
    // Derwentwater alone survives; the point of this test is the other order — with the scope on
    // the LAKES and a reach that admits both, the roster's other region is gone because of the
    // scope, and the empty-state machinery must not blame the lens for it.
    const [card] = buildScoped({ limitMinutes: 45, defaultLimitMinutes: 45, origin: ORIGIN });
    expect(card.spots.map((s) => s.locationName)).toEqual(['Derwentwater']);
    // The scope removed the other region BEFORE either gate, so what is left is the lens's own
    // choice within the Lakes rather than a window the lens emptied.
    expect(card.allSpots.map((s) => s.locationName)).toEqual(['Derwentwater']);
  });

  it('hands an away empty scope an empty card, and says nothing else about it', () => {
    // ⚠️ The explanation and the way home moved to the page-level conflict message at M2
    // (`planConflicts.test.js` owns both), so what the CARD owes is an honest empty set: an origin
    // whose region holds nothing in this window scopes every gate to nothing.
    const [card] = buildScoped({
      limitMinutes: null,
      defaultLimitMinutes: 90,
      origin: { id: 9, name: 'Peak District', baseName: 'Bakewell' },
    });
    expect(card.spots).toEqual([]);
    expect(card.allSpots).toEqual([]);
    expect(card.pool).toEqual([]);
  });

  it('⚠️ hotRegionName is null for a scoped region present in the payload but carrying no mean — an ordinary AWAITING state', () => {
    // The gap an adversarial review caught: `topMeanRating`'s scoped branch gates on
    // `scopedRegion.meanRating` being a finite number before returning it; `hotRegionName`'s must
    // do the same, or a window whose origin region is served but unrated would still brighten that
    // region's label — exactly what §2.3's "nothing rated → null, no label brightens" forbids.
    const days = [day(TODAY, [{
      targetType: 'SUNSET',
      regions: [
        {
          regionName: 'Lake District', slots: [SPOTS[0]], displayVerdict: 'AWAITING', bestRating: null,
        },
        {
          regionName: 'Northumberland',
          slots: [SPOTS[1]],
          displayVerdict: 'WORTH_IT',
          bestRating: 5,
          meanRating: 4.6,
        },
      ],
      unregioned: [],
      window: { verdict: 'AWAITING', badges: [] },
    }])];
    const [card] = buildWindowCards(
      events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set(), REACH,
      { limitMinutes: null, defaultLimitMinutes: 90, origin: ORIGIN },
    );

    expect(card.hotRegionName).toBeNull();
  });

  it('⚠️ re-points the header figures to the ORIGIN REGION\'s own served record', () => {
    // At home the card carries the window's roster-wide roll-up. Away that roll-up describes a
    // region the reader has scoped out — `best spot 5★` over a strip whose best card is 4★, and a
    // `Worth it` badge for somewhere else — so every one of these switches to the served
    // `BriefingRegion` record for the origin's own region.
    const [home] = buildScoped({ limitMinutes: null, defaultLimitMinutes: 150 });
    const [away] = buildScoped({ limitMinutes: null, defaultLimitMinutes: 90, origin: ORIGIN });

    expect(home.verdict).toBe('WORTH_IT');
    expect(home.bestRating).toBe(5);
    expect(home.topMeanRating).toBe(4.6);
    expect(home.confidence).toBe('high');
    expect(home.movement).toEqual({ regionName: 'Northumberland', delta: 0.6 });
    expect(home.hotRegionName).toBe('Northumberland');

    expect(away.verdict).toBe('MAYBE');
    expect(away.verdictLabel).toBe('Maybe');
    expect(away.bestRating).toBe(4);
    expect(away.topMeanRating).toBe(3.2);
    expect(away.confidence).toBe('low');
    expect(away.movement).toEqual({ regionName: 'Lake District', delta: -0.4 });
    // Away, `hotRegionName` re-points to the scoped region itself (§2.3) — never an argmax over a
    // roster the reader has just scoped away from.
    expect(away.hotRegionName).toBe('Lake District');
  });

  it('⚠️ falls back to the roster-wide leader when the origin\'s own region has no record, like movement and topMeanRating', () => {
    // `originRegion` returns null for a region the window carries no record for — an ordinary
    // state, not every region appears in every window — and every sibling header field then falls
    // back to describing the WHOLE window rather than fabricating a value for the missing scoped
    // region (the class comment's "honest" fallback). `hotRegionName` follows the same rule.
    const [card] = buildScoped({
      limitMinutes: null,
      defaultLimitMinutes: 90,
      origin: { id: 9, name: 'Peak District', baseName: 'Bakewell' },
    });
    expect(card.movement).toEqual({ regionName: 'Northumberland', delta: 0.6 });
    expect(card.hotRegionName).toBe('Northumberland');
  });

  it('⚠️ takes every re-pointed figure from the SERVED record, never from the scoped spots', () => {
    // Derwentwater is rated 4 in the fixture and the Lakes' served `bestRating` is also 4 — so this
    // asserts against a record whose served mean (3.2) NO client-side aggregation over one 4★ spot
    // could produce. A max or a mean over `card.spots` would answer 4, which is the aggregation
    // class Phase 3 of the verdict consolidation moved server-side.
    const [away] = buildScoped({ limitMinutes: null, defaultLimitMinutes: 90, origin: ORIGIN });
    expect(away.spots.map((s) => s.rating)).toEqual([4]);
    expect(away.topMeanRating).toBe(3.2);
  });

  it('withholds a pick that names a region the origin has scoped away', () => {
    // The badge opens a dialog naming that region, and the strip folds `pick.kind === 'best'`
    // straight into its BEST BET flag — so an out-of-scope pick recommends somewhere the reader
    // has just said they are not.
    const [home] = buildScoped({ limitMinutes: null, defaultLimitMinutes: 150 });
    const [away] = buildScoped({ limitMinutes: null, defaultLimitMinutes: 90, origin: ORIGIN });
    expect(home.pick).toMatchObject({ kind: 'best', regionName: 'Northumberland' });
    expect(away.pick).toBeNull();
  });

  it('keeps a pick that names the origin\'s own region', () => {
    const [away] = buildScoped({
      limitMinutes: null,
      defaultLimitMinutes: 90,
      origin: { id: 8, name: 'Northumberland', baseName: 'Alnwick' },
    });
    expect(away.pick).toMatchObject({ kind: 'best', regionName: 'Northumberland' });
  });

  it('falls back to the window\'s own figures for a scoped region the payload never named', () => {
    const [away] = buildScoped({
      limitMinutes: null,
      defaultLimitMinutes: 90,
      origin: { id: 9, name: 'Peak District', baseName: 'Bakewell' },
    });
    // No served record for that region at all → the card falls back to the window's own figures,
    // which is what `originRegion` returning null means. The verdict is the window's, and the spot
    // strip is empty — what explains that to the reader is the page-level conflict message.
    expect(away.spots).toEqual([]);
    // The window's OWN verdict, not the Peak District's — there is no served record for that region
    // to re-point to, which is exactly what `originRegion` returning null means.
    expect(away.verdict).toBe('WORTH_IT');
  });
});
