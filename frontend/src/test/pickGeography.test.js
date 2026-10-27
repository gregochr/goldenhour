/**
 * `utils/pickGeography.js` — the per-region picks facts of a shaped body (sub-regions S5a-2): counts over
 * the served `allowedLocationIds` and each place's served sub-region, never a role or a client rule.
 */
import { describe, it, expect } from 'vitest';
import {
  regionPickSummaries, pickedLineText, picksNoteText, countWord, compareNames,
} from '../utils/pickGeography.js';

const SPOTS = [
  { id: 1, name: 'Bamburgh', regionName: 'North East', subRegionId: 11, subRegionName: 'Bamburgh to Howick' },
  { id: 2, name: 'Beadnell', regionName: 'North East', subRegionId: 11, subRegionName: 'Bamburgh to Howick' },
  { id: 3, name: 'Alnmouth', regionName: 'North East', subRegionId: 12, subRegionName: 'Alnwick & the Coquet Coast' },
  { id: 4, name: 'amble', regionName: 'North East', subRegionId: 12, subRegionName: 'Alnwick & the Coquet Coast' },
  { id: 5, name: 'Wastwater', regionName: 'The Lakes', subRegionId: 20, subRegionName: 'Wasdale' },
  { id: 6, name: 'Blea Tarn', regionName: 'The Lakes', subRegionId: 21, subRegionName: 'The Langdales' },
  { id: 7, name: 'Unfiled', regionName: 'The Lakes', subRegionId: null, subRegionName: null },
];

describe('regionPickSummaries', () => {
  it('is null for a body that is not shaped, whatever else it is handed', () => {
    expect(regionPickSummaries(SPOTS, null)).toBeNull();
    expect(regionPickSummaries(SPOTS, undefined)).toBeNull();
    expect(regionPickSummaries(SPOTS, 'all')).toBeNull();
  });

  it('counts places, allowed places and the picked sub-regions per region from the served ids', () => {
    const out = regionPickSummaries(SPOTS, [1, 2, 3]);

    const ne = out.get('North East');
    expect(ne.places).toBe(4);
    expect(ne.allowedPlaces).toBe(3);
    expect(ne.pickedNames).toEqual(['Alnwick & the Coquet Coast', 'Bamburgh to Howick']);
    expect(ne.outsidePlaces.map((p) => p.name)).toEqual(['amble']);

    const lakes = out.get('The Lakes');
    expect(lakes.allowedPlaces).toBe(0);
    expect(lakes.pickedNames).toEqual([]);
    expect(lakes.places).toBe(3);
  });

  it('counts the outside places among the sky subjects separately, for a picked row\'s own group', () => {
    const spots = [
      { id: 1, name: 'a', regionName: 'R', skySubject: true },
      { id: 2, name: 'b', regionName: 'R', skySubject: true },
      { id: 3, name: 'hide', regionName: 'R', skySubject: false },
    ];
    const r = regionPickSummaries(spots, [1]).get('R');

    expect(r.places - r.allowedPlaces).toBe(2);
    expect(r.skyOutside).toBe(1);
  });

  it('lists a region sub-regions by name with their place counts, and leaves a place with none out of the list', () => {
    const lakes = regionPickSummaries(SPOTS, []).get('The Lakes');

    expect(lakes.subRegions.map((s) => [s.name, s.places])).toEqual([['The Langdales', 1], ['Wasdale', 1]]);
    expect(lakes.places).toBe(3);
    expect(lakes.outsidePlaces.map((p) => p.name)).toEqual(['Blea Tarn', 'Unfiled', 'Wastwater']);
  });

  it('is a shaped body with nothing allowed for an empty list: every place outside', () => {
    const out = regionPickSummaries(SPOTS, []);

    expect([...out.values()].every((r) => r.allowedPlaces === 0 && r.pickedNames.length === 0)).toBe(true);
  });

  it('never reads a role and never invents membership: a place not named by the list is outside', () => {
    const out = regionPickSummaries([{ id: 9, name: 'X', regionName: 'R', subRegionId: 1, subRegionName: 'S' }], [10]);

    expect(out.get('R').allowedPlaces).toBe(0);
  });
});

describe('the words', () => {
  it('prints the picked line singular at one and "outside your sub-regions" at none', () => {
    expect(pickedLineText(0)).toBe('outside your sub-regions');
    expect(pickedLineText(1)).toBe('1 sub-region picked');
    expect(pickedLineText(3)).toBe('3 sub-regions picked');
  });

  it('prints the legend note with the served counts, singular at one, and the settings line at none', () => {
    expect(picksNoteText({ allowed: 2, total: 260 })).toBe('Colour shows the 2 places in your sub-regions. Pro rates all 260.');
    expect(picksNoteText({ allowed: 1, total: 260 })).toBe('Colour shows the 1 place in your sub-regions. Pro rates all 260.');
    expect(picksNoteText({ allowed: 0, total: 260 })).toBe('Choose your sub-regions in Settings to see ratings.');
  });

  it('pairs a count with its noun', () => {
    expect(countWord(1, 'place', 'places')).toBe('1 place');
    expect(countWord(0, 'place', 'places')).toBe('0 places');
  });

  it('orders names case-insensitively by code unit, the server order', () => {
    expect(['b', 'A', 'a', 'B'].sort(compareNames)).toEqual(['A', 'a', 'b', 'B']);
  });
});
