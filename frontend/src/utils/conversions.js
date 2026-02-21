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
