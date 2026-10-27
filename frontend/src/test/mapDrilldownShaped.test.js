/**
 * `utils/mapDrilldown.js`, for a body the server shaped by a Lite reader's picks (sub-regions S5a-2): the
 * window panel's rows (regions with picks first, then verdict tier, then name; the served `picksBestRating`
 * for the best column) and its one note. An unshaped call is pinned to be untouched.
 */
import { describe, it, expect } from 'vitest';
import {
  buildPanelRegionRows, shapedWindowPanelNote, shapedNoteLead, windowPanelNote,
} from '../utils/mapDrilldown.js';

const DATE = '2026-01-15';

const rec = (regionName, displayVerdict, extra = {}) => ({
  regionName, displayVerdict, scoredLocationCount: 3, ...extra,
});
const RECORDS = [
  rec('The Lake District', 'MAYBE', { picksBestRating: 4 }),
  rec('The Yorkshire Dales', 'WORTH_IT'),
  rec('Northumberland & Tyneside', 'WORTH_IT', { picksBestRating: 5 }),
  rec('North York Moors', 'MAYBE'),
];
const INDEX = new Map(RECORDS.map((r) => [`${DATE}|SUNSET|${r.regionName}`, r]));
const NAMES = RECORDS.map((r) => r.regionName);

const summary = (places, allowedPlaces, skyOutside) => ({ places, allowedPlaces, skyOutside });
const PICKS = new Map([
  ['The Lake District', summary(10, 4, 5)],
  ['The Yorkshire Dales', summary(62, 0)],
  ['Northumberland & Tyneside', summary(21, 21)],
  ['North York Moors', summary(36, 0)],
]);

const SPOTS = [
  { id: 1, regionName: 'Northumberland & Tyneside', skySubject: true },
  { id: 2, regionName: 'The Lake District', skySubject: true },
  { id: 3, regionName: 'The Lake District', skySubject: true },
];

const build = (regionPicks) => buildPanelRegionRows({
  index: INDEX, date: DATE, targetType: 'SUNSET', regionsInScope: NAMES, points: [], driveMap: null, spots: SPOTS, regionPicks,
});

describe('buildPanelRegionRows — a shaped body', () => {
  it('orders regions with picks first, then verdict tier, then name', () => {
    expect(build(PICKS).map((r) => r.name)).toEqual([
      'Northumberland & Tyneside', // picks, Worth it
      'The Lake District', // picks, Maybe
      'The Yorkshire Dales', // no picks, Worth it
      'North York Moors', // no picks, Maybe
    ]);
  });

  it('marks each row with its picks, its outside count and the served best, with none for a region with no pick', () => {
    const rows = new Map(build(PICKS).map((r) => [r.name, r]));

    // 6 places lie outside, 5 of them sky subjects: a picked row counts over the same group as its "k of m".
    expect(rows.get('The Lake District')).toMatchObject({
      shaped: true, hasPicks: true, outsideCount: 5, bestRating: 4,
    });
    expect(rows.get('Northumberland & Tyneside')).toMatchObject({ hasPicks: true, outsideCount: 0, bestRating: 5 });
    expect(rows.get('The Yorkshire Dales')).toMatchObject({
      shaped: true, hasPicks: false, outsideCount: 62, bestRating: null,
    });
  });

  it('takes the best column from the served picksBestRating and never from the region bestRating', () => {
    const withBoth = new Map([[`${DATE}|SUNSET|The Lake District`, rec('The Lake District', 'MAYBE', { bestRating: 5, picksBestRating: 3 })]]);
    const [row] = buildPanelRegionRows({
      index: withBoth, date: DATE, targetType: 'SUNSET', regionsInScope: ['The Lake District'], points: [], driveMap: null, spots: SPOTS, regionPicks: PICKS,
    });

    expect(row.bestRating).toBe(3);
  });

  it('keeps an allowed-but-unrated region with no best, so the panel says "not scored"', () => {
    const rows = new Map(build(PICKS).map((r) => [r.name, r]));
    expect(rows.get('The Lake District').hasPicks).toBe(true);

    const bare = new Map([[`${DATE}|SUNSET|The Lake District`, rec('The Lake District', 'MAYBE')]]);
    const [row] = buildPanelRegionRows({
      index: bare, date: DATE, targetType: 'SUNSET', regionsInScope: ['The Lake District'], points: [], driveMap: null, spots: SPOTS, regionPicks: PICKS,
    });
    expect(row.bestRating).toBeNull();
    expect(row.hasPicks).toBe(true);
  });

  it('adds nothing to a row when the body is not shaped', () => {
    const rows = build(null);

    rows.forEach((r) => {
      expect(r).not.toHaveProperty('shaped');
      expect(r).not.toHaveProperty('hasPicks');
      expect(r).not.toHaveProperty('outsideCount');
    });
  });
});

describe('shapedWindowPanelNote', () => {
  const rows = () => build(PICKS);

  it('names the lead region outside the picks and the best region with picks, with "too" for the same verdict', () => {
    const note = shapedWindowPanelNote({ rows: rows(), leadRegion: 'The Yorkshire Dales' });

    expect(note).toBe(
      'Best in the Yorkshire Dales, outside your sub-regions. In your sub-regions, Northumberland & Tyneside is Worth it too.',
    );
  });

  it('drops "too" when the verdicts differ, because it would be false', () => {
    const maybeOnly = rows().map((r) => (r.name === 'Northumberland & Tyneside' ? { ...r, hasPicks: false } : r));
    const note = shapedWindowPanelNote({ rows: maybeOnly, leadRegion: 'The Yorkshire Dales' });

    expect(note).toBe(
      'Best in the Yorkshire Dales, outside your sub-regions. In your sub-regions, the Lake District is Maybe.',
    );
  });

  it('says one sentence on the order when the lead region holds a pick of the reader', () => {
    const note = shapedWindowPanelNote({ rows: rows(), leadRegion: 'Northumberland & Tyneside' });

    expect(note).toBe('Regions with places in your sub-regions come first, then by verdict.');
  });

  it('is omitted entirely for a reader with no pick in any region', () => {
    const none = build(new Map(NAMES.map((n) => [n, summary(10, 0)])));

    expect(shapedWindowPanelNote({ rows: none, leadRegion: 'The Yorkshire Dales' })).toBe('');
    expect(shapedWindowPanelNote({ rows: [], leadRegion: null })).toBe('');
  });

  it('never says "not rated", "not scored" or a dash about a place outside the picks', () => {
    const note = shapedWindowPanelNote({ rows: rows(), leadRegion: 'The Yorkshire Dales' });

    expect(note).not.toMatch(/not rated|not scored|—|\b0\b/i);
  });

  it('names the lead region to set in bold, only in the lead-outside sentence', () => {
    expect(shapedNoteLead({ rows: rows(), leadRegion: 'The Yorkshire Dales' })).toBe('Yorkshire Dales');
    expect(shapedNoteLead({ rows: rows(), leadRegion: 'Northumberland & Tyneside' })).toBeNull();
  });

  it('leaves the unshaped note exactly as it was', () => {
    expect(windowPanelNote({ verdict: null, isSolar: true })).toMatch(/^Verdict is the strongest region/);
  });
});
