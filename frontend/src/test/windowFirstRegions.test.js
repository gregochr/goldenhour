import { describe, it, expect } from 'vitest';
import {
  activeFilterClauses, buildRegionRows, buildRegionSeries, gateSpotsByRegion,
} from '../utils/windowFirstRegions.js';

/**
 * The open row's region derivation — the rail's cells, the band's figures and the six-dot series.
 *
 * <p>Two rules carry most of this file's weight, and both are recorded failures rather than
 * hypotheses. Every star is <b>served</b>: a client-side maximum over the drawn spots would be a
 * third answer to a question two surfaces already agree on, and it would diverge in exactly the
 * seasons {@code BriefingRegion.bestRating}'s canopy fallback exists for. And an <b>all-canopy
 * region is not on the rail at all</b>: {@code PlanWindowProjector.rank} drops it, so a rail that
 * kept it would rank a wood's inverted-polarity mean above a sky region, under a header reading
 * "Poor", beside a field painting no heat there.
 */

/** One served region record, as the briefing payload shapes it. */
function region(overrides = {}) {
  return {
    regionName: 'Northumberland & Tyneside',
    displayVerdict: 'WORTH_IT',
    summary: 'A clean eastern horizon with high cloud to catch the light.',
    meanRating: 4.2,
    bestRating: 5,
    slots: [{ locationName: 'Bamburgh', canopy: false }],
    ...overrides,
  };
}

/** One drawn spot, as {@code buildWindowSpots} shapes it. */
function spot(overrides = {}) {
  return {
    key: 'bamburgh',
    regionName: 'Northumberland & Tyneside',
    rating: 5,
    driveMinutes: 40,
    ...overrides,
  };
}

describe('buildRegionRows — which regions reach the rail', () => {
  it('drops a region whose every slot is canopy, exactly as the projector does', () => {
    // The defect P2's review caught one surface along: a wood rated 4.8 on a misty dawn outranking
    // a sky region at 4.2, under a header reading "Poor". Its mean is on inverted polarity — a
    // canopy GO means heavy cloud and mist — so it is not comparable with the numbers beside it.
    const es = {
      regions: [
        region({ regionName: 'Sky Country', meanRating: 4.2 }),
        region({
          regionName: 'The Woods',
          meanRating: 4.8,
          slots: [{ locationName: 'Bluebell Wood', canopy: true }],
        }),
      ],
    };
    const rows = buildRegionRows(es, [], [], {});
    expect(rows.map((r) => r.name)).toEqual(['Sky Country']);
  });

  it('keeps every wood when the WHOLE window is canopy, so the row is still rankable', () => {
    // The projector's own fallback: with no sky answer to prefer, dropping every region would leave
    // the window unrankable rather than honestly ranked on what was measured.
    const es = {
      regions: [
        region({ regionName: 'North Woods', meanRating: 4.8, slots: [{ canopy: true }] }),
        region({ regionName: 'South Woods', meanRating: 3.1, slots: [{ canopy: true }] }),
      ],
    };
    expect(buildRegionRows(es, [], [], {}).map((r) => r.name))
      .toEqual(['North Woods', 'South Woods']);
  });

  it('keeps a region carrying no slots at all, which makes no canopy claim either way', () => {
    const es = { regions: [region({ regionName: 'Unslotted', slots: [] })] };
    expect(buildRegionRows(es, [], [], {}).map((r) => r.name)).toEqual(['Unslotted']);
  });

  it('returns nothing for a window with no event summary', () => {
    expect(buildRegionRows(null, [], [], {})).toEqual([]);
  });
});

describe('buildRegionRows — the ranking', () => {
  it('ranks on the served mean, best first', () => {
    const es = {
      regions: [
        region({ regionName: 'Lakes', meanRating: 3.1 }),
        region({ regionName: 'Coast', meanRating: 4.4 }),
        region({ regionName: 'Dales', meanRating: 3.9 }),
      ],
    };
    expect(buildRegionRows(es, [], [], {}).map((r) => r.name)).toEqual(['Coast', 'Dales', 'Lakes']);
  });

  it('breaks a tie on the name, so the order is total and the rail cannot flicker', () => {
    const es = {
      regions: [
        region({ regionName: 'Zed', meanRating: 4 }),
        region({ regionName: 'Alpha', meanRating: 4 }),
      ],
    };
    expect(buildRegionRows(es, [], [], {}).map((r) => r.name)).toEqual(['Alpha', 'Zed']);
  });

  it('ranks a region with NO mean last rather than treating the absence as a zero', () => {
    // "Not scored" and "scored badly" are different statements — the same rule `windowFirstOrder.js`
    // applies to whole windows.
    const es = {
      regions: [
        region({ regionName: 'Unscored', meanRating: null }),
        region({ regionName: 'Poor', meanRating: 1.2 }),
      ],
    };
    const rows = buildRegionRows(es, [], [], {});
    expect(rows.map((r) => r.name)).toEqual(['Poor', 'Unscored']);
    expect(rows[1].meanRating).toBeNull();
  });
});

describe('buildRegionRows — the served figures', () => {
  it('copies meanRating and bestRating through, never a maximum of the drawn spots', () => {
    // The drawn spot is rated 5 and the served best is 3. A client-side max would print 5 — and
    // would be a third answer beside the header's own served number.
    const es = { regions: [region({ bestRating: 3, meanRating: 2.5 })] };
    const rows = buildRegionRows(es, [spot({ rating: 5 })], [spot({ rating: 5 })], {});
    expect(rows[0].bestRating).toBe(3);
    expect(rows[0].meanRating).toBe(2.5);
  });

  it('reads a missing bestRating as null rather than coercing it', () => {
    const es = { regions: [region({ bestRating: undefined, meanRating: undefined })] };
    const rows = buildRegionRows(es, [], [], {});
    expect(rows[0].bestRating).toBeNull();
    expect(rows[0].meanRating).toBeNull();
  });

  it('copies the served movement through, including a MEASURED zero', () => {
    // The client has no previous build to subtract from and could not reconstruct one — the
    // comparand is a mean over the backend's voting-slot rule, from a payload since overwritten.
    // The zero must survive: the band draws `—` for it and nothing at all for null.
    const es = { regions: [region({ meanRatingDelta: 0 })] };
    expect(buildRegionRows(es, [], [], {})[0].meanRatingDelta).toBe(0);
  });

  it('copies a non-zero movement through with its sign', () => {
    // Without this the zero case above is satisfied by `region.meanRatingDelta === 0 ? 0 : null`,
    // which drops every real delta and leaves the band showing `—` on regions that moved.
    const es = { regions: [region({ meanRatingDelta: -0.4 })] };
    expect(buildRegionRows(es, [], [], {})[0].meanRatingDelta).toBe(-0.4);
  });

  it('reads a missing movement as null rather than coercing it to zero', () => {
    // A zero here would put a `—` on every region of every legacy payload, claiming a stillness
    // nobody measured.
    const es = { regions: [region({ meanRatingDelta: undefined })] };
    expect(buildRegionRows(es, [], [], {})[0].meanRatingDelta).toBeNull();
  });

  it('takes the verdict word from displayVerdict, and Awaiting where there is none', () => {
    const es = {
      regions: [
        region({ regionName: 'A', displayVerdict: 'STAND_DOWN', meanRating: 2 }),
        region({ regionName: 'B', displayVerdict: undefined, meanRating: 1 }),
      ],
    };
    const rows = buildRegionRows(es, [], [], {});
    expect(rows[0].verdictLabel).toBe('Poor');
    expect(rows[1].verdictLabel).toBe('Awaiting');
    expect(rows[1].verdict).toBe('AWAITING');
  });
});

describe('buildRegionRows — what a cell may say about its count', () => {
  const ES = { regions: [region()] };

  it('counts what the window DRAWS in that region, so the cell and the strip agree', () => {
    const drawn = [spot({ key: 'a' }), spot({ key: 'b' }), spot({ key: 'c', regionName: 'Lakes' })];
    const rows = buildRegionRows(ES, drawn, drawn, { limitMinutes: 90, minRating: null });
    expect(rows[0].drawnCount).toBe(2);
  });

  it('uses the word "reach" only when a tier is actually gating', () => {
    const drawn = [spot()];
    expect(buildRegionRows(ES, drawn, drawn, { limitMinutes: 90, minRating: null })[0].countNoun)
      .toBe('in reach');
  });

  it('drops the word "reach" under Any, where nothing was gated', () => {
    const drawn = [spot()];
    expect(buildRegionRows(ES, drawn, drawn, { limitMinutes: null, minRating: null })[0].countNoun)
      .toBe('spots');
  });

  it('drops the word "reach" while a rating floor is also active', () => {
    // The drawn set is then gated on two axes and this clause names one of them — the window
    // header's own rule, applied one level down.
    const drawn = [spot()];
    expect(buildRegionRows(ES, drawn, drawn, { limitMinutes: 90, minRating: 4 })[0].countNoun)
      .toBe('spots');
  });

  it('drops the word "reach" when one drawn spot has no measured drive', () => {
    // An unknown drive passes every tier, so a part-measured set is not a set within reach.
    const drawn = [spot({ key: 'a' }), spot({ key: 'b', driveMinutes: null })];
    expect(buildRegionRows(ES, drawn, drawn, { limitMinutes: 90, minRating: null })[0].countNoun)
      .toBe('spots');
  });
});

describe('buildRegionRows — the distance clause', () => {
  const ES = { regions: [region()] };

  it('states the nearest drive when REACH is what emptied the region', () => {
    const all = [spot({ driveMinutes: 158 }), spot({ key: 'b', driveMinutes: 200 })];
    const rows = buildRegionRows(ES, [], all, { limitMinutes: 90, minRating: null });
    expect(rows[0].drawnCount).toBe(0);
    expect(rows[0].awayLabel).toBe('2h 38min away');
  });

  it('states NO distance when the rating floor is what emptied it', () => {
    // A region you can drive to in forty minutes is not "40 min away" in the sense this clause
    // means — naming a drive there would point at the wrong control.
    const all = [spot({ driveMinutes: 40, rating: 2 })];
    const rows = buildRegionRows(ES, [], all, { limitMinutes: 90, minRating: 4 });
    expect(rows[0].drawnCount).toBe(0);
    expect(rows[0].awayLabel).toBeNull();
  });

  it('states no distance when nobody measured one', () => {
    const all = [spot({ driveMinutes: null })];
    const rows = buildRegionRows(ES, [], all, { limitMinutes: 90, minRating: null });
    expect(rows[0].awayLabel).toBeNull();
  });

  it('states no distance for a region that is not empty', () => {
    const all = [spot({ driveMinutes: 40 })];
    expect(buildRegionRows(ES, all, all, { limitMinutes: 90, minRating: null })[0].awayLabel)
      .toBeNull();
  });
});

describe('buildRegionRows — the band’s lens figures', () => {
  it('measures the rating figure over everything the region holds, not over the drawn set', () => {
    // `M of N`: the floor's denominator is what the floor chose from. Two of the four clear 4★, and
    // one of those two is beyond the tier — so the reach figure and this one differ, which is the
    // whole reason the band prints both.
    const all = [
      spot({ key: 'a', rating: 5, driveMinutes: 30 }),
      spot({ key: 'b', rating: 4, driveMinutes: 300 }),
      spot({ key: 'c', rating: 2, driveMinutes: 30 }),
      spot({ key: 'd', rating: 1, driveMinutes: 30 }),
    ];
    const drawn = [all[0]];
    const rows = buildRegionRows({ regions: [region()] }, drawn, all, {
      limitMinutes: 90, minRating: 4,
    });
    expect(rows[0].totalCount).toBe(4);
    expect(rows[0].atFloorCount).toBe(2);
    expect(rows[0].drawnCount).toBe(1);
  });
});

describe('buildRegionRows — whether the band may state a reach figure', () => {
  const ES = { regions: [region()] };

  it('permits it when a tier gates and every drawn spot carries a drive', () => {
    const drawn = [spot({ driveMinutes: 30 })];
    expect(buildRegionRows(ES, drawn, drawn, { limitMinutes: 90, minRating: null })[0]
      .reachFigureHolds).toBe(true);
  });

  it('withholds it when NO drive was ever measured — the browser-seen defect', () => {
    // A 45 min tier with no home postcode: every drive is unknown, every spot passes the gate, and
    // the figure read `within 45 min — 6` about six locations nobody had measured a drive to.
    const drawn = [spot({ driveMinutes: null })];
    expect(buildRegionRows(ES, drawn, drawn, { limitMinutes: 45, minRating: null })[0]
      .reachFigureHolds).toBe(false);
  });

  it('withholds it when even ONE drawn spot has no measured drive', () => {
    const drawn = [spot({ key: 'a', driveMinutes: 30 }), spot({ key: 'b', driveMinutes: null })];
    expect(buildRegionRows(ES, drawn, drawn, { limitMinutes: 90, minRating: null })[0]
      .reachFigureHolds).toBe(false);
  });

  it('withholds it under Any, where the tier gates nothing', () => {
    const drawn = [spot({ driveMinutes: 30 })];
    expect(buildRegionRows(ES, drawn, drawn, { limitMinutes: null, minRating: null })[0]
      .reachFigureHolds).toBe(false);
  });

  it('PERMITS it while a rating floor is also active, unlike the cell’s count noun', () => {
    // The two are deliberately different tests: the figure sits beside `at 4★+ · M of N`, so the
    // second axis is on screen rather than hidden behind one word.
    const drawn = [spot({ driveMinutes: 30 })];
    const row0 = buildRegionRows(ES, drawn, drawn, { limitMinutes: 90, minRating: 4 })[0];
    expect(row0.reachFigureHolds).toBe(true);
    expect(row0.countNoun).toBe('spots');
  });
});

describe('gateSpotsByRegion', () => {
  it('keeps only the named region, matching the name byte-identically', () => {
    // Nothing normalises a region name anywhere in this join — a trim here would make " North East"
    // miss "North East", and the kernel answers a focus matching nothing by fading the whole canvas.
    const spots = [spot({ regionName: 'North East' }), spot({ regionName: ' North East' })];
    expect(gateSpotsByRegion(spots, 'North East')).toHaveLength(1);
    expect(gateSpotsByRegion(spots, 'North East')[0].regionName).toBe('North East');
  });

  it('returns the input untouched for a null region, which is what makes All a peer choice', () => {
    const spots = [spot(), spot({ key: 'b' })];
    expect(gateSpotsByRegion(spots, null)).toBe(spots);
  });

  it('returns an empty list for a region with nothing drawn', () => {
    expect(gateSpotsByRegion([spot()], 'Nowhere')).toEqual([]);
  });
});

describe('activeFilterClauses', () => {
  it('names all three when all three are in force, region first', () => {
    expect(activeFilterClauses({
      regionName: 'The Lake District',
      minRating: 4,
      ratingLabel: '4★+',
      limitMinutes: 150,
      tierLabel: '2h 30min',
    })).toEqual(['The Lake District', '4★+', 'within 2h 30min']);
  });

  it('omits an axis that gates nothing, rather than naming a control left alone', () => {
    expect(activeFilterClauses({
      regionName: null, minRating: null, ratingLabel: 'Any rating', limitMinutes: null, tierLabel: 'Any',
    })).toEqual([]);
  });

  it('names the region alone when the lens gates nothing', () => {
    expect(activeFilterClauses({
      regionName: 'Coast', minRating: null, ratingLabel: 'Any rating', limitMinutes: null, tierLabel: 'Any',
    })).toEqual(['Coast']);
  });

  it('names the tier alone when only reach gates', () => {
    expect(activeFilterClauses({
      regionName: null, minRating: null, ratingLabel: null, limitMinutes: 90, tierLabel: '1h 30min',
    })).toEqual(['within 1h 30min']);
  });
});

describe('buildRegionSeries', () => {
  const EVENTS = [
    { date: '2026-08-04', targetType: 'SUNSET' },
    { date: '2026-08-05', targetType: 'SUNRISE' },
  ];

  it('keys by region name then by window key, never by position', () => {
    // The card list is not the event list, and `selectUpcomingEvents` re-indexes when a window
    // passes mid-session — the same reason `buildHeatPointSets` is keyed.
    const days = [
      { date: '2026-08-04', eventSummaries: [{ targetType: 'SUNSET', regions: [region({ meanRating: 4.2 })] }] },
      { date: '2026-08-05', eventSummaries: [{ targetType: 'SUNRISE', regions: [region({ meanRating: 2.1 })] }] },
    ];
    const series = buildRegionSeries(EVENTS, days);
    const windows = series.get('Northumberland & Tyneside');
    expect(windows.get('2026-08-04:SUNSET')).toBe(4.2);
    expect(windows.get('2026-08-05:SUNRISE')).toBe(2.1);
  });

  it('records null for a window the region carries no mean in', () => {
    const days = [
      { date: '2026-08-04', eventSummaries: [{ targetType: 'SUNSET', regions: [region({ meanRating: null })] }] },
    ];
    const windows = buildRegionSeries(EVENTS, days).get('Northumberland & Tyneside');
    expect(windows.get('2026-08-04:SUNSET')).toBeNull();
  });

  it('omits a window in which the region is all canopy, so the band cannot chart a wood', () => {
    // A MIXED window: the sky region beside it is what makes the wood ineligible. In an all-canopy
    // window the projector's own fallback keeps every wood, which the rail test above pins.
    const days = [
      {
        date: '2026-08-04',
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [
            region({ meanRating: 4.9, slots: [{ canopy: true }] }),
            region({ regionName: 'Sky Country', meanRating: 3.5 }),
          ],
        }],
      },
      { date: '2026-08-05', eventSummaries: [{ targetType: 'SUNRISE', regions: [region({ meanRating: 3 })] }] },
    ];
    const windows = buildRegionSeries(EVENTS, days).get('Northumberland & Tyneside');
    expect(windows.has('2026-08-04:SUNSET')).toBe(false);
    expect(windows.get('2026-08-05:SUNRISE')).toBe(3);
  });

  it('is empty for a payload with no days', () => {
    expect(buildRegionSeries(EVENTS, null).size).toBe(0);
  });
});
