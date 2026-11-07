import { describe, it, expect, beforeEach, vi } from 'vitest';
import { DRIVE_UNROUTABLE } from '../utils/reachLens.js';

// Loaded afresh in each test so the module-level margin constants run during a test.
let gateSpotsByOrigin;
let missingDriveStatus;
let originReachMap;
let scopeSpots;
let ROSTER_VISIBILITY_MARGIN_MS;

beforeEach(async () => {
  vi.resetModules();
  ({
    gateSpotsByOrigin, missingDriveStatus, originReachMap, scopeSpots, ROSTER_VISIBILITY_MARGIN_MS,
  } = await import('../utils/planOrigin.js'));
});

describe('missingDriveStatus — timestamps that are not usable', () => {
  const measured = '2026-11-01T12:00:00Z';
  const longBefore = '2026-10-01T00:00:00Z';

  it('calls a place unroutable when it was last changed well before the matrix was measured', () => {
    expect(missingDriveStatus(measured, longBefore)).toBe(DRIVE_UNROUTABLE);
  });

  it('stays pending when the measurement time is not a string, even if it would parse', () => {
    const notAString = { toString: () => measured };
    expect(missingDriveStatus(notAString, longBefore)).toBe('PENDING');
  });

  it('stays pending when the change time is not a string, even if it would parse', () => {
    const notAString = { toString: () => longBefore };
    expect(missingDriveStatus(measured, notAString)).toBe('PENDING');
  });

  it('stays pending for an empty measurement time', () => {
    expect(missingDriveStatus('', longBefore)).toBe('PENDING');
  });

  it('stays pending when the measurement time is unknown, however old the change is', () => {
    expect(missingDriveStatus(null, '1960-01-01T00:00:00Z')).toBe('PENDING');
    expect(missingDriveStatus(undefined, '1960-01-01T00:00:00Z')).toBe('PENDING');
  });

  it('reads an offset-carrying timestamp as that instant and a bare one as UTC', () => {
    // 12:00+02:00 is 10:00Z; the change at 10:30Z is after the matrix, so the place is pending.
    expect(missingDriveStatus('2026-11-01T12:00:00+02:00', '2026-11-01T10:30:00Z')).toBe('PENDING');
    // The same wall-clock string with no zone is 12:00Z, so a change at 10:30Z is over an hour before it.
    expect(missingDriveStatus('2026-11-01T12:00:00', '2026-11-01T10:30:00Z')).toBe(DRIVE_UNROUTABLE);
    // Two hours before a Z-stamped matrix is outside the margin; the offset form moves the matrix to 10:00Z.
    expect(missingDriveStatus('2026-11-01T12:00:00Z', '2026-11-01T09:30:00Z')).toBe(DRIVE_UNROUTABLE);
    expect(missingDriveStatus('2026-11-01T12:00:00+02:00', '2026-11-01T09:30:00Z')).toBe('PENDING');
  });
});

describe('originReachMap — rows that do not name a location', () => {
  it('ignores a matrix key that is not a number', () => {
    const map = originReachMap({ id: 1 }, { 1: { abc: 20, 5: 30 } });
    expect([...map.keys()]).toEqual([5]);
    expect(map.get(5)).toMatchObject({ driveMinutes: 30, driveStatus: 'MEASURED' });
  });

  it('ignores non-numeric and non-finite drive times in a row', () => {
    const map = originReachMap({ id: 1 }, { 1: { 5: '30', 6: null, 7: Infinity, 8: 40 } });
    expect([...map.keys()]).toEqual([8]);
  });

  it('answers an empty map for an origin with no row, and null for no origin', () => {
    expect(originReachMap({ id: 1 }, {}).size).toBe(0);
    expect(originReachMap(null, { 1: { 5: 30 } })).toBeNull();
  });

  it('tolerates a locations argument that is not a list', () => {
    expect(originReachMap({ id: 1 }, { 1: { 5: 30 } }, { locations: 'nope' }).size).toBe(1);
  });
});

describe('scopeSpots and gateSpotsByOrigin with input that is not a list', () => {
  it('answers an empty list for a non-array when the origin has no name', () => {
    expect(scopeSpots(null, null, { id: 1 })).toEqual([]);
    expect(scopeSpots('spots', null, { id: 1 })).toEqual([]);
  });

  it('answers an empty list for a non-array when gating on an origin with no name', () => {
    expect(gateSpotsByOrigin('spots', { id: 1 })).toEqual([]);
    expect(gateSpotsByOrigin(null, { id: 1 })).toEqual([]);
  });

  it('hands the spots back untouched when there is no origin', () => {
    const spots = [{ regionName: 'A' }];
    expect(gateSpotsByOrigin(spots, null)).toBe(spots);
  });

  it('keeps only the spots in the origin region and skips empty slots', () => {
    const spots = [null, { id: 1, regionName: 'A' }, { id: 2, regionName: 'B' }];
    expect(gateSpotsByOrigin(spots, { name: 'A' })).toEqual([{ id: 1, regionName: 'A' }]);
    expect(scopeSpots(spots, null, { name: 'B' })).toEqual([{ id: 2, regionName: 'B' }]);
  });
});

describe('the roster visibility margin', () => {
  it('is one hour', () => {
    expect(ROSTER_VISIBILITY_MARGIN_MS).toBe(3_600_000);
  });

  it('calls a place unroutable only when it changed more than an hour before the measurement', () => {
    const measured = '2026-11-01T12:00:00.000Z';
    expect(missingDriveStatus(measured, '2026-11-01T11:00:00.000Z')).toBe('PENDING');
    expect(missingDriveStatus(measured, '2026-11-01T10:59:59.999Z')).toBe(DRIVE_UNROUTABLE);
    expect(missingDriveStatus(measured, null)).toBe('PENDING');
  });
});
