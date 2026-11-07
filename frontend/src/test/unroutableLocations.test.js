import { describe, it, expect } from 'vitest';
import {
  NEEDS_DRIVE_POINT, driveFlagText, noRouteText, unroutableById,
} from '../utils/unroutableLocations.js';

const ENTRY = {
  locationId: 7, name: 'Helvellyn', unroutableHomes: 3, judgedHomes: 3, unroutableBases: 2, judgedBases: 4,
};

describe('noRouteText', () => {
  it('names both kinds with their denominators', () => {
    expect(noRouteText(ENTRY)).toBe('no route from 3 of 3 homes, 2 of 4 bases');
  });

  it('leaves out a kind with none unroutable', () => {
    expect(noRouteText({ ...ENTRY, unroutableBases: 0, judgedBases: 4 })).toBe('no route from 3 of 3 homes');
    expect(noRouteText({ ...ENTRY, unroutableHomes: 0, judgedHomes: 5 })).toBe('no route from 2 of 4 bases');
  });

  it('uses the singular for a denominator of one', () => {
    expect(noRouteText({ ...ENTRY, unroutableHomes: 1, judgedHomes: 1, unroutableBases: 1, judgedBases: 1 }))
      .toBe('no route from 1 of 1 home, 1 of 1 base');
  });
});

describe('driveFlagText', () => {
  it('leads with the admin wording', () => {
    expect(driveFlagText(ENTRY)).toBe(`${NEEDS_DRIVE_POINT} · no route from 3 of 3 homes, 2 of 4 bases`);
  });
});

describe('unroutableById', () => {
  it('indexes the rows by location id', () => {
    const index = unroutableById([ENTRY]);
    expect(index.get(7)).toBe(ENTRY);
    expect(index.has(8)).toBe(false);
  });

  it('treats a missing payload as empty', () => {
    expect(unroutableById(null).size).toBe(0);
    expect(unroutableById(undefined).size).toBe(0);
  });
});
