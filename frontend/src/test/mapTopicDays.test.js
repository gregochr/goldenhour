/**
 * Tests for `utils/mapTopicDays.js` (the collapsed Hot topics row's day dots and the open grid's current-day
 * bar reading), `alignedCount` in `utils/mapTideFit.js`, and `utils/comingUpOpen.js`
 * (collapse-and-topic-pips-plan.md §2, §4, §5), over the REAL `fourDayGrid`.
 *
 * Friday 23 Oct 2026 is today. Fixture: see HOT below.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { fourDayGrid } from '../utils/mapTopics.js';
import {
  TOPIC_DOT_MAX, barLightState, currentDayIndex, topicDays,
} from '../utils/mapTopicDays.js';
import { alignedCount } from '../utils/mapTideFit.js';
import {
  COMING_UP_OPEN_KEY, defaultEntryOpen, isEntryOpen, readOpenOverrides, writeOpenOverrides,
} from '../utils/comingUpOpen.js';
import { buildTopicIndex } from '../utils/windowFirstTopics.js';

const FRI = '2026-10-23';
const SAT = '2026-10-24';
const SUN = '2026-10-25';
const MON = '2026-10-26';
const NOW = new Date('2026-10-23T09:00:00Z');

const solar = (date, eventType, extra = {}) => ({
  id: `solar:${date}:${eventType}`, kind: 'solar', date, eventType, badges: [], ...extra,
});

const EVENTS = [
  solar(FRI, 'SUNRISE', { past: true }),
  solar(FRI, 'SUNSET'),
  solar(SAT, 'SUNRISE'),
  solar(SAT, 'SUNSET'),
  solar(SUN, 'SUNRISE'),
  solar(SUN, 'SUNSET'),
  solar(MON, 'SUNSET'),
];

const topic = (type, label, date, eventType) => ({
  type, label, date, eventType, regions: [], locationNames: [],
});

/** Sat: spring tide on both lights. Sun: spring tide at sunrise, inversion at sunrise. Mon: inversion at sunrise (no EV row). */
const HOT = [
  topic('SPRING_TIDE', 'Spring tide', SAT, null),
  topic('SPRING_TIDE', 'Spring tide', SUN, 'SUNRISE'),
  topic('INVERSION', 'Inversion', SUN, 'SUNRISE'),
  topic('INVERSION', 'Inversion', MON, 'SUNRISE'),
];

function gridFor(activeRow, hotTopics = HOT, events = EVENTS) {
  return fourDayGrid({
    events,
    topicIndex: buildTopicIndex(hotTopics),
    hotTopics,
    scopeNames: [],
    bigSeas: [],
    todayStr: FRI,
    activeRow,
    now: NOW,
  });
}

const satSunset = EVENTS[3];

describe('topicDays', () => {
  it('returns one group per forecast day, labelled by weekday', () => {
    const days = topicDays(gridFor(satSunset));
    expect(days.map((d) => d.label)).toEqual(['FRI', 'SAT', 'SUN', 'MON']);
  });

  it('gives an empty day no dots and no tooltip', () => {
    const [fri] = topicDays(gridFor(satSunset));
    expect(fri.dots).toEqual([]);
    expect(fri.topicCount).toBe(0);
    expect(fri.tooltip).toBeNull();
    expect(fri.target).toBeNull();
  });

  it('marks the day of the selected window as current and moves with it', () => {
    expect(topicDays(gridFor(satSunset)).map((d) => d.current)).toEqual([false, true, false, false]);
    expect(topicDays(gridFor(EVENTS[4])).map((d) => d.current)).toEqual([false, false, true, false]);
    expect(currentDayIndex(gridFor(satSunset))).toBe(1);
  });

  it('has no current day when the window is outside the strip', () => {
    const grid = gridFor(null);
    expect(currentDayIndex(grid)).toBe(-1);
    expect(topicDays(grid).some((d) => d.current)).toBe(false);
  });

  it('draws plain dots on other days, in the panel row order, in the row colour', () => {
    const grid = gridFor(satSunset);
    const sun = topicDays(grid)[2];
    expect(sun.dots.map((d) => d.type)).toEqual(grid.rows.filter((r) => r.on[4] || r.on[5]).map((r) => r.type));
    expect(sun.dots.every((d) => d.state === 'plain')).toBe(true);
    expect(sun.dots[0].colour).toBe(grid.rows.find((r) => r.type === sun.dots[0].type).colour);
  });

  it('glows a current-day dot on this window and rings one that is only on the other light', () => {
    const sat = topicDays(gridFor(satSunset))[1];
    expect(sat.dots).toHaveLength(1);
    expect(sat.dots[0]).toMatchObject({ type: 'SPRING_TIDE', state: 'on' });
    // A tide topic covers both lights of its day, so on Sunday's sunset it is on this window and the
    // Sunday-sunrise inversion is only on the other light.
    const sun = topicDays(gridFor(EVENTS[5]))[2];
    expect(sun.dots.map((d) => [d.type, d.state])).toEqual([['SPRING_TIDE', 'on'], ['INVERSION', 'other']]);
    const sunSunrise = topicDays(gridFor(EVENTS[4]))[2];
    expect(sunSunrise.dots.map((d) => d.state)).toEqual(['on', 'on']);
  });

  it('caps the dots at three but names every topic in the tooltip', () => {
    const many = ['MIST', 'DUST', 'BLUEBELL', 'CLEARANCE'].map((t) => topic(t, t, SAT, 'SUNRISE'));
    const days = topicDays(gridFor(EVENTS[2], many));
    expect(days[1].dots).toHaveLength(TOPIC_DOT_MAX);
    expect(days[1].topicCount).toBe(4);
    const other = topicDays(gridFor(EVENTS[0], many))[1];
    expect(other.tooltip.rest.split(', ')).toHaveLength(4);
  });

  it('jumps to the day\'s first topic window, sunrise first, with the copy "Show Sun sunrise"', () => {
    const sun = topicDays(gridFor(satSunset))[2];
    expect(sun.target).toMatchObject({ column: 4, word: 'Sun sunrise' });
    expect(sun.target.row.id).toBe('solar:2026-10-25:SUNRISE');
    expect(sun.tooltip.lead).toBe('Show Sun sunrise');
    expect(sun.tooltip.rest).toMatch(/^ · /);
    expect(sun.tooltip.rest).toContain('Spring tide');
    expect(sun.tooltip.rest).toContain('Inversion');
  });

  it('falls to the sunset when only the sunset has a topic and an EV row', () => {
    const hot = [topic('INVERSION', 'Inversion', SUN, 'SUNSET')];
    const sun = topicDays(gridFor(satSunset, hot))[2];
    expect(sun.target).toMatchObject({ column: 5, word: 'Sun sunset' });
  });

  it('skips a topic window that has no EV row and offers no jump when none is left', () => {
    // Monday sunrise carries the inversion but has no EV row (only Monday sunset does).
    const mon = topicDays(gridFor(satSunset))[3];
    expect(mon.topicCount).toBe(1);
    expect(mon.target).toBeNull();
    expect(mon.tooltip).toBeNull();
  });

  it('skips a past window when choosing the jump column', () => {
    const hot = [topic('SPRING_TIDE', 'Spring tide', FRI, null)];
    const fri = topicDays(gridFor(satSunset, hot))[0];
    expect(fri.target).toMatchObject({ column: 1 });
  });

  it('words the current day\'s tooltip as on this window, naming the other light for the rest', () => {
    const sat = topicDays(gridFor(satSunset))[1];
    expect(sat.target).toBeNull();
    expect(sat.tooltip).toEqual({ lead: 'On this window · Spring tide', rest: '' });
    const dawnOnly = [topic('INVERSION', 'Inversion', SUN, 'SUNRISE')];
    const none = topicDays(gridFor(EVENTS[5], dawnOnly))[2];
    expect(none.tooltip.lead).toBe('Nothing on this window');
    expect(none.tooltip.rest).toBe(' · Inversion at sunrise');
    const mixed = topicDays(gridFor(EVENTS[5]))[2];
    expect(mixed.tooltip.lead).toBe('On this window · Spring tide');
    expect(mixed.tooltip.rest).toBe(' · Inversion at sunrise');
  });
});

describe('barLightState', () => {
  it('glows the bar on the selected window, dashes the same day\'s other light and ignores other days', () => {
    const grid = gridFor(satSunset);
    expect(barLightState(grid, { start: 3, end: 3 })).toBe('window');
    expect(barLightState(grid, { start: 2, end: 2 })).toBe('other');
    expect(barLightState(grid, { start: 2, end: 3 })).toBe('window');
    expect(barLightState(grid, { start: 4, end: 5 })).toBeNull();
    expect(barLightState(grid, { start: 0, end: 1 })).toBeNull();
  });

  it('is null for every bar when no window is selected in the strip', () => {
    expect(barLightState(gridFor(null), { start: 2, end: 3 })).toBeNull();
  });

  it('reads both lights of the day as the window for the daylight High-water row', () => {
    const hw = { id: 'hw:2026-10-24', kind: 'hw', date: SAT, eventType: 'HIGH_WATER' };
    const grid = gridFor(hw);
    expect(grid.currentSpan).toEqual({ start: 2, end: 3 });
    expect(barLightState(grid, { start: 2, end: 2 })).toBe('window');
    expect(barLightState(grid, { start: 3, end: 3 })).toBe('window');
  });
});

describe('alignedCount (collapse-and-topic-pips-plan.md §4)', () => {
  const spots = (n) => Array.from({ length: n }, (_, i) => ({ name: `s${i}` }));

  it('says K of N aligned when some are dimmed', () => {
    expect(alignedCount({ namedCoastal: spots(14), dimmed: spots(10), fitKnown: true }))
      .toEqual({ kind: 'some', text: '4 of 14 aligned', aligned: 4, total: 14 });
  });

  it('says none of N aligned when every spot is dimmed', () => {
    expect(alignedCount({ namedCoastal: spots(14), dimmed: spots(14), fitKnown: true }))
      .toMatchObject({ kind: 'none', text: 'none of 14 aligned' });
  });

  it('says all N aligned when nothing is dimmed', () => {
    expect(alignedCount({ namedCoastal: spots(14), dimmed: [], fitKnown: true }))
      .toMatchObject({ kind: 'all', text: 'all 14 aligned' });
  });

  it('says nothing when the fit is unknown or there is no coastal spot in view', () => {
    expect(alignedCount({ namedCoastal: spots(14), dimmed: [], fitKnown: false })).toBeNull();
    expect(alignedCount({ namedCoastal: [], dimmed: [], fitKnown: true })).toBeNull();
    expect(alignedCount(null)).toBeNull();
  });
});

describe('comingUpOpen (collapse-and-topic-pips-plan.md §5)', () => {
  const view = (startDate, extra = {}) => ({ id: `e:${startDate}`, startDate, inNext4Days: false, ...extra });
  const TODAY = '2026-11-03';

  beforeEach(() => { sessionStorage.clear(); });

  it('opens an entry tagged in the next four days whatever its start', () => {
    expect(defaultEntryOpen(view('2027-02-01', { inNext4Days: true }), TODAY)).toBe(true);
  });

  it('opens an entry starting within 30 days, inclusive, and closes a later one', () => {
    expect(defaultEntryOpen(view('2026-11-07'), TODAY)).toBe(true);
    expect(defaultEntryOpen(view('2026-12-03'), TODAY)).toBe(true);
    expect(defaultEntryOpen(view('2026-12-04'), TODAY)).toBe(false);
    expect(defaultEntryOpen(view('2027-01-15'), TODAY)).toBe(false);
  });

  it('opens a run that started before today', () => {
    expect(defaultEntryOpen(view('2026-10-30'), TODAY)).toBe(true);
  });

  it('lets the reader\'s press override the default either way', () => {
    const later = view('2027-01-15');
    const soon = view('2026-11-07');
    expect(isEntryOpen(later, { [later.id]: true }, TODAY)).toBe(true);
    expect(isEntryOpen(soon, { [soon.id]: false }, TODAY)).toBe(false);
    expect(isEntryOpen(soon, {}, TODAY)).toBe(true);
  });

  it('round-trips the presses through sessionStorage', () => {
    expect(readOpenOverrides()).toEqual({});
    writeOpenOverrides({ a: true, b: false });
    expect(JSON.parse(sessionStorage.getItem(COMING_UP_OPEN_KEY))).toEqual({ a: true, b: false });
    expect(readOpenOverrides()).toEqual({ a: true, b: false });
  });

  it('survives corrupt or unavailable storage', () => {
    sessionStorage.setItem(COMING_UP_OPEN_KEY, '{not json');
    expect(readOpenOverrides()).toEqual({});
    sessionStorage.setItem(COMING_UP_OPEN_KEY, '[1]');
    expect(readOpenOverrides()).toEqual({});
  });
});
