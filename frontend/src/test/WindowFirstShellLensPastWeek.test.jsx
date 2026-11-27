import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, act } from '@testing-library/react';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { lensShellCtx, stubLensObservers, renderLensShell as renderShell } from './shellLensHarness.jsx';

/**
 * The shell's half of "the lens belongs to This week" (screen-spec.md D6): the shell lifts `useLensPastWeek`, watching the
 * This week section at every width and passing `hidden` to the bar. The state machine itself is
 * `WindowFirstLensPastWeek.test.jsx`; this file pins the wiring, at 390, 768 and 1280.
 */
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

let observers;

beforeEach(() => {
  localStorage.clear();
  mockIsMobile = false;
  observers = [];
  stubLensObservers(observers);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(lensShellCtx());
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const observerOf = (testId) => observers.findLast((o) => o.element?.dataset?.testid === testId);
const report = (testId, entry) => act(() => { observerOf(testId).callback([entry]); });

describe.each([['390', true], ['768', false], ['1280', false]])('WindowFirstShell, the lens belongs to This week at %spx', (width, phone) => {
  beforeEach(() => { mockIsMobile = phone; });

  it('watches the section itself, so a jump over it is never missed (a 1px marker below the fold would be)', () => {
    renderShell();
    expect(observerOf('this-week')).toBeDefined();
    expect(screen.queryByTestId('this-week-end-sentinel')).toBeNull();
  });

  it('hides the lens once the end of the week has scrolled ABOVE the viewport, and not before it is reached', () => {
    renderShell();
    // Below the viewport (not reached yet): not intersecting, but not passed either.
    report('this-week', {
      isIntersecting: false, boundingClientRect: { top: 900, bottom: 2400 }, rootBounds: { top: 0 },
    });
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-away');
    // Past the line: not drawn (it keeps its place, `inert`).
    report('this-week', {
      isIntersecting: false, boundingClientRect: { top: -1500, bottom: -4 }, rootBounds: { top: 0 },
    });
    expect(screen.getByTestId('window-first-lens')).toHaveClass('wf-lens-away');
    expect(screen.getByTestId('window-first-lens')).toHaveAttribute('inert');
    // Scrolled back up through the week: it is drawn again.
    report('this-week', {
      isIntersecting: true, boundingClientRect: { top: -300, bottom: 1200 }, rootBounds: { top: 0 },
    });
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-away');
    expect(screen.getByTestId('window-first-lens')).not.toHaveAttribute('inert');
  });

  it('draws no summary and no spacer at any width: the one-line state is gone', () => {
    renderShell();
    report('this-week', {
      isIntersecting: false, boundingClientRect: { top: -1500, bottom: -4 }, rootBounds: { top: 0 },
    });
    expect(screen.queryByTestId('window-first-lens-summary')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-spacer')).toBeNull();
  });

  it('a pointer focus left on a chip moves to the Further ahead heading when the lens hides, never to <body>', () => {
    vi.spyOn(Element.prototype, 'matches').mockImplementation(function matches(selector) {
      if (selector === ':focus-visible') return false;
      return Element.prototype.webkitMatchesSelector.call(this, selector);
    });
    renderShell();
    act(() => screen.getByTestId('window-first-lens').querySelector('button').focus());
    report('this-week', {
      isIntersecting: false, boundingClientRect: { top: -1500, bottom: -4 }, rootBounds: { top: 0 },
    });
    expect(screen.getByTestId('window-first-lens')).toHaveClass('wf-lens-away');
    expect(document.activeElement).toBe(screen.getByTestId('further-ahead-heading'));
  });
});
