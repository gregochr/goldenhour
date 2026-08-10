import { describe, it, expect, beforeEach } from 'vitest';
import { PLAN_DOORS_KEY, readStoredDoors, writeStoredDoors } from '../utils/planDoors.js';

// Locally sessionStorage is a process-level store shared across files in a reused worker, so
// without this a leak from another suite is invisible on CI and very real here.
beforeEach(() => sessionStorage.clear());

describe('planDoors', () => {
  it('round-trips the doors that are open', () => {
    writeStoredDoors(new Set(['topics']));
    expect(readStoredDoors()).toEqual(new Set(['topics']));
  });

  it('records both doors, so a closed one is a fact rather than an absence', () => {
    // A whole-value write: without the closed door written explicitly, "closed" and "this build did
    // not know about that door" would be the same stored shape.
    writeStoredDoors(new Set(['regional']));
    expect(JSON.parse(sessionStorage.getItem(PLAN_DOORS_KEY))).toEqual({ regional: true, topics: false });
  });

  it('round-trips both doors open, and none', () => {
    writeStoredDoors(new Set(['regional', 'topics']));
    expect(readStoredDoors()).toEqual(new Set(['regional', 'topics']));
    writeStoredDoors(new Set());
    expect(readStoredDoors()).toEqual(new Set());
  });

  // The five absences, which all mean the same thing to the caller. Each is a separate input, and
  // the last two are the ones a hand-written or half-migrated key actually produces.
  it.each([
    ['no key at all', null],
    ['unparseable JSON', '{oh no'],
    ['valid JSON that is not an object', '"topics"'],
    ['an array, which typeof calls an object', '["topics"]'],
    ['null, which typeof also calls an object', 'null'],
    ['a door id this build does not have', '{"cellar":true}'],
    ['a truthy non-boolean against a known id', '{"topics":"yes"}'],
  ])('reads as no doors open when the stored value is %s', (_label, raw) => {
    if (raw !== null) sessionStorage.setItem(PLAN_DOORS_KEY, raw);
    expect(readStoredDoors()).toEqual(new Set());
  });

  it('ignores an unknown door while honouring a known one beside it', () => {
    // The mixed case: a forward-compatible read must not throw away what it does understand.
    sessionStorage.setItem(PLAN_DOORS_KEY, '{"topics":true,"cellar":true}');
    expect(readStoredDoors()).toEqual(new Set(['topics']));
  });
});
