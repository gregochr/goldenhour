import { describe, it, expect } from 'vitest';
import {
  areaFigure, buildSearchGroups, fuzzyMatches, MAX_RESULTS_PER_GROUP, withinOneSlip,
} from '../utils/planSearch.js';

/**
 * Areas in Find and the ranking they introduced (find-and-favourites-plan.md F2): exact first, one typo forgiven
 * after, the cap applied after the merge, and an area row that carries a figure with its window and never a verdict.
 */
const WALL = {
  id: 1, name: "Hadrian's Wall", kind: 'NAMED', aliases: ['Roman Wall'], regionName: null, locationIds: [1, 2, 3],
};
const TYNEDALE = {
  id: 2, name: "Hadrian's Wall & Tynedale", kind: 'SUB_REGION', aliases: [], regionName: 'North East', locationIds: [1, 2],
};
const AREAS = [WALL, TYNEDALE];
const SPOTS = [
  { id: 1, name: 'Cawfields', regionName: 'North East' },
  { id: 2, name: 'Caw Gap', regionName: 'North East' },
  { id: 9, name: 'Hadrian Cottage Pond', regionName: 'North East' },
];
const WINDOWS = [
  {
    key: '2026-10-05:SUNSET', date: '2026-10-05', targetType: 'SUNSET', label: 'Tonight Sunset', dow: 'Mon', time: '17:41',
  },
  {
    key: '2026-10-06:SUNRISE', date: '2026-10-06', targetType: 'SUNRISE', label: 'Tue Sunrise', dow: 'Tue', time: '06:51',
  },
];

const groups = (query, extra = {}) => buildSearchGroups(query, {
  areas: AREAS, locations: SPOTS, windows: WINDOWS, ...extra,
});
const rows = (query, extra = {}) => groups(query, extra).flatMap((group) => group.rows);
const labels = (query, extra = {}) => rows(query, extra).map((row) => row.label);

describe('withinOneSlip', () => {
  it.each([
    ['hadrian', 'hadrian', true],
    ['hadrain', 'hadrian', true], // adjacent pair swapped
    ['hadrin', 'hadrian', true], // a letter missing
    ['haddrian', 'hadrian', true], // an extra letter
    ['hadrion', 'hadrian', true], // a wrong letter
    ['hadrnia', 'hadrian', false], // two slips
    ['hadr', 'hadrian', false],
  ])('%s vs %s is %s', (a, b, expected) => {
    expect(withinOneSlip(a, b)).toBe(expected);
  });
});

describe('fuzzyMatches', () => {
  it('forgives one slip in a word of 5+ letters, and needs the slip to have been used', () => {
    expect(fuzzyMatches('hadrian s wall', 'hadrain')).toBe(true);
    expect(fuzzyMatches('hadrian s wall', 'hadrian')).toBe(false);
  });

  it('keeps a word under 5 letters exact', () => {
    expect(fuzzyMatches('st marys', 'sy')).toBe(false);
    expect(fuzzyMatches('whitley bay', 'bax')).toBe(false);
    expect(fuzzyMatches('st marys', 'smt')).toBe(false);
  });

  it('wants every word of the query to be found', () => {
    expect(fuzzyMatches('hadrian s wall', 'hadrain wall')).toBe(true);
    expect(fuzzyMatches('hadrian s wall', 'hadrain moor')).toBe(false);
  });
});

describe('areas in the groups', () => {
  it('⚠️ hadrian lists both areas, the sub-region and the named area never merged, above any place', () => {
    const found = groups('hadrian');

    expect(found.map((g) => g.id)).toEqual(['areas', 'locations']);
    expect(found[0].rows.map((row) => row.label)).toEqual(["Hadrian's Wall", "Hadrian's Wall & Tynedale"]);
    expect(labels('hadrian').indexOf('Hadrian Cottage Pond')).toBeGreaterThan(1);
  });

  it('⚠️ hadrain (a typo) still finds both areas, and above the places, after the exact rows of its own group', () => {
    const found = groups('hadrain');

    expect(found.find((g) => g.id === 'areas').rows.map((row) => row.label))
      .toEqual(["Hadrian's Wall", "Hadrian's Wall & Tynedale"]);
    expect(found.find((g) => g.id === 'areas').rows.every((row) => row.marks === null)).toBe(true);
  });

  it('finds an area by its alias, and says which alias it was', () => {
    const [row] = rows('roman wall');

    expect(row).toMatchObject({ kind: 'area', label: "Hadrian's Wall" });
    expect(row.sub).toContain('also Roman Wall');
  });

  it('does not name an alias when the name itself matched', () => {
    expect(rows('hadrian')[0].sub).not.toContain('also');
  });

  it('orders the groups windows, regions, areas, places', () => {
    const regions = [{ id: 1, name: 'Hadrian Region', baseName: 'Hexham', baseLat: 55, baseLon: -2 }];

    const ids = groups('hadrian', { regions, windows: [{ ...WINDOWS[0], label: 'Hadrian Sunset' }] }).map((g) => g.id);

    expect(ids).toEqual(['windows', 'regions', 'areas', 'locations']);
  });

  it('is absent at rest: an empty query lists the windows only', () => {
    expect(groups('').map((g) => g.id)).toEqual(['windows']);
  });

  it('⚠️ caw still lists Cawfields first: the exact places come before any fuzzy one', () => {
    expect(labels('caw')[0]).toBe('Cawfields');
  });

  it('⚠️ keeps st and bay exact: a short word forgives nothing', () => {
    const spots = [
      { id: 1, name: "St Mary's Lighthouse" }, { id: 2, name: 'Whitley Bay' }, { id: 3, name: 'Seaton Sluice' },
    ];

    expect(labels('st', { areas: null, locations: spots })).toEqual(["St Mary's Lighthouse"]);
    expect(labels('bax', { areas: null, locations: spots })).toEqual([]);
    expect(labels('bay', { areas: null, locations: spots })).toEqual(['Whitley Bay']);
  });

  it('⚠️ a typo never displaces an exact match inside the cap: the cap is applied after the merge', () => {
    const exact = Array.from({ length: MAX_RESULTS_PER_GROUP }, (_, i) => ({ id: i + 1, name: `Hadrian Spot ${i + 1}` }));
    const slip = { id: 99, name: 'Hadrain Viewpoint' };

    const found = buildSearchGroups('hadrian', { locations: [slip, ...exact] }).flatMap((g) => g.rows);

    expect(found).toHaveLength(MAX_RESULTS_PER_GROUP);
    expect(found.map((row) => row.label)).not.toContain('Hadrain Viewpoint');
    expect(found.every((row) => row.marks !== undefined)).toBe(true);
  });

  it('puts a fuzzy row after every exact row, with no mark', () => {
    const found = buildSearchGroups('hadrian', {
      locations: [{ id: 1, name: 'Hadrain Viewpoint' }, { id: 2, name: 'Hadrian Spot' }],
    }).flatMap((g) => g.rows);

    expect(found.map((row) => row.label)).toEqual(['Hadrian Spot', 'Hadrain Viewpoint']);
    expect(found[0].marks).not.toBeNull();
    expect(found[1].marks).toBeNull();
  });

  it('caps a group at eight after the merge, exact rows first', () => {
    const many = Array.from({ length: 12 }, (_, i) => ({ id: i + 1, name: `Roman Camp ${i + 1}` }));

    expect(buildSearchGroups('roman', { locations: many }).flatMap((g) => g.rows)).toHaveLength(8);
  });
});

describe('an area row', () => {
  const outlook = new Map([[1, [
    {
      date: '2026-10-05', targetType: 'SUNSET', ratedMembers: 2, totalMembers: 3, gatedMembers: 1, topRating: 3,
    },
    {
      date: '2026-10-06', targetType: 'SUNRISE', ratedMembers: 3, totalMembers: 3, gatedMembers: 0, topRating: 4,
    },
  ]]]);

  it('names its kind and spot count, with no figure until the outlook lands', () => {
    const [row] = rows('hadrian');

    expect(row).toMatchObject({ glyph: '▦', action: 'Its spots', figure: null });
    expect(row.sub).toBe('Named area · 3 spots');
    expect(rows('tynedale')[0].sub).toBe('Sub-region · 2 spots');
  });

  it('carries the served figure with its window, never a verdict', () => {
    const [row] = rows('hadrian', { areaOutlook: outlook });

    expect(row.figure).toMatchObject({ value: '4★', caption: 'Tue Sunrise' });
    expect(row.figure.reason).toContain('not a verdict on the area');
    expect(row.sub).toBe('Named area · 3 spots · 3 rated');
    expect(JSON.stringify(row)).not.toMatch(/best of the area/i);
  });

  it('says how many members are outside a Lite reader\'s picks, and prints no figure when none is rated', () => {
    const gated = new Map([[1, [{
      date: '2026-10-05', targetType: 'SUNSET', ratedMembers: 0, totalMembers: 3, gatedMembers: 3,
    }]]]);

    const [row] = rows('hadrian', { areaOutlook: gated });

    expect(row.figure).toBeNull();
    expect(row.subParts.find((part) => part.tone === 'outside').text).toBe('3 outside your picks');
  });

  it('is worded "Show" on the Map', () => {
    expect(rows('hadrian', { context: 'map' })[0].action).toBe('Show');
  });

  it('absent directory is no rows and no error', () => {
    expect(rows('hadrian', { areas: null }).map((row) => row.kind)).toEqual(['location']);
  });
});

describe('areaFigure', () => {
  const windows = [
    { key: 'a:SUNSET', date: 'a', targetType: 'SUNSET', label: 'First' },
    { key: 'b:SUNRISE', date: 'b', targetType: 'SUNRISE', label: 'Second' },
    {
      key: 'c:SUNSET', date: 'c', targetType: 'SUNSET', label: 'Away', away: true,
    },
  ];
  const w = (date, targetType, topRating) => ({
    date, targetType, ratedMembers: 1, totalMembers: 2, gatedMembers: 0, topRating,
  });

  it('takes the highest top rating, the earlier window on a tie, and skips away days', () => {
    expect(areaFigure([w('a', 'SUNSET', 3), w('b', 'SUNRISE', 3), w('c', 'SUNSET', 5)], windows).label).toBe('First');
    expect(areaFigure([w('a', 'SUNSET', 2), w('b', 'SUNRISE', 4)], windows).label).toBe('Second');
  });

  it('falls back to the first window with any member counted when none is rated', () => {
    expect(areaFigure([w('b', 'SUNRISE', undefined)], windows)).toMatchObject({ label: 'Second', topRating: undefined });
  });

  it('is null when there is no outlook, or none of it lands on a window the strip shows', () => {
    expect(areaFigure(null, windows)).toBeNull();
    expect(areaFigure([], windows)).toBeNull();
    expect(areaFigure([w('z', 'SUNSET', 5)], windows)).toBeNull();
  });
});
