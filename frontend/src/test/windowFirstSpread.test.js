import { describe, it, expect } from 'vitest';
import {
  SPREAD_BAR_EMPTY_PX, SPREAD_BAR_MAX_PX, SPREAD_BAR_MIN_PX, SPREAD_MIN_RATED_COUNT, SPREAD_STARS,
  SPREAD_UNRATED_BAR_MIN_PX,
  buildSpread, formatSpreadCount, hasSpreadSample, poolPhrase, poolWithinReach, spokenPoolPhrase,
  spreadBandsSpoken, spreadBars, spreadColumnWidthPx, spreadCountLayout,
  spreadCountLayoutAcross,
  spreadQualifier, spreadRowState, spreadTitle, stoodSpreadTitle, unratedBar, unratedPhrase,
} from '../utils/windowFirstSpread.js';
import { REACH_TIERS } from '../utils/reachLens.js';

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

/**
 * A pool big enough to clear the minimum-sample gate ({@link hasSpreadSample}: rated ≥ 5 AND
 * rated·2 ≥ total), for the tests below that are about the BARS-mode tooltip rather than about the
 * gate itself. `n` unrated spots appended keep the remainder testable without dropping below the
 * gate — five rated is already at the absolute floor, so an unrated appendage never has to compete
 * with it for the coverage floor as long as `n <= 5`.
 */
function ratedPool(ratings, unrated = 0) {
  return [
    ...ratings.map((rating) => spot(rating)),
    ...Array.from({ length: unrated }, () => spot(null)),
  ];
}

describe('spreadTitle — what the tooltip may claim (bars mode, above the minimum-sample gate)', () => {
  it('leads with the POOL size, not the sum of the bars, and names the remainder', () => {
    // The rule this block exists for. 8 in reach, 5 of them rated: "8 locations" over bars summing
    // to 5, with the difference stated rather than left as arithmetic the reader has to notice.
    const spread = buildSpread(ratedPool([5, 5, 5, 3, 3], 3));
    expect(spreadTitle(spread, true)).toBe(
      '8 locations within reach — 3 at 5★, 0 at 4★, 2 at 3★, 0 at 2★, 0 at 1★ · 3 not yet rated',
    );
    // Said explicitly, because it is the property the copy exists to disclose.
    expect(spread.total).toBeGreaterThan(spread.counts.reduce((a, b) => a + b, 0));
  });

  it('omits the remainder clause when every spot in reach is rated', () => {
    const spread = buildSpread(ratedPool([5, 5, 4, 4, 4]));
    expect(spreadTitle(spread, true))
      .toBe('5 locations within reach — 2 at 5★, 3 at 4★, 0 at 3★, 0 at 2★, 0 at 1★');
    expect(spreadTitle(spread, true)).not.toContain('not yet rated');
  });

  it('reads the bands highest star first, the direction a reader scans for good news', () => {
    const title = spreadTitle(buildSpread(ratedPool([1, 1, 1, 1, 1, 5])), true);
    expect(title.indexOf('at 5★')).toBeLessThan(title.indexOf('at 1★'));
  });

  it('drops the words "within reach" when a drive in the pool is unknown', () => {
    const pool = [spot(5, null), spot(4, 20), spot(4, 20), spot(4, 20), spot(4, 20)];
    expect(spreadTitle(buildSpread(pool), poolWithinReach(pool)))
      .toBe('5 locations — 1 at 5★, 4 at 4★, 0 at 3★, 0 at 2★, 0 at 1★');
  });

  it('never says "scored" of the pool count', () => {
    // Plan §3 rule 5: the count is places to go — the statement the lens readout already makes —
    // and "N of M scored" would be a count of our own data dressed as a fact about the sky.
    const title = spreadTitle(buildSpread(ratedPool([5, 5, 5, 5, 5], 1)), true);
    expect(title).toContain('locations within reach');
    expect(title).not.toContain('scored');
  });
});

describe('spreadTitle — below the minimum-sample gate: the tooltip says MORE than the row', () => {
  it('keeps the ORIGINAL "none rated yet" sentence, lead and period included — unchanged by this rule', () => {
    // "rated", never "scored" — A10 and M1 task 2 both ban the second word from this copy in terms.
    // ⚠️ This is the sentence `spreadTitle` returned before the minimum-sample rule existed, and it
    // stays that shape: the tooltip has room the row does not, so it keeps the pool-size lead and
    // the period the row's own bare "none rated yet" (`spreadRowState`'s text) does not carry.
    const spread = buildSpread([spot(null), spot(null), spot(null)]);
    expect(spreadTitle(spread, true)).toBe('3 locations within reach — none rated yet.');
  });

  it('never says "scored" of an unrated far-horizon pool either', () => {
    expect(spreadTitle(buildSpread([spot(null)]), true)).not.toContain('scored');
  });

  it('names the pool, the rated count and the remainder for a partial sample — more than the row\'s bare "N/M rated"', () => {
    // 2 of 4 — clears neither floor (rated < 5) — so this is NOT the per-band breakdown a real
    // distribution would draw, but it is MORE than the row's own compact text: the pool size leads,
    // "rated" states the count, and `unratedPhrase` names the remainder, the same "lead — detail ·
    // remainder" shape bars mode uses.
    const spread = buildSpread([spot(5), spot(3), spot(null), spot(null)]);
    expect(spreadTitle(spread, true)).toBe('4 locations within reach — 2 rated · 2 not yet rated');
  });

  it('singularises the lead for a pool of one, below the gate', () => {
    // A total of 1 can never reach the gate's absolute floor of 5. `poolPhrase`'s own
    // singular/plural rule is pinned directly below too, not only through this path — see the
    // 'poolPhrase and unratedPhrase' block.
    expect(spreadTitle(buildSpread([spot(3)]), true)).toBe('1 location within reach — 1 rated');
  });

  it('says nothing is within reach when the pool is empty', () => {
    expect(spreadTitle(buildSpread([]), true)).toBe('Nothing within reach for this one.');
  });

  it('⚠️ drops the reach word for an empty pool when the caller says reach did not act', () => {
    // TWO sentences, and this was one until M5. The old comment argued the second branch was dead —
    // `poolWithinReach([])` is true by `Array.every`, so the only caller always arrived with the
    // word available. That is exactly the problem: for a reader with no home postcode nothing was
    // gated by distance at all (an unknown drive passes every tier, plan §2.5), so an empty pool
    // means this window has no sky-gated slots and "within reach" blames a control that did nothing.
    // §6 clause 7. The caller now answers from the card's own `reachMeasured`, which is the same
    // field `bestReachLine` reads for its own empty word. An empty pool is not a minimum-sample gate
    // failure (see `spreadRowState`), so this pair of sentences is untouched by that rule.
    expect(spreadTitle(buildSpread([]), false)).toBe('Nothing to show for this one.');
  });
});

describe('hasSpreadSample / spreadRowState — the minimum-sample gate (2026-09-29)', () => {
  it('requires the absolute floor: 4 rated fails even with the whole pool rated', () => {
    // 4 of 4 clears the coverage floor (4 ≥ 4·0.5) but not the absolute one.
    const spread = buildSpread(ratedPool([5, 5, 5, 5]));
    expect(spread.rated).toBe(SPREAD_MIN_RATED_COUNT - 1);
    expect(hasSpreadSample(spread)).toBe(false);
    // Compact "N/M rated" for the VISIBLE row (never "N of M rated" — measured too wide for the
    // value column at a realistic pool size, plan-matrix-plan.md A27), but WORDED "N of M rated"
    // for `spoken` — a screen reader would voice a fraction-shaped "4/4" as a number, not as two
    // counts, so the compact form that fixed the row's width must never reach the accessible name.
    expect(spreadRowState(spread)).toEqual({ bars: false, text: '4/4 rated', spoken: '4 of 4 rated' });
  });

  it('passes at exactly the absolute floor, with coverage to spare', () => {
    const spread = buildSpread(ratedPool([5, 5, 5, 5, 5]));
    expect(spread.rated).toBe(SPREAD_MIN_RATED_COUNT);
    expect(hasSpreadSample(spread)).toBe(true);
    expect(spreadRowState(spread)).toEqual({ bars: true, text: null, spoken: null });
  });

  it('requires the coverage floor: 5 of 11 fails just short of half', () => {
    const spread = buildSpread(ratedPool([5, 5, 5, 5, 5], 6));
    expect(spread.total).toBe(11);
    expect(hasSpreadSample(spread)).toBe(false);
    expect(spreadRowState(spread)).toEqual({ bars: false, text: '5/11 rated', spoken: '5 of 11 rated' });
  });

  it('passes at EXACTLY half the pool — the boundary is inclusive', () => {
    const spread = buildSpread(ratedPool([5, 5, 5, 5, 5], 5));
    expect(spread.total).toBe(10);
    expect(hasSpreadSample(spread)).toBe(true);
    expect(spreadRowState(spread)).toEqual({ bars: true, text: null, spoken: null });
  });

  it('answers "none rated yet" for zero rated out of a real, non-empty pool — text and spoken agree', () => {
    // The one partial state where the two strings do NOT diverge: "none" carries no count to
    // mis-voice, so there is nothing for `spoken` to re-word.
    const spread = buildSpread([spot(null), spot(null)]);
    expect(spreadRowState(spread)).toEqual({ bars: false, text: 'none rated yet', spoken: 'none rated yet' });
  });

  it('end-to-end: an unusable rating (4.5) does not count toward the gate, and can push a card below it', () => {
    // `buildSpread` already refuses a non-integer rating on its own (a separate, pinned rule above);
    // this proves the CONSEQUENCE reaches the gate — five real stars plus one 4.5 is six pool
    // entries but still only five COUNTABLE ratings, exactly at the absolute floor with room to
    // spare on coverage, so the gate still passes; adding a second uncountable entry drops rated
    // back to five out of seven, still passing — but the moment an uncountable rating REPLACES one
    // of the five countable ones, the count drops to four and the gate fails.
    const stillPasses = buildSpread([spot(5), spot(5), spot(5), spot(5), spot(5), spot(4.5)]);
    expect(stillPasses.rated).toBe(5);
    expect(hasSpreadSample(stillPasses)).toBe(true);

    const fails = buildSpread([spot(5), spot(5), spot(5), spot(5), spot(4.5)]);
    expect(fails.rated).toBe(4);
    expect(hasSpreadSample(fails)).toBe(false);
    expect(spreadRowState(fails)).toEqual({ bars: false, text: '4/5 rated', spoken: '4 of 5 rated' });
  });

  it('⚠️ answers bars: true for a truly EMPTY pool — not a gate failure, the pre-existing treatment', () => {
    // An empty pool is not a sample too small to trust — there is no sample at all, only nothing to
    // show, and `spreadBars`/`buildSpread` already draw five hairlines for it unchanged. This was
    // "bars: false, text: 'nothing in reach'/'nothing to show'" in an earlier cut of this rule,
    // reverted after review: it put NEW text on the row where none existed before and doubled the
    // "nothing in reach" the Best row already says right below it.
    const spread = buildSpread([]);
    expect(spreadRowState(spread)).toEqual({ bars: true, text: null, spoken: null });
  });

  it('never lets the compact "/" reach the spoken form — asserted directly on spreadRowState', () => {
    // The defect this whole fix round is for: `spoken` must never contain the row's own "/"
    // separator, however the compact form is spelled.
    const spread = buildSpread([spot(5), spot(3), spot(null), spot(null)]);
    const state = spreadRowState(spread);
    expect(state.text).toContain('/');
    expect(state.spoken).not.toContain('/');
    expect(state.spoken).toBe('2 of 4 rated');
  });
});

describe('unratedBar — the sixth, hatched bar', () => {
  it('draws nothing when every spot in reach is rated', () => {
    expect(unratedBar(buildSpread(ratedPool([5, 5, 5, 5, 5])))).toBeNull();
  });

  it('draws nothing for a spread built from an empty pool', () => {
    expect(unratedBar(buildSpread([]))).toBeNull();
  });

  it('scales against the tallest BAND when the unrated count is smaller', () => {
    // 4★ band holds 4 (the tallest band), 2 are unrated — the bands' own scale wins, so the unrated
    // bar is smaller than the tallest band rather than sharing its height.
    const spread = buildSpread(ratedPool([4, 4, 4, 4, 5], 2));
    expect(spread.max).toBe(4);
    const bar = unratedBar(spread);
    expect(bar.count).toBe(2);
    // 7, not the formula `Math.round((2 / 4) * SPREAD_BAR_MAX_PX))` — a literal so this test cannot
    // reproduce the same wrong answer the implementation would if its own rounding broke.
    expect(bar.heightPx).toBe(7);
    expect(bar.heightPx).toBeLessThan(SPREAD_BAR_MAX_PX);
  });

  it('⚠️ scales against ITSELF, and the bands shrink to match, when the unrated count is the largest value', () => {
    // The shared-scale rule this module's header names: 5 rated (all 3★) and 9 unrated — the
    // unrated count is now the tallest thing on the row, so the SAME scale must shrink the rated
    // band rather than let it draw at full height against a smaller max of its own.
    const spread = buildSpread(ratedPool([3, 3, 3, 3, 3], 9));
    expect(spread.max).toBe(5); // the band's own max, ignoring the unrated count
    const bar = unratedBar(spread);
    expect(bar.count).toBe(9);
    expect(bar.heightPx).toBe(SPREAD_BAR_MAX_PX); // the new scale's own maximum
    const bars = spreadBars(spread);
    // 5 of 9 on the SHARED scale, not 5 of 5 on the bands' own — this is what proves the two calls
    // share one scale rather than each computing its own.
    expect(bars[2]).toMatchObject({ star: 3, count: 5 });
    // 7, not the formula — see the note on the sibling test above.
    expect(bars[2].heightPx).toBe(7);
    expect(bars[2].heightPx).toBeLessThan(SPREAD_BAR_MAX_PX);
  });

  it('never lets a small unrated remainder round away to nothing, on its OWN taller floor than a band', () => {
    // ⚠️ NOT `SPREAD_BAR_MIN_PX` (2px) — the unrated bar has its own, taller floor
    // (`SPREAD_UNRATED_BAR_MIN_PX`, 4px): a hatch this thin is not reliably recognisable as a hatch
    // rather than a hairline, where a solid band of the same height still reads as "small but
    // present" from its colour alone.
    const spread = buildSpread(ratedPool(Array.from({ length: 19 }, () => 3), 1));
    const bar = unratedBar(spread);
    expect(bar.count).toBe(1);
    expect(bar.heightPx).toBe(SPREAD_UNRATED_BAR_MIN_PX);
    expect(SPREAD_UNRATED_BAR_MIN_PX).toBeGreaterThan(SPREAD_BAR_MIN_PX);
  });
});

describe('poolPhrase and unratedPhrase — one spelling for two surfaces', () => {
  /**
   * ⚠️ These are exported because the histogram's `title` and the card's visually-hidden sentence
   * describe the SAME set on the same card. An earlier cut spelled the plural rule and the "within
   * reach" condition a second time in the component, which is two chances for one card to make two
   * claims — and each copy was pinned to its own literal, so neither test could see the drift.
   */
  it('composes the tooltip\'s own leading clause, in bars mode', () => {
    // Below the minimum-sample gate `spreadTitle` returns `spreadRowState`'s bare text with no
    // lead at all (its own doc comment says why) — so this composition is a BARS-mode property,
    // and the fixture must clear the gate for the assertion to mean anything.
    const spread = buildSpread(ratedPool([5, 5, 5, 5, 5], 1));
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

describe('formatSpreadCount — the printed count', () => {
  it.each([
    [0, '0'],
    [7, '7'],
    [999, '999'],
    [1000, '1k'],
    [1049, '1k'],
    [1200, '1.2k'],
    [1050, '1.1k'],
    [1150, '1.2k'],
    [1950, '2k'],
    [1250, '1.3k'],
    [9949, '9.9k'],
    [9950, '10k'],
    [12345, '12k'],
  ])('%s prints as %s', (count, printed) => {
    expect(formatSpreadCount(count)).toBe(printed);
  });

  it('rounds a fractional count down to a whole place rather than printing 2.5', () => {
    expect(formatSpreadCount(2.5)).toBe('2');
  });

  it('prints a count nothing can be said about as 0 rather than NaN', () => {
    expect(formatSpreadCount(undefined)).toBe('0');
    expect(formatSpreadCount(Number.NaN)).toBe('0');
    expect(formatSpreadCount(-3)).toBe('0');
  });

  it('never prints more than four characters for a pool up to 99k places', () => {
    for (const count of [1, 99, 999, 1000, 9949, 9950, 54321, 99499]) {
      expect(formatSpreadCount(count).length).toBeLessThanOrEqual(4);
    }
  });
});

describe('spreadColumnWidthPx — one width for the card, clamped', () => {
  it.each([
    [0, 9],
    [1, 9],
    [2, 10.4],
    [3, 15.6],
    [4, 17],
    [6, 17],
  ])('%s characters → %spx', (chars, widthPx) => {
    expect(spreadColumnWidthPx(chars)).toBe(widthPx);
  });

  it('treats a non-number as the floor rather than writing NaN into a custom property', () => {
    expect(spreadColumnWidthPx(Number.NaN)).toBe(9);
    expect(spreadColumnWidthPx(undefined)).toBe(9);
  });
});

describe('spreadCountLayout — the longest printed count decides the whole card', () => {
  /** A pool shaped to put exact counts in the bands and the remainder. */
  function layoutOf(counts, unrated) {
    const pool = [];
    counts.forEach((count, i) => {
      for (let n = 0; n < count; n += 1) pool.push(spot(i + 1));
    });
    for (let n = 0; n < unrated; n += 1) pool.push(spot(null));
    const spread = buildSpread(pool);
    return spreadCountLayout(spreadBars(spread), unratedBar(spread));
  }

  it('sizes a single-digit card at the floor, at the normal type', () => {
    expect(layoutOf([1, 2, 3, 4, 5], 0)).toEqual({ chars: 1, widthPx: 9, small: false });
  });

  it('sizes a three-character card without shrinking the type', () => {
    expect(layoutOf([1, 2, 999, 4, 5], 0)).toEqual({ chars: 3, widthPx: 15.6, small: false });
  });

  it('flips to the smaller type at exactly four characters, and clamps the width', () => {
    expect(layoutOf([1, 2, 1200, 4, 5], 0)).toEqual({ chars: 4, widthPx: 17, small: true });
    expect(layoutOf([1, 2, 999, 4, 5], 0).small).toBe(false);
  });

  it('counts the hatched remainder: 1,200 unrated beside single-digit bands is four characters', () => {
    // Left out of `chars`, this would size the columns for the bands (9px) and clip "1.2k".
    expect(layoutOf([1, 2, 3, 4, 5], 1200)).toEqual({ chars: 4, widthPx: 17, small: true });
  });

  it('sizes by the PRINTED width, not the raw digit count', () => {
    // 1000 is four digits but prints "1k": two characters, the normal type.
    expect(layoutOf([1, 2, 1000, 4, 5], 0)).toEqual({ chars: 2, widthPx: 10.4, small: false });
    expect(layoutOf([1, 2, 12345, 4, 5], 0).chars).toBe(3);
  });

  it('ignores a remainder that is not drawn', () => {
    const spread = buildSpread(Array.from({ length: 1200 }, () => spot(null)));
    expect(spreadCountLayout(spreadBars(spread), null).chars).toBe(1);
  });

  it('answers a floor-width layout for no bars at all', () => {
    expect(spreadCountLayout([], null)).toEqual({ chars: 0, widthPx: 9, small: false });
    expect(spreadCountLayout(undefined, undefined)).toEqual({ chars: 0, widthPx: 9, small: false });
  });
});

describe('spreadQualifier — the pool the bars were counted over', () => {
  it.each([
    [45, 'within 45m', 'within 45 min'],
    [90, 'within 1h30', 'within 1h 30min'],
    [150, 'within 2h30', 'within 2h 30min'],
  ])('a %s-minute tier with every drive measured reads %s, spoken %s', (limit, text, spoken) => {
    expect(spreadQualifier({ reachLimitMinutes: limit, withinReach: true })).toEqual({ text, spoken });
  });

  it('reads "any drive" under Any, in text and in speech, whatever the drives are', () => {
    const any = { text: 'any drive', spoken: 'any drive' };
    expect(spreadQualifier({ reachLimitMinutes: null, withinReach: false })).toEqual(any);
    expect(spreadQualifier({ reachLimitMinutes: null, withinReach: true })).toEqual(any);
  });

  it('is silent under a finite tier when any drive in the pool is unmeasured', () => {
    // An unknown drive passes every tier, so the pool was not wholly gated at 45 minutes.
    expect(spreadQualifier({ reachLimitMinutes: 45, withinReach: false })).toBeNull();
    expect(spreadQualifier({ reachLimitMinutes: 150, withinReach: false })).toBeNull();
  });

  it('is silent for a threshold that matches no tier rather than inventing a figure', () => {
    expect(spreadQualifier({ reachLimitMinutes: 60, withinReach: true })).toBeNull();
  });

  it('is silent for a matching tier that carries no words, rather than printing "within undefined"', () => {
    const noShort = [{ id: '45', limitMinutes: 45, label: '45 min', shortLabel: null }];
    const noLabel = [{ id: '45', limitMinutes: 45, label: null, shortLabel: '45m' }];
    expect(spreadQualifier({ reachLimitMinutes: 45, withinReach: true }, noShort)).toBeNull();
    expect(spreadQualifier({ reachLimitMinutes: 45, withinReach: true }, noLabel)).toBeNull();
  });

  it('treats an unresolved (undefined) threshold as silence, never as Any', () => {
    expect(spreadQualifier({ reachLimitMinutes: undefined, withinReach: false })).toBeNull();
    expect(spreadQualifier({ reachLimitMinutes: undefined, withinReach: true })).toBeNull();
  });

  it('speaks the tier lower-cased even when the lens bar\'s label is capitalised', () => {
    const capital = [{
      id: '720', limitMinutes: 720, label: 'Half Day', shortLabel: '12h',
    }];
    expect(spreadQualifier({ reachLimitMinutes: 720, withinReach: true }, capital))
      .toEqual({ text: 'within 12h', spoken: 'within half day' });
  });

  it('builds the text from each tier\'s shortLabel and the speech from its label, not a table', () => {
    // A copy of the tiers with a retuned threshold: both forms must follow it.
    const retuned = [{
      id: '75', limitMinutes: 75, label: '1h 15min', shortLabel: '1h15',
    }];
    expect(spreadQualifier({ reachLimitMinutes: 75, withinReach: true }, retuned))
      .toEqual({ text: 'within 1h15', spoken: 'within 1h 15min' });
  });

  it('answers a pair for every finite tier the lens offers', () => {
    for (const tier of REACH_TIERS.filter((t) => t.limitMinutes != null)) {
      expect(spreadQualifier({ reachLimitMinutes: tier.limitMinutes, withinReach: true }))
        .toEqual({ text: `within ${tier.shortLabel}`, spoken: `within ${tier.label.toLowerCase()}` });
    }
  });
});

describe('spokenPoolPhrase — the sentence names the pool the label names', () => {
  const within45 = { text: 'within 45m', spoken: 'within 45 min' };
  const anyDrive = { text: 'any drive', spoken: 'any drive' };

  it('puts the tier after the count, and a comma before "any drive"', () => {
    expect(spokenPoolPhrase(7, within45)).toBe('7 locations within 45 min');
    expect(spokenPoolPhrase(7, anyDrive)).toBe('7 locations, any drive');
  });

  it('joins every real tier\'s qualifier with a space, and Any with a comma', () => {
    // End to end through spreadQualifier: the separator keys on the qualifier's own wording.
    for (const tier of REACH_TIERS.filter((t) => t.limitMinutes != null)) {
      const qualifier = spreadQualifier({ reachLimitMinutes: tier.limitMinutes, withinReach: true });
      expect(spokenPoolPhrase(7, qualifier)).toBe(`7 locations within ${tier.label.toLowerCase()}`);
    }
    expect(spokenPoolPhrase(7, spreadQualifier({ reachLimitMinutes: null, withinReach: false })))
      .toBe('7 locations, any drive');
  });

  it('singularises one', () => {
    expect(spokenPoolPhrase(1, within45)).toBe('1 location within 45 min');
    expect(spokenPoolPhrase(1, anyDrive)).toBe('1 location, any drive');
  });

  it('says "no locations" for an empty pool only when there is a qualifier to voice', () => {
    expect(spokenPoolPhrase(0, within45)).toBe('no locations within 45 min');
    expect(spokenPoolPhrase(0, anyDrive)).toBe('no locations, any drive');
    expect(spokenPoolPhrase(0, null)).toBeNull();
  });

  it('with no qualifier is exactly the bare clause the sentence has always carried', () => {
    for (const total of [1, 2, 7, 253]) {
      expect(spokenPoolPhrase(total, null)).toBe(poolPhrase(total, false));
    }
  });
});

describe('spreadBandsSpoken — the visible counts, in words', () => {
  it('names only the non-zero bands, highest first, in words rather than glyphs', () => {
    const spread = buildSpread([spot(5), spot(5), spot(3), spot(3), spot(3), spot(null)]);
    expect(spreadBandsSpoken(spread)).toBe('2 at 5 stars, 3 at 3 stars');
  });

  it('singularises "1 star"', () => {
    expect(spreadBandsSpoken(buildSpread([spot(1), spot(1), spot(2)]))).toBe('1 at 2 stars, 2 at 1 star');
  });

  it('is the empty string for a spread with nothing rated', () => {
    expect(spreadBandsSpoken(buildSpread([spot(null), spot(null)]))).toBe('');
    expect(spreadBandsSpoken(buildSpread([]))).toBe('');
    expect(spreadBandsSpoken(undefined)).toBe('');
  });

  it('uses the same five bands the tooltip does, filtered and reworded', () => {
    // Both read one buildSpread: parse the tooltip's "N at S★" clauses back out, drop the zeros,
    // reword, and require the spoken clause to be exactly that.
    const spread = buildSpread([
      spot(5), spot(4), spot(4), spot(2), spot(2), spot(2), spot(1), spot(null),
    ]);
    const fromTooltip = [...spreadTitle(spread, true).matchAll(/(\d+) at (\d)★/g)]
      .filter(([, count]) => Number(count) > 0)
      .map(([, count, star]) => `${count} at ${star} star${star === '1' ? '' : 's'}`)
      .join(', ');
    expect(fromTooltip).not.toBe('');
    expect(spreadBandsSpoken(spread)).toBe(fromTooltip);
  });
});

describe('spreadCountLayoutAcross — neighbours in a row share one column width', () => {
  const layout = (chars) => ({ chars, widthPx: spreadColumnWidthPx(chars), small: chars > 3 });

  it('answers the one-character floor for no cards at all', () => {
    expect(spreadCountLayoutAcross([])).toEqual({ chars: 1, widthPx: 9, small: false });
    expect(spreadCountLayoutAcross(undefined)).toEqual({ chars: 1, widthPx: 9, small: false });
  });

  it('takes the widest card: 91 beside 125 is 15.6px for both', () => {
    expect(spreadCountLayoutAcross([layout(2), layout(3)])).toEqual({ chars: 3, widthPx: 15.6, small: false });
  });

  it('flips every card to the smaller type when one card has a 1.2k count', () => {
    expect(spreadCountLayoutAcross([layout(1), layout(4), layout(2)]))
      .toEqual({ chars: 4, widthPx: 17, small: true });
  });

  it('lets cards that draw no bars (null) contribute nothing', () => {
    expect(spreadCountLayoutAcross([null, layout(2), undefined])).toEqual({ chars: 2, widthPx: 10.4, small: false });
    expect(spreadCountLayoutAcross([null, null])).toEqual({ chars: 1, widthPx: 9, small: false });
  });
});

describe('stoodSpreadTitle — the tooltip over a pool the weather ruled out', () => {
  it('keeps spreadTitle\'s lead and swaps the reason, with and without the reach claim', () => {
    const spread = buildSpread([{ rating: null }, { rating: null }]);
    expect(stoodSpreadTitle(spread, true)).toBe('2 locations within reach — stood down by the weather.');
    expect(stoodSpreadTitle(spread, false)).toBe('2 locations — stood down by the weather.');
    expect(stoodSpreadTitle(spread, true).split(' — ')[0]).toBe(spreadTitle(spread, true).split(' — ')[0]);
  });

  it('says one location in the singular', () => {
    expect(stoodSpreadTitle(buildSpread([{ rating: null }]), false)).toBe('1 location — stood down by the weather.');
  });
});
