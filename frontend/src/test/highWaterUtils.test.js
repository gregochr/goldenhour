/**
 * The pure helpers behind the daylight High-water row (hot-topics-by-day-plan §3 M3): the big-seas line's
 * action and copy, the chips' rings, the strip model's tick and whole-day shade, the peek list helpers.
 * The row itself is `mapEventsHighWater.test.js`'s; the component seams are the `.jsx` tests'.
 */
import { describe, it, expect } from 'vitest';
import { bigSeasFor, bigSeasCopy } from '../utils/bigSeas.js';
import { bigSeasChipItems, bigSeasChipAriaLabel, bigSeasChipTipLines } from '../utils/bigSeasChips.js';
import {
  fourDayGrid, barTarget, hwTickPercent, topicsOnRow, BIG_SEAS_TYPE,
} from '../utils/mapTopics.js';
import { isNightRow, otherWindow } from '../utils/mapPeek.js';
import { askWindowOf } from '../utils/askMapContext.js';
import { buildMapEvents } from '../utils/mapEvents.js';
import { formatEventTimeUk } from '../utils/conversions.js';
import { FRIDAY_HW, SATURDAY, solarRow } from './bigSeasFixtures.js';

const TODAY = '2026-10-22';
const FRI = '2026-10-23';
const NOW = new Date('2026-10-22T09:40:00Z');

function solarWindow(date, targetType) {
  return {
    key: `${date}:${targetType}`, date, targetType, label: `${date} ${targetType}`, time: '07:20',
    bestRating: 4, confidenceTier: 'high', badges: [],
  };
}

function eventsWith(bigSeas = [FRIDAY_HW]) {
  return buildMapEvents({
    solarWindows: [
      solarWindow(TODAY, 'SUNSET'),
      solarWindow(FRI, 'SUNRISE'),
      solarWindow(FRI, 'SUNSET'),
    ],
    forecastDates: [TODAY, FRI],
    todayStr: TODAY,
    tomorrowStr: FRI,
    formatTimeUk: formatEventTimeUk,
    bigSeas,
    now: NOW,
  });
}

const ROSTER = new Map([
  [243, { name: 'Roker Pier & Lighthouse', lat: 54.92, lon: -1.36, regionName: 'North East', driveMinutes: 45 }],
  [28, { name: "St. Mary's Lighthouse", lat: 55.07, lon: -1.44, regionName: 'North East', driveMinutes: null }],
  [21, { name: 'Amble', lat: 55.33, lon: -1.58, regionName: 'North East', driveMinutes: 80 }],
]);

describe('bigSeasFor / bigSeasCopy on a High-water day', () => {
  const events = eventsWith();
  const sunrise = events.find((e) => e.id === `solar:${FRI}:SUNRISE`);
  const hw = events.find((e) => e.kind === 'hw');
  const today = events.find((e) => e.id === `solar:${TODAY}:SUNSET`);

  it('jumps to the High-water row from the day\'s other rows, and the action reads "At high water 12:40 ›"', () => {
    const sel = bigSeasFor([FRIDAY_HW], sunrise, events, NOW);
    expect(sel.form).toBe('full');
    expect(sel.jumpRow).toBe(hw);
    expect(bigSeasCopy(sel).action).toBe('At high water 12:40 ›');
  });

  it('offers NO action on the High-water row itself', () => {
    const sel = bigSeasFor([FRIDAY_HW], hw, events, NOW);
    expect(sel.form).toBe('full');
    expect(sel.jumpRow).toBeNull();
    expect(bigSeasCopy(sel).action).toBeNull();
  });

  it('prints the first clause "… · high water 12:40, between the lights" (never "midday")', () => {
    const copy = bigSeasCopy(bigSeasFor([FRIDAY_HW], sunrise, events, NOW));
    expect(copy.line1).toBe('11:30–14:00 · high water 12:40, between the lights');
    expect(copy.line1).not.toMatch(/midday/);
    // The HW row reads the same clause (the line speaks of the day).
    expect(bigSeasCopy(bigSeasFor([FRIDAY_HW], hw, events, NOW)).line1).toBe(copy.line1);
  });

  it('carries the row\'s lead copy, without "in flat midday light"', () => {
    const copy = bigSeasCopy(bigSeasFor([FRIDAY_HW], hw, events, NOW));
    expect(copy.lead).toBe(
      'High water at 12:40 with 3.4 m waves from NNE. Not a light window: this is for spray over the piers, not for colour.',
    );
    expect(copy.lead).not.toMatch(/midday/);
  });

  it('on an EARLIER day the compact form still points at the day\'s High-water row', () => {
    const sel = bigSeasFor([FRIDAY_HW], today, events, NOW);
    expect(sel.form).toBe('compact');
    expect(sel.jumpRow).toBe(hw);
    expect(bigSeasCopy(sel).action).toBe('See Friday ›');
  });

  it('leaves an aligned day\'s copy exactly as it was (no lead, no between-the-lights clause)', () => {
    const satEvents = [solarRow('2026-10-24', 'SUNRISE'), solarRow('2026-10-24', 'SUNSET')];
    const copy = bigSeasCopy(bigSeasFor([SATURDAY], satEvents[0], satEvents, NOW));
    expect(copy.lead).toBeUndefined();
    expect(copy.line1).toBe('15:30–18:15 · high water 16:58');
  });

  it('never jumps to a High-water row that has gone (the list no longer holds it)', () => {
    const expired = { ...FRIDAY_HW, windowEnd: '2026-10-22T09:00:00' };
    const gone = eventsWith([expired]);
    expect(gone.some((e) => e.kind === 'hw')).toBe(false);
    const sel = bigSeasFor([expired], gone.find((e) => e.id === `solar:${TODAY}:SUNSET`), gone, NOW);
    expect(sel.form).toBeNull();
  });
});

describe('bigSeasChipItems on the High-water row', () => {
  const hw = eventsWith().find((e) => e.kind === 'hw');

  it('draws a chip per structure and rings EVERY one (high water is the event)', () => {
    const items = bigSeasChipItems(FRIDAY_HW, ROSTER, 9, hw);
    expect(items).toHaveLength(3);
    expect(items.every((i) => i.ringed)).toBe(true);
  });

  it('draws none on a past High-water row or at street level', () => {
    expect(bigSeasChipItems(FRIDAY_HW, ROSTER, 9, { ...hw, past: true })).toHaveLength(0);
    expect(bigSeasChipItems(FRIDAY_HW, ROSTER, 13, hw)).toHaveLength(0);
  });

  it('names the ring in words: "at high water", the tooltip carries each structure\'s own high water', () => {
    const [first] = bigSeasChipItems(FRIDAY_HW, ROSTER, 9, hw);
    expect(bigSeasChipAriaLabel(first)).toMatch(/, at high water$/);
    expect(bigSeasChipTipLines(first).some((l) => l.startsWith('High water '))).toBe(true);
  });
});

describe('topicsOnRow on the High-water row', () => {
  const hw = eventsWith().find((e) => e.kind === 'hw');
  const sunrise = eventsWith().find((e) => e.id === `solar:${FRI}:SUNRISE`);
  const kingTide = {
    type: 'KING_TIDE', label: 'King tide', date: FRI, eventType: 'SUNRISE', locationNames: [],
  };
  const topicIndex = new Map([[`${FRI}|SUNRISE`, [kingTide]]]);

  it('is Big seas only, named "Big seas · HW 12:40"', () => {
    const chips = topicsOnRow({
      row: hw, topicIndex, hotTopics: [kingTide], scopeNames: [], bigSeasDay: FRIDAY_HW, locationsById: ROSTER,
    });
    expect(chips.map((c) => c.type)).toEqual([BIG_SEAS_TYPE]);
    expect(chips[0].name).toBe('Big seas · HW 12:40');
    expect(chips[0].label).toBe('Big seas · HW 12:40, 3 structures');
    expect(chips[0].places).toHaveLength(3);
  });

  it('a day with no flag keeps the plain name', () => {
    const chips = topicsOnRow({
      row: sunrise, topicIndex: new Map(), hotTopics: [], scopeNames: [], bigSeasDay: SATURDAY, locationsById: ROSTER,
    });
    expect(chips[0].name).toBe('Big seas');
  });
});

describe('fourDayGrid: the High-water tick and the whole-day shade', () => {
  const grid = (bigSeasDay = FRIDAY_HW, activeRow = null) => {
    const events = eventsWith([bigSeasDay]);
    return fourDayGrid({
      events,
      topicIndex: new Map(),
      hotTopics: [],
      scopeNames: [],
      bigSeas: [bigSeasDay],
      todayStr: TODAY,
      activeRow: activeRow ?? events.find((e) => e.id === `solar:${FRI}:SUNRISE`),
      now: NOW,
    });
  };

  it('places the tick proportionally between the served sunrise and sunset (51.6% of the day)', () => {
    const g = grid();
    expect(g.hwTicks).toHaveLength(1);
    const [tick] = g.hwTicks;
    expect(tick.day).toBe(1);
    expect(tick.time).toBe('12:40');
    expect(tick.dow).toBe('Friday');
    expect(tick.percent).toBeCloseTo((320 / 620) * 100, 5);
    expect(tick.current).toBe(false);
  });

  it('clamps to 4 to 96% of the day', () => {
    expect(hwTickPercent({
      hwTime: '2026-10-23T06:21:00', daylightStart: '2026-10-23T06:20:00', daylightEnd: '2026-10-23T16:40:00',
    })).toBe(4);
    expect(hwTickPercent({
      hwTime: '2026-10-23T16:39:00', daylightStart: '2026-10-23T06:20:00', daylightEnd: '2026-10-23T16:40:00',
    })).toBe(96);
  });

  it('draws no tick without served bounds (never a guess), or with an empty day', () => {
    expect(grid({ ...FRIDAY_HW, daylightStart: undefined }).hwTicks).toEqual([]);
    expect(grid({ ...FRIDAY_HW, daylightEnd: undefined }).hwTicks).toEqual([]);
    expect(grid({ ...FRIDAY_HW, daylightEnd: FRIDAY_HW.daylightStart }).hwTicks).toEqual([]);
    expect(hwTickPercent({ hwTime: 'nonsense', daylightStart: FRIDAY_HW.daylightStart, daylightEnd: FRIDAY_HW.daylightEnd }))
      .toBeNull();
  });

  it('marks the tick current when the High-water row is the active row, and shades the WHOLE day', () => {
    const events = eventsWith();
    const hw = events.find((e) => e.kind === 'hw');
    const g = grid(FRIDAY_HW, hw);
    expect(g.hwTicks[0].current).toBe(true);
    // Friday is the strip's second day: columns 2 and 3.
    expect(g.currentSpan).toEqual({ start: 2, end: 3 });
    expect(g.currentColumn).toBe(-1);
  });

  it('shades one column for a solar window', () => {
    const g = grid();
    expect(g.currentSpan).toEqual({ start: 2, end: 2 });
  });

  it('a Big seas bar over the High-water day is "here" on the HW row: pressing it selects in place', () => {
    const events = eventsWith();
    const hw = events.find((e) => e.kind === 'hw');
    const g = grid(FRIDAY_HW, hw);
    const seas = g.rows.find((r) => r.bigSeas);
    const bar = seas.bars.find((b) => b.start <= 2 && b.end >= 3);
    expect(barTarget(g, bar, true)).toEqual({ stay: true, column: 2 });
  });
});

describe('the peek list helpers', () => {
  const events = eventsWith();
  const hw = events.find((e) => e.kind === 'hw');

  it('isNightRow is false for the High-water row (it is neither a night nor a solar window)', () => {
    expect(isNightRow(hw)).toBe(false);
    expect(isNightRow({ kind: 'astro' })).toBe(true);
    expect(isNightRow({ kind: 'solar' })).toBe(false);
  });

  it('the "other window" is never the High-water row, in the scan or the fallback', () => {
    const list = [solarRow(FRI, 'SUNRISE'), hw, solarRow(FRI, 'SUNSET')];
    // From the sunrise the next row is the HW row: it is skipped for the sunset.
    expect(otherWindow(list, 0, new Map())).toBe(list[2]);
    // With every other solar row stood down, the fallback still never names it.
    const verdicts = new Map([[list[2].id, { tier: 'STAND_DOWN' }]]);
    expect(otherWindow(list, 0, verdicts)).toBe(list[2]);
    expect(otherWindow([list[0], hw], 0, new Map())).toBeNull();
  });
});

describe('askWindowOf', () => {
  it('names no Ask window for the High-water row', () => {
    expect(askWindowOf(eventsWith().find((e) => e.kind === 'hw'))).toBeNull();
  });
});
