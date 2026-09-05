import { useEffect, useRef } from 'react';

/**
 * The focus ring's outline plus its offset — what a reservation needs beyond the bar's own height.
 *
 * <p>It is added ONCE to the whole reservation, not per sticky element: it is the outline and
 * offset on the focused card, which has one ring however many things are stacked above it. ⚠️ This
 * doc has said two different things and is now back to the first: the stylesheet's 60px fallback is
 * "this constant plus the bar's measured 53.5px". M3 made it a sum by adding the masthead's height,
 * and the masthead's `position: sticky` never actually took effect (`index.css`'s `.wf-mast` carries
 * the measurement), so the sum reserved against chrome that was never on screen. The literals in
 * `index.css` came back with it — 60px desktop, 136px phone.
 */
const RING_ALLOWANCE = 6;

/**
 * Keeps `--wf-lens-reserve` equal to the sticky chrome above the cards and `--wf-lens-h` equal to
 * the lens bar's own height. Since the anchoring fix those are the same measurement used two ways —
 * the bar is the only pinned thing on the pane — and they are still two properties because one
 * carries the focus ring's allowance and the other must not.
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
 * <h2>Two properties, and the second does not follow the first's clear-on-absent rule</h2>
 *
 * <p>`--wf-lens-h` (matrix-axis plan D11(a)) is the bar's OWN height, and it is the whole of the
 * row-tile rail's sticky `top` calc now that the masthead term has gone from it. Unlike
 * `--wf-lens-reserve`, it is written as a MEASURED `0px` when the bar is absent rather than cleared
 * to the stylesheet's fallback — clearing
 * would leave the tiles' `top` resting on a 54px fallback with no bar there to justify it, floating
 * the row over open space with cards scrolling through the naked band above them. A measured zero is
 * not the banned zero *fallback literal* the rest of this file's discipline forbids: it is what the
 * bar's own height actually is when it does not exist.
 *
 * <h2>⚠️ `--wf-mast-h` IS GONE, AND RE-ADDING IT IS A DELIBERATE PHASE</h2>
 *
 * <p>This hook also measured the masthead and published its height, because M3 gave `.wf-mast` a
 * `position: sticky` and the lens bar rested at `top: var(--wf-mast-h)`. That stick never worked: a
 * sticky element cannot travel outside its own containing block, and the masthead's is the shell
 * wrapper holding only the masthead, the tab bar and the tab rule — about 46px taller than the band
 * itself. So the band scrolled away like any other content while the bar went on sticking a
 * masthead's height down the viewport, with matrix cards scrolling through the gap above it.
 *
 * <p>Three things were derived from that height and all three have lost the term: the bar's own
 * `top` (now `0`), the two row-rail `top` calcs, and `--wf-lens-reserve` — which is once again the
 * bar plus the focus ring rather than a sum, because there is once again only one sticky element
 * over a focused card. `index.css`'s `.wf-mast` block carries the checklist for putting it back if
 * a later phase pins the masthead for real.
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
 * <p>`--wf-lens-reserve` is <b>removed</b> when the bar it measures is gone, rather than left at
 * its last value: with nothing there to reserve against, the stylesheet's own fallback is the right
 * answer.
 *
 * <p>It returns nothing. It used to hand the masthead's height back for {@code useStuckSentinel}'s
 * {@code rootMargin}, which was the line the bar rested at; the bar rests at the viewport's own top
 * edge now, so that offset is a constant zero and the sentinel takes no argument.
 *
 * @param {object} rootRef ref to the element that hosts the variables — the arm's `.wf-shell`
 */
export default function useLensReserve(rootRef) {
  // The last value written per property, so a resize that changes nothing does not touch the DOM.
  // `ResizeObserver` fires on sub-pixel reflows the bar has plenty of.
  const written = useRef({ '--wf-lens-reserve': null, '--wf-lens-h': null });

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
      const barEl = root.querySelector('.wf-lens');
      // ⚠️ Observed DIRECTLY as well as through the shell, and re-observed on every pass. Watching
      // the shell alone is enough in principle — it is the bar's ancestor and its height follows the
      // bar's — but it makes every measurement one reflow late. `observe` is idempotent per
      // element+box, so re-calling it costs nothing, and `disconnect()` in the teardown below
      // releases both at once.
      if (observer && barEl) observer.observe(barEl);
      const bar = heightOf(barEl);
      // The ring allowance is added once rather than per element — it is the focus outline's own 2px
      // plus its 2px offset on the focused card, not a per-sticky-element margin. It was a SUM over
      // the masthead and the bar until the anchoring fix; the masthead was never pinned, so the term
      // reserved against nothing. Cleared when the bar is gone: with no bar there is nothing on the
      // pane to reserve against at all, and the stylesheet's fallback covers it.
      write('--wf-lens-reserve', bar === null ? null : bar + RING_ALLOWANCE);
      // ⚠️ MEASURED ZERO, NOT CLEARED — the one place this hook's own discipline is deliberately
      // reversed (matrix-axis plan D11(a)). `--wf-lens-reserve` clears so the stylesheet's fallback
      // takes over when there is nothing to reserve against; the row-tile rail's sticky `top` has no
      // such safe fallback state, because a 54px fallback for a bar that does not exist floats the
      // tiles over open space with cards scrolling through the naked band above them. `bar ?? 0` is
      // still a real measurement, just of an absent element's rendered height.
      write('--wf-lens-h', bar ?? 0);
    };

    observer = new ResizeObserver(apply);
    // The bar comes and goes with the tab, so what is watched is the SHELL — a box that outlives
    // every layout change inside it — and both elements are looked up on each callback. Watching
    // either directly would need re-attaching on every tab change and would never fire for removal.
    observer.observe(root);
    apply();

    return () => {
      observer.disconnect();
      clear('--wf-lens-reserve');
      clear('--wf-lens-h');
    };
  }, [rootRef]);
}
