import { describe, it, expect } from 'vitest';
import { buildGroundSets } from '../utils/pickScope.js';

/**
 * The Plan thumbnails' grounds (sub-regions-plan §11 S5b-1): each rendered window's outside places, every other
 * place, the allowed places nobody has rated, and whether any of the reader's places is rated. Built from the same
 * place states the Map reads.
 */

const spot = (id, states) => ({
  id, name: `Place ${id}`, lat: 55 + id / 10, lng: -1.5, rid: 'Coast', states,
});
const EVENTS = [{ date: '2026-08-04', targetType: 'SUNRISE' }, { date: '2026-08-04', targetType: 'SUNSET' }];

describe('buildGroundSets', () => {
  const spots = [
    spot(1, ['rated', 'unrated']),
    spot(2, ['unrated', 'unrated']),
    spot(3, ['gated', 'gated']),
    spot(4, ['stood', 'na']),
  ];

  it('splits each window into outside and allowed, keyed by window', () => {
    const sets = buildGroundSets(spots, EVENTS, true);

    expect([...sets.keys()]).toEqual(['2026-08-04:SUNRISE', '2026-08-04:SUNSET']);
    expect(sets.get('2026-08-04:SUNRISE').outside.map((p) => p.id)).toEqual([3]);
    expect(sets.get('2026-08-04:SUNRISE').allowed.map((p) => p.id)).toEqual([1, 2, 4]);
  });

  it('carries only the points a kernel host needs, never a score', () => {
    const [point] = buildGroundSets(spots, EVENTS, true).get('2026-08-04:SUNRISE').outside;

    expect(Object.keys(point).sort()).toEqual(['id', 'lat', 'lng', 'name', 'rid']);
  });

  it('names the allowed places nobody has rated, for the hatch', () => {
    const sets = buildGroundSets(spots, EVENTS, true);

    expect(sets.get('2026-08-04:SUNRISE').unrated.map((p) => p.id)).toEqual([2]);
    expect(sets.get('2026-08-04:SUNSET').unrated.map((p) => p.id)).toEqual([1, 2]);
  });

  it('says whether any of the reader\'s own places is rated, per window', () => {
    const sets = buildGroundSets(spots, EVENTS, true);

    expect(sets.get('2026-08-04:SUNRISE').rated).toBe(true);
    expect(sets.get('2026-08-04:SUNSET').rated).toBe(false);
  });

  it('does not count a place outside the picks as rating the window', () => {
    const sets = buildGroundSets([spot(1, ['unrated']), spot(3, ['gated'])], [EVENTS[0]], true);

    expect(sets.get('2026-08-04:SUNRISE').rated).toBe(false);
  });

  it('says nothing about rating until the scores have loaded', () => {
    expect(buildGroundSets(spots, EVENTS, false).get('2026-08-04:SUNRISE').rated).toBeNull();
  });

  it('leaves a window with no place states out, claiming nothing', () => {
    expect(buildGroundSets([{ ...spot(1, []), states: [] }], EVENTS, true).size).toBe(0);
    expect(buildGroundSets([], EVENTS, true).size).toBe(0);
    expect(buildGroundSets(null, null, true).size).toBe(0);
  });

  it('keeps a reader with no pick: every place outside, none allowed', () => {
    const sets = buildGroundSets([spot(3, ['gated']), spot(5, ['gated'])], [EVENTS[0]], true);

    expect(sets.get('2026-08-04:SUNRISE').allowed).toEqual([]);
    expect(sets.get('2026-08-04:SUNRISE').outside).toHaveLength(2);
  });
});
