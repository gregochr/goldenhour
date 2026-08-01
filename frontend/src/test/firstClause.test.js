import { describe, it, expect } from 'vitest';
import { firstClause } from '../utils/firstClause.js';

/**
 * The hover peek shows a glance, not a paragraph — and the glance has to stay a sentence.
 *
 * What is pinned here is the reason the cut is at a clause boundary rather than a character count:
 * every one of these inputs is a real shape from Claude's slot summaries, and a naive `slice(0, 60)`
 * breaks at least three of them mid-word, mid-number or mid-hyphenation.
 */
describe('firstClause', () => {
  it('cuts at the first comma and terminates the fragment', () => {
    expect(firstClause('Breaking low cloud frames golden westerly light, with a settled ridge'))
      .toBe('Breaking low cloud frames golden westerly light.');
  });

  it('cuts at a spaced em dash — the other clause mark Claude reaches for', () => {
    expect(firstClause('Thin high cloud over the gorge takes warm light well — though the valley '
      + 'loses the sun early'))
      .toBe('Thin high cloud over the gorge takes warm light well.');
  });

  it('cuts at the end of the first sentence when there is no earlier break', () => {
    expect(firstClause('A clear western horizon from the ridge. The crags are the reason to go.'))
      .toBe('A clear western horizon from the ridge.');
  });

  it('leaves a single short clause exactly as it is', () => {
    expect(firstClause('Solid overcast all evening.')).toBe('Solid overcast all evening.');
  });

  it('adds the missing full stop when the summary has none', () => {
    expect(firstClause('Solid overcast all evening')).toBe('Solid overcast all evening.');
  });

  it('does not break a hyphenated compound', () => {
    // `high-cloud` and `dew-point` are one word each; only a dash with space either side is a
    // clause mark, which is the whole reason the boundary is not simply /-/.
    expect(firstClause('A high-cloud canvas with a narrowing dew-point gap adds depth to the hour'))
      .toBe('A high-cloud canvas with a narrowing dew-point gap adds depth to the hour.');
  });

  it('does not treat a decimal point as the end of a sentence', () => {
    expect(firstClause('Surge peaks at 0.4 m above the predicted tide at dusk'))
      .toBe('Surge peaks at 0.4 m above the predicted tide at dusk.');
  });

  it('skips a break that would leave a fragment too short to say anything', () => {
    // `Clear, but…` truncated at the first comma yields `Clear.` — true, and no more than the
    // card's own star pill already told the reader. The scan takes the next boundary instead.
    expect(firstClause('Clear, but a bank of low cloud sits offshore and drifts in after sunset'))
      .toBe('Clear, but a bank of low cloud sits offshore and drifts in after sunset.');
  });

  it('returns null for nothing to say', () => {
    expect(firstClause(null)).toBeNull();
    expect(firstClause(undefined)).toBeNull();
    expect(firstClause('   ')).toBeNull();
  });

  it('is not stateful — the same input answers the same way twice', () => {
    // The boundary scanner is a /g regex. Sharing one across calls would carry `lastIndex` over
    // and make the second answer depend on the first.
    const text = 'Breaking low cloud frames golden westerly light, with a settled ridge';
    expect(firstClause(text)).toBe(firstClause(text));
  });
});
