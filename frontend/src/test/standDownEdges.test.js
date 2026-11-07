import { describe, it, expect } from 'vitest';
import { standDownCauseWord, stoodPlaceCauseWord } from '../utils/standDown.js';

describe('standDown edges', () => {
  it('reads weather for no reasons at all, a non-array or an unknown reason', () => {
    expect(standDownCauseWord([])).toBe('weather');
    expect(standDownCauseWord(null)).toBe('weather');
    expect(standDownCauseWord(['MYSTERY'])).toBe('weather');
  });

  it('reads the single shared reason, treating a missing one as generic', () => {
    expect(standDownCauseWord(['HIGH_CLOUD', 'HIGH_CLOUD'])).toBe('cloud');
    expect(standDownCauseWord([null, undefined])).toBe('weather');
    expect(standDownCauseWord(['HIGH_CLOUD', null])).toBe('weather');
  });

  it('names no cause for a single place with a generic, missing or unknown reason', () => {
    expect(stoodPlaceCauseWord(null)).toBeNull();
    expect(stoodPlaceCauseWord(undefined)).toBeNull();
    expect(stoodPlaceCauseWord('GENERIC')).toBeNull();
    expect(stoodPlaceCauseWord('MYSTERY')).toBeNull();
    expect(stoodPlaceCauseWord('PRECIPITATION')).toBe('rain');
  });
});
