import { useLayoutEffect } from 'react';

/**
 * Keeps a sticky dock's bottom at the bottom of the viewport.
 *
 * <p>The dock is {@code position: sticky; top: 0; height: 100dvh} (plan §2.6), which is right once the
 * page has scrolled past what sits above the shell — but BEFORE that its top is a banner and `<main>`'s
 * padding down the page, so a 100dvh box would hang that far below the fold and take its input row with
 * it, on the very first paint. A sticky element's own top is {@code max(natural top, 0)}, which is
 * exactly the amount to take off its height; it does not depend on the height, so there is no loop.
 * The figure is published as {@code --wf-dock-top} (the stylesheet subtracts it) and is written
 * imperatively, the way {@code useLensReserve} writes its own, rather than through state: it changes
 * on every scroll frame until the dock sticks and touches no React tree.
 *
 * <p>Shared by Ask's dock and the guide's docked column (both sit in the shell's docked row).
 *
 * @param {{current: ?HTMLElement}} ref the dock
 * @param {boolean} enabled false on the Map tab, where the dock is the frame's own height
 */
export default function useStickyViewportFill(ref, enabled) {
  useLayoutEffect(() => {
    const node = ref.current;
    if (!enabled || !node) return undefined;
    let frame = 0;
    const measure = () => {
      frame = 0;
      const top = Math.max(0, Math.round(node.getBoundingClientRect().top));
      node.style.setProperty('--wf-dock-top', `${top}px`);
    };
    const schedule = () => {
      if (frame === 0) frame = requestAnimationFrame(measure);
    };
    measure();
    window.addEventListener('scroll', schedule, { passive: true });
    window.addEventListener('resize', schedule);
    // A banner arriving above the shell moves the dock's natural top without a scroll or a resize.
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    observer?.observe(document.body);
    return () => {
      if (frame !== 0) cancelAnimationFrame(frame);
      window.removeEventListener('scroll', schedule);
      window.removeEventListener('resize', schedule);
      observer?.disconnect();
      node.style.removeProperty('--wf-dock-top');
    };
  }, [ref, enabled]);
}
