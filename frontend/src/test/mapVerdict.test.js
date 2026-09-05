/**
 * Tests for `utils/mapVerdict.js` — the Map tab's window verdict and its region tally
 * (map-landing-plan.md §3 L1, `docs/design/map-landing/README.md` §1).
 *
 * Covers: the canopy filter the index inherits from the backend's own ranking; the three region
 * label cases including the "everywhere" one that regressed during the design; what counts in the
 * denominator and why an unmeasured region is the safe direction; the tie-break shared with the
 * Plan strip; the absence rules (unrated window, night window, empty scope); and the agreement
 * between this module's whole-catalogue answer and the served window verdict the Plan tab renders
 * (map-landing-plan.md check 8).
 */
import { describe, it, expect } from 'vitest';
import {
  buildEvVerdicts, buildRegionVerdictIndex, regionNamesOf, windowVerdict,
} from '../utils/mapVerdict.js';
import { EVENT_KIND } from '../utils/mapEvents.js';
import { buildWindowCards } from '../utils/windowFirstCards.js';

const DATE = '2026-09-06';
const SUNSET = 'SUNSET';

/**
 * A served region record, in the shape `BriefingRegion` reaches the client in.
 *
 * `slots` matters: `eligibleRegions` reads `slot.canopy` to apply the backend's own ranking
 * filter, so a region with no slots and a region with one sky slot are different inputs.
 */
function region(regionName, meanRating, displayVerdict, overrides = {}) {
  return {
    regionName,
    meanRating,
    displayVerdict,
    bestRating: overrides.bestRating ?? null,
    confidence: overrides.confidence ?? null,
    slots: overrides.slots ?? [{ canopy: false }],
  };
}

/** One day's payload, in the shape `briefing.days` arrives in. */
function days(regions, { date = DATE, targetType = SUNSET } = {}) {
  return [{ date, eventSummaries: [{ targetType, regions }] }];
}

describe('buildRegionVerdictIndex', () => {
  it('keys on date|targetType|regionName and stores the served record itself', () => {
    const lakes = region('The Lake District', 4.2, 'WORTH_IT');
    const index = buildRegionVerdictIndex(days([lakes]));

    expect(index.get('2026-09-06|SUNSET|The Lake District')).toBe(lakes);
    expect(index.size).toBe(1);
  });

  it('drops an all-canopy region from a mixed window, matching the backend ranking', () => {
    // A rated wood on a misty dawn scores 5 on inverted polarity. The backend's own
    // `PlanWindowProjector.rank` drops it before ranking; so must this index, or the map would
    // name a region the Plan tab and the heat field have both already excluded.
    const wood = region('Bluebell Woods', 5, 'WORTH_IT', { slots: [{ canopy: true }] });
    const coast = region('Northumberland', 2.1, 'STAND_DOWN');
    const index = buildRegionVerdictIndex(days([wood, coast]));

    expect(index.has('2026-09-06|SUNSET|Bluebell Woods')).toBe(false);
    expect(index.has('2026-09-06|SUNSET|Northumberland')).toBe(true);
  });

  it('keeps the woods when the WHOLE window is canopy — there is no sky answer to prefer', () => {
    const woodA = region('Bluebell Woods', 5, 'WORTH_IT', { slots: [{ canopy: true }] });
    const woodB = region('Beech Hangers', 3, 'MAYBE', { slots: [{ canopy: true }] });
    const index = buildRegionVerdictIndex(days([woodA, woodB]));

    expect(index.size).toBe(2);
  });

  it('keeps a region with no slots at all — it carries no canopy claim either way', () => {
    const index = buildRegionVerdictIndex(days([region('The Dales', 3.4, 'MAYBE', { slots: [] })]));

    expect(index.has('2026-09-06|SUNSET|The Dales')).toBe(true);
  });

  it('takes the first of two rows sharing a key, matching buildRegionBestIndex', () => {
    const first = region('The Peak', 4.4, 'WORTH_IT');
    const second = region('The Peak', 1.2, 'STAND_DOWN');
    const index = buildRegionVerdictIndex(days([first, second]));

    expect(index.get('2026-09-06|SUNSET|The Peak')).toBe(first);
  });

  it('degrades a null/non-array payload to an empty index', () => {
    expect(buildRegionVerdictIndex(null).size).toBe(0);
    expect(buildRegionVerdictIndex(undefined).size).toBe(0);
  });

  it('skips a day with no date, rather than keying it "undefined|..."', () => {
    // ⚠️ The fixture carries a REAL region. With `regions: []` the guard is unreachable and the
    // assertion passes with the guard deleted — which is how the first cut of this test read.
    const index = buildRegionVerdictIndex([
      { eventSummaries: [{ targetType: SUNSET, regions: [region('The Dales', 3.4, 'MAYBE')] }] },
    ]);

    expect(index.size).toBe(0);
  });

  it('skips a summary with no targetType, rather than keying it "...|undefined|..."', () => {
    const index = buildRegionVerdictIndex([
      { date: DATE, eventSummaries: [{ regions: [region('The Dales', 3.4, 'MAYBE')] }] },
    ]);

    expect(index.size).toBe(0);
  });

  it('skips a region with no name, rather than keying it "...|undefined"', () => {
    const index = buildRegionVerdictIndex(days([
      region(null, 3.4, 'MAYBE'),
      region('The Dales', 3.0, 'MAYBE'),
    ]));

    expect([...index.keys()]).toEqual(['2026-09-06|SUNSET|The Dales']);
  });
});

describe('windowVerdict — the three region label cases', () => {
  const call = (index, regionsInScope) => windowVerdict({
    index, date: DATE, targetType: SUNSET, regionsInScope,
  });

  it('names the region alone when it is the only one in its tier', () => {
    const index = buildRegionVerdictIndex(days([
      region('The Lake District', 4.2, 'WORTH_IT'),
      region('Northumberland', 3.0, 'MAYBE'),
      region('The Dales', 2.0, 'STAND_DOWN'),
    ]));

    const result = call(index, ['The Lake District', 'Northumberland', 'The Dales']);

    expect(result.tier).toBe('WORTH_IT');
    expect(result.regionName).toBe('The Lake District');
    expect(result.sharingCount).toBe(0);
    expect(result.allInScope).toBe(false);
    expect(result.scopedRegionCount).toBe(3);
  });

  it('counts the others when several share the tier but not all do', () => {
    const index = buildRegionVerdictIndex(days([
      region('Northumberland', 4.4, 'WORTH_IT'),
      region('The Lake District', 4.1, 'WORTH_IT'),
      region('The Dales', 3.9, 'WORTH_IT'),
      region('The Peak', 2.0, 'STAND_DOWN'),
    ]));

    const result = call(index, ['Northumberland', 'The Lake District', 'The Dales', 'The Peak']);

    expect(result.regionName).toBe('Northumberland');
    expect(result.sharingCount).toBe(2);
    expect(result.allInScope).toBe(false);
  });

  it('names NO region when every region in scope shares the tier — the case that regressed', () => {
    // Naming the top one here reads "Poor · the Pennines +6": the least-bad region presented as a
    // destination, which is the overclaim of case 1 inverted.
    const index = buildRegionVerdictIndex(days([
      region('The Pennines', 2.4, 'STAND_DOWN'),
      region('The Dales', 2.1, 'STAND_DOWN'),
      region('The Moors', 1.8, 'STAND_DOWN'),
    ]));

    const result = call(index, ['The Pennines', 'The Dales', 'The Moors']);

    expect(result.tier).toBe('STAND_DOWN');
    expect(result.allInScope).toBe(true);
    expect(result.sharingCount).toBe(2);
    // The name is still published — the CALLER suppresses it on `allInScope`, so a surface that
    // wants "everywhere" and one that wants the leader both read one derivation.
    expect(result.regionName).toBe('The Pennines');
  });

  it('never says "everywhere" for a one-region scope — the word would be the region, named', () => {
    const index = buildRegionVerdictIndex(days([region('The Lake District', 2.0, 'STAND_DOWN')]));

    const result = call(index, ['The Lake District']);

    expect(result.scopedRegionCount).toBe(1);
    expect(result.allInScope).toBe(false);
    expect(result.regionName).toBe('The Lake District');
  });
});

describe('windowVerdict — what counts in the denominator', () => {
  const call = (index, regionsInScope) => windowVerdict({
    index, date: DATE, targetType: SUNSET, regionsInScope,
  });

  it('counts an in-scope region this window never mentioned, so it cannot claim "everywhere"', () => {
    // Two measured regions agree; a third is in scope with no row. "Everywhere in your area is
    // Poor" would be a claim about three regions when only two were measured.
    const index = buildRegionVerdictIndex(days([
      region('The Pennines', 2.4, 'STAND_DOWN'),
      region('The Dales', 2.1, 'STAND_DOWN'),
    ]));

    const result = call(index, ['The Pennines', 'The Dales', 'The Borders']);

    expect(result.scopedRegionCount).toBe(3);
    expect(result.sharingCount).toBe(1);
    expect(result.allInScope).toBe(false);
  });

  it('counts an unscored region in the denominator but never lets it win the name', () => {
    const index = buildRegionVerdictIndex(days([
      region('The Lake District', 4.2, 'WORTH_IT'),
      region('The Borders', null, 'AWAITING'),
    ]));

    const result = call(index, ['The Lake District', 'The Borders']);

    expect(result.regionName).toBe('The Lake District');
    expect(result.scopedRegionCount).toBe(2);
    expect(result.sharingCount).toBe(0);
    expect(result.allInScope).toBe(false);
  });

  it('counts a region sharing the tier from triage, with no mean of its own', () => {
    // `displayVerdict` can come from triage when nothing is scored, so a region can genuinely be
    // WORTH_IT with a null mean. It shares the tier and must be counted; it cannot win the name.
    const index = buildRegionVerdictIndex(days([
      region('The Lake District', 4.2, 'WORTH_IT'),
      region('The Borders', null, 'WORTH_IT'),
    ]));

    const result = call(index, ['The Lake District', 'The Borders']);

    expect(result.regionName).toBe('The Lake District');
    expect(result.sharingCount).toBe(1);
    expect(result.allInScope).toBe(true);
  });

  it('deduplicates the scope list, so a repeated name cannot inflate the denominator', () => {
    const index = buildRegionVerdictIndex(days([
      region('The Pennines', 2.4, 'STAND_DOWN'),
      region('The Dales', 2.1, 'STAND_DOWN'),
    ]));

    const result = call(index, ['The Pennines', 'The Dales', 'The Pennines']);

    expect(result.scopedRegionCount).toBe(2);
    expect(result.allInScope).toBe(true);
  });

  it('ignores a region outside scope even when it would have led the window', () => {
    const index = buildRegionVerdictIndex(days([
      region('The Highlands', 5, 'WORTH_IT'),
      region('The Dales', 2.1, 'STAND_DOWN'),
    ]));

    const result = call(index, ['The Dales']);

    expect(result.tier).toBe('STAND_DOWN');
    expect(result.regionName).toBe('The Dales');
  });

  it('does not COUNT an out-of-scope region that shares the tier', () => {
    // ⚠️ The whole reason `regionsInScope` is threaded through three files. Without this the tally
    // could be taken over `index.values()` — every region in the window — and every other test in
    // this file would still pass, because their fixtures make the index and the scope the same set.
    // The pill would then read "Poor · The Dales +2" while counting two regions the reader has
    // scoped away.
    const index = buildRegionVerdictIndex(days([
      region('The Dales', 2.4, 'STAND_DOWN'),
      region('The Moors', 2.2, 'STAND_DOWN'),
      region('The Peak', 2.0, 'STAND_DOWN'),
    ]));

    const result = call(index, ['The Dales']);

    expect(result.sharingCount).toBe(0);
    expect(result.scopedRegionCount).toBe(1);
    expect(result.allInScope).toBe(false);
  });

  it('counts only the in-scope half when the window holds more of the same tier', () => {
    const index = buildRegionVerdictIndex(days([
      region('Cumbria', 4.4, 'WORTH_IT'),
      region('Northumberland', 4.2, 'WORTH_IT'),
      region('The Borders', 4.0, 'WORTH_IT'),
      region('The Highlands', 3.8, 'WORTH_IT'),
    ]));

    const result = call(index, ['Cumbria', 'Northumberland']);

    expect(result.regionName).toBe('Cumbria');
    expect(result.sharingCount).toBe(1);
    expect(result.scopedRegionCount).toBe(2);
    expect(result.allInScope).toBe(true);
  });
});

describe('windowVerdict — ranking and absence', () => {
  const call = (index, regionsInScope) => windowVerdict({
    index, date: DATE, targetType: SUNSET, regionsInScope,
  });

  it('breaks a tie on the region name, the rule the Plan strip already uses', () => {
    const index = buildRegionVerdictIndex(days([
      region('Northumberland', 4.0, 'WORTH_IT'),
      region('Cumbria', 4.0, 'WORTH_IT'),
    ]));

    expect(call(index, ['Northumberland', 'Cumbria']).regionName).toBe('Cumbria');
    // Order of the scope list must not decide it either.
    expect(call(index, ['Cumbria', 'Northumberland']).regionName).toBe('Cumbria');
  });

  it('ranks on the mean, not on the ceiling', () => {
    // A single 5★ in a flat region is a lucky location, not a good night.
    const index = buildRegionVerdictIndex(days([
      region('The Peak', 2.2, 'STAND_DOWN', { bestRating: 5 }),
      region('The Dales', 3.6, 'WORTH_IT', { bestRating: 4 }),
    ]));

    expect(call(index, ['The Peak', 'The Dales']).regionName).toBe('The Dales');
  });

  it('returns null when nothing in scope carries a mean — an unrated window says nothing', () => {
    const index = buildRegionVerdictIndex(days([
      region('The Dales', null, 'AWAITING'),
      region('The Peak', null, 'AWAITING'),
    ]));

    expect(call(index, ['The Dales', 'The Peak'])).toBeNull();
  });

  it('returns null for an empty scope, a missing index, and a window with no rows', () => {
    const index = buildRegionVerdictIndex(days([region('The Dales', 3.4, 'MAYBE')]));

    expect(call(index, [])).toBeNull();
    expect(call(null, ['The Dales'])).toBeNull();
    expect(windowVerdict({ index, date: null, targetType: SUNSET, regionsInScope: ['The Dales'] })).toBeNull();
    expect(windowVerdict({ index, date: DATE, targetType: null, regionsInScope: ['The Dales'] })).toBeNull();
  });

  it('maps a legacy payload\'s triage verdict when displayVerdict is absent', () => {
    // A cached briefing written before `displayVerdict` existed carries only `verdict`
    // (GO/MARGINAL/STANDDOWN). `resolveRegionDisplay` maps it; reading the raw field would have
    // rendered this region as AWAITING, a tier the design has no colour for.
    const legacy = {
      regionName: 'The Dales', meanRating: 4.1, verdict: 'GO', slots: [{ canopy: false }],
    };
    const index = buildRegionVerdictIndex(days([legacy]));

    expect(call(index, ['The Dales']).tier).toBe('WORTH_IT');
  });

  it('counts a legacy-mapped tier as SHARING a served one — one vocabulary, two sources', () => {
    const index = buildRegionVerdictIndex(days([
      region('Cumbria', 4.4, 'WORTH_IT'),
      { regionName: 'The Dales', meanRating: 4.1, verdict: 'GO', slots: [{ canopy: false }] },
    ]));

    const result = call(index, ['Cumbria', 'The Dales']);

    expect(result.sharingCount).toBe(1);
    expect(result.allInScope).toBe(true);
  });

  it('returns null for a night window — astro and aurora carry no per-region rollup', () => {
    const index = buildRegionVerdictIndex(days([region('The Dales', 3.4, 'MAYBE')]));

    expect(windowVerdict({
      index, date: DATE, targetType: 'ASTRO', regionsInScope: ['The Dales'],
    })).toBeNull();
  });

  it('returns null for a date the briefing never carried', () => {
    const index = buildRegionVerdictIndex(days([region('The Dales', 3.4, 'MAYBE')]));

    expect(windowVerdict({
      index, date: '2026-09-30', targetType: SUNSET, regionsInScope: ['The Dales'],
    })).toBeNull();
  });
});

describe('regionNamesOf', () => {
  it('returns distinct names in first-seen order', () => {
    expect(regionNamesOf([
      { regionName: 'The Dales' },
      { regionName: 'The Peak' },
      { regionName: 'The Dales' },
    ])).toEqual(['The Dales', 'The Peak']);
  });

  it('keeps a non-sky location\'s region — it is still a region you can drive to', () => {
    // `buildHeatSpots` withholds a waterfall's SCORES from the field and keeps the spot, so a
    // region reachable only for waterfalls is in scope and the backend ranking can still name it.
    expect(regionNamesOf([{ regionName: 'Waterfall Country', skySubject: false }]))
      .toEqual(['Waterfall Country']);
  });

  it('drops a spot with no region and degrades an absent pool to an empty list', () => {
    expect(regionNamesOf([{ regionName: null }, { regionName: '' }, {}])).toEqual([]);
    expect(regionNamesOf(null)).toEqual([]);
    expect(regionNamesOf(undefined)).toEqual([]);
  });
});

describe('agreement with the Plan tab (map-landing-plan.md check 8)', () => {
  /**
   * ONE fixture drives both surfaces, built so that three plausible mistakes each give a DIFFERENT
   * answer from the right one:
   *
   *  - rank on the ceiling rather than the mean → picks The Peak (best 5, mean 2.2)
   *  - skip the canopy filter                   → picks Bluebell Woods (mean 4.9, all-canopy)
   *  - tie-break on payload order               → picks Northumberland over Cumbria at 4.4 each
   *
   * ⚠️ **What these tests do and do not prove**, stated because the first cut of this block
   * overclaimed and a review caught it. `planCard().verdict` with no origin collapses to
   * `win.verdict` — a verbatim echo of the fixture — so comparing it to the map's tier compares two
   * strings written one screen apart. And both `card.hotRegionName` and the map's `regionName` now
   * reach the SAME `pickTopEligibleRegion`, so a mutation inside that function moves both sides
   * together and the equality cannot fail from it.
   *
   * The teeth are therefore the LITERALS (`'WORTH_IT'`, `'Cumbria'`), which the three mistakes above
   * each break. The equalities are worth keeping for a different reason: they fail the day someone
   * re-forks either derivation, which is exactly what this phase's shared-argmax extraction exists
   * to prevent.
   */
  const slot = (locationName, claudeRating, canopy = false) => ({
    locationName, claudeRating, canopy, locationId: locationName,
  });

  const REGIONS = [
    region('The Peak', 2.2, 'STAND_DOWN', { bestRating: 5, slots: [slot('Kinder Scout', 5)] }),
    region('Bluebell Woods', 4.9, 'WORTH_IT', { slots: [slot('Hallerbos', 5, true)] }),
    region('Northumberland', 4.4, 'WORTH_IT', { slots: [slot('Bamburgh Beach', 4)] }),
    region('Cumbria', 4.4, 'WORTH_IT', { slots: [slot('Derwentwater', 4)] }),
  ];

  function briefingDays() {
    return [{
      date: DATE,
      eventSummaries: [{
        targetType: SUNSET,
        regions: REGIONS,
        window: { verdict: 'WORTH_IT', bestRating: 5, confidence: 'high', badges: [] },
      }],
    }];
  }

  function planCard() {
    const [card] = buildWindowCards(
      [{ date: DATE, targetType: SUNSET }], briefingDays(), DATE, '2026-09-07', new Set(), null,
    );
    return card;
  }

  function mapAnswer(regionsInScope) {
    return windowVerdict({
      index: buildRegionVerdictIndex(briefingDays()), date: DATE, targetType: SUNSET, regionsInScope,
    });
  }

  const ALL = REGIONS.map((r) => r.regionName);

  it('lands on the tier the Plan tab renders, at whole-catalogue scope', () => {
    expect(mapAnswer(ALL).tier).toBe('WORTH_IT');
    expect(mapAnswer(ALL).tier).toBe(planCard().verdict);
  });

  it('names the region the Plan strip brightens — the one all three mistakes would miss', () => {
    expect(mapAnswer(ALL).regionName).toBe('Cumbria');
    expect(planCard().hotRegionName).toBe('Cumbria');
    expect(mapAnswer(ALL).regionName).toBe(planCard().hotRegionName);
  });

  it('diverges from the Plan tab exactly where scope narrows, which is the design\'s own rule', () => {
    expect(mapAnswer(ALL).tier).toBe('WORTH_IT');
    expect(mapAnswer(['The Peak']).tier).toBe('STAND_DOWN');
    expect(mapAnswer(['The Peak']).regionName).toBe('The Peak');
  });

  it('excludes the wood from the tally as well as from the name', () => {
    const result = mapAnswer(['Bluebell Woods', 'Northumberland', 'Cumbria']);

    expect(result.regionName).toBe('Cumbria');
    expect(result.sharingCount).toBe(1);
    // The wood still counts in the DENOMINATOR — a region in your area with no sky answer — so
    // "everywhere" is correctly withheld.
    expect(result.scopedRegionCount).toBe(3);
    expect(result.allInScope).toBe(false);
  });
});

describe('buildEvVerdicts', () => {
  const evRow = (id, kind, date, eventType) => ({ id, kind, date, eventType });
  const INDEX = () => buildRegionVerdictIndex(days([
    region('Cumbria', 4.4, 'WORTH_IT'),
    region('The Dales', 2.0, 'STAND_DOWN'),
  ]));
  const SCOPE = ['Cumbria', 'The Dales'];

  it('keys on the row id, so a caller can look up a stepper neighbour', () => {
    const events = [evRow('solar:2026-09-06:SUNSET', 'solar', DATE, SUNSET)];

    const out = buildEvVerdicts({ events, index: INDEX(), regionsInScope: SCOPE });

    expect([...out.keys()]).toEqual(['solar:2026-09-06:SUNSET']);
    expect(out.get('solar:2026-09-06:SUNSET').tier).toBe('WORTH_IT');
  });

  it('skips a night row by KIND, even when the index would answer for its date', () => {
    // The rule is "astro and aurora have no per-region rollup", not "the index happens to hold no
    // night keys" — so it is keyed off `kind`, and this row shares its date with a solar one.
    const events = [
      evRow('solar:2026-09-06:SUNSET', 'solar', DATE, SUNSET),
      evRow('astro:2026-09-06:ASTRO', 'astro', DATE, SUNSET),
      evRow('aur:2026-09-06:AURORA', 'aur', DATE, SUNSET),
    ];

    const out = buildEvVerdicts({ events, index: INDEX(), regionsInScope: SCOPE });

    expect([...out.keys()]).toEqual(['solar:2026-09-06:SUNSET']);
  });

  it('omits a solar row with no verdict rather than storing null', () => {
    const events = [
      evRow('solar:2026-09-06:SUNSET', 'solar', DATE, SUNSET),
      evRow('solar:2026-09-30:SUNSET', 'solar', '2026-09-30', SUNSET),
    ];

    const out = buildEvVerdicts({ events, index: INDEX(), regionsInScope: SCOPE });

    expect(out.has('solar:2026-09-30:SUNSET')).toBe(false);
    expect(out.size).toBe(1);
  });

  it('draws nothing on the frozen Plan-tab overlay', () => {
    const events = [evRow('solar:2026-09-06:SUNSET', 'solar', DATE, SUNSET)];

    expect(buildEvVerdicts({
      events, index: INDEX(), regionsInScope: SCOPE, overlayMode: true,
    }).size).toBe(0);
  });

  it('degrades to an empty map with no index, no events and an empty scope', () => {
    const events = [evRow('solar:2026-09-06:SUNSET', 'solar', DATE, SUNSET)];

    expect(buildEvVerdicts({ events, index: null, regionsInScope: SCOPE }).size).toBe(0);
    expect(buildEvVerdicts({ events: null, index: INDEX(), regionsInScope: SCOPE }).size).toBe(0);
    expect(buildEvVerdicts({ events, index: INDEX(), regionsInScope: [] }).size).toBe(0);
  });

  it('uses the same "solar" literal `mapEvents` does', () => {
    // The module mirrors the literal rather than importing EVENT_KIND; this is what stops the two
    // drifting apart silently.
    const events = [evRow('x', EVENT_KIND.SOLAR, DATE, SUNSET)];

    expect(buildEvVerdicts({ events, index: INDEX(), regionsInScope: SCOPE }).size).toBe(1);
  });
});
