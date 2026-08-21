import { useCallback, useEffect, useState } from 'react';

/**
 * Whether a sticky element has left its resting place, from a 1px sentinel above it.
 *
 * <h2>Why a sentinel rather than a scroll listener</h2>
 *
 * <p>The bundle asks for the lens bar to gain a shadow and a raised border "once stuck", and names
 * the mechanism: "driven by an IntersectionObserver on a 1px sentinel above it". The alternative —
 * a {@code scroll} listener comparing {@code getBoundingClientRect().top} against the masthead's
 * height — measures layout on the main thread on every scroll frame, which is exactly the work the
 * observer exists to move off it. On a page whose Plan pane repaints six canvases, that is not a
 * theoretical cost.
 *
 * <p>The sentinel is a zero-content box immediately above the sticky element, so it scrolls away
 * while the element itself does not. Once it has passed above the line the element sticks at, the
 * element is stuck. {@code rootMargin}'s top is negative that line — the masthead's measured
 * height — which is why this hook takes {@code offset} rather than assuming zero: with the
 * masthead sticky too (M3), the lens bar's resting line is the masthead's bottom edge and not the
 * viewport's.
 *
 * <p>⚠️ <b>The observer is rebuilt when {@code offset} changes</b>, because {@code rootMargin} is
 * fixed at construction. The offset comes from a measured custom property and moves when the tick
 * line wraps or the origin gains a home button, so a hook that read it once would report "stuck"
 * from the wrong scroll position for the rest of the session.
 *
 * <p><b>Degrades to never-stuck.</b> jsdom has no {@code IntersectionObserver} and neither does a
 * very old browser; both get {@code false}, which renders the resting treatment. That is the safe
 * direction — the stuck treatment is a shadow, and a missing shadow costs a reader nothing, where
 * a permanent one over an unscrolled page would be a lie about where they are.
 *
 * <p>⚠️ <b>The sentinel arrives through a CALLBACK REF held in state, not a {@code useRef}</b>, and
 * that is a fix rather than a flourish. The lens bar is mounted conditionally — it appears only once
 * the briefing provider has resolved a reach lens — so on the shell's first commit the sentinel does
 * not exist and {@code ref.current} is null. With a plain ref the effect ran once against nothing,
 * returned early, and never re-ran (its only dependency was the offset, which by then had already
 * settled); no observer was ever attached and the bar sat unstuck through the whole session. Caught
 * in a browser, not by the suite — the tests rendered a shell whose bar was present on the first
 * commit, which is the one case the defect does not reach. A state-carrying callback ref makes the
 * node's arrival a dependency, so mounting the bar is itself what attaches the observer.
 *
 * @param {number} offset the line the sticky element rests at, in CSS pixels from the viewport top
 * @returns {[Function, boolean]} a ref callback for the 1px sentinel, and whether it has passed
 */
export default function useStuckSentinel(offset = 0) {
  const [node, setNode] = useState(null);
  const [stuck, setStuck] = useState(false);
  // Identity-stable, so React does not detach and re-attach on every render.
  const sentinelRef = useCallback((el) => setNode(el ?? null), []);

  useEffect(() => {
    if (!node || typeof IntersectionObserver === 'undefined') return undefined;

    const observer = new IntersectionObserver(
      ([entry]) => setStuck(!entry.isIntersecting),
      // `threshold: 0` with the top inset: the sentinel counts as visible only while some of it is
      // below the line. `Math.max(0, …)` because a negative offset would INFLATE the root and
      // report stuck a screen too early — the value arrives from a measurement, and a measurement
      // taken before layout is 0 rather than a sensible default.
      { threshold: 0, rootMargin: `-${Math.max(0, Math.round(offset))}px 0px 0px 0px` },
    );
    observer.observe(node);
    // Reset rather than freeze on teardown: the bar unmounts with the tab, and a `stuck` left true
    // would put the shadow back on the next bar the moment it mounts, before anything has scrolled.
    // In the cleanup rather than the body because a `setState` in an effect BODY is a cascading
    // render (the arm's lint rule refuses it); observing re-fires immediately, so the reset costs at
    // most one frame of resting treatment when the masthead's height changes.
    return () => { observer.disconnect(); setStuck(false); };
  }, [node, offset]);

  return [sentinelRef, stuck];
}
