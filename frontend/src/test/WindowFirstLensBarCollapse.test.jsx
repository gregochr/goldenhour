import React, { useRef } from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import WindowFirstLensBar from '../components/WindowFirstLensBar.jsx';
import useRatingLens from '../hooks/useRatingLens.js';
import useReachLens from '../hooks/useReachLens.js';
import useLensReserve from '../hooks/useLensReserve.js';
import { mockOpenGuide } from './guideContextMock.js';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

/**
 * The phone lens that collapses past This week (screen-spec.md D6, `useCollapsingLens`).
 *
 * <p>jsdom evaluates no CSS and has no layout, so the heights are a stub: the lens measures {@code FULL} at rest,
 * {@code COLLAPSED} once it carries the collapsed class, and {@code FULL + COLLAPSED} while the summary row is drawn above
 * the controls. The IntersectionObserver is a stub too, driven by hand. What is asserted is therefore the state machine and
 * the numbers it writes (the spacer, the published properties, the observer's line); that the page does not move is a
 * browser measurement, taken in the smoke spec.
 */
const FULL = 90;
const COLLAPSED = 36;
const TUESDAY = '2026-08-04';

let phoneListeners;
const setMatches = (matches) => vi.stubGlobal('matchMedia', (query) => ({
  matches: matches && query === '(max-width: 639px)',
  media: query,
  onchange: null,
  addEventListener(type, handler) { phoneListeners.push(handler); },
  removeEventListener(type, handler) { phoneListeners = phoneListeners.filter((h) => h !== handler); },
  addListener() {},
  removeListener() {},
  dispatchEvent() { return false; },
}));
const asPhone = () => setMatches(true);
const flipTo = (mobile) => act(() => { phoneListeners.forEach((handler) => handler({ matches: mobile })); });

let fullHeight;
let resizeObservers;
let intersectionObservers;
let keyboardFocus;
let week;
let realMatches;

beforeEach(() => {
  localStorage.clear();
  mockOpenGuide.mockClear?.();
  phoneListeners = [];
  fullHeight = FULL;
  resizeObservers = [];
  intersectionObservers = [];
  keyboardFocus = true;
  week = document.createElement('section');
  document.body.appendChild(week);
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback) { this.callback = callback; resizeObservers.push(this); }

    observe() {}

    disconnect() { resizeObservers = resizeObservers.filter((o) => o !== this); }
  });
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback, options) { this.callback = callback; this.options = options; intersectionObservers.push(this); }

    observe(element) { this.element = element; }

    disconnect() { intersectionObservers = intersectionObservers.filter((o) => o !== this); }
  });
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
    let h = 0;
    if (this.classList?.contains('wf-lens')) {
      const summary = this.querySelector('.wf-lens-summary');
      const collapsed = this.classList.contains('wf-lens-collapsed');
      if (collapsed) h = COLLAPSED;
      else h = fullHeight + (summary && !summary.hidden ? COLLAPSED : 0);
    }
    return {
      width: 390, height: h, top: 0, left: 0, right: 390, bottom: h, x: 0, y: 0, toJSON() {},
    };
  });
  realMatches = Element.prototype.matches;
  vi.spyOn(Element.prototype, 'matches').mockImplementation(function matches(selector) {
    if (selector === ':focus-visible') return keyboardFocus;
    return realMatches.call(this, selector);
  });
});

afterEach(() => {
  week.remove();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function Lens({ stuck = true, verdict = null, spotCount = 6, reachedCount = 6, originBase = null }) {
  const lens = useReachLens(TUESDAY, false);
  const ratingLens = useRatingLens();
  return (
    <WindowFirstLensBar
      lens={lens}
      ratingLens={ratingLens}
      spotCount={spotCount}
      reachedCount={reachedCount}
      windowCount={4}
      stuck={stuck}
      weekNode={week}
      verdict={verdict}
      originBase={originBase}
    />
  );
}

/** The observer watching This week, and the line (px from the viewport top) it is set to. */
const weekObserver = () => intersectionObservers.findLast((o) => o.element === week);
const lineOf = (observer) => Number.parseInt(observer.options.rootMargin, 10) * -1;
/** Reports This week as scrolled past the observer's line / back above it (the section crossed the line). */
const passWeek = () => act(() => {
  const observer = weekObserver();
  observer.callback([{ isIntersecting: false, boundingClientRect: { bottom: lineOf(observer) - 3 }, rootBounds: { top: lineOf(observer) } }]);
});
const backInWeek = () => act(() => {
  const observer = weekObserver();
  observer.callback([{ isIntersecting: true, boundingClientRect: { bottom: lineOf(observer) + 300 }, rootBounds: { top: lineOf(observer) } }]);
});
const remeasure = () => act(() => { resizeObservers.forEach((o) => o.callback()); });

const summary = () => screen.getByTestId('window-first-lens-summary');
const lensEl = () => screen.getByTestId('window-first-lens');
const spacer = () => screen.getByTestId('window-first-lens-spacer');
const spacerHeight = () => spacer().style.height;
const spacerPull = () => spacer().style.marginTop;
const chips = () => within(screen.getByTestId('window-first-lens-tiers')).getAllByRole('button');

describe('the lens on desktop and tablet (provably unchanged)', () => {
  it('draws no summary and no spacer, only the display-contents wrapper, and never collapses', () => {
    render(<Lens />);
    expect(screen.queryByTestId('window-first-lens-summary')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-spacer')).toBeNull();
    expect(screen.getByTestId('window-first-lens-body')).not.toHaveAttribute('hidden');
    expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
    expect(intersectionObservers.some((o) => o.element === week)).toBe(false);
  });
});

describe('the phone lens', () => {
  beforeEach(asPhone);

  it('at rest keeps the controls and hides the persistent summary, with no spacer', () => {
    render(<Lens />);
    expect(summary()).toHaveAttribute('hidden');
    expect(screen.getByTestId('window-first-lens-body')).not.toHaveAttribute('hidden');
    expect(spacerHeight()).toBe('0px');
  });

  it('past This week it collapses to the one summary line, naming drive, rating and count', () => {
    render(<Lens />);
    passWeek();
    expect(summary()).not.toHaveAttribute('hidden');
    expect(summary()).toHaveTextContent('Drive 45 min · any rating · 6 spots ▾');
    expect(summary()).toHaveAttribute('aria-expanded', 'false');
    expect(summary()).toHaveAttribute('aria-controls', screen.getByTestId('window-first-lens-body').id);
    expect(lensEl()).toHaveClass('wf-lens-collapsed');
  });

  it('names an away origin in the collapsed line, so it is never invisible', () => {
    render(<Lens originBase="Keswick" />);
    passWeek();
    expect(summary()).toHaveTextContent('From Keswick · 45 min · any rating · 6 spots ▾');
    // Label in name: the accessible name is the visible words (the dots and glyph are decoration).
    expect(summary()).toHaveAccessibleName('From Keswick 45 min any rating 6 spots');
  });

  it('keeps the controls mounted while collapsed, only hidden', () => {
    render(<Lens />);
    passWeek();
    const body = screen.getByTestId('window-first-lens-body');
    expect(body).toHaveAttribute('hidden');
    expect(within(body).getAllByRole('button', { hidden: true }).length).toBeGreaterThan(4);
  });

  it('speaks the stars of a rating floor and the glyph is decoration', () => {
    render(<Lens />);
    passWeek();
    fireEvent.click(summary());
    fireEvent.click(within(screen.getByTestId('window-first-lens-floors')).getByText('3★+'));
    fireEvent.click(summary());
    expect(summary()).toHaveTextContent('3★ stars+');
    expect(summary().querySelector('.sr-only')).toHaveTextContent('stars');
    expect(summary().querySelector('[aria-hidden="true"]')).not.toBeNull();
  });

  it('says "any distance" for the Any tier and the of-N wording when the floor trims', () => {
    render(<Lens spotCount={3} reachedCount={9} />);
    passWeek();
    fireEvent.click(summary());
    fireEvent.click(within(screen.getByTestId('window-first-lens-tiers')).getByText('Any'));
    fireEvent.click(summary());
    expect(summary()).toHaveTextContent('Drive any distance · any rating · 3 of 9 spots within reach');
  });

  describe('the line This week is measured against', () => {
    it('is the lens\'s FULL bottom edge plus a little, with hysteresis: collapse 8px past it, expand only back on it', () => {
      render(<Lens />);
      // The full lens is measured (90); the line sits 16px below its bottom edge, and collapsing needs 8px more.
      remeasure();
      expect(lineOf(weekObserver())).toBe(FULL + 16 - 8);
      passWeek();
      // Collapsed: the observer is rebuilt at the line itself, so the shrinking lens cannot re-trigger it.
      expect(lineOf(weekObserver())).toBe(FULL + 16);
      backInWeek();
      expect(lineOf(weekObserver())).toBe(FULL + 16 - 8);
    });

    it('ignores a section merely below the line, and one with no geometry', () => {
      render(<Lens />);
      act(() => weekObserver().callback([{
        isIntersecting: false, boundingClientRect: { bottom: 1500 }, rootBounds: { top: 100 },
      }]));
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
      act(() => weekObserver().callback([{ isIntersecting: false }]));
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
    });

    it('works without an IntersectionObserver (a very old browser): it never collapses on its own', () => {
      vi.stubGlobal('IntersectionObserver', undefined);
      render(<Lens />);
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
    });

    it('is not watched while the lens is disabled by a plan-conflict verdict', () => {
      const verdict = {
        lead: 'Clash', text: 'Nothing fits', action: null, announce: 'Clash. Nothing fits',
      };
      render(<Lens verdict={verdict} />);
      expect(weekObserver()).toBeUndefined();
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
      expect(summary()).toHaveAttribute('hidden');
    });
  });

  describe('no jump', () => {
    it('writes a spacer of full minus collapsed (unrounded), so the document is as tall as it was', () => {
      fullHeight = 90.4;
      render(<Lens />);
      passWeek();
      expect(spacerHeight()).toBe(`${90.4 - COLLAPSED}px`);
      expect(spacerPull()).toBe('0px');
    });

    it('opening adds no document height: the spacer pulls the content up by the summary row it added', () => {
      render(<Lens />);
      passWeek();
      fireEvent.click(summary());
      expect(spacerHeight()).toBe('0px');
      expect(spacerPull()).toBe(`-${COLLAPSED}px`);
      // …and corrects it from the measured opened height.
      fullHeight = 90;
      remeasure();
      expect(spacerPull()).toBe(`-${COLLAPSED}px`);
    });

    it('re-measures the full height on a resize while it is full', () => {
      render(<Lens />);
      fullHeight = 98;
      act(() => { window.dispatchEvent(new Event('resize')); });
      passWeek();
      expect(spacerHeight()).toBe(`${98 - COLLAPSED}px`);
    });

    it('re-measures when the lens itself resizes while full (a wrapped mark)', () => {
      render(<Lens />);
      fullHeight = 104;
      remeasure();
      passWeek();
      expect(spacerHeight()).toBe(`${104 - COLLAPSED}px`);
    });

    it('corrects the spacer from the measured collapsed height', () => {
      render(<Lens />);
      passWeek();
      expect(spacerHeight()).toBe(`${FULL - COLLAPSED}px`);
      remeasure();
      expect(spacerHeight()).toBe(`${FULL - COLLAPSED}px`);
    });

    it('works without a ResizeObserver (a very old browser): it still measures once', () => {
      vi.stubGlobal('ResizeObserver', undefined);
      render(<Lens />);
      passWeek();
      expect(spacerHeight()).toBe(`${FULL - COLLAPSED}px`);
    });

    it('a width change while collapsed invalidates the full height: it expands, measures at rest, then collapses again', () => {
      render(<Lens />);
      remeasure();
      passWeek();
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
      // A rotation within the phone band: the lens is taller at the new width.
      fullHeight = 112;
      window.innerWidth = 360;
      act(() => { window.dispatchEvent(new Event('resize')); });
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
      expect(spacerHeight()).toBe('0px');
      remeasure();
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
      expect(spacerHeight()).toBe(`${112 - COLLAPSED}px`);
      window.innerWidth = 1024;
    });

    it('a width change while opened closes it the same way, and is measured at rest', () => {
      render(<Lens />);
      passWeek();
      fireEvent.click(summary());
      fullHeight = 100;
      window.innerWidth = 340;
      act(() => { window.dispatchEvent(new Event('resize')); });
      remeasure();
      expect(spacerHeight()).toBe(`${100 - COLLAPSED}px`);
      window.innerWidth = 1024;
    });
  });

  describe('opening, closing and the reset', () => {
    it('a tap opens the lens in place: same button, expanded, controls shown', () => {
      render(<Lens />);
      passWeek();
      const before = summary();
      fireEvent.click(before);
      expect(summary()).toBe(before);
      expect(before).toHaveAttribute('aria-expanded', 'true');
      expect(screen.getByTestId('window-first-lens-body')).not.toHaveAttribute('hidden');
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
    });

    it('stays open while stuck, and tapping it again puts it back to one line', () => {
      const { rerender } = render(<Lens />);
      passWeek();
      fireEvent.click(summary());
      rerender(<Lens stuck />);
      expect(summary()).toHaveAttribute('aria-expanded', 'true');
      fireEvent.click(summary());
      expect(summary()).toHaveAttribute('aria-expanded', 'false');
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
    });

    it('un-sticking (back at the top) resets an opened lens, so the next scroll collapses it again', () => {
      const { rerender } = render(<Lens />);
      passWeek();
      fireEvent.click(summary());
      backInWeek();
      rerender(<Lens stuck={false} />);
      expect(summary()).toHaveAttribute('hidden');
      rerender(<Lens stuck />);
      passWeek();
      expect(summary()).toHaveAttribute('aria-expanded', 'false');
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
    });
  });

  describe('the summary is never a dead control', () => {
    it('opened and back within This week, a press collapses the lens (it is visibly open, and closes)', () => {
      render(<Lens />);
      passWeek();
      fireEvent.click(summary());
      backInWeek();
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
      expect(summary()).toHaveAttribute('aria-expanded', 'true');
      fireEvent.click(summary());
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
    });

    it('at rest with focus on it, a press collapses; scrolling away and back to the top ends that', () => {
      const { rerender } = render(<Lens stuck />);
      passWeek();
      act(() => summary().focus());
      fireEvent.click(summary());
      backInWeek();
      rerender(<Lens stuck={false} />);
      // Reset at the top, but focus is on the summary, so it stays drawn (and pressing it must do something).
      expect(summary()).not.toHaveAttribute('hidden');
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
      fireEvent.click(summary());
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
      // Scrolls (stuck), then returns to the top (un-stuck): the forced collapse is over.
      rerender(<Lens stuck />);
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
      rerender(<Lens stuck={false} />);
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
    });

    it('a collapsed press opens, and a second press collapses again', () => {
      render(<Lens />);
      passWeek();
      fireEvent.click(summary());
      expect(summary()).toHaveAttribute('aria-expanded', 'true');
      fireEvent.click(summary());
      expect(summary()).toHaveAttribute('aria-expanded', 'false');
    });
  });

  describe('focus', () => {
    it('never collapses under a keyboard-focused chip, and collapses on the blur', () => {
      render(<Lens />);
      const chip = chips()[3];
      act(() => chip.focus());
      passWeek();
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
      expect(document.activeElement).toBe(chip);
      act(() => chip.blur());
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
    });

    it('a pointer-focused chip (a tap on Android) does not hold the lens open, and focus moves to the summary', () => {
      keyboardFocus = false;
      render(<Lens />);
      const chip = chips()[3];
      act(() => chip.focus());
      passWeek();
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
      // Never in a hidden element, never <body>: the persistent summary takes it in the same commit.
      expect(document.activeElement).toBe(summary());
      expect(screen.getByTestId('window-first-lens-body')).toHaveAttribute('hidden');
    });

    it('a keyboard focus moving to a pointer focus lets go of the hold', () => {
      render(<Lens />);
      const [first, second] = chips();
      act(() => first.focus());
      keyboardFocus = false;
      act(() => second.focus());
      passWeek();
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
    });

    it('a focus move between two chips does not count as leaving the lens', () => {
      render(<Lens />);
      const [first, second] = chips();
      act(() => first.focus());
      passWeek();
      act(() => second.focus());
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
    });

    it('treats focus as the keyboard\'s where :focus-visible cannot be asked (jsdom)', () => {
      Element.prototype.matches.mockImplementation(function matches(selector) {
        if (selector === ':focus-visible') throw new SyntaxError('unsupported');
        return realMatches.call(this, selector);
      });
      render(<Lens />);
      act(() => chips()[0].focus());
      passWeek();
      expect(lensEl()).not.toHaveClass('wf-lens-collapsed');
    });

    it('a reset never moves focus: the summary keeps it, and stays drawn while it does', () => {
      const { rerender } = render(<Lens />);
      passWeek();
      act(() => summary().focus());
      fireEvent.click(summary());
      backInWeek();
      rerender(<Lens stuck={false} />);
      expect(document.activeElement).toBe(summary());
      expect(summary()).not.toHaveAttribute('hidden');
      act(() => summary().blur());
      expect(summary()).toHaveAttribute('hidden');
    });

    it('focusing the collapsed summary does not re-open it', () => {
      render(<Lens />);
      passWeek();
      act(() => summary().focus());
      expect(summary()).toHaveAttribute('aria-expanded', 'false');
      expect(lensEl()).toHaveClass('wf-lens-collapsed');
    });
  });
});

describe('crossing 639px (a rotation) with focus on the lens', () => {
  it('keeps the very same chips mounted, so a focused chip keeps its focus', () => {
    asPhone();
    render(<Lens />);
    const chip = chips()[2];
    act(() => chip.focus());
    flipTo(false);
    expect(screen.queryByTestId('window-first-lens-summary')).toBeNull();
    expect(chips()[2]).toBe(chip);
    expect(document.activeElement).toBe(chip);
    flipTo(true);
    expect(chips()[2]).toBe(chip);
    expect(document.activeElement).toBe(chip);
  });

  it('a summary that held focus when it leaves hands it to the first chip, never to <body>', () => {
    asPhone();
    render(<Lens />);
    passWeek();
    act(() => summary().focus());
    flipTo(false);
    expect(screen.queryByTestId('window-first-lens-summary')).toBeNull();
    expect(document.activeElement).toBe(chips()[0]);
  });

  it('does not take focus when the summary leaves without holding it', () => {
    asPhone();
    render(<Lens />);
    passWeek();
    const other = document.createElement('button');
    document.body.appendChild(other);
    act(() => other.focus());
    flipTo(false);
    expect(document.activeElement).toBe(other);
    other.remove();
  });
});

describe('--wf-bar-h and --wf-lens-h while collapsed', () => {
  beforeEach(asPhone);

  function Root(props) {
    const ref = useRef(null);
    useLensReserve(ref);
    return <div ref={ref} data-testid="root"><Lens {...props} /></div>;
  }

  it('publish the collapsed height, so the bars stick right under the one line', () => {
    render(<Root />);
    const root = screen.getByTestId('root');
    expect(root.style.getPropertyValue('--wf-bar-h')).toBe(`${FULL}px`);
    passWeek();
    remeasure();
    expect(root.style.getPropertyValue('--wf-bar-h')).toBe(`${COLLAPSED}px`);
    expect(root.style.getPropertyValue('--wf-lens-h')).toBe(`${COLLAPSED}px`);
    expect(root.style.getPropertyValue('--wf-lens-reserve')).toBe(`${COLLAPSED + 6}px`);
  });
});
