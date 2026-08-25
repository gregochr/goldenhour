/**
 * Utility functions for unit conversions and date/label formatting.
 */
import { ukDateStr, ukDateStrOffset, ukDayOffset, UK_ZONE } from './mapDates.js';

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
  // "Today"/"Tomorrow" on the UK calendar, not the browser's. `DateStrip` decides which chip is
  // today with `ukDateStr` and then calls this for every other chip, so a second basis here could
  // put "Today" on two chips at once for a reader outside the UK.
  const diffDays = ukDayOffset(dateStr, now);
  const [year, month, day] = dateStr.split('-').map(Number);
  const targetUtc = Date.UTC(year, month - 1, day);

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
 * True when a datetime string already names its own offset, so appending `Z` would corrupt it.
 *
 * <p>Anchored at the end on purpose: the `-` in `2026-04-08` is not an offset, and an unanchored
 * `[+-]` would match it.
 */
const HAS_ZONE_SUFFIX = /(?:Z|[+-]\d{2}:?\d{2})$/;

/**
 * Parses a backend instant into a {@code Date}, or null when there is nothing usable.
 *
 * <p>The API sends instants in two shapes and this has to accept both, because which one you get is
 * a property of the Java type on the other side rather than of the field's meaning. A Jackson
 * {@code Instant} serialises with a trailing {@code Z} ({@code termsAcceptedAt},
 * {@code detectedAt}); a {@code LocalDateTime} serialises bare ({@code submittedAt},
 * {@code runTimestamp}, every solar event time) even though the value it holds was produced in UTC.
 *
 * <p>⚠️ A bare string is the dangerous one, and it is why this exists rather than each caller doing
 * its own {@code new Date(…)}. JavaScript parses {@code "2026-04-08T14:32:00"} as <b>local</b> time,
 * so a UTC value read that way is silently shifted by the reader's own offset — and then formatting
 * it locally shifts it back, which is what made the bug invisible: the digits came out right, but
 * they were UTC digits presented as if they were the reader's clock, an hour off the UK for the
 * seven months of BST. Appending {@code Z} states what the value already meant.
 *
 * <p>Exported because a caller that needs the UK <em>day</em> of an instant (rather than a rendering
 * of it) still has to get the instant right first — {@code ukDateStr(parseUtcInstant(x))} rather
 * than {@code ukDateStr(new Date(x))}, which would reintroduce the bare-string trap above for the
 * one question where being a day out is the whole failure.
 *
 * @param {string|Date|null} value - a backend instant, or a Date already in hand
 * @returns {Date|null} the instant, or null when absent or unparseable
 */
export function parseUtcInstant(value) {
  if (!value) return null;
  if (value instanceof Date) return isNaN(value.getTime()) ? null : value;
  if (typeof value !== 'string') return null;
  const d = new Date(HAS_ZONE_SUFFIX.test(value) ? value : `${value}Z`);
  return isNaN(d.getTime()) ? null : d;
}

/**
 * Formats a backend instant on the <b>UK</b> calendar and clock, with the caller's own field set.
 *
 * <p>This is the general form the named helpers below are built from, and the one to reach for
 * when none of them fits. The point is not brevity — it is that {@code timeZone} is supplied
 * <em>here</em> rather than by the caller, so a call site cannot forget it. Every hand-rolled
 * {@code toLocaleDateString('en-GB', …)} in this codebase that did forget it rendered a UK instant
 * on the reader's own calendar: an acceptance stored at {@code 2026-04-01T00:00:00Z} read
 * "31 Mar 2026" to an admin west of Greenwich. The zone is deliberately not a parameter.
 *
 * <p>{@code Europe/London} rather than UTC because these are wall-clock facts for a UK audience,
 * and it is the calendar the rest of the app already answers to — the backend keys every forecast
 * date to it (`ForecastHorizon.today`) and {@code mapDates.js} reads it. {@code Intl} derives BST or
 * GMT from the instant itself, so there is no seasonal rule here to get wrong.
 *
 * @param {string|Date|null} value - a backend instant (bare or `Z`-suffixed), or a Date
 * @param {Intl.DateTimeFormatOptions} options - the fields to render; `timeZone` is ignored
 * @returns {string|null} the formatted string, or null when the instant is absent or unparseable
 */
export function formatInstantUk(value, options) {
  const d = parseUtcInstant(value);
  if (!d) return null;
  return d.toLocaleString('en-GB', { ...options, timeZone: UK_ZONE });
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
  return formatInstantUk(utcDateTimeStr, { hour: '2-digit', minute: '2-digit' });
}

/**
 * Formats a UTC forecast run timestamp as a full UK local datetime string including year.
 *
 * Returns a string like "23 Feb 2026 13:25" for display in map popups and detail views.
 * Returns null for falsy input.
 *
 * <p>Two calls rather than one options object with all five fields: `en-GB` puts a comma between
 * the date and the time when asked for both at once, and this format is on screen beside others
 * that have never had one.
 *
 * @param {string|null} utcDateTimeStr - ISO-like datetime string without timezone suffix.
 * @returns {string|null} Formatted string like "23 Feb 2026 13:25", or null.
 */
export function formatGeneratedAtFull(utcDateTimeStr) {
  const date = formatInstantUk(utcDateTimeStr, { day: 'numeric', month: 'short', year: 'numeric' });
  if (!date) return null;
  return `${date} ${formatInstantUk(utcDateTimeStr, { hour: '2-digit', minute: '2-digit' })}`;
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
/**
 * Tests whether an ISO date string (YYYY-MM-DD) falls inside any travel range.
 *
 * <p>Ranges are inclusive of both bounds. ISO date strings sort lexicographically
 * in chronological order, so plain string comparison is correct here.
 *
 * @param {string} dateStr - the date to test, "YYYY-MM-DD"
 * @param {Array<{startDate: string, endDate: string}>} ranges - travel ranges
 * @returns {boolean} true if the date lies within at least one range
 */
export function isTravelDate(dateStr, ranges) {
  if (!dateStr || !ranges || ranges.length === 0) return false;
  return ranges.some((r) => dateStr >= r.startDate && dateStr <= r.endDate);
}

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
 * Returns a string like "2 Apr 2026, 14:31:12 BST" for display in admin grids and alerts.
 * Handles both bare ISO strings (no suffix) and those with a trailing 'Z'.
 *
 * The zone abbreviation is not decoration. Every admin surface this feeds sits alongside evidence
 * that is stated in UTC — cron expressions, container logs, `forecast_batch` rows — so an unlabelled
 * local time leaves the reader silently converting between two clocks. `Intl` derives BST or GMT
 * from the instant itself, so there is no seasonal rule here to get wrong.
 *
 * @param {string|null} utcDateTimeStr - ISO-like datetime string.
 * @returns {string|null} Formatted string, or null for falsy/invalid input.
 */
export function formatTimestampUk(utcDateTimeStr) {
  return formatInstantUk(utcDateTimeStr, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZoneName: 'short',
  });
}

/**
 * Formats a UTC timestamp as a relative time string ("Xm ago", "1h 34m ago", etc.).
 *
 * Handles both bare ISO strings (no suffix) and those with a trailing 'Z'.
 *
 * The hours band keeps its minutes rather than rounding to the nearest hour. Rounding turned a
 * 94-minute-old batch into "2h ago", which is exactly the resolution someone loses when asking
 * whether a batch went out ninety minutes ago or longer — and the intraday cycle's whole latency
 * question lives in that band (afternoon batches have been observed at 98–173 min). Minutes are
 * dropped only when they are genuinely zero, so the common case stays short.
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
  if (diffMin < 1440) {
    const hrs = Math.floor(diffMin / 60);
    const mins = diffMin % 60;
    return mins === 0 ? `${hrs}h ago` : `${hrs}h ${mins}m ago`;
  }
  return `${Math.floor(diffMin / 1440)}d ago`;
}

/**
 * Formats the elapsed time since a start instant as a compact duration ("4m 16s", "1h 34m").
 *
 * For a phase or run that is still in flight, where the recorded duration is necessarily null.
 * Callers re-render on their own poll, so the value advances without a timer of its own.
 *
 * @param {string|null} startedAtUtc - ISO-like datetime string for the start instant.
 * @param {number} [nowMs] - Millisecond epoch to measure against; defaults to now. Injectable so
 *   tests need not manipulate the clock.
 * @returns {string|null} Elapsed duration, or null for falsy/invalid input or a future start.
 */
export function formatElapsedSince(startedAtUtc, nowMs = Date.now()) {
  if (!startedAtUtc) return null;
  const d = new Date(startedAtUtc.endsWith('Z') ? startedAtUtc : startedAtUtc + 'Z');
  if (isNaN(d.getTime())) return null;
  const totalSec = Math.floor((nowMs - d.getTime()) / 1000);
  // A start in the future is clock skew between the browser and the server, not a negative
  // duration. Saying nothing is honest; "-3s elapsed" is not.
  if (totalSec < 0) return null;
  if (totalSec < 60) return `${totalSec}s`;
  const mins = Math.floor(totalSec / 60);
  if (mins < 60) return `${mins}m ${totalSec % 60}s`;
  return `${Math.floor(mins / 60)}h ${mins % 60}m`;
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
  // UK calendar: this looks up `forecastsByDate`, whose keys are backend dates keyed to
  // Europe/London. On the browser's own zone a reader outside the UK looked up the wrong day.
  const todayStr = ukDateStr(now);

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

  return { date: ukDateStrOffset(1, now), eventType: 'SUNRISE' };
}

/**
 * Moon phase emoji map — keyed by backend MoonPhase enum values.
 */
export const MOON_EMOJI = {
  NEW_MOON: '\u{1F311}', WAXING_CRESCENT: '\u{1F312}', FIRST_QUARTER: '\u{1F313}',
  WAXING_GIBBOUS: '\u{1F314}', FULL_MOON: '\u{1F315}', WANING_GIBBOUS: '\u{1F316}',
  LAST_QUARTER: '\u{1F317}', WANING_CRESCENT: '\u{1F318}',
};

/**
 * Human-readable moon phase names — keyed by backend MoonPhase enum values.
 */
export const MOON_PHASE_NAME = {
  NEW_MOON: 'New moon', WAXING_CRESCENT: 'Waxing crescent', FIRST_QUARTER: 'First quarter',
  WAXING_GIBBOUS: 'Waxing gibbous', FULL_MOON: 'Full moon', WANING_GIBBOUS: 'Waning gibbous',
  LAST_QUARTER: 'Last quarter', WANING_CRESCENT: 'Waning crescent',
};

/**
 * Returns colour class and descriptive suffix for a moon indicator.
 *
 * When windowQuality is present (aurora observation window data), the transition
 * timing drives the result. Falls back to illumination-only thresholds when
 * windowQuality is absent (backward compatible with non-aurora contexts).
 *
 * @param {number} illuminationPct - Moon illumination percentage (0–100).
 * @param {string} [windowQuality] - One of DARK_ALL_WINDOW, DARK_THEN_MOONLIT,
 *   MOONLIT_THEN_DARK, MOONLIT_ALL_WINDOW.
 * @param {string} [moonRiseTime] - UTC ISO timestamp of moonrise within the window.
 * @param {string} [moonSetTime] - UTC ISO timestamp of moonset within the window.
 * @returns {{ colourClass: string, suffix: string }}
 */
export function moonIlluminationStyle(illuminationPct, windowQuality, moonRiseTime, moonSetTime) {
  if (windowQuality) {
    switch (windowQuality) {
      case 'DARK_ALL_WINDOW':
        return { colourClass: 'text-green-400/70', suffix: ' — dark all night' };
      case 'DARK_THEN_MOONLIT':
        return {
          colourClass: 'text-amber-400',
          suffix: ` — dark until ${formatEventTimeUk(moonRiseTime) || '??:??'} ↑`,
        };
      case 'MOONLIT_THEN_DARK':
        return {
          colourClass: 'text-green-400/70',
          suffix: ` — clears after ${formatEventTimeUk(moonSetTime) || '??:??'} ↓`,
        };
      case 'MOONLIT_ALL_WINDOW':
        return { colourClass: 'text-red-400', suffix: ' — moon above horizon all night' };
      default:
        break; // unknown windowQuality — fall through to illumination logic
    }
  }

  // Illumination-only fallback
  if (illuminationPct < 20) return { colourClass: 'text-green-400/70', suffix: ' — dark all night' };
  if (illuminationPct < 50) return { colourClass: 'text-plex-text-secondary', suffix: '' };
  if (illuminationPct < 75) return { colourClass: 'text-amber-400', suffix: ' — moon will impact' };
  return { colourClass: 'text-red-400', suffix: ' — moon above horizon all night' };
}

/**
 * Reformats a tide highlight string into a compact count-based label.
 *
 * Examples:
 * - "King Tide at 3 coastal spots" → "3 king tides"
 * - "Spring Tide at 1 coastal spot" → "1 spring tide"
 * - "King Tide, Extra Extra High at 2 coastal spots" → "2 king tide, extra extra high"
 *
 * @param {string} highlight - Raw tide highlight string from the backend.
 * @returns {string} Reformatted label, or the original if it doesn't match.
 */
export function formatTideHighlight(highlight) {
  const countMatch = highlight.match(/at (\d+) coastal/);
  if (!countMatch) return highlight;
  const count = parseInt(countMatch[1], 10);
  const rawLabel = highlight.replace(/ at .+$/, '').toLowerCase();
  const isSimple = !rawLabel.includes(',');
  return count === 1 ? `${count} ${rawLabel}` : `${count} ${rawLabel}${isSimple ? 's' : ''}`;
}
