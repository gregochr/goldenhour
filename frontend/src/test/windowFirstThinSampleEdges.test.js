import { describe, it, expect, beforeEach, vi } from 'vitest';

let thinVerdictCount;

beforeEach(async () => {
  vi.resetModules();
  ({ thinVerdictCount } = await import('../utils/windowFirstThinSample.js'));
});

const spread = (rated, total = 83) => ({ rated, total });

describe('thinVerdictCount — only a rated grade wears the count', () => {
  it('counts the rated places for Worth it and Maybe', () => {
    expect(thinVerdictCount({ verdict: 'WORTH_IT', spread: spread(3) })).toBe(3);
    expect(thinVerdictCount({ verdict: 'MAYBE', spread: spread(2) })).toBe(2);
  });

  it('says nothing for any other word, a missing verdict, an away cell or an unscored window', () => {
    ['UNRATED', 'STAND_DOWN', 'AWAITING', 'NOT_WORTH_IT', 'SOMETHING_NEW', null, undefined].forEach((verdict) => {
      expect(thinVerdictCount({ verdict, spread: spread(3) })).toBeNull();
    });
    expect(thinVerdictCount({ verdict: 'MAYBE', away: true, spread: spread(3) })).toBeNull();
    expect(thinVerdictCount({ verdict: 'MAYBE', notScored: true, spread: spread(3) })).toBeNull();
  });

  it('says nothing with no rated place, or without a spread', () => {
    expect(thinVerdictCount({ verdict: 'MAYBE', spread: spread(0) })).toBeNull();
    expect(thinVerdictCount({ verdict: 'MAYBE', spread: null })).toBeNull();
    expect(thinVerdictCount({ verdict: 'MAYBE' })).toBeNull();
  });

  it('says nothing once the spread gate passes', () => {
    expect(thinVerdictCount({ verdict: 'MAYBE', spread: spread(10, 10) })).toBeNull();
  });
});
