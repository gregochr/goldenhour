import { useCallback, useMemo, useState } from 'react';
import {
  ANY_TIER_ID,
  defaultTierIdFor,
  isWeekend,
  readStoredTierId,
  tierById,
  writeStoredTierId,
} from '../utils/reachLens.js';

/**
 * The Plan tab's reach lens: which tier is active, and the two facts the bar renders beside it.
 *
 * <h2>The day roll needs no effect, and that is deliberate</h2>
 *
 * <p>Plan §5: "Day-derived default; reach expires at the day roll." The obvious shape — an effect
 * watching {@code todayStr} that resets the state at midnight — has a window in which the stored
 * choice is still live, and it writes state during a render pass that already has the right answer.
 * So the choice is held <em>with the day it was made on</em> and the active tier is derived:
 * a stamp that is no longer today simply stops matching, and the default takes over on the next
 * render with nothing to schedule and nothing to tear down. The provider re-renders on every poll,
 * so a browser left open overnight rolls over within ten minutes without a timer of its own.
 *
 * <p>Nothing is written back when a stamp expires. An expired choice that re-persisted itself on
 * read would be a "today only" setting that quietly became permanent.
 *
 * <h2>LITE is pinned to Any, and the lens then gates nothing</h2>
 *
 * <p>Plan §7 makes the bar a PRO control taking CLAUDE.md's LITE treatment — {@code opacity: 0.45},
 * {@code pointer-events: none}, a "Pro" pill. That settles the <em>chrome</em> and leaves the
 * question this hook has to answer: what does the gate do for a user who cannot move it?
 *
 * <p>It does nothing. CLAUDE.md's freemium rule is "breadcrumbs not paywalls", and a tier a LITE
 * user cannot widen would withhold forecast content rather than a feature — on a weekday, every spot
 * over 45 minutes, with no route to it. Pinning to Any keeps every spot on screen and makes the
 * greyed control describe its own true state, so the "Pro" pill offers the ability to <em>narrow</em>
 * rather than standing in front of something removed. It also keeps the override signals honest:
 * "today only" and the reset button mark a choice the user made, and a LITE user has made none, so
 * {@code overridden} is false however far Any sits from today's default.
 *
 * @param {string}  todayStr ISO `YYYY-MM-DD` in Europe/London — the default's input and the stamp
 *        the stored choice is checked against
 * @param {boolean} [locked] true when the control is inert for this user (LITE)
 * @returns {{tier: object, tierId: string, defaultTier: object, defaultTierId: string,
 *           weekend: boolean, overridden: boolean, locked: boolean,
 *           selectTier: function, resetToDefault: function}}
 */
export default function useReachLens(todayStr, locked = false) {
  // Lazily, and once: a synchronous localStorage read plus a JSON.parse on every render of a
  // provider that re-renders on each poll, focus and health event is the trap the briefing cache
  // hydrate already documents. The day it was made on rides with it so the derivation below can
  // expire it without an effect.
  const [choice, setChoice] = useState(() => {
    const stored = readStoredTierId(todayStr);
    return stored ? { id: stored, day: todayStr } : null;
  });

  const defaultTierId = defaultTierIdFor(todayStr);
  const chosenId = choice && choice.day === todayStr ? choice.id : null;
  const activeId = locked ? ANY_TIER_ID : (chosenId || defaultTierId);

  const selectTier = useCallback((tierId) => {
    if (locked || !tierById(tierId)) return;
    setChoice({ id: tierId, day: todayStr });
    writeStoredTierId(tierId, todayStr);
  }, [locked, todayStr]);

  const resetToDefault = useCallback(() => {
    selectTier(defaultTierIdFor(todayStr));
  }, [selectTier, todayStr]);

  return useMemo(() => ({
    tier: tierById(activeId),
    tierId: activeId,
    defaultTier: tierById(defaultTierId),
    defaultTierId,
    weekend: isWeekend(todayStr),
    // False for a locked control however far Any sits from the default — see the class comment.
    overridden: !locked && activeId !== defaultTierId,
    locked,
    selectTier,
    resetToDefault,
  }), [activeId, defaultTierId, todayStr, locked, selectTier, resetToDefault]);
}
