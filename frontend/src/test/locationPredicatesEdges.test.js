import { describe, it, expect } from 'vitest';
import { isCoastalLocation, isDarkSkyLocation } from '../utils/locationPredicates.js';
import { DARK_SKY_THRESHOLD } from '../utils/darkSky.js';

describe('isCoastalLocation', () => {
  it('is false for no location at all, and for one without a type list', () => {
    expect(isCoastalLocation(null)).toBe(false);
    expect(isCoastalLocation(undefined)).toBe(false);
    expect(isCoastalLocation({})).toBe(false);
    expect(isCoastalLocation({ locationType: 'SEASCAPE' })).toBe(false);
  });

  it('is true only when the type list holds SEASCAPE', () => {
    expect(isCoastalLocation({ locationType: ['LANDSCAPE', 'SEASCAPE'] })).toBe(true);
    expect(isCoastalLocation({ locationType: ['LANDSCAPE'] })).toBe(false);
  });
});

describe('isDarkSkyLocation', () => {
  it('is false for no place at all, and for one with no Bortle class', () => {
    expect(isDarkSkyLocation(null)).toBe(false);
    expect(isDarkSkyLocation(undefined)).toBe(false);
    expect(isDarkSkyLocation({})).toBe(false);
    expect(isDarkSkyLocation({ bortleClass: null })).toBe(false);
  });

  it('is inclusive at the threshold and false one class brighter', () => {
    expect(isDarkSkyLocation({ bortleClass: DARK_SKY_THRESHOLD })).toBe(true);
    expect(isDarkSkyLocation({ bortleClass: DARK_SKY_THRESHOLD + 1 })).toBe(false);
    expect(isDarkSkyLocation({ bortleClass: 1 })).toBe(true);
  });
});
