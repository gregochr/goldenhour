import { describe, it, expect } from 'vitest';
import { topicCertainty, CERTAINTY_KINDS } from '../utils/topicCertainty.js';

describe('topicCertainty', () => {
  it('classifies tides and astronomical events as almanac (fixed)', () => {
    for (const type of ['KING_TIDE', 'SPRING_TIDE', 'SUPERMOON', 'METEOR', 'EQUINOX']) {
      expect(topicCertainty(type).key).toBe('almanac');
    }
  });

  it('classifies NLC as a chance (window fixed, display unforecastable)', () => {
    expect(topicCertainty('NLC').key).toBe('chance');
    expect(topicCertainty('NLC').label).toBe('chance');
  });

  it('classifies weather-driven topics as forecast', () => {
    for (const type of ['BLUEBELL', 'SNOW_FRESH', 'SNOW_MIST', 'SNOW_TOPS',
      'INVERSION', 'DUST', 'STORM_SURGE', 'CLEARANCE', 'AURORA']) {
      expect(topicCertainty(type).key).toBe('forecast');
    }
  });

  it('falls back to forecast for an unknown or missing type (fail-soft)', () => {
    expect(topicCertainty('WHATEVER').key).toBe('forecast');
    expect(topicCertainty(undefined).key).toBe('forecast');
    expect(topicCertainty(null).key).toBe('forecast');
  });

  it('stays fail-soft for inherited Object keys (no prototype-member leak)', () => {
    for (const type of ['constructor', 'toString', 'hasOwnProperty', '__proto__']) {
      expect(topicCertainty(type).key).toBe('forecast');
    }
  });

  it('every kind carries a legible word and a gloss title', () => {
    for (const kind of Object.values(CERTAINTY_KINDS)) {
      expect(kind.label).toBeTruthy();
      expect(kind.title.length).toBeGreaterThan(10);
    }
  });
});
