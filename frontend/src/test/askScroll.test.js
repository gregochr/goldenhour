import { describe, it, expect, afterEach } from 'vitest';
import { scrollToTopOfScroller } from '../utils/askScroll.js';

/**
 * `scrollToTopOfScroller` moves the nearest vertical scroller and nothing else. jsdom has no layout, so
 * the geometry is set by hand: the scroller reports a height smaller than its content, and the node a
 * top edge relative to the scroller's.
 */

afterEach(() => { document.body.replaceChildren(); });

/** A scroller (overflow-y as given) holding a node; returns both. */
function build({ overflowY = 'auto', overflows = true, nodeTop = 300, scrollerTop = 100, scrollTop = 50 } = {}) {
  const scroller = document.createElement('div');
  scroller.style.overflowY = overflowY;
  Object.defineProperty(scroller, 'scrollHeight', { value: overflows ? 900 : 200 });
  Object.defineProperty(scroller, 'clientHeight', { value: 400 });
  scroller.getBoundingClientRect = () => ({ top: scrollerTop });
  scroller.scrollTop = scrollTop;
  const inner = document.createElement('div');
  const node = document.createElement('div');
  node.getBoundingClientRect = () => ({ top: nodeTop });
  inner.append(node);
  scroller.append(inner);
  document.body.append(scroller);
  return { scroller, node };
}

describe('scrollToTopOfScroller', () => {
  it('scrolls the scroller so the node sits at its top, leaving a small gap', () => {
    const { scroller, node } = build();

    scrollToTopOfScroller(node);

    // 50 already scrolled + (300 − 100) the node is below the scroller's top − 8 gap.
    expect(scroller.scrollTop).toBe(242);
  });

  it('takes the gap as given', () => {
    const { scroller, node } = build();

    scrollToTopOfScroller(node, 0);

    expect(scroller.scrollTop).toBe(250);
  });

  it('works with overflow-y: scroll too', () => {
    const { scroller, node } = build({ overflowY: 'scroll' });

    scrollToTopOfScroller(node);

    expect(scroller.scrollTop).toBe(242);
  });

  it('skips an ancestor that does not overflow, and one that does not scroll', () => {
    const { scroller, node } = build({ overflows: false });

    scrollToTopOfScroller(node);

    expect(scroller.scrollTop).toBe(50);
  });

  it('finds the NEAREST scroller', () => {
    const { scroller: outer, node } = build();
    const inner = document.createElement('div');
    inner.style.overflowY = 'auto';
    Object.defineProperty(inner, 'scrollHeight', { value: 700 });
    Object.defineProperty(inner, 'clientHeight', { value: 300 });
    inner.getBoundingClientRect = () => ({ top: 200 });
    node.parentElement.replaceWith(inner);
    inner.append(node);

    scrollToTopOfScroller(node);

    expect(inner.scrollTop).toBe(92);
    expect(outer.scrollTop).toBe(50);
  });

  it('does nothing for no node, or a node with no scroller around it', () => {
    const lone = document.createElement('div');
    document.body.append(lone);

    expect(() => scrollToTopOfScroller(null)).not.toThrow();
    expect(() => scrollToTopOfScroller(lone)).not.toThrow();
  });
});
