import { describe, it, expect } from 'vitest';
import { areaField, bestOf, ratedFromLive, ratedFromStored } from '../utils/auroraBannerScores.js';

const live = (over = {}, stars = 3) => ({
  location: { id: 1, name: 'Kielder', lat: 55.2, lon: -2.5, ...over }, stars,
});
const site = (id, name, stars, over = {}) => ({
  id, name, lat: 55, lng: -2, rid: name, stars, ...over,
});
const spot = (id, name, regionName) => ({
  id, name, lat: 55, lng: -2, regionName, rid: regionName,
});

describe('ratedFromLive — what counts as a rated, drawable, named site', () => {
  it('accepts every star from 1 to 5 and nothing outside', () => {
    expect(ratedFromLive([1, 2, 3, 4, 5].map((s) => live({}, s))).map((r) => r.stars)).toEqual([1, 2, 3, 4, 5]);
    expect(ratedFromLive([0, 6, 2.5, null, '3'].map((s) => live({}, s)))).toEqual([]);
  });

  it('rejects a coordinate that is NaN, infinite, a string or missing', () => {
    expect(ratedFromLive([live({ lat: NaN })])).toEqual([]);
    expect(ratedFromLive([live({ lon: Infinity })])).toEqual([]);
    expect(ratedFromLive([live({ lat: '55.2' })])).toEqual([]);
    expect(ratedFromLive([live({ lon: null })])).toEqual([]);
    expect(ratedFromLive([live({ lat: undefined })])).toEqual([]);
  });

  it('rejects a name that is not a string or is blank, and keeps the name untrimmed', () => {
    expect(ratedFromLive([live({ name: 123 })])).toEqual([]);
    expect(ratedFromLive([live({ name: null })])).toEqual([]);
    expect(ratedFromLive([live({ name: '   ' })])).toEqual([]);
    expect(ratedFromLive([live({ name: '' })])).toEqual([]);
    expect(ratedFromLive([live({ name: ' Kielder ' })])[0].name).toBe(' Kielder ');
  });

  it('reads a stored row the same way', () => {
    const row = {
      locationId: 4, locationName: 'Bamburgh', lat: 55.6, lon: -1.7, stars: 1, triaged: false,
    };
    expect(ratedFromStored([row])).toEqual([
      { id: 4, name: 'Bamburgh', lat: 55.6, lng: -1.7, rid: 'Bamburgh', stars: 1 },
    ]);
    expect(ratedFromStored([{ ...row, locationName: 77 }])).toEqual([]);
    expect(ratedFromStored([{ ...row, lat: NaN }])).toEqual([]);
  });
});

describe('bestOf — tie-breaks, edge by edge', () => {
  it('keeps the first of two sites that tie on stars, drive and name', () => {
    const first = site(1, 'Same', 4);
    const second = site(2, 'Same', 4);
    expect(bestOf([first, second], new Map())).toBe(first);
    expect(bestOf([second, first], new Map())).toBe(second);
  });

  it('prefers a measured drive over an unknown one whichever comes first', () => {
    const unknown = site(1, 'Unknown', 4);
    const measured = site(2, 'Measured', 4);
    const reach = new Map([[2, { driveMinutes: 20 }]]);
    expect(bestOf([unknown, measured], reach)).toBe(measured);
    expect(bestOf([measured, unknown], reach)).toBe(measured);
  });

  it('treats a non-numeric or non-finite drive as unknown', () => {
    const odd = site(1, 'Odd', 4);
    const good = site(2, 'Good', 4);
    for (const bad of ['5', NaN, Infinity, null]) {
      const reach = new Map([[1, { driveMinutes: bad }], [2, { driveMinutes: 90 }]]);
      expect(bestOf([odd, good], reach)).toBe(good);
    }
  });

  it('never looks up a drive for a site without an id', () => {
    const idless = site(null, 'Zed', 3);
    const withId = site(2, 'Amy', 3);
    const reach = new Map([[null, { driveMinutes: 5 }], [2, { driveMinutes: 30 }]]);
    expect(bestOf([idless, withId], reach)).toBe(withId);
  });

  it('copes with a missing or get-less reach map', () => {
    const a = site(1, 'Alpha', 3);
    const b = site(2, 'Beta', 3);
    expect(bestOf([b, a], null)).toBe(a);
    expect(bestOf([b, a], undefined)).toBe(a);
    expect(bestOf([b, a], {})).toBe(a);
  });

  it('breaks an equal-drive tie by name A to Z', () => {
    const a = site(1, 'Alpha', 3);
    const b = site(2, 'Beta', 3);
    const reach = new Map([[1, { driveMinutes: 30 }], [2, { driveMinutes: 30 }]]);
    expect(bestOf([b, a], reach)).toBe(a);
  });
});

describe('areaField — the name fallback is only for sites without an id', () => {
  it('does not admit a rated site that has an id because an id-less scoped spot shares its name', () => {
    const catalogue = [spot(null, 'Orphan Hide', 'Highlands')];
    const rated = [site(7, 'Orphan Hide', 5), site(null, 'Orphan Hide', 3)];
    const field = areaField(rated, catalogue, new Map(), { name: 'Highlands' });
    expect(field.spots).toEqual([rated[1]]);
    expect(field.best).toBe(rated[1]);
  });
});
