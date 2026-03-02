import { describe, it, expect } from 'vitest';
import {
  formatDuration,
  formatShiftedEventTimeUk,
  formatGeneratedAtFull,
  groupForecastsByLocation,
} from '../utils/conversions.js';

describe('formatDuration', () => {
  it('returns 0s for 0', () => {
    expect(formatDuration(0)).toBe('0s');
  });

  it('returns 0s for null', () => {
    expect(formatDuration(null)).toBe('0s');
  });

  it('returns 0s for undefined', () => {
    expect(formatDuration(undefined)).toBe('0s');
  });

  it('returns 0s for negative value', () => {
    expect(formatDuration(-1000)).toBe('0s');
  });

  it('formats 5000ms as 5s', () => {
    expect(formatDuration(5000)).toBe('5s');
  });

  it('formats 45000ms as 45s', () => {
    expect(formatDuration(45000)).toBe('45s');
  });

  it('formats 65000ms as 1m 5s', () => {
    expect(formatDuration(65000)).toBe('1m 5s');
  });

  it('formats 3661000ms as 1h 1m 1s', () => {
    expect(formatDuration(3661000)).toBe('1h 1m 1s');
  });

  it('formats 7200000ms as 2h 0m 0s', () => {
    expect(formatDuration(7200000)).toBe('2h 0m 0s');
  });
});

describe('formatShiftedEventTimeUk', () => {
  it('returns null for null input', () => {
    expect(formatShiftedEventTimeUk(null, 30)).toBeNull();
  });

  it('returns null for undefined input', () => {
    expect(formatShiftedEventTimeUk(undefined, 30)).toBeNull();
  });

  it('returns null for invalid date string', () => {
    expect(formatShiftedEventTimeUk('not-a-date', 30)).toBeNull();
  });

  it('shifts a winter date forward by +30 minutes', () => {
    // 20 Feb is GMT (UTC+0), so 07:00 UTC + 30 min = 07:30 UK
    const result = formatShiftedEventTimeUk('2026-02-20T07:00:00', 30);
    expect(result).toBe('07:30');
  });

  it('shifts a date with a negative offset', () => {
    // 20 Feb is GMT (UTC+0), so 07:30 UTC - 30 min = 07:00 UK
    const result = formatShiftedEventTimeUk('2026-02-20T07:30:00', -30);
    expect(result).toBe('07:00');
  });
});

describe('formatGeneratedAtFull', () => {
  it('returns null for null input', () => {
    expect(formatGeneratedAtFull(null)).toBeNull();
  });

  it('returns null for undefined input', () => {
    expect(formatGeneratedAtFull(undefined)).toBeNull();
  });

  it('returns null for invalid date string', () => {
    expect(formatGeneratedAtFull('not-a-date')).toBeNull();
  });

  it('formats a winter date as full UK datetime', () => {
    // 20 Feb is GMT (UTC+0), so 13:25 UTC = 13:25 UK
    const result = formatGeneratedAtFull('2026-02-20T13:25:00');
    expect(result).toContain('20 Feb 2026');
    expect(result).toContain('13:25');
  });
});

describe('groupForecastsByLocation', () => {
  it('returns an empty array for an empty input', () => {
    expect(groupForecastsByLocation([])).toEqual([]);
  });

  it('groups two forecasts at the same location', () => {
    const forecasts = [
      {
        locationName: 'Durdle Door',
        locationLat: 50.62,
        locationLon: -2.27,
        targetDate: '2026-02-20',
        targetType: 'SUNSET',
        forecastRunAt: '2026-02-20T06:00:00Z',
        rating: 3,
      },
      {
        locationName: 'Durdle Door',
        locationLat: 50.62,
        locationLon: -2.27,
        targetDate: '2026-02-20',
        targetType: 'SUNRISE',
        forecastRunAt: '2026-02-20T06:00:00Z',
        rating: 2,
      },
    ];

    const result = groupForecastsByLocation(forecasts);
    expect(result).toHaveLength(1);
    expect(result[0].name).toBe('Durdle Door');
    expect(result[0].forecastsByDate.has('2026-02-20')).toBe(true);
  });

  it('separates forecasts at different locations', () => {
    const forecasts = [
      {
        locationName: 'Durdle Door',
        locationLat: 50.62,
        locationLon: -2.27,
        targetDate: '2026-02-20',
        targetType: 'SUNSET',
        forecastRunAt: '2026-02-20T06:00:00Z',
        rating: 4,
      },
      {
        locationName: 'Salisbury Cathedral',
        locationLat: 51.06,
        locationLon: -1.79,
        targetDate: '2026-02-20',
        targetType: 'SUNSET',
        forecastRunAt: '2026-02-20T06:00:00Z',
        rating: 3,
      },
    ];

    const result = groupForecastsByLocation(forecasts);
    expect(result).toHaveLength(2);
    expect(result[0].name).toBe('Durdle Door');
    expect(result[1].name).toBe('Salisbury Cathedral');
  });

  it('preserves lat/lon from the first forecast for each location', () => {
    const forecasts = [
      {
        locationName: 'Durdle Door',
        locationLat: 50.62,
        locationLon: -2.27,
        targetDate: '2026-02-20',
        targetType: 'SUNSET',
        forecastRunAt: '2026-02-20T06:00:00Z',
        rating: 3,
      },
      {
        locationName: 'Durdle Door',
        locationLat: 50.62,
        locationLon: -2.27,
        targetDate: '2026-02-21',
        targetType: 'SUNSET',
        forecastRunAt: '2026-02-21T06:00:00Z',
        rating: 4,
      },
    ];

    const result = groupForecastsByLocation(forecasts);
    expect(result[0].lat).toBe(50.62);
    expect(result[0].lon).toBe(-2.27);
  });
});
