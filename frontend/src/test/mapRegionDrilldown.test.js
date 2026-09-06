/**
 * `utils/mapDrilldown.js`'s SECOND level — one region's top locations and its stats line
 * (`docs/engineering/map-landing-plan.md` §3 L6, `docs/design/map-landing/README.md` §5).
 *
 * <p>Kept apart from `mapDrilldown.test.js` (the window level) because the two answer different
 * questions off different inputs — this one joins two served indexes the level above never reads.
 */
import { describe, it, expect } from 'vitest';
import {
  buildRegionLocationRows, regionStatSegments, REGION_PANEL_LOCATIONS,
} from '../utils/mapDrilldown.js';
import { buildScoreIndex, buildTideAlignmentIndex } from '../utils/locationSheet.js';

const DATE = '2026-01-15';

/**
 * ⚠️ **Built by the REAL builders, from the served slot shape.** `buildRegionGlossIndex` shipped
 * silently empty for weeks because its own fixture used the wrong field name (`name` for
 * `regionName`) and therefore pre-satisfied the wrong predicate — `regionGloss.js` records it. A
 * hand-rolled `{byId, byName}` here would reproduce exactly that class of green-but-dead test, so
 * both indexes are built from a `briefing.days` tree.
 *
 * <p>`solarEventTime` is deliberately NOT on these slots: nothing on this path reads it. The event
 * instant is recovered from the light boundaries by `locationSheet.eventInstantOf` — for SUNSET,
 * `goldenHourEnd` — which is the same recovery the map callout already makes. A fixture carrying
 * `solarEventTime` would pass whether or not the code read the right field.
 */
const DAYS = [{
  date: DATE,
  eventSummaries: [{
    /**
     * ⚠️ **A SUNRISE summary whose answers DIFFER from the sunset's, and it exists because a review
     * lens measured the alternative.** With only a SUNSET summary, the "reads the window it is asked
     * about" test below landed on a location that answers `false`/null on BOTH windows, so
     * hard-coding `'SUNSET'` into either lookup survived all 191 tests. Castlerigg's water lands on
     * the sunrise and not on the sunset, and only the sunrise carries a `blueHourEnd`, so each
     * lookup's window is pinned by a value that genuinely changes with it.
     */
    targetType: 'SUNRISE',
    regions: [{
      regionName: 'The Lakes',
      slots: [
        {
          locationId: 2, locationName: 'Castlerigg', rating: 4,
          blueHourEnd: `${DATE}T08:30:00`, goldenHourStart: `${DATE}T08:30:00`,
          tideOnTheLight: false,
        },
        {
          locationId: 3, locationName: 'Buttermere', rating: 4,
          blueHourEnd: `${DATE}T08:31:00`, goldenHourStart: `${DATE}T08:31:00`,
          tideOnTheLight: true,
        },
      ],
    }],
  }, {
    targetType: 'SUNSET',
    regions: [{
      regionName: 'The Lakes',
      slots: [
        {
          locationId: 1, locationName: 'Ashness Bridge', rating: 5,
          goldenHourEnd: `${DATE}T16:20:00`, blueHourStart: `${DATE}T16:20:00`,
        },
        {
          locationId: 2, locationName: 'Castlerigg', rating: 3,
          goldenHourEnd: `${DATE}T16:22:00`, blueHourStart: `${DATE}T16:22:00`,
          // Coastal-style fact on an inland fixture is fine — the index only carries the boolean.
          tideOnTheLight: true, nearestSolarOffsetPhrase: 'HW 18 min after sunset',
        },
        {
          locationId: 3, locationName: 'Buttermere', rating: 4,
          goldenHourEnd: `${DATE}T16:21:00`, blueHourStart: `${DATE}T16:21:00`,
          tideOnTheLight: false,
        },
      ],
    }],
  }],
}];

const SCORE_INDEX = buildScoreIndex([
  // The SUNRISE rows carry a `blueHourEnd` and NO `goldenHourEnd`, so `eventInstantOf` can only
  // answer for them on the SUNRISE side — which is what makes the boundary choice testable.
  {
    locationId: 2, locationName: 'Castlerigg', date: DATE, targetType: 'SUNRISE', rating: 4,
    blueHourEnd: `${DATE}T08:30:00`,
  },
  {
    locationId: 3, locationName: 'Buttermere', date: DATE, targetType: 'SUNRISE', rating: 4,
    blueHourEnd: `${DATE}T08:31:00`,
  },
  {
    locationId: 1, locationName: 'Ashness Bridge', date: DATE, targetType: 'SUNSET', rating: 5,
    goldenHourEnd: `${DATE}T16:20:00`,
  },
  {
    locationId: 2, locationName: 'Castlerigg', date: DATE, targetType: 'SUNSET', rating: 3,
    goldenHourEnd: `${DATE}T16:22:00`,
  },
  {
    locationId: 3, locationName: 'Buttermere', date: DATE, targetType: 'SUNSET', rating: 4,
    goldenHourEnd: `${DATE}T16:21:00`,
  },
]);
const TIDE_INDEX = buildTideAlignmentIndex(DAYS);

const POINTS = [
  { id: 1, name: 'Ashness Bridge', rid: 'The Lakes', r: [5] },
  { id: 2, name: 'Castlerigg', rid: 'The Lakes', r: [3] },
  { id: 3, name: 'Buttermere', rid: 'The Lakes', r: [4] },
  { id: 9, name: 'Bamburgh', rid: 'North East', r: [5] },
];

const DRIVE = new Map([
  [1, { driveMinutes: 95 }], [2, { driveMinutes: 60 }], [3, { driveMinutes: 40 }],
  [9, { driveMinutes: 25 }],
]);

function build(over = {}) {
  return buildRegionLocationRows({
    regionName: 'The Lakes',
    points: POINTS,
    driveMap: DRIVE,
    scoreIndex: SCORE_INDEX,
    tideIndex: TIDE_INDEX,
    date: DATE,
    targetType: 'SUNSET',
    ...over,
  });
}

describe('buildRegionLocationRows — the population', () => {
  it('names only this region\'s locations, and never a neighbour\'s', () => {
    expect(build().map((r) => r.name))
      .toEqual(['Ashness Bridge', 'Buttermere', 'Castlerigg']);
  });

  it('drops a location the window did not score — it has no star to rank on', () => {
    const rows = build({ points: [...POINTS, { id: 4, name: 'Wastwater', rid: 'The Lakes', r: [null] }] });

    expect(rows.map((r) => r.name)).not.toContain('Wastwater');
  });

  it('answers with nothing at all when no region is named', () => {
    expect(build({ regionName: null })).toEqual([]);
  });

  it('caps the list at four, and the cap is the exported constant', () => {
    const many = Array.from({ length: 9 }, (_, i) => ({
      id: 100 + i, name: `Place ${i}`, rid: 'The Lakes', r: [5 - (i % 3)],
    }));

    expect(build({ points: many })).toHaveLength(REGION_PANEL_LOCATIONS);
    expect(REGION_PANEL_LOCATIONS).toBe(4);
  });
});

describe('buildRegionLocationRows — the ranking', () => {
  it('ranks on stars first, best first', () => {
    expect(build().map((r) => r.rating)).toEqual([5, 4, 3]);
  });

  /**
   * ⚠️ The two rows sit on ONE rating so the star comparator cannot decide between them, and their
   * drives are the reverse of their names — so a comparator that fell through to name order, or one
   * that never reached the drive at all, produces a different list from this one.
   */
  it('breaks a tie on the shorter drive, not on the name', () => {
    const tied = [
      { id: 1, name: 'Ashness Bridge', rid: 'The Lakes', r: [4] },
      { id: 3, name: 'Buttermere', rid: 'The Lakes', r: [4] },
    ];

    expect(build({ points: tied }).map((r) => r.name)).toEqual(['Buttermere', 'Ashness Bridge']);
  });

  /**
   * ⚠️ An unmeasured drive is not a nearer journey. Without the `?? Infinity` it compares as
   * `NaN`, every comparison is false, and the pair keeps whatever order the array held — which is
   * a different four locations depending on how the points were assembled.
   */
  it('sorts an unmeasured drive LAST among equals, never first', () => {
    const tied = [
      { id: 7, name: 'Unmeasured', rid: 'The Lakes', r: [4] },
      { id: 3, name: 'Buttermere', rid: 'The Lakes', r: [4] },
    ];

    expect(build({ points: tied }).map((r) => r.name)).toEqual(['Buttermere', 'Unmeasured']);
  });

  it('falls through to the name, so two equal rows cannot re-order between renders', () => {
    const tied = [
      { id: 6, name: 'Zebra Tarn', rid: 'The Lakes', r: [4] },
      { id: 5, name: 'Alpha Force', rid: 'The Lakes', r: [4] },
    ];

    expect(build({ points: tied, driveMap: new Map() }).map((r) => r.name))
      .toEqual(['Alpha Force', 'Zebra Tarn']);
  });
});

describe('buildRegionLocationRows — the two looked-up facts', () => {
  /**
   * The whole point of the leave-by line: `16:20` UTC minus a 95-minute drive minus 20 minutes of
   * setup is `14:25` UTC, which is `14:25` on the UK clock in January. A row that read the WINDOW's
   * header clock instead of this location's own boundary would give every row one departure.
   */
  it('subtracts this location\'s own event instant, recovered from its light boundary', () => {
    const [first] = build();

    expect(first.name).toBe('Ashness Bridge');
    expect(first.leaveTime).toBe('14:25');
    expect(first.leaveDayWord).toBeNull();
  });

  it('gives two locations two departures, because each carries its own instant', () => {
    const times = Object.fromEntries(build().map((r) => [r.name, r.leaveTime]));

    // 16:21 − 40 − 20 = 15:21; 16:22 − 60 − 20 = 15:02.
    expect(times.Buttermere).toBe('15:21');
    expect(times.Castlerigg).toBe('15:02');
  });

  it('⚠️ withholds the drive AND the departure where no drive is measured — never a zero', () => {
    const [row] = build({ driveMap: new Map() });

    expect(row.driveMinutes).toBeNull();
    expect(row.driveLabel).toBeNull();
    expect(row.leaveTime).toBeNull();
  });

  it('withholds the departure where the score index carries no light boundary for the window', () => {
    const [row] = build({ scoreIndex: buildScoreIndex([]) });

    expect(row.driveLabel).not.toBeNull();
    expect(row.leaveTime).toBeNull();
  });

  it('flags the tide only where the payload says the water lands on the light', () => {
    const tide = Object.fromEntries(build().map((r) => [r.name, r.tideOnLight]));

    expect(tide.Castlerigg).toBe(true);
    // Served `false` — the deriver looked and said no.
    expect(tide.Buttermere).toBe(false);
    // Not indexed at all — the deriver could not answer. Same silence, different claim.
    expect(tide['Ashness Bridge']).toBe(false);
  });

  /**
   * ⚠️ Both halves assert a value that genuinely CHANGES with the window, which the first cut of
   * this test did not: Castlerigg's water lands on the sunrise and not on the sunset, and only its
   * sunrise row carries a light boundary this index can recover an instant from. A lookup with
   * `'SUNSET'` hard-coded into it now fails; before, it survived all 191 tests.
   */
  it('reads the window it is asked about — the tide answer flips with it', () => {
    const tide = Object.fromEntries(
      build({ targetType: 'SUNRISE' }).map((r) => [r.name, r.tideOnLight]),
    );

    expect(tide.Castlerigg).toBe(false);
    expect(tide.Buttermere).toBe(true);
    // …and the reverse on the sunset, from the same fixture.
    const sunset = Object.fromEntries(build().map((r) => [r.name, r.tideOnLight]));
    expect(sunset.Castlerigg).toBe(true);
    expect(sunset.Buttermere).toBe(false);
  });

  /**
   * ⚠️ The SUNRISE rows carry `blueHourEnd` alone and the SUNSET rows `goldenHourEnd` alone, so a
   * departure exists on exactly one side per row. That pins BOTH the lookup's window and
   * `eventInstantOf`'s boundary choice — with one window in the fixture, hard-coding either
   * survived, because the wrong answer was null for a reason the fixture supplied.
   */
  it('...and takes the boundary that window\'s own side uses', () => {
    // 08:30 UTC − a 60-minute drive − 20 minutes of setup = 07:10.
    const sunrise = Object.fromEntries(
      build({ targetType: 'SUNRISE' }).map((r) => [r.name, r.leaveTime]),
    );
    expect(sunrise.Castlerigg).toBe('07:10');
    // Its SUNSET row has no `blueHourEnd`, so a SUNRISE-side read of the sunset yields nothing.
    expect(Object.fromEntries(build().map((r) => [r.name, r.leaveTime])).Castlerigg).toBe('15:02');
  });
});

describe('regionStatSegments', () => {
  const ROW = {
    name: 'The Lakes', atFourPlus: 4, placeCount: 9, driveLabel: '1h 35min', meanRating: 4.24,
  };

  it('reads the window panel row\'s own figures, in the design\'s order', () => {
    expect(regionStatSegments(ROW).map((s) => s.text))
      .toEqual(['4 of 9 at 4', 'nearest 1h 35min', 'average 4.2']);
  });

  /**
   * ⚠️ The prototype's copy is `N of M rated locations at 4★+`. Our M counts PLACES in scope, so
   * that noun would be false about our own number — and "N of M scored" is banned by name in
   * `plan-matrix-plan.md` §5 and CLAUDE.md's licensed-class bullet.
   */
  it('⚠️ never says "rated locations", "scored" or "in reach"', () => {
    const line = regionStatSegments(ROW).map((s) => s.text).join(' ');

    expect(line).not.toMatch(/rated|scored|reach/i);
  });

  /**
   * ⚠️ Asserted as a whole shape rather than as an `expect` inside an `if` inside a loop — that form
   * is vacuous the moment the glyphs stop being emitted, and `toBeTruthy()` is banned by name in
   * `docs/engineering/frontend-test-standards.md`.
   */
  it('carries a spoken alternative for every star glyph, because NVDA does not speak U+2605', () => {
    expect(regionStatSegments(ROW).map(({ key, glyph, spoken }) => ({ key, glyph, spoken })))
      .toEqual([
        { key: 'hits', glyph: '★+', spoken: ' stars or better' },
        { key: 'near', glyph: null, spoken: null },
        { key: 'mean', glyph: '★', spoken: ' stars' },
      ]);
  });

  it('omits the drive where none is measured, rather than dashing it', () => {
    expect(regionStatSegments({ ...ROW, driveLabel: null }).map((s) => s.key))
      .toEqual(['hits', 'mean']);
  });

  it('omits the average where the region carries no mean', () => {
    expect(regionStatSegments({ ...ROW, meanRating: null }).map((s) => s.key))
      .toEqual(['hits', 'near']);
  });

  it('trims a trailing .0 rather than implying a precision the roll-up has not got', () => {
    expect(regionStatSegments({ ...ROW, meanRating: 4 }).map((s) => s.text))
      .toContain('average 4');
  });

  it('answers with nothing for no row at all', () => {
    expect(regionStatSegments(null)).toEqual([]);
  });
});
