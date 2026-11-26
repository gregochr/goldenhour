import React, { useCallback, useRef } from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, within } from '@testing-library/react';
import WindowFirstLensBar from '../components/WindowFirstLensBar.jsx';
import useRatingLens from '../hooks/useRatingLens.js';
import useReachLens from '../hooks/useReachLens.js';
import useLensReserve from '../hooks/useLensReserve.js';
import useLensPastWeek from '../hooks/useLensPastWeek.js';
import { mockOpenGuide } from './guideContextMock.js';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

/**
 * The lens belongs to This week (screen-spec.md D6, `useLensPastWeek`; lens-this-week-only-plan.md).
 *
 * <p>jsdom evaluates no CSS and has no layout, so the lens height is a stub per width and the IntersectionObserver is driven
 * by hand. What is asserted is the state machine (hidden, inert, the focus rules), the numbers the reserve hook publishes
 * (the shared three keep the full lens; Further ahead's pair drops it) and the observer's line. That a stuck lens hidden
 * with `visibility` moves nothing in the page, and where the Further ahead jump lands, are browser measurements, taken in
 * `lens-collapse.spec.js`.
 */
const TUESDAY = '2026-08-04';
const AHEAD_BAR = 30;
const WIDTHS = [['phone, 390', 390, 90, true], ['tablet, 768', 768, 54, false], ['desktop, 1280', 1280, 54, false]];

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

let lensHeight;
let resizeObservers;
let intersectionObservers;
let keyboardFocus;
let week;
let heading;
let realMatches;

beforeEach(() => {
  localStorage.clear();
  mockOpenGuide.mockClear?.();
  phoneListeners = [];
  lensHeight = 90;
  resizeObservers = [];
  intersectionObservers = [];
  keyboardFocus = true;
  week = document.createElement('section');
  document.body.appendChild(week);
  heading = document.createElement('h2');
  heading.tabIndex = -1;
  heading.id = 'ahead-heading';
  document.body.appendChild(heading);
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
    if (this.classList?.contains('wf-lens')) h = lensHeight;
    else if (this.classList?.contains('wf-sec-bar-ahead')) h = AHEAD_BAR;
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
  heading.remove();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** The shell's wiring in miniature: the lifted hook, the bar, the reserve hook, and Further ahead's bar to measure. */
function Host({ verdict = null, guess = 90, getFocusTarget = undefined }) {
  const ref = useRef(null);
  const lens = useReachLens(TUESDAY, false);
  const ratingLens = useRatingLens();
  const defaultTarget = useCallback(() => document.getElementById('ahead-heading'), []);
  const past = useLensPastWeek({
    enabled: !verdict, weekNode: week, guessHeight: guess, getFocusTarget: getFocusTarget ?? defaultTarget,
  });
  useLensReserve(ref, { hidden: !verdict });
  return (
    <div ref={ref} data-testid="root">
      <WindowFirstLensBar
        lens={lens}
        ratingLens={ratingLens}
        spotCount={6}
        reachedCount={6}
        windowCount={4}
        stuck
        hidden={past.hidden}
        attachLens={past.attachLens}
        focusProps={past.focusProps}
        verdict={verdict}
      />
      <div className="wf-sec-bar-ahead" />
    </div>
  );
}

const weekObserver = () => intersectionObservers.findLast((o) => o.element === week);
const lineOf = (observer) => Number.parseInt(observer.options.rootMargin, 10) * -1;
/** Reports This week as scrolled past the observer's line / back above it. */
const passWeek = () => act(() => {
  const observer = weekObserver();
  observer.callback([{ isIntersecting: false, boundingClientRect: { bottom: lineOf(observer) - 3 }, rootBounds: { top: lineOf(observer) } }]);
});
const backInWeek = () => act(() => {
  const observer = weekObserver();
  observer.callback([{ isIntersecting: true, boundingClientRect: { bottom: lineOf(observer) + 300 }, rootBounds: { top: lineOf(observer) } }]);
});

const lensEl = () => screen.getByTestId('window-first-lens');
const rootEl = () => screen.getByTestId('root');
const chips = () => within(screen.getByTestId('window-first-lens-tiers')).getAllByRole('button');
const prop = (name) => rootEl().style.getPropertyValue(name);
const VERDICT = {
  lead: 'Clash', text: 'Nothing fits', action: null, announce: 'Clash. Nothing fits',
};

describe.each(WIDTHS)('the lens past This week at %s', (label, width, height, phone) => {
  beforeEach(() => {
    lensHeight = height;
    setMatches(phone);
  });

  it('is drawn at rest, with no summary, no spacer and no body wrapper', () => {
    render(<Host guess={height} />);
    expect(lensEl()).not.toHaveClass('wf-lens-away');
    expect(lensEl()).not.toHaveAttribute('inert');
    expect(screen.queryByTestId('window-first-lens-summary')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-spacer')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-body')).toBeNull();
    expect(chips().length).toBeGreaterThan(0);
  });

  it('is hidden and inert once This week has scrolled past, and drawn again when it is back', () => {
    render(<Host guess={height} />);
    passWeek();
    expect(lensEl()).toHaveClass('wf-lens-away');
    expect(lensEl()).toHaveAttribute('inert');
    // Hidden by class (visibility), never unmounted: the sticky slot and the controls stay.
    expect(chips().length).toBeGreaterThan(0);
    backInWeek();
    expect(lensEl()).not.toHaveClass('wf-lens-away');
    expect(lensEl()).not.toHaveAttribute('inert');
  });

  it(`measures the line from the lens's own height (${height}px), at every width, with hysteresis`, () => {
    render(<Host guess={200} />);
    // The guess (200) is replaced by the measurement on attach: the line is 16px below the measured edge, minus 8 until past.
    expect(lineOf(weekObserver())).toBe(height + 16 - 8);
    passWeek();
    expect(lineOf(weekObserver())).toBe(height + 16);
    backInWeek();
    expect(lineOf(weekObserver())).toBe(height + 16 - 8);
  });

  it('keeps the shared variables at the full lens while hidden, and drops the lens only from Further ahead\'s pair', () => {
    render(<Host guess={height} />);
    expect(prop('--wf-bar-h')).toBe(`${height}px`);
    expect(prop('--wf-lens-h')).toBe(`${height}px`);
    expect(prop('--wf-lens-reserve')).toBe(`${height + 6}px`);
    expect(prop('--wf-ahead-top')).toBe('0px');
    expect(prop('--wf-ahead-reserve')).toBe(`${AHEAD_BAR + 6}px`);
    passWeek();
    act(() => resizeObservers.forEach((o) => o.callback()));
    expect(lensEl()).toHaveClass('wf-lens-away');
    // The This week jump reads these: they must still be the full lens, or its target lands under a lens that returns.
    expect(prop('--wf-bar-h')).toBe(`${height}px`);
    expect(prop('--wf-lens-h')).toBe(`${height}px`);
    expect(prop('--wf-lens-reserve')).toBe(`${height + 6}px`);
    expect(prop('--wf-ahead-top')).toBe('0px');
    expect(prop('--wf-ahead-reserve')).toBe(`${AHEAD_BAR + 6}px`);
  });

  it(`keeps the very same chips mounted when the viewport crosses 639px (${width}px)`, () => {
    render(<Host guess={height} />);
    const chip = chips()[0];
    act(() => phoneListeners.forEach((handler) => handler({ matches: !phone })));
    expect(chips()[0]).toBe(chip);
  });
});

describe('the hysteresis band', () => {
  beforeEach(() => setMatches(true));

  it('ignores a section merely below the line, and one with no geometry', () => {
    render(<Host />);
    act(() => weekObserver().callback([{
      isIntersecting: false, boundingClientRect: { bottom: 1500 }, rootBounds: { top: 100 },
    }]));
    expect(lensEl()).not.toHaveClass('wf-lens-away');
    act(() => weekObserver().callback([{ isIntersecting: false }]));
    expect(lensEl()).not.toHaveClass('wf-lens-away');
  });

  it('does not flip inside the band: the end of the week between the two lines leaves the lens as it was', () => {
    render(<Host />);
    // Not yet past: the observer sits 8px above the line, and a report inside the band (not past the line) hides nothing.
    act(() => weekObserver().callback([{
      isIntersecting: false, boundingClientRect: { bottom: 90 + 16 + 4 }, rootBounds: { top: 90 + 16 - 8 },
    }]));
    expect(lensEl()).not.toHaveClass('wf-lens-away');
    passWeek();
    expect(lineOf(weekObserver())).toBe(90 + 16);
    expect(lensEl()).toHaveClass('wf-lens-away');
  });

  it('works without an IntersectionObserver (a very old browser): it never hides on its own', () => {
    vi.stubGlobal('IntersectionObserver', undefined);
    render(<Host />);
    expect(lensEl()).not.toHaveClass('wf-lens-away');
  });

  it('works without a ResizeObserver: the lens is still measured once, from the element', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    lensHeight = 70;
    render(<Host />);
    expect(lineOf(weekObserver())).toBe(70 + 16 - 8);
  });

  it('keeps the guess while the lens measures nothing', () => {
    lensHeight = 0;
    render(<Host guess={94} />);
    expect(lineOf(weekObserver())).toBe(94 + 16 - 8);
  });

  it('re-measures when the lens resizes (a wrapped mark)', () => {
    render(<Host />);
    lensHeight = 120;
    act(() => resizeObservers.forEach((o) => o.callback()));
    expect(lineOf(weekObserver())).toBe(120 + 16 - 8);
  });
});

describe('the plan-conflict verdict', () => {
  beforeEach(() => setMatches(true));

  it('is never hidden: the lens is not watched while a verdict shows, and its status line announces', () => {
    render(<Host verdict={VERDICT} />);
    expect(weekObserver()).toBeUndefined();
    expect(lensEl()).not.toHaveClass('wf-lens-away');
    expect(screen.getByTestId('window-first-lens-verdict-status')).toHaveTextContent('Clash. Nothing fits');
  });

  it('a verdict that ARRIVES past This week brings the lens back, and its end lets it go again', () => {
    const { rerender } = render(<Host />);
    passWeek();
    expect(lensEl()).toHaveClass('wf-lens-away');
    rerender(<Host verdict={VERDICT} />);
    expect(lensEl()).not.toHaveClass('wf-lens-away');
    expect(lensEl()).not.toHaveAttribute('inert');
    rerender(<Host />);
    passWeek();
    expect(lensEl()).toHaveClass('wf-lens-away');
  });

  it('puts the lens term back into Further ahead\'s pair while the verdict holds the lens drawn over it', () => {
    const { rerender } = render(<Host />);
    expect(prop('--wf-ahead-top')).toBe('0px');
    rerender(<Host verdict={VERDICT} />);
    expect(prop('--wf-ahead-top')).toBe('90px');
    expect(prop('--wf-ahead-reserve')).toBe(`${90 + AHEAD_BAR + 6}px`);
  });
});

describe('focus', () => {
  beforeEach(() => setMatches(true));

  it('keyboard focus in a chip holds the lens drawn, and the blur hides it', () => {
    render(<Host />);
    act(() => chips()[0].focus());
    passWeek();
    expect(lensEl()).not.toHaveClass('wf-lens-away');
    act(() => chips()[0].blur());
    expect(lensEl()).toHaveClass('wf-lens-away');
  });

  it('a focus move between two chips does not count as leaving the lens', () => {
    render(<Host />);
    act(() => chips()[0].focus());
    passWeek();
    act(() => chips()[1].focus());
    expect(lensEl()).not.toHaveClass('wf-lens-away');
  });

  it('a POINTER focus (a tap on Android) does not hold it: focus moves to the Further ahead heading, never <body>', () => {
    keyboardFocus = false;
    render(<Host />);
    act(() => chips()[0].focus());
    expect(document.activeElement).toBe(chips()[0]);
    const focus = vi.spyOn(heading, 'focus');
    passWeek();
    expect(lensEl()).toHaveClass('wf-lens-away');
    expect(focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(document.activeElement).toBe(heading);
  });

  it('with no heading to take it, a pointer focus is released rather than left inside the inert lens', () => {
    keyboardFocus = false;
    render(<Host getFocusTarget={() => null} />);
    act(() => chips()[0].focus());
    passWeek();
    expect(lensEl()).toHaveClass('wf-lens-away');
    expect(lensEl().contains(document.activeElement)).toBe(false);
  });

  it('does not take focus when it hides with focus elsewhere', () => {
    render(<Host />);
    const other = document.createElement('button');
    document.body.appendChild(other);
    act(() => other.focus());
    passWeek();
    expect(document.activeElement).toBe(other);
    other.remove();
  });

  it('a keyboard focus moving to a pointer focus lets go of the hold', () => {
    render(<Host />);
    act(() => chips()[0].focus());
    passWeek();
    keyboardFocus = false;
    act(() => chips()[1].focus());
    expect(lensEl()).toHaveClass('wf-lens-away');
  });

  it('treats focus as the keyboard\'s where :focus-visible cannot be asked (jsdom)', () => {
    Element.prototype.matches.mockImplementation(function matches(selector) {
      if (selector === ':focus-visible') throw new SyntaxError('unsupported');
      return realMatches.call(this, selector);
    });
    keyboardFocus = false;
    render(<Host />);
    act(() => chips()[0].focus());
    passWeek();
    expect(lensEl()).not.toHaveClass('wf-lens-away');
  });
});
