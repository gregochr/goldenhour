import { describe, it, expect } from 'vitest';
import {
  MAX_ROWS, badgeKey, buildWindowRows, tideSparkline,
} from '../utils/windowFirstRows.js';

/** A tide rollup as `BriefingWindowTide` serialises one. */
function tide(overrides = {}) {
  return {
    locationName: 'Whitby',
    state: 'MID',
    direction: 'FALLING',
    nearestType: 'HW',
    nearestTime: '19:28',
    nearestOffset: '1h43 before sunset',
    range: '4.9 m',
    rangeAnomaly: '1.2 m above an average tide',
    seas: '0.3 m · smooth',
    curve: [0, 0.5, 1],
    windowPosition: 0.5,
    windowLevel: 0.5,
    ...overrides,
  };
}

/** A badge as `BriefingWindow.Badge` serialises one — facts always present, often empty. */
function badge(overrides = {}) {
  return {
    type: 'SNOW_TOPS',
    label: 'Snow on the fells',
    detail: 'Tops white above the valleys',
    facts: [
      { key: 'snow line', value: '~650 m', emphasis: true, optional: false },
      { key: null, value: '240 m below the tops', emphasis: false, optional: true },
    ],
    eventTime: '05:31',
    rarityRank: 8,
    ...overrides,
  };
}

/** The chips a row states, flattened to plain strings — what a reader sees, in order. */
const chips = (row) => row.facts.map((f) => f.segments.map((s) => s.text).join(' '));

describe('buildWindowRows — what a window may state as an attribute row', () => {
  it('renders no rows for a window with no tide and no promotable topic', () => {
    // The normal inland case, and the empty-payload case. The card must draw no container at all
    // rather than an empty one — the same call P5 made about the footer.
    expect(buildWindowRows({ verdict: 'MAYBE', badges: [] }).rows).toEqual([]);
    expect(buildWindowRows(null).rows).toEqual([]);
    expect(buildWindowRows(undefined).rows).toEqual([]);
  });

  describe('the tide row', () => {
    it('states where the water is, which way it is going, and the extreme nearest the light', () => {
      const [row] = buildWindowRows({ tide: tide(), badges: [] }).rows;

      expect(row.channel).toBe('tide');
      expect(row.kicker).toBe('≈ Tide');
      expect(chips(row)[0]).toBe('mid tide, falling');
      expect(chips(row)[1]).toBe('HW 19:28 · 1h43 before sunset');
    });

    it('emphasises the direction and the offset, which are the two facts a reader acts on', () => {
      const [row] = buildWindowRows({ tide: tide(), badges: [] }).rows;

      expect(row.facts[0].segments).toEqual([
        { text: 'mid tide,', tone: 'base' },
        { text: 'falling', tone: 'strong' },
      ]);
      expect(row.facts[1].segments[1]).toEqual({
        text: '1h43 before sunset', tone: 'strong',
      });
    });

    it.each([
      ['HIGH', 'high water, falling'],
      ['MID', 'mid tide, falling'],
      ['LOW', 'low water, falling'],
    ])('says %s as "%s"', (state, expected) => {
      const [row] = buildWindowRows({ tide: tide({ state }), badges: [] }).rows;

      expect(chips(row)[0]).toBe(expected);
    });

    it('says a rising tide is rising', () => {
      const [row] = buildWindowRows({ tide: tide({ direction: 'RISING' }), badges: [] }).rows;

      expect(chips(row)[0]).toBe('mid tide, rising');
    });

    it('names the coastal location every figure was measured at', () => {
      // Plan §2.4. Alignment differs ~20–30 minutes across a coastline and the chip above states an
      // offset to the minute, so an unattributed high-water time is a claim this project cannot
      // make. It rides the measured chip so the row needs no extra column.
      const [row] = buildWindowRows({ tide: tide(), badges: [] }).rows;

      expect(chips(row)).toContain('4.9 m · 1.2 m above an average tide · at Whitby');
    });

    it('drops the anomaly clause when there is no baseline, and never calls it average', () => {
      // Null means no historical baseline existed, which is a different statement from "about
      // average" — a phrase the backend says in words when it means it.
      const [row] = buildWindowRows({ tide: tide({ rangeAnomaly: null }), badges: [] }).rows;

      expect(chips(row)).toContain('4.9 m · at Whitby');
      expect(chips(row).join(' ')).not.toContain('average');
    });

    it('states "about average" when the backend says so', () => {
      const [row] = buildWindowRows({
        tide: tide({ rangeAnomaly: 'about average' }), badges: [],
      }).rows;

      expect(chips(row)).toContain('4.9 m · about average · at Whitby');
    });

    it('still renders the row past T+4, where there is a tide and no sea state', () => {
      // marine_wave reaches T+4 and tide_extreme reaches T+13, so the far half of the rail is
      // exactly this shape. A missing sea state must degrade on its own, never suppress the row.
      const [row] = buildWindowRows({ tide: tide({ seas: null }), badges: [] }).rows;

      expect(row.channel).toBe('tide');
      expect(chips(row)).toHaveLength(3);
      expect(chips(row).join(' ')).not.toContain('seas');
    });

    it('marks the sea state as the chip a phone may drop, and nothing else', () => {
      const [row] = buildWindowRows({ tide: tide(), badges: [] }).rows;

      expect(row.facts.filter((f) => f.optional).map((f) => f.segments[0].text))
        .toEqual(['seas 0.3 m · smooth']);
    });

    it('omits the nearest-extreme chip rather than half-stating it', () => {
      // The row is the whole accessible answer — the sparkline beside it is aria-hidden — so a
      // fact that cannot be completed must be absent, never approximated.
      const [row] = buildWindowRows({
        tide: tide({ nearestOffset: null }), badges: [],
      }).rows;

      expect(chips(row).join(' ')).not.toContain('19:28');
      expect(chips(row)[0]).toBe('mid tide, falling');
    });

    it('states the direction alone when the level is unknown', () => {
      const [row] = buildWindowRows({ tide: tide({ state: null }), badges: [] }).rows;

      expect(chips(row)[0]).toBe('falling');
    });

    it('drops the location clause when there is no name to state', () => {
      const [row] = buildWindowRows({ tide: tide({ locationName: null }), badges: [] }).rows;

      expect(chips(row)).toContain('4.9 m · 1.2 m above an average tide');
    });
  });

  describe('promotion — a topic is a row or a badge, never both', () => {
    it('promotes a snow topic that carries facts, and takes its badge with it', () => {
      const { rows, promoted } = buildWindowRows({ badges: [badge()] });

      expect(rows).toHaveLength(1);
      expect(rows[0].channel).toBe('snow');
      expect(rows[0].kicker).toBe('❄ Snow on the fells');
      expect(chips(rows[0])).toEqual(['snow line ~650 m', '240 m below the tops']);
      expect(promoted.has(badgeKey(badge()))).toBe(true);
    });

    it('leaves a snow topic with no facts as a badge, because a row would only repeat its label', () => {
      // The rule the duplication question turns on: a badge holds the label, a row holds the label
      // plus numbers. With no numbers the row is the label printed twice on one card.
      const { rows, promoted } = buildWindowRows({ badges: [badge({ facts: [] })] });

      expect(rows).toEqual([]);
      expect(promoted.size).toBe(0);
    });

    it('leaves a factful topic outside the snow channel as a badge', () => {
      // Promotion is scoped to the channels the design draws a row for. NLC carries facts and is
      // deliberately not promotable: four rows on a busy window is the height budget the cap exists
      // to defend.
      const nlc = badge({ type: 'NLC', label: '✦ NLC', rarityRank: 14 });

      expect(buildWindowRows({ badges: [nlc] }).rows).toEqual([]);
    });

    it('carries the emphasis the fact declares, and no other', () => {
      const { rows } = buildWindowRows({
        badges: [badge({
          facts: [
            { key: 'depth', value: '4 cm', emphasis: true, optional: false },
            { key: null, value: 'wind-scoured', emphasis: false, optional: false },
          ],
        })],
      });

      expect(rows[0].facts[0].segments).toEqual([
        { text: 'depth', tone: 'base' },
        { text: '4 cm', tone: 'strong' },
      ]);
      expect(rows[0].facts[1].segments).toEqual([{ text: 'wind-scoured', tone: 'base' }]);
    });

    it('carries the topic\'s own phone-droppable flag through to the row', () => {
      // The only thing that puts `.opt` on a promoted chip, and every other `optional` assertion in
      // this file is built from a TIDE row — so deleting the flag here would leave the suite green
      // while `240 m below the tops` stopped dropping on a phone. Both snow strategies really do
      // emit an optional chip (`SnowTopsHotTopicStrategy:119`, `SnowFreshHotTopicStrategy:159`).
      const { rows } = buildWindowRows({ badges: [badge()] });

      expect(rows[0].facts.filter((f) => f.optional).map((f) => f.segments.at(-1).text))
        .toEqual(['240 m below the tops']);
    });

    it('does not prefix a glyph onto a label that already opens with one', () => {
      // No snow strategy emits a glyph today, but topic labels elsewhere do (`✦ NLC`), and
      // `❄ ❄ Fresh snow` is what ships when a label changes in a file nobody thought was related.
      const { rows } = buildWindowRows({ badges: [badge({ label: '❄ Fresh snow' })] });

      expect(rows[0].kicker).toBe('❄ Fresh snow');
    });
  });

  describe('the two-row cap', () => {
    it('is two', () => {
      expect(MAX_ROWS).toBe(2);
    });

    it('drops the third row and leaves that topic its badge, so no fact is lost', () => {
      // A winter dawn genuinely offers this: SNOW_FRESH and SNOW_TOPS are separate strategies,
      // both anchored to sunrise, so a tide row plus two snow rows is three.
      const fresh = badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 });
      const tops = badge({ type: 'SNOW_TOPS', label: 'Snow on the fells', rarityRank: 8 });

      const { rows, promoted } = buildWindowRows({ tide: tide(), badges: [fresh, tops] });

      expect(rows).toHaveLength(2);
      expect(rows.map((r) => r.channel)).toEqual(['tide', 'snow']);
      // Rarest first — the same rank the payload publishes for the promoted strip, so the two
      // surfaces cannot disagree about which topic matters most.
      expect(rows[1].kicker).toBe('❄ Snow on the fells');
      expect(promoted.has(badgeKey(tops))).toBe(true);
      expect(promoted.has(badgeKey(fresh))).toBe(false);
    });

    it('allows two snow rows on a window with no tide, which is still within the cap', () => {
      const fresh = badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 });
      const tops = badge({ type: 'SNOW_TOPS', label: 'Snow on the fells', rarityRank: 8 });

      const { rows } = buildWindowRows({ badges: [fresh, tops] });

      expect(rows.map((r) => r.kicker)).toEqual(['❄ Snow on the fells', '❄ Fresh snow']);
    });

    it('puts the tide row first regardless of payload order', () => {
      // The tide row renders on every window of every day, so a card whose rows moved about
      // between days would read as a different card.
      const { rows } = buildWindowRows({ tide: tide(), badges: [badge()] });

      expect(rows.map((r) => r.channel)).toEqual(['tide', 'snow']);
    });
  });
});

describe('tideSparkline — pure geometry over the payload, and nothing else', () => {
  it('maps the curve onto the 104×24 box, first sample to last', () => {
    // x = i/(n−1)·104, y = (1−curve[i])·24 — plan §2.4a. Three samples put the middle at x = 52.
    const chart = tideSparkline(tide({ curve: [0, 0.5, 1] }));

    expect(chart.path).toBe('M0.00 24.00 L52.00 12.00 L104.00 0.00');
  });

  it('places the window mark from windowPosition and windowLevel', () => {
    const chart = tideSparkline(tide({ windowPosition: 0.25, windowLevel: 0.75 }));

    expect(chart.markX).toBe(26);
    expect(chart.markY).toBe(6);
  });

  it('draws a line from two samples, which is the fewest that make one', () => {
    expect(tideSparkline(tide({ curve: [0, 1] })).path).toBe('M0.00 24.00 L104.00 0.00');
  });

  it('draws nothing from one sample, rather than dividing by zero', () => {
    expect(tideSparkline(tide({ curve: [0.4] }))).toBeNull();
  });

  it('draws nothing from an empty or absent curve', () => {
    // Empty is what the record's own constructor normalises a null curve to, so this is the
    // shape a legacy or failed rollup actually arrives in.
    expect(tideSparkline(tide({ curve: [] }))).toBeNull();
    expect(tideSparkline(tide({ curve: null }))).toBeNull();
    expect(tideSparkline(null)).toBeNull();
  });

  it('draws nothing rather than a path containing NaN', () => {
    // An SVG path with NaN in it is not a degraded picture, it is a broken element — and the row's
    // facts already carry the whole answer, so the honest response is no picture.
    expect(tideSparkline(tide({ curve: [0, null, 1] }))).toBeNull();
    expect(tideSparkline(tide({ curve: [0, Number.NaN, 1] }))).toBeNull();
  });

  it('keeps the trace and drops only the mark when the instant cannot be placed', () => {
    // A trace with no mark still says what the day's water did. A mark defaulted to zero would say
    // the window sits at midnight at dead low water.
    const chart = tideSparkline(tide({ windowPosition: null }));

    expect(chart.path).toBe('M0.00 24.00 L52.00 12.00 L104.00 0.00');
    expect(chart.markX).toBeNull();
    expect(chart.markY).toBeNull();
  });

  it('is reached by the tide row, so a bad curve costs the picture and not the row', () => {
    const { rows } = buildWindowRows({ tide: tide({ curve: [] }), badges: [] });

    expect(rows).toHaveLength(1);
    expect(rows[0].chart).toBeNull();
    expect(chips(rows[0])[0]).toBe('mid tide, falling');
  });
});
