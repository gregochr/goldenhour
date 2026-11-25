import { useEffect, useLayoutEffect, useRef, useState } from 'react';

/**
 * What the collapsed lens measures until the browser says otherwise: the summary button's 36px (screen-spec.md section 4).
 * Only the first collapse can read it, and only for the instant before the real measurement lands.
 */
const COLLAPSED_GUESS = 36;
/** The full lens before it has been measured (a phone's two chip rows and a count line). */
const FULL_GUESS = 94;
/**
 * How far BELOW the lens's bottom edge the "past This week" line sits, in CSS px. The Further ahead jump parks that
 * section's heading about 2px under the lens, so the end of This week is then only a pixel or two below the lens: the line
 * is a little below the edge so that landing counts as past, which a line at the viewport top never did.
 */
const LINE_SLACK = 16;
/**
 * Hysteresis, in CSS px: it collapses once the end of This week is this far PAST the line, and expands only once it is back
 * on the near side of the line. Without it a one-pixel difference between the full and the collapsed lens, or a rounding at
 * a fractional device scale, can flip the state on every frame.
 */
const HYSTERESIS = 8;

/**
 * Whether a focus event is KEYBOARD focus. Chrome on Android focuses a button on a tap and a touch scroll never blurs it, so
 * "focus is in the lens" must mean keyboard focus or a chip tap would hold the lens open for good. Where
 * {@code :focus-visible} cannot be asked (jsdom), focus is assumed to be the keyboard's, the safe direction.
 */
function isKeyboardFocus(element) {
  try {
    return element.matches(':focus-visible');
  } catch {
    return true;
  }
}

/**
 * The phone lens that collapses to one line while the reader scrolls (screen-spec.md section 6.6, D6).
 *
 * <p><b>The state machine.</b> {@code enabled} is the phone (and no plan-conflict verdict, which is a warning the reader
 * must not lose behind a button); everything below is inert without it. The lens is <i>collapsed</i> when the This week
 * section has scrolled past the lens (or the reader has pressed the summary button while the lens was expanded: <i>forced</i>),
 * unless:
 * <ul>
 *   <li>the reader has <i>opened</i> it (a tap on the summary), which holds until the lens un-sticks (scrolled back to the
 *       top): that RESETS it. A reset never moves focus;</li>
 *   <li>KEYBOARD focus is inside the lens body: it never collapses under a focused chip (the collapse waits for the blur).
 *       A pointer focus does not count (see {@link isKeyboardFocus}); when the lens collapses with one in the body, focus
 *       moves to the summary button in the same commit, so it never sits in a hidden element or falls to {@code <body>};</li>
 *   <li>it is already collapsed: focusing the summary button must not re-open it, so collapse is a latch, not a derived
 *       value.</li>
 * </ul>
 * Expanding is always immediate. The state changes are made while rendering (the adjust-state pattern), never in an effect
 * body. The summary button is never a dead control: collapsed it opens; expanded, it collapses, wherever the reader is.
 *
 * <p><b>Past This week</b> is observed here (an IntersectionObserver on {@code weekNode}, the This week section) against a
 * line just below the lens's FULL bottom edge, with {@link HYSTERESIS}. The section, not a 1px marker at its end: an
 * observer reports only a change of "intersecting", so a marker below the fold is never reported when one frame jumps over
 * it (a fling, the Further ahead jump), where the tall section flips state on every jump from the top. The line is the full
 * height, never the collapsed one, so the lens shrinking cannot move it.
 *
 * <p><b>No jump.</b> A spacer straight after the lens takes the difference: {@code full - collapsed} px while collapsed. While
 * <i>opened</i> the lens is taller than at rest (the summary row joins it), so the spacer pulls the content back up by that
 * much with a negative margin and opening adds no document height. The full height is the UNROUNDED height cached while the
 * lens is at rest (summary hidden), re-measured by a {@code ResizeObserver} and on {@code resize}. A width change while
 * collapsed or opened invalidates it: the lens expands for one commit to be measured at rest, then collapses again. The
 * spacer is a sibling, outside what the lens measures, and its height is written BEFORE the measurement is read (a forced
 * layout in between would let scroll anchoring act on a half-finished layout). {@code useLensReserve} observes the same lens
 * element, so {@code --wf-bar-h}, {@code --wf-lens-h} and {@code --wf-lens-reserve} publish the collapsed height with no
 * help from here.
 *
 * @param {object} args
 * @param {boolean} args.enabled whether the collapse applies at all (phone, no verdict)
 * @param {boolean} args.stuck whether the lens bar is stuck (scrolled away from the top): un-sticking resets "opened"
 * @param {?Element} args.weekNode the This week section, or null before it mounts
 * @returns {{attachLens: Function, attachSpacer: Function, attachSummary: Function, collapsed: boolean, opened: boolean,
 *   summaryShown: boolean, toggle: Function, bodyFocusProps: object, summaryFocusProps: object}}
 */
export default function useCollapsingLens({ enabled, stuck, weekNode }) {
  // The nodes arrive through CALLBACK refs held in state so the effects attach when they mount (the bar is mounted
  // conditionally), the same reason `useStuckSentinel` does.
  const [lensNode, setLensNode] = useState(null);
  const [spacerNode, setSpacerNode] = useState(null);
  const [summaryNode, setSummaryNode] = useState(null);
  const fullRef = useRef(0);
  const collapsedRef = useRef(COLLAPSED_GUESS);
  const widthRef = useRef(0);
  const [fullHeight, setFullHeight] = useState(FULL_GUESS);
  const [pastWeek, setPastWeek] = useState(false);
  const [opened, setOpened] = useState(false);
  const [forced, setForced] = useState(false);
  const [stale, setStale] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [bodyFocus, setBodyFocus] = useState(false);
  const [summaryFocus, setSummaryFocus] = useState(false);
  const [prevStuck, setPrevStuck] = useState(stuck);

  const wantCollapsed = enabled && (pastWeek || forced) && !opened && !stale;
  if (prevStuck !== stuck) {
    setPrevStuck(stuck);
    // Back at the top: a forced collapse ends with the scroll that outlived it.
    if (prevStuck && !stuck && forced) setForced(false);
  }
  if (opened && (!enabled || !stuck)) setOpened(false);
  if (collapsed && !wantCollapsed) setCollapsed(false);
  else if (!collapsed && wantCollapsed && !bodyFocus) setCollapsed(true);

  const summaryShown = enabled && (collapsed || opened || summaryFocus);

  // Is This week past the lens? Rebuilt when the state flips so the line moves by the hysteresis.
  useEffect(() => {
    if (!enabled || !weekNode || typeof IntersectionObserver === 'undefined') return undefined;
    const line = Math.round(fullHeight) + LINE_SLACK;
    const margin = pastWeek ? line : line - HYSTERESIS;
    const observer = new IntersectionObserver(
      ([entry]) => setPastWeek(
        !entry.isIntersecting && (entry.boundingClientRect?.bottom ?? Infinity) <= (entry.rootBounds?.top ?? margin),
      ),
      { threshold: 0, rootMargin: `-${margin}px 0px 0px 0px` },
    );
    observer.observe(weekNode);
    return () => observer.disconnect();
  }, [enabled, weekNode, fullHeight, pastWeek]);

  // Focus never sits in a hidden element or falls to <body>: a pointer focus left in the body moves to the summary.
  useLayoutEffect(() => {
    if (!collapsed || !lensNode || !summaryNode) return;
    const active = document.activeElement;
    if (active && active !== summaryNode && lensNode.contains(active)) summaryNode.focus({ preventScroll: true });
  }, [collapsed, lensNode, summaryNode]);

  useLayoutEffect(() => {
    if (!spacerNode) return undefined;
    const setSpacer = (height, pull) => {
      spacerNode.style.setProperty('height', `${height}px`);
      spacerNode.style.setProperty('margin-top', `${-pull}px`);
    };
    if (!enabled || !lensNode) {
      setSpacer(0, 0);
      return undefined;
    }
    // Written first, from what is already known, so no layout is forced between the lens changing and the spacer.
    if (collapsed) setSpacer(Math.max(0, fullRef.current - collapsedRef.current), 0);
    else if (opened) setSpacer(0, collapsedRef.current);
    else setSpacer(0, 0);
    const measure = (fromEvent) => {
      const height = lensNode.getBoundingClientRect().height;
      if (collapsed) {
        collapsedRef.current = height;
        setSpacer(Math.max(0, fullRef.current - height), 0);
      } else if (opened) {
        // The summary row joined the lens: pull the content back up by exactly that much.
        setSpacer(0, Math.max(0, height - fullRef.current));
      } else if (!summaryShown || stale) {
        // At rest. (A stale measurement is taken even with the summary drawn: it is the summary's row less.)
        fullRef.current = summaryShown ? height - collapsedRef.current : height;
        widthRef.current = window.innerWidth;
        if (fromEvent) {
          setFullHeight(fullRef.current);
          setStale(false);
        }
      }
    };
    measure(false);
    const onResize = () => {
      if ((collapsed || opened) && window.innerWidth !== widthRef.current) {
        // The full height was measured at another width: expand for one commit, measure at rest, then collapse again.
        setOpened(false);
        setStale(true);
      } else {
        measure(true);
      }
    };
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => measure(true));
    observer?.observe(lensNode);
    window.addEventListener('resize', onResize);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', onResize);
    };
  }, [enabled, lensNode, spacerNode, collapsed, opened, summaryShown, stale]);

  return {
    attachLens: setLensNode,
    attachSpacer: setSpacerNode,
    attachSummary: setSummaryNode,
    collapsed,
    opened,
    summaryShown,
    // The one button, never a dead control: collapsed it opens the lens in place; expanded it puts the lens back to one line
    // wherever the reader is (forced, when they are not past This week).
    toggle: () => {
      if (collapsed) {
        setOpened(true);
        setForced(false);
      } else {
        setOpened(false);
        setForced(true);
      }
    },
    bodyFocusProps: {
      onFocus: (event) => setBodyFocus(isKeyboardFocus(event.target)),
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
