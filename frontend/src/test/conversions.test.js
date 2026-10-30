import { describe, it, expect } from 'vitest';
import {
  mpsToMph,
  degreesToCompass,
  formatDuration,
  formatDateLabel,
  formatEventTimeUk,
  formatGeneratedAtFull,
  formatTimestampUk,
  formatRelativeTimeUk,
  formatElapsedSince,
  groupForecastsByDate,
  colourForecastDates,
  isTravelDate,
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

  it('returns "Xh ago" for a whole number of hours', () => {
    const threeHoursAgo = new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString();
    const result = formatRelativeTimeUk(threeHoursAgo);
    expect(result).toMatch(/^\d+h ago$/);
  });

  it('keeps the minutes in the hours band — 94m reads as 1h 34m, not 2h', () => {
    // The case this resolution exists for: "was that batch sent ninety minutes ago or longer?"
    // Rounding to the nearest hour answers "2h ago" and destroys the distinction.
    const ninetyFourMinAgo = new Date(Date.now() - 94 * 60 * 1000).toISOString();
    expect(formatRelativeTimeUk(ninetyFourMinAgo)).toBe('1h 34m ago');
  });

  it('does not round a 59-minute gap up into the hours band', () => {
    const fiftyNineMinAgo = new Date(Date.now() - 59 * 60 * 1000).toISOString();
    expect(formatRelativeTimeUk(fiftyNineMinAgo)).toBe('59m ago');
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

describe('colourForecastDates', () => {
  // A wildlife hide's hourly comfort rows reach a day (T+5) further than the colour pipeline
  // evaluates. `groupForecastsByDate` opens an entry for ANY row, so before this a date holding only
  // those rows became a Sunrise/Sunset window on the Map tab that nothing rated.
  const hourly = (date, time) => ({
    targetDate: date, targetType: 'HOURLY', solarEventTime: `${date}T${time}`, forecastRunAt: `${date}T05:00:00Z`,
  });
  const sunset = (date) => ({
    targetDate: date, targetType: 'SUNSET', forecastRunAt: `${date}T05:00:00Z`, rating: 3,
  });

  it('does not count a date that holds only hourly rows', () => {
    const hide = { forecastsByDate: groupForecastsByDate([hourly('2026-10-07', '08:00:00')]) };
    expect(hide.forecastsByDate.has('2026-10-07')).toBe(true);
    expect(colourForecastDates([hide])).toEqual([]);
  });

  it('counts a date once a sunrise or a sunset stands behind it, for any location', () => {
    const sky = { forecastsByDate: groupForecastsByDate([sunset('2026-10-04')]) };
    const sunriseOnly = {
      forecastsByDate: groupForecastsByDate([{
        targetDate: '2026-10-05', targetType: 'SUNRISE', forecastRunAt: '2026-10-05T05:00:00Z',
      }]),
    };
    expect(colourForecastDates([sky, sunriseOnly])).toEqual(['2026-10-04', '2026-10-05']);
  });

  it('drops the hide-only T+5 day while keeping the sky dates, deduplicated and sorted', () => {
    const sky = { forecastsByDate: groupForecastsByDate([sunset('2026-10-04'), sunset('2026-10-03')]) };
    const hide = {
      forecastsByDate: groupForecastsByDate([
        hourly('2026-10-03', '08:00:00'), hourly('2026-10-04', '08:00:00'), hourly('2026-10-08', '08:00:00'),
      ]),
    };
    expect(colourForecastDates([hide, sky])).toEqual(['2026-10-03', '2026-10-04']);
  });
});

describe('isTravelDate', () => {
  const ranges = [
    { startDate: '2026-07-01', endDate: '2026-07-03' },
    { startDate: '2026-07-10', endDate: '2026-07-10' },
  ];

  it('returns true for a date inside a multi-day range', () => {
    expect(isTravelDate('2026-07-02', ranges)).toBe(true);
  });

  it('is inclusive of both range bounds', () => {
    expect(isTravelDate('2026-07-01', ranges)).toBe(true);
    expect(isTravelDate('2026-07-03', ranges)).toBe(true);
  });

  it('matches a single-day range', () => {
    expect(isTravelDate('2026-07-10', ranges)).toBe(true);
  });

  it('returns false for a date outside every range', () => {
    expect(isTravelDate('2026-07-05', ranges)).toBe(false);
  });

  it('returns false for empty or missing inputs', () => {
    expect(isTravelDate('2026-07-02', [])).toBe(false);
    expect(isTravelDate('2026-07-02', null)).toBe(false);
    expect(isTravelDate('', ranges)).toBe(false);
  });
});

describe('formatTimestampUk timezone label', () => {
  it('names BST in summer, so a local time is never mistaken for the UTC one', () => {
    // The reason this exists: the intraday cron is 14:00 UTC and the pipeline view rendered
    // "15:00:00" with no zone, leaving the reader converting between two clocks unaided.
    expect(formatTimestampUk('2026-08-12T14:00:00Z')).toContain('BST');
  });

  it('names GMT in winter', () => {
    expect(formatTimestampUk('2026-02-20T13:31:12Z')).toContain('GMT');
  });

  it('still renders the UK local time alongside the label', () => {
    const result = formatTimestampUk('2026-08-12T14:00:00Z');
    expect(result).toContain('15:00:00');
    expect(result).toContain('12');
    expect(result).toContain('Aug');
  });
});

describe('formatElapsedSince', () => {
  const START = '2026-08-12T14:04:22Z';
  const startMs = Date.parse(START);

  it('returns null for falsy input', () => {
    expect(formatElapsedSince(null)).toBeNull();
    expect(formatElapsedSince('')).toBeNull();
    expect(formatElapsedSince(undefined)).toBeNull();
  });

  it('returns null for an invalid date', () => {
    expect(formatElapsedSince('not-a-date')).toBeNull();
  });

  it('renders seconds under a minute', () => {
    expect(formatElapsedSince(START, startMs + 42_000)).toBe('42s');
  });

  it('renders minutes and seconds under an hour', () => {
    expect(formatElapsedSince(START, startMs + (4 * 60 + 16) * 1000)).toBe('4m 16s');
  });

  it('renders hours and minutes beyond an hour — the batch-latency case', () => {
    // 98 minutes: the low end of the observed afternoon batch latency band.
    expect(formatElapsedSince(START, startMs + 98 * 60 * 1000)).toBe('1h 38m');
  });

  it('returns null rather than a negative duration when the start is in the future', () => {
    // Browser/server clock skew. Saying nothing is honest; "-3s" is not.
    expect(formatElapsedSince(START, startMs - 3000)).toBeNull();
  });

  it('handles a bare timestamp with no Z suffix identically', () => {
    expect(formatElapsedSince('2026-08-12T14:04:22', startMs + 60_000)).toBe('1m 0s');
  });
});
