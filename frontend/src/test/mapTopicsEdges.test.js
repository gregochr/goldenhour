import { describe, it, expect } from 'vitest';
import {
  barTarget, bigSeasPlaces, currentColumnOf, fourDayGrid, hwTickPercent, labelTarget, placesFor,
  topicAnnouncement, topicsOnRow,
} from '../utils/mapTopics.js';
import { buildTopicIndex } from '../utils/windowFirstTopics.js';
import { formatEventTimeUk } from '../utils/conversions.js';

/**
 * Edge behaviour of the Map's chip and strip model: tolerance of the gaps real payloads have (a null
 * element, a missing index, a typeless topic), the exact boundaries of the strip's columns, and the
 * tide-at-the-light words. Friday 23 Oct 2026 is today; the strip runs Fri 23 .. Mon 26.
 */
const FRI = '2026-10-23';
const SAT = '2026-10-24';
const MON = '2026-10-26';
const NOW = new Date('2026-10-23T09:00:00Z');
const NE = 'Northumberland & Tyneside';

const solar = (date, eventType, extra = {}) => ({
  id: `solar:${date}:${eventType}`, kind: 'solar', date, eventType, badges: [], ...extra,
});

const topic = (overrides = {}) => ({
  type: 'SPRING_TIDE', label: 'Spring tide', date: FRI, eventType: null, regions: [], locationNames: [], ...overrides,
});

const tide = (overrides = {}) => ({
  locationName: 'North Shields',
  nearestType: 'HW',
  nearestTime: '18:00',
  nearestOffset: '1h09 after sunrise',
  lightAlignment: 'HW',
  nearestHeight: '4.9 m',
  eventTime: '06:50',
  ...overrides,
});

function gridFor(hotTopics, {
  events = [], activeRow = null, bigSeas = null, scopeNames = [], todayStr = FRI, ...rest
} = {}) {
  return fourDayGrid({
    events, topicIndex: buildTopicIndex(hotTopics), hotTopics, scopeNames, bigSeas, todayStr, activeRow, now: NOW, ...rest,
  });
}

const ROSTER = [
  { id: 1, name: 'Bamburgh', regionName: NE },
  { id: 2, name: 'Buttermere', regionName: 'The Lake District' },
];

describe('placesFor: tolerance', () => {
  it('skips a null topic among the served topics', () => {
    const places = placesFor('KING_TIDE', [null, { locationNames: ['Bamburgh'] }], ROSTER, []);

    expect(places.map((p) => p.name)).toEqual(['Bamburgh']);
  });

  it('keeps every roster place when the scope is not an array (not known yet), regions or not', () => {
    const topics = [{ locationNames: ['Bamburgh', 'Buttermere'] }];

    expect(placesFor('KING_TIDE', topics, ROSTER, null).map((p) => p.name)).toEqual(['Bamburgh', 'Buttermere']);
    expect(placesFor('KING_TIDE', topics, ROSTER, undefined).map((p) => p.name)).toEqual(['Bamburgh', 'Buttermere']);
  });

  it('survives a null spot in the roster while a scope is applied', () => {
    const places = placesFor('KING_TIDE', [{ locationNames: ['Bamburgh'] }], [null, ROSTER[0]], [NE]);

    expect(places.map((p) => p.name)).toEqual(['Bamburgh']);
  });
});

describe('bigSeasPlaces: tolerance', () => {
  it('skips a null structure and joins the others by id', () => {
    const byId = new Map([[1, { name: 'Roker' }]]);

    expect(bigSeasPlaces({ structures: [null, { locationId: 1 }] }, byId)).toEqual([{ name: 'Roker' }]);
  });

  it('joins nothing against a roster that is not a map', () => {
    expect(bigSeasPlaces({ structures: [{ locationId: 1 }] }, {})).toEqual([]);
  });
});

describe('topicsOnRow: tolerance and defaults', () => {
  const hot = [topic({ type: 'DUST', label: 'Dust', eventType: 'SUNSET' })];

  it('lists a solar row\'s topics as nothing when there is no topic index, or one that is not a map', () => {
    const row = solar(FRI, 'SUNSET');

    expect(topicsOnRow({ row, topicIndex: null, hotTopics: hot, scopeNames: [] })).toEqual([]);
    expect(topicsOnRow({ row, topicIndex: {}, hotTopics: hot, scopeNames: [] })).toEqual([]);
  });

  it('gives the daylight High-water row no topic although its date and light have one', () => {
    const row = { id: `hw:${FRI}`, kind: 'hw', date: FRI, eventType: 'SUNSET' };

    expect(topicsOnRow({ row, topicIndex: buildTopicIndex(hot), hotTopics: hot, scopeNames: [] })).toEqual([]);
  });

  it('reads a night row\'s topics past a null served topic', () => {
    const night = { id: `aur:${FRI}`, kind: 'aur', date: FRI, eventType: 'AURORA' };
    const aurora = topic({ type: 'AURORA', label: 'Aurora', eventType: 'NIGHT' });
    const chips = topicsOnRow({ row: night, topicIndex: null, hotTopics: [null, aurora], scopeNames: [] });

    expect(chips.map((c) => c.type)).toEqual(['AURORA']);
  });

  it('draws the tide-at-the-light clause only when asked (off by default)', () => {
    const row = solar(FRI, 'SUNRISE', { tide: tide() });
    const args = { row, topicIndex: buildTopicIndex([topic()]), hotTopics: [topic()], scopeNames: [] };

    expect(topicsOnRow(args)[0].light).toBeNull();
    expect(topicsOnRow({ ...args, tideLight: true })[0].light).toEqual({ align: 'HW', text: 'HW on the light' });
  });

  it('gives the Big seas chip no topics of its own', () => {
    const row = solar(FRI, 'SUNRISE');
    const [chip] = topicsOnRow({
      row, topicIndex: new Map(), hotTopics: [], scopeNames: [], bigSeasDay: { structures: [] },
    });

    expect(chip.bigSeas).toBe(true);
    expect(chip.topics).toEqual([]);
  });
});

describe('topicAnnouncement', () => {
  it('names the selection without a count when the chip has no places list', () => {
    expect(topicAnnouncement({ name: 'Dust', wholeSky: false, places: null }, true, true)).toBe('Dust selected.');
  });
});

describe('currentColumnOf', () => {
  const dates = [FRI, SAT, '2026-10-25', MON];

  it('is -1 for a sunrise row outside the strip, exactly as for a sunset one', () => {
    expect(currentColumnOf(solar('2026-11-30', 'SUNRISE'), dates, FRI)).toBe(-1);
    expect(currentColumnOf(solar('2026-11-30', 'SUNSET'), dates, FRI)).toBe(-1);
    expect(currentColumnOf(solar('2026-10-22', 'SUNRISE'), dates, FRI)).toBe(-1);
  });
});

describe('fourDayGrid: days and columns', () => {
  it('numbers the day without stripping a zero from the middle or end of it', () => {
    const grid = gridFor([], { todayStr: '2026-10-09' });

    expect(grid.days.map((d) => d.dayNum)).toEqual(['9', '10', '11', '12']);
    expect(gridFor([], { todayStr: '2026-10-19' }).days.map((d) => d.dayNum)).toEqual(['19', '20', '21', '22']);
    expect(gridFor([], { todayStr: '2026-10-29' }).days.map((d) => d.dayNum)).toEqual(['29', '30', '31', '1']);
  });

  it('lets the first row of a window win when the list holds two for it', () => {
    const first = solar(FRI, 'SUNSET', { id: 'first' });
    const second = solar(FRI, 'SUNSET', { id: 'second' });

    expect(gridFor([], { events: [first, second] }).columns[1].row.id).toBe('first');
  });

  it('survives a null event among the rows', () => {
    const grid = gridFor([], { events: [null, solar(FRI, 'SUNSET')] });

    expect(grid.columns[1].row.id).toBe(`solar:${FRI}:SUNSET`);
  });

  it('reads no topics when there is no topic index, or one that is not a map', () => {
    for (const topicIndex of [null, {}]) {
      const grid = fourDayGrid({
        events: [], topicIndex, hotTopics: [topic()], scopeNames: [], todayStr: FRI, now: NOW,
      });

      expect(grid.rows).toEqual([]);
    }
  });

  it('draws no row for a served topic that has no type and no window', () => {
    expect(gridFor([{}, null]).rows).toEqual([]);
  });

  it('keeps order and rows past a null served topic', () => {
    const grid = gridFor([null, topic({ type: 'DUST', label: 'Dust', eventType: 'SUNSET' })]);

    expect(grid.rows.map((r) => r.type)).toEqual(['DUST']);
  });

  it('shades the first column when it is the one on screen', () => {
    const row = solar(FRI, 'SUNRISE');
    const grid = gridFor([], { events: [row], activeRow: row });

    expect(grid.currentColumn).toBe(0);
    expect(grid.currentSpan).toEqual({ start: 0, end: 0 });
    expect(grid.currentSolar).toBe(true);
  });
});

describe('fourDayGrid: Big seas', () => {
  const day = (date, windowEnd) => ({ date, windowEnd });

  it('puts Big seas on both columns of today, the first day of the strip', () => {
    const grid = gridFor([], { bigSeas: [day(FRI, '2026-10-23T18:00:00Z')] });

    expect(grid.rows[0].type).toBe('BIG_SEAS');
    expect(grid.rows[0].on.slice(0, 3)).toEqual([true, true, false]);
  });

  it('adds no Big seas row for a day outside the strip, and survives a null day', () => {
    const grid = gridFor([], { bigSeas: [null, day('2026-11-30', '2026-11-30T18:00:00Z')] });

    expect(grid.rows).toEqual([]);
  });
});

describe('fourDayGrid: the daylight High-water tick', () => {
  const hwRow = (date, extra = {}) => ({
    id: `hw:${date}`, kind: 'hw', date, hwTime: `${date}T11:40:00Z`, daylightStart: `${date}T06:00:00Z`,
    daylightEnd: `${date}T16:00:00Z`, time: 'FALLBACK', ...extra,
  });

  it('puts a tick on today, with the served time, not the row\'s fallback text', () => {
    const row = hwRow(FRI);
    const grid = gridFor([], { events: [row], activeRow: row });

    expect(grid.hwTicks).toHaveLength(1);
    expect(grid.hwTicks[0]).toMatchObject({
      date: FRI, day: 0, time: formatEventTimeUk(row.hwTime), dow: 'Friday', current: true,
    });
    expect(grid.hwTicks[0].time).not.toBe('FALLBACK');
    expect(grid.hwTicks[0].percent).toBeCloseTo((340 / 600) * 100, 5);
  });

  it('draws no tick for a High-water row whose instant is missing or unreadable', () => {
    expect(hwTickPercent(hwRow(FRI, { hwTime: 'not-a-time' }))).toBeNull();
    expect(gridFor([], { events: [hwRow(FRI, { hwTime: undefined })] }).hwTicks).toEqual([]);
  });

  it('shades both columns of the day for the High-water row, and nothing for a day outside the strip', () => {
    const inside = hwRow(SAT);
    const outside = hwRow('2026-10-30');

    expect(gridFor([], { events: [inside], activeRow: inside }).currentSpan).toEqual({ start: 2, end: 3 });
    const grid = gridFor([], { events: [outside], activeRow: outside });
    expect(grid.currentSpan).toBeNull();
    expect(grid.hwTicks).toEqual([]);
  });

  it('shades the first day\'s two columns for a High-water row dated today', () => {
    const row = hwRow(FRI);

    expect(gridFor([], { events: [row], activeRow: row }).currentSpan).toEqual({ start: 0, end: 1 });
  });

  it('ignores a null event, and a solar row that happens to carry High-water fields', () => {
    const solarWithHw = solar(FRI, 'SUNSET', {
      hwTime: `${FRI}T11:40:00Z`, daylightStart: `${FRI}T06:00:00Z`, daylightEnd: `${FRI}T16:00:00Z`,
    });

    expect(gridFor([], { events: [null, solarWithHw] }).hwTicks).toEqual([]);
  });

  it('is null without a row, and without any of the three instants', () => {
    expect(hwTickPercent(null)).toBeNull();
    expect(hwTickPercent(undefined)).toBeNull();
    expect(hwTickPercent(hwRow(FRI, { daylightStart: null }))).toBeNull();
  });
});

describe('fourDayGrid: tide at the light', () => {
  const events = [
    solar(FRI, 'SUNRISE', { tide: tide() }),
    solar(FRI, 'SUNSET', { tide: tide({ lightAlignment: 'LW', nearestType: 'LW' }) }),
  ];

  it('draws no marks and no detail line unless the tide treatment is asked for', () => {
    const grid = gridFor([topic()], { events, activeRow: events[0] });

    expect(grid.rows[0]).not.toHaveProperty('marks');
    expect(grid).not.toHaveProperty('tideDetail');
    expect(gridFor([topic()], { events, activeRow: events[0], tideMarks: true })).toHaveProperty('tideDetail');
  });

  it('speaks the water\'s time and gap for a mark, whichever of the two is served', () => {
    const spoken = (served) => {
      const ev = [solar(FRI, 'SUNRISE', { tide: tide(served) })];
      return gridFor([topic()], { events: ev, tideMarks: true }).rows[0].marks[0].spoken;
    };

    expect(spoken({})).toBe('high water on the light Friday sunrise, 18:00, 1h09 after');
    expect(spoken({ nearestTime: null })).toBe('high water on the light Friday sunrise, 1h09 after');
    expect(spoken({ nearestOffset: null })).toBe('high water on the light Friday sunrise, 18:00');
    expect(spoken({ nearestTime: null, nearestOffset: null })).toBe('high water on the light Friday sunrise');
    expect(spoken({ nearestOffset: '' })).toBe('high water on the light Friday sunrise, 18:00');
  });

  it('marks a low-water light as low water', () => {
    const grid = gridFor([topic()], { events, tideMarks: true });

    expect(grid.rows[0].marks.map((m) => [m.column, m.align])).toEqual([[0, 'HW'], [1, 'LW']]);
    expect(grid.rows[0].marks[1].spoken).toMatch(/^low water on the light Friday sunset/);
  });

  describe('the detail line gap', () => {
    const gapFor = (offset) => {
      const ev = solar(FRI, 'SUNRISE', { tide: tide({ nearestOffset: offset }) });
      return gridFor([topic()], { events: [ev], activeRow: ev, tideMarks: true }).tideDetail.gap;
    };

    it('drops the trailing event word of "before" and "after" offsets', () => {
      expect(gapFor('1h09 after sunrise')).toBe('1h09 after');
      expect(gapFor('22m before sunset')).toBe('22m before');
    });

    it('keeps "at sunrise" whole, and leaves text that does not end in an event word alone', () => {
      expect(gapFor('at sunrise')).toBe('at sunrise');
      expect(gapFor('1h09 after sunrise, roughly')).toBe('1h09 after sunrise, roughly');
    });

    it('is null for a blank or non-text offset', () => {
      expect(gapFor('')).toBeNull();
      expect(gapFor(null)).toBeNull();
      expect(gapFor(5)).toBeNull();
    });
  });

  it('reads the detail line from column 0, the first light of the strip', () => {
    const grid = gridFor([topic()], { events, activeRow: events[0], tideMarks: true });

    expect(grid.currentColumn).toBe(0);
    expect(grid.tideDetail).toMatchObject({
      column: 0, type: 'SPRING_TIDE', sunWord: 'Fri sunrise', eventTime: '06:50', align: 'HW', kindWord: 'high',
      time: '18:00', gap: '1h09 after', height: '4.9 m', station: 'North Shields',
    });
  });

  it('prefers the tide-run row to the surge row whichever the server listed first', () => {
    const surge = topic({ type: 'STORM_SURGE', label: 'Storm surge', eventType: 'SUNRISE' });
    const spring = topic();

    for (const hot of [[surge, spring], [spring, surge]]) {
      const grid = gridFor(hot, { events, activeRow: events[0], tideMarks: true });

      expect(grid.rows.map((r) => r.type)).toEqual(hot.map((t) => t.type));
      expect(grid.tideDetail.type).toBe('SPRING_TIDE');
    }
  });

  it('has no detail line when the column on screen has no row of its own in the list', () => {
    const grid = gridFor([topic()], { events: [], activeRow: events[0], tideMarks: true });

    expect(grid.currentColumn).toBe(0);
    expect(grid.tideDetail).toBeNull();
  });

  it('has no detail line for a night row, nor for a window outside the strip', () => {
    const night = { id: `aur:${FRI}`, kind: 'aur', date: FRI, eventType: 'AURORA' };

    expect(gridFor([topic()], { events, activeRow: night, tideMarks: true }).tideDetail).toBeNull();
    const outside = solar('2026-11-30', 'SUNRISE', { tide: tide() });
    expect(gridFor([topic()], { events, activeRow: outside, tideMarks: true }).tideDetail).toBeNull();
  });
});

describe('barTarget: a grid without a precomputed span', () => {
  const columns = (n) => Array.from({ length: n }, () => ({ interactive: true }));

  it('stays on the current column when the bar covers it and the topic is on the row', () => {
    const grid = { currentColumn: 3, columns: columns(8) };

    expect(barTarget(grid, { start: 2, end: 4 }, true)).toEqual({ stay: true, column: 3 });
    expect(barTarget(grid, { start: 3, end: 3 }, true)).toEqual({ stay: true, column: 3 });
    expect(barTarget(grid, { start: 0, end: 0 }, true)).toEqual({ stay: false, column: 0 });
  });

  it('treats column 0 as a current column like any other', () => {
    const grid = { currentColumn: 0, columns: columns(8) };

    expect(barTarget(grid, { start: 0, end: 0 }, true)).toEqual({ stay: true, column: 0 });
    expect(barTarget(grid, { start: 0, end: 2 }, false)).toEqual({ stay: false, column: 0 });
  });

  it('stays on the current column even when a wider served span starts earlier', () => {
    const grid = { currentColumn: 3, currentSpan: { start: 2, end: 3 }, columns: columns(8) };

    expect(barTarget(grid, { start: 2, end: 3 }, true)).toEqual({ stay: true, column: 3 });
  });

  it('opens the current column rather than the bar\'s first, when the bar covers it', () => {
    const grid = { currentColumn: 2, columns: columns(8) };

    expect(barTarget(grid, { start: 0, end: 3 }, false)).toEqual({ stay: false, column: 2 });
  });

  it('falls to the bar\'s first openable column when the current one cannot be opened', () => {
    const cols = columns(8);
    cols[2] = { interactive: false };
    const grid = { currentColumn: 2, columns: cols };

    expect(barTarget(grid, { start: 1, end: 3 }, false)).toEqual({ stay: false, column: 1 });
  });

  it('is null for a bar reaching past the columns that has nothing to open', () => {
    const grid = { currentColumn: -1, columns: [{ interactive: false }, { interactive: false }] };

    expect(barTarget(grid, { start: 0, end: 3 }, false)).toBeNull();
  });

  it('is null for a bar that does not cover the current column when none of its columns opens', () => {
    const grid = { currentColumn: 6, columns: columns(8).map(() => ({ interactive: false })) };

    expect(barTarget(grid, { start: 0, end: 1 }, true)).toBeNull();
  });

  it('follows the whole-day span of a High-water row, staying on its first column', () => {
    const grid = { currentColumn: -1, currentSpan: { start: 0, end: 1 }, columns: columns(8) };

    expect(barTarget(grid, { start: 0, end: 1 }, true)).toEqual({ stay: true, column: 0 });
    expect(barTarget(grid, { start: 0, end: 1 }, false)).toEqual({ stay: false, column: 0 });
  });
});

describe('labelTarget edges', () => {
  it('jumps to column 0 when it is the first openable one', () => {
    const grid = { columns: [{ interactive: true }, { interactive: true }] };

    expect(labelTarget(grid, { on: [true, true] }, false)).toEqual({ toggle: false, column: 0 });
  });

  it('is null when the row is on only columns the grid does not have, or cannot open', () => {
    const grid = { columns: [{ interactive: false }, { interactive: false }] };

    expect(labelTarget(grid, { on: [false, true, true, true] }, false)).toBeNull();
  });
});
