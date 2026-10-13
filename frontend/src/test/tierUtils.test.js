import { describe, it, expect } from 'vitest';
import {
  computeCellTier,
  resolveRegionDisplay,
} from '../utils/tierUtils.js';

// The tide lookup is the caller's now (window.tideFacts); these fixtures keep their marker on the slot.
const alignedOf = (slot) => slot.tideAligned === true;

describe('computeCellTier — the tide lookup is the predicate, not the slot', () => {
  const region = { displayVerdict: 'WORTH_IT', tideHighlights: [], slots: [{ locationId: 1, tideAligned: true }] };

  it('ignores a slot-side tideAligned when the predicate says no', () => {
    expect(computeCellTier(region, () => false)).toBe(2);
  });

  it('reads alignment from the predicate even when the slot carries nothing', () => {
    const bare = { ...region, slots: [{ locationId: 1 }] };
    expect(computeCellTier(bare, (slot) => slot.locationId === 1)).toBe(1);
  });

  it('only a strict true counts as aligned', () => {
    expect(computeCellTier(region, () => 'yes')).toBe(2);
  });
});

describe('computeCellTier', () => {
  it('returns 5 for null/undefined region', () => {
    expect(computeCellTier(null)).toBe(5);
    expect(computeCellTier(undefined)).toBe(5);
  });

  it('returns 5 for STANDDOWN verdict', () => {
    expect(computeCellTier({ verdict: 'STANDDOWN', tideHighlights: [], slots: [] }, alignedOf)).toBe(5);
  });

  it('returns 0 (go-king) for GO with king tide in tideHighlights', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: ['King tide at Bamburgh'],
      slots: [],
    };
    expect(computeCellTier(region, alignedOf)).toBe(0);
  });

  it('king tide detection is case-insensitive', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: ['KING TIDE at somewhere'],
      slots: [],
    };
    expect(computeCellTier(region, alignedOf)).toBe(0);
  });

  it('returns 1 (go-tide) for GO with tideAligned slot but no king tide', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: [],
      slots: [{ tideAligned: true }],
    };
    expect(computeCellTier(region, alignedOf)).toBe(1);
  });

  it('returns 2 (go-plain) for GO with no tide signals', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: [],
      slots: [{ tideAligned: false }],
    };
    expect(computeCellTier(region, alignedOf)).toBe(2);
  });

  it('returns 2 (go-plain) for GO with empty slots', () => {
    const region = { verdict: 'GO', tideHighlights: [], slots: [] };
    expect(computeCellTier(region, alignedOf)).toBe(2);
  });

  it('king tide takes priority over tideAligned for GO', () => {
    const region = {
      verdict: 'GO',
      tideHighlights: ['King tide at somewhere'],
      slots: [{ tideAligned: true }],
    };
    expect(computeCellTier(region, alignedOf)).toBe(0); // king overrides tide-aligned
  });

  it('returns 3 (ma-tide) for MARGINAL with tideAligned slot', () => {
    const region = {
      verdict: 'MARGINAL',
      tideHighlights: [],
      slots: [{ tideAligned: true }],
    };
    expect(computeCellTier(region, alignedOf)).toBe(3);
  });

  it('returns 4 (ma-plain) for MARGINAL with no tide alignment', () => {
    const region = {
      verdict: 'MARGINAL',
      tideHighlights: [],
      slots: [{ tideAligned: false }],
    };
    expect(computeCellTier(region, alignedOf)).toBe(4);
  });

  it('returns 5 for unknown verdict', () => {
    const region = { verdict: 'UNKNOWN', tideHighlights: [], slots: [] };
    expect(computeCellTier(region, alignedOf)).toBe(5);
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
    expect(computeCellTier(region, alignedOf)).toBe(1);
  });

  it('displayVerdict STAND_DOWN drops GO triage region into tier 5', () => {
    // Claude gave it 2★ so displayVerdict=STAND_DOWN even though triage said GO
    const region = {
      verdict: 'GO',
      displayVerdict: 'STAND_DOWN',
      tideHighlights: [],
      slots: [],
    };
    expect(computeCellTier(region, alignedOf)).toBe(5);
  });

  it('AWAITING displayVerdict is treated as stand-down (tier 5)', () => {
    const region = {
      verdict: 'GO',
      displayVerdict: 'AWAITING',
      tideHighlights: [],
      slots: [],
    };
    expect(computeCellTier(region, alignedOf)).toBe(5);
  });

  it.each([
    ['a plain region', { tideHighlights: [], slots: [] }],
    ['a king-tide region', { tideHighlights: ['King Tide at 3 coastal spots'], slots: [] }],
    ['a tide-aligned region', { tideHighlights: [], slots: [{ tideAligned: true }] }],
  ])('UNRATED displayVerdict is tier 5 for %s — a weather pass no rating has called never enters a recommending tier (U2)', (_, shape) => {
    // The tide and king signals lift a WORTH_IT/MAYBE region through tiers 0-4; they must not lift
    // "Too few rated", whatever the triage said (`verdict: 'GO'` is what the old fallback read).
    const region = { verdict: 'GO', displayVerdict: 'UNRATED', ...shape };
    expect(computeCellTier(region, (s) => s.tideAligned === true)).toBe(5);
  });

  it.each([
    ['a plain region', { tideHighlights: [], slots: [] }],
    ['a king-tide region', { tideHighlights: ['King Tide at 3 coastal spots'], slots: [] }],
    ['a tide-aligned region', { tideHighlights: [], slots: [{ tideAligned: true }] }],
  ])('UNRATED takes exactly the AWAITING tier for %s — the explicit arm and the fall-through must move together (U2)', (_, shape) => {
    // The explicit `dv === 'UNRATED'` clause in computeCellTier is a pinned equivalent of the final
    // `return 5` (deleting it changes nothing), so a test of the value alone cannot see it. Equating
    // the two verdicts' tiers does: it breaks if UNRATED is ever given its own tier, or if the
    // fall-through stops returning what AWAITING gets.
    const aligned = (s) => s.tideAligned === true;
    const unrated = computeCellTier({ verdict: 'GO', displayVerdict: 'UNRATED', ...shape }, aligned);
    const awaiting = computeCellTier({ verdict: 'GO', displayVerdict: 'AWAITING', ...shape }, aligned);
    expect(unrated).toBe(awaiting);
    expect(unrated).toBe(5);
  });

  it('MAYBE displayVerdict with tide-aligned slot lands at tier 3', () => {
    const region = {
      verdict: 'GO',
      displayVerdict: 'MAYBE',
      tideHighlights: [],
      slots: [{ tideAligned: true }],
    };
    expect(computeCellTier(region, alignedOf)).toBe(3);
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

  it('passes UNRATED through as itself, and prefers it over a GO triage verdict (U2)', () => {
    expect(resolveRegionDisplay({ displayVerdict: 'UNRATED', verdict: 'GO' })).toBe('UNRATED');
  });

  it('returns AWAITING for null region', () => {
    expect(resolveRegionDisplay(null)).toBe('AWAITING');
  });

  it('returns AWAITING when neither field is set', () => {
    expect(resolveRegionDisplay({})).toBe('AWAITING');
  });
});
