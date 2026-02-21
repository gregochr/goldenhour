import { describe, it, expect } from 'vitest';
import {
  mpsToMph,
  metresToKm,
  degreesToCompass,
  formatDateLabel,
  groupForecastsByDate,
} from '../utils/conversions.js';

describe('mpsToMph', () => {
  it('converts 0 correctly', () => {
    expect(mpsToMph(0)).toBe(0);
  });

  it('converts a known value correctly', () => {
    expect(mpsToMph(10)).toBe(22.4);
  });

  it('handles string input', () => {
    expect(mpsToMph('5')).toBe(11.2);
  });
});

describe('metresToKm', () => {
  it('converts 10000m to 10km', () => {
    expect(metresToKm(10000)).toBe(10);
  });

  it('converts 15000m to 15km', () => {
    expect(metresToKm(15000)).toBe(15);
  });
});

describe('degreesToCompass', () => {
  it('converts 0° to N', () => {
    expect(degreesToCompass(0)).toBe('N');
  });

  it('converts 45° to NE', () => {
    expect(degreesToCompass(45)).toBe('NE');
  });

  it('converts 90° to E', () => {
    expect(degreesToCompass(90)).toBe('E');
  });

  it('converts 180° to S', () => {
    expect(degreesToCompass(180)).toBe('S');
  });

  it('converts 270° to W', () => {
    expect(degreesToCompass(270)).toBe('W');
  });

  it('converts 315° to NW', () => {
    expect(degreesToCompass(315)).toBe('NW');
  });

  it('converts 360° back to N', () => {
    expect(degreesToCompass(360)).toBe('N');
  });
});

describe('formatDateLabel', () => {
  const now = new Date('2026-02-20T12:00:00Z');

  it('returns "Today" for the current date', () => {
    expect(formatDateLabel('2026-02-20', now)).toBe('Today');
  });

  it('returns "Tomorrow" for the next date', () => {
    expect(formatDateLabel('2026-02-21', now)).toBe('Tomorrow');
  });

  it('returns a formatted date string for further dates', () => {
    const label = formatDateLabel('2026-02-25', now);
    expect(label).toContain('25');
    expect(label).toContain('Feb');
  });
});

describe('groupForecastsByDate', () => {
  const older = {
    targetDate: '2026-02-20',
    targetType: 'SUNSET',
    forecastRunAt: '2026-02-20T06:00:00Z',
    rating: 2,
  };
  const newer = {
    targetDate: '2026-02-20',
    targetType: 'SUNSET',
    forecastRunAt: '2026-02-20T18:00:00Z',
    rating: 4,
  };
  const sunrise = {
    targetDate: '2026-02-20',
    targetType: 'SUNRISE',
    forecastRunAt: '2026-02-20T06:00:00Z',
    rating: 3,
  };

  it('groups forecasts by date', () => {
    const result = groupForecastsByDate([older, sunrise]);
    expect(result.has('2026-02-20')).toBe(true);
  });

  it('keeps only the most recent run for each date+type', () => {
    const result = groupForecastsByDate([older, newer]);
    expect(result.get('2026-02-20').sunset.rating).toBe(4);
  });

  it('keeps sunrise and sunset separately', () => {
    const result = groupForecastsByDate([older, sunrise]);
    expect(result.get('2026-02-20').sunrise).not.toBeNull();
    expect(result.get('2026-02-20').sunset).not.toBeNull();
  });

  it('returns null for missing type', () => {
    const result = groupForecastsByDate([sunrise]);
    expect(result.get('2026-02-20').sunset).toBeNull();
  });
});
