import { describe, it, expect } from 'vitest';
import { tideRun } from '../utils/windowFirstTideRun.js';

/** A strip card, as `windowFirstStrip.js#buildHeatStripCards` folds one. */
function card(key, overrides = {}) {
  return {
    key,
    away: false,
    tideFit: null,
    ...overrides,
  };
}

const live = (key, matched, meanQuality = null, extra = {}) => card(key, {
  tideFit: { coastal: 9, matched, live: true, meanQuality, ...extra },
});

const miss = (key) => card(key, { tideFit: { coastal: 9, matched: 2, live: false, meanQuality: null } });

describe('tideRun', () => {
  it('marks every live card, and only the live ones', () => {
    const cards = [live('a', 5), miss('b'), live('c', 4)];
    const { liveKeys, liveCount } = tideRun(cards);
    expect([...liveKeys]).toEqual(['a', 'c']);
    expect(liveCount).toBe(2);
  });

  it('crowns nobody with a single live window — nothing to be best OF', () => {
    const cards = [miss('a'), live('b', 6, 0.5), miss('c')];
    const { bestKey, liveCount } = tideRun(cards);
    expect(liveCount).toBe(1);
    expect(bestKey).toBeNull();
  });

  it('crowns nobody when nothing is live at all', () => {
    const { bestKey, liveCount, liveKeys } = tideRun([miss('a'), miss('b')]);
    expect(liveCount).toBe(0);
    expect(bestKey).toBeNull();
    expect(liveKeys.size).toBe(0);
  });

  describe('the ranking — matched DESC, meanQuality DESC (nulls last), strip order ASC', () => {
    it('count beats quality: a higher-matched window wins over a better-served one', () => {
      const cards = [
        live('low-count-high-quality', 5, 0.9),
        live('high-count-low-quality', 7, 0.1),
      ];
      expect(tideRun(cards).bestKey).toBe('high-count-low-quality');
    });

    it('quality breaks a count tie — the spec\'s own worked case', () => {
      // Three windows matching the same nine locations (identical `matched`), mean qualities
      // 0.3 / 0.9 / 0.6 in strip order. The winner is the SECOND, not the first — pinned so a
      // `||`→`&&` flip in the replace condition, or a reversed comparison in `outranks`, fails
      // this test rather than merely picking a different-but-plausible window.
      const cards = [
        live('mon-sunset', 9, 0.3),
        live('thu-sunset', 9, 0.9),
        live('sat-sunset', 9, 0.6),
      ];
      const { bestKey, liveCount } = tideRun(cards);
      expect(liveCount).toBe(3);
      expect(bestKey).toBe('thu-sunset');
    });

    it('treats a null meanQuality as the worst quality, never as a pass or a zero', () => {
      const cards = [
        live('no-quality-yet', 9, null),
        live('has-quality', 9, 0.01),
      ];
      expect(tideRun(cards).bestKey).toBe('has-quality');
    });

    it('strip order breaks a full tie — equal matched, equal (non-null) quality', () => {
      const cards = [
        live('first', 6, 0.5),
        live('second', 6, 0.5),
      ];
      expect(tideRun(cards).bestKey).toBe('first');
    });

    it('strip order breaks a tie where NEITHER window carries a quality', () => {
      const cards = [
        live('first', 5, null),
        live('second', 5, null),
      ];
      expect(tideRun(cards).bestKey).toBe('first');
    });

    it('never lets a later, merely-equal window displace an earlier winner', () => {
      // The direct test of "window index ASC": a third window tying the running best must leave it
      // standing, not silently overwrite it with an equally-good later one.
      const cards = [
        live('a', 4, 0.5),
        live('b', 9, 0.9),
        live('c', 9, 0.9),
      ];
      expect(tideRun(cards).bestKey).toBe('b');
    });
  });

  describe('away and unserved cards', () => {
    it('never counts an away card as live, even if its tideFit says so', () => {
      // Should not happen from `buildHeatStripCards` (an away card's `tideFit` is always null), but
      // the run must not trust `away` and `tideFit` to always agree — `away` wins.
      const cards = [card('away-day', { away: true, tideFit: { coastal: 9, matched: 9, live: true, meanQuality: 1 } })];
      const { liveKeys, liveCount, bestKey } = tideRun(cards);
      expect(liveKeys.size).toBe(0);
      expect(liveCount).toBe(0);
      expect(bestKey).toBeNull();
    });

    it('never counts a card with no tideFit at all as live', () => {
      const cards = [card('unserved', { tideFit: null }), live('served', 5)];
      const { liveKeys, liveCount } = tideRun(cards);
      expect([...liveKeys]).toEqual(['served']);
      expect(liveCount).toBe(1);
    });

    it('survives a missing or empty card list', () => {
      expect(tideRun([])).toEqual({ liveKeys: new Set(), bestKey: null, liveCount: 0 });
      expect(tideRun(undefined)).toEqual({ liveKeys: new Set(), bestKey: null, liveCount: 0 });
      expect(tideRun(null)).toEqual({ liveKeys: new Set(), bestKey: null, liveCount: 0 });
    });

    it('skips a null entry in the card list rather than throwing', () => {
      expect(() => tideRun([null, live('a', 5)])).not.toThrow();
      expect(tideRun([null, live('a', 5)]).liveKeys.has('a')).toBe(true);
    });
  });
});
