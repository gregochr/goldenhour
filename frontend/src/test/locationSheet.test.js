import { describe, it, expect } from 'vitest';
import {
  buildLocationSheet, buildScoreIndex, buildSlotIndex, lookupForWindow,
} from '../utils/locationSheet.js';

/**
 * The four-day location sheet's derivation (plan D10, P8).
 *
 * <p><b>What breaks if these fail:</b> a sheet that prints a rating from one evaluation over prose
 * from another, or another location's rating entirely; a departure computed from somebody else's
 * sunrise; a "best here" claimed over a comparison never made; an "outside your plan" badge on a
 * place inside it; a lead line counting our own database rows; or a claim that nothing is scored
 * built out of a request that never came back.
 *
 * <p>Fixtures are explicit UTC instants in the bare shape the backend serialises a
 * {@code LocalDateTime} in, and every date is a real BST one — the suite runs on UTC, so a GMT
 * fixture could not tell the UK clock from the runner's.
 */

const TODAY = '2026-08-14';

/** The six strip descriptors, as `buildHeatStripCards` shapes them. */
const WINDOWS = [
  { key: '2026-08-14:SUNSET', date: '2026-08-14', targetType: 'SUNSET', dow: 'Fri', sunrise: false, label: 'Tonight Sunset', time: '20:37', verdictLabel: 'Worth it', confidence: 'high', away: false },
  { key: '2026-08-15:SUNRISE', date: '2026-08-15', targetType: 'SUNRISE', dow: 'Sat', sunrise: true, label: 'Tomorrow Sunrise', time: '05:38', verdictLabel: 'Maybe', confidence: 'high', away: false },
  { key: '2026-08-15:SUNSET', date: '2026-08-15', targetType: 'SUNSET', dow: 'Sat', sunrise: false, label: 'Tomorrow Sunset', time: '20:35', verdictLabel: 'Poor', confidence: 'medium', away: false },
  { key: '2026-08-16:SUNRISE', date: '2026-08-16', targetType: 'SUNRISE', dow: 'Sun', sunrise: true, label: 'Sun Sunrise', time: '05:40', verdictLabel: 'Maybe', confidence: 'medium', away: false },
  { key: '2026-08-16:SUNSET', date: '2026-08-16', targetType: 'SUNSET', dow: 'Sun', sunrise: false, label: 'Sun Sunset', time: '20:33', verdictLabel: 'Awaiting', confidence: 'low', away: false },
  { key: '2026-08-17:SUNRISE', date: '2026-08-17', targetType: 'SUNRISE', dow: 'Mon', sunrise: true, label: 'Mon Sunrise', time: '05:42', verdictLabel: 'Not forecast', confidence: null, away: true },
];

const SPOT = { id: 7, name: 'Bamburgh', regionName: 'Northumberland' };

/** Raw `LocationEvaluationView` rows, which is what the sheet's ratings now come from. */
const rows = (list) => list.map((r) => ({ locationId: 7, locationName: 'Bamburgh', ...r }));

const SCORES = buildScoreIndex(rows([
  { date: '2026-08-14', targetType: 'SUNSET', rating: 3, summary: 'High cloud thins after eight.' },
  { date: '2026-08-15', targetType: 'SUNRISE', rating: 5, summary: 'A clear eastern horizon under mid cloud.' },
  { date: '2026-08-15', targetType: 'SUNSET', rating: 2, summary: 'Blanket low cloud to the west.' },
  { date: '2026-08-16', targetType: 'SUNRISE', rating: 4, summary: 'Broken cloud, decent odds.' },
]));

/**
 * A briefing payload carrying Bamburgh's own event times and its region's confidence.
 *
 * <p>The region's confidence is deliberately DIFFERENT from the matching `WINDOWS` entry's
 * `card.confidence`: the card's is the top region's, and the whole point of the sheet's fix is that
 * it must not be read here.
 */
const DAYS = [
  {
    date: '2026-08-14',
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName: 'Northumberland',
        confidence: 'low',
        slots: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: '2026-08-14T19:41:00' }],
      }],
    }],
  },
  {
    date: '2026-08-15',
    eventSummaries: [
      {
        targetType: 'SUNRISE',
        regions: [{
          regionName: 'Northumberland',
          confidence: 'high',
          slots: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: '2026-08-15T04:38:00' }],
        }],
      },
      {
        targetType: 'SUNSET',
        // Unregioned, which `buildWindowSpots` drops and this index deliberately keeps.
        unregioned: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: '2026-08-15T19:39:00' }],
      },
    ],
  },
];

const SLOTS = buildSlotIndex(DAYS);

const build = (overrides = {}) => buildLocationSheet(SPOT, WINDOWS, {
  scoreIndex: SCORES,
  slotIndex: SLOTS,
  scoresKnown: true,
  reachById: new Map([[7, { driveMinutes: 66, distanceMiles: null }]]),
  scopeRegionNames: ['Northumberland', 'North Pennines'],
  todayStr: TODAY,
  ...overrides,
});

describe('buildSlotIndex', () => {
  it('carries each slot\'s own event time and its REGION\'s confidence', () => {
    expect(lookupForWindow(SLOTS, 7, 'Bamburgh', '2026-08-14', 'SUNSET'))
      .toEqual({ eventTime: '2026-08-14T19:41:00', confidence: 'low' });
    // Unregioned: a region's confidence is a fact about a region, so there is none to carry.
    expect(lookupForWindow(SLOTS, 7, 'Bamburgh', '2026-08-15', 'SUNSET'))
      .toEqual({ eventTime: '2026-08-15T19:39:00', confidence: null });
    // Name-keyed fallback for a payload with no ids on its slots.
    expect(lookupForWindow(SLOTS, null, 'Bamburgh', '2026-08-15', 'SUNRISE').eventTime)
      .toBe('2026-08-15T04:38:00');
  });

  it('lets an id hit END the lookup rather than falling through to the name', () => {
    // The rule `buildHeatSpots` states: id-first only means something if a hit is final. Here the
    // name key points at a DIFFERENT time, so a fall-through would be visible.
    const idx = buildSlotIndex([{
      date: '2026-08-14',
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{ slots: [{ locationId: 7, locationName: 'Elsewhere', solarEventTime: '2026-08-14T19:41:00' }] }],
        unregioned: [{ locationName: 'Bamburgh', solarEventTime: '2026-08-14T18:00:00' }],
      }],
    }]);
    expect(lookupForWindow(idx, 7, 'Bamburgh', '2026-08-14', 'SUNSET').eventTime)
      .toBe('2026-08-14T19:41:00');
  });

  it('keeps the FIRST slot on a repeated key, matching buildBriefingScoreIndex', () => {
    // A stated invariant with a real consequence: two indexes over one payload must not resolve the
    // same slot two ways. Last-wins here and the sheet's departure would disagree with the field's.
    const idx = buildSlotIndex([{
      date: '2026-08-14',
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          confidence: 'high',
          slots: [
            { locationId: 7, locationName: 'Bamburgh', solarEventTime: '2026-08-14T19:41:00' },
            { locationId: 7, locationName: 'Bamburgh', solarEventTime: '2026-08-14T18:00:00' },
          ],
        }],
      }],
    }]);
    expect(lookupForWindow(idx, 7, 'Bamburgh', '2026-08-14', 'SUNSET').eventTime)
      .toBe('2026-08-14T19:41:00');
  });

  it('skips a day with no date and a summary with no event type, rather than keying on undefined', () => {
    expect(buildSlotIndex(null).byId.size).toBe(0);
    expect(buildSlotIndex([{ eventSummaries: [{ targetType: 'SUNSET' }] }]).byName.size).toBe(0);
    // The summary guard specifically: a real date, a summary with no targetType. Without it the key
    // would read `2026-08-14|undefined` and collide with any equally malformed lookup.
    const noType = buildSlotIndex([{
      date: '2026-08-14',
      eventSummaries: [{ regions: [{ slots: [{ locationId: 7, solarEventTime: 'x' }] }] }],
    }]);
    expect(noType.byId.size).toBe(0);
  });

  it('answers null for a window, or an index, it has nothing for', () => {
    expect(lookupForWindow(SLOTS, 7, 'Bamburgh', '2026-08-17', 'SUNRISE')).toBeNull();
    expect(lookupForWindow(null, 7, 'Bamburgh', '2026-08-14', 'SUNSET')).toBeNull();
  });
});

describe('buildScoreIndex', () => {
  it('⚠️ joins id-FIRST, so a renamed location still rates', () => {
    // The defect this index replaced: the provider's `scoreIndex` is name-keyed, so a location
    // renamed since the last evaluation run timed correctly (id-first) and rated as unscored, under
    // a heat field that — being id-first too — still painted its star.
    const idx = buildScoreIndex([
      { locationId: 7, locationName: 'Bamburgh Castle Beach', date: '2026-08-14', targetType: 'SUNSET', rating: 4, summary: 'x' },
    ]);
    expect(lookupForWindow(idx, 7, 'Bamburgh', '2026-08-14', 'SUNSET').rating).toBe(4);
  });

  it('⚠️ does not let a shared display name hand over another place\'s rating', () => {
    // Two roster entries with one name: the id must decide, or the sheet prints another place's
    // stars AND its prose.
    const idx = buildScoreIndex([
      { locationId: 9, locationName: 'Bamburgh', date: '2026-08-14', targetType: 'SUNSET', rating: 1, summary: 'Elsewhere.' },
      { locationId: 7, locationName: 'Bamburgh', date: '2026-08-14', targetType: 'SUNSET', rating: 5, summary: 'Here.' },
    ]);
    expect(lookupForWindow(idx, 7, 'Bamburgh', '2026-08-14', 'SUNSET'))
      .toEqual({ rating: 5, summary: 'Here.' });
  });

  it('discards a rating outside 1–5 rather than displaying it', () => {
    // The projector's own bounds. A 0 or a 6 would otherwise reach `spotBadgeStyle`, which clamps,
    // and paint a badge for a rating the pipeline never produced.
    for (const bad of [0, 6, 2.5, null, undefined, '4']) {
      const idx = buildScoreIndex(rows([{ date: '2026-08-14', targetType: 'SUNSET', rating: bad }]));
      expect(lookupForWindow(idx, 7, 'Bamburgh', '2026-08-14', 'SUNSET').rating).toBeNull();
    }
    for (const good of [1, 5]) {
      const idx = buildScoreIndex(rows([{ date: '2026-08-14', targetType: 'SUNSET', rating: good }]));
      expect(lookupForWindow(idx, 7, 'Bamburgh', '2026-08-14', 'SUNSET').rating).toBe(good);
    }
  });

  it('treats a blank summary as no summary', () => {
    // A whitespace-only string would otherwise render an empty serif paragraph under an expanded
    // row — the gate `resolveSpotPeek` applies for the same reason.
    const idx = buildScoreIndex(rows([{ date: '2026-08-14', targetType: 'SUNSET', rating: 3, summary: '   ' }]));
    expect(lookupForWindow(idx, 7, 'Bamburgh', '2026-08-14', 'SUNSET').summary).toBeNull();
  });

  it('skips a row that names no window, and survives a null payload', () => {
    expect(buildScoreIndex(null).byId.size).toBe(0);
    expect(buildScoreIndex([{ locationId: 7, rating: 4 }]).byId.size).toBe(0);
    expect(buildScoreIndex([{ locationId: 7, date: '2026-08-14', rating: 4 }]).byId.size).toBe(0);
  });
});

describe('buildLocationSheet rows', () => {
  it('renders one row per rendered window, in the strip\'s own order', () => {
    const sheet = build();
    expect(sheet.rows.map((r) => r.key)).toEqual(WINDOWS.map((w) => w.key));
    // The away day keeps its slot, exactly as it does on the strip — a missing row would silently
    // renumber the week.
    expect(sheet.rows[5].away).toBe(true);
  });

  it('takes the rating and the "why" from ONE score row', () => {
    const sheet = build();
    expect(sheet.rows[1].rating).toBe(5);
    expect(sheet.rows[1].summary).toBe('A clear eastern horizon under mid cloud.');
    // A window the score payload has nothing for is unrated AND unexplained — never a rating with
    // borrowed prose or the reverse.
    expect(sheet.rows[4].rating).toBeNull();
    expect(sheet.rows[4].summary).toBeNull();
  });

  it('⚠️ prints THIS location\'s own event time, not the window header\'s', () => {
    // Bamburgh's own sunset is 19:41 UTC = 20:41 BST; the window header says 20:37, which is the
    // roster's earliest (or, on the fallback path, an order-dependent first slot). The first cut
    // printed the header's time one line above a departure derived from the location's, and the two
    // disagreed by four minutes with nothing on screen to explain the gap.
    expect(WINDOWS[0].time).toBe('20:37');
    expect(build().rows[0].time).toBe('20:41');
    // The header's time survives ONLY as the fallback for a window with no slot.
    expect(build().rows[3].time).toBe('05:40');
  });

  it('⚠️ takes the confidence from the LOCATION\'S region, not the window\'s top region', () => {
    // Every `WINDOWS` entry names the window's TOP region's confidence; Northumberland's own is
    // `low` on the Friday and `high` on the Saturday. Reading the card's would qualify a
    // Northumberland rating with another region's certainty — silently, in both directions.
    expect(WINDOWS[0].confidence).toBe('high');
    expect(build().rows[0].confidence).toBe('low');
    expect(build().rows[1].confidence).toBe('high');
  });

  it('carries no confidence on a row with no rating', () => {
    // The channel qualifies a forecast; an away day and an unscored window have none to qualify, so
    // a "provisional" mark beside "Not forecast" would be marking an absence.
    expect(build().rows[4].confidence).toBeNull();
    expect(build().rows[5].confidence).toBeNull();
  });

  it('looks nothing up at all for an away window', () => {
    // A travel day's slots are collected and never evaluated. Even if a stale row existed for one,
    // the sheet must not print a forecast for a night nobody forecast.
    const sheet = build({
      scoreIndex: buildScoreIndex(rows([
        { date: '2026-08-17', targetType: 'SUNRISE', rating: 5, summary: 'Stale.' },
      ])),
    });
    expect(sheet.rows[5].rating).toBeNull();
    expect(sheet.rows[5].summary).toBeNull();
    expect(sheet.rows[5].leave).toBeNull();
    expect(sheet.rows[5].stateLabel).toBe('Not forecast');
  });

  it('folds the strip\'s own label for the map action', () => {
    expect(build().rows[0].label).toBe('Tonight Sunset');
    // A descriptor with no label falls back to the row's own two words rather than rendering blank.
    const bare = buildLocationSheet(SPOT, [{ ...WINDOWS[0], label: undefined }], {});
    expect(bare.rows[0].label).toBe('Fri sunset');
  });

  it('skips a null element rather than throwing at render', () => {
    // Not reachable from `buildHeatStripCards`, and guarded anyway — the two index builders above
    // guard every level, and an unguarded map inside a lazy `Suspense` has no error boundary.
    expect(buildLocationSheet(SPOT, [null, WINDOWS[0], undefined], {}).rows).toHaveLength(1);
  });

  it('renders the day box from the UK calendar', () => {
    // Both halves keyed at noon UTC with the zone named. `locationSheetAbroad.test.js` is where the
    // zone half is actually separated; this is the local value guard.
    expect(build().rows[0].dayNum).toBe('14');
    expect(build().rows[5].dayNum).toBe('17');
  });
});

describe('buildLocationSheet departures', () => {
  it('computes the departure from THIS location\'s own event time', () => {
    // 20:41 BST − 1h6 − 20 min = 19:15. Deriving from the window header would answer 19:11, which
    // is advice to one person from somebody else's sun.
    expect(build().rows[0].leave.time).toBe('19:15');
  });

  it('names no day for an ordinary drive', () => {
    expect(build().rows[1].leave.time).toBe('04:12');
    expect(build().rows[1].leave.dayWord).toBeNull();
  });

  it('⚠️ names the previous day for a drive search can now reach', () => {
    // A 5h30 drive — reachable from a region base to the far side of the roster, which is what
    // search makes possible and what the spot card never sees. 05:38 BST − 5h30 − 20 min = 23:48 on
    // the FRIDAY, for a Saturday sunrise.
    const far = build({ reachById: new Map([[7, { driveMinutes: 330 }]]) });
    expect(far.rows[1].leave.time).toBe('23:48');
    expect(far.rows[1].leave.dayWord).toBe('Fri');
  });

  it('gives no departure at all when the drive or the event time is unknown', () => {
    // Two absences meaning two different things, both silence. The unmeasured drive is the normal
    // first-run state; the missing slot is a window the briefing carries no slot for.
    expect(build({ reachById: new Map() }).rows[0].leave).toBeNull();
    expect(build({ reachById: null }).rows[0].leave).toBeNull();
    // Windows 3 and 4 have scores but no slot in `DAYS`.
    expect(build().rows[3].leave).toBeNull();
  });
});

describe('buildLocationSheet lead, best, handoff and scope', () => {
  it('counts strong windows and states NO denominator', () => {
    // ⚠️ "N of the scored windows" was built and removed: §6's sweep bans counts of our own data
    // ("a fact about the database, not about tonight"), and "3 scored windows" is a count of
    // evaluation rows. What is left is a count of the sky. Four scored (3, 5, 2, 4), two at 4★+,
    // across four distinct dates — the away day included, because the span is what is on screen.
    expect(build().lead).toBe('The next 4 days here · 2 windows at 4★+');
  });

  it('says so when nothing reaches the bar', () => {
    // The only "don't bother" signal on the sheet. Reachable and worth having.
    const poor = build({
      scoreIndex: buildScoreIndex(rows([{ date: '2026-08-14', targetType: 'SUNSET', rating: 2 }])),
    });
    expect(poor.lead).toBe('The next 4 days here · none at 4★+');
  });

  it('⚠️ says NOTHING while the ratings are unknown', () => {
    // An unfetched or failed request is not evidence that nothing was rated — `scoresLoaded`'s own
    // rule, stated at its declaration. Without this the sheet reports our own network failure as a
    // complete, confident picture of an empty forecast.
    expect(build({ scoresKnown: false }).lead).toBeNull();
    expect(build({ scoresKnown: false }).rows[0].scoresKnown).toBe(false);
    expect(build().rows[0].scoresKnown).toBe(true);
  });

  it('singularises a lone window and a lone day', () => {
    const sheet = buildLocationSheet(SPOT, [WINDOWS[1]], {
      scoreIndex: SCORES, slotIndex: SLOTS, scoresKnown: true, todayStr: TODAY,
    });
    expect(sheet.lead).toBe('The next 1 day here · 1 window at 4★+');
  });

  it('marks the best window, earliest on a tie', () => {
    expect(build().bestKey).toBe('2026-08-15:SUNRISE');
    const tied = build({
      scoreIndex: buildScoreIndex(rows([
        { date: '2026-08-14', targetType: 'SUNSET', rating: 4 },
        { date: '2026-08-15', targetType: 'SUNRISE', rating: 4 },
      ])),
    });
    expect(tied.bestKey).toBe('2026-08-14:SUNSET');
  });

  it('claims no best when only one window was rated', () => {
    // A max over one is not a comparison — the rule the tide runs state as "a one-day run claims no
    // peak". The badge would otherwise assert a ranking that never happened.
    const one = build({
      scoreIndex: buildScoreIndex(rows([{ date: '2026-08-14', targetType: 'SUNSET', rating: 3 }])),
    });
    expect(one.bestKey).toBeNull();
    const two = build({
      scoreIndex: buildScoreIndex(rows([
        { date: '2026-08-14', targetType: 'SUNSET', rating: 3 },
        { date: '2026-08-15', targetType: 'SUNRISE', rating: 1 },
      ])),
    });
    expect(two.bestKey).toBe('2026-08-14:SUNSET');
  });

  it('hands the map the best-rated window', () => {
    expect(build().handoffKey).toBe('2026-08-15:SUNRISE');
  });

  it('⚠️ never hands the map an away window while a forecast one exists', () => {
    // A travel day's slots are collected and never evaluated, so opening the map there lands on a
    // date the pipeline skipped. The first window is the away one here and nothing is rated — the
    // exact fixture that separates the filter from its absence.
    const awayFirst = buildLocationSheet(SPOT, [
      { ...WINDOWS[0], away: true }, WINDOWS[1], WINDOWS[2],
    ], { slotIndex: SLOTS, scoresKnown: true, todayStr: TODAY });
    expect(awayFirst.handoffKey).toBe('2026-08-15:SUNRISE');
  });

  it('falls back to the first row when EVERY window is away', () => {
    // The map must still be offered — a footer that vanished exactly when the rest of the card is
    // emptiest would be the worst moment to withhold it.
    const allAway = buildLocationSheet(SPOT, WINDOWS.map((w) => ({ ...w, away: true })), {
      scoreIndex: SCORES, slotIndex: SLOTS, scoresKnown: true, todayStr: TODAY,
    });
    expect(allAway.handoffKey).toBe('2026-08-14:SUNSET');
  });

  it('falls back to the first forecast window when nothing is rated', () => {
    expect(build({ scoreIndex: null }).handoffKey).toBe('2026-08-14:SUNSET');
  });

  it('marks a place outside the scope, and NAMES which scope', () => {
    expect(build().outsideScope).toBe(false);
    const away = build({
      scopeRegionNames: ['Lake District'],
      origin: { id: 7, name: 'Lake District', baseName: 'Keswick' },
    });
    expect(away.outsideScope).toBe(true);
    // ⚠️ Never a bare "outside your plan". The scope means two things and only one is about
    // distance, so a Dales spot 45 min from a Keswick base wore the badge over "45 min from
    // Keswick" and read as a broken filter.
    expect(away.outsideLabel).toBe('outside Lake District');
    expect(build({ scopeRegionNames: ['Lake District'] }).outsideLabel).toBe('outside your 3h area');
  });

  it('⚠️ marks nothing when the scope is unknown OR empty', () => {
    // The badge claims a place is OUT of the plan; an unmeasured planning area is no evidence for
    // it — the direction `areaRegions` resolves an unmeasured region in. An EMPTY array is the same
    // claim: at home `scopeRegions` folds to `areaRegions`, which is empty whenever the catalogue
    // is, a state the sheet can be open across.
    expect(build({ scopeRegionNames: null }).outsideScope).toBe(false);
    expect(build({ scopeRegionNames: [] }).outsideScope).toBe(false);
    // A spot with no region cannot be outside a set of region names either.
    expect(buildLocationSheet({ id: 7, name: 'Bamburgh' }, WINDOWS, { scopeRegionNames: ['Lake District'] })
      .outsideScope).toBe(false);
  });

  it('resolves each rated row\'s confidence through the shared channel, at every horizon step', () => {
    // A region carrying no confidence at all → inferred from the horizon and CAPPED at medium,
    // which is `resolveConfidence`'s own rule: an inference never earns the top tier.
    const unconfident = buildSlotIndex([{
      date: '2026-08-14',
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{ slots: [{ locationId: 7, solarEventTime: '2026-08-14T19:41:00' }] }],
      }],
    }]);
    const at = (date) => buildLocationSheet(SPOT, [{ ...WINDOWS[0], date, key: `${date}:SUNSET` }], {
      scoreIndex: buildScoreIndex(rows([{ date, targetType: 'SUNSET', rating: 3 }])),
      slotIndex: unconfident, scoresKnown: true, todayStr: TODAY,
    }).rows[0].confidence;
    expect(at('2026-08-14')).toBe('medium'); // T+0, inferred and capped
    expect(at('2026-08-17')).toBe('medium'); // T+3, the last medium step
    expect(at('2026-08-18')).toBe('low'); // T+4, below the cap and therefore kept
  });

  it('survives an empty catalogue and an absent spot without throwing', () => {
    const empty = buildLocationSheet(null, null, {});
    expect(empty.rows).toEqual([]);
    expect(empty.lead).toBeNull();
    expect(empty.bestKey).toBeNull();
    expect(empty.handoffKey).toBeNull();
    expect(empty.name).toBe('');
    expect(empty.driveMinutes).toBeNull();
  });
});
