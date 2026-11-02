import { ukDateStr } from './mapDates.js';

/**
 * The Runs tab's address, in one place (runs-deep-dive-plan.md DD-9 and F4). The view, the Day view's day and its
 * filter chip ride the hash's query ({@code #manage/runs?view=day&date=2026-11-01&filter=cycles}); the path
 * ({@code /cycle/12}, {@code /job/34}, {@code /start}) is {@code useHashSelection}'s and is never touched here.
 *
 * <p>Every write is a {@code replaceState}: a view, a day or a chip is not a place to go Back to, and no write fires a
 * {@code hashchange}. {@code date} is written only when it is not today. The pre-deep-dive inbound query
 * ({@code ?range=&kind=&attention=}) is mapped to a Day filter once ({@link normalizeRunsQuery}) and rewritten out.
 *
 * <p>⚠️ Every clock read here is the real clock ({@code new Date()}), never {@code appNow()} (R-D7).
 */

/** The views, in tab order; the first is the default. */
export const RUNS_VIEWS = ['day', 'cost', 'timing'];

/** The Day filter chips' keys as the hash writes them (lower case); {@code all} is the default and is never written. */
export const DAY_FILTERS = ['all', 'cycles', 'ask_ready', 'briefing', 'sky_eval', 'other', 'attention'];

/** The one thing localStorage remembers: the last view. */
const VIEW_STORAGE_KEY = 'photocast.runs.view';

/** The hash's path and query, without the leading {@code #}. */
function splitHash() {
  const [path, ...query] = window.location.hash.replace('#', '').split('?');
  return { path, query: new URLSearchParams(query.join('?')) };
}

/** Rewrites the current history entry's hash (no new entry, no hashchange). */
function replaceHash(path, query) {
  const text = query.toString();
  window.history.replaceState(window.history.state, '', `#${path}${text === '' ? '' : `?${text}`}`);
}

/** The remembered view, or null (storage unavailable or empty). */
function storedView() {
  try {
    const value = window.localStorage.getItem(VIEW_STORAGE_KEY);
    return RUNS_VIEWS.includes(value) ? value : null;
  } catch {
    return null;
  }
}

/** Remembers the view; a failure (private window, blocked storage) is silent. */
function rememberView(view) {
  try {
    window.localStorage.setItem(VIEW_STORAGE_KEY, view);
  } catch {
    // Not remembered: the next visit opens the Day view.
  }
}

/** A hash date that is an ISO date, else null. */
function validDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value ?? '') && !Number.isNaN(Date.parse(`${value}T12:00:00Z`)) ? value : null;
}

/**
 * The legacy {@code kind} (the old list's chips) as a Day filter: CYCLES and BRIEFING keep their names, every other
 * kind (BATCHES, TIDE, AURORA, OTHER) is Other.
 */
function legacyKindFilter(kind) {
  const upper = (kind ?? '').toUpperCase();
  if (upper === 'CYCLES') return 'cycles';
  if (upper === 'BRIEFING') return 'briefing';
  if (upper === 'ALL' || upper === '') return null;
  return 'other';
}

/**
 * Maps a legacy {@code ?range=&kind=&attention=} query onto the Day filter and rewrites it out of the address, once.
 * {@code attention=true} wins over a kind; {@code range} is dropped. Does nothing for an address with none of them.
 */
export function normalizeRunsQuery() {
  const { path, query } = splitHash();
  const legacy = ['range', 'kind', 'attention'].some((key) => query.has(key));
  if (!legacy) return;
  const attention = ['true', '1'].includes((query.get('attention') ?? '').toLowerCase());
  const filter = attention ? 'attention' : legacyKindFilter(query.get('kind'));
  ['range', 'kind', 'attention'].forEach((key) => query.delete(key));
  if (filter && !query.has('filter')) query.set('filter', filter);
  replaceHash(path, query);
}

/**
 * Reads the Runs query: the view (the hash's, else the remembered one, else Day), the Day's date (an ISO date not
 * after today and not malformed, else null meaning "today") and the filter ({@code all} when unknown). Legacy
 * parameters are normalised first.
 *
 * @param {Date} [now] the real clock
 * @returns {{view: string, date: string|null, filter: string}}
 */
export function readRunsQuery(now = new Date()) {
  normalizeRunsQuery();
  const { query } = splitHash();
  const view = query.get('view');
  const date = validDate(query.get('date'));
  const filter = (query.get('filter') ?? '').toLowerCase();
  return {
    view: RUNS_VIEWS.includes(view) ? view : (storedView() ?? 'day'),
    // A future date is clamped to today before it is ever asked for (F12); an earlier-than-earliest one is the
    // Day hook's to clamp, once the server has said where the history starts.
    date: date && date < ukDateStr(now) ? date : null,
    filter: DAY_FILTERS.includes(filter) ? filter : 'all',
  };
}

/**
 * Merges changes into the Runs query and writes it with {@code replaceState}. A {@code null} or empty value removes
 * its key; {@code filter: 'all'} is the default and is removed; {@code view} is remembered in localStorage.
 *
 * @param {{view?: string, date?: string|null, filter?: string}} patch the keys to set (or, null, remove)
 */
export function writeRunsQuery(patch) {
  const { path, query } = splitHash();
  Object.entries(patch).forEach(([key, value]) => {
    if (value == null || value === '' || (key === 'filter' && value === 'all')) query.delete(key);
    else query.set(key, value);
  });
  if (patch.view) rememberView(patch.view);
  replaceHash(path, query);
}

/**
 * The Day filter chip the Scheduler's "All in Runs" link opens, from a job's run group (F16).
 *
 * @param {string|null|undefined} runGroup the job's {@code runGroup} (CYCLES, BATCHES, TIDE, BRIEFING, AURORA, OTHER)
 * @returns {string} the filter key
 */
export function dayFilterForGroup(runGroup) {
  return legacyKindFilter(runGroup) ?? 'all';
}
