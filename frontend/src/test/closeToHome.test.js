import { describe, it, expect } from 'vitest';
import {
  RADIUS_MILES,
  buildCloseToHome,
  collectNearbySlots,
  distanceMiles,
  slotTideLabel,
} from '../utils/closeToHome.js';

// ── Fixture scaffolding ───────────────────────────────────────────────────────
//
// Home sits at Whitley Bay (55.04, -1.45). The roster mirrors the handoff's reference data: two
// regions, four in-radius spots, and one deliberately far one so the distance gate has something
// to reject.

const HOME = { lat: 55.04, lon: -1.45 };

const LOCATIONS = [
  { name: "St Mary's Lighthouse", lat: 55.072, lon: -1.451, regionName: 'Northumberland' },
  { name: 'Souter Lighthouse', lat: 54.969, lon: -1.359, regionName: 'Tyne and Wear' },
  { name: 'Tynemouth Priory', lat: 55.017, lon: -1.418, regionName: 'Tyne and Wear' },
  // ~16 miles out — the furthest spot still inside the gate.
  { name: 'Simonside', lat: 55.200, lon: -1.750, regionName: 'Northumberland' },
  { name: 'Whitburn Coast', lat: 54.951, lon: -1.361, regionName: 'Tyne and Wear' },
  // ~90 miles away — always outside the 22-mile gate.
  { name: 'Malham Cove', lat: 54.070, lon: -2.157, regionName: 'The Yorkshire Dales' },
];

/** ISO date N days from today, in the same calendar the component uses. */
function dateStr(offset = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(d);
}

const TODAY = dateStr(0);
const TOMORROW = dateStr(1);
const DAY_AFTER = dateStr(2);
const DAY_FOUR = dateStr(3);

/** A slot with sensible defaults; `displayVerdict` drives the poor/qualifying split. */
function slot(locationName, overrides = {}) {
  return {
    locationName,
    // Far enough into the future that isEventPast() never trips mid-run.
    solarEventTime: `${TOMORROW}T05:15:00`,
    verdict: 'GO',
    displayVerdict: 'WORTH_IT',
    claudeRating: 4,
    flags: [],
    ...overrides,
  };
}

/** One day carrying a single sunrise event with the given regions. */
function day(date, regions, targetType = 'SUNRISE') {
  return {
    date,
    eventSummaries: [{ targetType, regions, unregioned: [] }],
  };
}

function region(regionName, slots) {
  return { regionName, verdict: 'GO', displayVerdict: 'WORTH_IT', summary: '', tideHighlights: [], slots };
}

/** Every in-fixture slot event time pinned to the given date, so nothing reads as past. */
function futureSlot(locationName, date, overrides = {}) {
  return slot(locationName, { solarEventTime: `${date}T05:15:00`, ...overrides });
}

const BASE_ARGS = {
  locations: LOCATIONS,
  homeCoords: HOME,
  todayStr: TODAY,
  tomorrowStr: TOMORROW,
};

// ── distanceMiles ────────────────────────────────────────────────────────────

describe('distanceMiles', () => {
  it('returns zero for a point against itself', () => {
    expect(distanceMiles(55.04, -1.45, 55.04, -1.45)).toBeCloseTo(0, 6);
  });

  it('measures a known separation in miles, not kilometres', () => {
    // Whitley Bay → St Mary's Lighthouse is a shade over 2 miles (≈3.6 km). A km-based
    // implementation would return ~3.6 here, so this pins the unit as much as the maths.
    const miles = distanceMiles(55.04, -1.45, 55.072, -1.451);
    expect(miles).toBeGreaterThan(2.0);
    expect(miles).toBeLessThan(2.5);
  });

  it('returns null when any coordinate is missing', () => {
    expect(distanceMiles(null, -1.45, 55.07, -1.45)).toBeNull();
    expect(distanceMiles(55.04, -1.45, 55.07, undefined)).toBeNull();
  });
});

// ── slotTideLabel ────────────────────────────────────────────────────────────

describe('slotTideLabel', () => {
  it('prefers a king tide over a spring tide', () => {
    expect(slotTideLabel({ isKingTide: true, isSpringTide: true })).toBe('king tide');
  });

  it('names a spring tide', () => {
    expect(slotTideLabel({ isSpringTide: true })).toBe('spring tide');
  });

  it('falls back to the aligned tide state', () => {
    expect(slotTideLabel({ tideAligned: true, tideState: 'LOW' })).toBe('low tide');
  });

  it('says nothing for an unaligned or inland slot', () => {
    expect(slotTideLabel({ tideAligned: false, tideState: 'HIGH' })).toBeNull();
    expect(slotTideLabel({})).toBeNull();
    expect(slotTideLabel(null)).toBeNull();
  });
});

// ── collectNearbySlots ───────────────────────────────────────────────────────

describe('collectNearbySlots', () => {
  const days = [day(TOMORROW, [
    region('Northumberland', [futureSlot("St Mary's Lighthouse", TOMORROW)]),
    region('The Yorkshire Dales', [futureSlot('Malham Cove', TOMORROW)]),
  ])];

  it('returns nothing without home coordinates', () => {
    expect(collectNearbySlots({ ...BASE_ARGS, briefingDays: days, homeCoords: null })).toEqual([]);
  });

  it('drops locations beyond the radius', () => {
    const found = collectNearbySlots({ ...BASE_ARGS, briefingDays: days });
    expect(found.map((c) => c.locationName)).toEqual(["St Mary's Lighthouse"]);
  });

  it('caps collection at the forecast horizon', () => {
    const fourDays = [TOMORROW, DAY_AFTER, DAY_FOUR, dateStr(4)].map((d) => day(d, [
      region('Northumberland', [futureSlot("St Mary's Lighthouse", d)]),
    ]));
    const found = collectNearbySlots({ ...BASE_ARGS, briefingDays: fourDays });
    expect([...new Set(found.map((c) => c.date))]).toEqual([TOMORROW, DAY_AFTER, DAY_FOUR]);
  });

  it('reads unregioned slots too', () => {
    const withUnregioned = [{
      date: TOMORROW,
      eventSummaries: [{
        targetType: 'SUNRISE',
        regions: [],
        unregioned: [futureSlot('Tynemouth Priory', TOMORROW)],
      }],
    }];
    const found = collectNearbySlots({ ...BASE_ARGS, briefingDays: withUnregioned });
    expect(found).toHaveLength(1);
    // The region label still comes from the location roster, not the (absent) briefing region.
    expect(found[0].regionName).toBe('Tyne and Wear');
  });

  it('prefers a cached evaluation score over the payload rating', () => {
    const scores = new Map([
      [`Northumberland|${TOMORROW}|SUNRISE|St Mary's Lighthouse`, { rating: 2 }],
    ]);
    const found = collectNearbySlots({ ...BASE_ARGS, briefingDays: days, evaluationScores: scores });
    expect(found[0].rating).toBe(2);
  });
});

// ── buildCloseToHome ─────────────────────────────────────────────────────────

describe('buildCloseToHome', () => {
  it('returns null without a saved home postcode', () => {
    const days = [day(TOMORROW, [region('Northumberland', [futureSlot("St Mary's Lighthouse", TOMORROW)])])];
    expect(buildCloseToHome({ ...BASE_ARGS, briefingDays: days, homeCoords: null })).toBeNull();
  });

  it('returns null when nothing at all is within reach', () => {
    const days = [day(TOMORROW, [region('The Yorkshire Dales', [futureSlot('Malham Cove', TOMORROW)])])];
    expect(buildCloseToHome({ ...BASE_ARGS, briefingDays: days })).toBeNull();
  });

  it('ranks strictly by rating, ignoring distance', () => {
    // Tynemouth Priory is by far the closest and still ranks last of the three — the
    // "distance gates, rating sorts" rule, which is the whole point of the block.
    const days = [day(TOMORROW, [
      region('Northumberland', [futureSlot("St Mary's Lighthouse", TOMORROW, { claudeRating: 5 })]),
      region('Tyne and Wear', [
        futureSlot('Souter Lighthouse', TOMORROW, { claudeRating: 4 }),
        futureSlot('Tynemouth Priory', TOMORROW, { claudeRating: 3, displayVerdict: 'MAYBE' }),
      ]),
    ])];
    const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
    expect(model.cards.map((c) => c.locationName))
      .toEqual(["St Mary's Lighthouse", 'Souter Lighthouse', 'Tynemouth Priory']);
    expect(model.cards[0].lead).toBe(true);
    expect(model.cards[1].lead).toBe(false);
  });

  it('crosses region lines freely and shows the real region', () => {
    const days = [day(TOMORROW, [
      // The briefing groups this slot under Tyne and Wear, but the location's own region is
      // Northumberland — the honesty label must follow the location, not the roll-up.
      region('Tyne and Wear', [futureSlot("St Mary's Lighthouse", TOMORROW)]),
    ])];
    const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
    expect(model.cards[0].regionName).toBe('Northumberland');
  });

  it('never puts a stand-down spot on a card', () => {
    const days = [day(TOMORROW, [
      region('Tyne and Wear', [
        futureSlot('Tynemouth Priory', TOMORROW, { displayVerdict: 'STAND_DOWN', verdict: 'STANDDOWN', claudeRating: 1 }),
        futureSlot('Souter Lighthouse', TOMORROW, { claudeRating: 4 }),
      ]),
    ])];
    const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
    expect(model.cards.map((c) => c.locationName)).toEqual(['Souter Lighthouse']);
  });

  it('skips unrated locations', () => {
    const days = [day(TOMORROW, [
      region('Tyne and Wear', [futureSlot('Souter Lighthouse', TOMORROW, { claudeRating: null })]),
    ])];
    const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
    expect(model.cards).toEqual([]);
  });

  it('caps the card list at four, dropping the lowest-rated', () => {
    const days = [
      day(TOMORROW, [
        region('Northumberland', [
          futureSlot("St Mary's Lighthouse", TOMORROW, { claudeRating: 5 }),
          futureSlot('Simonside', TOMORROW, { claudeRating: 4 }),
        ]),
        region('Tyne and Wear', [
          futureSlot('Souter Lighthouse', TOMORROW, { claudeRating: 4 }),
          futureSlot('Tynemouth Priory', TOMORROW, { claudeRating: 4 }),
        ]),
      ]),
      day(DAY_AFTER, [
        region('Tyne and Wear', [
          futureSlot('Whitburn Coast', DAY_AFTER, { claudeRating: 3, displayVerdict: 'MAYBE' }),
        ]),
      ]),
    ];
    const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
    expect(model.cards).toHaveLength(4);
    expect(model.cards.map((c) => c.locationName)).not.toContain('Whitburn Coast');
  });

  it('shows one row per location, keeping its best slot across the horizon', () => {
    const days = [
      day(TOMORROW, [region('Northumberland', [futureSlot("St Mary's Lighthouse", TOMORROW, { claudeRating: 3, displayVerdict: 'MAYBE' })])]),
      day(DAY_AFTER, [region('Northumberland', [futureSlot("St Mary's Lighthouse", DAY_AFTER, { claudeRating: 5 })])]),
    ];
    const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
    expect(model.cards).toHaveLength(1);
    expect(model.cards[0].rating).toBe(5);
    expect(model.cards[0].date).toBe(DAY_AFTER);
  });

  it('flags a single-day result set so the day chip can be suppressed', () => {
    const days = [day(TOMORROW, [
      region('Northumberland', [futureSlot("St Mary's Lighthouse", TOMORROW, { claudeRating: 5 })]),
      region('Tyne and Wear', [futureSlot('Souter Lighthouse', TOMORROW, { claudeRating: 4 })]),
    ])];
    expect(buildCloseToHome({ ...BASE_ARGS, briefingDays: days }).multiDay).toBe(false);
  });

  it('flags a multi-day result set', () => {
    const days = [
      day(TOMORROW, [region('Northumberland', [futureSlot("St Mary's Lighthouse", TOMORROW, { claudeRating: 5 })])]),
      day(DAY_AFTER, [region('Tyne and Wear', [futureSlot('Souter Lighthouse', DAY_AFTER, { claudeRating: 4 })])]),
    ];
    expect(buildCloseToHome({ ...BASE_ARGS, briefingDays: days }).multiDay).toBe(true);
  });

  it('attaches per-user drive minutes', () => {
    const days = [day(TOMORROW, [region('Northumberland', [futureSlot("St Mary's Lighthouse", TOMORROW)])])];
    const model = buildCloseToHome({
      ...BASE_ARGS, briefingDays: days, driveMap: new Map([["St Mary's Lighthouse", 22]]),
    });
    expect(model.cards[0].driveMinutes).toBe(22);
  });

  it('exposes the radius it gated on', () => {
    const days = [day(TOMORROW, [region('Northumberland', [futureSlot("St Mary's Lighthouse", TOMORROW)])])];
    expect(buildCloseToHome({ ...BASE_ARGS, briefingDays: days }).radiusMiles).toBe(RADIUS_MILES);
  });

  // ── the breadcrumb ─────────────────────────────────────────────────────────

  describe('breadcrumb', () => {
    it('says stay in and explains why when nothing local qualifies at the next event', () => {
      const days = [
        day(TOMORROW, [region('Tyne and Wear', [
          futureSlot('Tynemouth Priory', TOMORROW, {
            displayVerdict: 'STAND_DOWN', verdict: 'STANDDOWN', standdownReason: 'Heavy cloud', claudeRating: 1,
          }),
        ])]),
        day(DAY_AFTER, [region('Northumberland', [
          futureSlot("St Mary's Lighthouse", DAY_AFTER, { claudeRating: 5 }),
        ])]),
      ];
      const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
      expect(model.breadcrumb.worthIt).toBe(false);
      expect(model.breadcrumb.reason).toContain('Heavy cloud');
      expect(model.breadcrumb.reason).toContain('nothing within reach is worth the trip');
    });

    it('still renders the breadcrumb when part 2 is empty', () => {
      const days = [day(TOMORROW, [region('Tyne and Wear', [
        futureSlot('Tynemouth Priory', TOMORROW, {
          displayVerdict: 'STAND_DOWN', verdict: 'STANDDOWN', standdownReason: 'Rain', claudeRating: 1,
        }),
      ])])];
      const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
      expect(model.cards).toEqual([]);
      expect(model.breadcrumb.worthIt).toBe(false);
      expect(model.breadcrumb.reason).toContain('Rain');
      // Naming the best nearby spot on a poor night undercuts the stay-in verdict — there is
      // no qualifying window here, so nothing is named.
      expect(model.breadcrumb.nextWindow).toBeNull();
    });

    // Discriminating fixture: the soonest window is rated LOWER than a later one. With both
    // rated equally, a rating-first comparator produces the same answer and the test cannot
    // fail — which is exactly how the original version of this test passed while the rule it
    // names was unreachable.
    it('points at the soonest qualifying window even when a later one scores higher', () => {
      const days = [
        day(TOMORROW, [region('Tyne and Wear', [
          futureSlot('Tynemouth Priory', TOMORROW, {
            displayVerdict: 'STAND_DOWN', verdict: 'STANDDOWN', standdownReason: 'Overcast', claudeRating: 1,
          }),
        ])]),
        day(DAY_AFTER, [region('Northumberland', [
          futureSlot("St Mary's Lighthouse", DAY_AFTER, { claudeRating: 3, displayVerdict: 'MAYBE' }),
        ])]),
        day(DAY_FOUR, [region('Tyne and Wear', [
          futureSlot('Souter Lighthouse', DAY_FOUR, { claudeRating: 5 }),
        ])]),
      ];
      const model = buildCloseToHome({
        ...BASE_ARGS, briefingDays: days, driveMap: new Map([["St Mary's Lighthouse", 22]]),
      });
      expect(model.breadcrumb.nextWindow.locationName).toBe("St Mary's Lighthouse");
      expect(model.breadcrumb.nextWindow.rating).toBe(3);
      expect(model.breadcrumb.nextWindow.driveMinutes).toBe(22);
      expect(model.breadcrumb.nextWindow.detail).toContain('sunrise');
    });

    // The other half of the rule, and the one that was actually broken: within a single event,
    // the best spot wins. Solar times are per location, so two spots at the same sunrise differ
    // by minutes — keying the sort on that time made this tiebreak unreachable and named
    // whichever the sun reached first, contradicting the lead card directly beneath it.
    it('names the best-rated spot within one event, not the one the sun reaches first', () => {
      const days = [day(TOMORROW, [
        region('Tyne and Wear', [
          { ...futureSlot('Souter Lighthouse', TOMORROW, { claudeRating: 3, displayVerdict: 'MAYBE' }),
            solarEventTime: `${TOMORROW}T05:12:00` },
        ]),
        region('Northumberland', [
          { ...futureSlot("St Mary's Lighthouse", TOMORROW, { claudeRating: 5 }),
            solarEventTime: `${TOMORROW}T05:15:00` },
        ]),
      ])];
      const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
      expect(model.breadcrumb.nextWindow.locationName).toBe("St Mary's Lighthouse");
      // And it must agree with the card grid beneath it — a panel that contradicts itself is
      // the actual user-visible defect here.
      expect(model.cards[0].locationName).toBe("St Mary's Lighthouse");
    });

    it('falls back to the event summary time when no nearby spot is briefed for that event', () => {
      // Every in-radius location is briefed for sunset only; the next event is a sunrise
      // carried by a distant region. The row must still show a time, not a bare label.
      const days = [{
        date: TOMORROW,
        eventSummaries: [
          {
            targetType: 'SUNRISE',
            regions: [region('The Yorkshire Dales', [futureSlot('Malham Cove', TOMORROW)])],
            unregioned: [],
          },
          {
            targetType: 'SUNSET',
            regions: [region('Northumberland', [
              { ...futureSlot("St Mary's Lighthouse", TOMORROW, { claudeRating: 5 }),
                solarEventTime: `${TOMORROW}T21:24:00` },
            ])],
            unregioned: [],
          },
        ],
      }];
      const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
      expect(model.breadcrumb.eventLabel).toContain('sunrise');
      expect(model.breadcrumb.eventTime).not.toBe('');
    });

    it('says worth it and quotes the leading local headline', () => {
      const days = [day(TOMORROW, [region('Northumberland', [
        futureSlot("St Mary's Lighthouse", TOMORROW, {
          claudeRating: 5, claudeHeadline: 'Spring tide bares the causeway at first light',
        }),
      ])])];
      const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
      expect(model.breadcrumb.worthIt).toBe(true);
      expect(model.breadcrumb.reason).toBe('Spring tide bares the causeway at first light');
    });

    it('labels tonight\'s sunset as tonight', () => {
      const days = [{
        date: TODAY,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [region('Tyne and Wear', [slot('Tynemouth Priory', {
            // Far enough ahead of "now" that the event never reads as past mid-run.
            solarEventTime: `${TOMORROW}T21:24:00`,
            displayVerdict: 'STAND_DOWN', verdict: 'STANDDOWN', standdownReason: 'Heavy cloud', claudeRating: 1,
          })])],
          unregioned: [],
        }],
      }];
      const model = buildCloseToHome({ ...BASE_ARGS, briefingDays: days });
      expect(model.breadcrumb.eventLabel).toBe("Tonight's sunset");
      expect(model.breadcrumb.reason).toContain('tonight');
    });
  });
});
