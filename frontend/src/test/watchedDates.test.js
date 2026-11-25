import { describe, it, expect } from 'vitest';
import {
  buildWatchedLanes, entryMatchesWatch, matchWatchToEntry, parseWatchKey, TIDE_DRIFT_DAYS, unlistedWatches, watchedStillToCome,
  watchKeyOf, watchKeysMatching, watchedKeysFor, watchedWithoutPlan, WATCH_NO_WINDOW_LINE, WATCHABLE_TYPES,
} from '../utils/watchedDates.js';

/** The watch match and the watched lanes (one-screen-plan.md D16): joins over served fields, no new fact. */

const TODAY = '2026-11-24';

const tide = (over = {}) => ({
  id: 'king-tide:2026-12-03:2026-12-07',
  type: 'king-tide',
  family: 'coastal',
  title: 'King tide',
  startDate: '2026-12-03',
  endDate: '2026-12-07',
  coincidence: [],
  ...over,
});

const card = (date, targetType, over = {}) => ({
  key: `${date}:${targetType}`,
  date,
  targetType,
  dow: 'Wed',
  sunrise: targetType === 'SUNRISE',
  time: targetType === 'SUNRISE' ? '07:48' : '15:40',
  away: false,
  tide: null,
  bestReach: null,
  bestRating: null,
  ...over,
});

describe('the watch key', () => {
  it('is the type and the START date, for every watchable type; a warning or an unknown type has none', () => {
    expect(watchKeyOf(tide())).toBe('king-tide:2026-12-03');
    for (const type of WATCHABLE_TYPES) {
      expect(watchKeyOf({ type, startDate: '2026-12-14' })).toBe(`${type}:2026-12-14`);
    }
    expect(watchKeyOf({ type: 'weather-warning', startDate: '2026-12-14' })).toBeNull();
    // The NLC season's served start is clipped to the feed's first day, so a key on it would slide and expire early.
    expect(watchKeyOf({ type: 'nlc-season', startDate: '2026-12-14' })).toBeNull();
    expect(WATCHABLE_TYPES).not.toContain('nlc-season');
    expect(parseWatchKey('nlc-season:2026-12-14')).toBeNull();
    expect(watchKeyOf({ type: 'king-tide', startDate: '2026-12-14', warning: {} })).toBeNull();
    expect(watchKeyOf({ type: 'king-tide' })).toBeNull();
    expect(watchKeyOf(null)).toBeNull();
  });

  it('parses a stored key, and refuses anything else', () => {
    expect(parseWatchKey('king-tide:2026-12-03')).toEqual({ type: 'king-tide', date: '2026-12-03' });
    expect(parseWatchKey('aurora:2026-12-03')).toBeNull();
    expect(parseWatchKey('king-tide')).toBeNull();
    expect(parseWatchKey('king-tide:3 Dec')).toBeNull();
    expect(parseWatchKey(7)).toBeNull();
  });
});

describe('matching a watch to a served entry', () => {
  it('an exact key matches; a tide matches by family, so a reclassified run is the same watch', () => {
    expect(entryMatchesWatch('king-tide:2026-12-03', tide())).toBe(true);
    expect(entryMatchesWatch('spring-tide:2026-12-03', tide())).toBe(true);
    expect(entryMatchesWatch('king-tide:2026-12-03', tide({ type: 'spring-tide' }))).toBe(true);
  });

  it('a tide start that drifted by the tolerance still matches, one past it does not', () => {
    const drifted = tide({ startDate: '2026-12-06', endDate: '2026-12-10' });
    expect(TIDE_DRIFT_DAYS).toBe(3);
    expect(entryMatchesWatch('king-tide:2026-12-03', drifted)).toBe(true);
    expect(entryMatchesWatch('king-tide:2026-12-02', drifted)).toBe(false);
  });

  it('a tide whose end moved still matches, because the key carries no end', () => {
    expect(entryMatchesWatch('king-tide:2026-12-03', tide({ endDate: '2026-12-12' }))).toBe(true);
  });

  it('a non-tide watch matches only an entry of its own type that covers the day', () => {
    const moon = { id: 'supermoon:2026-12-04', type: 'supermoon', startDate: '2026-12-04', endDate: '2026-12-05' };
    expect(entryMatchesWatch('supermoon:2026-12-04', moon)).toBe(true);
    expect(entryMatchesWatch('supermoon:2026-12-05', moon)).toBe(true);
    expect(entryMatchesWatch('supermoon:2026-12-06', moon)).toBe(false);
    expect(entryMatchesWatch('meteor:2026-12-04', moon)).toBe(false);
  });

  it('a tide merged into a supermoon entry still holds the tide, on its own days', () => {
    const merged = {
      id: 'supermoon:2026-12-01', type: 'supermoon', startDate: '2026-12-01', endDate: '2026-12-09',
      coincidence: [{ type: 'king-tide' }],
      dayRanges: [{ date: '2026-12-05' }, { date: '2026-12-06' }, { date: '2026-12-02', shoulder: true }],
    };
    expect(entryMatchesWatch('king-tide:2026-12-05', merged)).toBe(true);
    // The union's own start is not the tide's: the tide's days are 5 to 6.
    expect(entryMatchesWatch('king-tide:2026-12-12', merged)).toBe(false);
    expect(entryMatchesWatch('king-tide:2026-12-05', { ...merged, dayRanges: [] })).toBe(false);
  });

  it('nothing matches a warning, a bad key or a missing entry', () => {
    expect(entryMatchesWatch('king-tide:2026-12-03', tide({ warning: {} }))).toBe(false);
    expect(entryMatchesWatch('nonsense', tide())).toBe(false);
    expect(entryMatchesWatch('king-tide:2026-12-03', null)).toBe(false);
    expect(entryMatchesWatch('king-tide:2026-12-03', { type: 'king-tide' })).toBe(false);
  });

  it('with two matches the earliest wins; none, or no feed yet, is null', () => {
    const later = tide({ id: 'a', startDate: '2026-12-05', endDate: '2026-12-08' });
    const earlier = tide({ id: 'b', startDate: '2026-12-02', endDate: '2026-12-04', regions: ['x'] });
    expect(matchWatchToEntry('king-tide:2026-12-03', [later, earlier]).id).toBe('b');
    expect(matchWatchToEntry('king-tide:2026-12-03', [later, earlier].reverse()).id).toBe('b');
    expect(matchWatchToEntry('king-tide:2027-03-03', [later])).toBeNull();
    expect(matchWatchToEntry('king-tide:2026-12-03', null)).toBeNull();
  });

  it('lists the stored keys that are an entry, as strings or served rows', () => {
    const dates = [{ key: 'king-tide:2026-12-03' }, 'spring-tide:2026-12-04', { key: 'meteor:2026-12-14' }];
    expect(watchKeysMatching(dates, tide())).toEqual(['king-tide:2026-12-03', 'spring-tide:2026-12-04']);
    expect(watchKeysMatching(null, tide())).toEqual([]);
  });
});

describe('the lanes for watched dates', () => {
  const dates = [{ key: 'king-tide:2026-12-03' }];
  const lanes = (over) => buildWatchedLanes({
    dates, entries: [tide()], windows: [], hotTopics: [], todayStr: '2026-12-02', ...over,
  });

  it('inside the forecast, a tide lane names the window marked on the light, the best spot and its stars', () => {
    const windows = [
      card('2026-12-03', 'SUNRISE', { tide: { lightAlignment: 'NONE' } }),
      card('2026-12-03', 'SUNSET', { dow: 'Thu', time: '15:40', tide: { lightAlignment: 'LW' }, bestReach: { rating: 3, locationId: 5, locationName: 'Seaton' } }),
      card('2026-12-04', 'SUNRISE', { dow: 'Fri', tide: { lightAlignment: 'HW' }, bestReach: { rating: 4, locationId: 7, locationName: 'Crimdon Beach' } }),
    ];
    const [lane] = lanes({ windows, entries: [tide({ metric: '4.6 m' })] });
    expect(lane).toEqual({
      key: 'king-tide:2026-12-03',
      entryId: 'king-tide:2026-12-03:2026-12-07',
      kind: 'window',
      date: '2026-12-04',
      title: 'King tide',
      name: 'King tide · 4.6 m',
      plan: { dow: 'Fri', event: 'sunrise 07:48', best: { id: 7, name: 'Crimdon Beach', rating: 4 }, tideOnLight: true },
      window: { date: '2026-12-04', eventType: 'SUNRISE' },
      bestSpot: { id: 7, name: 'Crimdon Beach' },
    });
  });

  it('beyond the forecast, or in it with no window on the light, draws no lane: the date stays in Watching', () => {
    expect(lanes({ windows: [card('2026-11-24', 'SUNRISE')], todayStr: TODAY })).toEqual([]);
    const windows = [card('2026-12-03', 'SUNRISE', { tide: { lightAlignment: 'NONE' } }), card('2026-12-04', 'SUNRISE')];
    expect(lanes({ windows })).toEqual([]);
  });

  it('two windows on the light: the better served rating wins, the earlier on a tie', () => {
    const a = card('2026-12-03', 'SUNRISE', { tide: { lightAlignment: 'HW' }, bestReach: { rating: 3, locationId: 1, locationName: 'A' } });
    const b = card('2026-12-04', 'SUNRISE', { tide: { lightAlignment: 'HW' }, bestReach: { rating: 5, locationId: 2, locationName: 'B' } });
    const c = card('2026-12-05', 'SUNRISE', { tide: { lightAlignment: 'HW' }, bestReach: { rating: 5, locationId: 3, locationName: 'C' } });
    const pick = (windows) => lanes({ windows })[0];
    expect(pick([a, b]).date).toBe('2026-12-04');
    expect(pick([a, b, c]).date).toBe('2026-12-04');
    // With no reach pick the window's own served best is the rating, and an unrated window ranks last.
    const rated = card('2026-12-05', 'SUNSET', { tide: { lightAlignment: 'LW' }, bestRating: 4 });
    const unrated = card('2026-12-03', 'SUNSET', { tide: { lightAlignment: 'LW' } });
    expect(pick([unrated, rated]).date).toBe('2026-12-05');
    expect(pick([unrated, rated]).plan).toEqual({ dow: 'Wed', event: 'sunset 15:40', best: null, tideOnLight: true });
    expect(pick([unrated]).date).toBe('2026-12-03');
  });

  it('an away window is never chosen, and a window outside the run is ignored', () => {
    const away = card('2026-12-03', 'SUNRISE', { away: true, tide: { lightAlignment: 'HW' } });
    const outside = card('2026-12-09', 'SUNRISE', { tide: { lightAlignment: 'HW' } });
    expect(lanes({ windows: [away, outside] })).toEqual([]);
  });

  it('a supermoon opens its served topic window: a night topic is that evening\'s sunset', () => {
    const moon = { id: 'supermoon:2026-12-04', type: 'supermoon', family: 'night-sky', title: 'Supermoon', startDate: '2026-12-04', endDate: '2026-12-05' };
    const windows = [
      card('2026-12-04', 'SUNRISE'),
      card('2026-12-04', 'SUNSET', { dow: 'Fri', time: '15:39', bestReach: { rating: 4, locationId: 9, locationName: 'Bamburgh' } }),
      card('2026-12-05', 'SUNSET', { dow: 'Sat' }),
    ];
    const hotTopics = [{ type: 'SUPERMOON', date: '2026-12-04', eventType: 'NIGHT' }, { type: 'AURORA', date: '2026-12-04', eventType: 'NIGHT' }];
    const [lane] = buildWatchedLanes({
      dates: [{ key: 'supermoon:2026-12-04' }], entries: [moon], windows, hotTopics, todayStr: '2026-12-03',
    });
    expect(lane).toMatchObject({
      kind: 'window', name: 'Supermoon', date: '2026-12-04',
      plan: { dow: 'Fri', event: 'sunset 15:39', best: { name: 'Bamburgh', rating: 4 }, tideOnLight: false },
      window: { date: '2026-12-04', eventType: 'SUNSET' },
    });
  });

  it('a topic that anchors its own sunrise opens that one (an eclipse)', () => {
    const eclipse = { id: 'eclipse:2026-12-04', type: 'eclipse', family: 'eclipse', title: 'Solar eclipse', startDate: '2026-12-04', endDate: '2026-12-04' };
    const windows = [card('2026-12-04', 'SUNRISE'), card('2026-12-04', 'SUNSET')];
    const hotTopics = [{ type: 'ECLIPSE', date: '2026-12-04', eventType: 'SUNRISE' }];
    const [lane] = buildWatchedLanes({
      dates: [{ key: 'eclipse:2026-12-04' }], entries: [eclipse], windows, hotTopics, todayStr: '2026-12-03',
    });
    expect(lane.window).toEqual({ date: '2026-12-04', eventType: 'SUNRISE' });
  });

  it('a meteor peak has no window: a static lane that says so, on the first forecast day it covers', () => {
    const meteor = { id: 'meteor:2026-12-12:2026-12-14', type: 'meteor', family: 'night-sky', title: 'Geminids', startDate: '2026-12-12', endDate: '2026-12-14' };
    const [lane] = buildWatchedLanes({
      dates: [{ key: 'meteor:2026-12-12' }], entries: [meteor],
      windows: [card('2026-12-13', 'SUNSET'), card('2026-12-14', 'SUNSET')],
      hotTopics: [{ type: 'METEOR', date: '2026-12-14', eventType: 'NIGHT' }], todayStr: '2026-12-13',
    });
    expect(lane).toMatchObject({ kind: 'static', date: '2026-12-13', plan: null, window: null, bestSpot: null, name: 'Geminids' });
    expect(WATCH_NO_WINDOW_LINE).toBe('clear-sky check on the night');
    // Beyond the forecast the same meteor draws no lane.
    expect(buildWatchedLanes({ dates: [{ key: 'meteor:2026-12-12' }], entries: [meteor], windows: [], hotTopics: [], todayStr: '2026-12-01' }))
      .toEqual([]);
  });

  it('a topic the served topics do not name draws no lane', () => {
    const solstice = { id: 'solstice:2026-12-21', type: 'solstice', family: 'sun-moon', title: 'Winter solstice', startDate: '2026-12-21', endDate: '2026-12-21' };
    expect(buildWatchedLanes({
      dates: [{ key: 'solstice:2026-12-21' }], entries: [solstice], windows: [card('2026-12-21', 'SUNRISE')], hotTopics: null, todayStr: '2026-12-20',
    })).toEqual([]);
  });

  it('a watch whose dates have passed, or the feed does not list, draws no lane; two keys of one entry draw one; earliest first', () => {
    const passed = tide({ id: 'p', startDate: '2026-11-18', endDate: '2026-11-22' });
    const moon = { id: 'supermoon:2026-12-01', type: 'supermoon', title: 'Supermoon', startDate: '2026-12-01', endDate: '2026-12-01' };
    const windows = [
      card('2026-12-01', 'SUNSET', { dow: 'Tue' }),
      card('2026-12-04', 'SUNRISE', { tide: { lightAlignment: 'HW' } }),
    ];
    const hotTopics = [{ type: 'SUPERMOON', date: '2026-12-01', eventType: 'NIGHT' }];
    const result = buildWatchedLanes({
      dates: [
        { key: 'king-tide:2026-12-03' }, { key: 'spring-tide:2026-12-04' }, { key: 'king-tide:2026-11-18' },
        { key: 'supermoon:2026-12-01' }, { key: 'meteor:2027-01-01' },
      ],
      entries: [passed, tide(), moon],
      windows,
      hotTopics,
      todayStr: '2026-11-30',
    });
    expect(result.map((lane) => lane.title)).toEqual(['Supermoon', 'King tide']);
    expect(buildWatchedLanes({ dates: null, entries: null, windows: [], hotTopics: [], todayStr: TODAY })).toEqual([]);
  });

  it('the event word falls back to the strip\'s sunrise flag, and a window without a clock still reads', () => {
    const windows = [{
      key: '2026-12-03:DUSK', date: '2026-12-03', targetType: 'DUSK', dow: 'Thu', sunrise: false, time: '', away: false,
      tide: { lightAlignment: 'HW' }, bestReach: null,
    }];
    const [lane] = lanes({ windows });
    expect(lane.plan).toEqual({ dow: 'Thu', event: 'sunset', best: null, tideOnLight: true });
    expect(lane.bestSpot).toBeNull();
    expect(lane.name).toBe('King tide');
  });

  it('a best spot with no id still names the lane, but gives no place to focus', () => {
    const windows = [card('2026-12-03', 'SUNRISE', { tide: { lightAlignment: 'HW' }, bestReach: { rating: 3, locationId: null, locationName: 'Somewhere' } })];
    expect(lanes({ windows })[0].bestSpot).toBeNull();
  });
});

describe('the watches the feed no longer lists', () => {
  it('are named from their key, earliest first; a passed date, a bad key and a listed entry are not', () => {
    const rows = unlistedWatches({
      dates: [
        { key: 'king-tide:2027-01-05' }, { key: 'king-tide:2026-11-01' }, { key: 'meteor:2026-12-14' },
        { key: 'nlc-season:2026-12-14' }, { key: 'king-tide:2026-12-03' },
      ],
      entries: [tide()],
      todayStr: TODAY,
    });
    expect(rows).toEqual([
      { key: 'meteor:2026-12-14', title: 'Meteor shower', name: 'Meteor shower · 14 Dec', date: '2026-12-14' },
      { key: 'king-tide:2027-01-05', title: 'King tide', name: 'King tide · 5 Jan', date: '2027-01-05' },
    ]);
  });

  it('say nothing until the feed has arrived', () => {
    expect(unlistedWatches({ dates: [{ key: 'king-tide:2026-12-03' }], entries: null, todayStr: TODAY })).toEqual([]);
    expect(unlistedWatches({ dates: null, entries: [], todayStr: TODAY })).toEqual([]);
  });

  it('count a served entry as listed whatever This week does with it (a lane is served, not unlisted)', () => {
    expect(unlistedWatches({ dates: [{ key: 'king-tide:2026-12-03' }], entries: [tide()], todayStr: '2026-12-02' })).toEqual([]);
  });
});

describe('how many watched dates are still to come', () => {
  it('counts an entry still running, an unmatched watch by its own date, and one entry once', () => {
    const running = tide({ startDate: '2026-11-20', endDate: '2026-11-24' });
    expect(watchedStillToCome({ dates: [{ key: 'king-tide:2026-11-20' }], entries: [running], todayStr: '2026-11-24' })).toBe(1);
    expect(watchedStillToCome({ dates: [{ key: 'king-tide:2026-11-20' }], entries: [running], todayStr: '2026-11-25' })).toBe(0);
    expect(watchedStillToCome({
      dates: [{ key: 'king-tide:2026-12-03' }, { key: 'spring-tide:2026-12-04' }, { key: 'meteor:2026-12-14' }, { key: 'meteor:2026-11-01' }],
      entries: [tide()],
      todayStr: TODAY,
    })).toBe(2);
  });

  it('still counts by date before the feed has arrived, and ignores a key that does not parse', () => {
    expect(watchedStillToCome({ dates: [{ key: 'king-tide:2026-12-03' }, { key: 'nonsense' }], entries: null, todayStr: TODAY })).toBe(1);
    expect(watchedStillToCome({ dates: null, entries: null, todayStr: TODAY })).toBe(0);
  });
});

describe('a watched entry with no plan window (the topic lane still wears its mark)', () => {
  const dates = [{ key: 'king-tide:2026-12-03' }];
  const args = (over) => ({
    dates, entries: [tide()], windows: [], hotTopics: [], todayStr: '2026-12-02', ...over,
  });

  it('is named when the run is inside the forecast but no window is on the light, and not when it has a plan', () => {
    const noLight = [card('2026-12-03', 'SUNRISE', { tide: { lightAlignment: 'NONE' } })];
    expect(watchedWithoutPlan(args({ windows: noLight }))).toEqual([
      { key: 'king-tide:2026-12-03', entryId: 'king-tide:2026-12-03:2026-12-07' },
    ]);
    const onLight = [card('2026-12-03', 'SUNRISE', { tide: { lightAlignment: 'HW' } })];
    expect(watchedWithoutPlan(args({ windows: onLight }))).toEqual([]);
  });

  it('is named for a topic the served topics do not name, and is empty beyond the forecast, once past, or with no feed', () => {
    const solstice = { id: 'solstice:2026-12-21', type: 'solstice', title: 'Winter solstice', startDate: '2026-12-21', endDate: '2026-12-21' };
    const solsticeArgs = {
      dates: [{ key: 'solstice:2026-12-21' }], entries: [solstice], windows: [card('2026-12-21', 'SUNRISE')],
      hotTopics: null, todayStr: '2026-12-20',
    };
    expect(watchedWithoutPlan(solsticeArgs)).toHaveLength(1);
    // Beyond the forecast, planFor has no window to look at either: the entry has no plan and is not past, so it is named
    // (a lane that carries it, if any, wears the mark; with none it stays in Watching).
    expect(watchedWithoutPlan(args({ windows: [card('2026-11-24', 'SUNRISE')], todayStr: TODAY }))).toHaveLength(1);
    expect(watchedWithoutPlan(args({ todayStr: '2026-12-08' }))).toEqual([]);
    expect(watchedWithoutPlan(args({ entries: null }))).toEqual([]);
  });

  it('names one entry once however many keys point at it', () => {
    expect(watchedWithoutPlan(args({ dates: [{ key: 'king-tide:2026-12-03' }, { key: 'spring-tide:2026-12-04' }] }))).toHaveLength(1);
  });
});

describe('binding a watched key to ONE entry', () => {
  const run = tide();
  const sibling = tide({ id: 'king-tide:2026-12-06:2026-12-08', startDate: '2026-12-06', endDate: '2026-12-08' });
  const entries = [sibling, run];

  it('a key belongs to the earliest entry it matches, and to no other', () => {
    expect(watchedKeysFor([{ key: 'king-tide:2026-12-04' }], run, entries)).toEqual(['king-tide:2026-12-04']);
    // Both entries overlap the key's drift window, but only the earlier one is its entry.
    expect(watchedKeysFor([{ key: 'king-tide:2026-12-04' }], sibling, entries)).toEqual([]);
  });

  it('takes strings or served rows, and answers nothing for a missing entry or feed', () => {
    expect(watchedKeysFor(['king-tide:2026-12-03'], run, entries)).toEqual(['king-tide:2026-12-03']);
    expect(watchedKeysFor(null, run, entries)).toEqual([]);
    expect(watchedKeysFor(['king-tide:2026-12-03'], null, entries)).toEqual([]);
    expect(watchedKeysFor(['king-tide:2026-12-03'], run, null)).toEqual([]);
  });

  it('is what the count counts: two overlapping entries and one key are one watched date', () => {
    expect(watchedStillToCome({ dates: [{ key: 'king-tide:2026-12-04' }], entries, todayStr: TODAY })).toBe(1);
  });
});
