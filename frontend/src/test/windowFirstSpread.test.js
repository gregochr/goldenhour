import { describe, it, expect } from 'vitest';
import {
  SPREAD_BAR_EMPTY_PX, SPREAD_BAR_MAX_PX, SPREAD_BAR_MIN_PX, SPREAD_STARS,
  buildSpread, poolPhrase, poolWithinReach, spreadBars, spreadTitle, unratedPhrase,
} from '../utils/windowFirstSpread.js';

/**
 * The spread histogram — what the bars count, and what the tooltip may claim about them.
 *
 * <p>The two rules this file exists to protect are both about the count NOT closing:
 *
 * <ul>
 *   <li>the leading N is the POOL's size, not the sum of the bars, because an unrated spot within
 *       reach is a place you could go and belongs in no band;</li>
 *   <li>the rating floor is never applied, because an average of things that already passed a 4★
 *       filter always reads 4-something.</li>
 * </ul>
 *
 * <p>The second one cannot be pinned here — this module never sees a floor — so it is pinned where
 * the pool is built (`windowFirstCards.test.js`) and where it is rendered
 * (`WindowFirstHeatStrip.test.jsx`). What IS pinned here is that every rated spot lands in exactly
 * one band and every unrated one lands in none.
 */

/** A pool entry, as `buildWindowSpots` shapes the two fields this module reads. */
function spot(rating, driveMinutes = 30, locationName = 'Somewhere') {
  return { locationName, rating, driveMinutes };
}

describe('buildSpread — counting the pool into bands', () => {
  it('counts five bands, one per star of the ramp', () => {
    expect(SPREAD_STARS).toEqual([1, 2, 3, 4, 5]);
  });

  it('puts each rating in its own band and nowhere else', () => {
    const { counts } = buildSpread([spot(1), spot(3), spot(3), spot(5)]);
    expect(counts).toEqual([1, 0, 2, 0, 1]);
  });

  it('counts an unrated spot in the total and in no band', () => {
    // The ordinary far-horizon state, not an edge case: T+4 is never evaluated at all.
    const spread = buildSpread([spot(4), spot(null), spot(null)]);
    expect(spread.total).toBe(3);
    expect(spread.rated).toBe(1);
    expect(spread.unrated).toBe(2);
    expect(spread.counts).toEqual([0, 0, 0, 1, 0]);
  });

  it('counts nothing for a rating outside the ramp\'s own domain', () => {
    // 0 and 6 are the boundary either side. `rampHex` CLAMPS rather than throwing, so an unbounded
    // count would paint a bar at the ramp's end for a value the ratings vocabulary has no word for.
    const spread = buildSpread([spot(0), spot(6), spot(2.5), spot(2)]);
    expect(spread.counts).toEqual([0, 1, 0, 0, 0]);
    expect(spread.rated).toBe(1);
    expect(spread.unrated).toBe(3);
  });

  it('answers an empty spread for an empty pool', () => {
    const spread = buildSpread([]);
    expect(spread).toEqual({
      total: 0, rated: 0, unrated: 0, counts: [0, 0, 0, 0, 0], max: 0,
    });
  });

  it('answers an empty spread for a null pool rather than throwing', () => {
    expect(buildSpread(null).total).toBe(0);
  });

  it('reports the tallest band, which is what the bars are scaled against', () => {
    expect(buildSpread([spot(5), spot(5), spot(5), spot(3)]).max).toBe(3);
  });
});

describe('spreadBars — the shape a reader sees', () => {
  it('scales the tallest band to the full drawable height', () => {
    const bars = spreadBars(buildSpread([spot(5), spot(5), spot(3)]));
    expect(bars[4]).toMatchObject({ star: 5, count: 2, filled: true, heightPx: SPREAD_BAR_MAX_PX });
  });

  it('never lets a band of one round away to nothing', () => {
    // One in twenty is 0.65px, which rounds to 1 and would be indistinguishable from empty — and
    // "one good spot, drive to it" is the single most useful shape this histogram can show.
    //
    // ⚠️ Asserted against a LITERAL, not against the constant. `toBe(SPREAD_BAR_MIN_PX)` is
    // self-referential: setting the floor to 1 would satisfy it while making a populated band
    // pixel-identical to an empty one, which is the exact confusion the floor exists to prevent.
    const pool = Array.from({ length: 20 }, () => spot(3)).concat([spot(5)]);
    const bars = spreadBars(buildSpread(pool));
    expect(bars[4].count).toBe(1);
    expect(bars[4].heightPx).toBe(2);
    // The invariant the floor really encodes, said once so a future retune cannot break it quietly.
    expect(SPREAD_BAR_MIN_PX).toBeGreaterThan(SPREAD_BAR_EMPTY_PX);
  });

  it('draws an empty band as a hairline, so the row still reads as five', () => {
    const bars = spreadBars(buildSpread([spot(5)]));
    expect(bars[0]).toMatchObject({ star: 1, count: 0, filled: false, heightPx: 1 });
  });

  it('draws five hairlines for a pool with nothing rated in it', () => {
    const bars = spreadBars(buildSpread([spot(null), spot(null)]));
    expect(bars.every((b) => !b.filled && b.heightPx === SPREAD_BAR_EMPTY_PX)).toBe(true);
  });

  it('orders the bars lowest star first, which is the direction they are drawn', () => {
    expect(spreadBars(buildSpread([])).map((b) => b.star)).toEqual([1, 2, 3, 4, 5]);
  });
});

describe('poolWithinReach — whether the phrase may be used at all', () => {
  it('is true when every spot in the pool has a measured drive', () => {
    expect(poolWithinReach([spot(4, 20), spot(3, 90)])).toBe(true);
  });

  it('is false when ANY spot has an unknown drive', () => {
    // Plan §2.5 rule 1: an unknown drive is not out of reach, it passes every tier — so a pool
    // holding one is part measured and part unknown, and calling the whole of it "within reach"
    // over-claims. The normal first-run state (no home postcode) has no drives at all.
    expect(poolWithinReach([spot(4, 20), spot(3, null)])).toBe(false);
  });

  it('is true for an empty pool, which is what makes "nothing in reach" safe to say', () => {
    expect(poolWithinReach([])).toBe(true);
  });
});

describe('spreadTitle — what the tooltip may claim', () => {
  it('leads with the POOL size, not the sum of the bars, and names the remainder', () => {
    // The rule this whole module exists for. 4 in reach, 2 of them rated: "4 locations" over bars
    // summing to 2, with the difference stated rather than left as arithmetic the reader has to
    // notice.
    const spread = buildSpread([spot(5), spot(3), spot(null), spot(null)]);
    expect(spreadTitle(spread, true)).toBe(
      '4 locations within reach — 1 at 5★, 0 at 4★, 1 at 3★, 0 at 2★, 0 at 1★ · 2 not yet rated',
    );
    // Said explicitly, because it is the property the copy exists to disclose.
    expect(spread.total).toBeGreaterThan(spread.counts.reduce((a, b) => a + b, 0));
  });

  it('omits the remainder clause when every spot in reach is rated', () => {
    const spread = buildSpread([spot(5), spot(4)]);
    expect(spreadTitle(spread, true))
      .toBe('2 locations within reach — 1 at 5★, 1 at 4★, 0 at 3★, 0 at 2★, 0 at 1★');
    expect(spreadTitle(spread, true)).not.toContain('not yet rated');
  });

  it('reads the bands highest star first, the direction a reader scans for good news', () => {
    const title = spreadTitle(buildSpread([spot(1), spot(5)]), true);
    expect(title.indexOf('at 5★')).toBeLessThan(title.indexOf('at 1★'));
  });

  it('drops the words "within reach" when a drive in the pool is unknown', () => {
    const pool = [spot(5, null), spot(4, 20)];
    expect(spreadTitle(buildSpread(pool), poolWithinReach(pool)))
      .toBe('2 locations — 1 at 5★, 1 at 4★, 0 at 3★, 0 at 2★, 0 at 1★');
  });

  it('says none RATED yet rather than five zeroes and a remainder restating the pool', () => {
    // "rated", never "scored" — A10 and M1 task 2 both ban the second word from this copy in terms,
    // and it is the word the remainder clause already uses ("2 not yet rated").
    const spread = buildSpread([spot(null), spot(null), spot(null)]);
    expect(spreadTitle(spread, true)).toBe('3 locations within reach — none rated yet.');
  });

  it('says nothing is within reach when the pool is empty', () => {
    expect(spreadTitle(buildSpread([]), true)).toBe('Nothing within reach for this window.');
  });

  it('⚠️ drops the reach word for an empty pool when the caller says reach did not act', () => {
    // TWO sentences, and this was one until M5. The old comment argued the second branch was dead —
    // `poolWithinReach([])` is true by `Array.every`, so the only caller always arrived with the
    // word available. That is exactly the problem: for a reader with no home postcode nothing was
    // gated by distance at all (an unknown drive passes every tier, plan §2.5), so an empty pool
    // means this window has no sky-gated slots and "within reach" blames a control that did nothing.
    // §6 clause 7. The caller now answers from the card's own `reachMeasured`, which is the same
    // field `bestReachLine` reads for its own empty word.
    expect(spreadTitle(buildSpread([]), false)).toBe('Nothing to show for this window.');
  });

  it('singularises a pool of one', () => {
    expect(spreadTitle(buildSpread([spot(3)]), true))
      .toBe('1 location within reach — 0 at 5★, 0 at 4★, 1 at 3★, 0 at 2★, 0 at 1★');
  });

  it('never says "scored" of the pool count', () => {
    // Plan §3 rule 5: the count is places to go — the statement the lens readout already makes —
    // and "N of M scored" would be a count of our own data dressed as a fact about the sky.
    const title = spreadTitle(buildSpread([spot(5), spot(null)]), true);
    expect(title).toContain('locations within reach');
    expect(title).not.toContain('scored');
    // And the same for the branch that most wants the word — an unrated far-horizon pool.
    expect(spreadTitle(buildSpread([spot(null)]), true)).not.toContain('scored');
  });
});

describe('poolPhrase and unratedPhrase — one spelling for two surfaces', () => {
  /**
   * ⚠️ These are exported because the histogram's `title` and the card's visually-hidden sentence
   * describe the SAME set on the same card. An earlier cut spelled the plural rule and the "within
   * reach" condition a second time in the component, which is two chances for one card to make two
   * claims — and each copy was pinned to its own literal, so neither test could see the drift.
   */
  it('composes the tooltip\'s own leading clause', () => {
    const spread = buildSpread([spot(5), spot(null)]);
    const lead = poolPhrase(spread.total, true);
    expect(spreadTitle(spread, true).startsWith(lead)).toBe(true);
  });

  it('singularises one and pluralises two', () => {
    expect(poolPhrase(1, true)).toBe('1 location within reach');
    expect(poolPhrase(2, true)).toBe('2 locations within reach');
  });

  it('drops the reach words when the phrase may not claim them', () => {
    expect(poolPhrase(3, false)).toBe('3 locations');
  });

  it('names the remainder, with whatever separator the surface punctuates by', () => {
    const spread = buildSpread([spot(5), spot(null), spot(null)]);
    expect(unratedPhrase(spread)).toBe(' · 2 not yet rated');
    expect(unratedPhrase(spread, ', ')).toBe(', 2 not yet rated');
  });

  it('says nothing when every spot in reach is rated', () => {
    expect(unratedPhrase(buildSpread([spot(5)]))).toBe('');
    expect(unratedPhrase(buildSpread([]))).toBe('');
  });
});
