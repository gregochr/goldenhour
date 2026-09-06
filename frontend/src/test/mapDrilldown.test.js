/**
 * `utils/mapDrilldown.js` — the window panel's rows and its four-way note
 * (`docs/engineering/map-landing-plan.md` §3 L5, `docs/design/map-landing/README.md` §5).
 *
 * <p>Everything L5 decides is here: which regions appear, in what order, what each row counts, and
 * which of the four notes prints. The component renders this and decides nothing — the same seam
 * L1, L3 and L4 were each corrected into.
 */
import { describe, it, expect } from 'vitest';
import { buildPanelRegionRows, windowPanelNote, PANEL_STAR_FLOOR } from '../utils/mapDrilldown.js';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';

const DATE = '2026-01-15';
/** The real index, built by the real builder — so the canopy filter under test is the shipped one. */
const indexFor = (summary) => buildRegionVerdictIndex([{ date: DATE, eventSummaries: [summary] }]);

const region = (name, mean, best, displayVerdict, extra = {}) => ({
  regionName: name,
  meanRating: mean,
  bestRating: best,
  displayVerdict,
  slots: [{ canopy: false }],
  ...extra,
});

/**
 * ⚠️ **The means and the ceilings are deliberately ANTI-correlated**, and an earlier fixture had
 * them in lockstep (4.2/5, 3.9/4, 2.1/3) under a comment claiming they differed. A review lens
 * measured the consequence: swapping `byMeanThenName` for a ceiling sort left all 153 tests green,
 * because the two orders were the same list. `North East` now has the highest CEILING and the
 * middle mean, so mean-order and ceiling-order genuinely disagree — which is the whole claim the
 * design makes ("a single 5★ in a flat region is a lucky location, not a good night").
 */
const SUMMARY = {
  targetType: 'SUNSET',
  regions: [
    region('The Lakes', 4.2, 4, 'WORTH_IT'),
    region('North East', 3.9, 5, 'WORTH_IT'),
    region('The Borders', 2.1, 3, 'STAND_DOWN'),
  ],
};

const SPOTS = [
  { id: 1, regionName: 'The Lakes' }, { id: 2, regionName: 'The Lakes' },
  { id: 3, regionName: 'North East' }, { id: 4, regionName: 'The Borders' },
];

const POINTS = [
  { id: 1, rid: 'The Lakes', r: [5] },
  { id: 2, rid: 'The Lakes', r: [3] },
  { id: 3, rid: 'North East', r: [4] },
  { id: 4, rid: 'The Borders', r: [2] },
];

const DRIVE = new Map([
  [1, { driveMinutes: 95 }], [2, { driveMinutes: 140 }], [3, { driveMinutes: 25 }],
]);

function build({ summary = SUMMARY, ...over } = {}) {
  return buildPanelRegionRows({
    index: indexFor(summary),
    date: DATE,
    targetType: summary.targetType,
    regionsInScope: ['The Lakes', 'North East', 'The Borders'],
    points: POINTS,
    driveMap: DRIVE,
    spots: SPOTS,
    ...over,
  });
}

describe('buildPanelRegionRows', () => {
  it('ranks by the served MEAN, not by the ceiling', () => {
    // ⚠️ The fixture is built so the two orders differ: The Borders has a ceiling of 3 and the
    // worst mean, North East a ceiling of 4 and the second mean. Ranking on `bestRating` would
    // still put The Lakes first, so only a middle row can catch it.
    const rows = build();

    expect(rows.map((r) => r.name)).toEqual(['The Lakes', 'North East', 'The Borders']);
    // ⚠️ The ceilings are NOT in that order — this is what makes the assertion above discriminate.
    expect(rows.map((r) => r.bestRating)).toEqual([4, 5, 3]);
    expect(rows.map((r) => r.meanRating)).toEqual([4.2, 3.9, 2.1]);
  });

  it('sorts a region with no mean LAST, not as a zero', () => {
    // ⚠️ A region at mean ZERO sits in the fixture too, and it is what makes the claim testable:
    // without it every competing mean is > 0, so a null read as zero still sorts last and
    // `?? -Infinity → ?? 0` survives (measured).
    const summary = {
      targetType: 'SUNSET',
      regions: [
        // ⚠️ The null-mean region's NAME sorts FIRST on purpose. With `?? 0` the two tie at zero and
        // the name tiebreak puts this one above — which is the only arrangement that tells the two
        // implementations apart. A first fixture named it "Unscored" (after "Bottomed out"), so the
        // tiebreak reproduced the `-Infinity` order and the mutation survived (measured).
        region('Aardvark unscored', null, null, 'AWAITING'),
        region('Bottomed out', 0, 0, 'STAND_DOWN'),
        ...SUMMARY.regions,
      ],
    };

    const rows = build({
      summary,
      regionsInScope: ['Aardvark unscored', 'Bottomed out', 'The Lakes', 'North East', 'The Borders'],
    });

    expect(rows.map((r) => r.name)).toEqual([
      'The Lakes', 'North East', 'The Borders', 'Bottomed out', 'Aardvark unscored',
    ]);
  });

  it('breaks a mean tie on the name, so the order is total', () => {
    const summary = {
      targetType: 'SUNSET',
      regions: [region('Zeta', 3, 4, 'MAYBE'), region('Alpha', 3, 5, 'MAYBE')],
    };

    const rows = build({ summary, regionsInScope: ['Zeta', 'Alpha'], points: [], spots: [] });

    expect(rows.map((r) => r.name)).toEqual(['Alpha', 'Zeta']);
  });

  it('counts rated locations and those at 4 stars or better', () => {
    const rows = build();

    // ⚠️ The denominator counts PLACES in scope, not rows we hold a rating for: counting points on
    // both sides made the line literally "N of M scored", which the plan bans by name. The Lakes has
    // two locations and one of them is 4★+; North East one location, 4★; The Borders one at 2★.
    expect(PANEL_STAR_FLOOR).toBe(4);
    expect(rows.find((r) => r.name === 'The Lakes')).toMatchObject({ placeCount: 2, atFourPlus: 1 });
    expect(rows.find((r) => r.name === 'North East')).toMatchObject({ placeCount: 1, atFourPlus: 1 });
    expect(rows.find((r) => r.name === 'The Borders')).toMatchObject({ placeCount: 1, atFourPlus: 0 });
  });

  it('names each region\'s NEAREST measured drive, and null where none is measured', () => {
    const rows = build();

    // The Lakes has 95 and 140; the nearest is the one that matters.
    expect(rows.find((r) => r.name === 'The Lakes')).toMatchObject({ driveMinutes: 95, driveLabel: '1h 35min' });
    expect(rows.find((r) => r.name === 'North East').driveMinutes).toBe(25);
    // No entry in the drive map at all — unknown, never zero and never a stale figure.
    expect(rows.find((r) => r.name === 'The Borders')).toMatchObject({ driveMinutes: null, driveLabel: null });
  });

  /**
   * ⚠️ **A place this window could never rate is not in the denominator, and two review lenses
   * found independently that it was.** `buildHeatSpots` KEEPS a non-sky location — a wildlife hide,
   * a waterfall — as a spot and withholds only its scores (`skySubject` records why), so it sat in
   * M and could never, by construction, reach N. A region with two sky locations (one at 4★+) and
   * two hides read `1 of 4`, understating every wood-bearing region uniformly. That is the mirror
   * image of the "N of M scored" phrasing the plan bans: not a denominator of rows-we-scored, but
   * one holding places the question does not apply to.
   */
  it('⚠️ counts only places this window COULD rate — a non-sky location is in neither half', () => {
    const withHides = [
      ...SPOTS,
      { id: 8, regionName: 'The Lakes', skySubject: false },
      { id: 9, regionName: 'The Lakes', skySubject: false },
    ];

    const rows = build({ spots: withHides });

    expect(rows.find((r) => r.name === 'The Lakes')).toMatchObject({ placeCount: 2, atFourPlus: 1 });
  });

  /** The same filter feeds NEAREST: a 5-minute hide is not the nearest answer to a sky question. */
  it('...and the nearest drive is the nearest SCOREABLE place, not the nearest place', () => {
    const withHides = [...SPOTS, { id: 8, regionName: 'The Lakes', skySubject: false }];
    const drive = new Map([...DRIVE, [8, { driveMinutes: 5 }]]);

    const rows = build({ spots: withHides, driveMap: drive });

    expect(rows.find((r) => r.name === 'The Lakes')).toMatchObject({ driveMinutes: 95 });
  });

  /** A spot shape with no `skySubject` at all keeps counting — an unknown shape must not empty a
   *  region, and every spot `buildHeatSpots` emits carries the field. */
  it('counts a spot that carries no sky-subject flag at all', () => {
    const rows = build({ spots: [...SPOTS, { id: 8, regionName: 'The Lakes' }] });

    expect(rows.find((r) => r.name === 'The Lakes').placeCount).toBe(3);
  });

  it('narrows to the reader\'s scope — a region outside it has no row', () => {
    const rows = build({ regionsInScope: ['The Lakes', 'North East'] });

    expect(rows.map((r) => r.name)).toEqual(['The Lakes', 'North East']);
  });

  it('drops a canopy-only region, the same rule the pill\'s verdict applies', () => {
    // `eligibleRegions` is `PlanWindowProjector.rank`'s own filter: a region holding no non-canopy
    // slot is dropped, because canopy scores run on inverted polarity.
    const summary = {
      targetType: 'SUNSET',
      regions: [
        region('Woodland', 4.9, 5, 'WORTH_IT', { slots: [{ canopy: true }] }),
        ...SUMMARY.regions,
      ],
    };

    const rows = build({ summary, regionsInScope: ['Woodland', 'The Lakes', 'North East', 'The Borders'] });

    expect(rows.map((r) => r.name)).not.toContain('Woodland');
  });

  it('reads the tier through the shared helper, so a legacy payload still maps', () => {
    // `resolveRegionDisplay` falls back to MAPPING a cached payload's triage verdict rather than
    // reading it as AWAITING — the convergence plan §3 L5 step 6b asks for.
    const summary = {
      targetType: 'SUNSET',
      regions: [{ regionName: 'Legacy', verdict: 'GO', meanRating: 4.1, bestRating: 5, slots: [{ canopy: false }] }],
    };

    const rows = build({ summary, regionsInScope: ['Legacy'], points: [], spots: [] });

    expect(rows[0]).toMatchObject({ tier: 'WORTH_IT', verdictLabel: 'Worth it' });
  });

  it('has NO rows for a night window, because nothing serves a per-region night rollup', () => {
    // ⚠️ This replaces a test that passed `isSolar: false` alongside a SOLAR target type — a pairing
    // `MapView` never constructs, since a night row's `eventType` is ASTRO/AURORA. It pinned an
    // em-dash branch that could not fire in production, and two review lenses found it. The real
    // behaviour is that the index is keyed on a solar target type and a night window misses every
    // key: the panel renders its note and an empty line (map-landing-plan.md §4 #27, O-16).
    const rows = build({ targetType: 'ASTRO' });

    expect(rows).toEqual([]);
  });

  it('degrades to nothing rather than throwing on an absent payload', () => {
    expect(buildPanelRegionRows({
      index: null, date: DATE, targetType: 'SUNSET', regionsInScope: ['A'], points: null, driveMap: null, spots: null,
    })).toEqual([]);
    expect(build({ regionsInScope: [] })).toEqual([]);
    // A window the index has no entry for at all — a date beyond the briefing.
    expect(build({ date: '2026-12-25' })).toEqual([]);
  });
});

describe('windowPanelNote — the four branches, and only their own states', () => {
  const solar = (over) => windowPanelNote({ isSolar: true, ...over });

  it('one region in the tier: the ranking rule alone', () => {
    const note = solar({ verdict: { tier: 'WORTH_IT', sharingCount: 0, allInScope: false, scopedRegionCount: 3 } });

    expect(note).toMatch(/Regions rank on that average, not on their ceiling/);
    expect(note).not.toMatch(/regions are/);
    expect(note).not.toMatch(/nothing here is worth the drive/);
  });

  it('several regions: the count, the drive-time sentence, THEN the ranking rule', () => {
    const note = solar({ verdict: { tier: 'WORTH_IT', sharingCount: 2, allInScope: false, scopedRegionCount: 5 } });

    // Three, not two: `sharingCount` is the OTHERS that share the tier.
    expect(note).toMatch(/^3 regions are worth it for this window, so the choice between them is drive time\./);
    expect(note).toMatch(/Regions rank on that average/);
  });

  it('all in scope and Poor: DROPS the drive-time sentence — there is no choice to make', () => {
    const note = solar({ verdict: { tier: 'STAND_DOWN', sharingCount: 4, allInScope: true, scopedRegionCount: 5 } });

    expect(note).toBe('Poor in all 5 regions in your area — nothing here is worth the drive on '
      + 'this window. Rows are ranked by average, best first.');
    expect(note).not.toMatch(/drive time/);
    expect(note).not.toMatch(/only decides what the map draws/);
  });

  it('...and says "in your area" only when there IS an area', () => {
    // §4 #7's two forms. A reader with no postcode is scoped to the whole catalogue, and telling
    // them it is "your area" was the exact defect L2 and L4 each fixed once.
    const verdict = { tier: 'STAND_DOWN', sharingCount: 4, allInScope: true, scopedRegionCount: 5 };

    expect(solar({ verdict, scopeIsArea: false })).toMatch(/^Poor in all 5 regions — nothing/);
  });

  it('all in scope but NOT Poor keeps the several-regions branch', () => {
    // The design's own condition is `all AND poor`; every region agreeing that a night is Worth it
    // is a choice between them, which is exactly what the drive-time sentence is for.
    const note = solar({ verdict: { tier: 'WORTH_IT', sharingCount: 4, allInScope: true, scopedRegionCount: 5 } });

    expect(note).toMatch(/^5 regions are worth it/);
  });

  it('several regions POOR: no drive-time advice, even when the all-in-scope branch did not fire', () => {
    // ⚠️ `allInScope` counts every name in scope, including regions the verdict index has no entry
    // for — one woodland-only region (dropped by the canopy filter) is enough to make an all-Poor
    // area fall through here. It printed "4 regions are poor … so the choice between them is drive
    // time", which is advice to pick between write-offs — the exact sentence the branch above
    // exists to prevent. Two review lenses found it independently.
    const note = solar({ verdict: { tier: 'STAND_DOWN', sharingCount: 3, allInScope: false, scopedRegionCount: 5 } });

    expect(note).toBe('4 regions are poor for this window — nothing there is worth the drive. '
      + 'Rows are ranked by average, best first.');
    expect(note).not.toMatch(/drive time/);
  });

  it('a night window: its own note, and never a solar one', () => {
    const note = windowPanelNote({ isSolar: false, verdict: null });

    expect(note).toMatch(/^A night event, scored from darkness, clarity and Kp/);
    expect(note).toMatch(/cannot be the week’s Best bet/);
    expect(note).not.toMatch(/Regions rank/);
  });

  it('an unrated solar window still explains the ranking rather than going blank', () => {
    expect(windowPanelNote({ isSolar: true, verdict: null })).toMatch(/Regions rank on that average/);
  });
});
