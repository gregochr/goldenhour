import { useEffect, useRef } from 'react';

/**
 * The focus ring's outline plus its offset — what a reservation needs beyond the bar's own height.
 *
 * <p>The 60px literal the stylesheet still carries as a fallback is this constant plus the bar's
 * measured 53.5px, and stating it here is what lets the two agree without either being rewritten
 * when the other moves.
 */
const RING_ALLOWANCE = 6;

/**
 * Keeps `--wf-lens-reserve` equal to the height of the sticky lens bar.
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
 * <p>So the shell observes the bar and publishes its height. The stylesheet keeps its literals as
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
 * <p>The property is <b>removed</b> when the bar is gone, rather than left at its last value: with
 * no bar there is nothing to reserve against, and the stylesheet's own fallback is the right answer.
 *
 * @param {object} rootRef ref to the element that hosts the variable — the arm's `.wf-shell`
 */
export default function useLensReserve(rootRef) {
  // The last value written, so a resize that changes nothing does not touch the DOM. `ResizeObserver`
  // fires on sub-pixel reflows the bar has plenty of.
  const written = useRef(null);

  useEffect(() => {
    const root = rootRef.current;
    if (!root || typeof ResizeObserver === 'undefined') return undefined;

    const clear = () => {
      if (written.current === null) return;
      written.current = null;
      root.style.removeProperty('--wf-lens-reserve');
    };
    const apply = (bar) => {
      if (!bar) { clear(); return; }
      const next = Math.round(bar.getBoundingClientRect().height + RING_ALLOWANCE);
      if (next === written.current) return;
      written.current = next;
      root.style.setProperty('--wf-lens-reserve', `${next}px`);
    };

    const observer = new ResizeObserver(() => apply(root.querySelector('.wf-lens')));
    // The bar comes and goes with the tab, so what is watched is the SHELL — a box that outlives
    // every layout change inside it — and the bar is looked up on each callback. Watching the bar
    // itself would need re-attaching on every tab change and would never fire for its removal.
    observer.observe(root);
    apply(root.querySelector('.wf-lens'));

    return () => {
      observer.disconnect();
      clear();
    };
  }, [rootRef]);
}
