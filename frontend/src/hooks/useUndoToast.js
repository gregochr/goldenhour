import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * How long an undo toast stays up. WCAG 2.2.1 (timing adjustable) and the plan (find-and-favourites-plan.md F4) put
 * the floor at ten seconds: a reader who needs longer than that to reach Undo must be able to hold it open, which
 * pausing on hover and on focus does.
 */
export const UNDO_TOAST_MS = 10000;

/**
 * The state behind {@code UndoToast}: one toast at a time, with an Undo action, a timer that pauses while the
 * reader's pointer or focus is on the toast, and a restart of the full time on resume.
 *
 * <p>A new toast replaces the one showing (a change made while the last is still up is the newer fact). The caller
 * owns what Undo does; this owns only how long the toast lives.
 *
 * @param {object} [options]
 * @param {number} [options.duration] ms before it dismisses itself; never below {@link UNDO_TOAST_MS}
 * @returns {{toast: ?{id: number, message: string, onUndo: ?function, returnTo: ?(Element|function)},
 *   show: function({message: string, onUndo?: function, returnTo?: ?(Element|function)}): void, dismiss: function(): void,
 *   pause: function(): void, resume: function(): void}}
 */
export default function useUndoToast({ duration = UNDO_TOAST_MS } = {}) {
  const [toast, setToast] = useState(null);
  const [paused, setPaused] = useState(false);
  const seq = useRef(0);
  const ms = Math.max(duration, UNDO_TOAST_MS);

  const show = useCallback(({ message, onUndo = null, returnTo = undefined }) => {
    seq.current += 1;
    // Where the press came from, so focus can go back there when the toast's own button is pressed and unmounts. A
    // caller whose toast lands later (after a request) passes what had focus at PRESS time; otherwise it is read now.
    const origin = returnTo === undefined ? document.activeElement : returnTo;
    setPaused(false);
    setToast({
      id: seq.current, message, onUndo, returnTo: origin === document.body ? null : origin,
    });
  }, []);
  const dismiss = useCallback(() => setToast(null), []);
  const pause = useCallback(() => setPaused(true), []);
  const resume = useCallback(() => setPaused(false), []);

  useEffect(() => {
    if (!toast || paused) return undefined;
    const timer = setTimeout(() => setToast(null), ms);
    return () => clearTimeout(timer);
  }, [toast, paused, ms]);

  return { toast, show, dismiss, pause, resume };
}
