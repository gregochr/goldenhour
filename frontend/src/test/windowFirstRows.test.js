import { describe, it, expect } from 'vitest';
import {
  buildWindowRows, tideSparkline, topicFacts,
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
  it('renders no rows for a window with no tide', () => {
    // The normal inland case, and the empty-payload case. The popup must draw no container at all
    // rather than an empty one — the same call P5 made about the footer.
    expect(buildWindowRows({ verdict: 'MAYBE', badges: [] })).toEqual([]);
    expect(buildWindowRows(null)).toEqual([]);
    expect(buildWindowRows(undefined)).toEqual([]);
  });

  describe('the tide row', () => {
    it('states where the water is, which way it is going, and the extreme nearest the light', () => {
      const [row] = buildWindowRows({ tide: tide(), badges: [] });

      expect(row.channel).toBe('tide');
      expect(row.kicker).toBe('≈ Tide');
      expect(chips(row)[0]).toBe('mid tide, falling');
      expect(chips(row)[1]).toBe('HW 19:28 · 1h43 before sunset');
    });

    it('emphasises the direction and the offset, which are the two facts a reader acts on', () => {
      const [row] = buildWindowRows({ tide: tide(), badges: [] });

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
      const [row] = buildWindowRows({ tide: tide({ state }), badges: [] });

      expect(chips(row)[0]).toBe(expected);
    });

    it('says a rising tide is rising', () => {
      const [row] = buildWindowRows({ tide: tide({ direction: 'RISING' }), badges: [] });

      expect(chips(row)[0]).toBe('mid tide, rising');
    });

    it('names the coastal location every figure was measured at', () => {
      // Plan §2.4. Alignment differs ~20–30 minutes across a coastline and the chip above states an
      // offset to the minute, so an unattributed high-water time is a claim this project cannot
      // make. It rides the measured chip so the row needs no extra column.
      const [row] = buildWindowRows({ tide: tide(), badges: [] });

      expect(chips(row)).toContain('4.9 m · 1.2 m above an average tide · at Whitby');
    });

    it('drops the anomaly clause when there is no baseline, and never calls it average', () => {
      // Null means no historical baseline existed, which is a different statement from "about
      // average" — a phrase the backend says in words when it means it.
      const [row] = buildWindowRows({ tide: tide({ rangeAnomaly: null }), badges: [] });

      expect(chips(row)).toContain('4.9 m · at Whitby');
      expect(chips(row).join(' ')).not.toContain('average');
    });

    it('states "about average" when the backend says so', () => {
      const [row] = buildWindowRows({
        tide: tide({ rangeAnomaly: 'about average' }), badges: [],
      });

      expect(chips(row)).toContain('4.9 m · about average · at Whitby');
    });

    it('still renders the row past T+4, where there is a tide and no sea state', () => {
      // marine_wave reaches T+4 and tide_extreme reaches months ahead, so most of the rail is
      // exactly this shape. A missing sea state must degrade on its own, never suppress the row.
      const [row] = buildWindowRows({ tide: tide({ seas: null }), badges: [] });

      expect(row.channel).toBe('tide');
      expect(chips(row)).toHaveLength(3);
      expect(chips(row).join(' ')).not.toContain('seas');
    });

    it('marks the sea state as the chip a phone may drop, and nothing else', () => {
      const [row] = buildWindowRows({ tide: tide(), badges: [] });

      expect(row.facts.filter((f) => f.optional).map((f) => f.segments[0].text))
        .toEqual(['seas 0.3 m · smooth']);
    });

    it('omits the nearest-extreme chip rather than half-stating it', () => {
      // The row is the whole accessible answer — the sparkline beside it is aria-hidden — so a
      // fact that cannot be completed must be absent, never approximated.
      const [row] = buildWindowRows({
        tide: tide({ nearestOffset: null }), badges: [],
      });

      expect(chips(row).join(' ')).not.toContain('19:28');
      expect(chips(row)[0]).toBe('mid tide, falling');
    });

    it('states the direction alone when the level is unknown', () => {
      const [row] = buildWindowRows({ tide: tide({ state: null }), badges: [] });

      expect(chips(row)[0]).toBe('falling');
    });

    it('drops the location clause when there is no name to state', () => {
      const [row] = buildWindowRows({ tide: tide({ locationName: null }), badges: [] });

      expect(chips(row)).toContain('4.9 m · 1.2 m above an average tide');
    });
  });

  describe('⚠️ topics are no longer promoted into rows, and the facts did not go with them', () => {
    // M2 deleted the promotion. The reasoning it embodied — "a topic renders once: as a row when it
    // has numbers, as a badge otherwise" — was about a card that had BOTH a header of chips and a
    // row band under it. The popup has neither: it states each topic once, in a topic row that
    // carries the label, the detail, the science note AND the measured facts. Promoting here would
    // print one topic twice, eight pixels apart.
    it('builds no row for a snow topic, however many facts it carries', () => {
      expect(buildWindowRows({ badges: [badge()] })).toEqual([]);
    });

    it('builds no row for two snow topics either, so nothing survives on a count', () => {
      const fresh = badge({ type: 'SNOW_FRESH', label: 'Fresh snow', rarityRank: 10 });
      const tops = badge({ type: 'SNOW_TOPS', label: 'Snow on the fells', rarityRank: 8 });
      expect(buildWindowRows({ badges: [fresh, tops] })).toEqual([]);
    });

    it('builds the tide row and nothing else on a window carrying both', () => {
      const rows = buildWindowRows({ tide: tide(), badges: [badge()] });
      expect(rows.map((r) => r.channel)).toEqual(['tide']);
    });

    it('⚠️ still maps a topic\'s facts, because the popup\'s topic rows read the same function', () => {
      // The mapping survived the promotion's deletion and is now exported. Deleting it would take
      // "snow line ~650 m" off the plan arm entirely — the strategies serve that figure as a FACT
      // and never as `detail`, and the surface that used to render it is gone.
      const mapped = topicFacts(badge({
        facts: [
          { key: 'depth', value: '4 cm', emphasis: true, optional: false },
          { key: null, value: 'wind-scoured', emphasis: false, optional: false },
        ],
      }));
      expect(mapped[0].segments).toEqual([
        { text: 'depth', tone: 'base' },
        { text: '4 cm', tone: 'strong' },
      ]);
      expect(mapped[1].segments).toEqual([{ text: 'wind-scoured', tone: 'base' }]);
    });

    it('carries the topic\'s own phone-droppable flag through the mapping', () => {
      // The only thing that puts `.opt` on a fact chip, and every other `optional` assertion in this
      // file is built from a TIDE row — so deleting the flag here would leave the suite green while
      // `240 m below the tops` stopped dropping on a narrow popup. Both snow strategies really do
      // emit an optional chip (`SnowTopsHotTopicStrategy:119`, `SnowFreshHotTopicStrategy:159`).
      expect(topicFacts(badge()).filter((f) => f.optional).map((f) => f.segments.at(-1).text))
        .toEqual(['240 m below the tops']);
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
    const rows = buildWindowRows({ tide: tide({ curve: [] }), badges: [] });

    expect(rows).toHaveLength(1);
    expect(rows[0].chart).toBeNull();
    expect(chips(rows[0])[0]).toBe('mid tide, falling');
  });
});
