import { describe, it, expect } from 'vitest';
import { scopePoolWithinReach } from '../utils/mapReach.js';

/**
 * The scope pool narrowed to the drive tier (reach-scoped-card-plan R3): the population the Map's scoped pill, its rated
 * count and its region names describe. It is the Plan's own gate, so a place with no measured drive stays (unknown) and
 * one the reader's drives say have no route does not.
 */
const SPOTS = [
  { id: 1, name: 'Near' },
  { id: 2, name: 'Edge' },
  { id: 3, name: 'Far' },
  { id: 4, name: 'Pending' },
  { id: 5, name: 'Unroutable' },
  { id: null, name: 'No id' },
];
const ENTRIES = new Map([
  [1, { driveMinutes: 20, driveStatus: 'MEASURED' }],
  [2, { driveMinutes: 45, driveStatus: 'MEASURED' }],
  [3, { driveMinutes: 80, driveStatus: 'MEASURED' }],
  [4, { driveMinutes: null, driveStatus: 'PENDING' }],
  [5, { driveMinutes: null, driveStatus: 'UNROUTABLE' }],
]);
const entryFor = (id) => ENTRIES.get(id) ?? null;
const names = (spots) => spots.map((s) => s.name);

describe('scopePoolWithinReach', () => {
  it('keeps what is inside the tier (boundary inclusive) and drops what is beyond it', () => {
    expect(names(scopePoolWithinReach(SPOTS, entryFor, 45, true)))
      .toEqual(['Near', 'Edge', 'Pending', 'No id']);
    expect(names(scopePoolWithinReach(SPOTS, entryFor, 90, true)))
      .toEqual(['Near', 'Edge', 'Far', 'Pending', 'No id']);
  });

  it('drops an UNROUTABLE place from a measured reader but keeps a PENDING one (unknown, not out of reach)', () => {
    const kept = names(scopePoolWithinReach(SPOTS, entryFor, 150, true));
    expect(kept).not.toContain('Unroutable');
    expect(kept).toContain('Pending');
  });

  it('drops nothing as unroutable for a reader with no measured drive', () => {
    expect(names(scopePoolWithinReach(SPOTS, entryFor, 45, false))).toContain('Unroutable');
  });

  it('returns the pool itself under Any (nothing is narrowed), and tolerates a non-array', () => {
    expect(scopePoolWithinReach(SPOTS, entryFor, null, true)).toBe(SPOTS);
    expect(scopePoolWithinReach(null, entryFor, 45, true)).toBeNull();
  });

  it('reads each entry from the map in force, so an away origin\'s minutes are the ones used', () => {
    const away = new Map([[3, { driveMinutes: 30, driveStatus: 'MEASURED' }]]);
    const kept = names(scopePoolWithinReach(SPOTS, (id) => away.get(id) ?? null, 45, true));
    expect(kept).toContain('Far');
    expect(kept).toContain('Near'); // no away entry: unknown, kept
  });
});
