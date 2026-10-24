import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { scrollToEntry, ENTRY_HIGHLIGHT_MS } from '../utils/scrollToEntry.js';

/**
 * The Coming up jump's scroll helper. It lost its behavioural coverage when the lane tests were
 * deleted (coming-up-glance-plan T1), so it is pinned here: the scroll (smooth, or instant under
 * reduced motion), the highlight ring, and where focus lands.
 */

let scrollIntoView;

/** Mounts a `data-entry-id` wrapper, with a card button unless `inert`. */
const mountEntry = ({ id = 'e1', inert = false } = {}) => {
  const wrap = document.createElement('div');
  wrap.setAttribute('data-entry-id', id);
  if (!inert) {
    const card = document.createElement('button');
    card.setAttribute('data-testid', 'coming-up-card');
    wrap.appendChild(card);
  }
  document.body.appendChild(wrap);
  return wrap;
};

const setReducedMotion = (reduce) => {
  window.matchMedia = vi.fn().mockReturnValue({ matches: reduce });
};

beforeEach(() => {
  scrollIntoView = vi.fn();
  Element.prototype.scrollIntoView = scrollIntoView;
  setReducedMotion(false);
});

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
  delete Element.prototype.scrollIntoView;
  delete window.matchMedia;
});

describe('scrollToEntry', () => {
  it('returns false and does nothing for an entry that is not rendered', () => {
    expect(scrollToEntry('missing')).toBe(false);
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('scrolls the entry to the centre, smoothly by default', () => {
    mountEntry();
    expect(scrollToEntry('e1')).toBe(true);
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' });
  });

  it('scrolls instantly under reduced motion', () => {
    setReducedMotion(true);
    mountEntry();
    scrollToEntry('e1');
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'auto', block: 'center' });
  });

  it('adds the highlight class, then removes it after ENTRY_HIGHLIGHT_MS', () => {
    vi.useFakeTimers();
    const wrap = mountEntry();
    scrollToEntry('e1');
    expect(wrap).toHaveClass('wf-cu-ent-highlight');
    vi.advanceTimersByTime(ENTRY_HIGHLIGHT_MS - 1);
    expect(wrap).toHaveClass('wf-cu-ent-highlight');
    vi.advanceTimersByTime(1);
    expect(wrap).not.toHaveClass('wf-cu-ent-highlight');
  });

  it('puts focus on the card button, never on something inside it', () => {
    const wrap = mountEntry();
    scrollToEntry('e1');
    expect(document.activeElement).toBe(wrap.querySelector('[data-testid="coming-up-card"]'));
    expect(wrap).not.toHaveAttribute('tabindex');
  });

  it('focuses an inert entry\'s wrapper with a temporary tabindex that goes on blur', () => {
    const wrap = mountEntry({ inert: true });
    scrollToEntry('e1');
    expect(wrap).toHaveAttribute('tabindex', '-1');
    expect(document.activeElement).toBe(wrap);
    wrap.blur();
    expect(wrap).not.toHaveAttribute('tabindex');
  });
});
