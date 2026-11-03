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

/** The display types the {@code type} key can name (the Cost isolate and the Timing type), as the server serves them. */
export const RUN_TYPES = ['OVERNIGHT', 'AFTERNOON', 'EVENING', 'ASK_READY', 'BRIEFING', 'SKY_EVAL', 'OTHER'];

/** The Timing measures: start to the end of the Ask ready answers that follow (the default), or the run's own length. */
export const TIMING_MEASURES = ['cycle', 'ready'];

/** The default measure for a cycle type: the number that matters for getting results out before the light. */
export const DEFAULT_MEASURE = 'ready';

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
  // {@code range} is the Cost and Timing views' own key now (a number of days); only the old word form
  // ({@code today}, {@code week}, {@code month}) is legacy.
  const legacyRange = query.has('range') && !/^\d+$/.test(query.get('range') ?? '');
  const legacy = legacyRange || ['kind', 'attention'].some((key) => query.has(key));
  if (!legacy) return;
  const attention = ['true', '1'].includes((query.get('attention') ?? '').toLowerCase());
  const filter = attention ? 'attention' : legacyKindFilter(query.get('kind'));
  [...(legacyRange ? ['range'] : []), 'kind', 'attention'].forEach((key) => query.delete(key));
  if (filter && !query.has('filter')) query.set('filter', filter);
  replaceHash(path, query);
}

/**
 * Reads the Runs query: the view (the hash's, else the remembered one, else Day), the Day's date (an ISO date not
 * after today and not malformed, else null meaning "today") and the filter ({@code all} when unknown). Legacy
 * parameters are normalised first.
 *
 * @param {Date} [now] the real clock
 * <p>The Cost and Timing views' keys ride beside them (DD-9): {@code range} (days, validated by the view that reads
 * it), {@code type} (a display type, or null) and {@code measure} ({@code ready} unless {@code cycle}).
 *
 * @returns {{view: string, date: string|null, filter: string, range: number|null, type: string|null,
 *   measure: string}}
 */
export function readRunsQuery(now = new Date()) {
  normalizeRunsQuery();
  const { query } = splitHash();
  const view = query.get('view');
  const date = validDate(query.get('date'));
  const filter = (query.get('filter') ?? '').toLowerCase();
  const range = /^\d+$/.test(query.get('range') ?? '') ? Number(query.get('range')) : null;
  const type = (query.get('type') ?? '').toUpperCase();
  const measure = (query.get('measure') ?? '').toLowerCase();
  // A date that is today or later means "today": it is not written, so it is rewritten out of the address.
  if (date && date >= ukDateStr(now)) writeRunsQuery({ date: null });
  return {
    view: RUNS_VIEWS.includes(view) ? view : (storedView() ?? 'day'),
    // A future date is clamped to today before it is ever asked for (F12); an earlier-than-earliest one is the
    // Day hook's to clamp, once the server has said where the history starts.
    date: date && date < ukDateStr(now) ? date : null,
    filter: DAY_FILTERS.includes(filter) ? filter : 'all',
    range,
    type: RUN_TYPES.includes(type) ? type : null,
    measure: TIMING_MEASURES.includes(measure) ? measure : DEFAULT_MEASURE,
  };
}

/**
 * Merges changes into the Runs query and writes it with {@code replaceState}. A {@code null} or empty value removes
 * its key; {@code filter: 'all'} and {@code measure: 'ready'} are the defaults and are removed; {@code view} is
 * remembered in localStorage.
 *
 * @param {{view?: string, date?: string|null, filter?: string, range?: number|string|null, type?: string|null,
 *   measure?: string}} patch the keys to set (or, null, remove)
 */
export function writeRunsQuery(patch) {
  const { path, query } = splitHash();
  Object.entries(patch).forEach(([key, value]) => {
    const isDefault = (key === 'filter' && value === 'all') || (key === 'measure' && value === DEFAULT_MEASURE);
    if (value == null || value === '' || isDefault) query.delete(key);
    else query.set(key, String(value));
  });
  if (patch.view) rememberView(patch.view);
  replaceHash(path, query);
}

/**
 * The address of the Runs tab's Timing view for a run type, as a plain href (D14 of the scheduler redesign). Not
 * {@link writeRunsQuery}, which rewrites the <em>current</em> path: this is a link from another screen.
 *
 * @param {string} [type] a display type from {@link RUN_TYPES}; the afternoon (intraday) refresh by default
 * @returns {string} {@code #manage/runs?view=timing&type=AFTERNOON}
 */
export function runsTimingHref(type = 'AFTERNOON') {
  const query = new URLSearchParams({ view: 'timing' });
  if (RUN_TYPES.includes(type)) query.set('type', type);
  return `#manage/runs?${query.toString()}`;
}

/**
 * The address of the Runs tab's Day view for a date, as a plain href (D14 of the scheduler redesign), the link from
 * the Scheduler's Day picture. Runs reads a date that is today or later as "today" and rewrites it out of the address.
 *
 * @param {string} date - an ISO date ({@code YYYY-MM-DD}); anything else links to the Day view's own default day
 * @returns {string} {@code #manage/runs?view=day&date=2026-11-01}
 */
export function runsDayHref(date) {
  const query = new URLSearchParams({ view: 'day' });
  if (validDate(date)) query.set('date', date);
  return `#manage/runs?${query.toString()}`;
}
