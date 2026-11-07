import { describe, it, expect } from 'vitest';
import { COASTAL_KIND, DARK_SKY_KIND, entrySubject } from '../utils/comingUpOutside.js';

describe('entrySubject — an entry that cannot say what its door is', () => {
  it('is null for no entry, an empty entry and an entry whose action has no kind', () => {
    expect(entrySubject(null)).toBeNull();
    expect(entrySubject(undefined)).toBeNull();
    expect(entrySubject({})).toBeNull();
    expect(entrySubject({ action: {} })).toBeNull();
    expect(entrySubject({ action: null })).toBeNull();
    expect(entrySubject({ action: { kind: 'something-else' } })).toBeNull();
  });

  it('names the subject from the door kind', () => {
    expect(entrySubject({ action: { kind: COASTAL_KIND } })).toBe('coastal');
    expect(entrySubject({ action: { kind: DARK_SKY_KIND } })).toBe('darkSky');
  });

  it('has no line for a withheld door unless day chips carry the entry', () => {
    expect(entrySubject({ actionWithheld: true, action: { kind: COASTAL_KIND } })).toBeNull();
    expect(entrySubject({ actionWithheld: true, dayChips: [{ date: '2026-11-02' }], action: { kind: COASTAL_KIND } }))
      .toBe('coastal');
    expect(entrySubject({ actionWithheld: false, action: { kind: DARK_SKY_KIND } })).toBe('darkSky');
  });
});
