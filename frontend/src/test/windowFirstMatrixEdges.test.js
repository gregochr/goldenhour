import { describe, it, expect, beforeEach, vi } from 'vitest';

let mod;

beforeEach(async () => {
  vi.resetModules();
  mod = await import('../utils/windowFirstMatrix.js');
});

const card = (date, targetType, extra = {}) => ({
  key: `${date}:${targetType}`, date, targetType, dow: 'Mon', away: false, ...extra,
});

describe('the matrix constants', () => {
  it('places the four grid rows in render order', () => {
    expect(mod.MATRIX_ROW).toEqual({
      header: 1, topics: 2, sunrise: 3, sunset: 4,
    });
  });

  it('words the two explained holes', () => {
    expect(mod.EMPTY_CELL_COPY).toEqual({
      morningGone: 'this morning has gone',
      beyondForecast: 'past the end of the forecast',
    });
  });
});

describe('buildWindowMatrix — away days', () => {
  it('marks a day away when every window it renders is a travel one', () => {
    const { days } = mod.buildWindowMatrix([
      card('2026-11-02', 'SUNRISE', { away: true }),
      card('2026-11-02', 'SUNSET', { away: true }),
      card('2026-11-03', 'SUNSET', { away: true }),
    ], '2026-11-02');
    expect(days.map((d) => d.away)).toEqual([true, true]);
  });

  it('marks a day not away as soon as one of its windows is not a travel one', () => {
    const { days } = mod.buildWindowMatrix([
      card('2026-11-02', 'SUNRISE', { away: true }),
      card('2026-11-02', 'SUNSET', { away: false }),
    ], '2026-11-02');
    expect(days[0].away).toBe(false);
  });
});

describe('buildWindowMatrix — the two explained holes are narrow', () => {
  it('says "this morning has gone" only on the first day, and only when it is today', () => {
    const { days } = mod.buildWindowMatrix([
      card('2026-11-02', 'SUNSET'),
      card('2026-11-03', 'SUNRISE'),
      card('2026-11-03', 'SUNSET'),
    ], '2026-11-02');
    expect(days[0].amEmpty).toBe('this morning has gone');
    expect(days[1].amEmpty).toBeNull();
  });

  it('does not say it on the first day when that day is not today', () => {
    const { days } = mod.buildWindowMatrix([
      card('2026-11-03', 'SUNSET'),
      card('2026-11-04', 'SUNRISE'),
      card('2026-11-04', 'SUNSET'),
    ], '2026-11-02');
    expect(days[0].amEmpty).toBeNull();
  });

  it('does not say it on a LATER day that happens to be today with no sunrise', () => {
    const { days } = mod.buildWindowMatrix([
      card('2026-11-01', 'SUNRISE'),
      card('2026-11-01', 'SUNSET'),
      card('2026-11-02', 'SUNSET'),
    ], '2026-11-02');
    expect(days[1].today).toBe(true);
    expect(days[1].amEmpty).toBeNull();
  });

  it('says "past the end of the forecast" only on the last day', () => {
    const { days } = mod.buildWindowMatrix([
      card('2026-11-02', 'SUNRISE'),
      card('2026-11-03', 'SUNRISE'),
      card('2026-11-03', 'SUNSET'),
      card('2026-11-04', 'SUNRISE'),
    ], '2026-11-02');
    expect(days.map((d) => d.pmEmpty)).toEqual([null, null, 'past the end of the forecast']);
  });
});

describe('buildWindowMatrix — input guards', () => {
  it('builds nothing from a missing list, or from entries that are not cards', () => {
    expect(mod.buildWindowMatrix(null, '2026-11-02')).toEqual({ columns: 0, days: [] });
    expect(mod.buildWindowMatrix(['Stryker', 42, null, {}], '2026-11-02')).toEqual({ columns: 0, days: [] });
  });

  it('drops a card of a kind the grid cannot place, along with its day', () => {
    const { columns, days } = mod.buildWindowMatrix([card('2026-11-02', 'ASTRO')], '2026-11-02');
    expect(columns).toBe(0);
    expect(days).toEqual([]);
  });
});
