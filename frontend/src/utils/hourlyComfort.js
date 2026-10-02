/**
 * Reading the hourly comfort rows a wildlife hide carries — filter, min, max and join, nothing else.
 *
 * <p>A pure-wildlife location (`locationTypes.isWildlifeOnly`) has no sky rating by design. What the
 * backend writes for it instead is one `target_type = 'HOURLY'` row per daylight hour carrying
 * temperature, feels-like, wind speed and direction, and precipitation probability, which
 * `conversions.groupForecastsByDate` collects into each day's `hourly` array (latest run per
 * hour, in time order). Everything here is a read of those served rows: the range of a column, the
 * hour a maximum occurs at, the first and last hour. It decides nothing about what is "comfortable"
 * — that would be a rule about the product, and the product has not made one. That keeps it inside
 * the Backend-heavy bullet's already-licensed filter/map/select class (CLAUDE.md), not a new member
 * of the reach/scope classes.
 */
import { formatDateLabel, formatEventTimeUk, mpsToMph, degreesToCompass, parseUtcInstant } from './conversions.js';

/** A row's instant as a number, for ordering. Unparseable sorts as 0, ahead of everything. */
function instantOf(row) {
  return parseUtcInstant(row?.solarEventTime)?.getTime() ?? 0;
}

/** Whether a value is a usable finite number (a missing column arrives as null, never NaN). */
function isNum(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

/**
 * One wind reading as the table prints it: "11.2 mph SW".
 *
 * <p>The compass word is dropped when the direction is missing rather than printed as the string
 * "undefined" — `degreesToCompass(null)` is `NaN` through an array lookup, which is what the
 * popup's own cell used to print for a row with a speed and no direction.
 *
 * @param {?number} speedMps wind speed in metres per second
 * @param {?number} directionDeg wind direction in degrees
 * @returns {?string} the reading, or null when there is no speed
 */
export function formatWind(speedMps, directionDeg) {
  if (!isNum(speedMps)) return null;
  const compass = isNum(directionDeg) ? ` ${degreesToCompass(directionDeg)}` : '';
  return `${mpsToMph(speedMps)} mph${compass}`;
}

/**
 * The range of one column across the rows, rounded to whole degrees, or null when no row has it.
 *
 * @param {Array<object>} rows
 * @param {string} field
 * @returns {?{min: number, max: number}}
 */
function rangeOf(rows, field) {
  const values = rows.map((r) => r?.[field]).filter(isNum);
  if (values.length === 0) return null;
  return { min: Math.round(Math.min(...values)), max: Math.round(Math.max(...values)) };
}

/**
 * The row at which a column peaks. A tie goes to the EARLIEST hour: the rows are walked in time
 * order and only a strictly greater value replaces the holder, so "the hour it occurs" is never a
 * function of array order or of floating-point luck.
 *
 * @param {Array<object>} ordered rows, already in time order
 * @param {string} field
 * @returns {?{row: object, count: number}} the peak row and how many rows carried the column
 */
function peakOf(ordered, field) {
  let best = null;
  let count = 0;
  for (const row of ordered) {
    if (!isNum(row?.[field])) continue;
    count += 1;
    if (best == null || row[field] > best[field]) best = row;
  }
  return best ? { row: best, count } : null;
}

/**
 * Summarises one day's hourly rows: the temperature and feels-like ranges, the strongest wind and
 * the highest rain chance each with the hour it occurs, and the span of hours the rows cover.
 *
 * <p>Ties for the strongest wind and the highest rain chance resolve to the earliest hour. Wind is
 * compared on the served metres-per-second figure, not on the rounded mph that is printed, so two
 * hours that print alike but differ are not called a tie.
 *
 * @param {?Array<object>} rows the day's `hourly` rows (any order; a copy is sorted)
 * @returns {?object} the summary, or null when there are no rows to summarise
 */
export function comfortSummary(rows) {
  if (!Array.isArray(rows) || rows.length === 0) return null;
  const ordered = [...rows].sort((a, b) => instantOf(a) - instantOf(b));
  const times = ordered.map((r) => formatEventTimeUk(r.solarEventTime)).filter(Boolean);
  const wind = peakOf(ordered, 'windSpeed');
  const rain = peakOf(ordered, 'precipitationProbabilityPercent');
  return {
    hours: ordered.length,
    from: times[0] ?? null,
    to: times[times.length - 1] ?? null,
    temperature: rangeOf(ordered, 'temperatureCelsius'),
    feelsLike: rangeOf(ordered, 'apparentTemperatureCelsius'),
    wind: wind ? {
      speedMps: wind.row.windSpeed,
      directionDeg: isNum(wind.row.windDirection) ? wind.row.windDirection : null,
      time: formatEventTimeUk(wind.row.solarEventTime),
      readings: wind.count,
    } : null,
    rain: rain ? {
      percent: rain.row.precipitationProbabilityPercent,
      time: formatEventTimeUk(rain.row.solarEventTime),
      readings: rain.count,
    } : null,
  };
}

/** "6 to 12" or, when both ends round alike, "9". `to` rather than a dash so a negative reads. */
function spanText(range) {
  return range.min === range.max ? `${range.min}` : `${range.min} to ${range.max}`;
}

/**
 * The summary as short sentences, one per fact, each ending in a full stop so the lines read as
 * one passage when a screen reader runs them together.
 *
 * <p>"up to" appears only where more than one hour carried the figure: a single reading is not a
 * ceiling over anything.
 *
 * @param {?object} summary {@link comfortSummary}'s result
 * @returns {string[]} the lines, in the order temperature, wind, rain, hours; empty for null
 */
export function comfortLines(summary) {
  if (!summary) return [];
  const lines = [];
  if (summary.temperature) {
    const feels = summary.feelsLike ? `, feels like ${spanText(summary.feelsLike)}°C` : '';
    lines.push(`${spanText(summary.temperature)}°C${feels}.`);
  }
  if (summary.wind) {
    const reading = formatWind(summary.wind.speedMps, summary.wind.directionDeg);
    const upTo = summary.wind.readings > 1 ? 'up to ' : '';
    lines.push(`Wind ${upTo}${reading}${summary.wind.time ? ` at ${summary.wind.time}` : ''}.`);
  }
  if (summary.rain) {
    const { percent, time, readings } = summary.rain;
    if (percent === 0 && readings > 1) {
      lines.push('Rain chance 0% throughout.');
    } else {
      const upTo = readings > 1 ? 'up to ' : '';
      lines.push(`Rain chance ${upTo}${percent}%${time ? ` at ${time}` : ''}.`);
    }
  }
  if (summary.from && summary.to) {
    lines.push(summary.from === summary.to
      ? `Only the ${summary.from} hour is forecast.`
      : `Daylight hours ${summary.from} to ${summary.to}.`);
  }
  return lines;
}

/**
 * The days a hide's hourly forecast covers, in date order, each with its rows (possibly none).
 *
 * <p>A day is listed when it has hourly rows OR when `alsoDates` names it — the sheet passes the
 * dates of its own window rows, so a day the forecast window spans but the hourly job has not
 * written yet says so instead of silently not existing. Days before `fromDate` (UK today) are
 * dropped: a finished day's comfort forecast answers nothing.
 *
 * @param {?Map<string, {hourly: ?Array<object>}>} forecastsByDate the location's per-date forecasts
 * @param {object} [options]
 * @param {string} [options.fromDate] the first date to list, UK calendar, `YYYY-MM-DD`
 * @param {string[]} [options.alsoDates] dates to list even when they hold no rows
 * @returns {Array<{date: string, label: string, rows: Array<object>}>}
 */
export function hourlyDaysOf(forecastsByDate, { fromDate = '', alsoDates = [] } = {}) {
  const dates = new Set();
  if (forecastsByDate instanceof Map) {
    for (const [date, entry] of forecastsByDate) {
      if (Array.isArray(entry?.hourly) && entry.hourly.length > 0) dates.add(date);
    }
  }
  for (const date of alsoDates) {
    if (date) dates.add(date);
  }
  return [...dates]
    .filter((date) => date >= fromDate)
    .sort()
    .map((date) => ({
      date,
      // `skipRelative`: this sheet names days by weekday and date, never "Today"/"Tomorrow".
      label: formatDateLabel(date, new Date(), true),
      rows: forecastsByDate?.get?.(date)?.hourly ?? [],
    }));
}
