import { formatDayClockUk, formatEventTimeUk } from './conversions.js';
import { ukDateStr } from './mapDates.js';
import { appNow } from './rewind.js';

/**
 * Met Office weather warnings, display helpers (weather-warnings-plan.md §2, §12, §13).
 *
 * <p>Everything here is formatting and ordering of what the server already decided. The server owns which warning
 * is in force, which places it covers and how warnings sort; the one derivation kept (the fallback for a window with
 * no stored forecast row, {@link warningsForPlaceAt}) is the one the W1b reference doc names as the client's door:
 * a place with no row reads {@code locationIds} from {@code GET /api/warnings}. A warning never changes a rating, so
 * nothing in this file reads or writes one.
 */

/**
 * The attribution line the licence requires beside warning text; used only until the server's own
 * {@code attribution} has arrived.
 */
export const DEFAULT_ATTRIBUTION = 'Met Office Weather Warnings © Crown Copyright 2026';

/** The Met Office warnings overview, used only until the server's own {@code metOfficeUrl} has arrived. */
export const DEFAULT_MET_OFFICE_URL = 'https://www.metoffice.gov.uk/public/weather/warnings/';

/**
 * The official level colours and the ink the style guide (v1.4) pairs with each. Alternatives need the Met Office's
 * agreement, so these are not tuned. The classes are written out in full so Tailwind sees them.
 */
export const WARNING_LEVELS = {
  RED: { word: 'Red', rank: 3, hex: '#ca0a37', ink: '#ffffff', classes: 'bg-[#ca0a37] text-white' },
  AMBER: { word: 'Amber', rank: 2, hex: '#ff9900', ink: '#000000', classes: 'bg-[#ff9900] text-black' },
  YELLOW: { word: 'Yellow', rank: 1, hex: '#ffe923', ink: '#000000', classes: 'bg-[#ffe923] text-black' },
};

/** Neutral classes for a level this client does not know: its word is still printed, never a guessed colour. */
export const UNKNOWN_LEVEL_CLASSES = 'bg-plex-surface-light text-plex-text border border-plex-border-light';

/**
 * The served level as the word the chip prints. An unknown level is printed as the server sent it, sentence case;
 * an absent one is null (no chip).
 *
 * @param {?string} level the served level, upper case
 * @returns {?string} "Red", "Amber", "Yellow", the sentence-case unknown word, or null
 */
export function levelWord(level) {
  if (typeof level !== 'string' || level.trim() === '') return null;
  const known = WARNING_LEVELS[level.trim().toUpperCase()];
  if (known) return known.word;
  const text = level.trim().toLowerCase().replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * The served weather types as lower-case words, in the order received (the style guide's order). Unknown types are
 * rendered from the served value, never dropped.
 *
 * @param {?Array<string>} types the served {@code weatherTypes}
 * @returns {string[]} words such as {@code ['wind', 'extreme heat']}
 */
export function typeWords(types) {
  if (!Array.isArray(types)) return [];
  return types
    .filter((t) => typeof t === 'string' && t.trim() !== '')
    .map((t) => t.trim().toLowerCase().replace(/[_-]+/g, ' '));
}

/**
 * Joins words as prose: "a", "a and b", "a, b and c".
 *
 * @param {string[]} words
 * @returns {string}
 */
export function joinWords(words) {
  if (words.length <= 1) return words.join('');
  return `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`;
}

/**
 * The chip's printed phrase: "Yellow warning of wind". No type means "Yellow warning". Null when the warning has no
 * level (nothing to print).
 *
 * @param {?object} warning a served {@code WarningDto}
 * @returns {?string}
 */
export function warningLabel(warning) {
  const word = levelWord(warning?.level);
  if (word == null) return null;
  const types = typeWords(warning.weatherTypes);
  return types.length > 0 ? `${word} warning of ${joinWords(types)}` : `${word} warning`;
}

/**
 * The chip's accessible name: its label plus ", updated" when the server flags a re-issued warning.
 *
 * @param {?object} warning a served {@code WarningDto}
 * @returns {?string}
 */
export function warningName(warning) {
  const label = warningLabel(warning);
  if (label == null) return null;
  return warning.updated === true ? `${label}, updated` : label;
}

/** Rank of a level, 0 for an unknown one. */
function levelRank(level) {
  return WARNING_LEVELS[String(level ?? '').toUpperCase()]?.rank ?? 0;
}

/** Descending on a possibly-missing number, a missing one last. */
function byNumberDesc(a, b) {
  const x = Number.isFinite(a) ? a : -Infinity;
  const y = Number.isFinite(b) ? b : -Infinity;
  if (x === y) return 0;
  return x > y ? -1 : 1;
}

/** Ascending on a possibly-missing instant, a missing one last. */
function byInstantAsc(a, b) {
  const x = Date.parse(a);
  const y = Date.parse(b);
  const xv = Number.isNaN(x) ? Infinity : x;
  const yv = Number.isNaN(y) ? Infinity : y;
  if (xv === yv) return 0;
  return xv < yv ? -1 : 1;
}

/**
 * The style guide's overlap order: level (Red, Amber, Yellow), then impact, then likelihood (highest first), then
 * {@code validFrom} (earliest first), then id so the order is total. The server serves warnings in this order; a row
 * names its warnings by id, so the chips are put back in it here.
 *
 * @param {object} a a served {@code WarningDto}
 * @param {object} b another
 * @returns {number} a comparator result
 */
export function compareWarnings(a, b) {
  const level = levelRank(b?.level) - levelRank(a?.level);
  if (level !== 0) return level;
  const impact = byNumberDesc(a?.impact, b?.impact);
  if (impact !== 0) return impact;
  const likelihood = byNumberDesc(a?.likelihood, b?.likelihood);
  if (likelihood !== 0) return likelihood;
  const from = byInstantAsc(a?.validFrom, b?.validFrom);
  if (from !== 0) return from;
  return String(a?.warningId ?? '').localeCompare(String(b?.warningId ?? ''));
}

/**
 * The warnings in the style guide's order. Returns a new array.
 *
 * @param {?Array<object>} warnings served warnings
 * @returns {object[]}
 */
export function orderWarnings(warnings) {
  return (Array.isArray(warnings) ? warnings.filter(Boolean) : []).slice().sort(compareWarnings);
}

/** A served instant (bare UTC or Z-suffixed) as epoch milliseconds, or NaN. */
function instantMs(value) {
  if (typeof value !== 'string' || value === '') return Number.NaN;
  return Date.parse(/(Z|[+-]\d\d:?\d\d)$/.test(value) ? value : `${value}Z`);
}

/**
 * The warnings covering a place at an instant, read from {@code GET /api/warnings} ({@code locationIds} and the
 * validity span). Only for a window the server could not name warnings for because the place has no stored forecast
 * row for it (W1b: "a place with no stored row for a window has nothing to carry the id").
 *
 * @param {?Array<object>} warnings served warnings
 * @param {*} locationId the place's id
 * @param {?string} eventIso the window's event instant as served
 * @returns {object[]} in the style guide's order
 */
export function warningsForPlaceAt(warnings, locationId, eventIso) {
  const at = instantMs(eventIso);
  if (locationId == null || Number.isNaN(at) || !Array.isArray(warnings)) return [];
  return orderWarnings(warnings.filter((w) => {
    const ids = Array.isArray(w?.locationIds) ? w.locationIds : [];
    return ids.some((id) => Number(id) === Number(locationId))
      && instantMs(w.validFrom) <= at
      && at <= instantMs(w.validTo)
      && warningLabel(w) != null;
  }));
}

/**
 * The warnings to draw for one window of one place.
 *
 * <p>Always matched against the polled {@code GET /api/warnings} list ({@code locationIds} the server computed, and
 * the validity span), never against the stored forecast row's {@code warningIds}: that row only refreshes when
 * {@code /api/forecast} is fetched again, which can be hours, while the warnings list is polled every two minutes
 * (the licence's ten-minute bound for new, changed and cancelled warnings). This stays a filter over served facts.
 * The instant is the stored row's own {@code solarEventTime} when a row exists, else the slot or score instant.
 * Stood-down, unscored and travel-day windows are matched the same way: a warning is not a rating.
 *
 * @param {{warnings: object[]}} served the shared warnings (the context value)
 * @param {{row: ?object, locationId: *, eventIso: ?string}} window the window's stored forecast row (or null), the
 *        place's id and the fallback event instant as served
 * @returns {object[]} in the style guide's order
 */
export function warningsForWindow(served, { row, locationId, eventIso }) {
  if (!served || !Array.isArray(served.warnings) || served.warnings.length === 0) return [];
  return warningsForPlaceAt(served.warnings, locationId, row?.solarEventTime ?? eventIso);
}

/**
 * The stale-feed sentence (plan §8): "Warnings last checked 14:05" or, when no check has succeeded, the plain
 * admission. The caller appends the "check the Met Office" link.
 *
 * @param {?string} lastCheckedAt the served instant, or null
 * @returns {string}
 */
export function staleLead(lastCheckedAt) {
  if (!lastCheckedAt) return 'Warnings have not been checked yet';
  const checked = new Date(lastCheckedAt);
  if (Number.isNaN(checked.getTime())) return 'Warnings have not been checked yet';
  // Today's clock alone is unambiguous; an earlier day needs its day named, or "14:05" reads as today.
  const text = ukDateStr(checked) === ukDateStr(appNow())
    ? formatEventTimeUk(lastCheckedAt)
    : formatDayClockUk(lastCheckedAt);
  return `Warnings last checked ${text}`;
}
