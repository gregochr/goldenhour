/**
 * View-model helpers for the Locations tab (data-tabs-redesign-plan.md §6): who needs attention, a row's status, the
 * words a row and a panel show, and the filters. Pure functions over the locations payload; nothing here is a served
 * verdict, it labels rows for an admin list.
 */
import { LOCATION_TYPE_META } from './locationTypes.js';
import { formatDataDateTime } from './dataDates.js';

/** What a missing value reads as. */
export const NO_VALUE = '—';

/**
 * Consecutive failed scheduled runs after which the server turns a location off. Mirrors
 * {@code LocationFailureService.AUTO_DISABLE_THRESHOLD}; the panel only uses it to say "a third failure turns the
 * location off", so keep the two in step.
 */
export const FAILURE_THRESHOLD = 3;

const ORDINALS = { 1: 'first', 2: 'second', 3: 'third', 4: 'fourth', 5: 'fifth' };

/** The threshold as an ordinal word ("third"). */
export function failureThresholdWord() {
  return ORDINALS[FAILURE_THRESHOLD] ?? `${FAILURE_THRESHOLD}th`;
}

/** Whether the server turned this place off after failed runs (a reason is stored only then). */
export function isAutoDisabled(loc) {
  return !loc.enabled && Boolean(loc.disabledReason);
}

/**
 * Whether a place belongs in the "needs attention" set: it has a failure count above zero, OR carries a disabled
 * reason. The scheduled-cycle auto-disable stores a reason, and a place switched off that way must stay listed even
 * if its counter was reset elsewhere.
 */
export function needsAttention(loc) {
  return (loc.consecutiveFailures ?? 0) > 0 || Boolean(loc.disabledReason);
}

/** "1 failed run" / "3 failed runs". */
export function failedRunsText(count) {
  return `${count} failed ${count === 1 ? 'run' : 'runs'}`;
}

/**
 * A row's status: the tone of its dot, its words, and a stable key.
 * Active (go) · "{n} failed runs" (amber, while enabled) · Disabled (off) · Disabled after failures (coral).
 *
 * @param {object} loc the location
 * @returns {{key: string, tone: 'go'|'amber'|'coral'|'off', label: string}}
 */
export function locationStatus(loc) {
  if (isAutoDisabled(loc)) return { key: 'auto-disabled', tone: 'coral', label: 'Disabled after failures' };
  if (!loc.enabled) return { key: 'disabled', tone: 'off', label: 'Disabled' };
  if ((loc.consecutiveFailures ?? 0) > 0) {
    return { key: 'failing', tone: 'amber', label: failedRunsText(loc.consecutiveFailures) };
  }
  return { key: 'active', tone: 'go', label: 'Active' };
}

/** The type labels in the catalogue's own order ("Landscape, Woodland"); a location with none reads as Landscape. */
export function typeWords(types) {
  const held = Array.isArray(types) ? types : [];
  const words = Object.keys(LOCATION_TYPE_META).filter((t) => held.includes(t)).map((t) => LOCATION_TYPE_META[t].label);
  // A type this build has never heard of reads as its raw name rather than vanishing.
  held.filter((t) => !(t in LOCATION_TYPE_META)).forEach((t) => words.push(t));
  return words.length === 0 ? LOCATION_TYPE_META.LANDSCAPE.label : words.join(', ');
}

/** Whether the location is a seascape (the only type with tide preferences and a tide point). */
export function isSeascape(loc) {
  return Array.isArray(loc.locationType) && loc.locationType.includes('SEASCAPE');
}

/** The light tokens a row draws, with whether each is held: Rise, Set, Day. */
export const LIGHT_TOKENS = [
  { value: 'SUNRISE', label: 'Rise', word: 'Sunrise' },
  { value: 'SUNSET', label: 'Set', word: 'Sunset' },
  { value: 'ALLDAY', label: 'Day', word: 'All day' },
];

/** The tide tokens a row draws: H, M, L. */
export const TIDE_TOKENS = [
  { value: 'HIGH', label: 'H', word: 'High' },
  { value: 'MID', label: 'M', word: 'Mid' },
  { value: 'LOW', label: 'L', word: 'Low' },
];

/** The light as words in the panel ("Sunrise, Sunset"); a location with none stored reads as both, as the editor opens it. */
export function lightWords(types) {
  const held = Array.isArray(types) && types.length > 0 ? types : ['SUNRISE', 'SUNSET'];
  return LIGHT_TOKENS.filter((t) => held.includes(t.value)).map((t) => t.word).join(', ');
}

/** The tide preferences as words ("High, Mid, Low"), or "Not tidal". */
export function tideWords(types) {
  const held = Array.isArray(types) ? types : [];
  const words = TIDE_TOKENS.filter((t) => held.includes(t.value)).map((t) => t.word);
  return words.length === 0 ? 'Not tidal' : words.join(', ');
}

/** The tide tokens of a seascape as a compact "H M L" with the unheld ones dropped, for a card line. */
export function tideTokensText(types) {
  const held = Array.isArray(types) ? types : [];
  return TIDE_TOKENS.filter((t) => held.includes(t.value)).map((t) => t.label).join(' ');
}

/** What the Bortle class means: 1–2 excellent, 3–4 moderate (aurora-friendly), 5–9 light-polluted. */
export function bortleBlurb(bortle) {
  if (bortle == null) return null;
  if (bortle <= 2) return 'Excellent dark sky';
  if (bortle <= 4) return 'Moderate, aurora-friendly';
  return 'Light-polluted';
}

/** The card's second line: "{Type} · Tides H M L · Bortle {n}" (the Tides part only for seascapes). */
export function locationSummary(loc) {
  const parts = [typeWords(loc.locationType)];
  if (isSeascape(loc)) parts.push(`Tides ${tideTokensText(loc.tideType) || NO_VALUE}`);
  parts.push(`Bortle ${loc.bortleClass ?? NO_VALUE}`);
  return parts.join(' · ');
}

/** The accessible name of a row: who, where, what, and the state. */
export function locationRowLabel(loc) {
  return [
    loc.name,
    loc.subRegionName,
    typeWords(loc.locationType),
    loc.mistProne === true ? 'mist-prone' : null,
    locationStatus(loc).label,
  ].filter(Boolean).join(', ');
}

/** The panel's meta line: "{Types} · {Sub-region} · {Status}". */
export function locationMeta(loc) {
  return [typeWords(loc.locationType), loc.subRegionName, locationStatus(loc).label].filter(Boolean).join(' · ');
}

/** Sort accessors for the columns the header and the sort chip offer. */
export const LOCATION_SORT_ACCESSORS = {
  name: (loc) => loc.name,
  type: (loc) => typeWords(loc.locationType),
  bortle: (loc) => loc.bortleClass ?? null,
  status: (loc) => locationStatus(loc).label,
};

/** The 3.dp coordinates the panel shows ("55.390, -1.613"). */
export function coordinatesText(lat, lon) {
  if (lat == null || lon == null) return null;
  return `${Number(lat).toFixed(3)}, ${Number(lon).toFixed(3)}`;
}

/** "Last failed Today, 07:48." or null when the server holds no time. */
export function lastFailedSentence(loc) {
  if (!loc.lastFailureAt) return null;
  return `Last failed ${formatDataDateTime(loc.lastFailureAt)}.`;
}

/** "1 location needs attention" / "3 locations need attention". */
function needText(count) {
  return count === 1 ? '1 location needs attention.' : `${count} locations need attention.`;
}

/**
 * The attention alert's pieces: the headline count and, at most, one failing place and one turned-off place named.
 * "Failing" is an enabled place with failed runs; "turned off" is an auto-disabled one. A place off by hand that still
 * carries a failure count is counted in the headline but named in neither sentence.
 *
 * @param {Array<object>} locations all locations
 * @returns {{count: number, headline: string, failing: {name: string, text: string}|null,
 *   turnedOff: {name: string, text: string}|null}|null} null when nothing needs attention
 */
export function attentionSummary(locations) {
  const needing = locations.filter(needsAttention);
  if (needing.length === 0) return null;
  const byName = (a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
  const failing = needing.filter((l) => l.enabled && (l.consecutiveFailures ?? 0) > 0).sort(byName)[0];
  const turnedOff = needing.filter(isAutoDisabled).sort(byName)[0];
  let turnedOffText = null;
  if (turnedOff) {
    const n = turnedOff.consecutiveFailures ?? 0;
    turnedOffText = n > 0 ? `was turned off after ${failedRunsText(n)}.` : 'was turned off after failed runs.';
  }
  return {
    count: needing.length,
    headline: needText(needing.length),
    failing: failing
      ? {
        name: failing.name,
        text: `has failed ${failing.consecutiveFailures} ${failing.consecutiveFailures === 1 ? 'run' : 'runs'} in a row.`,
      }
      : null,
    turnedOff: turnedOff ? { name: turnedOff.name, text: turnedOffText } : null,
  };
}

/** The status filters: none, needs attention, or disabled. */
export const STATUS_FILTERS = { NONE: 'none', ATTENTION: 'attention', DISABLED: 'disabled' };

/** The "no region filter" value of the region select. */
export const ALL_REGIONS = 'ALL';

/**
 * Whether a location passes the search and the region (the scope the chip counts describe).
 *
 * @param {object} loc the location
 * @param {string} needle the lower-cased, trimmed search text
 * @param {string} region a region id as text, or {@link ALL_REGIONS}
 */
export function inScope(loc, needle, region) {
  if (region !== ALL_REGIONS && String(loc.region?.id) !== region) return false;
  if (needle === '') return true;
  return loc.name.toLowerCase().includes(needle) || (loc.subRegionName ?? '').toLowerCase().includes(needle);
}

/** Whether a location passes a type chip ({@code ''} is the All chip). A location counts under each type it holds. */
export function passesType(loc, type) {
  return type === '' || (Array.isArray(loc.locationType) && loc.locationType.includes(type));
}

/** Whether a location passes the status chip. */
export function passesStatus(loc, status) {
  if (status === STATUS_FILTERS.ATTENTION) return needsAttention(loc);
  if (status === STATUS_FILTERS.DISABLED) return !loc.enabled;
  return true;
}

/**
 * The region id a Locations link carries: {@code #manage/locations?region=3} gives "3"; any other hash gives null.
 *
 * @param {string} hash a {@code location.hash}
 * @returns {string|null}
 */
export function regionFromHash(hash) {
  const [path, query] = hash.replace('#', '').split('?');
  const [area, tab] = path.split('/');
  if (area !== 'manage' || tab !== 'locations' || !query) return null;
  const id = new URLSearchParams(query).get('region');
  return id != null && /^\d+$/.test(id) ? id : null;
}
