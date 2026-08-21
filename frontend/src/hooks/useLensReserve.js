import { useEffect, useRef, useState } from 'react';

/**
 * The focus ring's outline plus its offset — what a reservation needs beyond the bar's own height.
 *
 * <p>It is added ONCE to the whole sum, not per sticky element: it is the outline and offset on the
 * focused card, which has one ring however many things are stacked above it. ⚠️ This doc used to say
 * the stylesheet's 60px fallback was "this constant plus the bar's measured 53.5px" — true until M3
 * made the masthead sticky as well. The fallbacks are now masthead + bar + this, and the two
 * literals in `index.css` were re-measured with them (188px desktop, 266px phone).
 */
const RING_ALLOWANCE = 6;

/**
 * Keeps `--wf-mast-h` equal to the masthead's height and `--wf-lens-reserve` equal to the whole of
 * the sticky chrome above the cards.
 *
 * <h2>Why this is measured rather than written down</h2>
 *
 * <p>`scroll-margin-top` is how the Plan pane stops the browser scrolling a focused card underneath
 * a sticky bar the browser knows nothing about. It has been a literal since P6, and the stylesheet
 * around it carries three separate warnings to re-measure it — one of which (P14's) had already come
 * true, unnoticed, across the whole tablet band. The number is now a function of something the page
 * can see, so it cannot go stale:
 *
 * <ul>
 *   <li>the bar is <b>53.5px</b> as one row and <b>92px</b> once it wraps, and where it wraps
 *       depends on what the reader has clicked — a non-default tier adds a pill and a reset;</li>
 *   <li>on a phone it stacks to two full-width rows plus a count line, which is taller again;</li>
 *   <li>and every one of those is a browser measurement no test in this suite can make, because
 *       jsdom evaluates no CSS at all.</li>
 * </ul>
 *
 * <h2>Two properties, because M3 made the masthead sticky too</h2>
 *
 * <p>`--wf-mast-h` is what the lens bar sticks BELOW. The design pins the bar at
 * `top: var(--mastH)` and calls the value "measured from the masthead at runtime" — a literal here
 * would be the same staleness this hook exists to end, and worse: the masthead's height moves with
 * the tick line, which wraps at narrow widths and gains a home button when the origin does.
 *
 * <p>`--wf-lens-reserve` grew by the same amount. It is the `scroll-margin-top` every focusable
 * thing on the pane reserves, and a focused card now has TWO sticky elements over it rather than
 * one — reserving for the bar alone would park the card behind the masthead, which is the whole
 * defect the reservation exists to prevent. It is the sum, not the bar, and the property keeps its
 * name because what it names is the reservation rather than the bar.
 *
 * <p>The masthead is not conditional the way the bar is (it outlives every tab), but it is looked
 * up on each callback for the same reason and with the same fallback: a missing element clears
 * rather than freezes, so the stylesheet's own literal is what applies.
 *
 * <p>So the shell observes both and publishes their heights. The stylesheet keeps its literals as
 * <b>fallbacks</b> — `var(--wf-lens-reserve, 60px)` — which cover the first paint and any build
 * where this effect never runs. Those are the two states where being approximately right is fine;
 * what they cannot cover is the reader who has widened their reach on a tablet.
 *
 * <p>Reached through the shell's own root and the bar's class rather than a ref threaded into
 * {@code WindowFirstLensBar}: the property has to land on a common ANCESTOR of both the bar and the
 * cards, because custom properties inherit downwards and the cards are the bar's siblings. The bar
 * mounts and unmounts with the Plan tab, so a ref handed up would need clearing on every tab change
 * anyway.
 *
 * <p>Each property is <b>removed</b> when the element it measures is gone, rather than left at its
 * last value: with nothing there to reserve against, the stylesheet's own fallback is the right
 * answer.
 *
 * <p>It also RETURNS the masthead's height, which `--wf-mast-h` cannot answer for: the stuck
 * sentinel needs the number in JavaScript to build its {@code rootMargin}, and reading the property
 * back off the DOM would be the same measurement twice with a chance of disagreeing. The property
 * is for the stylesheet and the return value is for the observer; both come from one read.
 *
 * @param {object} rootRef ref to the element that hosts the variable — the arm's `.wf-shell`
 * @returns {number} the masthead's measured height in CSS pixels, or 0 before the first measure
 */
export default function useLensReserve(rootRef) {
  // The last value written per property, so a resize that changes nothing does not touch the DOM.
  // `ResizeObserver` fires on sub-pixel reflows the bar has plenty of.
  const written = useRef({ '--wf-mast-h': null, '--wf-lens-reserve': null });
  // State rather than a ref, because a consumer renders on it. It changes only when the masthead's
  // own height does — the tick line wrapping, the home button appearing — which is rare enough that
  // the render it costs is not on any scroll or resize hot path.
  const [mastHeight, setMastHeight] = useState(0);

  useEffect(() => {
    const root = rootRef.current;
    if (!root || typeof ResizeObserver === 'undefined') return undefined;

    const clear = (name) => {
      if (written.current[name] === null) return;
      written.current[name] = null;
      root.style.removeProperty(name);
    };
    const write = (name, next) => {
      if (next === null) { clear(name); return; }
      if (next === written.current[name]) return;
      written.current[name] = next;
      root.style.setProperty(name, `${next}px`);
    };
    const heightOf = (el) => (el ? Math.round(el.getBoundingClientRect().height) : null);
    /** @type {?ResizeObserver} assigned below; `apply` runs before the constructor returns. */
    let observer = null;
    const apply = () => {
      const mastEl = root.querySelector('.wf-mast');
      const barEl = root.querySelector('.wf-lens');
      // ⚠️ Observed DIRECTLY as well as through the shell, and re-observed on every pass. Watching
      // the shell alone is enough in principle — it is their parent and its height follows theirs —
      // but it makes every measurement one reflow late, and the masthead's own height lands late:
      // the light row arrives on an async fetch and grows the band by ~12px. `observe` is
      // idempotent per element+box, so re-calling it costs nothing, and `disconnect()` in the
      // teardown below releases all three at once. Measured on the running app before this: the
      // masthead read 117px against a real 129.5px, which would have stuck the lens bar 12px too
      // high — under the band it is supposed to rest against.
      if (observer) {
        if (mastEl) observer.observe(mastEl);
        if (barEl) observer.observe(barEl);
      }
      const mast = heightOf(mastEl);
      const bar = heightOf(barEl);
      write('--wf-mast-h', mast);
      setMastHeight(mast ?? 0);
      // ⚠️ The SUM, and the ring allowance is added once rather than per element — it is the focus
      // outline's own 2px plus its 2px offset on the focused card, not a per-sticky-element margin.
      // Cleared when the bar is gone even if the masthead is not: with no bar, there is nothing on
      // the pane to reserve against beyond the masthead, and the stylesheet's fallback covers it.
      write('--wf-lens-reserve', bar === null ? null : (mast ?? 0) + bar + RING_ALLOWANCE);
    };

    observer = new ResizeObserver(apply);
    // The bar comes and goes with the tab, so what is watched is the SHELL — a box that outlives
    // every layout change inside it — and both elements are looked up on each callback. Watching
    // either directly would need re-attaching on every tab change and would never fire for removal.
    observer.observe(root);
    apply();

    return () => {
      observer.disconnect();
      clear('--wf-mast-h');
      clear('--wf-lens-reserve');
    };
  }, [rootRef]);

  return mastHeight;
}
