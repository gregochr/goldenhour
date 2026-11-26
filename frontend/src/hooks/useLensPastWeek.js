import { useEffect, useLayoutEffect, useState } from 'react';

/**
 * How far BELOW the lens's bottom edge the "past This week" line sits, in CSS px. The Further ahead jump parks that
 * section's heading right under the top of the viewport, so the end of This week is then only a pixel or two below the lens:
 * the line is a little below the edge so that landing counts as past, which a line at the viewport top never did.
 *
 * <p>Builder measurement (Playwright, Chromium, the built app on the seeded fixture, 390x844, lens 93.4px, scrolled 10px a
 * step): with this line the lens hides when the end of This week is at 100px (the line is 93 + 16 = 109, so the first step
 * at or below it), which leaves the vacated slot showing the last 100px of This week while Further ahead's bar is still 110px
 * down; the bar reaches the top 110px of scroll after that. The alternative line, where Further ahead's bar would stick
 * (the end of This week at about -10px), keeps the lens drawn over Further ahead's heading and first rows for those whole 100px
 * of scroll, so the heading the reader just scrolled to is covered by a control that gates nothing there. The line just below
 * the lens's edge was kept: the swap shows This week's tail for a moment, and Further ahead's heading is never under a drawn
 * lens.
 */
const LINE_SLACK = 16;
/**
 * Hysteresis, in CSS px: the lens is hidden once the end of This week is this far PAST the line, and drawn again only once
 * it is back on the near side of the line. Without it a rounding at a fractional device scale can flip the state on every
 * frame.
 */
const HYSTERESIS = 8;

/**
 * Whether a focus event is KEYBOARD focus. Chrome on Android focuses a button on a tap and a touch scroll never blurs it, so
 * "focus is in the lens" must mean keyboard focus or a chip tap would hold the lens drawn for good. Where
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
 * The lens belongs to This week (screen-spec.md section 6.6, D6; docs/engineering/lens-this-week-only-plan.md): past This
 * week the drive and rating bar is not drawn, because the almanac below reads neither of its controls.
 *
 * <p><b>The state.</b> {@code hidden} is true while the This week section is scrolled past the lens, and false otherwise
 * (and always false when {@code enabled} is false). It is held false (the lens stays drawn) while KEYBOARD focus is inside
 * the lens, and ends on the blur (or when focus leaves the lens by any route, including the focused element being removed). A POINTER focus (see {@link isKeyboardFocus}) does not hold it: when the lens hides with
 * one inside, focus moves to {@code getFocusTarget()} (Further ahead's heading) with {@code preventScroll}, so it never
 * sits in an {@code inert} subtree and never falls to {@code <body>}. {@code enabled} is false while the plan-conflict
 * verdict shows: a warning with an action is never hidden.
 *
 * <p><b>Past This week</b> is observed here (an IntersectionObserver on {@code weekNode}, the This week section) against a
 * line just below the lens's bottom edge, with {@link HYSTERESIS}. The section, not a 1px marker at its end: an observer
 * reports only a change of "intersecting", so a marker below the fold is never reported when one frame jumps over it (a
 * fling, the Further ahead jump), where the tall section flips state on every jump from the top. The lens's height is
 * measured from the element (a {@code ResizeObserver}), with {@code guessHeight} standing in for the first paint.
 *
 * <p><b>No jump.</b> The caller hides the lens with {@code visibility: hidden} and {@code inert}, never {@code display:
 * none}: the lens is {@code position: sticky}, so its flow slot (above This week) stays where it rests and the document
 * height does not change, so nothing needs a spacer. The visibility change does not alter the box either, so no
 * ResizeObserver reports the flip: whatever depends on it takes {@code hidden} as an explicit input.
 *
 * <p>Builder measurement (same setup): across the flip from drawn to hidden, in 10px steps through the line, {@code scrollY}
 * advanced by exactly the step and {@code document.scrollingElement.scrollHeight} did not change by a pixel (31 samples, 0px
 * difference); the smoke spec asserts the same, plus that the section below the lens has not moved in the page.
 *
 * @param {object} args
 * @param {boolean} args.enabled false while a plan-conflict verdict shows (the lens is never hidden then)
 * @param {?Element} args.weekNode the This week section, or null before it mounts
 * @param {number} args.guessHeight the lens's height before it has been measured (it differs by width)
 * @param {Function} args.getFocusTarget returns the element a pointer focus left in the lens moves to
 * @returns {{attachLens: Function, hidden: boolean, focusProps: object}} the callback ref for the lens element, whether it is
 *   hidden, and the focus handlers to spread on the lens
 */
export default function useLensPastWeek({ enabled, weekNode, guessHeight, getFocusTarget }) {
  // The node arrives through a CALLBACK ref held in state so the effects attach when it mounts (the bar is mounted
  // conditionally), the same reason `useStuckSentinel` does.
  const [lensNode, setLensNode] = useState(null);
  const [lensHeight, setLensHeight] = useState(guessHeight);
  const [pastWeek, setPastWeek] = useState(false);
  const [keyboardFocus, setKeyboardFocus] = useState(false);

  // Adjust-state-while-rendering: with the hook off (a verdict) the latch is cleared, so a stale "past" cannot outlive it.
  if (!enabled && pastWeek) setPastWeek(false);
  const hidden = enabled && pastWeek && !keyboardFocus;

  // The hold is true only while focus is ACTUALLY in the lens. A focused element that is REMOVED (the Reset button after
  // its press, a verdict action that refocuses a card) fires no blur, so the lens's own onBlur never clears it: while held,
  // watch the lens's children for removal, and any focus that lands outside it.
  useEffect(() => {
    if (!keyboardFocus || !lensNode) return undefined;
    const release = () => {
      if (!lensNode.contains(document.activeElement)) setKeyboardFocus(false);
    };
    const onFocusIn = (event) => {
      if (!lensNode.contains(event.target)) setKeyboardFocus(false);
    };
    const mutations = typeof MutationObserver === 'undefined' ? null : new MutationObserver(release);
    mutations?.observe(lensNode, { childList: true, subtree: true });
    document.addEventListener('focusin', onFocusIn);
    return () => {
      mutations?.disconnect();
      document.removeEventListener('focusin', onFocusIn);
    };
  }, [keyboardFocus, lensNode]);

  // The lens's own height, which fixes the line. Read on attach and whenever the box changes.
  useLayoutEffect(() => {
    if (!lensNode) return undefined;
    const measure = () => {
      const height = lensNode.getBoundingClientRect().height;
      if (height > 0) setLensHeight(height);
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(lensNode);
    return () => observer?.disconnect();
  }, [lensNode]);

  // Is This week past the lens? Rebuilt when the state flips so the line moves by the hysteresis.
  useEffect(() => {
    if (!enabled || !weekNode || typeof IntersectionObserver === 'undefined') return undefined;
    const line = Math.round(lensHeight) + LINE_SLACK;
    const margin = pastWeek ? line : line - HYSTERESIS;
    const observer = new IntersectionObserver(
      ([entry]) => setPastWeek(
        !entry.isIntersecting && (entry.boundingClientRect?.bottom ?? Infinity) <= (entry.rootBounds?.top ?? margin),
      ),
      { threshold: 0, rootMargin: `-${margin}px 0px 0px 0px` },
    );
    observer.observe(weekNode);
    return () => observer.disconnect();
  }, [enabled, weekNode, lensHeight, pastWeek]);

  // Focus never sits in an inert element or falls to <body>: a pointer focus left in the lens moves on, in the commit that
  // hides it (before the browser's own focus fix-up could drop it to the body).
  useLayoutEffect(() => {
    if (!hidden || !lensNode) return;
    const active = document.activeElement;
    if (!active || !lensNode.contains(active)) return;
    const target = getFocusTarget?.();
    if (target) target.focus({ preventScroll: true });
    else active.blur();
  }, [hidden, lensNode, getFocusTarget]);

  return {
    attachLens: setLensNode,
    hidden,
    focusProps: {
      onFocus: (event) => setKeyboardFocus(isKeyboardFocus(event.target)),
      // A pointer focus followed by keys (arrows, Space on a clicked chip) is keyboard use: the hold is decided on the keys
      // too, not once at focus time.
      onKeyDown: () => setKeyboardFocus(true),
      onBlur: (event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setKeyboardFocus(false);
      },
    },
  };
}
