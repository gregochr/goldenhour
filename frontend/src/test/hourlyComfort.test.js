/**
 * `utils/hourlyComfort.js` — the filter/min/max reads over a wildlife hide's served hourly rows.
 *
 * Every fixture is a literal row and every expectation a literal string: the module decides nothing
 * about what is comfortable, so the whole contract is "which served figure, printed how". The
 * summary is what a reader sees on the Map tab callout, so the printed words are the assertion.
 *
 * Winter dates throughout (GMT, so UTC and UK clocks agree) except the one test that exists to
 * prove the UK conversion.
 */
import { describe, it, expect } from 'vitest';
import {
  comfortLines, comfortSummary, formatCompass, formatWind, hourlyDaysOf, rowsFromNow,
} from '../utils/hourlyComfort.js';

/** One served HOURLY row, in the shape `groupForecastsByDate` hands the component. */
const row = (time, temperatureCelsius, apparentTemperatureCelsius, windSpeed, windDirection, rain, date = '2026-02-10') => ({
  solarEventTime: `${date}T${time}:00`,
  temperatureCelsius,
  apparentTemperatureCelsius,
  windSpeed,
  windDirection,
  precipitationProbabilityPercent: rain,
});

/** Four daylight hours: wind peaks at 6 m/s TWICE (08:00, 09:00), rain at 40 % TWICE (09:00, 10:00). */
const FOUR_HOURS = [
  row('07:00', 2.4, -0.6, 3, 270, 10),
  row('08:00', 4.0, 1.2, 6, 250, 10),
  row('09:00', 6.6, 4.4, 6, 240, 40),
  row('10:00', 7.1, 5.0, 4, 240, 40),
];

describe('formatWind', () => {
  it('prints mph with a compass point', () => {
    expect(formatWind(5, 180)).toBe('11.2 mph S');
  });

  it('drops the compass word, never prints "undefined", when the direction is missing', () => {
    expect(formatWind(5, null)).toBe('11.2 mph');
  });

  it('is null with no speed, whatever the direction', () => {
    expect(formatWind(null, 180)).toBeNull();
  });
});

describe('comfortSummary', () => {
  it('is null for no rows at all', () => {
    expect(comfortSummary([])).toBeNull();
    expect(comfortSummary(null)).toBeNull();
  });

  it('takes the temperature and feels-like ranges, rounded to whole degrees', () => {
    const s = comfortSummary(FOUR_HOURS);
    expect(s.temperature).toEqual({ min: 2, max: 7 });
    expect(s.feelsLike).toEqual({ min: -1, max: 5 });
  });

  it('resolves a tie for the strongest wind to the EARLIEST hour, and reads its own direction', () => {
    const s = comfortSummary(FOUR_HOURS);
    expect(s.wind.time).toBe('08:00');
    expect(s.wind.directionDeg).toBe(250);
    expect(s.wind.speedMps).toBe(6);
  });

  it('resolves a tie for the highest rain chance to the EARLIEST hour', () => {
    const s = comfortSummary(FOUR_HOURS);
    expect(s.rain).toEqual({ percent: 40, time: '09:00', readings: 4 });
  });

  it('gives the same answer whatever order the rows arrive in', () => {
    const shuffled = [FOUR_HOURS[3], FOUR_HOURS[1], FOUR_HOURS[0], FOUR_HOURS[2]];
    expect(comfortSummary(shuffled)).toEqual(comfortSummary(FOUR_HOURS));
  });

  it('compares wind on the served metres per second, not on the rounded mph it prints', () => {
    // 5.00 and 5.01 m/s both print 11.2 mph; the later, stronger hour is still the strongest.
    const s = comfortSummary([row('08:00', 5, 4, 5.0, 90, 0), row('09:00', 5, 4, 5.01, 180, 0)]);
    expect(s.wind.time).toBe('09:00');
  });

  it('reports the span of hours covered, on the UK clock', () => {
    // 07:00 UTC in June is 08:00 BST — the conversion `formatEventTimeUk` owns, proved here once.
    const s = comfortSummary([
      row('07:00', 12, 11, 2, 90, 0, '2026-06-10'),
      row('16:00', 18, 17, 2, 90, 0, '2026-06-10'),
    ]);
    expect(s.from).toBe('08:00');
    expect(s.to).toBe('17:00');
  });
});

describe('comfortLines', () => {
  it('is empty for no summary', () => {
    expect(comfortLines(null)).toEqual([]);
  });

  it('prints a four-hour day as temperature, wind, rain and the hours covered', () => {
    expect(comfortLines(comfortSummary(FOUR_HOURS))).toEqual([
      '2 to 7°C, feels like -1 to 5°C.',
      'Wind up to 13.4 mph W at 08:00.',
      'Rain chance up to 40% at 09:00.',
      'Daylight hours 07:00 to 10:00.',
    ]);
  });

  it('prints a single-row day without "up to" — one reading is not a ceiling over anything', () => {
    expect(comfortLines(comfortSummary([row('08:00', 9.2, 7.4, 5, 180, 20)]))).toEqual([
      '9°C, feels like 7°C.',
      'Wind 11.2 mph S at 08:00.',
      'Rain chance 20% at 08:00.',
      'Only the 08:00 hour is forecast.',
    ]);
  });

  it('leaves the wind line out when no hour carries a wind speed', () => {
    // A direction with no speed says nothing a reader can use, so the line goes — it is not a
    // "calm" claim either.
    const lines = comfortLines(comfortSummary([
      row('08:00', 5, 3, null, 90, 10),
      row('09:00', 6, 4, null, 90, 20),
    ]));
    expect(lines).toEqual([
      '5 to 6°C, feels like 3 to 4°C.',
      'Rain chance up to 20% at 09:00.',
      'Daylight hours 08:00 to 09:00.',
    ]);
  });

  it('names a wind with no direction by speed alone', () => {
    const lines = comfortLines(comfortSummary([
      row('08:00', 5, 3, 5, null, 10),
      row('09:00', 6, 4, 2, null, 10),
    ]));
    expect(lines[1]).toBe('Wind up to 11.2 mph at 08:00.');
  });

  it('leaves the feels-like clause out when no hour carries one', () => {
    const lines = comfortLines(comfortSummary([
      row('08:00', 5, null, 2, 90, 10),
      row('09:00', 8, null, 2, 90, 10),
    ]));
    expect(lines[0]).toBe('5 to 8°C.');
  });

  it('says a flat 0% rain chance once, as "throughout", rather than naming an hour for it', () => {
    const lines = comfortLines(comfortSummary([
      row('08:00', 5, 3, 2, 90, 0),
      row('09:00', 6, 4, 2, 90, 0),
    ]));
    expect(lines).toContain('Rain chance 0% throughout.');
  });

  it('collapses a range whose ends round alike to one figure', () => {
    const lines = comfortLines(comfortSummary([
      row('08:00', 5.1, 3.2, 2, 90, 10),
      row('09:00', 4.9, 2.8, 2, 90, 10),
    ]));
    expect(lines[0]).toBe('5°C, feels like 3°C.');
  });
});

describe('rowsFromNow — "the rest of today", with an injected clock', () => {
  // 2026-02-10 is GMT, so UTC and UK hours agree. The clock is handed in; the util reads none.
  const DAY = [
    row('07:00', 2, 1, 3, 90, 10),
    row('12:00', 5, 4, 3, 90, 10),
    row('16:00', 6, 5, 3, 90, 10),
    row('17:00', 4, 3, 3, 90, 10),
  ];
  const at = (hhmm, ss = '00') => new Date(`2026-02-10T${hhmm}:${ss}Z`);

  it('drops every hour that has already started and keeps the current one', () => {
    expect(rowsFromNow(DAY, at('16:30')).map((r) => r.solarEventTime))
      .toEqual(['2026-02-10T16:00:00', '2026-02-10T17:00:00']);
  });

  it('keeps an hour starting exactly now, and drops the one before it', () => {
    expect(rowsFromNow(DAY, at('17:00')).map((r) => r.solarEventTime)).toEqual(['2026-02-10T17:00:00']);
    expect(rowsFromNow(DAY, at('16:59', '59')).map((r) => r.solarEventTime))
      .toEqual(['2026-02-10T16:00:00', '2026-02-10T17:00:00']);
  });

  it('returns nothing once the last daylight hour is over', () => {
    expect(rowsFromNow(DAY, at('18:00'))).toEqual([]);
  });

  it('drops a row whose time cannot be parsed — it cannot be shown to be in the future', () => {
    const rows = [{ ...DAY[3], solarEventTime: 'not a time' }, DAY[3]];
    expect(rowsFromNow(rows, at('08:00'))).toEqual([DAY[3]]);
  });

  it('is empty for no rows, whatever shape arrives', () => {
    expect(rowsFromNow(null, at('08:00'))).toEqual([]);
    expect(rowsFromNow(undefined, at('08:00'))).toEqual([]);
  });

  it('feeds a summary that names only the hours left, in the "rest of today" wording', () => {
    const left = comfortSummary(rowsFromNow(DAY, at('16:30')));
    expect(comfortLines(left, { rest: true })).toEqual([
      '4 to 6°C, feels like 3 to 5°C.',
      'Wind up to 6.7 mph E at 16:00.',
      'Rain chance up to 10% at 16:00.',
      'Daylight hours left, 16:00 to 17:00.',
    ]);
  });

  it('says "the 17:00 hour is left" for a single hour remaining', () => {
    expect(comfortLines(comfortSummary(rowsFromNow(DAY, at('17:10'))), { rest: true }).at(-1))
      .toBe('Only the 17:00 hour is left.');
  });
});

describe('boundary values — zero, negative, missing and half-way figures', () => {
  it('keeps the compass word for a wind from 0 degrees (north)', () => {
    expect(formatWind(5, 0)).toBe('11.2 mph N');
    expect(formatCompass(0)).toBe('N');
    expect(formatCompass(null)).toBeNull();
    expect(formatCompass(undefined)).toBeNull();
  });

  it('calls an all-zero wind "Calm throughout.", never "up to 0 mph"', () => {
    const lines = comfortLines(comfortSummary([row('08:00', 5, 3, 0, 90, 10), row('09:00', 5, 3, 0, 90, 10)]));
    expect(lines[1]).toBe('Calm throughout.');
  });

  it('calls a single zero-wind reading "Calm at <hour>."', () => {
    expect(comfortLines(comfortSummary([row('08:00', 5, 3, 0, 90, 10)]))[1]).toBe('Calm at 08:00.');
  });

  it('prints a 0°C air temperature as a figure, not as an absence', () => {
    expect(comfortLines(comfortSummary([row('08:00', 0, -3, 2, 90, 10)]))[0]).toBe('0°C, feels like -3°C.');
  });

  it('prints a negative air-temperature range with "to", so a minus sign cannot read as a dash', () => {
    const lines = comfortLines(comfortSummary([row('08:00', -10, -14, 2, 90, 10), row('12:00', -2, -6, 2, 90, 10)]));
    expect(lines[0]).toBe('-10 to -2°C, feels like -14 to -6°C.');
  });

  it('prints a single-row 0% rain chance with its hour', () => {
    expect(comfortLines(comfortSummary([row('08:00', 5, 3, 2, 90, 0)]))[2]).toBe('Rain chance 0% at 08:00.');
  });

  it('says only what was served when no air temperature exists but a feels-like does', () => {
    const lines = comfortLines(comfortSummary([row('08:00', null, 3, 2, 90, 10), row('09:00', null, 4, 2, 90, 10)]));
    expect(lines[0]).toBe('Feels like 3 to 4°C.');
  });

  it('names a wind carried by one row of several without "up to"', () => {
    const lines = comfortLines(comfortSummary([
      row('08:00', 5, 3, null, null, 10), row('09:00', 6, 4, 5, 180, 10), row('10:00', 7, 5, null, null, 10),
    ]));
    expect(lines[1]).toBe('Wind 11.2 mph S at 09:00.');
  });

  it('prints a wind with no hour when its time cannot be parsed, rather than a made-up one', () => {
    const rows = [{ ...row('08:00', 5, 3, 5, 180, 10), solarEventTime: 'not a time' }, row('09:00', 6, 4, 2, 180, 10)];
    const lines = comfortLines(comfortSummary(rows));
    expect(lines[1]).toBe('Wind up to 11.2 mph S.');
  });

  it('rounds a half-degree up, in the summary exactly as the table does (Math.round)', () => {
    expect(comfortLines(comfortSummary([row('08:00', 2.5, 0.5, 2, 90, 10)]))[0]).toBe('3°C, feels like 1°C.');
  });
});

describe('hourlyDaysOf', () => {
  const MAP = new Map([
    ['2026-02-09', { sunrise: null, sunset: null, hourly: [row('08:00', 5, 3, 2, 90, 0, '2026-02-09')] }],
    ['2026-02-10', { sunrise: null, sunset: null, hourly: [row('08:00', 5, 3, 2, 90, 0)] }],
    ['2026-02-12', { sunrise: null, sunset: null, hourly: [row('08:00', 6, 4, 2, 90, 0, '2026-02-12')] }],
    ['2026-02-13', { sunrise: null, sunset: null, hourly: [] }],
  ]);

  it('lists the days that have rows, in date order, from the first date asked for', () => {
    const days = hourlyDaysOf(MAP, { fromDate: '2026-02-10' });
    expect(days.map((d) => d.date)).toEqual(['2026-02-10', '2026-02-12']);
    expect(days.map((d) => d.label)).toEqual(['Tue 10 Feb', 'Thu 12 Feb']);
    expect(days[0].rows).toHaveLength(1);
  });

  it('lists a day the sheet\'s own windows span but the hourly job has not written, with no rows', () => {
    const days = hourlyDaysOf(MAP, { fromDate: '2026-02-10', alsoDates: ['2026-02-10', '2026-02-11'] });
    expect(days.map((d) => d.date)).toEqual(['2026-02-10', '2026-02-11', '2026-02-12']);
    expect(days[1].rows).toEqual([]);
  });

  it('does not list a day whose entry holds an empty hourly array unless a window names it', () => {
    expect(hourlyDaysOf(MAP, { fromDate: '2026-02-10' }).map((d) => d.date)).not.toContain('2026-02-13');
  });

  it('drops a finished day: yesterday\'s comfort forecast answers nothing', () => {
    expect(hourlyDaysOf(MAP, { fromDate: '2026-02-10' }).map((d) => d.date)).not.toContain('2026-02-09');
  });

  it('copes with no forecast map at all, listing only the days a window names', () => {
    expect(hourlyDaysOf(null, { fromDate: '2026-02-10', alsoDates: ['2026-02-11'] }))
      .toEqual([{ date: '2026-02-11', label: 'Wed 11 Feb', rows: [] }]);
    expect(hourlyDaysOf(undefined)).toEqual([]);
  });

  it('drops a window-named day that falls before the first date asked for, as it drops a finished day', () => {
    const days = hourlyDaysOf(MAP, { fromDate: '2026-02-10', alsoDates: ['2026-02-08', '2026-02-11'] });
    expect(days.map((d) => d.date)).toEqual(['2026-02-10', '2026-02-11', '2026-02-12']);
  });

  it('lists every date it holds when no first date is given', () => {
    expect(hourlyDaysOf(MAP).map((d) => d.date)).toEqual(['2026-02-09', '2026-02-10', '2026-02-12']);
  });
});
