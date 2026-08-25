/**
 * Which of the window-first pane's two doors the reader left open.
 *
 * <h2>Why this is persisted at all</h2>
 *
 * <p>Without it, remounting the pane (a tab switch, a re-render of the shell) collapsed both doors
 * even when the reader had just opened one — a working position lost to incidental React churn
 * rather than to anything the reader did.
 *
 * <h2>sessionStorage, not localStorage</h2>
 *
 * <p>Two reasons. The arm's own doctrine is that it persists two things, the reach lens and the
 * rating floor, "and both are settled preferences": an open door is a working position, not taste,
 * so a fresh session should still start collapsed. And restoring the regional door fires one astro
 * request per visible date during first paint, so the lifetime of the memory is also the scope of
 * consent to that fetch.
 *
 * <h2>⚠️ Whole-value writes, never a read feeding a write</h2>
 *
 * <p>The same rule the reach lens and the rating floor both record: CodeQL models web storage as one
 * store with no notion of keys, so a {@code getItem} feeding a {@code setItem} reads as a conduit
 * carrying every other write in the app — including the auth token — back out to storage.
 */
export const PLAN_DOORS_KEY = 'photocast.planDoors';

/** The door ids this build has. An id outside this set is treated as absent, not as an error. */
const DOOR_IDS = ['regional', 'topics'];

/**
 * The doors that were left open.
 *
 * <p>Fail-soft over five different absences, all of which mean the same thing to the caller — no
 * key, unparseable JSON, a value that is not an object, an id this build no longer offers, and a
 * non-boolean against a known id. A door is open only if its value is exactly {@code true}.
 *
 * @returns {Set<string>} open door ids; empty when nothing usable is stored
 */
export function readStoredDoors() {
  try {
    const raw = sessionStorage.getItem(PLAN_DOORS_KEY);
    if (!raw) return new Set();
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return new Set();
    return new Set(DOOR_IDS.filter((id) => parsed[id] === true));
  } catch {
    return new Set();
  }
}

/**
 * Records which doors are open, as a whole value covering every door this build has.
 *
 * @param {Set<string>} openDoors the currently open door ids
 */
export function writeStoredDoors(openDoors) {
  try {
    const value = {};
    DOOR_IDS.forEach((id) => { value[id] = openDoors.has(id); });
    sessionStorage.setItem(PLAN_DOORS_KEY, JSON.stringify(value));
  } catch {
    // Quota exceeded or private-browsing restriction — the doors still work for this render.
  }
}
