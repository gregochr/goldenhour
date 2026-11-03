/**
 * The Scheduler tab's address, in one place (scheduler-redesign-plan.md D9), following {@code runsQuery.js}. The tab
 * and the Day picture's date ride the hash's query ({@code #manage/scheduler?view=all&date=2026-11-03}); the path
 * ({@code /{jobKey}}) is {@code useHashSelection}'s and is never touched here.
 *
 * <p>Every write is a {@code replaceState}: a tab or a date is not a place to go Back to, and no write fires a
 * {@code hashchange}. The hash wins over the remembered tab. {@code date} is the Day's jobs tab's day (written only
 * when it is not today); the All jobs tab preserves it and never reads it.
 */

/** The tabs, in tab order. */
export const SCHEDULER_VIEWS = ['day', 'all'];

/** The tab opened when neither the hash nor storage names one. */
export const DEFAULT_SCHEDULER_VIEW = 'day';

/** The one thing localStorage remembers: the last tab. */
const VIEW_STORAGE_KEY = 'photocast.scheduler.view';

/** The hash's path and query, without the leading {@code #}. */
function splitHash() {
  const [path, ...query] = window.location.hash.replace('#', '').split('?');
  return { path, query: new URLSearchParams(query.join('?')) };
}

/** Whether the address is the Scheduler tab's (the bare tab or a job under it). */
export function isSchedulerHash() {
  const [area, tab] = splitHash().path.split('/');
  return area === 'manage' && tab === 'scheduler';
}

/** The remembered tab, or null (storage unavailable or empty). */
function storedView() {
  try {
    const value = window.localStorage.getItem(VIEW_STORAGE_KEY);
    return SCHEDULER_VIEWS.includes(value) ? value : null;
  } catch {
    return null;
  }
}

/** Remembers the tab; a failure (private window, blocked storage) is silent. */
function rememberView(view) {
  try {
    window.localStorage.setItem(VIEW_STORAGE_KEY, view);
  } catch {
    // Not remembered: the next visit opens the default tab.
  }
}

/** A hash date that is an ISO date, else null. */
function validDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value ?? '')) return null;
  const parsed = new Date(`${value}T12:00:00Z`);
  // Round-trip: Date rolls 2026-02-31 over to March, so a date that does not come back unchanged is not a date.
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value ? value : null;
}

/**
 * Reads the Scheduler query: the tab (the hash's, else the remembered one, else the default) and the date (an ISO
 * date, else null).
 *
 * @returns {{view: string, date: string|null}}
 */
export function readSchedulerQuery() {
  const { query } = splitHash();
  const view = query.get('view');
  return {
    view: SCHEDULER_VIEWS.includes(view) ? view : (storedView() ?? DEFAULT_SCHEDULER_VIEW),
    date: validDate(query.get('date')),
  };
}

/**
 * Merges changes into the Scheduler query and writes it with {@code replaceState}. A {@code null} or empty value
 * removes its key; {@code view} is remembered in localStorage.
 *
 * @param {{view?: string, date?: string|null}} patch the keys to set (or, null, remove)
 */
export function writeSchedulerQuery(patch) {
  const { path, query } = splitHash();
  Object.entries(patch).forEach(([key, value]) => {
    if (value == null || value === '') query.delete(key);
    else query.set(key, String(value));
  });
  if (patch.view) rememberView(patch.view);
  const text = query.toString();
  window.history.replaceState(window.history.state, '', `#${path}${text === '' ? '' : `?${text}`}`);
}

/**
 * Removes a {@code date} that is in the address but is not a date (so it reads as "today" and the address says so).
 * A valid date, or none, is left alone.
 */
export function dropInvalidDate() {
  const { query } = splitHash();
  if (query.has('date') && validDate(query.get('date')) == null) writeSchedulerQuery({ date: null });
}
