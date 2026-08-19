import { describe, it, expect } from 'vitest';
import {
  POINT_SCORE_INDEX, buildHeatPointSets, buildHeatSpots, heatPointsFor, windowKey,
} from '../utils/heatSpots.js';

/**
 * The heat field's catalogue join (plan P1, §4.2).
 *
 * Every filter under test here exists because the ported kernel is unforgiving in a specific,
 * silent way: it reads a null score as ZERO rather than skipping the point, and it tests
 * `opts.focus` for truthiness. So "the join drops X" is never tidiness — each drop is the only
 * thing standing between a missing measurement and a plausible-looking cold patch on a map.
 */

/** Two windows, so a per-window rule can be told apart from a per-spot one. */
const EVENTS = [
  { date: '2026-08-19', targetType: 'SUNSET' },
  { date: '2026-08-20', targetType: 'SUNRISE' },
];

function location(overrides = {}) {
  return {
    id: 1,
    name: 'Bamburgh',
    lat: 55.608,
    lon: -1.709,
    regionName: 'North East',
    bortleClass: 3,
    ...overrides,
  };
}

function row(overrides = {}) {
  return {
    locationId: 1,
    locationName: 'Bamburgh',
    date: '2026-08-19',
    targetType: 'SUNSET',
    rating: 4,
    ...overrides,
  };
}

describe('buildHeatSpots — the join', () => {
  it('gives every rendered window its own slot, in RENDER order not row order', () => {
    // The rows are supplied in the reverse of the event order on purpose: written to read rows
    // positionally, the join would return [2, 4] and a same-order fixture could not tell.
    const spots = buildHeatSpots([location()], [
      row({ date: '2026-08-20', targetType: 'SUNRISE', rating: 2 }),
      row({ rating: 4 }),
    ], EVENTS);

    expect(spots).toHaveLength(1);
    expect(spots[0].scores).toEqual([4, 2]);
  });

  it('leaves a window unscored rather than borrowing the other window\'s rating', () => {
    // The failure this prevents is a spot painting last night's sunset over tomorrow's sunrise.
    const spots = buildHeatSpots([location()], [row({ rating: 5 })], EVENTS);

    expect(spots[0].scores).toEqual([5, null]);
  });

  it('matches on locationId even when the location has been renamed since scoring', () => {
    // A name is a display string an admin can edit; an id is not. This is the whole reason P1
    // retains the raw rows — the provider's name-keyed map cannot answer this.
    const spots = buildHeatSpots(
      [location({ name: 'Bamburgh Castle' })],
      [row({ locationName: 'Bamburgh', rating: 3 })],
      EVENTS,
    );

    expect(spots[0].scores[0]).toBe(3);
  });

  it('falls back to the name when the row carries no id', () => {
    const spots = buildHeatSpots([location()], [row({ locationId: null, rating: 5 })], EVENTS);

    expect(spots[0].scores[0]).toBe(5);
  });

  it('lets an id match with no rating END the lookup, rather than falling through to a name', () => {
    // Id-first only means something if an id HIT is an answer. A row that exists and is unscored
    // says "we looked at this location for this window and it has no rating" — falling through
    // would let a same-named row from a different location supply one.
    // Row ORDER is load-bearing. `putFirst` is first-wins, so with the unrated row first the
    // name index would hold null as well and a fall-through implementation would return null
    // too — the test would pass against the very code it forbids (verified: it did). The rated
    // same-named row goes first, so the name index holds 5 and only an id hit that ENDS the
    // lookup can produce null.
    const spots = buildHeatSpots([location()], [
      row({ locationId: 99, locationName: 'Bamburgh', rating: 5 }),
      row({ rating: null }),
    ], EVENTS);

    expect(spots[0].scores[0]).toBeNull();
  });

  it('drops a location with no region', () => {
    // The region is the field's focus key and the rail's unit: an unregioned spot could be
    // painted but never selected, named or counted.
    expect(buildHeatSpots([location({ regionName: null })], [row()], EVENTS)).toEqual([]);
  });

  it('drops a location whose region name is blank or whitespace', () => {
    // '' is FALSY, and the kernel tests opts.focus for truthiness — a blank region id silently
    // paints the unfocused field when that region is selected.
    expect(buildHeatSpots([location({ regionName: '' })], [row()], EVENTS)).toEqual([]);
    expect(buildHeatSpots([location({ regionName: '   ' })], [row()], EVENTS)).toEqual([]);
  });

  it('keeps the region name byte-identical, so it still joins the briefing payload', () => {
    // Deliberately NOT trimmed. The focus key and the region rail's labels come from
    // `BriefingRegion.regionName` on a different payload, which nothing normalises — so a trim
    // here would make " North East" on the rail miss "North East" on every point, and the kernel
    // answers a focus matching nothing by fading the WHOLE field to transparent.
    const [spot] = buildHeatSpots([location({ regionName: ' North East ' })], [row()], EVENTS);

    expect(spot.regionName).toBe(' North East ');
    expect(spot.rid).toBe(' North East ');
  });

  it('exposes rid and regionName as the same string, for centroid and for the rail', () => {
    // `centroid(spots, rid, project)` filters on `s.rid`; the rail prints `regionName`. One
    // value, two names, set once — so a label can be positioned from the whole catalogue rather
    // than from one window's scored subset.
    const [spot] = buildHeatSpots([location()], [row()], EVENTS);

    expect(spot.rid).toBe(spot.regionName);
  });

  it.each([
    ['null', null],
    ['undefined', undefined],
    ['NaN', NaN],
    ['Infinity', Infinity],
    ['a numeric string', '55.608'],
  ])('drops a location whose latitude is %s', (_label, lat) => {
    // Number(null) is 0 and Number('') is 0, so a coercing parse would put this location at
    // (0, 0) — clipped away invisibly on the geo host and painted off West Africa on Leaflet.
    // A NaN additionally poisons centroid() for its whole region.
    expect(buildHeatSpots([location({ lat })], [row()], EVENTS)).toEqual([]);
  });

  it('drops a location with no longitude', () => {
    expect(buildHeatSpots([location({ lon: null })], [row()], EVENTS)).toEqual([]);
  });

  it.each([
    ['a woodland-only site', ['WOODLAND']],
    ['a bluebell wood', ['BLUEBELL']],
    ['a bluebell wood in season', ['WOODLAND', 'BLUEBELL']],
    ['a wildlife hide', ['WILDLIFE']],
  ])('withholds the rating of %s — it is not a sky score', (_label, locationType) => {
    // A canopy rating runs on INVERTED polarity: a woodland GO means heavy cloud and mist. Blend
    // it into the field and a wood rated 5 blooms gold over sky locations rated 1, on precisely
    // the misty dawn the sky is at its worst. Every other aggregate signal already excludes it —
    // the map's cluster ramp, the v2 spot strip, and this phase's own BriefingRegion.bestRating.
    const spots = buildHeatSpots(
      [location({ locationType })], [row({ rating: 5 })], EVENTS,
    );

    expect(spots).toHaveLength(1);
    expect(spots[0].skySubject).toBe(false);
    expect(spots[0].scores).toEqual([null, null]);
    expect(heatPointsFor(spots, 0)).toEqual([]);
  });

  it.each([
    ['a landscape viewpoint', ['LANDSCAPE']],
    ['a seascape', ['SEASCAPE']],
    ['a waterfall', ['WATERFALL']],
    ['a wood that is ALSO a landscape viewpoint', ['WOODLAND', 'LANDSCAPE']],
    ['an untyped location', []],
  ])('paints %s — its rating is a sky score', (_label, locationType) => {
    // The mirror of the backend's own sky-prompt gate, including its rule that an untyped
    // location counts, and including WATERFALL — whose rating IS produced by the sky prompt, so
    // excluding it here would make the field disagree with the `best N★` printed beside it.
    const spots = buildHeatSpots([location({ locationType })], [row({ rating: 5 })], EVENTS);

    expect(spots[0].skySubject).toBe(true);
    expect(spots[0].scores[0]).toBe(5);
  });

  it('keeps a location that no window has scored', () => {
    // It is a real place, not a data error, and windowPoints withdraws it per window. Dropping
    // it here would take it out of the region rail and the roster count too.
    const spots = buildHeatSpots([location()], [], EVENTS);

    expect(spots).toHaveLength(1);
    expect(spots[0].scores).toEqual([null, null]);
  });

  it.each([
    ['0', 0],
    ['6', 6],
    ['a fraction', 3.5],
    ['a string', '4'],
  ])('treats an out-of-contract rating of %s as unscored', (_label, rating) => {
    // 1–5, the same bound RatingValidator applies server-side. A 0 would paint the ramp's
    // bottom as though it were a measured 1★; a 6 would clamp to the top and paint GO green.
    expect(buildHeatSpots([location()], [row({ rating })], EVENTS)[0].scores[0]).toBeNull();
  });

  it('carries id, name, coordinates and bortleClass through for the rail and the dark-sky filter', () => {
    const [spot] = buildHeatSpots([location()], [row()], EVENTS);

    expect(spot).toMatchObject({
      id: 1, name: 'Bamburgh', lat: 55.608, lng: -1.709, regionName: 'North East', bortleClass: 3,
    });
  });

  it('renames lon to lng, which is what the kernel and d3 read', () => {
    const [spot] = buildHeatSpots([location({ lon: -2.5 })], [], EVENTS);

    expect(spot.lng).toBe(-2.5);
    expect(spot.lon).toBeUndefined();
  });

  it('defaults a missing bortleClass to null rather than leaving it undefined', () => {
    expect(buildHeatSpots([location({ bortleClass: undefined })], [], EVENTS)[0].bortleClass).toBeNull();
  });

  it('returns an empty scores array when no windows are rendered', () => {
    // The legacy-payload degrade: renderedEvents can be absent, and the provider then hands on
    // whatever its own walk produced — including nothing.
    expect(buildHeatSpots([location()], [row()], [])[0].scores).toEqual([]);
  });

  it.each([
    ['locations', () => buildHeatSpots(null, [row()], EVENTS)],
    ['score rows', () => buildHeatSpots([location()], null, EVENTS)],
    ['rendered events', () => buildHeatSpots([location()], [row()], null)],
  ])('survives a null %s rather than throwing', (_label, call) => {
    expect(call).not.toThrow();
  });

  it('ignores a score row with no date or target type instead of indexing it under "undefined"', () => {
    // The guard only bites when a rendered event is malformed the same way, because that is the
    // only time the two can key to the same string. An exotic fixture, but the alternative is a
    // guard no test can fail: with well-formed events the row simply misses the rendered-window
    // filter and the assertion holds either way.
    const malformedEvents = [{ date: undefined, targetType: undefined }];

    const spots = buildHeatSpots([location()], [row({ date: null, targetType: null, rating: 5 })],
      malformedEvents);

    expect(spots[0].scores).toEqual([null]);
  });
});

describe('heatPointsFor — what the kernel is handed', () => {
  const SPOTS = buildHeatSpots(
    [location(), location({ id: 2, name: 'Alnmouth', regionName: 'Northumberland' })],
    [row({ rating: 4 }), row({ locationId: 2, locationName: 'Alnmouth', rating: 2 }),
      row({ locationId: 2, locationName: 'Alnmouth', date: '2026-08-20', targetType: 'SUNRISE', rating: 5 })],
    EVENTS,
  );

  it('withdraws a spot from the windows it is unscored in, and only those', () => {
    // THE load-bearing filter. heatField.js reads a null score as zero: an unscored spot left in
    // would drag its neighbourhood to 1★ AND spend its weight on the coverage clamp, so the map
    // would show a confident cold patch where the truth is "not evaluated".
    expect(heatPointsFor(SPOTS, 0).map((p) => p.name)).toEqual(['Bamburgh', 'Alnmouth']);
    expect(heatPointsFor(SPOTS, 1).map((p) => p.name)).toEqual(['Alnmouth']);
  });

  it('carries the whole point the kernel projects from, coordinates included', () => {
    // Asserted as a WHOLE object because the per-field assertions around it all happened to skip
    // lat/lng — and a point without them projects to NaN, which paints nothing and poisons its
    // region's centroid. Verified: dropping `lat`/`lng` from the pushed object left every other
    // test in this file green.
    expect(heatPointsFor(SPOTS, 1)).toEqual([{
      id: 2, name: 'Alnmouth', lat: 55.608, lng: -1.709, rid: 'Northumberland', r: [5],
    }]);
  });

  it('scores every point at POINT_SCORE_INDEX, whichever window it was built for', () => {
    // The index cannot be mismatched: the points carry a one-element r, so passing the wrong
    // window number to drawGeo is impossible rather than merely unlikely.
    expect(heatPointsFor(SPOTS, 1)[0].r[POINT_SCORE_INDEX]).toBe(5);
    expect(heatPointsFor(SPOTS, 1)[0].r).toHaveLength(1);
  });

  it('keys rid on the region name, which the join guarantees is never falsy', () => {
    // opts.focus is truthiness-tested in the kernel, so a falsy rid means "focus does nothing",
    // silently. heatSpots drops blank region names for exactly this reason.
    const rids = heatPointsFor(SPOTS, 0).map((p) => p.rid);

    expect(rids).toEqual(['North East', 'Northumberland']);
    expect(rids.every(Boolean)).toBe(true);
  });

  it('returns nothing for a window index past the end of the score vector', () => {
    expect(heatPointsFor(SPOTS, 9)).toEqual([]);
  });

  it('survives a null spot list', () => {
    expect(heatPointsFor(null, 0)).toEqual([]);
  });
});

describe('buildHeatPointSets', () => {
  const SPOTS = buildHeatSpots([location()], [row({ rating: 4 })], EVENTS);

  it('keys each window by the same key the shell addresses it with', () => {
    // `card.key` is `${date}:${targetType}`. A positional array would diverge from the card list
    // the moment a travel day is dropped from it, and would hand the caller an integer that
    // looks exactly like the kernel's `win` argument.
    const sets = buildHeatPointSets(SPOTS, EVENTS);

    expect([...sets.keys()]).toEqual(['2026-08-19:SUNSET', '2026-08-20:SUNRISE']);
    expect(sets.get(windowKey('2026-08-19', 'SUNSET'))).toHaveLength(1);
    expect(sets.get(windowKey('2026-08-20', 'SUNRISE'))).toEqual([]);
  });

  it('puts each window\'s OWN score in its entry', () => {
    const spots = buildHeatSpots([location()], [
      row({ rating: 4 }),
      row({ date: '2026-08-20', targetType: 'SUNRISE', rating: 2 }),
    ], EVENTS);

    const sets = buildHeatPointSets(spots, EVENTS);

    expect(sets.get('2026-08-19:SUNSET')[0].r[POINT_SCORE_INDEX]).toBe(4);
    expect(sets.get('2026-08-20:SUNRISE')[0].r[POINT_SCORE_INDEX]).toBe(2);
  });

  it('returns an empty map when no windows are rendered', () => {
    expect(buildHeatPointSets(SPOTS, []).size).toBe(0);
    expect(buildHeatPointSets(SPOTS, null).size).toBe(0);
  });

  it('skips a malformed event rather than keying it under "undefined"', () => {
    const sets = buildHeatPointSets(SPOTS, [{ date: '2026-08-19', targetType: 'SUNSET' }, {}]);

    expect([...sets.keys()]).toEqual(['2026-08-19:SUNSET']);
  });
});
