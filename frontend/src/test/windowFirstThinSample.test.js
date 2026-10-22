import { describe, it, expect } from 'vitest';
import { thinVerdictCount, thinVerdictSpoken } from '../utils/windowFirstThinSample.js';

const thin = { rated: 6, total: 83 };

describe('thinVerdictCount', () => {
  it('counts a rated grade over a thin sample', () => {
    expect(thinVerdictCount({ verdict: 'WORTH_IT', spread: thin })).toBe(6);
    expect(thinVerdictCount({ verdict: 'MAYBE', spread: { rated: 1, total: 4 } })).toBe(1);
  });

  it('is silent once the spread gate passes', () => {
    expect(thinVerdictCount({ verdict: 'WORTH_IT', spread: { rated: 5, total: 9 } })).toBeNull();
    expect(thinVerdictCount({ verdict: 'WORTH_IT', spread: { rated: 45, total: 83 } })).toBeNull();
  });

  it('counts below the five-rated floor even when coverage is high', () => {
    expect(thinVerdictCount({ verdict: 'MAYBE', spread: { rated: 3, total: 4 } })).toBe(3);
  });

  it('is silent with nothing rated in the pool', () => {
    expect(thinVerdictCount({ verdict: 'WORTH_IT', spread: { rated: 0, total: 83 } })).toBeNull();
    expect(thinVerdictCount({ verdict: 'WORTH_IT', spread: null })).toBeNull();
  });

  it.each(['UNRATED', 'STAND_DOWN', 'AWAITING', null, undefined, 'NEW_WORD'])(
    'is silent for the %s verdict',
    (verdict) => {
      expect(thinVerdictCount({ verdict, spread: thin })).toBeNull();
    },
  );

  it('is silent on an away or unscored card', () => {
    expect(thinVerdictCount({ verdict: 'WORTH_IT', away: true, spread: thin })).toBeNull();
    expect(thinVerdictCount({ verdict: 'WORTH_IT', notScored: true, spread: thin })).toBeNull();
  });
});

describe('thinVerdictSpoken', () => {
  it('words the count, singular and plural', () => {
    expect(thinVerdictSpoken(6)).toBe('6 places rated');
    expect(thinVerdictSpoken(1)).toBe('1 place rated');
  });
});
