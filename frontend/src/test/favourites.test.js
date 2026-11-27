import { describe, it, expect } from 'vitest';
import {
  applyStar, favouriteFirst, favouriteSets, NO_FAVOURITES, pickerEntries, starToastText,
} from '../utils/favourites.js';
import { buildSearchGroups } from '../utils/planSearch.js';

/** The pure helpers behind Your places (find-and-favourites-plan.md F4), and the honesty rule for each list. */
describe('favourites helpers', () => {
  it('reads the served body into the three sets, and an absent body into none', () => {
    const sets = favouriteSets({
      places: [{ locationId: 3 }, { locationId: 4 }], areas: [{ areaId: 9 }], locationIds: [3, 4, 5, 6],
    });
    expect([...sets.placeIds]).toEqual([3, 4]);
    expect([...sets.areaIds]).toEqual([9]);
    expect([...sets.locationIds]).toEqual([3, 4, 5, 6]);
    expect(favouriteSets(null).placeIds.size).toBe(0);
    expect(NO_FAVOURITES.locationIds.size).toBe(0);
  });

  it('reads watched dates beside the places, and they add nothing to the merged location ids', () => {
    const sets = favouriteSets({
      places: [], areas: [], locationIds: [3],
      dates: [{ key: 'king-tide:2026-12-03', createdAt: 't' }, { key: 'meteor:2026-12-14', createdAt: 't' }],
    });
    expect([...sets.dateKeys]).toEqual(['king-tide:2026-12-03', 'meteor:2026-12-14']);
    expect(sets.dates).toHaveLength(2);
    expect([...sets.locationIds]).toEqual([3]);
    // An older body with no dates, and an absent one, read as none.
    expect(favouriteSets({ places: [] }).dateKeys.size).toBe(0);
    expect(favouriteSets(null).dates).toEqual([]);
    expect(NO_FAVOURITES.dateKeys.size).toBe(0);
    expect(NO_FAVOURITES.dates).toEqual([]);
  });

  it('applyStar watches and un-watches a date by its key and touches nothing else', () => {
    const body = { places: [{ locationId: 1 }], areas: [], locationIds: [1], dates: [{ key: 'meteor:2026-12-14', createdAt: 'x' }] };
    const watched = applyStar(body, 'date', 'king-tide:2026-12-03', true);
    expect(watched.dates.map((d) => d.key)).toEqual(['meteor:2026-12-14', 'king-tide:2026-12-03']);
    expect(watched.places).toEqual(body.places);
    expect(watched.locationIds).toEqual([1]);
    expect(applyStar(watched, 'date', 'meteor:2026-12-14', false).dates.map((d) => d.key)).toEqual(['king-tide:2026-12-03']);
    expect(applyStar(watched, 'date', 'king-tide:2026-12-03', true).dates).toHaveLength(2);
    expect(applyStar({ places: [] }, 'date', 'king-tide:2026-12-03', true).dates).toEqual([{ key: 'king-tide:2026-12-03', createdAt: null }]);
  });

  it('applyStar moves only the list the toggle reads and leaves the merged ids to the server', () => {
    const body = { places: [{ locationId: 1, createdAt: 'x' }], areas: [], locationIds: [1] };
    const added = applyStar(body, 'place', 2, true);
    expect(added.places.map((p) => p.locationId)).toEqual([1, 2]);
    expect(added.locationIds).toEqual([1]);
    expect(applyStar(added, 'place', 1, false).places.map((p) => p.locationId)).toEqual([2]);
    expect(applyStar(added, 'place', 2, true).places).toHaveLength(2);
    expect(applyStar(body, 'area', 7, true).areas).toEqual([{ areaId: 7, createdAt: null }]);
    expect(applyStar(null, 'place', 5, true).places).toHaveLength(1);
  });

  it('favouriteFirst is a stable partition and leaves the order alone with no predicate or nothing bookmarked', () => {
    const rows = ['a', 'b', 'c', 'd'];
    expect(favouriteFirst(rows, (r) => r === 'c' || r === 'd')).toEqual(['c', 'd', 'a', 'b']);
    expect(favouriteFirst(rows, null)).toBe(rows);
    expect(favouriteFirst(rows, () => false)).toBe(rows);
  });

  it('pickerEntries: My places first, then one entry per bookmarked area; none when nothing is bookmarked', () => {
    const sets = { locationIds: new Set([1, 2, 3]) };
    const entries = pickerEntries(sets, [{ id: 8, name: "Hadrian's Wall", locationIds: [2, 3] }]);
    // The glyph is a field the renderer draws (`mark`), never a character in the name.
    expect(entries.map((e) => e.name)).toEqual(['My places', "Hadrian's Wall"]);
    expect(entries.every((e) => e.mark === true && !('label' in e))).toBe(true);
    expect(entries[0].count).toBe(3);
    expect(entries[1]).toMatchObject({ kind: 'area', ids: [2, 3], count: 2 });
    expect(pickerEntries({ locationIds: new Set() }, [])).toEqual([]);
    expect(pickerEntries(null, null)).toEqual([]);
    // An area with no enabled member still has its entry (the server names the members).
    expect(pickerEntries({ locationIds: new Set() }, [{ id: 1, name: 'A' }])[1].count).toBe(0);
  });

  it('words the toast for a place and for an area', () => {
    expect(starToastText('place', 'Steel Rigg', true)).toBe('Bookmarked Steel Rigg.');
    expect(starToastText('area', "Hadrian's Wall", false)).toBe("Removed the bookmark from Hadrian's Wall (area).");
    expect(starToastText('date', 'King tide · 3 Dec', true)).toBe("Watching King tide · 3 Dec. It's at the top of Further ahead.");
    expect(starToastText('date', 'King tide · 3 Dec', false)).toBe('Stopped watching King tide · 3 Dec.');
  });

  it('says where a date really went: its day in This week when it became a lane, else the top of Further ahead', () => {
    expect(starToastText('date', 'King tide · 3 Dec', true, { dow: 'Fri' })).toBe("Watching King tide · 3 Dec. It's on Fri in This week.");
    expect(starToastText('date', 'King tide · 3 Dec', true, null)).toBe("Watching King tide · 3 Dec. It's at the top of Further ahead.");
    // An un-watch ignores any home.
    expect(starToastText('date', 'King tide · 3 Dec', false, { dow: 'Fri' })).toBe('Stopped watching King tide · 3 Dec.');
  });
});

describe('the honesty rule in Find: a favourite only breaks a tie among rows that already matched', () => {
  const WINDOWS = [];
  const AREAS = [
    { id: 1, name: 'Cawfields Area', aliases: [], locationIds: [1] },
    { id: 2, name: 'Hadrian Wall', aliases: [], locationIds: [2] },
  ];
  const SPOTS = Array.from({ length: 10 }, (_, i) => ({ id: i + 1, name: `Cove ${i + 1}`, regionName: 'North' }));
  const fav = (placeIds = [], areaIds = []) => ({ placeIds: new Set(placeIds), areaIds: new Set(areaIds) });
  const ids = (groups, id) => groups.find((g) => g.id === id)?.rows.map((r) => r.spot?.id ?? r.area?.id);

  it('a bookmarked place goes first inside the matched group, before the cap of eight', () => {
    const groups = buildSearchGroups('cove', { windows: WINDOWS, locations: SPOTS, favourites: fav([10]) });
    expect(ids(groups, 'locations')).toEqual([10, 1, 2, 3, 4, 5, 6, 7]);
  });

  it('a bookmarked place that does not match the query never appears (no group, no row)', () => {
    const groups = buildSearchGroups('zzz', { windows: WINDOWS, locations: SPOTS, favourites: fav([10]) });
    expect(groups).toEqual([]);
    const groups2 = buildSearchGroups('cove 3', { windows: WINDOWS, locations: SPOTS, favourites: fav([5]) });
    expect(ids(groups2, 'locations')).toEqual([3]);
  });

  it('a bookmarked row from the fuzzy tier never outranks an exact match', () => {
    const spots = [{ id: 1, name: 'Seahouses' }, { id: 2, name: 'Seahouse Rocks' }, { id: 3, name: 'Seahoutes' }];
    // "seahouses" matches 1 exactly; "seahoutes" is one slip away. Star the fuzzy one.
    const groups = buildSearchGroups('seahouses', { windows: WINDOWS, locations: spots, favourites: fav([3]) });
    const rows = groups.find((g) => g.id === 'locations').rows;
    expect(rows[0].spot.id).toBe(1);
    expect(rows.find((r) => r.spot.id === 3)?.bookmarked).toBe(true);
    expect(rows.map((r) => r.spot.id).indexOf(3)).toBeGreaterThan(0);
  });

  it('windows and regions never take a favourite key', () => {
    const windows = [
      { key: 'a', label: 'Mon sunrise', dow: 'Mon', time: '06:00', date: '2026-10-05', targetType: 'SUNRISE' },
      { key: 'b', label: 'Tue sunrise', dow: 'Tue', time: '06:01', date: '2026-10-06', targetType: 'SUNRISE' },
    ];
    const groups = buildSearchGroups('sunrise', { windows, favourites: fav([1], [1]), areas: AREAS });
    expect(groups.find((g) => g.id === 'windows').rows.map((r) => r.windowKey)).toEqual(['a', 'b']);
  });

  it('marks bookmarked area and place rows', () => {
    const groups = buildSearchGroups('hadrian', { windows: WINDOWS, areas: AREAS, favourites: fav([], [2]) });
    expect(groups.find((g) => g.id === 'areas').rows[0].bookmarked).toBe(true);
    const plain = buildSearchGroups('hadrian', { windows: WINDOWS, areas: AREAS });
    expect(plain.find((g) => g.id === 'areas').rows[0].bookmarked).toBe(false);
  });

  it('rests with a Your places group (the mark is drawn, not typed), areas first, above the windows, capped and never ranked by rating', () => {
    const windows = [{ key: 'a', label: 'Mon sunrise', dow: 'Mon', time: '06:00', date: '2026-10-05', targetType: 'SUNRISE' }];
    const groups = buildSearchGroups('', {
      windows, areas: AREAS, locations: SPOTS, favourites: fav([4, 2], [2]),
    });
    expect(groups.map((g) => g.id)).toEqual(['favourites', 'windows']);
    expect(groups[0].title).toBe('Your places');
    expect(groups[0].mark).toBe(true);
    expect(groups[0].rows.map((r) => r.kind)).toEqual(['area', 'location', 'location']);
    expect(groups[0].rows.every((r) => r.bookmarked && r.marks === null)).toBe(true);
    expect(new Set(groups[0].rows.map((r) => r.key)).size).toBe(3);
  });

  it('rests with no Your places group for a reader with no favourites (Lite) or nothing bookmarked', () => {
    const windows = [{ key: 'a', label: 'Mon sunrise', dow: 'Mon', time: '06:00', date: '2026-10-05', targetType: 'SUNRISE' }];
    expect(buildSearchGroups('', { windows, locations: SPOTS, areas: AREAS }).map((g) => g.id)).toEqual(['windows']);
    expect(buildSearchGroups('', { windows, locations: SPOTS, favourites: fav() }).map((g) => g.id)).toEqual(['windows']);
  });
});
