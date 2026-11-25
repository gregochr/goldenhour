import { useLayoutEffect, useRef, useState } from 'react';

/**
 * What the collapsed lens measures until the browser says otherwise: the summary button's 36px (screen-spec.md section 4).
 * Only the first collapse can read it, and only for the instant before the real measurement lands.
 */
const COLLAPSED_GUESS = 36;

/**
 * The phone lens that collapses to one line while the reader scrolls (screen-spec.md section 6.6, D6).
 *
 * <p><b>The state machine.</b> {@code enabled} is the phone (and no plan-conflict verdict, which is a warning the reader
 * must not lose behind a button); everything below is inert without it. {@code pastWeek} says the end of This week has
 * scrolled above the viewport. The lens is then <i>collapsed</i>, unless:
 * <ul>
 *   <li>the reader has <i>opened</i> it (a tap on the summary button), which holds until the lens un-sticks (scrolled
 *       back to the top): that RESETS it. A reset never moves focus;</li>
 *   <li>focus is inside the lens body: it never collapses under a focused chip (the collapse waits for the blur);</li>
 *   <li>it is already collapsed: focusing the summary button must not re-open it, so collapse is a latch, not a derived
 *       value.</li>
 * </ul>
 * Expanding is always immediate. The state changes are made while rendering (the adjust-state pattern), never in an
 * effect.
 *
 * <p><b>No jump.</b> The lens's in-flow height changes (full to one line), so a spacer straight after it takes the
 * difference: {@code full - collapsed} while collapsed, so the document is exactly as tall as it was. The full height is
 * cached whenever the lens is at rest (summary hidden) and re-measured by a {@code ResizeObserver} and on window
 * {@code resize} while it is, so a rotation or a reflow while full refreshes it. The spacer is a sibling, outside the
 * element the lens measures. Its height is written BEFORE the measurement is read in the commit that collapses (a forced
 * layout in between would otherwise let scroll anchoring act on a half-finished layout), then corrected by the
 * measurement. {@code useLensReserve} observes the same lens element, so {@code --wf-bar-h} and {@code --wf-lens-h}
 * publish the collapsed height with no help from here, and the This week and Further ahead bars stick right under it.
 *
 * <p><b>The summary button</b> is one persistent element: shown while collapsed or opened, hidden at rest, and kept
 * shown at rest while it holds focus so a reset can never drop focus to {@code <body>}.
 *
 * @param {object} args
 * @param {boolean} args.enabled whether the collapse applies at all (phone, no verdict)
 * @param {boolean} args.stuck whether the lens bar is stuck (scrolled away from the top): un-sticking resets "opened"
 * @param {boolean} args.pastWeek whether the end of This week has scrolled above the viewport
 * @returns {{attachLens: Function, attachSpacer: Function, collapsed: boolean, opened: boolean, summaryShown: boolean,
 *   toggle: Function, bodyFocusProps: object, summaryFocusProps: object}}
 */
export default function useCollapsingLens({ enabled, stuck, pastWeek }) {
  // The lens element arrives through a CALLBACK ref held in state so the measurement effect attaches when the bar mounts
  // (it is mounted conditionally), the same reason `useStuckSentinel` does.
  const [lensNode, setLensNode] = useState(null);
  const [spacerNode, setSpacerNode] = useState(null);
  const fullRef = useRef(0);
  const collapsedRef = useRef(COLLAPSED_GUESS);
  const [opened, setOpened] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [bodyFocus, setBodyFocus] = useState(false);
  const [summaryFocus, setSummaryFocus] = useState(false);

  const wantCollapsed = enabled && pastWeek && !opened;
  if (opened && (!enabled || !stuck)) setOpened(false);
  if (collapsed && !wantCollapsed) setCollapsed(false);
  else if (!collapsed && wantCollapsed && !bodyFocus) setCollapsed(true);

  const summaryShown = enabled && (collapsed || opened || summaryFocus);

  useLayoutEffect(() => {
    if (!spacerNode) return undefined;
    const setSpacer = (px) => { spacerNode.style.setProperty('height', `${px}px`); };
    if (!enabled || !lensNode) {
      setSpacer(0);
      return undefined;
    }
    // Written first, from what is already known, so no layout is forced between the lens shrinking and the spacer.
    setSpacer(collapsed ? Math.max(0, fullRef.current - collapsedRef.current) : 0);
    const measure = () => {
      const height = Math.round(lensNode.getBoundingClientRect().height);
      if (collapsed) {
        collapsedRef.current = height;
        setSpacer(Math.max(0, fullRef.current - height));
      } else if (!summaryShown) {
        fullRef.current = height;
      }
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(lensNode);
    window.addEventListener('resize', measure);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', measure);
    };
  }, [enabled, lensNode, spacerNode, collapsed, summaryShown]);

  return {
    attachLens: setLensNode,
    attachSpacer: setSpacerNode,
    collapsed,
    opened,
    summaryShown,
    // The one button: collapsed it opens the lens in place; expanded it puts the lens back to one line.
    toggle: () => setOpened(collapsed),
    bodyFocusProps: {
      onFocus: () => setBodyFocus(true),
      onBlur: (event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setBodyFocus(false);
      },
    },
    summaryFocusProps: {
      onFocus: () => setSummaryFocus(true),
      onBlur: () => setSummaryFocus(false),
    },
  };
}
