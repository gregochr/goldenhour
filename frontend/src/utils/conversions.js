/**
 * Utility functions for unit conversions and date/label formatting.
 */

const COMPASS_POINTS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
const MS_TO_MPH = 2.23694;

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
 * @returns {string} Human-readable label.
 */
export function formatDateLabel(dateStr, now = new Date()) {
  const todayUtc = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  const [year, month, day] = dateStr.split('-').map(Number);
  const targetUtc = Date.UTC(year, month - 1, day);
  const diffDays = Math.round((targetUtc - todayUtc) / (1000 * 60 * 60 * 24));

  if (diffDays === 0) return 'Today';
  if (diffDays === 1) return 'Tomorrow';

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
 * Formats a UTC forecast run timestamp as a compact UK local datetime string.
 *
 * Returns a string like "22 Feb 15:33" for display alongside the forecast rating.
 * Returns null for falsy input.
 *
 * @param {string|null} utcDateTimeStr - ISO-like datetime string without timezone suffix (e.g. "2026-02-22T15:33:00").
 * @returns {string|null} Formatted string like "22 Feb 15:33", or null.
 */
export function formatGeneratedAt(utcDateTimeStr) {
  if (!utcDateTimeStr) return null;
  const utcDate = new Date(utcDateTimeStr + 'Z');
  if (isNaN(utcDate.getTime())) return null;
  const date = utcDate.toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
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
 * @param {Array<object>} forecasts - Raw forecast evaluations from the API.
 * @returns {Map<string, {sunrise: object|null, sunset: object|null}>}
 *   A map keyed by date string (YYYY-MM-DD) with sunrise and sunset entries.
 */
export function groupForecastsByDate(forecasts) {
  const map = new Map();

  for (const forecast of forecasts) {
    const date = forecast.targetDate;
    if (!map.has(date)) {
      map.set(date, { sunrise: null, sunset: null });
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
    }
  }

  return map;
}
