/**
 * Which Coming up entries start open, and the session memory of the reader's own open and close presses
 * (collapse-and-topic-pips-plan.md §5).
 *
 * <p>Presentation only: the default reads two served facts of an entry view ({@code inNext4Days},
 * {@code startDate}) against the reader's UK civil today. A press is remembered per entry id for the
 * session ({@code sessionStorage}, try/catch: storage can be unavailable, and then the in-memory state
 * still holds the choice) and overrides the default.
 */
import { shiftDateStr } from './mapDates.js';

/** An entry that starts within this many days of today starts open. */
export const OPEN_WITHIN_DAYS = 30;

/** The session key holding {@code {entryId: boolean}}, the reader's own open or close presses. */
export const COMING_UP_OPEN_KEY = 'comingUpEntryOpen';

/**
 * Whether an entry starts open when the reader has not pressed it: tagged "In next 4 days", or starting
 * within {@link OPEN_WITHIN_DAYS} days of {@code todayStr} (a run already under way counts: it started
 * earlier). Later entries start closed.
 *
 * @param {{inNext4Days: ?boolean, startDate: ?string}} view an entry view ({@code buildEntryView})
 * @param {string} todayStr the reader's UK civil today, {@code YYYY-MM-DD}
 * @returns {boolean} true when the entry starts open
 */
export function defaultEntryOpen(view, todayStr) {
  if (view.inNext4Days) return true;
  if (!view.startDate || !todayStr) return true;
  return view.startDate <= shiftDateStr(todayStr, OPEN_WITHIN_DAYS);
}

/**
 * Whether an entry is open: the reader's press if there is one, else the default.
 *
 * @param {{id: string, inNext4Days: ?boolean, startDate: ?string}} view an entry view
 * @param {Object<string, boolean>} overrides the reader's presses, by entry id
 * @param {string} todayStr the reader's UK civil today
 * @returns {boolean} true when the entry is open
 */
export function isEntryOpen(view, overrides, todayStr) {
  const pressed = overrides?.[view.id];
  return typeof pressed === 'boolean' ? pressed : defaultEntryOpen(view, todayStr);
}

/** The reader's stored presses, or an empty object when there are none or storage is unavailable. */
export function readOpenOverrides() {
  try {
    const raw = sessionStorage.getItem(COMING_UP_OPEN_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

/** Stores the reader's presses for the session; silently keeps them in memory only when storage fails. */
export function writeOpenOverrides(overrides) {
  try {
    sessionStorage.setItem(COMING_UP_OPEN_KEY, JSON.stringify(overrides));
  } catch {
    // Storage unavailable: the component state still holds the choice for this session.
  }
}
