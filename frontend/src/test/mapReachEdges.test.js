import { describe, it, expect, vi } from 'vitest';
import { scopePoolWithinReach } from '../utils/mapReach.js';

describe('scopePoolWithinReach — spots with no id to look up', () => {
  it('keeps a spot with no id (unknown drive) without asking for its entry', () => {
    const entryFor = vi.fn(() => ({ driveMinutes: 999, driveStatus: null }));
    const noId = { name: 'No id' };
    const out = scopePoolWithinReach([noId, { id: 1 }], entryFor, 45, true);
    expect(out).toEqual([noId]);
    expect(entryFor).toHaveBeenCalledTimes(1);
    expect(entryFor).toHaveBeenCalledWith(1);
  });

  it('keeps a null spot in place rather than throwing on it', () => {
    const entryFor = vi.fn(() => null);
    expect(scopePoolWithinReach([null, undefined], entryFor, 45, true)).toEqual([null, undefined]);
    expect(entryFor).not.toHaveBeenCalled();
  });

  it('looks up an id of zero', () => {
    const entryFor = vi.fn(() => ({ driveMinutes: 120, driveStatus: null }));
    expect(scopePoolWithinReach([{ id: 0 }], entryFor, 45, true)).toEqual([]);
    expect(entryFor).toHaveBeenCalledWith(0);
  });
});

describe('scopePoolWithinReach — the tier', () => {
  const drives = new Map([
    [1, { driveMinutes: 30, driveStatus: 'OK' }],
    [2, { driveMinutes: 90, driveStatus: 'OK' }],
    [3, { driveMinutes: null, driveStatus: 'UNROUTABLE' }],
    [4, { driveMinutes: null, driveStatus: 'PENDING' }],
  ]);
  const pool = [1, 2, 3, 4, 5].map((id) => ({ id }));

  it('narrows to the drive limit, keeps unknowns and drops unroutable for a measured reader', () => {
    const out = scopePoolWithinReach(pool, (id) => drives.get(id), 45, true);
    expect(out.map((s) => s.id)).toEqual([1, 4, 5]);
  });

  it('returns the pool itself for Any, or for a pool that is not an array', () => {
    expect(scopePoolWithinReach(pool, (id) => drives.get(id), null, true)).toBe(pool);
    expect(scopePoolWithinReach(null, () => null, 45, true)).toBeNull();
  });
});
