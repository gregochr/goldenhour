import { describe, it, expect, beforeEach, vi } from 'vitest';

/**
 * Edge cases for `utils/planSearch.js` that the main suites leave open: the glyph column, the
 * sub-line clause list, the "no source supplied" fallbacks and the arrow-key walk's boundaries.
 *
 * <p>The module is loaded afresh in each test (`vi.resetModules()` plus a dynamic import) so its
 * module-level constants and arrow helpers are executed during a test rather than at file load.
 */

let search;

beforeEach(async () => {
  vi.resetModules();
  search = await import('../utils/planSearch.js');
});

const win = (over = {}) => ({
  key: 'k1', date: '2026-08-06', targetType: 'SUNRISE', dow: 'Thu', label: 'Thursday Sunrise',
  time: '05:22', verdictLabel: 'Maybe', away: false, ...over,
});

const rowsOf = (groups, id) => groups.find((g) => g.id === id)?.rows ?? [];

describe('the glyph column', () => {
  it('prints a distinct decorative glyph per kind of row', () => {
    const groups = search.buildSearchGroups('lake', {
      windows: [win({ label: 'Lake Sunrise' })],
      regions: [{ id: 1, name: 'Lake District', baseName: 'Keswick', baseLat: 54, baseLon: -3 }],
      locations: [{ id: 5, name: 'Lake View', regionName: 'Lake District' }],
    });
    expect(rowsOf(groups, 'windows')[0].glyph).toBe('◷');
    expect(rowsOf(groups, 'regions')[0].glyph).toBe('◎');
    expect(rowsOf(groups, 'locations')[0].glyph).toBe('◇');
  });
});

describe('the figure column on a location row', () => {
  it('formats the top rating as whole stars beside the window it came from', () => {
    const scoreIndex = {
      byId: new Map([['5|2026-08-06|SUNRISE', { rating: 4 }]]),
      byName: new Map(),
    };
    const groups = search.buildSearchGroups('lake', {
      windows: [win()],
      locations: [{ id: 5, name: 'Lake View', regionName: 'Lake District' }],
      scoreIndex,
    });
    expect(rowsOf(groups, 'locations')[0].figure).toEqual({ value: '4★', caption: 'Thursday Sunrise' });
  });

  it('has no figure at all when the windows list is null, whatever the index would answer', () => {
    const everyone = { get: () => ({ rating: 5 }) };
    const groups = search.buildSearchGroups('lake', {
      windows: null,
      locations: [{ id: 5, name: 'Lake View', regionName: 'Lake District' }],
      scoreIndex: { byId: everyone, byName: everyone },
    });
    expect(rowsOf(groups, 'locations')[0].figure).toBeNull();
  });
});

describe('the sub-line clause list', () => {
  it('keeps the region, the drive and the toned outside clause as parts, in that order', () => {
    const reachById = new Map([[5, { driveMinutes: 75 }]]);
    const groups = search.buildSearchGroups('lake', {
      locations: [{ id: 5, name: 'Lake View', regionName: 'Lake District' }],
      reachById,
      scopeRegionNames: ['Northumberland'],
      origin: { id: 2, name: 'Alnwick' },
    });
    const [row] = rowsOf(groups, 'locations');
    expect(row.subParts).toEqual([
      { text: 'Lake District', tone: null },
      { text: '1h 15min', tone: null },
      { text: 'outside Alnwick', tone: 'outside' },
    ]);
    expect(row.sub).toBe('Lake District · 1h 15min · outside Alnwick');
  });

  it('drops the drive clause when the place has no drive time, leaving the region alone', () => {
    const reachById = new Map([[5, { driveMinutes: null }]]);
    const [row] = rowsOf(search.buildSearchGroups('lake', {
      locations: [{ id: 5, name: 'Lake View', regionName: 'Lake District' }],
      reachById,
    }), 'locations');
    expect(row.subParts).toEqual([{ text: 'Lake District', tone: null }]);
    expect(row.sub).toBe('Lake District');
  });

  it('normalises a bare string window clause to a toneless part', () => {
    const [row] = rowsOf(search.buildSearchGroups('', { windows: [win()] }), 'windows');
    expect(row.subParts).toEqual([{ text: '05:22', tone: null }, { text: 'Maybe', tone: null }]);
  });
});

describe('sources that are not supplied', () => {
  it('offers no window rows when no windows key is passed at all', () => {
    expect(search.buildSearchGroups('', { locations: [{ id: 1, name: 'A' }] })).toEqual([]);
    expect(search.buildSearchGroups('')).toEqual([]);
  });
});

describe('flattenRows', () => {
  it('contributes nothing for a group that has no rows list', () => {
    expect(search.flattenRows([{ id: 'a' }, { id: 'b', rows: [{ key: 'x' }] }])).toEqual([{ key: 'x' }]);
  });

  it('answers an empty list for no groups', () => {
    expect(search.flattenRows(null)).toEqual([]);
  });
});

describe('nextSelectable boundaries', () => {
  const rows = (...disabled) => disabled.map((d, i) => ({ key: `r${i}`, disabled: d }));

  it('moves down one and up one from a middle row', () => {
    const list = rows(false, false, false, false);
    expect(search.nextSelectable(list, 1, 1)).toBe(2);
    expect(search.nextSelectable(list, 1, -1)).toBe(0);
  });

  it('wraps at both ends', () => {
    const list = rows(false, false, false);
    expect(search.nextSelectable(list, 2, 1)).toBe(0);
    expect(search.nextSelectable(list, 0, -1)).toBe(2);
  });

  it('skips disabled rows in the direction of travel', () => {
    const list = rows(false, true, true, false);
    expect(search.nextSelectable(list, 0, 1)).toBe(3);
    expect(search.nextSelectable(list, 3, -1)).toBe(0);
  });

  it('lands on the only selectable row when it is the starting row itself, after a full lap', () => {
    const list = rows(true, false, true);
    expect(search.nextSelectable(list, 1, 1)).toBe(1);
    expect(search.nextSelectable(list, 1, -1)).toBe(1);
  });

  it('from nothing active, going up reaches the first row at the end of the lap', () => {
    const list = rows(false, true);
    expect(search.nextSelectable(list, -1, -1)).toBe(0);
  });

  it('starts from the top when nothing is active and the step is forward', () => {
    const list = rows(false, false, false);
    expect(search.nextSelectable(list, -1, 1)).toBe(0);
  });

  it('treats a zero step from nothing active as the first row rather than the last', () => {
    const list = rows(false, false, false);
    expect(search.nextSelectable(list, -1, 0)).toBe(0);
  });

  it('returns the current index when nothing is selectable, and -1 for an empty list', () => {
    expect(search.nextSelectable(rows(true, true), 1, 1)).toBe(1);
    expect(search.nextSelectable([], 0, 1)).toBe(-1);
    expect(search.nextSelectable(null, 0, 1)).toBe(-1);
  });

  it('treats an empty slot in the list as selectable rather than failing', () => {
    const list = [{ key: 'a' }, null];
    expect(search.nextSelectable(list, 0, 1)).toBe(1);
  });
});
