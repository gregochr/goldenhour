import { describe, it, expect } from 'vitest';
import {
  buildWatchedChips, entryMatchesWatch, matchWatchToEntry, parseWatchKey, TIDE_DRIFT_DAYS, watchKeyOf,
  watchKeysMatching, WATCH_NO_WINDOW_LINE, WATCHABLE_TYPES,
} from '../utils/watchedDates.js';

/** The watch match and the watched chips (one-screen-plan.md D16): joins over served fields, no new fact. */

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

describe('the chips for watched dates', () => {
  const dates = [{ key: 'king-tide:2026-12-03' }];

  it('beyond the forecast: title, date and the countdown, opening its entry', () => {
    const [chip] = buildWatchedChips({ dates, entries: [tide()], windows: [card('2026-11-24', 'SUNRISE')], hotTopics: [], todayStr: TODAY });
    expect(chip).toMatchObject({
      kind: 'beyond', entryId: 'king-tide:2026-12-03:2026-12-07', title: 'King tide', name: 'King tide · 3 Dec', glyph: '🌊',
      detail: "in 9 days · best spots once it's forecast", window: null,
    });
  });

  it('a date that starts tomorrow says so, and one under way says now', () => {
    const entry = tide({ startDate: '2026-11-25', endDate: '2026-11-29', id: 'x' });
    expect(buildWatchedChips({ dates: [{ key: 'king-tide:2026-11-25' }], entries: [entry], windows: [], hotTopics: [], todayStr: TODAY })[0].detail)
      .toContain('tomorrow');
    const under = tide({ startDate: '2026-11-22', endDate: '2026-11-26', id: 'y' });
    expect(buildWatchedChips({ dates: [{ key: 'king-tide:2026-11-22' }], entries: [under], windows: [], hotTopics: [], todayStr: TODAY })[0].detail)
      .toContain('now');
  });

  it('inside the forecast, a tide chip names the window marked on the light, the best spot and its stars', () => {
    const windows = [
      card('2026-12-03', 'SUNRISE', { tide: { lightAlignment: 'NONE' } }),
      card('2026-12-03', 'SUNSET', { dow: 'Thu', time: '15:40', tide: { lightAlignment: 'LW' }, bestReach: { rating: 3, locationId: 5, locationName: 'Seaton' } }),
      card('2026-12-04', 'SUNRISE', { dow: 'Fri', tide: { lightAlignment: 'HW' }, bestReach: { rating: 4, locationId: 7, locationName: 'Crimdon Beach' } }),
    ];
    const [chip] = buildWatchedChips({ dates, entries: [tide()], windows, hotTopics: [], todayStr: '2026-12-02' });
    expect(chip).toMatchObject({
      kind: 'window',
      name: 'King tide · Fri',
      detail: 'sunrise 07:48 · best Crimdon Beach 4★ · tide on the light',
      window: { date: '2026-12-04', eventType: 'SUNRISE' },
      bestSpot: { id: 7, name: 'Crimdon Beach' },
    });
  });

  it('two windows on the light: the better served rating wins, the earlier on a tie', () => {
    const a = card('2026-12-03', 'SUNRISE', { tide: { lightAlignment: 'HW' }, bestReach: { rating: 3, locationId: 1, locationName: 'A' } });
    const b = card('2026-12-04', 'SUNRISE', { tide: { lightAlignment: 'HW' }, bestReach: { rating: 5, locationId: 2, locationName: 'B' } });
    const c = card('2026-12-05', 'SUNRISE', { tide: { lightAlignment: 'HW' }, bestReach: { rating: 5, locationId: 3, locationName: 'C' } });
    const pick = (windows) => buildWatchedChips({ dates, entries: [tide()], windows, hotTopics: [], todayStr: '2026-12-02' })[0];
    expect(pick([a, b]).window.date).toBe('2026-12-04');
    expect(pick([a, b, c]).window.date).toBe('2026-12-04');
    // With no reach pick the window's own served best is the rating, and an unrated window ranks last.
    const rated = card('2026-12-05', 'SUNSET', { tide: { lightAlignment: 'LW' }, bestRating: 4 });
    const unrated = card('2026-12-03', 'SUNSET', { tide: { lightAlignment: 'LW' } });
    expect(pick([unrated, rated]).window.date).toBe('2026-12-05');
    expect(pick([unrated, rated]).detail).toBe('sunset 15:40 · tide on the light');
    expect(pick([unrated]).window.date).toBe('2026-12-03');
  });

  it('an away window is never chosen, and a window outside the run is ignored', () => {
    const away = card('2026-12-03', 'SUNRISE', { away: true, tide: { lightAlignment: 'HW' } });
    const outside = card('2026-12-09', 'SUNRISE', { tide: { lightAlignment: 'HW' } });
    const [chip] = buildWatchedChips({ dates, entries: [tide()], windows: [away, outside], hotTopics: [], todayStr: '2026-12-02' });
    expect(chip.kind).toBe('beyond');
  });

  it('in the forecast but with no window on the light falls back to the beyond chip, without "once forecast"', () => {
    const windows = [card('2026-12-03', 'SUNRISE', { tide: { lightAlignment: 'NONE' } }), card('2026-12-04', 'SUNRISE')];
    const [chip] = buildWatchedChips({ dates, entries: [tide()], windows, hotTopics: [], todayStr: '2026-12-02' });
    expect(chip).toMatchObject({ kind: 'beyond', detail: 'tomorrow' });
    expect(chip.detail).not.toContain('forecast');
  });

  it('a supermoon opens its served topic window: a night topic is that evening\'s sunset', () => {
    const moon = { id: 'supermoon:2026-12-04', type: 'supermoon', family: 'night-sky', title: 'Supermoon', startDate: '2026-12-04', endDate: '2026-12-05' };
    const windows = [
      card('2026-12-04', 'SUNRISE'),
      card('2026-12-04', 'SUNSET', { dow: 'Fri', time: '15:39', bestReach: { rating: 4, locationId: 9, locationName: 'Bamburgh' } }),
      card('2026-12-05', 'SUNSET', { dow: 'Sat' }),
    ];
    const hotTopics = [{ type: 'SUPERMOON', date: '2026-12-04', eventType: 'NIGHT' }, { type: 'AURORA', date: '2026-12-04', eventType: 'NIGHT' }];
    const [chip] = buildWatchedChips({ dates: [{ key: 'supermoon:2026-12-04' }], entries: [moon], windows, hotTopics, todayStr: '2026-12-03' });
    expect(chip).toMatchObject({
      kind: 'window', glyph: '🌙', name: 'Supermoon · Fri', detail: 'sunset 15:39 · best Bamburgh 4★',
      window: { date: '2026-12-04', eventType: 'SUNSET' },
    });
  });

  it('a topic that anchors its own sunrise opens that one (an eclipse)', () => {
    const eclipse = { id: 'eclipse:2026-12-04', type: 'eclipse', family: 'eclipse', title: 'Solar eclipse', startDate: '2026-12-04', endDate: '2026-12-04' };
    const windows = [card('2026-12-04', 'SUNRISE'), card('2026-12-04', 'SUNSET')];
    const hotTopics = [{ type: 'ECLIPSE', date: '2026-12-04', eventType: 'SUNRISE' }];
    const [chip] = buildWatchedChips({ dates: [{ key: 'eclipse:2026-12-04' }], entries: [eclipse], windows, hotTopics, todayStr: '2026-12-03' });
    expect(chip.window).toEqual({ date: '2026-12-04', eventType: 'SUNRISE' });
  });

  it('a meteor peak has no window: a static chip that says so and opens nothing', () => {
    const meteor = { id: 'meteor:2026-12-14', type: 'meteor', family: 'night-sky', title: 'Geminids', startDate: '2026-12-14', endDate: '2026-12-14' };
    const [chip] = buildWatchedChips({
      dates: [{ key: 'meteor:2026-12-14' }], entries: [meteor], windows: [card('2026-12-14', 'SUNSET')],
      hotTopics: [{ type: 'METEOR', date: '2026-12-14', eventType: 'NIGHT' }], todayStr: '2026-12-13',
    });
    expect(chip).toMatchObject({ kind: 'static', name: 'Geminids · 14 Dec', detail: WATCH_NO_WINDOW_LINE, window: null });
    // Beyond the forecast the same meteor is the ordinary beyond chip, which opens its entry.
    expect(buildWatchedChips({ dates: [{ key: 'meteor:2026-12-14' }], entries: [meteor], windows: [], hotTopics: [], todayStr: '2026-12-01' })[0].kind)
      .toBe('beyond');
  });

  it('a topic the served topics do not name falls back to the beyond chip', () => {
    const solstice = { id: 'solstice:2026-12-21', type: 'solstice', family: 'sun-moon', title: 'Winter solstice', startDate: '2026-12-21', endDate: '2026-12-21' };
    const [chip] = buildWatchedChips({
      dates: [{ key: 'solstice:2026-12-21' }], entries: [solstice], windows: [card('2026-12-21', 'SUNRISE')], hotTopics: null, todayStr: '2026-12-20',
    });
    expect(chip.kind).toBe('beyond');
  });

  it('a watch whose dates have passed, or whose entry is not served, draws nothing; the rest are earliest first', () => {
    const passed = tide({ id: 'p', startDate: '2026-11-18', endDate: '2026-11-22' });
    const meteor = { id: 'meteor:2026-12-14', type: 'meteor', title: 'Geminids', startDate: '2026-12-14', endDate: '2026-12-14' };
    const chips = buildWatchedChips({
      dates: [{ key: 'meteor:2026-12-14' }, { key: 'king-tide:2026-11-18' }, { key: 'king-tide:2026-12-03' }, { key: 'supermoon:2027-01-01' }],
      entries: [passed, meteor, tide()],
      windows: [],
      hotTopics: [],
      todayStr: TODAY,
    });
    expect(chips.map((chip) => chip.title)).toEqual(['King tide', 'Geminids']);
    expect(buildWatchedChips({ dates: null, entries: null, windows: [], hotTopics: [], todayStr: TODAY })).toEqual([]);
  });

  it('a tide still running today is shown until its last day has gone', () => {
    const running = tide({ startDate: '2026-11-20', endDate: '2026-11-24' });
    expect(buildWatchedChips({ dates: [{ key: 'king-tide:2026-11-20' }], entries: [running], windows: [], hotTopics: [], todayStr: '2026-11-24' }))
      .toHaveLength(1);
    expect(buildWatchedChips({ dates: [{ key: 'king-tide:2026-11-20' }], entries: [running], windows: [], hotTopics: [], todayStr: '2026-11-25' }))
      .toHaveLength(0);
  });

  it('the event word falls back to the strip\'s sunrise flag, and a window without a clock still reads', () => {
    const windows = [{
      key: '2026-12-03:DUSK', date: '2026-12-03', targetType: 'DUSK', dow: 'Thu', sunrise: false, time: '', away: false,
      tide: { lightAlignment: 'HW' }, bestReach: null,
    }];
    const [chip] = buildWatchedChips({ dates, entries: [tide()], windows, hotTopics: [], todayStr: '2026-12-02' });
    expect(chip.detail).toBe('sunset · tide on the light');
    expect(chip.bestSpot).toBeNull();
  });
});
