import { describe, it, expect } from 'vitest';
import {
  buildSearchGroups,
  firstSelectable,
  flattenRows,
  fold,
  matchRange,
  nextSelectable,
} from '../utils/planSearch.js';

const win = (over = {}) => ({
  key: 'k1', date: '2026-08-06', targetType: 'SUNRISE', dow: 'Thu', label: 'Thursday Sunrise',
  time: '05:22', verdictLabel: 'Maybe', away: false, ...over,
});

const index = (entries) => ({
  byId: new Map(entries.filter((e) => e.id != null).map((e) => [`${e.id}|${e.date}|${e.type}`, { rating: e.rating }])),
  byName: new Map(entries.filter((e) => e.name).map((e) => [`${e.name}|${e.date}|${e.type}`, { rating: e.rating }])),
});

const rowsOf = (groups, id) => groups.find((g) => g.id === id)?.rows ?? [];

describe('foldPlain / matchRange with stray combining marks', () => {
  it('drops a lone combining mark from the folded text', () => {
    expect(fold('á')).toBe('a');
    expect(fold('́')).toBe('');
  });
  it('returns no range when the query is not in the label', () => {
    expect(matchRange('Lake District', 'zzz')).toBeNull();
  });
  it('walks the end of the range past a decomposed accent', () => {
    expect(matchRange('Café Bar', 'cafe')).toEqual([0, 5]);
  });
  it('does not swallow the apostrophe after the match', () => {
    expect(matchRange("St Mary's", 'mary')).toEqual([3, 7]);
  });
});

describe('window rows', () => {
  it('copes with a null windows list and null cards', () => {
    expect(buildSearchGroups('', { windows: null })).toEqual([]);
    expect(rowsOf(buildSearchGroups('', { windows: [null, win()] }), 'windows')).toHaveLength(1);
  });

  it('matches a window by its weekday and event and by its date, and tolerates a card without a date', () => {
    const windows = [win(), win({ key: 'k2', date: undefined, targetType: 'SUNSET', label: 'Some Sunset', dow: 'Fri' })];
    expect(rowsOf(buildSearchGroups('thursday sunrise', { windows }), 'windows').map((r) => r.windowKey)).toEqual(['k1']);
    expect(rowsOf(buildSearchGroups('2026-08-06', { windows }), 'windows').map((r) => r.windowKey)).toEqual(['k1']);
    expect(rowsOf(buildSearchGroups('some sunset', { windows }), 'windows').map((r) => r.windowKey)).toEqual(['k2']);
  });

  it('shows the reach figure with the caption only when a drive time exists', () => {
    const windows = [win({ bestReach: { rating: 4 } })];
    const measured = new Map([[1, { driveMinutes: 30 }]]);
    const unmeasured = new Map([[1, { driveMinutes: null }], [2, null]]);
    expect(rowsOf(buildSearchGroups('', { windows, reachById: measured }), 'windows')[0].figure)
      .toEqual({ value: '4★', caption: 'in reach' });
    expect(rowsOf(buildSearchGroups('', { windows, reachById: unmeasured }), 'windows')[0].figure)
      .toEqual({ value: '4★', caption: null });
    expect(rowsOf(buildSearchGroups('', { windows }), 'windows')[0].figure)
      .toEqual({ value: '4★', caption: null });
  });

  it('has a null value when bestReach carries no rating', () => {
    const [row] = rowsOf(buildSearchGroups('', { windows: [win({ bestReach: {} })] }), 'windows');
    expect(row.figure).toEqual({ value: null, caption: null });
  });

  it('shows "no clear pick" for a withheld pick, worded by whether reach is measured', () => {
    const windows = [win({ bestReachWithheld: true })];
    const measured = new Map([[1, { driveMinutes: 30 }]]);
    expect(rowsOf(buildSearchGroups('', { windows, reachById: measured }), 'windows')[0].figure).toEqual({
      value: 'no clear pick', caption: 'in reach',
      reason: ': the top rating within reach is well above the places around it',
    });
    expect(rowsOf(buildSearchGroups('', { windows }), 'windows')[0].figure).toEqual({
      value: 'no clear pick', caption: null,
      reason: ': the top rating here is well above the places around it',
    });
  });

  it('has no figure when there is neither a pick nor a withheld pick', () => {
    expect(rowsOf(buildSearchGroups('', { windows: [win()] }), 'windows')[0].figure).toBeNull();
  });

  it('shows an away window as disabled with no action and a reason', () => {
    const [row] = rowsOf(buildSearchGroups('', { windows: [win({ away: true })] }), 'windows');
    expect(row).toMatchObject({
      disabled: true, action: null, reason: 'Not forecast — away this day', sub: '05:22 · Not forecast',
    });
    expect(row.subParts).toEqual([{ text: '05:22', tone: null }, { text: 'Not forecast', tone: null }]);
  });

  it('shows a live window as openable with its verdict', () => {
    const [row] = rowsOf(buildSearchGroups('', { windows: [win()] }), 'windows');
    expect(row).toMatchObject({ disabled: false, action: 'Open', reason: null, sub: '05:22 · Maybe' });
  });

  it('drops missing sub-line clauses', () => {
    const [row] = rowsOf(buildSearchGroups('', { windows: [win({ time: null, verdictLabel: undefined })] }), 'windows');
    expect(row.sub).toBe('');
    expect(row.subParts).toEqual([]);
  });
});

describe('region rows', () => {
  const based = { id: 7, name: 'Lake District', baseName: ' Keswick ', baseLat: 54.6, baseLon: -3.1 };
  const baseless = { id: 8, name: 'Lakeland Fringe', baseName: null, baseLat: null, baseLon: null };
  const off = { id: 9, name: 'Lakes Off', baseName: 'Ambleside', baseLat: 54.4, baseLon: -2.9, enabled: false };
  const offBaseless = { id: 10, name: 'Lakes Off Baseless', baseName: null, baseLat: null, baseLon: null, enabled: false };

  it('offers a based region as choosable, trimming its base name', () => {
    const [row] = rowsOf(buildSearchGroups('lake district', { regions: [based] }), 'regions');
    expect(row).toMatchObject({
      sub: 'Plan from Keswick', action: 'Plan from here', disabled: false, reason: null, figure: null,
    });
    expect(row.subParts).toEqual([{ text: 'Plan from Keswick', tone: null }]);
  });

  it('marks the current origin as planning now and disabled', () => {
    const [row] = rowsOf(buildSearchGroups('lake district', { regions: [based], originId: 7 }), 'regions');
    expect(row).toMatchObject({
      sub: 'Planning from here', action: 'Planning now', disabled: true, reason: "You're already planning from here",
    });
    expect(row.subParts).toEqual([{ text: 'Planning from here', tone: null }]);
  });

  it('shows a baseless region as disabled with its reason', () => {
    const [row] = rowsOf(buildSearchGroups('lakeland', { regions: [baseless] }), 'regions');
    expect(row).toMatchObject({
      sub: 'No base town set', action: null, disabled: true, reason: 'This region has no base town to plan from',
    });
    expect(row.subParts).toEqual([{ text: 'No base town set', tone: null }]);
  });

  it('shows a switched-off region, based or not, as switched off', () => {
    const rows = rowsOf(buildSearchGroups('lakes off', { regions: [off, offBaseless] }), 'regions');
    expect(rows.map((r) => [r.sub, r.reason, r.disabled, r.action])).toEqual([
      ['Switched off', 'This region is switched off', true, null],
      ['Switched off', 'This region is switched off', true, null],
    ]);
    expect(rows[0].subParts).toEqual([{ text: 'Switched off', tone: null }]);
  });

  it('copes with a null regions list and null entries, and has none at rest', () => {
    expect(buildSearchGroups('lake', { regions: null })).toEqual([]);
    expect(rowsOf(buildSearchGroups('lake', { regions: [null, based] }), 'regions')).toHaveLength(1);
    expect(rowsOf(buildSearchGroups('', { regions: [based] }), 'regions')).toEqual([]);
  });
});

describe('location rows', () => {
  const spot = { id: 1, name: 'Bamburgh Beach', regionName: 'Northumberland' };

  it('copes with a null locations list and null entries, and has none at rest', () => {
    expect(buildSearchGroups('bam', { locations: null })).toEqual([]);
    expect(rowsOf(buildSearchGroups('bam', { locations: [null, spot] }), 'locations')).toHaveLength(1);
    expect(rowsOf(buildSearchGroups('', { locations: [spot] }), 'locations')).toEqual([]);
  });

  it('builds the region, drive and outside clauses in order, toning only the outside one', () => {
    const [row] = rowsOf(buildSearchGroups('bamburgh', {
      locations: [spot], reachById: new Map([[1, { driveMinutes: 95 }]]), scopeRegionNames: ['Lake District'],
      origin: { name: 'Keswick' },
    }), 'locations');
    expect(row.sub).toBe('Northumberland · 1h 35min · outside Keswick');
    expect(row.subParts).toEqual([
      { text: 'Northumberland', tone: null },
      { text: '1h 35min', tone: null },
      { text: 'outside Keswick', tone: 'outside' },
    ]);
  });

  it('words the outside clause by the glance area when there is no origin', () => {
    const [row] = rowsOf(buildSearchGroups('bamburgh', { locations: [spot], scopeRegionNames: ['Lake District'] }), 'locations');
    expect(row.sub).toBe('Northumberland · outside your 3h area');
  });

  it('marks nothing outside for an empty or absent scope, or a spot inside it', () => {
    const inScope = rowsOf(buildSearchGroups('bamburgh', { locations: [spot], scopeRegionNames: ['Northumberland'] }), 'locations')[0];
    const empty = rowsOf(buildSearchGroups('bamburgh', { locations: [spot], scopeRegionNames: [] }), 'locations')[0];
    const absent = rowsOf(buildSearchGroups('bamburgh', { locations: [spot] }), 'locations')[0];
    [inScope, empty, absent].forEach((row) => expect(row.sub).toBe('Northumberland'));
  });

  it('does not mark a spot with no region as outside, and has an empty sub-line', () => {
    const [row] = rowsOf(buildSearchGroups('bamburgh', {
      locations: [{ id: 1, name: 'Bamburgh Beach' }], scopeRegionNames: ['Lake District'],
    }), 'locations');
    expect(row.sub).toBe('');
    expect(row.subParts).toEqual([]);
  });

  it('shows no drive for a spot with no id or no reach entry', () => {
    const noId = rowsOf(buildSearchGroups('bamburgh', {
      locations: [{ name: 'Bamburgh Beach', regionName: 'X' }], reachById: new Map([[undefined, { driveMinutes: 10 }]]),
    }), 'locations')[0];
    expect(noId.sub).toBe('X');
    const noEntry = rowsOf(buildSearchGroups('bamburgh', { locations: [spot], reachById: new Map() }), 'locations')[0];
    expect(noEntry.sub).toBe('Northumberland');
  });

  it('shows the best rated window with its label, earliest winning ties', () => {
    const windows = [
      win({ key: 'a', date: '2026-08-06', targetType: 'SUNRISE', label: 'Thursday Sunrise' }),
      win({ key: 'b', date: '2026-08-06', targetType: 'SUNSET', label: 'Thursday Sunset' }),
      win({ key: 'c', date: '2026-08-07', targetType: 'SUNSET', label: 'Friday Sunset' }),
    ];
    const scoreIndex = index([
      { id: 1, date: '2026-08-06', type: 'SUNRISE', rating: 3 },
      { id: 1, date: '2026-08-06', type: 'SUNSET', rating: 5 },
      { id: 1, date: '2026-08-07', type: 'SUNSET', rating: 5 },
    ]);
    const [row] = rowsOf(buildSearchGroups('bamburgh', { windows, locations: [spot], scoreIndex }), 'locations');
    expect(row.figure).toEqual({ value: '5★', caption: 'Thursday Sunset' });
  });

  it('builds a caption from the weekday and event when the card has no label', () => {
    const sunrise = win({ label: '', dow: 'Thu' });
    const sunset = win({ key: 's', date: '2026-08-07', targetType: 'SUNSET', label: undefined, dow: 'Fri', sunrise: false });
    const scoreIndex = index([
      { id: 1, date: '2026-08-06', type: 'SUNRISE', rating: 2 },
      { id: 1, date: '2026-08-07', type: 'SUNSET', rating: 4 },
    ]);
    expect(rowsOf(buildSearchGroups('bamburgh', { windows: [sunrise], locations: [spot], scoreIndex }), 'locations')[0].figure)
      .toEqual({ value: '2★', caption: 'Thu sunset' });
    expect(rowsOf(buildSearchGroups('bamburgh', { windows: [{ ...sunrise, sunrise: true }, sunset], locations: [spot], scoreIndex }), 'locations')[0].figure)
      .toEqual({ value: '4★', caption: 'Fri sunset' });
    expect(rowsOf(buildSearchGroups('bamburgh', { windows: [{ ...sunrise, sunrise: true }], locations: [spot], scoreIndex }), 'locations')[0].figure)
      .toEqual({ value: '2★', caption: 'Thu sunrise' });
  });

  it('falls back to the name index when the id is not indexed', () => {
    const scoreIndex = index([{ name: 'Bamburgh Beach', date: '2026-08-06', type: 'SUNRISE', rating: 4 }]);
    const [row] = rowsOf(buildSearchGroups('bamburgh', { windows: [win()], locations: [spot], scoreIndex }), 'locations');
    expect(row.figure).toEqual({ value: '4★', caption: 'Thursday Sunrise' });
  });

  it('shows no figure for an unrated hit, an away window, a null window or no window match', () => {
    const scoreIndex = index([{ id: 1, date: '2026-08-06', type: 'SUNRISE', rating: null }]);
    const figureFor = (windows, idx = scoreIndex) => rowsOf(
      buildSearchGroups('bamburgh', { windows, locations: [spot], scoreIndex: idx }), 'locations',
    )[0].figure;
    expect(figureFor([win()])).toBeNull();
    expect(figureFor([win({ away: true })], index([{ id: 1, date: '2026-08-06', type: 'SUNRISE', rating: 5 }]))).toBeNull();
    expect(figureFor([null, win({ date: '2026-09-01' })], index([{ id: 1, date: '2026-08-06', type: 'SUNRISE', rating: 5 }]))).toBeNull();
  });

  it('shows no figure without a score index or for a non sky subject, but does for one with the flag unset', () => {
    const scoreIndex = index([{ id: 1, date: '2026-08-06', type: 'SUNRISE', rating: 4 }]);
    const figure = (s, idx) => rowsOf(buildSearchGroups('bamburgh', { windows: [win()], locations: [s], scoreIndex: idx }), 'locations')[0].figure;
    expect(figure(spot, null)).toBeNull();
    expect(figure({ ...spot, skySubject: false }, scoreIndex)).toBeNull();
    expect(figure({ ...spot, skySubject: true }, scoreIndex)).toEqual({ value: '4★', caption: 'Thursday Sunrise' });
    expect(figure(spot, scoreIndex)).toEqual({ value: '4★', caption: 'Thursday Sunrise' });
  });

  it('shows no figure when the windows list is null', () => {
    const scoreIndex = index([{ id: 1, date: '2026-08-06', type: 'SUNRISE', rating: 4 }]);
    expect(rowsOf(buildSearchGroups('bamburgh', { windows: null, locations: [spot], scoreIndex }), 'locations')[0].figure)
      .toBeNull();
  });

});

describe('keyboard navigation helpers', () => {
  it('flattens null groups and groups with no rows', () => {
    expect(flattenRows(null)).toEqual([]);
    expect(flattenRows([{ id: 'a' }, { id: 'b', rows: [{ key: 1 }] }])).toEqual([{ key: 1 }]);
  });
  it('nextSelectable answers -1 for null or empty rows', () => {
    expect(nextSelectable(null, 0, 1)).toBe(-1);
    expect(nextSelectable([], 0, 1)).toBe(-1);
  });
  it('nextSelectable steps from nothing active to the first or last selectable row', () => {
    const rows = [{ disabled: false }, { disabled: true }, { disabled: false }];
    expect(nextSelectable(rows, -1, 1)).toBe(0);
    expect(nextSelectable(rows, -1, -1)).toBe(2);
  });
  it('nextSelectable skips disabled rows and wraps', () => {
    const rows = [{ disabled: false }, { disabled: true }, { disabled: false }];
    expect(nextSelectable(rows, 0, 1)).toBe(2);
    expect(nextSelectable(rows, 2, 1)).toBe(0);
    expect(nextSelectable(rows, 0, -1)).toBe(2);
  });
  it('nextSelectable stays put when nothing can be chosen', () => {
    expect(nextSelectable([{ disabled: true }, { disabled: true }], 1, 1)).toBe(1);
  });
  it('firstSelectable answers -1 for null rows and for all disabled', () => {
    expect(firstSelectable(null)).toBe(-1);
    expect(firstSelectable([{ disabled: true }])).toBe(-1);
    expect(firstSelectable([{ disabled: true }, { disabled: false }])).toBe(1);
  });
});
