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
 * <h2>The section headings (one-screen-plan.md O2)</h2>
 *
 * <p>The screen has a second sticky element under the lens bar, the "This week" heading bar
 * ({@code .wf-sec-bar-week}). The day tiles, the rails and every focused card sit below it, so its measured height is
 * added to {@code --wf-lens-h} and to {@code --wf-lens-reserve}: the existing {@code calc()}s in the stylesheet need no
 * new term, and they stay right while the bar is absent (it adds zero). Further ahead has its own pair (below), because
 * the lens is not drawn over it. {@code --wf-bar-h} is the lens bar ALONE, the line This week's heading sticks at.
 *
 * <h2>The lens belongs to This week: Further ahead's own pair (lens-this-week-only-plan.md)</h2>
 *
 * <p>Past This week the lens is {@code visibility: hidden} and {@code inert}, which keeps its box: no ResizeObserver
 * reports the flip, so {@code hidden} is an explicit input here. {@code --wf-bar-h}, {@code --wf-lens-h} and
 * {@code --wf-lens-reserve} nevertheless KEEP the full lens measurement, because This week reads them too (its scroll
 * margins, its cards, the This week jump): zeroing them would scroll a This week target to {@code top: 0}, bring This week
 * back across the line, and draw the lens over the focused target (WCAG 2.4.11). Further ahead is below the line whenever the
 * reader is in it, so it has its own pair that drops only the lens term while {@code hidden}:
 * {@code --wf-ahead-top} (where its bar sticks and its section and heading land) and {@code --wf-ahead-reserve} (its own bar
 * plus the ring: never 0, or a tabbed row lands under that bar).
 *
 * <p>The caller passes {@code hidden} as "no verdict is holding the lens drawn", not the lens's visibility at this instant:
 * the Further ahead jump scrolls with margins read BEFORE the lens flips, so they must already exclude it.
 * *
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
 * @param {object} [options]
 * @param {boolean} [options.hidden] whether Further ahead's measurements assume the lens is not drawn over it (see above);
 *        read by the effect, so a change re-measures. The three shared variables never read it.
 */
export default function useLensReserve(rootRef, { hidden = false } = {}) {
  // The last value written per property, so a resize that changes nothing does not touch the DOM.
  // `ResizeObserver` fires on sub-pixel reflows the bar has plenty of.
  const written = useRef({
    '--wf-lens-reserve': null, '--wf-lens-h': null, '--wf-bar-h': null, '--wf-ahead-top': null, '--wf-ahead-reserve': null,
  });

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
      // The "This week" heading bar (one-screen-plan.md O2) is sticky under the lens bar, so everything the matrix
      // pins or reserves against starts below it too. Zero when the Plan pane is not showing (a hidden element has no
      // box) and while the screen has no such bar.
      const weekEl = root.querySelector('.wf-sec-bar-week');
      if (observer && weekEl) observer.observe(weekEl);
      // ⚠️ Observed DIRECTLY as well as through the shell, and re-observed on every pass. Watching
      // the shell alone is enough in principle — it is the bar's ancestor and its height follows the
      // bar's — but it makes every measurement one reflow late. `observe` is idempotent per
      // element+box, so re-calling it costs nothing, and `disconnect()` in the teardown below
      // releases both at once.
      if (observer && barEl) observer.observe(barEl);
      // Further ahead's own bar: its measured height is the whole of its reservation once the lens term is dropped.
      const aheadEl = root.querySelector('.wf-sec-bar-ahead');
      if (observer && aheadEl) observer.observe(aheadEl);
      const bar = heightOf(barEl);
      const week = heightOf(weekEl) ?? 0;
      // The ring allowance is added once rather than per element — it is the focus outline's own 2px
      // plus its 2px offset on the focused card, not a per-sticky-element margin. It was a SUM over
      // the masthead and the bar until the anchoring fix; the masthead was never pinned, so the term
      // reserved against nothing. Cleared when the bar is gone: with no bar there is nothing on the
      // pane to reserve against at all, and the stylesheet's fallback covers it.
      write('--wf-lens-reserve', bar === null ? null : bar + week + RING_ALLOWANCE);
      // ⚠️ MEASURED ZERO, NOT CLEARED — the one place this hook's own discipline is deliberately
      // reversed (matrix-axis plan D11(a)). `--wf-lens-reserve` clears so the stylesheet's fallback
      // takes over when there is nothing to reserve against; the row-tile rail's sticky `top` has no
      // such safe fallback state, because a 54px fallback for a bar that does not exist floats the
      // tiles over open space with cards scrolling through the naked band above them. `bar ?? 0` is
      // still a real measurement, just of an absent element's rendered height.
      write('--wf-lens-h', (bar ?? 0) + week);
      // The lens bar ALONE, which is where each section heading rests: the "This week" heading and Further ahead's both
      // stick at this line, one after the other, so neither may include the other's height. A measured zero for the
      // same reason as `--wf-lens-h`.
      write('--wf-bar-h', bar ?? 0);
      // Further ahead's pair (see the class note): the lens term is dropped while `hidden`. The shared three above are
      // deliberately untouched by it.
      const aheadBar = heightOf(aheadEl);
      const lensTerm = hidden ? 0 : (bar ?? 0);
      write('--wf-ahead-top', lensTerm);
      write('--wf-ahead-reserve', aheadBar === null ? null : lensTerm + aheadBar + RING_ALLOWANCE);
    };

    observer = new ResizeObserver(apply);
    // The bar comes and goes with the tab, so what is watched is the SHELL — a box that outlives
    // every layout change inside it — and the bar is looked up on each callback. Watching ONLY the
    // bar would need re-attaching on every tab change and would never fire for its removal, which
    // is why the shell is observed as well as (not instead of) the bar itself, above.
    observer.observe(root);
    apply();

    return () => {
      observer.disconnect();
      clear('--wf-lens-reserve');
      clear('--wf-lens-h');
      clear('--wf-bar-h');
      clear('--wf-ahead-top');
      clear('--wf-ahead-reserve');
    };
  }, [rootRef, hidden]);
}
