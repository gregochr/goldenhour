import { describe, it, expect } from 'vitest';
import {
  buildRegionRows, buildRegionSeries, byMeanThenName, gateSpotsByRegion,
} from '../utils/windowFirstRegions.js';

/**
 * Edges of the region-row derivation the main suite leaves unpinned: the away label's nearest
 * drive, the reach word on an empty region, the shaped order, the mean-then-name comparator, finite
 * served figures, malformed spots and the region series' event matching. Fixtures are built inside
 * each `it`.
 */

/** A served region record that survives `eligibleRegions`. */
const region = (name, over = {}) => ({
  regionName: name,
  displayVerdict: 'WORTH_IT',
  meanRating: 4,
  bestRating: 5,
  sampleSufficient: true,
  slots: [{ canopy: false }],
  ...over,
});

const es = (...regions) => ({ targetType: 'SUNSET', regions, unregioned: [] });

const spot = (regionName, over = {}) => ({
  key: `${regionName}-${over.driveMinutes ?? 'x'}`, regionName, rating: 4, driveMinutes: 30, ...over,
});

describe('buildRegionRows — the away label', () => {
  const lens = { limitMinutes: 45, minRating: null, measured: true };

  it('names the nearest drive when nothing in the region is within reach', () => {
    const all = [spot('Coast', { driveMinutes: 120 }), spot('Coast', { driveMinutes: 90 }), spot('Coast', { driveMinutes: 100 })];
    const [row] = buildRegionRows(es(region('Coast')), [], all, lens);
    expect(row.awayLabel).toBe('1h 30min away');
  });

  it('ignores a spot with no drive time when taking the nearest, wherever it falls in the list', () => {
    const all = [
      spot('Coast', { driveMinutes: 90 }),
      spot('Coast', { driveMinutes: null, driveStatus: 'UNROUTABLE' }),
    ];
    const [row] = buildRegionRows(es(region('Coast')), [], all, lens);
    expect(row.awayLabel).toBe('1h 30min away');
  });

  it('reads no nearest drive from a drive that is not a finite number', () => {
    const all = [spot('Coast', { driveMinutes: Number.NaN }), spot('Coast', { driveMinutes: Infinity })];
    const [row] = buildRegionRows(es(region('Coast')), [], all, lens);
    expect(row.awayLabel).toBeNull();
  });

  it('gives no away label when the region draws a spot, even if the reach gate would drop every one', () => {
    // The caller hands over whatever it drew; a drawn spot means the region is not "away".
    const all = [spot('Coast', { driveMinutes: 100 })];
    const [row] = buildRegionRows(es(region('Coast')), all, all, lens);
    expect(row.drawnCount).toBe(1);
    expect(row.awayLabel).toBeNull();
  });

  it('gives no away label when something survives the reach gate', () => {
    const all = [spot('Coast', { driveMinutes: 30 })];
    const [row] = buildRegionRows(es(region('Coast')), [], all, lens);
    expect(row.awayLabel).toBeNull();
  });
});

describe('buildRegionRows — a region with nothing in it', () => {
  it('counts zero spots, never says "in reach", and holds no reach figure claim over nothing', () => {
    const lens = { limitMinutes: 45, minRating: null, measured: true };
    const [row] = buildRegionRows(es(region('Coast')), [], [], lens);
    expect(row.totalCount).toBe(0);
    expect(row.drawnCount).toBe(0);
    expect(row.countNoun).toBe('spots');
    expect(row.awayLabel).toBeNull();
  });

  it('says "in reach" only once the drawn spots all carry a drive and the floor is off', () => {
    const lens = { limitMinutes: 45, minRating: null, measured: true };
    const drawn = [spot('Coast', { driveMinutes: 20 })];
    const [row] = buildRegionRows(es(region('Coast')), drawn, drawn, lens);
    expect(row.countNoun).toBe('in reach');
  });
});

describe('buildRegionRows — served figures', () => {
  it('serves null for a mean, best or delta that is not a finite number', () => {
    const [row] = buildRegionRows(es(region('Coast', {
      meanRating: Infinity, bestRating: Number.NaN, meanRatingDelta: -Infinity,
    })), [], [], null);
    expect(row.meanRating).toBeNull();
    expect(row.bestRating).toBeNull();
    expect(row.meanRatingDelta).toBeNull();
  });

  it('serves a finite figure as it came', () => {
    const [row] = buildRegionRows(es(region('Coast', { meanRating: 3.5, meanRatingDelta: -0.5 })), [], [], null);
    expect(row.meanRating).toBe(3.5);
    expect(row.meanRatingDelta).toBe(-0.5);
  });

  it('serves null for a figure sent as a string', () => {
    const [row] = buildRegionRows(es(region('Coast', { meanRating: '3.5' })), [], [], null);
    expect(row.meanRating).toBeNull();
  });
});

describe('buildRegionRows — spots that cannot be grouped', () => {
  it('skips a null spot and a spot with no region name without throwing', () => {
    const drawn = [null, { key: 'x', rating: 3 }, spot('Coast'), undefined];
    const [row] = buildRegionRows(es(region('Coast')), drawn, drawn, null);
    expect(row.drawnCount).toBe(1);
    expect(row.totalCount).toBe(1);
  });

  it('reads a non-array spot list as empty', () => {
    const [row] = buildRegionRows(es(region('Coast')), undefined, 'nope', null);
    expect(row.drawnCount).toBe(0);
    expect(row.totalCount).toBe(0);
  });
});

describe('buildRegionRows — the shaped order', () => {
  const picks = (entries) => new Map(entries.map(([name, n]) => [name, { allowedPlaces: n }]));

  it('ranks regions with picks first, then by verdict tier, then by name', () => {
    const rows = buildRegionRows(
      es(
        region('Zed', { displayVerdict: 'MAYBE' }),
        region('Beta'),
        region('Gamma'),
        region('Alpha'),
      ),
      [], [], null,
      picks([['Zed', 2], ['Gamma', 1], ['Alpha', 3]]),
    );
    expect(rows.map((r) => r.name)).toEqual(['Alpha', 'Gamma', 'Zed', 'Beta']);
    expect(rows.map((r) => r.hasPicks)).toEqual([true, true, true, false]);
  });

  it('puts a region with fewer picks but a better tier ahead of a worse tier with more', () => {
    const rows = buildRegionRows(
      es(region('Zed', { displayVerdict: 'MAYBE' }), region('Alpha', { displayVerdict: 'STAND_DOWN' })),
      [], [], null,
      picks([['Zed', 1], ['Alpha', 9]]),
    );
    expect(rows.map((r) => r.name)).toEqual(['Zed', 'Alpha']);
  });

  it('hoists the served lead region when it holds picks', () => {
    const rows = buildRegionRows(
      { ...es(region('Alpha'), region('Beta'), region('Gamma')), shapedLeadRegion: 'Gamma' },
      [], [], null,
      picks([['Alpha', 1], ['Beta', 1], ['Gamma', 1]]),
    );
    expect(rows.map((r) => r.name)).toEqual(['Gamma', 'Alpha', 'Beta']);
  });

  it('does not hoist a lead region that holds no picks', () => {
    const rows = buildRegionRows(
      { ...es(region('Alpha'), region('Beta')), shapedLeadRegion: 'Beta' },
      [], [], null,
      picks([['Alpha', 1]]),
    );
    expect(rows.map((r) => r.name)).toEqual(['Alpha', 'Beta']);
  });

  it('treats a region the picks map does not mention as holding none', () => {
    const [row] = buildRegionRows(es(region('Alpha')), [], [], null, new Map());
    expect(row).toMatchObject({ shaped: true, hasPicks: false });
  });

  it('gives no rows, and does not throw, for a missing summary', () => {
    expect(buildRegionRows(undefined, [], [], null, new Map())).toEqual([]);
  });
});

describe('byMeanThenName', () => {
  const row = (name, meanRating, verdictEligible) => ({ name, meanRating, verdictEligible });

  it('puts an unrated region after a rated one when neither is verdict-eligible', () => {
    expect(byMeanThenName(row('A', null, false), row('B', 3, false))).toBeGreaterThan(0);
    expect(byMeanThenName(row('B', 3, false), row('A', null, false))).toBeLessThan(0);
  });

  it('does not count an eligible region with no mean as eligible', () => {
    // Ineligible-but-rated outranks eligible-but-unrated, from either side of the comparison.
    expect(byMeanThenName(row('C', null, true), row('D', 4, false))).toBeGreaterThan(0);
    expect(byMeanThenName(row('D', 4, false), row('C', null, true))).toBeLessThan(0);
  });

  it('puts the better mean first when both are eligible', () => {
    expect(byMeanThenName(row('A', 2, true), row('B', 4, true))).toBeGreaterThan(0);
    expect(byMeanThenName(row('B', 4, true), row('A', 2, true))).toBeLessThan(0);
  });

  it('puts an eligible region ahead of an ineligible one with a better mean', () => {
    expect(byMeanThenName(row('A', 2, true), row('B', 5, false))).toBeLessThan(0);
    expect(byMeanThenName(row('B', 5, false), row('A', 2, true))).toBeGreaterThan(0);
  });

  it('falls back to the name on an equal mean', () => {
    expect(byMeanThenName(row('B', 3, true), row('A', 3, true))).toBeGreaterThan(0);
    expect(byMeanThenName(row('A', 3, true), row('B', 3, true))).toBeLessThan(0);
  });
});

describe('gateSpotsByRegion', () => {
  it('returns the input untouched for no region, and filters by name otherwise', () => {
    const spots = [{ regionName: 'A' }, { regionName: 'B' }];
    expect(gateSpotsByRegion(spots, null)).toBe(spots);
    expect(gateSpotsByRegion(spots, 'B')).toEqual([{ regionName: 'B' }]);
  });

  it('reads a non-array list as empty', () => {
    expect(gateSpotsByRegion(undefined, 'A')).toEqual([]);
  });
});

describe('buildRegionSeries — matching an upcoming event to its summary', () => {
  const day = (date, ...summaries) => ({ date, eventSummaries: summaries });
  const summary = (targetType, name, meanRating) => ({ targetType, regions: [region(name, { meanRating })] });

  it('reads the summary of the event\'s own target type, not the first on the day', () => {
    const series = buildRegionSeries(
      [{ date: '2026-08-14', targetType: 'SUNSET' }],
      [day('2026-08-14', summary('SUNRISE', 'Coast', 1), summary('SUNSET', 'Coast', 4))],
    );
    expect([...series.get('Coast').entries()]).toEqual([['2026-08-14:SUNSET', 4]]);
  });

  it('skips an event missing its date or its target type', () => {
    const noType = { regions: [region('Coast', { meanRating: 2 })] };
    const series = buildRegionSeries(
      [{ date: '2026-08-14' }, { targetType: 'SUNSET' }, { date: '2026-08-14', targetType: undefined }],
      [
        day('2026-08-14', noType),
        { eventSummaries: [summary('SUNSET', 'Coast', 3)] },
      ],
    );
    expect(series.size).toBe(0);
  });

  it('builds one series per region across days', () => {
    const series = buildRegionSeries(
      [{ date: '2026-08-14', targetType: 'SUNSET' }, { date: '2026-08-15', targetType: 'SUNSET' }],
      [day('2026-08-14', summary('SUNSET', 'Coast', 3)), day('2026-08-15', summary('SUNSET', 'Coast', 4))],
    );
    expect([...series.get('Coast').entries()]).toEqual([
      ['2026-08-14:SUNSET', 3], ['2026-08-15:SUNSET', 4],
    ]);
  });

  it('tolerates absent events and absent days', () => {
    expect(buildRegionSeries(undefined, undefined).size).toBe(0);
    expect(buildRegionSeries([{ date: '2026-08-14', targetType: 'SUNSET' }], undefined).size).toBe(0);
  });
});
