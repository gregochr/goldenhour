import { useLayoutEffect, useState } from 'react';

/** The columns that dock beside the shell: Ask's, and the guide's in the same slot. */
const DOCK_SELECTOR = '.wf-ask-dock, .wf-guide-dock';

/**
 * How far from the viewport's right edge the Ask dock (or the docked guide) begins, in px, so the map view can end
 * at the dock column's edge and leave it beside the view and interactive (`docs/engineering/one-screen-plan.md`
 * D12). Zero while nothing is docked or `active` is false.
 *
 * <p>Measured, not assumed: the dock sits in a centred row, so it is not flush with the viewport on a wide screen,
 * and it animates in from zero width (a ResizeObserver on the dock reports every frame). It re-measures on window
 * resize and when the dock node is replaced (the lazy chunk swaps a fallback for the real aside).
 *
 * @param {{current: ?Element}} rootRef the shell root the dock lives in
 * @param {boolean} active whether anything needs the answer (the view is open and a dock is shown)
 * @returns {number} the dock's distance from the right edge, 0 when none
 */
export default function useDockEdge(rootRef, active) {
  const [edge, setEdge] = useState(0);
  // A layout effect that measures at once: a reopen must not paint one frame at the edge a previous dock left.
  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!active || !root) return undefined;
    let observer = null;
    let watched = null;
    const measure = () => {
      const dock = root.querySelector(DOCK_SELECTOR);
      if (dock !== watched) {
        observer?.disconnect();
        watched = dock;
        if (dock) observer?.observe(dock);
      }
      if (!dock) {
        setEdge(0);
        return;
      }
      const rect = dock.getBoundingClientRect();
      const width = document.documentElement.clientWidth;
      setEdge(rect.width > 0 ? Math.max(0, Math.round(width - rect.left)) : 0);
    };
    observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    measure();
    window.addEventListener('resize', measure);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', measure);
      // Forgotten while inactive, so the next activation never starts from this one's answer.
      setEdge(0);
    };
  }, [rootRef, active]);
  return active ? edge : 0;
}
