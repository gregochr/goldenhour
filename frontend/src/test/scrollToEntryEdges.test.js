import { describe, it, expect, vi, afterEach } from 'vitest';
import { scrollToEntry } from '../utils/scrollToEntry.js';

const mountEntry = ({ cardTag = 'button', id = 'e1' } = {}) => {
  const wrap = document.createElement('div');
  wrap.setAttribute('data-entry-id', id);
  if (cardTag) {
    const card = document.createElement(cardTag);
    card.setAttribute('data-testid', 'coming-up-card');
    wrap.appendChild(card);
  }
  document.body.appendChild(wrap);
  return wrap;
};

afterEach(() => {
  vi.unstubAllGlobals();
  document.body.innerHTML = '';
  delete Element.prototype.scrollIntoView;
  delete window.matchMedia;
});

describe('scrollToEntry — edge by edge', () => {
  it('works where scrollIntoView does not exist and still moves focus', () => {
    const wrap = mountEntry();
    delete Element.prototype.scrollIntoView;
    expect(scrollToEntry('e1')).toBe(true);
    expect(document.activeElement).toBe(wrap.querySelector('button'));
  });

  it('scrolls smoothly when matchMedia is missing, returns nothing, or throws', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    mountEntry();
    delete window.matchMedia;
    scrollToEntry('e1');
    window.matchMedia = vi.fn().mockReturnValue(undefined);
    scrollToEntry('e1');
    window.matchMedia = vi.fn(() => { throw new Error('no media'); });
    scrollToEntry('e1');
    expect(scrollIntoView).toHaveBeenCalledTimes(3);
    for (const call of scrollIntoView.mock.calls) {
      expect(call[0]).toEqual({ behavior: 'smooth', block: 'center' });
    }
  });

  it('asks for the reduced-motion query by name', () => {
    Element.prototype.scrollIntoView = vi.fn();
    window.matchMedia = vi.fn().mockReturnValue({ matches: true });
    mountEntry();
    scrollToEntry('e1');
    expect(window.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
  });

  it('focuses the wrapper, not the card, when the card is not a button', () => {
    const wrap = mountEntry({ cardTag: 'div' });
    scrollToEntry('e1');
    expect(wrap).toHaveAttribute('tabindex', '-1');
    expect(document.activeElement).toBe(wrap);
  });

  it('removes the temporary tabindex on the first blur only', () => {
    const wrap = mountEntry({ cardTag: null });
    scrollToEntry('e1');
    wrap.blur();
    expect(wrap).not.toHaveAttribute('tabindex');
    wrap.setAttribute('tabindex', '0');
    wrap.focus();
    wrap.blur();
    expect(wrap).toHaveAttribute('tabindex', '0');
  });

  it('focuses with preventScroll', () => {
    const wrap = mountEntry({ cardTag: null });
    wrap.focus = vi.fn();
    scrollToEntry('e1');
    expect(wrap.focus).toHaveBeenCalledTimes(1);
    expect(wrap.focus).toHaveBeenCalledWith({ preventScroll: true });
  });

  it('does not throw when the focus target has no focus method', () => {
    const wrap = mountEntry({ cardTag: null });
    wrap.focus = undefined;
    expect(scrollToEntry('e1')).toBe(true);
  });
});
