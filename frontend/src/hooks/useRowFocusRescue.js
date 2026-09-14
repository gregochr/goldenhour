import { useCallback, useLayoutEffect, useRef } from 'react';
import { foreignModalOverPaneOf } from '../utils/mapForeignModal.js';

/**
 * Keeps keyboard focus inside an open list when the row holding it leaves the list with nobody
 * pressing anything — the Map tab's window menu (`WindowControl`) and the callout's every-event
 * strip (`MapCallout`).
 *
 * <h2>Why rows leave on their own</h2>
 *
 * <p>Both lists render the EV list (`utils/mapEvents.buildMapEvents`), which is rebuilt every render
 * against the clock: D-13's filler solar rows leave at UK midnight, and since D-14 the night rows
 * leave at dawn for PRO/ADMIN (the first aurora-status fetch after it) and at UK midnight for LITE.
 * Rows are keyed by id, so a row that held focus unmounts with its node and focus falls to
 * {@code <body>}. The list stays open, but its own key handling is a React {@code onKeyDown} on the
 * control's wrapper, so {@code Escape} and ←/→ stop reaching anything until the reader finds the
 * control again. The drilldown has the same shape and its own recovery (`MapView`'s orphaned-focus
 * effect); these two lists had none.
 *
 * <h2>Edge-triggered, never "whenever focus is on body"</h2>
 *
 * <p>⚠️ {@code <body>} focus is ordinary on these surfaces — a click on text that cannot take focus,
 * a WebKit button click — so a rule keyed on that state would pull focus to the fallback on the next
 * unrelated render. This acts only on the transition a removal causes: the row that last took focus
 * is no longer among the rows AND focus is now on {@code <body>}. The record of that row ends when
 * focus moves to a real element, when the reader presses inside the list (their attention has
 * moved), and when the list closes — so it can fire at most once per focused row, and never takes
 * focus from anything that has it.
 *
 * <p>A blur with no {@code relatedTarget} keeps the record, deliberately: it is either the window
 * losing focus (the browser gives it back to the same row) or the row being removed, which is the
 * case this exists for.
 *
 * <p>Two guards, the drilldown recovery's own: never while a dialog from outside the map pane is
 * over it (taking focus out of the four-day sheet would be worse than losing it), and never for a
 * fallback that is not on screen (panes mount and stay; a hidden tab must not steal focus).
 *
 * @param {object} args
 * @param {boolean} args.active whether the list is open — its rows exist only then
 * @param {string[]} args.rowIds the ids of the rows the list renders, in the form each row's
 *   {@code data-ev-id} carries
 * @param {{current: ?HTMLElement}} args.fallbackRef the control that stays mounted while the list is
 *   open, to receive focus a vanished row leaves behind
 * @returns {{onFocus: Function, onBlur: Function, onPointerDown: Function}} handlers for the element
 *   that contains the rows
 */
export function useRowFocusRescue({ active, rowIds, fallbackRef }) {
  const focusedIdRef = useRef(null);

  const onFocus = useCallback((e) => {
    focusedIdRef.current = e.target?.closest?.('[data-ev-id]')?.getAttribute('data-ev-id') ?? null;
  }, []);
  const onBlur = useCallback((e) => {
    if (e.relatedTarget) focusedIdRef.current = null;
  }, []);
  const onPointerDown = useCallback(() => {
    // A press on a row focuses it straight after this and records it again; a press anywhere else
    // in the list means the reader has moved on from the row we were holding on to.
    focusedIdRef.current = null;
  }, []);

  // No dependency array: `rowIds` is rebuilt every render with the EV list, and the check is three
  // comparisons — cheaper than arguing about identity.
  useLayoutEffect(() => {
    if (!active) {
      focusedIdRef.current = null;
      return;
    }
    const id = focusedIdRef.current;
    if (id == null || rowIds.includes(id)) return;
    focusedIdRef.current = null;
    const focused = document.activeElement;
    if (focused && focused !== document.body) return;
    const fallback = fallbackRef.current;
    if (!fallback?.isConnected || fallback.closest('[hidden]')) return;
    if (foreignModalOverPaneOf(fallback)) return;
    fallback.focus();
  });

  return { onFocus, onBlur, onPointerDown };
}
