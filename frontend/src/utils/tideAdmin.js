/**
 * View-model helpers for Manage → Places → Tides (data-tabs-redesign-plan.md §7): joining the bulk tide statistics to
 * the locations list, the thin-data test, the median reading count, and the number formats the tab shows.
 */

/** A location with fewer tide readings than this is "thin": its percentiles are less reliable. */
export const THIN_TIDE_READINGS = 2000;

/** What a missing number reads as. */
export const NO_VALUE = '—';

/**
 * A metres figure to a fixed number of decimals, or an em dash when there is none.
 *
 * @param {number|string|null|undefined} val the value
 * @param {number} [dp=2] decimal places
 * @returns {string}
 */
export function fmtMetres(val, dp = 2) {
  return val == null || val === '' || Number.isNaN(Number(val)) ? NO_VALUE : Number(val).toFixed(dp);
}

/**
 * A decimal proportion (0 to 1) as a percentage with one decimal, or an em dash.
 *
 * @param {number|null|undefined} val the proportion
 * @returns {string}
 */
export function fmtPercent(val) {
  return val == null || val === '' || Number.isNaN(Number(val)) ? NO_VALUE : `${(Number(val) * 100).toFixed(1)}%`;
}

/** A number, or null for a missing or non-numeric value (a sort accessor must give null, never NaN). */
export function numOrNull(val) {
  if (val == null || val === '') return null;
  const n = Number(val);
  return Number.isNaN(n) ? null : n;
}

/**
 * Whether a row's reading count is thin: a number, below {@link THIN_TIDE_READINGS}. A missing count is unknown,
 * not thin.
 *
 * @param {{stats: {dataPoints: number|null}}} row
 * @returns {boolean}
 */
export function isThin(row) {
  const n = numOrNull(row.stats.dataPoints);
  return n != null && n < THIN_TIDE_READINGS;
}

/** The fewest other locations with a reading count for the "against about" comparison to mean anything. */
export const MIN_NEIGHBOURS = 3;

/** A count with en-GB thousands separators ("5,000"), or an em dash when there is none. */
export function fmtCount(val) {
  const n = numOrNull(val);
  return n == null ? NO_VALUE : n.toLocaleString('en-GB');
}

/**
 * The median reading count across the OTHER rows that have one (rounded), or null when fewer than
 * {@link MIN_NEIGHBOURS} do. Pass {@code exclude} so a location is not compared with itself.
 *
 * @param {Array<{stats: {dataPoints: number|null}}>} rows
 * @param {object} [exclude] a row to leave out
 * @returns {number|null}
 */
export function medianReadings(rows, exclude = undefined) {
  const counts = rows.filter((r) => r !== exclude).map((r) => numOrNull(r.stats.dataPoints))
    .filter((n) => n != null).sort((a, b) => a - b);
  if (counts.length < MIN_NEIGHBOURS) return null;
  const mid = Math.floor(counts.length / 2);
  return Math.round(counts.length % 2 === 1 ? counts[mid] : (counts[mid - 1] + counts[mid]) / 2);
}

/** A signed metres figure for a bar's end labels ("−2.89 m", "+2.87 m"), or an em dash. */
export function signedMetres(val) {
  const n = numOrNull(val);
  if (n == null) return NO_VALUE;
  return `${n < 0 ? '−' : '+'}${Math.abs(n).toFixed(2)} m`;
}

/** The tide types a location is worth shooting at, as words ("High, Mid, Low"); an em dash when none. */
export function tideTypesText(types) {
  if (!Array.isArray(types) || types.length === 0) return NO_VALUE;
  const words = { HIGH: 'High', MID: 'Mid', LOW: 'Low' };
  return types.map((t) => words[t] ?? t).join(', ');
}

/**
 * Joins the bulk statistics ({@code {locationName: TideStats}}) to the locations by name. A statistics entry with no
 * matching location still gets a row, with {@code locationId: null} (its row opens nothing).
 *
 * @param {Object<string, object>} statsByName the bulk stats
 * @param {Array<object>} locations GET /api/locations (carries {@code subRegionName})
 * @returns {Array<{id: string|number, name: string, stats: object, locationId: number|null, region: string|null,
 *   subRegion: string|null, tideTypes: string[], enabled: boolean|null}>}
 */
export function buildTideRows(statsByName, locations) {
  const byName = new Map((locations ?? []).map((l) => [l.name, l]));
  return Object.entries(statsByName ?? {}).map(([name, stats]) => {
    const loc = byName.get(name);
    return {
      id: loc ? loc.id : `stats:${name}`,
      name,
      stats: stats ?? {},
      locationId: loc ? loc.id : null,
      region: loc?.region?.name ?? null,
      subRegion: loc?.subRegionName ?? null,
      tideTypes: Array.isArray(loc?.tideType) ? loc.tideType : [],
      enabled: loc ? loc.enabled !== false : null,
    };
  });
}

/** The sort accessors for the tab's columns; a missing number is null, which sorts last either way. */
export const TIDE_SORT_ACCESSORS = {
  name: (r) => r.name,
  avgRange: (r) => numOrNull(r.stats.avgRangeMetres),
  p95High: (r) => numOrNull(r.stats.p95HighMetres),
  spring: (r) => numOrNull(r.stats.springTideFrequency),
  dataPoints: (r) => numOrNull(r.stats.dataPoints),
};
