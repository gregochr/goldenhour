import { describe, it, expect } from 'vitest';
import {
  computeCellTier,
  isCellVisible,
  resolveRegionDisplay,
} from '../utils/tierUtils.js';

describe('computeCellTier', () => {
  it('returns 5 for null/undefined region', () => {
    expect(computeCellTier(null)).toBe(5);
    expect(computeCellTier(undefined)).toBe(5);
  });

  it('returns 5 for STANDDOWN verdict', () => {
    expect(computeCellTier({ verdict: 'STANDDOWN', tideHighlights: [], slots: [] })).toBe(5);
  });

  it('returns 0 (go-king) for GO with king tide in tideHighlights', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: ['King tide at Bamburgh'],
      slots: [],
    };
    expect(computeCellTier(region)).toBe(0);
  });

  it('king tide detection is case-insensitive', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: ['KING TIDE at somewhere'],
      slots: [],
    };
    expect(computeCellTier(region)).toBe(0);
  });

  it('returns 1 (go-tide) for GO with tideAligned slot but no king tide', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: [],
      slots: [{ tideAligned: true }],
    };
    expect(computeCellTier(region)).toBe(1);
  });

  it('returns 2 (go-plain) for GO with no tide signals', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: [],
      slots: [{ tideAligned: false }],
    };
    expect(computeCellTier(region)).toBe(2);
  });

  it('returns 2 (go-plain) for GO with empty slots', () => {
    const region = { verdict: 'GO', tideHighlights: [], slots: [] };
    expect(computeCellTier(region)).toBe(2);
  });

  it('king tide takes priority over tideAligned for GO', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: ['King tide at somewhere'],
      slots: [{ tideAligned: true }],
    };
    expect(computeCellTier(region)).toBe(0); // king overrides tide-aligned
  });

  it('returns 3 (ma-tide) for MARGINAL with tideAligned slot', () => {
    const region = {
      verdict: 'MARGINAL',
      tideHighlights: [],
      slots: [{ tideAligned: true }],
    };
    expect(computeCellTier(region)).toBe(3);
  });

  it('returns 4 (ma-plain) for MARGINAL with no tide alignment', () => {
    const region = {
      verdict: 'MARGINAL',
      tideHighlights: [],
      slots: [{ tideAligned: false }],
    };
    expect(computeCellTier(region)).toBe(4);
  });

  it('returns 5 for unknown verdict', () => {
    const region = { verdict: 'UNKNOWN', tideHighlights: [], slots: [] };
    expect(computeCellTier(region)).toBe(5);
  });

  // ── displayVerdict takes precedence over legacy verdict ───────────────────

  it('displayVerdict WORTH_IT places MARGINAL triage region in WORTH IT tier', () => {
    // Claude gave it 4★ so displayVerdict=WORTH_IT even though triage said MARGINAL
    const region = {
      verdict: 'MARGINAL',
      displayVerdict: 'WORTH_IT',
      tideHighlights: [],
      slots: [{ tideAligned: true }],
    };
    expect(computeCellTier(region)).toBe(1);
  });

  it('displayVerdict STAND_DOWN drops GO triage region into tier 5', () => {
    // Claude gave it 2★ so displayVerdict=STAND_DOWN even though triage said GO
    const region = {
      verdict: 'GO',
      displayVerdict: 'STAND_DOWN',
      tideHighlights: [],
      slots: [],
    };
    expect(computeCellTier(region)).toBe(5);
  });

  it('AWAITING displayVerdict is treated as stand-down (tier 5)', () => {
    const region = {
      verdict: 'GO',
      displayVerdict: 'AWAITING',
      tideHighlights: [],
      slots: [],
    };
    expect(computeCellTier(region)).toBe(5);
  });

  it('MAYBE displayVerdict with tide-aligned slot lands at tier 3', () => {
    const region = {
      verdict: 'GO',
      displayVerdict: 'MAYBE',
      tideHighlights: [],
      slots: [{ tideAligned: true }],
    };
    expect(computeCellTier(region)).toBe(3);
  });
});

describe('resolveRegionDisplay', () => {
  it('prefers displayVerdict when present', () => {
    expect(resolveRegionDisplay({ displayVerdict: 'WORTH_IT', verdict: 'STANDDOWN' }))
      .toBe('WORTH_IT');
  });

  it('maps legacy verdict GO → WORTH_IT', () => {
    expect(resolveRegionDisplay({ verdict: 'GO' })).toBe('WORTH_IT');
  });

  it('maps legacy verdict MARGINAL → MAYBE', () => {
    expect(resolveRegionDisplay({ verdict: 'MARGINAL' })).toBe('MAYBE');
  });

  it('maps legacy verdict STANDDOWN → STAND_DOWN', () => {
    expect(resolveRegionDisplay({ verdict: 'STANDDOWN' })).toBe('STAND_DOWN');
  });

  it('returns AWAITING for null region', () => {
    expect(resolveRegionDisplay(null)).toBe('AWAITING');
  });

  it('returns AWAITING when neither field is set', () => {
    expect(resolveRegionDisplay({})).toBe('AWAITING');
  });
});

describe('isCellVisible', () => {
  it('shows tier 0 at qualityTier 0', () => expect(isCellVisible(0, 0)).toBe(true));
  it('hides tier 1 at qualityTier 0', () => expect(isCellVisible(1, 0)).toBe(false));
  it('shows tier 3 at qualityTier 3', () => expect(isCellVisible(3, 3)).toBe(true));
  it('shows tier 3 at qualityTier 4', () => expect(isCellVisible(3, 4)).toBe(true));
  it('hides tier 4 at qualityTier 3', () => expect(isCellVisible(4, 3)).toBe(false));
  it('shows all tiers at qualityTier 5', () => {
    for (let t = 0; t <= 5; t++) {
      expect(isCellVisible(t, 5)).toBe(true);
    }
  });
});
