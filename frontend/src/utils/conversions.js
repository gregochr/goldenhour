/**
 * Utility functions for unit conversions and date/label formatting.
 */

const COMPASS_POINTS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
const MS_TO_MPH = 2.23694;

/**
 * Formats milliseconds as a human-readable duration string.
 *
 * Examples: "45s", "2m 5s", "1h 23m 10s"
 *
 * @param {number} milliseconds - Duration in milliseconds.
 * @returns {string} Formatted duration string.
 */
export function formatDuration(milliseconds) {
  if (!milliseconds || milliseconds < 0) return '0s';
  const totalSeconds = Math.round(milliseconds / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}h ${minutes}m ${seconds}s`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds}s`;
  }
  return `${seconds}s`;
}

/**
 * Converts metres per second to miles per hour.
 *
 * @param {number} mps - Speed in metres per second.
 * @returns {number} Speed in miles per hour, rounded to one decimal place.
 */
export function mpsToMph(mps) {
  return Math.round(parseFloat(mps) * MS_TO_MPH * 10) / 10;
}

/**
 * Converts metres to kilometres.
 *
 * @param {number} metres - Distance in metres.
 * @returns {number} Distance in kilometres, rounded to one decimal place.
 */
export function metresToKm(metres) {
  return Math.round(parseFloat(metres) / 100) / 10;
}

/**
 * Converts wind direction in degrees to a compass point abbreviation.
 *
 * @param {number} degrees - Wind direction in degrees (0–360).
 * @returns {string} Compass point (e.g. "NE", "SW").
 */
export function degreesToCompass(degrees) {
  const index = Math.round(parseFloat(degrees) / 45) % 8;
  return COMPASS_POINTS[(index + 8) % 8];
}

/**
 * Formats a date string as a human-readable day label relative to today.
 *
 * Returns "Today", "Tomorrow", or a formatted date like "Wed 25 Feb".
 *
 * @param {string} dateStr - ISO date string (YYYY-MM-DD).
 * @param {Date} [now=new Date()] - Reference date for relative labels.
 * @param {boolean} [skipRelative=false] - If true, always returns the formatted date (e.g. "Sat 28 Feb").
 * @returns {string} Human-readable label.
 */
export function formatDateLabel(dateStr, now = new Date(), skipRelative = false) {
  const todayUtc = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  const [year, month, day] = dateStr.split('-').map(Number);
  const targetUtc = Date.UTC(year, month - 1, day);
  const diffDays = Math.round((targetUtc - todayUtc) / (1000 * 60 * 60 * 24));

  if (!skipRelative) {
    if (diffDays === 0) return 'Today';
    if (diffDays === 1) return 'Tomorrow';
  }

  const date = new Date(targetUtc);
  return date.toLocaleDateString('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  });
}

/**
 * Formats a UTC solar event timestamp shifted by an offset as UK local time (HH:MM).
 *
 * Useful for computing golden hour and blue hour window boundaries.
 *
 * @param {string|null} utcDateTimeStr - ISO-like datetime string without timezone suffix.
 * @param {number} offsetMinutes - Minutes to add (negative to subtract).
 * @returns {string|null} Formatted time like "07:30", or null.
 */
export function formatShiftedEventTimeUk(utcDateTimeStr, offsetMinutes) {
  if (!utcDateTimeStr) return null;
  const utcDate = new Date(utcDateTimeStr + 'Z');
  if (isNaN(utcDate.getTime())) return null;
  utcDate.setMinutes(utcDate.getMinutes() + offsetMinutes);
  return utcDate.toLocaleTimeString('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Europe/London',
  });
}

/**
 * Formats a UTC solar event timestamp as UK local time (HH:MM).
 *
 * Automatically handles GMT/BST conversion via the Europe/London timezone.
 * Returns null for falsy input (e.g. older records without a stored time).
 *
 * @param {string|null} utcDateTimeStr - ISO-like datetime string without timezone suffix (e.g. "2026-02-20T07:30:00").
 * @returns {string|null} Formatted time like "07:30", or null.
 */
export function formatEventTimeUk(utcDateTimeStr) {
  if (!utcDateTimeStr) return null;
  const utcDate = new Date(utcDateTimeStr + 'Z');
  if (isNaN(utcDate.getTime())) return null;
  return utcDate.toLocaleTimeString('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Europe/London',
  });
}

/**
 * Formats a UTC forecast run timestamp as a full UK local datetime string including year.
 *
 * Returns a string like "23 Feb 2026 13:25" for display in map popups and detail views.
 * Returns null for falsy input.
 *
 * @param {string|null} utcDateTimeStr - ISO-like datetime string without timezone suffix.
 * @returns {string|null} Formatted string like "23 Feb 2026 13:25", or null.
 */
export function formatGeneratedAtFull(utcDateTimeStr) {
  if (!utcDateTimeStr) return null;
  const utcDate = new Date(utcDateTimeStr + 'Z');
  if (isNaN(utcDate.getTime())) return null;
  const date = utcDate.toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'Europe/London',
  });
  const time = utcDate.toLocaleTimeString('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Europe/London',
  });
  return `${date} ${time}`;
}

/**
 * Groups an array of forecast evaluations by location, then by date within each location.
 *
 * Locations are returned in the order they first appear in the array (i.e. the order
 * the backend returns them, which matches the configured locations list).
 *
 * @param {Array<object>} forecasts - Raw forecast evaluations from the API.
 * @returns {Array<{name: string, lat: number, lon: number, forecastsByDate: Map}>}
 */
export function groupForecastsByLocation(forecasts) {
  const locationMap = new Map();

  for (const forecast of forecasts) {
    const name = forecast.locationName;
    if (!locationMap.has(name)) {
      locationMap.set(name, {
        name,
        lat: parseFloat(forecast.locationLat),
        lon: parseFloat(forecast.locationLon),
        evaluations: [],
      });
    }
    locationMap.get(name).evaluations.push(forecast);
  }

  return Array.from(locationMap.values()).map((loc) => ({
    name: loc.name,
    lat: loc.lat,
    lon: loc.lon,
    forecastsByDate: groupForecastsByDate(loc.evaluations),
  }));
}

/**
 * Groups an array of forecast evaluations by date, keeping only the most
 * recent run for each date+type combination.
 *
 * HOURLY rows (WILDLIFE model) are collected into a sorted {@code hourly} array
 * keyed by the full UTC hour timestamp. Only the most-recent run per hour slot
 * is kept.
 *
 * @param {Array<object>} forecasts - Raw forecast evaluations from the API.
 * @returns {Map<string, {sunrise: object|null, sunset: object|null, hourly: Array<object>}>}
 *   A map keyed by date string (YYYY-MM-DD).
 */
export function groupForecastsByDate(forecasts) {
  const map = new Map();
  // Intermediate: collect most-recent HOURLY row per hour slot
  const hourlyByDate = new Map();

  for (const forecast of forecasts) {
    const date = forecast.targetDate;
    if (!map.has(date)) {
      map.set(date, { sunrise: null, sunset: null, hourly: [] });
      hourlyByDate.set(date, new Map());
    }
    const entry = map.get(date);
    const type = forecast.targetType?.toLowerCase();

    if (type === 'sunrise' || type === 'sunset') {
      const existing = entry[type];
      if (
        !existing ||
        new Date(forecast.forecastRunAt) > new Date(existing.forecastRunAt)
      ) {
        entry[type] = forecast;
      }
    } else if (type === 'hourly') {
      // Key by solarEventTime (truncated to hour) — keep most-recent run per slot
      const slotKey = forecast.solarEventTime;
      const slotMap = hourlyByDate.get(date);
      const existing = slotMap.get(slotKey);
      if (
        !existing ||
        new Date(forecast.forecastRunAt) > new Date(existing.forecastRunAt)
      ) {
        slotMap.set(slotKey, forecast);
      }
    }
  }

  // Flatten hourly maps back into sorted arrays
  for (const [date, entry] of map.entries()) {
    const slotMap = hourlyByDate.get(date);
    if (slotMap && slotMap.size > 0) {
      entry.hourly = Array.from(slotMap.values()).sort(
        (a, b) => new Date(a.solarEventTime + 'Z') - new Date(b.solarEventTime + 'Z'),
      );
    }
  }

  return map;
}

/**
 * Formats a UTC timestamp as a full UK local date+time string.
 *
 * Returns a string like "2 Apr 2026, 14:31:12" for display in admin grids and alerts.
 * Handles both bare ISO strings (no suffix) and those with a trailing 'Z'.
 *
 * @param {string|null} utcDateTimeStr - ISO-like datetime string.
 * @returns {string|null} Formatted string, or null for falsy/invalid input.
 */
export function formatTimestampUk(utcDateTimeStr) {
  if (!utcDateTimeStr) return null;
  const d = new Date(utcDateTimeStr.endsWith('Z') ? utcDateTimeStr : utcDateTimeStr + 'Z');
  if (isNaN(d.getTime())) return null;
  return d.toLocaleString('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZone: 'Europe/London',
  });
}

/**
 * Formats a UTC timestamp as a relative time string ("Xm ago", "Xh ago", etc.).
 *
 * Handles both bare ISO strings (no suffix) and those with a trailing 'Z'.
 *
 * @param {string|null} utcDateTimeStr - ISO-like datetime string.
 * @returns {string} Relative time string, or empty string for falsy/invalid input.
 */
export function formatRelativeTimeUk(utcDateTimeStr) {
  if (!utcDateTimeStr) return '';
  const d = new Date(utcDateTimeStr.endsWith('Z') ? utcDateTimeStr : utcDateTimeStr + 'Z');
  if (isNaN(d.getTime())) return '';
  const diffMin = Math.round((Date.now() - d.getTime()) / 60000);
  if (diffMin < 1) return 'just now';
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHrs = Math.round(diffMin / 60);
  if (diffHrs < 24) return `${diffHrs}h ago`;
  return `${Math.floor(diffHrs / 24)}d ago`;
}

/**
 * Returns a plain-English label for a Bortle class value.
 *
 * @param {number|null} bortleClass - Bortle class (1–9), or null.
 * @returns {string|null} Label like "Rural sky", or null if unknown/null.
 */
export function bortleLabel(bortleClass) {
  if (bortleClass == null) return null;
  const labels = {
    1: 'Exceptional',
    2: 'Truly dark',
    3: 'Rural sky',
    4: 'Rural/suburban transition',
    5: 'Suburban sky',
    6: 'Bright suburban',
    7: 'Bright suburban',
    8: 'City sky',
    9: 'City sky',
  };
  return labels[bortleClass] ?? null;
}

const AUTO_SELECTION_BUFFER_MS = 30 * 60 * 1000; // 30-minute afterglow buffer

/**
 * Determines the next solar event to show on the map based on the current time.
 *
 * Rules:
 * - Find the earliest sunset today across all enabled non-wildlife locations.
 * - If now is before that sunset + 30 min → return today + SUNSET.
 * - If that window has passed → return tomorrow + SUNRISE.
 * - Returns null when no sunset data is available (e.g. data not yet loaded).
 *
 * @param {Array<object>} locations - Enabled locations with `forecastsByDate` Maps.
 * @param {Date} now - The current date/time (injectable for testing).
 * @returns {{ date: string, eventType: string }|null}
 */
export function computeAutoSelection(locations, now) {
  const todayStr = now.toLocaleDateString('en-CA'); // YYYY-MM-DD in local time

  let earliestSunset = null;
  for (const loc of locations) {
    if ((loc.locationType ?? []).every((t) => t === 'WILDLIFE')) continue;
    const sunsetTime = loc.forecastsByDate.get(todayStr)?.sunset?.solarEventTime;
    if (sunsetTime) {
      const t = new Date(sunsetTime + 'Z');
      if (!earliestSunset || t < earliestSunset) earliestSunset = t;
    }
  }

  if (!earliestSunset) return null;

  if (now < new Date(earliestSunset.getTime() + AUTO_SELECTION_BUFFER_MS)) {
    return { date: todayStr, eventType: 'SUNSET' };
  }

  const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
  return { date: tomorrow.toLocaleDateString('en-CA'), eventType: 'SUNRISE' };
}
