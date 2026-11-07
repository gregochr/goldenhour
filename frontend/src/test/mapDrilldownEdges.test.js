/**
 * Edge behaviour of `utils/mapDrilldown.js`: malformed input to the window panel's row builder, the
 * shaped (picks) row fields, the two shaped note helpers and the region panel's location rows.
 * Companion to mapDrilldown.test.js, mapDrilldownShaped.test.js and mapRegionDrilldown.test.js; each
 * case pins an answer the happy-path suites never ask for.
 */
import { describe, it, expect } from 'vitest';
import {
  buildPanelRegionRows, buildRegionLocationRows, shapedNoteLead, shapedWindowPanelNote,
} from '../utils/mapDrilldown.js';
import { buildScoreIndex } from '../utils/locationSheet.js';
import { tideIndexOf } from './tideFactsFixture.js';

const DATE = '2026-01-15';

const rec = (regionName, displayVerdict = 'WORTH_IT', extra = {}) => ({
  regionName, displayVerdict, meanRating: 4, bestRating: 5, ...extra,
});
const indexOf = (...records) => new Map(records.map((r) => [`${DATE}|SUNSET|${r.regionName}`, r]));
const panel = (over = {}) => buildPanelRegionRows({
  index: indexOf(rec('Lakes')),
  date: DATE,
  targetType: 'SUNSET',
  regionsInScope: ['Lakes'],
  points: [],
  driveMap: null,
  spots: [],
  ...over,
});

describe('buildPanelRegionRows — malformed and partial input', () => {
  it('drops falsy names from the scope, so no row is built for "" or null', () => {
    const index = new Map([
      [`${DATE}|SUNSET|Lakes`, rec('Lakes')],
      [`${DATE}|SUNSET|`, rec('')],
      [`${DATE}|SUNSET|null`, rec('null')],
      [`${DATE}|SUNSET|undefined`, rec('undefined')],
    ]);
    const rows = panel({ index, regionsInScope: ['Lakes', '', null, undefined] });
    expect(rows.map((r) => r.name)).toEqual(['Lakes']);
  });

  it('treats a non-array scope as empty, even when the index holds a key a stray entry could match', () => {
    const index = new Map([[`${DATE}|SUNSET|Stryker was here`, rec('Stryker was here')]]);
    expect(panel({ index, regionsInScope: null })).toEqual([]);
    expect(panel({ index, regionsInScope: 'Lakes' })).toEqual([]);
  });

  it('survives null entries in spots and points without throwing', () => {
    const rows = panel({
      spots: [null, { id: 1, regionName: 'Lakes' }],
      points: [null, { id: 1, rid: 'Lakes', r: [5] }],
    });
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ placeCount: 1, atFourPlus: 1 });
  });

  it('survives a point with no rating array', () => {
    const rows = panel({
      spots: [{ id: 1, regionName: 'Lakes' }],
      points: [{ id: 1, rid: 'Lakes' }, { id: 2, rid: 'Lakes', r: [4] }],
    });
    expect(rows[0].atFourPlus).toBe(1);
  });

  it('counts a point at the star floor and not below it, and ignores unscored points', () => {
    const at = (r) => panel({ points: [{ id: 1, rid: 'Lakes', r }] })[0].atFourPlus;
    expect(at([3])).toBe(0);
    expect(at([4])).toBe(1);
    expect(at([5])).toBe(1);
    expect(at([null])).toBe(0);
    expect(at(['5'])).toBe(0);
    expect(at([NaN])).toBe(0);
  });

  it('carries only finite numeric means and ceilings, as null otherwise', () => {
    const rows = panel({
      index: indexOf(
        rec('Lakes', 'WORTH_IT', { meanRating: '4.2', bestRating: NaN }),
        rec('Dales', 'WORTH_IT', { meanRating: Infinity, bestRating: 3 }),
      ),
      regionsInScope: ['Lakes', 'Dales'],
    });
    const by = new Map(rows.map((r) => [r.name, r]));
    expect(by.get('Lakes')).toMatchObject({ meanRating: null, bestRating: null });
    expect(by.get('Dales')).toMatchObject({ meanRating: null, bestRating: 3 });
  });

  it('gives a region with no reachable place a null drive and no drive label', () => {
    const rows = panel({
      index: indexOf(rec('Lakes'), rec('Dales')),
      regionsInScope: ['Lakes', 'Dales'],
      spots: [{ id: 1, regionName: 'Lakes' }, { id: 2, regionName: 'Dales' }],
      driveMap: new Map([[1, { driveMinutes: 95 }]]),
    });
    const by = new Map(rows.map((r) => [r.name, r]));
    expect(by.get('Lakes')).toMatchObject({ driveMinutes: 95, driveLabel: '1h 35min' });
    expect(by.get('Dales').driveMinutes).toBeNull();
    expect(by.get('Dales').driveLabel).toBeNull();
  });

  it('returns no rows without an index, a date or a target type', () => {
    expect(panel({ index: null })).toEqual([]);
    expect(panel({ date: '' })).toEqual([]);
    expect(panel({ targetType: undefined })).toEqual([]);
  });
});

describe('buildPanelRegionRows — shaped fields on a partial picks map', () => {
  const build = (regionPicks) => panel({
    index: indexOf(rec('Lakes', 'WORTH_IT', { picksBestRating: 4 })),
    regionPicks,
  })[0];

  it('gives a region the picks map has no entry for no picks, no outside places and no best', () => {
    expect(build(new Map())).toMatchObject({
      shaped: true, hasPicks: false, outsideCount: 0, bestRating: null,
    });
  });

  it('counts every place of an unpicked region as outside, even with no allowed count served', () => {
    expect(build(new Map([['Lakes', { places: 7 }]]))).toMatchObject({
      hasPicks: false, outsideCount: 7,
    });
  });

  it('subtracts the allowed count from the places, so a negative one adds to them', () => {
    expect(build(new Map([['Lakes', { places: 5, allowedPlaces: -2 }]]))).toMatchObject({
      hasPicks: false, outsideCount: 7,
    });
  });

  it('floors the outside count of a picked row at zero', () => {
    expect(build(new Map([['Lakes', { places: 2, allowedPlaces: 3 }]]))).toMatchObject({
      hasPicks: true, outsideCount: 0, bestRating: 4,
    });
  });
});

describe('shapedWindowPanelNote and shapedNoteLead — non-array rows', () => {
  it('answer the empty note and no lead for a missing row list', () => {
    expect(shapedWindowPanelNote({ rows: null, leadRegion: 'Lakes' })).toBe('');
    expect(shapedNoteLead({ rows: undefined, leadRegion: 'Lakes' })).toBeNull();
    expect(shapedNoteLead({ rows: 'Lakes', leadRegion: 'Lakes' })).toBeNull();
  });
});

describe('shapedNoteLead', () => {
  const row = (name, hasPicks) => ({ name, hasPicks, tier: 'MAYBE', verdictLabel: 'Maybe' });

  it('is null when no row has picks, even when the lead region is listed', () => {
    expect(shapedNoteLead({
      rows: [row('The Lakes', false), row('Dales', false)], leadRegion: 'The Lakes',
    })).toBeNull();
  });

  it('is null when the lead region is not listed', () => {
    expect(shapedNoteLead({ rows: [row('Dales', true)], leadRegion: 'The Lakes' })).toBeNull();
  });

  it('is null when the lead region itself has picks', () => {
    expect(shapedNoteLead({
      rows: [row('The Lakes', true), row('Dales', true)], leadRegion: 'The Lakes',
    })).toBeNull();
  });

  it('names a lead region with no picks without its leading article', () => {
    expect(shapedNoteLead({
      rows: [row('Dales', true), row('The Lakes', false)], leadRegion: 'The Lakes',
    })).toBe('Lakes');
  });

  it('strips an article from the start of the name only', () => {
    expect(shapedNoteLead({
      rows: [row('Dales', true), row('Loathe the Moors', false)], leadRegion: 'Loathe the Moors',
    })).toBe('Loathe the Moors');
  });

  it('strips a leading "The" in any case', () => {
    expect(shapedNoteLead({
      rows: [row('Dales', true), row('THE Moors', false)], leadRegion: 'THE Moors',
    })).toBe('Moors');
  });

  it('does not treat a name that merely begins with the letters "the" as an article', () => {
    expect(shapedNoteLead({
      rows: [row('Dales', true), row('Theakston', false)], leadRegion: 'Theakston',
    })).toBe('Theakston');
  });
});

describe('shapedWindowPanelNote — the article in the sentence', () => {
  const row = (name, hasPicks, tier = 'MAYBE', verdictLabel = 'Maybe') => ({
    name, hasPicks, tier, verdictLabel,
  });

  it('lower-cases a leading "The" in both region names', () => {
    expect(shapedWindowPanelNote({
      rows: [row('The Dales', true, 'WORTH_IT', 'Worth it'), row('The Lakes', false)],
      leadRegion: 'The Lakes',
    })).toBe('Best in the Lakes, outside your sub-regions. In your sub-regions, the Dales is Worth it.');
  });

  it('adds "too" only when both regions share a tier, and leaves a mid-name "the" alone', () => {
    expect(shapedWindowPanelNote({
      rows: [row('Loathe the Moors', true), row('Dales', false)],
      leadRegion: 'Dales',
    })).toBe('Best in Dales, outside your sub-regions. In your sub-regions, Loathe the Moors is Maybe too.');
  });
});

describe('buildRegionLocationRows — malformed and partial input', () => {
  it('answers no rows for a missing, empty or unknown region name', () => {
    const { rows } = fixture();
    const points = [{ name: 'Nameless' }, { id: 3, name: 'Elsewhere', rid: undefined, r: [4] }];
    expect(rows({ regionName: undefined, points })).toEqual([]);
    expect(rows({ regionName: '', points })).toEqual([]);
    expect(rows({ regionName: 'Dales' })).toEqual([]);
  });

  it('answers no rows for a non-array points list', () => {
    const { rows } = fixture();
    expect(rows({ points: null })).toEqual([]);
    expect(rows({ points: 'Lakes' })).toEqual([]);
  });

  it('skips null points and points with no rating array', () => {
    const { rows } = fixture();
    const result = rows({
      points: [null, { id: 9, name: 'Bare', rid: 'Lakes' }, { id: 1, name: 'Ashness', rid: 'Lakes', r: [5] }],
    });
    expect(result.map((r) => r.name)).toEqual(['Ashness']);
  });

  it('reads a drive map that is not a Map as no drive times', () => {
    const { rows } = fixture();
    const [row] = rows({ driveMap: {} });
    expect(row).toMatchObject({
      driveMinutes: null, driveLabel: null, leaveTime: null, leaveDayWord: null,
    });
  });

  it('names the day of a departure that falls before the event day, and the served tide state', () => {
    const { rows } = fixture();
    const [row] = rows();
    expect(row).toMatchObject({
      id: 1,
      name: 'Ashness',
      rating: 5,
      driveMinutes: 120,
      driveLabel: '2h',
      leaveDayWord: 'Wed',
      tideTier: 'match',
      tideState: 'HIGH',
      tideShortfall: null,
    });
    expect(row.leaveTime).toMatch(/^\d\d:\d\d$/);
  });

  it('gives a same-day departure no day word', () => {
    const { rows } = fixture();
    const [row] = rows({
      points: [{ id: 2, name: 'Castlerigg', rid: 'Lakes', r: [4] }],
      driveMap: new Map([[2, { driveMinutes: 60 }]]),
    });
    expect(row.leaveDayWord).toBeNull();
    expect(row.leaveTime).toMatch(/^\d\d:\d\d$/);
    expect(row.driveLabel).toBe('1h');
  });

  it('gives a point with no drive entry a null label', () => {
    const { rows } = fixture();
    const [row] = rows({ driveMap: new Map() });
    expect(row.driveMinutes).toBeNull();
    expect(row.driveLabel).toBeNull();
  });
});

/** Score and tide indexes built by the real builders, with a one-location window to look up. */
function fixture() {
  const scoreIndex = buildScoreIndex([
    {
      locationId: 1, locationName: 'Ashness', date: DATE, targetType: 'SUNSET', rating: 5,
      goldenHourEnd: `${DATE}T00:30:00`,
    },
    {
      locationId: 2, locationName: 'Castlerigg', date: DATE, targetType: 'SUNSET', rating: 4,
      goldenHourEnd: `${DATE}T16:00:00`,
    },
  ]);
  const tideIndex = tideIndexOf([{
    date: DATE,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName: 'Lakes',
        slots: [{
          locationId: 1, locationName: 'Ashness', rating: 5, tideState: 'HIGH', tideAligned: true,
          tideOnTheLight: true,
        }],
      }],
    }],
  }]);
  const rows = (over = {}) => buildRegionLocationRows({
    regionName: 'Lakes',
    points: [{ id: 1, name: 'Ashness', rid: 'Lakes', r: [5] }],
    driveMap: new Map([[1, { driveMinutes: 120 }]]),
    scoreIndex,
    tideIndex,
    date: DATE,
    targetType: 'SUNSET',
    ...over,
  });
  return { rows };
}
