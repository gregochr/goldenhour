import { describe, it, expect, afterEach } from 'vitest';
import { scrollToTopOfScroller } from '../utils/askScroll.js';

/** A div with inline overflow, a stubbed box and stubbed scroll metrics. */
function scroller({ overflowY, scrollHeight, clientHeight, top }) {
  const el = document.createElement('div');
  el.style.overflowY = overflowY;
  Object.defineProperty(el, 'scrollHeight', { configurable: true, value: scrollHeight });
  Object.defineProperty(el, 'clientHeight', { configurable: true, value: clientHeight });
  el.getBoundingClientRect = () => ({ top });
  el.scrollTop = 0;
  return el;
}

// jsdom 31 returns an empty computed style for an element that is not in the document, so each
// scroller tree is attached to the body (and removed again) for `overflow-y` to be read at all.
const mounted = [];
function mount(el) {
  document.body.appendChild(el);
  mounted.push(el);
  return el;
}
afterEach(() => {
  mounted.splice(0).forEach((el) => el.remove());
});

describe('askScroll edges', () => {
  it('skips a scroller whose content exactly fits and scrolls the next one out', () => {
    const outer = scroller({ overflowY: 'auto', scrollHeight: 500, clientHeight: 200, top: 100 });
    const inner = scroller({ overflowY: 'auto', scrollHeight: 100, clientHeight: 100, top: 120 });
    const node = document.createElement('p');
    node.getBoundingClientRect = () => ({ top: 150 });
    outer.appendChild(inner);
    inner.appendChild(node);
    mount(outer);
    scrollToTopOfScroller(node);
    expect(inner.scrollTop).toBe(0);
    expect(outer.scrollTop).toBe(150 - 100 - 8);
  });

  it('scrolls the nearest scroller that really overflows, by the offset less the gap', () => {
    const outer = scroller({ overflowY: 'scroll', scrollHeight: 900, clientHeight: 300, top: 0 });
    const inner = scroller({ overflowY: 'auto', scrollHeight: 300, clientHeight: 100, top: 40 });
    const node = document.createElement('p');
    node.getBoundingClientRect = () => ({ top: 90 });
    outer.appendChild(inner);
    inner.appendChild(node);
    mount(outer);
    scrollToTopOfScroller(node, 10);
    expect(inner.scrollTop).toBe(90 - 40 - 10);
    expect(outer.scrollTop).toBe(0);
  });

  it('does nothing when no ancestor scrolls, or when there is no node', () => {
    const fixed = scroller({ overflowY: 'hidden', scrollHeight: 500, clientHeight: 100, top: 0 });
    const node = document.createElement('p');
    fixed.appendChild(node);
    mount(fixed);
    scrollToTopOfScroller(node);
    expect(fixed.scrollTop).toBe(0);
    expect(() => scrollToTopOfScroller(null)).not.toThrow();
  });
});
