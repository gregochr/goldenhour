/**
 * Brings a node to the top of the scroller it sits in — and moves nothing else. {@code scrollIntoView}
 * is not used: it scrolls every ancestor, the PAGE included, and the Ask dock is sticky beside a page
 * the reader has scrolled, so a conversation that moves itself must not move the page under it.
 *
 * <p>The scroller is the nearest ancestor that scrolls vertically (the sheet's, the dock's or the
 * phone peek's own). With none — or a node that is not in one — nothing happens.
 *
 * @param {?HTMLElement} node
 * @param {number} [gap=8] px of room left above it
 */
export function scrollToTopOfScroller(node, gap = 8) {
  if (!node) return;
  let scroller = node.parentElement;
  while (scroller) {
    const { overflowY } = window.getComputedStyle(scroller);
    if ((overflowY === 'auto' || overflowY === 'scroll') && scroller.scrollHeight > scroller.clientHeight) break;
    scroller = scroller.parentElement;
  }
  if (!scroller) return;
  const offset = node.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
  scroller.scrollTop += offset - gap;
}
