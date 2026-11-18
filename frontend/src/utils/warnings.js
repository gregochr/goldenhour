import { formatDayClockUk, formatEventTimeUk } from './conversions.js';
import { ukDateStr } from './mapDates.js';
import { appNow } from './rewind.js';
import { warningInputsFor } from './locationSheet.js';

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
 * The Tailwind classes that colour a warning's text surface: the official level colours, or the neutral outline for a
 * level this client does not know. One place, so the chip, the lane and the card marker cannot drift.
 *
 * @param {?string} level the served level
 * @returns {string}
 */
export function levelClasses(level) {
  return WARNING_LEVELS[String(level).toUpperCase()]?.classes ?? UNKNOWN_LEVEL_CLASSES;
}

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
export function instantMs(value) {
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
 * The warnings to draw for the Map tab's active window as a whole (W4): every polled warning that covers at least one
 * place of the roster at the window's event instant, in the style guide's order. Same rule as the callout and the
 * Plan card ({@link warningsForPlaceAt}: the polled list's {@code locationIds} and validity span decide, never a stored
 * row's ids), applied across the places, so a reader's own filters (star floor, types, reach) never make the chip
 * flicker: a warning is not a rating. Solar windows only (the server keys a warning to a sunrise or sunset), and not a
 * window already gone. Each place is matched exactly as the callout matches it ({@link warningInputsFor}: its own
 * stored row's {@code solarEventTime}, else its own briefing slot's instant, else the instant of its own score row), so
 * the chip and the callout can never disagree about a place; a place with none of those is not matched.
 *
 * @param {{warnings: object[]}} served the shared warnings (the context value)
 * @param {{event: ?object, locations: ?Array<object>, slotIndex: ?object, scoreIndex: ?object}} scope the map's
 *        active EV row, the roster and the callout's two lookup indexes
 * @returns {object[]} in the style guide's order
 */
export function warningsForMapWindow(served, {
  event, locations, slotIndex = null, scoreIndex = null,
}) {
  if (!served || !Array.isArray(served.warnings) || served.warnings.length === 0) return [];
  if (event?.kind !== 'solar' || event.past || !event.date || !event.eventType) return [];
  if (!Array.isArray(locations) || locations.length === 0) return [];
  const found = new Map();
  for (const loc of locations) {
    const inputs = warningInputsFor(loc, event, slotIndex, scoreIndex);
    for (const w of warningsForWindow(served, { ...inputs, locationId: loc?.id })) found.set(w.warningId, w);
  }
  return orderWarnings(Array.from(found.values()));
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

/**
 * The warnings to draw on one Plan window card, matched against the polled {@code GET /api/warnings} list (the W2
 * rule: never trusted from a stored row, so a withdrawn or changed warning changes at the next poll, not at the next
 * briefing fetch).
 *
 * <p>A warning belongs to the card when it is in force at the window's event instant and either the server named it
 * on the window ({@code card.warningIds}, W1b) or it covers one of the window's own places ({@code card.placeIds}, or
 * the ids of {@code card.allSpots}, against the warning's {@code locationIds}). The second door is what lets a warning that arrived after the
 * briefing was built reach its window inside the licence's ten minutes. The polled list still decides whether the
 * warning is in force, its level and its text. Nothing here reads or writes a rating.
 *
 * @param {{warnings: object[]}} served the shared warnings (the context value)
 * @param {?object} card a window card from {@code buildWindowCards}
 * @returns {object[]} in the style guide's order
 */
export function warningsForCard(served, card) {
  if (!served || !Array.isArray(served.warnings) || served.warnings.length === 0 || !card || card.away) return [];
  const at = instantMs(card.eventIso);
  const named = new Set(Array.isArray(card.warningIds) ? card.warningIds : []);
  const placeIds = Array.isArray(card.placeIds)
    ? card.placeIds
    : (Array.isArray(card.allSpots) ? card.allSpots : []).map((spot) => spot?.locationId);
  const ids = new Set(placeIds.filter((id) => id != null).map(Number));
  return orderWarnings(served.warnings.filter((w) => {
    if (warningLabel(w) == null) return false;
    if (Number.isNaN(at)) return named.has(w.warningId);
    if (!(instantMs(w.validFrom) <= at && at <= instantMs(w.validTo))) return false;
    const covered = Array.isArray(w.locationIds) && w.locationIds.some((id) => ids.has(Number(id)));
    return named.has(w.warningId) || covered;
  }));
}

/**
 * The At a Glance warning lanes: one lane per run of consecutive live matrix columns in which a warning applies to a
 * window, most severe warning first (the style guide's order, so a Red takes the top row before span is considered).
 * Presentation only: which warning applies to which card is {@code warningsOfCard}'s answer.
 *
 * @param {Array<{date: string, am: ?object, pm: ?object}>} days the matrix days, one per grid column
 * @param {function(object): object[]} warningsOfCard the warnings of one window card (away cards answer none)
 * @returns {Array<{key: string, warning: object, startCol: number, endCol: number, row: number, dates: string[],
 *          firstCardKey: string}>} in DOM order (row, then start column)
 */
export function buildWarningLanes(days, warningsOfCard) {
  const byId = new Map();
  days.forEach((day, index) => {
    [day.am, day.pm].forEach((card) => {
      if (!card || card.away) return;
      warningsOfCard(card).forEach((warning) => {
        if (!byId.has(warning.warningId)) byId.set(warning.warningId, { warning, columns: new Map() });
        const entry = byId.get(warning.warningId);
        // The newest copy wins within one poll; a column keeps the first card (sunrise before sunset) it applies to.
        if (!entry.columns.has(index)) entry.columns.set(index, card.key);
      });
    });
  });
  const lanes = [];
  const rows = [];
  orderWarnings([...byId.values()].map((entry) => entry.warning)).forEach((warning) => {
    const { columns } = byId.get(warning.warningId);
    const sorted = [...columns.keys()].sort((a, b) => a - b);
    const runs = [];
    sorted.forEach((index) => {
      const last = runs[runs.length - 1];
      if (last && last[last.length - 1] === index - 1) last.push(index);
      else runs.push([index]);
    });
    runs.forEach((run) => {
      const startCol = run[0] + 1;
      const endCol = run[run.length - 1] + 1;
      // Never above a more severe warning it shares a column with: warnings are placed most severe first, so every
      // lane already placed that overlaps this run is at least as severe, and the search starts below the lowest of them.
      let floor = 0;
      rows.forEach((taken, index) => {
        for (let c = startCol; c <= endCol; c += 1) if (taken.has(c)) floor = Math.max(floor, index + 1);
      });
      let row = -1;
      for (let index = floor; index < rows.length && row < 0; index += 1) {
        let free = true;
        for (let c = startCol; c <= endCol; c += 1) if (rows[index].has(c)) free = false;
        if (free) row = index;
      }
      if (row < 0) {
        while (rows.length <= floor) rows.push(new Set());
        row = floor;
      }
      for (let c = startCol; c <= endCol; c += 1) rows[row].add(c);
      lanes.push({
        key: `${warning.warningId}@${days[run[0]].date}`,
        warning,
        startCol,
        endCol,
        row: row + 1,
        dates: run.map((index) => days[index].date),
        firstCardKey: columns.get(run[0]),
      });
    });
  });
  return lanes.sort((a, b) => a.row - b.row || a.startCol - b.startCol);
}

/**
 * The Met Office impact matrix (style guide v1.4, page 9; plan §12.1), read from the PDF and verified against it:
 * a 4 by 4 grid, likelihood 4 at the top to 1 at the bottom, impact 1 on the left to 4 on the right. The words are
 * the guide's colour names; the served {@code warningLevel} always wins, the grid only draws a cell.
 */
export const MATRIX_GRID = [
  ['green', 'yellow', 'amber', 'red'], // likelihood 4
  ['green', 'yellow', 'amber', 'amber'], // likelihood 3
  ['green', 'green', 'yellow', 'amber'], // likelihood 2
  ['green', 'green', 'yellow', 'yellow'], // likelihood 1
];

/** The matrix palette: the level colours plus the guide's matrix-only green. */
export const MATRIX_COLOURS = {
  green: '#c4e90c',
  yellow: WARNING_LEVELS.YELLOW.hex,
  amber: WARNING_LEVELS.AMBER.hex,
  red: WARNING_LEVELS.RED.hex,
};

/** A served likelihood or impact when it is an integer from 1 to 4, else null (no graphic, never a guess). */
export function matrixValue(value) {
  return Number.isInteger(value) && value >= 1 && value <= 4 ? value : null;
}

/**
 * The grid's colour name at a cell.
 *
 * @param {number} likelihood 1 to 4
 * @param {number} impact 1 to 4
 * @returns {?string} 'green', 'yellow', 'amber' or 'red'; null outside the grid
 */
export function matrixCell(likelihood, impact) {
  const l = matrixValue(likelihood);
  const i = matrixValue(impact);
  return l == null || i == null ? null : MATRIX_GRID[4 - l][i - 1];
}

/**
 * The text alternative for the matrix graphic: "Likelihood 3 of 4, impact 2 of 4". Never a word such as medium.
 *
 * @returns {?string} the sentence, or null when either value is absent or out of range
 */
export function matrixText(likelihood, impact) {
  const l = matrixValue(likelihood);
  const i = matrixValue(impact);
  return l == null || i == null ? null : `Likelihood ${l} of 4, impact ${i} of 4`;
}

/**
 * The served per-region counts for one warning on one window, as printed lines: "Northumberland: 4 of 12 places in
 * this area". Counts are non-personal (every enabled place in the region), never the reader's reach.
 *
 * @param {?Array<{warningId: string, regionName: string, covered: number, total: number}>} counts the window's
 *        served {@code warningPlaceCounts}
 * @param {string} warningId the warning
 * @returns {Array<{region: string, text: string}>}
 */
export function placeCountLines(counts, warningId) {
  return (Array.isArray(counts) ? counts : [])
    .filter((c) => c?.warningId === warningId && Number.isFinite(c.covered) && Number.isFinite(c.total) && c.covered > 0)
    .map((c) => ({ region: c.regionName, text: `${c.covered} of ${c.total} places in this area` }));
}

/**
 * The warning's validity as one UK-time sentence: "Valid Fri 14 Aug, 14:05 to Sat 15 Aug, 09:00 (UK time)".
 *
 * @param {object} warning a served {@code WarningDto}
 * @returns {?string} null when either instant is missing or unreadable
 */
export function validityText(warning) {
  if (!warning || Number.isNaN(instantMs(warning.validFrom)) || Number.isNaN(instantMs(warning.validTo))) return null;
  return `Valid ${formatDayClockUk(warning.validFrom)} to ${formatDayClockUk(warning.validTo)} (UK time)`;
}

/**
 * The text marker on a Plan window card: the most severe warning's level word and first weather type, and a
 * count of the others, never a glyph. {@code name} carries every warning's full phrase.
 *
 * @param {object[]} warnings the card's warnings in the style guide's order
 * @returns {?{text: string, count: ?string, name: string, level: string}} text printed, the "+N" count, the full phrase, top level
 */
export function cardMarker(warnings) {
  if (!Array.isArray(warnings) || warnings.length === 0) return null;
  const top = warnings[0];
  const word = levelWord(top.level);
  if (word == null) return null;
  const type = typeWords(top.weatherTypes)[0];
  const extra = warnings.length - 1;
  return {
    text: type ? `${word} ${type}` : `${word} warning`,
    count: extra > 0 ? `+${extra}` : null,
    name: warnings.map(warningName).filter(Boolean).join('; '),
    level: top.level,
  };
}
