import { describe, it, expect } from 'vitest';
import {
  mpsToMph,
  metresToKm,
  degreesToCompass,
  formatDuration,
  formatDateLabel,
  formatEventTimeUk,
  formatShiftedEventTimeUk,
  formatGeneratedAtFull,
  formatTimestampUk,
  formatRelativeTimeUk,
  groupForecastsByDate,
  bortleLabel,
  formatTideHighlight,
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

describe('formatDuration', () => {
  it('returns "0s" for null input', () => {
    expect(formatDuration(null)).toBe('0s');
  });

  it('returns "0s" for zero', () => {
    expect(formatDuration(0)).toBe('0s');
  });

  it('returns "0s" for negative input', () => {
    expect(formatDuration(-500)).toBe('0s');
  });

  it('formats seconds only', () => {
    expect(formatDuration(45000)).toBe('45s');
  });

  it('formats minutes and seconds', () => {
    expect(formatDuration(125000)).toBe('2m 5s');
  });

  it('formats hours, minutes, and seconds', () => {
    expect(formatDuration(4990000)).toBe('1h 23m 10s');
  });

  it('rounds milliseconds to nearest second', () => {
    expect(formatDuration(1500)).toBe('2s');
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

describe('formatEventTimeUk', () => {
  it('returns null for null input', () => {
    expect(formatEventTimeUk(null)).toBeNull();
  });

  it('returns null for undefined input', () => {
    expect(formatEventTimeUk(undefined)).toBeNull();
  });

  it('returns null for empty string', () => {
    expect(formatEventTimeUk('')).toBeNull();
  });

  it('returns null for invalid date string', () => {
    expect(formatEventTimeUk('not-a-date')).toBeNull();
  });

  it('formats a winter UTC time as GMT (no offset)', () => {
    // 20 Feb is GMT (UTC+0), so 07:30 UTC = 07:30 UK
    const result = formatEventTimeUk('2026-02-20T07:30:00');
    expect(result).toBe('07:30');
  });

  it('formats a summer UTC time as BST (UTC+1)', () => {
    // 21 Jun is BST (UTC+1), so 03:30 UTC = 04:30 UK
    const result = formatEventTimeUk('2026-06-21T03:30:00');
    expect(result).toBe('04:30');
  });
});

describe('formatShiftedEventTimeUk', () => {
  it('returns null for null input', () => {
    expect(formatShiftedEventTimeUk(null, 30)).toBeNull();
  });

  it('returns null for invalid date string', () => {
    expect(formatShiftedEventTimeUk('not-a-date', 30)).toBeNull();
  });

  it('shifts forward by positive offset', () => {
    // 07:00 UTC + 30 min = 07:30 UK (winter)
    const result = formatShiftedEventTimeUk('2026-02-20T07:00:00', 30);
    expect(result).toBe('07:30');
  });

  it('shifts backward by negative offset', () => {
    // 07:30 UTC - 30 min = 07:00 UK (winter)
    const result = formatShiftedEventTimeUk('2026-02-20T07:30:00', -30);
    expect(result).toBe('07:00');
  });

  it('applies BST during summer', () => {
    // 03:30 UTC + 30 min = 04:00 UTC = 05:00 BST
    const result = formatShiftedEventTimeUk('2026-06-21T03:30:00', 30);
    expect(result).toBe('05:00');
  });
});

describe('formatGeneratedAtFull', () => {
  it('returns null for null input', () => {
    expect(formatGeneratedAtFull(null)).toBeNull();
  });

  it('returns null for empty string', () => {
    expect(formatGeneratedAtFull('')).toBeNull();
  });

  it('returns null for invalid date string', () => {
    expect(formatGeneratedAtFull('not-a-date')).toBeNull();
  });

  it('formats a winter UTC timestamp as full UK date+time', () => {
    const result = formatGeneratedAtFull('2026-02-23T13:25:00');
    expect(result).toContain('23');
    expect(result).toContain('Feb');
    expect(result).toContain('2026');
    expect(result).toContain('13:25');
  });

  it('applies BST offset during summer', () => {
    // 13:00 UTC = 14:00 BST
    const result = formatGeneratedAtFull('2026-06-21T13:00:00');
    expect(result).toContain('14:00');
  });
});

describe('formatTimestampUk', () => {
  it('returns null for null input', () => {
    expect(formatTimestampUk(null)).toBeNull();
  });

  it('returns null for empty string', () => {
    expect(formatTimestampUk('')).toBeNull();
  });

  it('returns null for invalid date string', () => {
    expect(formatTimestampUk('not-a-date')).toBeNull();
  });

  it('formats a UTC timestamp without Z suffix as UK time', () => {
    // Winter (GMT): 13:31:12 UTC = 13:31:12 UK
    const result = formatTimestampUk('2026-02-20T13:31:12');
    expect(result).toContain('20');
    expect(result).toContain('Feb');
    expect(result).toContain('2026');
    expect(result).toContain('13:31:12');
  });

  it('formats a UTC timestamp with Z suffix as UK time', () => {
    const result = formatTimestampUk('2026-02-20T13:31:12Z');
    expect(result).toContain('13:31:12');
  });

  it('applies BST offset during summer', () => {
    // Summer (BST): 13:00:00 UTC = 14:00:00 UK
    const result = formatTimestampUk('2026-06-21T13:00:00');
    expect(result).toContain('14:00:00');
  });

  it('returns null for undefined input', () => {
    expect(formatTimestampUk(undefined)).toBeNull();
  });

  it('handles DST transition day — clocks spring forward', () => {
    // 2026-03-29 01:00 UTC = 02:00 BST (clocks go forward at 01:00 UTC)
    const result = formatTimestampUk('2026-03-29T01:00:00');
    expect(result).toContain('02:00:00');
    expect(result).toContain('29');
    expect(result).toContain('Mar');
  });

  it('handles late-night UTC that rolls into next day in BST', () => {
    // 2026-07-15 23:30:00 UTC = 2026-07-16 00:30:00 BST
    const result = formatTimestampUk('2026-07-15T23:30:00');
    expect(result).toContain('16');
    expect(result).toContain('Jul');
    expect(result).toContain('00:30:00');
  });

  it('produces consistent result regardless of Z suffix', () => {
    const withZ = formatTimestampUk('2026-04-02T10:00:00Z');
    const withoutZ = formatTimestampUk('2026-04-02T10:00:00');
    expect(withZ).toBe(withoutZ);
  });
});

describe('formatRelativeTimeUk', () => {
  it('returns empty string for null input', () => {
    expect(formatRelativeTimeUk(null)).toBe('');
  });

  it('returns empty string for empty string input', () => {
    expect(formatRelativeTimeUk('')).toBe('');
  });

  it('returns empty string for invalid date', () => {
    expect(formatRelativeTimeUk('not-a-date')).toBe('');
  });

  it('returns "just now" for a timestamp less than 1 minute ago', () => {
    const now = new Date();
    const result = formatRelativeTimeUk(now.toISOString());
    expect(result).toBe('just now');
  });

  it('returns "Xm ago" for recent timestamps', () => {
    const fiveMinAgo = new Date(Date.now() - 5 * 60 * 1000).toISOString();
    const result = formatRelativeTimeUk(fiveMinAgo);
    expect(result).toMatch(/^\d+m ago$/);
  });

  it('returns "Xh ago" for timestamps a few hours old', () => {
    const threeHoursAgo = new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString();
    const result = formatRelativeTimeUk(threeHoursAgo);
    expect(result).toMatch(/^\d+h ago$/);
  });

  it('returns "Xd ago" for timestamps over 24 hours old', () => {
    const twoDaysAgo = new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString();
    const result = formatRelativeTimeUk(twoDaysAgo);
    expect(result).toMatch(/^\d+d ago$/);
  });

  it('handles input without Z suffix', () => {
    const now = new Date();
    const bare = now.toISOString().replace('Z', '');
    const result = formatRelativeTimeUk(bare);
    expect(result).toBe('just now');
  });

  it('returns empty string for undefined input', () => {
    expect(formatRelativeTimeUk(undefined)).toBe('');
  });

  it('produces same result regardless of Z suffix for same instant', () => {
    const iso = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    const bare = iso.replace('Z', '');
    expect(formatRelativeTimeUk(iso)).toBe(formatRelativeTimeUk(bare));
  });
});

describe('bortleLabel', () => {
  it('returns null for null input', () => {
    expect(bortleLabel(null)).toBeNull();
  });

  it('returns null for undefined input', () => {
    expect(bortleLabel(undefined)).toBeNull();
  });

  it('returns "Exceptional" for Bortle 1', () => {
    expect(bortleLabel(1)).toBe('Exceptional');
  });

  it('returns "Truly dark" for Bortle 2', () => {
    expect(bortleLabel(2)).toBe('Truly dark');
  });

  it('returns "Rural sky" for Bortle 3', () => {
    expect(bortleLabel(3)).toBe('Rural sky');
  });

  it('returns "Rural/suburban transition" for Bortle 4', () => {
    expect(bortleLabel(4)).toBe('Rural/suburban transition');
  });

  it('returns "Suburban sky" for Bortle 5', () => {
    expect(bortleLabel(5)).toBe('Suburban sky');
  });

  it('returns "Bright suburban" for Bortle 6', () => {
    expect(bortleLabel(6)).toBe('Bright suburban');
  });

  it('returns "Bright suburban" for Bortle 7', () => {
    expect(bortleLabel(7)).toBe('Bright suburban');
  });

  it('returns "City sky" for Bortle 8', () => {
    expect(bortleLabel(8)).toBe('City sky');
  });

  it('returns "City sky" for Bortle 9', () => {
    expect(bortleLabel(9)).toBe('City sky');
  });

  it('returns null for out-of-range value 0', () => {
    expect(bortleLabel(0)).toBeNull();
  });

  it('returns null for out-of-range value 10', () => {
    expect(bortleLabel(10)).toBeNull();
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

describe('formatTideHighlight', () => {
  it('reformats plural king tide', () => {
    expect(formatTideHighlight('King Tide at 3 coastal spots')).toBe('3 king tides');
  });

  it('reformats singular spring tide', () => {
    expect(formatTideHighlight('Spring Tide at 1 coastal spot')).toBe('1 spring tide');
  });

  it('reformats composite label without adding s', () => {
    expect(formatTideHighlight('King Tide, Extra Extra High at 2 coastal spots'))
      .toBe('2 king tide, extra extra high');
  });

  it('returns original string when pattern does not match', () => {
    expect(formatTideHighlight('King tide at Bamburgh')).toBe('King tide at Bamburgh');
  });

  it('handles singular "spot" correctly', () => {
    expect(formatTideHighlight('Spring Tide at 1 coastal spot')).toBe('1 spring tide');
  });

  it('preserves extra-high composite without trailing s', () => {
    expect(formatTideHighlight('Spring Tide, Extra High at 1 coastal spot'))
      .toBe('1 spring tide, extra high');
  });
});
