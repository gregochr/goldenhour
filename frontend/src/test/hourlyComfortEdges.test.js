import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  rowsFromNow, formatWind, formatCompass, comfortSummary, comfortLines, comfortDayOf, hourlyDaysOf,
} from '../utils/hourlyComfort.js';

const NOW = new Date('2026-11-06T10:30:00Z');

function hourRow(date, hour, over = {}) {
  return { solarEventTime: `${date}T${String(hour).padStart(2, '0')}:00:00`, ...over };
}

function lineInput(over = {}) {
  return {
    temperature: null, feelsLike: null, wind: null, rain: null, from: null, to: null, ...over,
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('isNum through the public readers', () => {
  it('treats NaN, infinities and numeric strings as no reading', () => {
    expect(formatWind(NaN, 90)).toBeNull();
    expect(formatWind(Infinity, 90)).toBeNull();
    expect(formatWind('5', 90)).toBeNull();
    expect(formatCompass(NaN)).toBeNull();
    expect(formatCompass(Infinity)).toBeNull();
    expect(formatCompass('90')).toBeNull();
    expect(formatCompass(0)).toBe('N');
  });
});

describe('rowsFromNow', () => {
  it('drops a null row and an unparseable row but keeps the current hour on', () => {
    const rows = [null, { solarEventTime: 'garbage' }, hourRow('2026-11-06', 9), hourRow('2026-11-06', 10)];
    expect(rowsFromNow(rows, NOW)).toEqual([hourRow('2026-11-06', 10)]);
  });
});

describe('comfortSummary', () => {
  it('ignores a row whose time cannot be formatted when naming the first and last hour', () => {
    const rows = [
      { solarEventTime: 'garbage', temperatureCelsius: 5 },
      hourRow('2026-11-06', 8, { temperatureCelsius: 6 }),
      hourRow('2026-11-06', 9, { temperatureCelsius: 8 }),
    ];
    const summary = comfortSummary(rows);
    expect(summary.from).toBe('08:00');
    expect(summary.to).toBe('09:00');
    expect(summary.hours).toBe(3);
  });
});

describe('comfortLines edges', () => {
  it('is empty for a summary with nothing in it', () => {
    expect(comfortLines(lineInput())).toEqual([]);
  });

  it('says only the feels-like when no air temperature was served', () => {
    expect(comfortLines(lineInput({ feelsLike: { min: 2, max: 4 } }))).toEqual(['Feels like 2 to 4°C.']);
  });

  it('prints no hours line when only one end of the span is known', () => {
    expect(comfortLines(lineInput({ from: '08:00' }))).toEqual([]);
    expect(comfortLines(lineInput({ to: '16:00' }))).toEqual([]);
  });

  it('prints the hours line when both ends are known', () => {
    expect(comfortLines(lineInput({ from: '08:00', to: '16:00' }))).toEqual(['Daylight hours 08:00 to 16:00.']);
    expect(comfortLines(lineInput({ from: '08:00', to: '16:00' }), { rest: true }))
      .toEqual(['Daylight hours left, 08:00 to 16:00.']);
    expect(comfortLines(lineInput({ from: '08:00', to: '08:00' }))).toEqual(['Only the 08:00 hour is forecast.']);
    expect(comfortLines(lineInput({ from: '08:00', to: '08:00' }), { rest: true }))
      .toEqual(['Only the 08:00 hour is left.']);
  });
});

describe('comfortDayOf edges', () => {
  it('names no day for entries without usable hourly rows', () => {
    const none = { date: null, rows: [] };
    expect(comfortDayOf(new Map([['2026-11-07', { hourly: [] }]]), NOW)).toEqual(none);
    expect(comfortDayOf(new Map([['2026-11-07', { hourly: undefined }]]), NOW)).toEqual(none);
    expect(comfortDayOf(new Map([['2026-11-07', {}]]), NOW)).toEqual(none);
    expect(comfortDayOf(new Map([['2026-11-07', null]]), NOW)).toEqual(none);
    expect(comfortDayOf(new Map([['2026-11-06', { hourly: [] }]]), NOW)).toEqual(none);
    expect(comfortDayOf(new Map([['2026-11-05', { hourly: [hourRow('2026-11-05', 9)] }]]), NOW)).toEqual(none);
    expect(comfortDayOf(null, NOW)).toEqual(none);
  });

  it('skips an empty later day in favour of a later one with rows', () => {
    const rows = [hourRow('2026-11-08', 9)];
    const map = new Map([
      ['2026-11-07', { hourly: [] }],
      ['2026-11-08', { hourly: rows }],
    ]);
    expect(comfortDayOf(map, NOW)).toEqual({ date: '2026-11-08', rows });
  });

  it('prefers today while it has hours to come, else the next day, else today again', () => {
    const todayRows = [hourRow('2026-11-06', 9), hourRow('2026-11-06', 11)];
    const tomorrowRows = [hourRow('2026-11-07', 9)];
    const both = new Map([['2026-11-06', { hourly: todayRows }], ['2026-11-07', { hourly: tomorrowRows }]]);
    expect(comfortDayOf(both, NOW)).toEqual({ date: '2026-11-06', rows: todayRows });

    const gone = [hourRow('2026-11-06', 7)];
    const goneBoth = new Map([['2026-11-06', { hourly: gone }], ['2026-11-07', { hourly: tomorrowRows }]]);
    expect(comfortDayOf(goneBoth, NOW)).toEqual({ date: '2026-11-07', rows: tomorrowRows });

    const goneOnly = new Map([['2026-11-06', { hourly: gone }]]);
    expect(comfortDayOf(goneOnly, NOW)).toEqual({ date: '2026-11-06', rows: gone });
  });
});

describe('hourlyDaysOf edges', () => {
  it('ignores null entries and empty hourly arrays', () => {
    const map = new Map([
      ['2026-11-07', null],
      ['2026-11-08', { hourly: [] }],
      ['2026-11-09', { hourly: [hourRow('2026-11-09', 9)] }],
    ]);
    expect(hourlyDaysOf(map).map((d) => d.date)).toEqual(['2026-11-09']);
  });

  it('skips blank dates named by alsoDates', () => {
    const days = hourlyDaysOf(new Map(), { alsoDates: ['', null, undefined, '2026-11-08'] });
    expect(days.map((d) => d.date)).toEqual(['2026-11-08']);
  });

  it('names every day by weekday and date, never Today', () => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    const days = hourlyDaysOf(new Map(), { alsoDates: ['2026-11-06', '2026-11-07'] });
    expect(days.map((d) => d.label)).toEqual(['Fri 6 Nov', 'Sat 7 Nov']);
  });

  it('lists named days with no rows when the source is not a map', () => {
    const days = hourlyDaysOf({}, { alsoDates: ['2026-11-08'] });
    expect(days).toEqual([{ date: '2026-11-08', label: 'Sun 8 Nov', rows: [] }]);
    expect(hourlyDaysOf(null, { alsoDates: ['2026-11-08'] })[0].rows).toEqual([]);
  });

  it('drops days before fromDate and sorts the rest', () => {
    const rows = (d) => [hourRow(d, 9)];
    const map = new Map([
      ['2026-11-09', { hourly: rows('2026-11-09') }],
      ['2026-11-05', { hourly: rows('2026-11-05') }],
      ['2026-11-07', { hourly: rows('2026-11-07') }],
    ]);
    const days = hourlyDaysOf(map, { fromDate: '2026-11-06' });
    expect(days.map((d) => d.date)).toEqual(['2026-11-07', '2026-11-09']);
    expect(days[0].rows).toEqual(rows('2026-11-07'));
  });
});
