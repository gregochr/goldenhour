/**
 * Edge behaviour of `utils/mapTopicDays.js` over hand-built grids: the current column read without a
 * span, the sunset wording of a topic elsewhere, which column a collapsed day dot targets, and the
 * bar reading for a bar beside the window. Companion to mapTopicDays.test.js, which runs the real
 * `fourDayGrid`; each case pins an answer that suite never asks for.
 */
import { describe, it, expect } from 'vitest';
import { barLightState, currentDayIndex, topicDays } from '../utils/mapTopicDays.js';

const row = (name, on, type = 'INVERSION') => ({
  type, name, colour: '#abc', on,
});
const column = (eventType, over = {}) => ({
  eventType, interactive: true, row: 0, ...over,
});
const gridOf = (over = {}) => ({
  days: [
    { date: '2026-10-23', dow: 'Friday' },
    { date: '2026-10-24', dow: 'Saturday' },
  ],
  rows: [],
  columns: [
    column('SUNRISE'), column('SUNSET'), column('SUNRISE', { row: 1 }), column('SUNSET', { row: 1 }),
  ],
  currentSpan: null,
  currentColumn: -1,
  ...over,
});

describe('currentDayIndex — reading the current column', () => {
  it('reads a lone current column when no span is served', () => {
    expect(currentDayIndex(gridOf({ currentColumn: 3 }))).toBe(1);
    expect(currentDayIndex(gridOf({ currentColumn: 1 }))).toBe(0);
  });

  it('reads column zero as the first day', () => {
    expect(currentDayIndex(gridOf({ currentColumn: 0 }))).toBe(0);
  });

  it('answers -1 for a negative or absent current column', () => {
    expect(currentDayIndex(gridOf({ currentColumn: -1 }))).toBe(-1);
    expect(currentDayIndex(gridOf({ currentColumn: undefined }))).toBe(-1);
  });

  it('prefers a served span over the lone current column', () => {
    expect(currentDayIndex(gridOf({ currentSpan: { start: 2, end: 3 }, currentColumn: 0 }))).toBe(1);
  });
});

describe('topicDays — the current day', () => {
  it('words a topic that is only on the later light as "at sunset"', () => {
    const grid = gridOf({
      currentSpan: { start: 0, end: 0 },
      currentColumn: 0,
      rows: [row('Fog', [true, false]), row('Tide', [false, true])],
    });
    const [today] = topicDays(grid);
    expect(today.tooltip).toEqual({
      lead: 'On this window · Fog',
      rest: ' · Tide at sunset',
    });
    expect(today.dots.map((d) => [d.name, d.state])).toEqual([['Fog', 'on'], ['Tide', 'other']]);
  });

  it('words a topic on the earlier light of a later day as "at sunrise"', () => {
    const grid = gridOf({
      currentSpan: { start: 3, end: 3 },
      currentColumn: 3,
      rows: [row('Fog', [false, false, true, false]), row('Tide', [false, false, false, true])],
    });
    const tomorrow = topicDays(grid)[1];
    expect(tomorrow.tooltip).toEqual({
      lead: 'On this window · Tide',
      rest: ' · Fog at sunrise',
    });
  });

  it('says nothing is on the window when every topic is on the other light', () => {
    const grid = gridOf({
      currentSpan: { start: 0, end: 0 },
      currentColumn: 0,
      rows: [row('Tide', [false, true])],
    });
    expect(topicDays(grid)[0].tooltip).toEqual({
      lead: 'Nothing on this window',
      rest: ' · Tide at sunset',
    });
  });

  it('gives a current day with no topics no tooltip and no target', () => {
    const [today] = topicDays(gridOf({ currentSpan: { start: 0, end: 0 }, currentColumn: 0 }));
    expect(today).toMatchObject({ current: true, topicCount: 0, dots: [], target: null, tooltip: null });
  });
});

describe('topicDays — a day that is not current', () => {
  it('targets the first interactive column that carries a topic', () => {
    const grid = gridOf({
      rows: [row('Fog', [true, false]), row('Tide', [false, true])],
    });
    const [friday] = topicDays(grid);
    expect(friday.target).toEqual({ column: 0, row: 0, word: 'Fri sunrise' });
    expect(friday.tooltip).toEqual({ lead: 'Show Fri sunrise', rest: ' · Fog, Tide' });
  });

  it('skips a non-interactive first column and targets the second', () => {
    const grid = gridOf({
      columns: [
        column('SUNRISE', { interactive: false }), column('SUNSET'), column('SUNRISE'), column('SUNSET'),
      ],
      rows: [row('Fog', [true, true])],
    });
    expect(topicDays(grid)[0].target).toEqual({ column: 1, row: 0, word: 'Fri sunset' });
  });

  it('skips a column no topic is on, even when it is interactive', () => {
    const grid = gridOf({ rows: [row('Tide', [false, true])] });
    expect(topicDays(grid)[0].target).toEqual({ column: 1, row: 0, word: 'Fri sunset' });
  });

  it('gives no target, and does not throw, when the day has topics but the grid has no column for them', () => {
    const grid = gridOf({
      columns: [column('SUNRISE', { interactive: false })],
      rows: [row('Fog', [true, true])],
    });
    const [friday] = topicDays(grid);
    expect(friday).toMatchObject({ topicCount: 1, target: null, tooltip: null });
  });

  it('needs the SAME topic to be on a column for it to be the target', () => {
    const grid = gridOf({
      rows: [row('Fog', [true, false]), row('Tide', [false, true])],
    });
    // Both columns are interactive and each carries one topic: the earlier is the target.
    expect(topicDays(grid)[0].target.column).toBe(0);
  });

  it('gives a day with no topics no target and a plain empty dot list', () => {
    const [friday] = topicDays(gridOf());
    expect(friday).toMatchObject({
      index: 0, date: '2026-10-23', dow: 'Friday', label: 'FRI', current: false,
      topicCount: 0, dots: [], target: null, tooltip: null,
    });
  });

  it('keeps a short day name whole, rather than reading a word of the light as its abbreviation', () => {
    const grid = gridOf({
      days: [{ date: '2026-10-23', dow: 'Mo' }, { date: '2026-10-24', dow: 'Tu' }],
      rows: [row('Fog', [true, false])],
    });
    expect(topicDays(grid)[0].target.word).toBe('Mo sunrise');
  });

  it('shows at most three dots and counts every topic', () => {
    const grid = gridOf({
      rows: ['A', 'B', 'C', 'D'].map((name) => row(name, [true, false])),
    });
    const [friday] = topicDays(grid);
    expect(friday.topicCount).toBe(4);
    expect(friday.dots.map((d) => d.name)).toEqual(['A', 'B', 'C']);
    expect(friday.dots.every((d) => d.state === 'plain')).toBe(true);
  });
});

describe('barLightState — bars beside the window', () => {
  it('is null when there is no current column at all', () => {
    expect(barLightState(gridOf(), { start: 0, end: 1 })).toBeNull();
    expect(barLightState(gridOf({ currentColumn: -1 }), { start: -2, end: -1 })).toBeNull();
  });

  it('reads a lone current column when no span is served', () => {
    expect(barLightState(gridOf({ currentColumn: 1 }), { start: 0, end: 1 })).toBe('window');
  });

  it('calls a bar on the same day but off the window "other"', () => {
    const grid = gridOf({ currentSpan: { start: 0, end: 0 }, currentColumn: 0 });
    expect(barLightState(grid, { start: 1, end: 1 })).toBe('other');
  });

  it('calls a bar that overlaps the window "window"', () => {
    const grid = gridOf({ currentSpan: { start: 0, end: 1 }, currentColumn: 0 });
    expect(barLightState(grid, { start: 0, end: 0 })).toBe('window');
    expect(barLightState(grid, { start: 1, end: 1 })).toBe('window');
  });

  it('is null for a bar on another day', () => {
    const grid = gridOf({ currentSpan: { start: 0, end: 0 }, currentColumn: 0 });
    expect(barLightState(grid, { start: 2, end: 3 })).toBeNull();
  });
});
