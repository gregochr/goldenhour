import { useCallback, useMemo, useState } from 'react';
import {
  ANY_RATING_ID,
  lensRatingFloorById,
  readStoredRatingFloorId,
  writeStoredRatingFloorId,
} from '../utils/ratingLens.js';

/**
 * The Plan tab's rating floor: the lowest star still worth hearing about.
 *
 * <h2>Almost everything {@code useReachLens} does, this deliberately does not</h2>
 *
 * <p>The two sit side by side on one bar and it would be easy to read them as the same kind of
 * setting. They are not, and every difference is on purpose:
 *
 * <ul>
 *   <li><b>No day stamp and no expiry.</b> "How far will I drive tonight" is a judgement about one
 *       evening, so reach is held with the day it was made on and stops matching at the roll.
 *       "I only care about 4★ and up" is taste — re-asking it every morning would be the same
 *       nagging in the other direction.</li>
 *   <li><b>No derived default.</b> There is no weekday/weekend answer to a taste question, so the
 *       default is simply "Any rating", and a reader who has never touched the control sees exactly
 *       the page they saw before it existed.</li>
 *   <li><b>No "today only" pill and no reset button.</b> Reach needs both because its choice
 *       silently expires and a statement with no route back is a shape this project has already had
 *       to fix once. Nothing here expires, and the {@code Any rating} chip <em>is</em> the reset,
 *       on screen whenever the floor is.</li>
 *   <li><b>No lock.</b> Plan §7 makes reach a PRO control; the floor needs no per-user data and
 *       withholds nothing a role could not already see, so CLAUDE.md's "breadcrumbs not paywalls"
 *       leaves it open to everyone — the same call {@code WindowSpotSheet} already made for it.</li>
 * </ul>
 *
 * <p>Read lazily and once, for the reason {@code useReachLens} records: this hook lives in a
 * provider that re-renders on every poll, focus and health event, and a synchronous
 * {@code localStorage} read plus a {@code JSON.parse} on each of those is the trap the briefing
 * cache hydrate already documents.
 *
 * @returns {{floor: object, floorId: string, minRating: ?number, selectFloor: function}}
 */
export default function useRatingLens() {
  const [floorId, setFloorId] = useState(() => readStoredRatingFloorId() ?? ANY_RATING_ID);

  const selectFloor = useCallback((id) => {
    // Validated against what the BAR offers, not the whole vocabulary. The drill-down's 5★ is a
    // legitimate floor there and one this control cannot draw a pressed chip for, so storing it
    // would leave the page filtered by a control that looks untouched. The read side rejects it too,
    // because an older build could have written one.
    if (!lensRatingFloorById(id)) return;
    setFloorId(id);
    writeStoredRatingFloorId(id);
  }, []);

  return useMemo(() => {
    const floor = lensRatingFloorById(floorId);
    return { floor, floorId, minRating: floor?.min ?? null, selectFloor };
  }, [floorId, selectFloor]);
}
