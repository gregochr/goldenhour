/**
 * Which of the window-first pane's two doors the reader left open.
 *
 * <h2>Why this is persisted at all</h2>
 *
 * <p>Both Plan arms are alive at once and the reader flips between them to compare the same night.
 * The current Plan remembers whether its briefing grid is open across such a round trip
 * ({@code DailyBriefing.jsx}'s {@code planGridExpanded}, and its comment says why); this arm forgot
 * on every unmount, so a flip landed on collapsed doors while the arm beside it stayed open. That
 * asymmetry sits precisely on the surface the comparison is about, and it is not a difference either
 * design asked for.
 *
 * <h2>sessionStorage, matching the arm it is compared against</h2>
 *
 * <p>Three reasons, and they agree. The v1 side stores exactly this class of state in
 * {@code sessionStorage} and states the property worth keeping — "a fresh session still starts
 * collapsed". The arm's own doctrine is that it persists two things, the layout flag and the rating
 * floor, "and both are settled preferences": an open door is a working position, not taste. And
 * restoring the regional door fires one astro request per visible date during first paint, so the
 * lifetime of the memory is also the scope of consent to that fetch.
 *
 * <p>It deliberately does <b>not</b> share v1's {@code planGridExpanded} key. That is one boolean
 * for one disclosure; this arm has two independent doors, so a boolean cannot express it — and
 * coupling them would add cross-arm interference to the exact surface being compared.
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
