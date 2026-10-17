/**
 * The admin-only rewind: the moment the app is rendering AS OF, when that is not now.
 *
 * <p>A photographer who went out for this morning's sunrise wants to screenshot the forecast that
 * sent them there — after the event, when the Plan tab says "this morning has gone" and the Map tab
 * has moved on. A rewind turns the clock back, never the data: every client clock read goes through
 * {@link appNow}, and every GET carries the instant as {@code X-Rewind-To} (see
 * {@code api/axiosClient.js}), which the backend's own clock bean honours for an admin. The page
 * then renders the current forecast as it stood at that moment.
 *
 * <p>Module state, not React state, on purpose: the axios interceptor and the pure date utilities
 * have no component to read a context from. {@code hooks/useRewind.js} subscribes components to it.
 * Memory only — a reload ends a rewind, which is the safe default for a feature whose whole effect
 * is to make the page say something that is no longer true.
 *
 * <p>Only an admin can set one (the Operations tab is the only producer, and the backend ignores the
 * header from anyone else), so a rewound page is always an admin's page.
 */

/** @type {{ to: string, focus: { date: string, eventType: string } | null } | null} */
let rewind = null;
const listeners = new Set();

/**
 * The current rewind, or null when the page is live.
 *
 * @returns {{ to: string, focus: { date: string, eventType: string } | null } | null}
 */
export function getRewind() {
  return rewind;
}

/**
 * The instant the app is rendering as of, as an ISO-8601 UTC string, or null when live.
 *
 * @returns {string|null}
 */
export function getRewindTo() {
  return rewind?.to ?? null;
}

/**
 * Sets or clears the rewind and notifies subscribers.
 *
 * @param {string|null} to - an ISO-8601 UTC instant, or null to return to live
 * @param {{ date: string, eventType: string } | null} [focus] - the solar window the rewind was
 *        chosen for, so the Map tab can open on it rather than on its own default
 */
export function setRewind(to, focus = null) {
  if (to != null && Number.isNaN(new Date(to).getTime())) {
    throw new Error(`setRewind: not an instant: ${to}`);
  }
  rewind = to == null ? null : { to, focus: focus ?? null };
  listeners.forEach((listener) => listener());
}

/**
 * Subscribes to rewind changes. Shaped for {@code useSyncExternalStore}.
 *
 * @param {() => void} listener
 * @returns {() => void} unsubscribe
 */
export function subscribeRewind(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * The app's "now": the rewound instant while a rewind is set, else the wall clock. Every client
 * decision that reads the time — has this window passed, what is today, what is the next event,
 * how old is this forecast — reads it here, so one rewind moves them all together.
 *
 * @returns {Date}
 */
export function appNow() {
  return rewind ? new Date(rewind.to) : new Date();
}
