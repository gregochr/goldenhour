import { useCallback, useEffect, useState } from 'react';
import computePopoverPlacement from '../utils/popoverPlacement.js';

/**
 * State and dismissal for a single shared popover panel.
 *
 * <h2>One panel, held above every trigger</h2>
 *
 * <p>The whole point is that there is exactly <b>one</b> popover in the subtree, owned here rather
 * than one per trigger. That is the lifetime guarantee: a panel rendered inside the chip that opened
 * it dies the moment that chip re-renders, and on this Plan screen a chip re-renders whenever the
 * briefing refreshes underneath it. {@code CloseToHome} already learned this — it keeps one peek
 * node at the panel root, driven from panel-level state — and this hook generalises the shape.
 *
 * <p>{@code position: fixed} is a <em>separate</em> concern and solves a different problem: escaping
 * a scroller's {@code overflow} clipping. It is not sufficient on its own, because a
 * {@code transform}, {@code filter} or {@code will-change} on any ancestor silently re-bases fixed
 * positioning onto that ancestor — which is why the companion {@code PopoverHost} portals to
 * {@code document.body} rather than merely positioning in place.
 *
 * <h2>Dismissal is document-level, and scroll is the one that matters</h2>
 *
 * <p>Placement is computed once from the anchor's viewport rect. Nothing recomputes it, so the
 * instant anything scrolls the panel is describing a position the anchor has left — it hangs in
 * empty space pointing at nothing. Following the anchor would mean measuring on every scroll frame
 * for a panel that exists for a second; dismissing is both cheaper and what a reader expects. The
 * scroll listener is registered in the <b>capture</b> phase because scroll does not bubble, and the
 * scroller here is an inner element rather than the document.
 *
 * <p>Escape closes it too. The shipped region-chip gloss has no keyboard dismissal at all, so a
 * keyboard user who opens one by focusing a chip can only close it by moving focus again.
 *
 * @returns {{popover: ?object, show: function, hide: function, hideAll: function}}
 *          {@code show(key, anchorEl, content, opts)} opens the panel for a trigger;
 *          {@code hide(key)} closes it <em>only</em> if that trigger still owns it;
 *          {@code hideAll()} closes unconditionally.
 */
export default function usePopoverHost() {
  const [popover, setPopover] = useState(null);

  const show = useCallback((key, anchorEl, content, opts) => {
    if (!anchorEl) return;
    const { style, alignRight } = computePopoverPlacement(anchorEl.getBoundingClientRect(), opts);
    setPopover({ key, content, style, alignRight });
  }, []);

  /**
   * Closes the panel only when {@code key} is the trigger currently showing.
   *
   * <p>A pointer sweeping along a row of chips fires the outgoing chip's leave AFTER the incoming
   * chip's enter. An unkeyed close would let the chip the reader has just left cancel the panel for
   * the chip they are now on, so the popover flickers off exactly when it is wanted.
   */
  const hide = useCallback((key) => {
    setPopover((current) => (current && current.key === key ? null : current));
  }, []);

  const hideAll = useCallback(() => setPopover(null), []);

  useEffect(() => {
    if (!popover) return undefined;
    const onKeyDown = (e) => {
      if (e.key === 'Escape') hideAll();
    };
    // Capture: scroll does not bubble, and the scroller is an inner element, not the document.
    window.addEventListener('scroll', hideAll, true);
    window.addEventListener('resize', hideAll);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('scroll', hideAll, true);
      window.removeEventListener('resize', hideAll);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [popover, hideAll]);

  return { popover, show, hide, hideAll };
}
