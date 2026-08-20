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
 * <h2>The origin can move the default, and that is why it is a parameter rather than a setTier call</h2>
 *
 * <p>Plan §4.8: moving the origin to a region "drops the reach default to the 90 tier". The obvious
 * shape — {@code setOrigin} calling {@link selectTier} — is wrong twice over, and both were review
 * findings. It <b>persists</b>: {@code selectTier} ends in a {@code localStorage} write stamped with
 * today's date, and the origin is deliberately in-memory, so a reload restored the away lens without
 * the away frame and the reader landed at home behind a 1h 30 gate they never chose (with a "today
 * only" pill marking a choice they never made). And it <b>splits the default from the mark</b>: the
 * far mark measures against {@code defaultTier}, so an away page would mark spots against 90 while
 * this bar's own reset button still said "Back to 45 min" — precisely the drift
 * {@code reachLens.js}'s module comment says the derivation exists to make impossible.
 *
 * <p>So the origin supplies the <em>default</em> instead. Nothing is written, the reset button and
 * the readout name the away tier, the far mark measures against the same number the control calls
 * ordinary, and a reload — which returns the origin home — returns the lens with it.
 *
 * <p>One consequence worth stating: a reader who has explicitly chosen a tier today keeps that
 * choice across an origin move. That is the right way round — an explicit choice outranks a default
 * — and it is what makes the move a change of <em>frame</em> rather than a control that reaches over
 * and moves the bar.
 *
 * @param {string}  todayStr ISO `YYYY-MM-DD` in Europe/London — the default's input and the stamp
 *        the stored choice is checked against
 * @param {boolean} [locked] true when the control is inert for this user (LITE)
 * @param {?string} [defaultOverrideId] a tier id that replaces the day-derived default while it is
 *        set — the away origin's 1h 30. Ignored if it names no tier this build has.
 * @returns {{tier: object, tierId: string, defaultTier: object, defaultTierId: string,
 *           weekend: boolean, overridden: boolean, locked: boolean,
 *           selectTier: function, resetToDefault: function}}
 */
export default function useReachLens(todayStr, locked = false, defaultOverrideId = null) {
  // Lazily, and once: a synchronous localStorage read plus a JSON.parse on every render of a
  // provider that re-renders on each poll, focus and health event is the trap the briefing cache
  // hydrate already documents. The day it was made on rides with it so the derivation below can
  // expire it without an effect.
  const [choice, setChoice] = useState(() => {
    const stored = readStoredTierId(todayStr);
    return stored ? { id: stored, day: todayStr } : null;
  });

  // Validated through `tierById` rather than trusted, for the same reason a stored id is: an
  // override naming a tier this build no longer has must fall back to the day's own default rather
  // than leaving `defaultTier` undefined and taking the readout down with it.
  const dayDefaultId = defaultTierIdFor(todayStr);
  const defaultTierId = (defaultOverrideId && tierById(defaultOverrideId))
    ? defaultOverrideId : dayDefaultId;
  const chosenId = choice && choice.day === todayStr ? choice.id : null;
  const activeId = locked ? ANY_TIER_ID : (chosenId || defaultTierId);

  const selectTier = useCallback((tierId) => {
    if (locked || !tierById(tierId)) return;
    setChoice({ id: tierId, day: todayStr });
    writeStoredTierId(tierId, todayStr);
  }, [locked, todayStr]);

  // Resets to whatever the CURRENT default is — the day's, or the origin's while one is set — so
  // the button and the label beside it can never name two different tiers.
  const resetToDefault = useCallback(() => {
    selectTier(defaultTierId);
  }, [selectTier, defaultTierId]);

  return useMemo(() => ({
    tier: tierById(activeId),
    tierId: activeId,
    defaultTier: tierById(defaultTierId),
    defaultTierId,
    weekend: isWeekend(todayStr),
    // Whether the default on show is the origin's rather than the day's. The bar reads it to say
    // which, because "Back to 1h 30min" on a Tuesday is otherwise an unexplained number.
    defaultFromOrigin: defaultTierId !== dayDefaultId,
    // False for a locked control however far Any sits from the default — see the class comment.
    overridden: !locked && activeId !== defaultTierId,
    locked,
    selectTier,
    resetToDefault,
  }), [activeId, defaultTierId, dayDefaultId, todayStr, locked, selectTier, resetToDefault]);
}
