import { describe, it, expect } from 'vitest';
import { tideRun } from '../utils/windowFirstTideRun.js';

const card = (key, matched, meanQuality, extra = {}) => ({
  key, tideFit: { coastal: 4, matched, live: true, meanQuality }, ...extra,
});

describe('windowFirstTideRun edges', () => {
  it('a later window with the same match count and better quality takes the best', () => {
    const run = tideRun([card('a', 2, 3), card('b', 2, 4)]);
    expect(run.bestKey).toBe('b');
  });

  it('a later window with the same match count and worse quality does not take the best', () => {
    const run = tideRun([card('a', 2, 4), card('b', 2, 3)]);
    expect(run.bestKey).toBe('a');
  });

  it('a later window with an identical fit leaves the earlier one standing', () => {
    const run = tideRun([card('a', 2, 4), card('b', 2, 4)]);
    expect(run.bestKey).toBe('a');
    expect(run.liveCount).toBe(2);
  });

  it('more matches beat better quality, and fewer matches never do', () => {
    expect(tideRun([card('a', 1, 5), card('b', 2, 1)]).bestKey).toBe('b');
    expect(tideRun([card('a', 2, 1), card('b', 1, 5)]).bestKey).toBe('a');
  });

  it('a window with no quality loses to one with quality at the same match count', () => {
    expect(tideRun([card('a', 2, null), card('b', 2, 1)]).bestKey).toBe('b');
    expect(tideRun([card('a', 2, 1), card('b', 2, null)]).bestKey).toBe('a');
  });

  it('skips away windows and non-live fits, and names no best for a single live window', () => {
    const run = tideRun([
      card('away', 4, 5, { away: true }),
      { key: 'dead', tideFit: { matched: 4, live: false, meanQuality: 5 } },
      null,
      card('only', 1, 1),
    ]);
    expect([...run.liveKeys]).toEqual(['only']);
    expect(run.bestKey).toBeNull();
    expect(run.liveCount).toBe(1);
  });

  it('is empty for a non-array', () => {
    const run = tideRun(null);
    expect(run.liveCount).toBe(0);
    expect(run.bestKey).toBeNull();
  });
});
