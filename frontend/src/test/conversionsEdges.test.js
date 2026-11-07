import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// conversions.js is loaded afresh inside every test, not once at file import. Its constants (the
// compass tables, the zone-suffix pattern, the 30-minute afterglow buffer) are evaluated when it
// loads; a load that happens before any test starts is "static" to Stryker and its mutants are not
// run against this file. Loading inside the test lifecycle makes these assertions judge them.
let colourForecastDates;
let computeAutoSelection;
let degreesToCompass;
let degreesToCompass16;
let formatDayClockUk;
let formatElapsedSince;
let formatRelativeTimeUk;
let groupForecastsByDate;
let groupForecastsByLocation;
let mpsToMph;
let parseUtcInstant;

beforeEach(async () => {
  vi.resetModules();
  ({
    colourForecastDates, computeAutoSelection, degreesToCompass, degreesToCompass16, formatDayClockUk,
    formatElapsedSince, formatRelativeTimeUk, groupForecastsByDate, groupForecastsByLocation, mpsToMph,
    parseUtcInstant,
  } = await import('../utils/conversions.js'));
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('formatDayClockUk — the weekday comma', () => {
  it('drops the comma en-GB puts after the weekday, and only that one', () => {
    // Some ICU builds print "Sun, 4 Oct, 05:58"; the helper must read the same on all of them.
    vi.spyOn(Date.prototype, 'toLocaleString').mockReturnValue('Sun, 4 Oct, 05:58');

    expect(formatDayClockUk('2026-10-04T04:58:00Z')).toBe('Sun 4 Oct, 05:58');
  });

  it('leaves a string that has no weekday comma alone', () => {
    vi.spyOn(Date.prototype, 'toLocaleString').mockReturnValue('Sun 4 Oct, 05:58');

    expect(formatDayClockUk('2026-10-04T04:58:00Z')).toBe('Sun 4 Oct, 05:58');
  });

  it('prints the UK day and clock of a real instant', () => {
    expect(formatDayClockUk('2026-10-04T04:58:00Z')).toBe('Sun 4 Oct, 05:58');
  });

  it('is null for an absent instant', () => {
    expect(formatDayClockUk(null)).toBeNull();
  });
});

describe('parseUtcInstant — edges', () => {
  it('reads only strings and Dates: a bare number is not an instant', () => {
    // `new Date('2026Z')` is a valid year, so a number stringified and suffixed would parse.
    expect(parseUtcInstant(2026)).toBeNull();
    expect(parseUtcInstant({})).toBeNull();
    expect(parseUtcInstant(true)).toBeNull();
  });

  it('appends Z to a bare string and leaves a zoned one alone', () => {
    expect(parseUtcInstant('2026-10-05T17:41:00').toISOString()).toBe('2026-10-05T17:41:00.000Z');
    expect(parseUtcInstant('2026-10-05T17:41:00+01:00').toISOString()).toBe('2026-10-05T16:41:00.000Z');
  });
});

describe('groupForecastsByLocation — edges', () => {
  it('gives each location exactly the dates its own rows name', () => {
    const grouped = groupForecastsByLocation([
      { locationName: 'A', locationLat: '1.5', locationLon: '2.5', targetDate: '2026-10-05', targetType: 'SUNSET', forecastRunAt: '2026-10-05T05:00:00' },
      { locationName: 'B', locationLat: '3', locationLon: '4', targetDate: '2026-10-06', targetType: 'SUNRISE', forecastRunAt: '2026-10-05T05:00:00' },
    ]);

    expect(grouped.map((g) => [g.name, g.lat, g.lon])).toEqual([['A', 1.5, 2.5], ['B', 3, 4]]);
    expect([...grouped[0].forecastsByDate.keys()]).toEqual(['2026-10-05']);
    expect([...grouped[1].forecastsByDate.keys()]).toEqual(['2026-10-06']);
  });
});

describe('groupForecastsByDate — edges', () => {
  const row = (over = {}) => ({
    targetDate: '2026-10-05',
    targetType: 'SUNRISE',
    forecastRunAt: '2026-10-05T05:00:00',
    id: 'a',
    ...over,
  });

  it('opens a date with an empty hourly list when it holds only colour rows', () => {
    const map = groupForecastsByDate([row()]);

    expect(map.get('2026-10-05').hourly).toEqual([]);
    expect(map.get('2026-10-05').sunset).toBeNull();
  });

  it('does not throw on a row with no target type, and keeps it out of every slot', () => {
    const map = groupForecastsByDate([row({ targetType: undefined })]);

    expect(map.get('2026-10-05')).toEqual({ sunrise: null, sunset: null, hourly: [] });
  });

  it('ignores a row whose type is neither colour nor hourly', () => {
    const map = groupForecastsByDate([row({ targetType: 'NOON', solarEventTime: '2026-10-05T12:00:00' })]);

    expect(map.get('2026-10-05')).toEqual({ sunrise: null, sunset: null, hourly: [] });
  });

  it('keeps the newer sunrise run whichever order the rows arrive in', () => {
    const older = row({ id: 'older', forecastRunAt: '2026-10-05T05:00:00' });
    const newer = row({ id: 'newer', forecastRunAt: '2026-10-05T06:00:00' });

    expect(groupForecastsByDate([older, newer]).get('2026-10-05').sunrise.id).toBe('newer');
    expect(groupForecastsByDate([newer, older]).get('2026-10-05').sunrise.id).toBe('newer');
  });

  it('keeps the first of two sunrise rows from the same run', () => {
    const first = row({ id: 'first' });
    const second = row({ id: 'second' });

    expect(groupForecastsByDate([first, second]).get('2026-10-05').sunrise.id).toBe('first');
  });

  it('keeps the newer hourly run per slot whichever order the rows arrive in', () => {
    const slot = '2026-10-05T10:00:00';
    const older = row({ id: 'older', targetType: 'HOURLY', solarEventTime: slot, forecastRunAt: '2026-10-05T05:00:00' });
    const newer = row({ id: 'newer', targetType: 'HOURLY', solarEventTime: slot, forecastRunAt: '2026-10-05T06:00:00' });

    expect(groupForecastsByDate([older, newer]).get('2026-10-05').hourly.map((h) => h.id)).toEqual(['newer']);
    expect(groupForecastsByDate([newer, older]).get('2026-10-05').hourly.map((h) => h.id)).toEqual(['newer']);
  });

  it('keeps the first hourly row of two from the same run', () => {
    const slot = '2026-10-05T10:00:00';
    const first = row({ id: 'first', targetType: 'HOURLY', solarEventTime: slot });
    const second = row({ id: 'second', targetType: 'HOURLY', solarEventTime: slot });

    expect(groupForecastsByDate([first, second]).get('2026-10-05').hourly.map((h) => h.id)).toEqual(['first']);
  });

  it('sorts the hourly rows by time, earliest first, however they arrive', () => {
    const hour = (id, time) => row({ id, targetType: 'HOURLY', solarEventTime: `2026-10-05T${time}:00` });

    const hourly = groupForecastsByDate([hour('c', '12:00'), hour('a', '09:00'), hour('d', '15:00'), hour('b', '10:00')])
      .get('2026-10-05').hourly;

    expect(hourly.map((h) => h.id)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('keeps one hourly row per slot and a separate list per date', () => {
    const map = groupForecastsByDate([
      row({ id: 'x', targetType: 'HOURLY', solarEventTime: '2026-10-05T10:00:00' }),
      row({ id: 'y', targetType: 'HOURLY', solarEventTime: '2026-10-06T10:00:00', targetDate: '2026-10-06' }),
    ]);

    expect(map.get('2026-10-05').hourly.map((h) => h.id)).toEqual(['x']);
    expect(map.get('2026-10-06').hourly.map((h) => h.id)).toEqual(['y']);
  });
});

describe('colourForecastDates — edges', () => {
  it('skips a null entry, a hourly-only date and an empty one, and sorts what is left', () => {
    const locations = [{
      forecastsByDate: new Map([
        ['2026-10-09', null],
        ['2026-10-07', { sunset: { id: 1 }, sunrise: null }],
        ['2026-10-05', { sunrise: { id: 2 }, sunset: null }],
        ['2026-10-08', { sunrise: null, sunset: null, hourly: [{ id: 3 }] }],
      ]),
    }];

    expect(colourForecastDates(locations)).toEqual(['2026-10-05', '2026-10-07']);
  });
});

describe('formatRelativeTimeUk — band edges', () => {
  const NOW = Date.parse('2026-10-05T12:00:00Z');
  const ago = (minutes) => new Date(NOW - minutes * 60000).toISOString();
  const at = (stamp) => {
    vi.spyOn(Date, 'now').mockReturnValue(NOW);
    return formatRelativeTimeUk(stamp);
  };

  it.each([
    [0, 'just now'],
    [1, '1m ago'],
    [59, '59m ago'],
    [60, '1h ago'],
    [61, '1h 1m ago'],
    [94, '1h 34m ago'],
    [1439, '23h 59m ago'],
    [1440, '1d ago'],
    [4320, '3d ago'],
    [4321, '3d ago'],
  ])('reads %i minutes as %s', (minutes, expected) => {
    expect(at(ago(minutes))).toBe(expected);
  });

  it('is empty for nothing and for rubbish', () => {
    expect(at(null)).toBe('');
    expect(at('not a date')).toBe('');
  });
});

describe('formatElapsedSince — band edges', () => {
  const START = '2026-10-05T12:00:00Z';
  const startMs = Date.parse(START);

  it.each([
    [0, '0s'],
    [1000, '1s'],
    [59999, '59s'],
    [60000, '1m 0s'],
    [3599000, '59m 59s'],
    [3600000, '1h 0m'],
    [5400000, '1h 30m'],
  ])('reads %i ms after the start as %s', (ms, expected) => {
    expect(formatElapsedSince(START, startMs + ms)).toBe(expected);
  });

  it('says nothing for a start in the future, however slightly', () => {
    expect(formatElapsedSince(START, startMs - 1000)).toBeNull();
    expect(formatElapsedSince(START, startMs - 1)).toBeNull();
  });

  it('says nothing for nothing and for rubbish', () => {
    expect(formatElapsedSince(null, startMs)).toBeNull();
    expect(formatElapsedSince('nope', startMs)).toBeNull();
  });
});

describe('computeAutoSelection — edges', () => {
  const TODAY = '2026-10-05';
  const place = (sunset, locationType = ['LANDSCAPE']) => ({
    locationType,
    forecastsByDate: new Map([[TODAY, { sunset: sunset ? { solarEventTime: sunset } : null }]]),
  });
  const at = (iso) => new Date(iso);

  it('stays on tonight up to the instant 30 minutes after the earliest sunset, and moves on exactly then', () => {
    const locations = [place('2026-10-05T17:41:00')];

    expect(computeAutoSelection(locations, at('2026-10-05T18:10:59.999Z')))
      .toEqual({ date: TODAY, eventType: 'SUNSET' });
    expect(computeAutoSelection(locations, at('2026-10-05T18:11:00.000Z')))
      .toEqual({ date: '2026-10-06', eventType: 'SUNRISE' });
  });

  it('counts a place with some non-wildlife type, not only places with none', () => {
    const locations = [place('2026-10-05T17:00:00', ['WILDLIFE', 'LANDSCAPE']), place('2026-10-05T17:41:00')];

    expect(computeAutoSelection(locations, at('2026-10-05T17:45:00Z')))
      .toEqual({ date: '2026-10-06', eventType: 'SUNRISE' });
  });

  it('skips a wildlife-only place and an untyped one', () => {
    const locations = [
      place('2026-10-05T17:00:00', ['WILDLIFE']),
      { forecastsByDate: place('2026-10-05T17:00:00').forecastsByDate },
      place('2026-10-05T17:41:00'),
    ];

    expect(computeAutoSelection(locations, at('2026-10-05T17:45:00Z')))
      .toEqual({ date: TODAY, eventType: 'SUNSET' });
  });

  it('is null when every place is wildlife-only', () => {
    expect(computeAutoSelection([place('2026-10-05T17:00:00', ['WILDLIFE'])], at('2026-10-05T17:45:00Z')))
      .toBeNull();
  });

  it('uses the earliest sunset even when it comes from a later place in the list', () => {
    const locations = [place('2026-10-05T17:41:00'), place('2026-10-05T17:00:00')];

    expect(computeAutoSelection(locations, at('2026-10-05T17:45:00Z')))
      .toEqual({ date: '2026-10-06', eventType: 'SUNRISE' });
  });

  it('copes with a place whose day has a sunrise but no sunset', () => {
    const noSunset = {
      locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[TODAY, { sunrise: { solarEventTime: '2026-10-05T05:58:00' }, sunset: null }]]),
    };

    expect(computeAutoSelection([noSunset], at('2026-10-05T10:00:00Z'))).toBeNull();
    expect(computeAutoSelection([noSunset, place('2026-10-05T17:41:00')], at('2026-10-05T10:00:00Z')))
      .toEqual({ date: TODAY, eventType: 'SUNSET' });
  });

  it('copes with a place that has no entry for today at all', () => {
    const none = { locationType: ['LANDSCAPE'], forecastsByDate: new Map() };

    expect(computeAutoSelection([none], at('2026-10-05T10:00:00Z'))).toBeNull();
  });
});

describe('parseUtcInstant — the zone suffix', () => {
  it.each([
    ['2026-10-05T17:41:00Z', '2026-10-05T17:41:00.000Z'],
    ['2026-10-05T17:41:00+01:00', '2026-10-05T16:41:00.000Z'],
    ['2026-10-05T17:41:00+0100', '2026-10-05T16:41:00.000Z'],
    ['2026-10-05T17:41:00-08:00', '2026-10-06T01:41:00.000Z'],
    ['2026-10-05T17:41:00-0800', '2026-10-06T01:41:00.000Z'],
    ['2026-10-05T17:41:00', '2026-10-05T17:41:00.000Z'],
    ['2026-10-05', '2026-10-05T00:00:00.000Z'],
  ])('reads %s as %s', (input, iso) => {
    expect(parseUtcInstant(input).toISOString()).toBe(iso);
  });

  it('looks for the offset at the END of the string only', () => {
    // An offset-like run followed by more text is not a zone suffix, so the bare-UTC rule applies.
    expect(parseUtcInstant('Apr 8 2026 10:00:00 +0100 (BST)').getTime()).toBe(Date.UTC(2026, 3, 8, 10, 0, 0));
  });
});

describe('compass words and wind speed', () => {
  it.each([
    [0, 'N'], [44, 'NE'], [45, 'NE'], [90, 'E'], [135, 'SE'], [180, 'S'], [225, 'SW'], [270, 'W'],
    [315, 'NW'], [337, 'NW'], [338, 'N'], [360, 'N'], [-45, 'NW'], [22, 'N'], [23, 'NE'],
  ])('puts %i degrees at %s on the 8-point compass', (deg, word) => {
    expect(degreesToCompass(deg)).toBe(word);
  });

  it.each([
    [0, 'N'], [22.5, 'NNE'], [45, 'NE'], [67.5, 'ENE'], [90, 'E'], [112.5, 'ESE'], [135, 'SE'],
    [157.5, 'SSE'], [180, 'S'], [202.5, 'SSW'], [225, 'SW'], [247.5, 'WSW'], [270, 'W'], [292.5, 'WNW'],
    [315, 'NW'], [337.5, 'NNW'], [360, 'N'], [-22.5, 'NNW'],
  ])('puts %f degrees at %s on the 16-point compass', (deg, word) => {
    expect(degreesToCompass16(deg)).toBe(word);
  });

  it('converts metres per second to miles per hour to one decimal place', () => {
    expect(mpsToMph(10)).toBe(22.4);
    expect(mpsToMph('5')).toBe(11.2);
    expect(mpsToMph(0)).toBe(0);
  });
});
