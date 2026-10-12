import { describe, it, expect } from 'vitest';
import {
  FRAME_PAD_DEG, areaField, bestOf, isRealAlert, ratedFromLive, ratedFromStored,
} from '../utils/auroraBannerScores.js';
import { bbox } from '../utils/heatGeometry.js';
import { GLANCE_MINUTES } from '../utils/planningArea.js';

/**
 * The banner's score arithmetic (docs/engineering/aurora-banner-plan.md B2, §4 D3/D8). The component
 * and the hook tests render and fetch; the rules that decide WHICH site is drawn and named live here
 * so each can be failed on its own.
 */

/** A rated site, as the hook publishes one. */
const site = (id, name, stars, over = {}) => ({
  id, name, lat: 55 + (id ?? 0) / 100, lng: -2, rid: name, stars, ...over,
});
/** A catalogue spot, as the briefing provider's `heatSpots` carries one. */
const spot = (id, name, regionName, over = {}) => ({
  id, name, lat: 55, lng: -2, regionName, rid: regionName, ...over,
});
const reach = (entries) => new Map(entries.map(([id, driveMinutes]) => [id, { driveMinutes }]));

describe('bestOf — the most stars, then the shorter HOME drive, then the name', () => {
  it('returns null for an empty list', () => {
    expect(bestOf([], new Map())).toBeNull();
  });

  it('takes the most stars whatever the drive or the order', () => {
    const rated = [site(1, 'Near Three', 3), site(2, 'Far Five', 5), site(3, 'Mid Four', 4)];
    expect(bestOf(rated, reach([[1, 5], [2, 170], [3, 60]])).name).toBe('Far Five');
  });

  it('breaks a tie by the shorter drive', () => {
    const rated = [site(1, 'Far', 5), site(2, 'Near', 5)];
    expect(bestOf(rated, reach([[1, 120], [2, 30]])).name).toBe('Near');
    expect(bestOf([...rated].reverse(), reach([[1, 120], [2, 30]])).name).toBe('Near');
  });

  it('sorts an unknown drive after every measured one', () => {
    const rated = [site(1, 'Aaa Unmeasured', 5), site(2, 'Zzz Measured', 5)];
    expect(bestOf(rated, reach([[2, 170]])).name).toBe('Zzz Measured');
  });

  it('treats a null or non-finite drive as unknown', () => {
    const rated = [site(1, 'Aaa', 5), site(2, 'Bbb', 5)];
    expect(bestOf(rated, new Map([[1, { driveMinutes: null }], [2, { driveMinutes: 40 }]])).name).toBe('Bbb');
    expect(bestOf(rated, new Map([[1, { driveMinutes: NaN }], [2, { driveMinutes: 40 }]])).name).toBe('Bbb');
  });

  it('breaks a full tie by name A to Z, whatever the input order', () => {
    const rated = [site(1, 'Whitby', 4), site(2, 'Alnmouth', 4)];
    expect(bestOf(rated, new Map()).name).toBe('Alnmouth');
    expect(bestOf([...rated].reverse(), new Map()).name).toBe('Alnmouth');
  });

  it('reads the drive from the map it is given — a site with no id has no drive', () => {
    const rated = [site(null, 'Aaa No Id', 5), site(2, 'Bbb', 5)];
    expect(bestOf(rated, reach([[2, 170]])).name).toBe('Bbb');
  });
});

describe('ratedFromLive / ratedFromStored — what counts as a rated site', () => {
  it('reads a live row\'s nested location', () => {
    expect(ratedFromLive([{ location: { id: 4, name: 'Kielder', lat: 55.2, lon: -2.6 }, stars: 4 }]))
      .toEqual([{ id: 4, name: 'Kielder', lat: 55.2, lng: -2.6, rid: 'Kielder', stars: 4 }]);
  });

  it('reads a stored row\'s flat fields', () => {
    expect(ratedFromStored([{ locationId: 4, locationName: 'Kielder', lat: 55.2, lon: -2.6, stars: 4, triaged: false }]))
      .toEqual([{ id: 4, name: 'Kielder', lat: 55.2, lng: -2.6, rid: 'Kielder', stars: 4 }]);
  });

  it('refuses a triaged stored row but not a stored row that merely lacks the flag', () => {
    const base = { locationId: 4, locationName: 'Kielder', lat: 55.2, lon: -2.6, stars: 4 };
    expect(ratedFromStored([{ ...base, triaged: true }])).toEqual([]);
    expect(ratedFromStored([{ ...base }])).toHaveLength(1);
  });

  it.each([null, 0, 6, 2.5, '4', Number.NaN])('refuses stars %s', (stars) => {
    expect(ratedFromLive([{ location: { id: 1, name: 'X', lat: 55, lon: -2 }, stars }])).toEqual([]);
  });

  it('refuses a row with no name or no usable coordinate — it can be neither drawn nor named', () => {
    expect(ratedFromLive([{ location: { id: 1, name: '', lat: 55, lon: -2 }, stars: 4 }])).toEqual([]);
    expect(ratedFromLive([{ location: { id: 1, name: 'X', lat: '55', lon: -2 }, stars: 4 }])).toEqual([]);
    expect(ratedFromLive([{ stars: 4 }, null])).toEqual([]);
  });

  it('answers null — a failed read, not "no rated site" — for a body that is not a list', () => {
    expect(ratedFromLive(undefined)).toBeNull();
    expect(ratedFromLive('<html>captive portal</html>')).toBeNull();
    expect(ratedFromStored({})).toBeNull();
    expect(ratedFromStored(null)).toBeNull();
    expect(ratedFromLive([])).toEqual([]);
  });
});

describe('isRealAlert — a live or forecast MODERATE/STRONG alert, never a simulated one', () => {
  it.each([
    [{ level: 'MODERATE' }, true],
    [{ level: 'STRONG', simulated: false }, true],
    [{ level: 'STRONG', simulated: true }, false],
    [{ level: 'MINOR' }, false],
    [{ level: 'QUIET' }, false],
    [{}, false],
    [null, false],
    [undefined, false],
  ])('%j -> %s', (status, expected) => {
    expect(isRealAlert(status)).toBe(expected);
  });
});

describe('areaField — the planning area decides what is drawn, named and framed (D8)', () => {
  const heat = [
    spot(1, 'Kielder', 'Northumberland'),
    spot(2, 'Bamburgh', 'Northumberland'),
    spot(3, 'Skye', 'Highlands'),
  ];
  // Northumberland 60 minutes away, the Highlands measured beyond the glance limit.
  const reachById = reach([[1, 60], [2, 70], [3, GLANCE_MINUTES + 120]]);

  it('is null when nothing is rated', () => {
    expect(areaField([], heat, reachById, null)).toBeNull();
    expect(areaField(undefined, heat, reachById, null)).toBeNull();
  });

  it('names the best IN-AREA site although a better-rated one lies outside', () => {
    const rated = [site(3, 'Skye', 5), site(1, 'Kielder', 4)];
    const field = areaField(rated, heat, reachById, null);
    expect(field.best.name).toBe('Kielder');
    expect(field.spots.map((s) => s.name)).toEqual(['Kielder']);
  });

  it('draws only in-area sites', () => {
    const rated = [site(3, 'Skye', 5), site(1, 'Kielder', 4), site(2, 'Bamburgh', 3)];
    expect(areaField(rated, heat, reachById, null).spots.map((s) => s.name)).toEqual(['Kielder', 'Bamburgh']);
  });

  it('frames the planning area\'s spots, not the rated ones', () => {
    const rated = [site(1, 'Kielder', 4)];
    const scoped = [heat[0], heat[1]];
    expect(areaField(rated, heat, reachById, null).fit).toEqual(bbox(scoped, FRAME_PAD_DEG));
    expect(FRAME_PAD_DEG).toBe(0.25);
  });

  it('is null — pending — when the only rated sites are outside the area', () => {
    expect(areaField([site(3, 'Skye', 5)], heat, reachById, null)).toBeNull();
  });

  it('counts every rated site and frames them when there is no scoped set at all', () => {
    const rated = [site(3, 'Skye', 5), site(1, 'Kielder', 4)];
    const field = areaField(rated, [], new Map(), null);
    expect(field.spots).toHaveLength(2);
    expect(field.best.name).toBe('Skye');
    expect(field.fit).toEqual(bbox(rated, FRAME_PAD_DEG));
  });

  it('joins by id first: a same-named site under another id is not in the area', () => {
    const rated = [site(99, 'Kielder', 5), site(2, 'Bamburgh', 3)];
    const field = areaField(rated, heat, reachById, null);
    expect(field.spots.map((s) => s.id)).toEqual([2]);
  });

  it('falls back to the name for a rated site that has no id', () => {
    const idless = [spot(null, 'Orphan Hide', 'Northumberland'), heat[1]];
    const rated = [site(null, 'Orphan Hide', 5), site(2, 'Bamburgh', 3), site(null, 'Stranger', 5)];
    const field = areaField(rated, idless, reach([[2, 70]]), null);
    expect(field.spots.map((s) => s.name)).toEqual(['Orphan Hide', 'Bamburgh']);
  });

  it('scopes to the origin\'s own region when the Plan origin is away', () => {
    const rated = [site(3, 'Skye', 3), site(1, 'Kielder', 5)];
    const field = areaField(rated, heat, reachById, { name: 'Highlands' });
    expect(field.spots.map((s) => s.name)).toEqual(['Skye']);
    expect(field.best.name).toBe('Skye');
  });

  it('breaks a tie with the HOME drive although the Plan origin is elsewhere', () => {
    const rated = [site(1, 'Kielder', 5), site(2, 'Bamburgh', 5)];
    // The origin is the region holding both sites; the only drive the function is handed is HOME's.
    const field = areaField(rated, heat, reach([[1, 90], [2, 20]]), { name: 'Northumberland' });
    expect(field.spots).toHaveLength(2);
    expect(field.best.name).toBe('Bamburgh');
  });
});
