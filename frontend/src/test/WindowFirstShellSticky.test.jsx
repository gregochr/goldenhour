import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * M3's chrome model: the masthead sticks, the lens bar sticks BELOW it, and both are measured.
 *
 * <p>This is the phase's one change to a documented shell invariant — `index.css` carried
 * "`position: sticky` here and nowhere above it" beside `.wf-lens` — so what is pinned here is the
 * mechanism rather than the appearance: the two custom properties, that the reservation is their
 * SUM, that the sentinel comes and goes with the bar it watches, and that the stuck class is driven
 * by the observer rather than by a scroll position anybody guessed.
 *
 * <p>⚠️ <b>Every pixel below is a fixture, not a browser measurement.</b> jsdom lays nothing out and
 * evaluates no CSS: `getBoundingClientRect` answers zero for everything, which is why the heights
 * are stubbed by class. What that buys is the arithmetic and the wiring; what it cannot see is
 * whether the bar actually rests against the masthead's bottom edge, which is a browser claim and
 * is verified in one.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

const TODAY = '2026-08-08';

/** Heights by class, so the two measured elements can be told apart in a layout-free DOM. */
const HEIGHTS = { 'wf-mast': 118, 'wf-lens': 54 };

const ctx = () => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
  loading: false,
  heatStripCards: [],
  heatSpots: [],
  heatPointSets: new Map(),
  windowCards: [],
  paneItems: [],
  upcomingEvents: [],
  travelDayDates: new Set(),
  evaluationScores: new Map(),
  scoreIndex: new Map(),
  reachById: new Map(),
  todayStr: TODAY,
  tomorrowStr: '2026-08-09',
  homePlace: 'Newcastle',
  isPro: true,
  isLiteUser: false,
  reachLens: {
    tier: { id: '45', label: '45 min', limitMinutes: 45 },
    tierId: '45',
    defaultTier: { id: '45', label: '45 min', limitMinutes: 45 },
    defaultTierId: '45',
    weekend: false,
    overridden: false,
    locked: false,
    selectTier: vi.fn(),
    resetToDefault: vi.fn(),
  },
  ratingLens: {
    floor: { id: 'any', min: null, label: 'Any rating' },
    floorId: 'any',
    minRating: null,
    selectFloor: vi.fn(),
  },
});

/** The observers the shell installed, so a test can drive them. */
let intersections;
let resizeCallbacks;

const renderShell = (extraProps = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  return render(<WindowFirstShell
    onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()}
    locations={[]}
    {...extraProps}
  />);
};

beforeEach(() => {
  localStorage.clear();
  intersections = [];
  resizeCallbacks = [];

  // A ResizeObserver that RUNS its callback, unlike the suite-wide no-op stub — the whole point
  // here is what the measurement writes.
  vi.stubGlobal('ResizeObserver', class {
    constructor(cb) { resizeCallbacks.push(cb); }

    observe() {}

    unobserve() {}

    disconnect() {}
  });

  // jsdom has no IntersectionObserver at all, which is itself part of the contract: `useStuckSentinel`
  // must degrade to never-stuck rather than throw. Installed here so the wired path can be driven.
  vi.stubGlobal('IntersectionObserver', class {
    constructor(cb, options) {
      this.cb = cb;
      this.options = options;
      intersections.push(this);
    }

    observe() {}

    disconnect() {}
  });

  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
    const height = Object.entries(HEIGHTS)
      .find(([cls]) => this.classList?.contains(cls))?.[1] ?? 0;
    return {
      height, width: 0, top: 0, left: 0, right: 0, bottom: height, x: 0, y: 0, toJSON: () => ({}),
    };
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('WindowFirstShell — the measured sticky chrome', () => {
  it('publishes the masthead\'s height, which is what the lens bar sticks below', () => {
    renderShell();
    const shell = screen.getByTestId('window-first-shell');

    expect(shell.style.getPropertyValue('--wf-mast-h')).toBe('118px');
  });

  it('⚠️ reserves for BOTH sticky elements, not just the bar', () => {
    // A focused card now has two things over it. Reserving for the bar alone parks it behind the
    // masthead — the whole defect the reservation exists to prevent, one element higher up.
    // 118 + 54 + 6 (the focus ring's outline plus its offset).
    renderShell();

    expect(screen.getByTestId('window-first-shell').style.getPropertyValue('--wf-lens-reserve'))
      .toBe('178px');
  });

  it('drops the reservation when the bar goes, and keeps the masthead height', () => {
    // The bar is unmounted on Coming up; the masthead outlives every tab. With no bar there is
    // nothing on the pane to reserve against, and the stylesheet's own fallback is the right
    // answer — which only works if the property is REMOVED rather than frozen at its last value.
    renderShell();
    fireEvent.click(screen.getByTestId('window-first-tab-coming-up'));
    // The bar's removal is a layout change, which is what the observer on the SHELL exists to
    // catch — the hook looks the bar up on each callback rather than watching it, precisely so a
    // removal is observable. jsdom fires nothing on its own, so the callback is driven here.
    act(() => resizeCallbacks.forEach((cb) => cb()));

    const shell = screen.getByTestId('window-first-shell');
    expect(screen.queryByTestId('window-first-lens')).toBeNull();
    expect(shell.style.getPropertyValue('--wf-lens-reserve')).toBe('');
    expect(shell.style.getPropertyValue('--wf-mast-h')).toBe('118px');
  });

  it('⚠️ publishes --wf-lens-h at the bar\'s OWN height (matrix-axis plan D11(a))', () => {
    // The row-tile rail's own sticky `top` calc, alongside `--wf-mast-h` — the bar's height alone,
    // not the sum `--wf-lens-reserve` carries.
    renderShell();

    expect(screen.getByTestId('window-first-shell').style.getPropertyValue('--wf-lens-h'))
      .toBe('54px');
  });

  it('⚠️ writes --wf-lens-h as a MEASURED 0px when the bar is gone, never clears it', () => {
    // The one place this hook's clear-on-absent discipline is deliberately reversed: `-wf-lens-h`
    // has no safe fallback state the way `--wf-lens-reserve` does, because a 54px sticky `top` for a
    // rail with no bar above it floats the row-rails matrix over open space. `bar ?? 0` is still a
    // real measurement — of an absent element's rendered height — never the banned zero fallback
    // baked into the stylesheet.
    renderShell();
    fireEvent.click(screen.getByTestId('window-first-tab-coming-up'));
    act(() => resizeCallbacks.forEach((cb) => cb()));

    const shell = screen.getByTestId('window-first-shell');
    expect(screen.queryByTestId('window-first-lens')).toBeNull();
    expect(shell.style.getPropertyValue('--wf-lens-h')).toBe('0px');
  });
});

describe('WindowFirstShell — the stuck lens treatment', () => {
  it('watches a sentinel placed immediately above the bar', () => {
    renderShell();
    const sentinel = screen.getByTestId('window-first-lens-sentinel');
    const bar = screen.getByTestId('window-first-lens');

    // Immediately above: the sentinel scrolls away where the bar, being sticky, does not.
    expect(sentinel.nextElementSibling).toBe(bar);
  });

  it('unmounts the sentinel with the bar, so it cannot report a stick for an absent element', () => {
    renderShell();
    fireEvent.click(screen.getByTestId('window-first-tab-coming-up'));

    expect(screen.queryByTestId('window-first-lens-sentinel')).toBeNull();
  });

  it('⚠️ insets the observer by the MEASURED masthead height, never by zero', () => {
    // The bar's resting line is the masthead's bottom edge, not the viewport's. With a zero inset
    // the sentinel would still be "visible" for the whole height of the masthead and the shadow
    // would arrive 118px of scroll late.
    renderShell();

    const last = intersections[intersections.length - 1];
    expect(last.options.rootMargin).toBe('-118px 0px 0px 0px');
    expect(last.options.threshold).toBe(0);
  });

  /**
   * ⚠️ The defect the browser found and the suite could not: the lens bar is mounted CONDITIONALLY
   * (on a resolved reach lens), so on the shell's first commit the sentinel does not exist. With a
   * plain `useRef` the effect ran once against nothing and never re-ran — its only dependency was
   * the offset, which had already settled — so no observer was ever attached and the bar sat
   * unstuck for the whole session. Every other test in this file renders a shell whose bar is
   * present on the first commit, which is the one shape the defect does not reach.
   */
  it('⚠️ attaches the observer when the bar mounts AFTER the first commit', () => {
    const withoutLens = { ...ctx(), reachLens: null, ratingLens: null };
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(withoutLens);
    const { rerender } = render(<WindowFirstShell
      onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()}
      locations={[]}
    />);
    expect(screen.queryByTestId('window-first-lens-sentinel')).toBeNull();
    const before = intersections.length;

    briefingContext.useWindowFirstBriefing.mockReturnValue(ctx());
    rerender(<WindowFirstShell
      onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()}
      locations={[]}
    />);

    expect(screen.getByTestId('window-first-lens-sentinel')).toBeInTheDocument();
    expect(intersections.length, 'the sentinel arriving must attach an observer')
      .toBeGreaterThan(before);
    act(() => intersections[intersections.length - 1].cb([{ isIntersecting: false }]));
    expect(screen.getByTestId('window-first-lens').className).toContain('wf-lens-stuck');
  });

  it('⚠️ resets to unstuck when the bar goes, so the next one does not mount already shadowed', () => {
    renderShell();
    act(() => intersections[intersections.length - 1].cb([{ isIntersecting: false }]));
    expect(screen.getByTestId('window-first-lens').className).toContain('wf-lens-stuck');

    fireEvent.click(screen.getByTestId('window-first-tab-coming-up'));
    fireEvent.click(screen.getByTestId('window-first-tab-plan'));

    expect(screen.getByTestId('window-first-lens').className).not.toContain('wf-lens-stuck');
  });

  it('takes the stuck treatment only once the sentinel has passed', () => {
    renderShell();
    const bar = screen.getByTestId('window-first-lens');
    expect(bar.className).not.toContain('wf-lens-stuck');

    const observer = intersections[intersections.length - 1];
    act(() => observer.cb([{ isIntersecting: false }]));
    expect(screen.getByTestId('window-first-lens').className).toContain('wf-lens-stuck');

    act(() => observer.cb([{ isIntersecting: true }]));
    expect(screen.getByTestId('window-first-lens').className).not.toContain('wf-lens-stuck');
  });

  it('degrades to never-stuck where there is no IntersectionObserver at all', () => {
    // An old browser, and jsdom by default. A missing shadow costs a reader nothing; a permanent
    // one over an unscrolled page would be a lie about where they are.
    vi.stubGlobal('IntersectionObserver', undefined);
    renderShell();

    expect(screen.getByTestId('window-first-lens').className).not.toContain('wf-lens-stuck');
  });
});

describe('the stylesheet half, which jsdom cannot evaluate', () => {
  /**
   * Read as text, because `css: false` and jsdom resolves no `var()`. These three are the claims
   * the JavaScript above cannot reach: that the masthead is sticky at all, that the bar rests on
   * the published property rather than on `top: 0`, and — the one this phase promised — that the
   * comment which said sticky happened "nowhere above" the bar has been corrected in the same
   * commit rather than left to rot.
   */
  // ⚠️ Resolved from the runner's cwd (the `frontend` package root), NOT from `import.meta.url`.
  // MEASURED, not reasoned: in this file `new URL('../index.css', import.meta.url)` evaluates to
  // `http://localhost:3000/src/index.css`, which `readFileSync` rejects with "The URL must be of
  // scheme file". Two sibling guards (`mastheadColours.test.js`, `MastheadTickLine.test.jsx`) use
  // the URL form and are green, so whatever the cause it is not uniform across this suite — which
  // is why this note records the observation rather than a diagnosis, and why the cwd form stays.
  const readCss = () => readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
  const block = (selector) => {
    const css = readCss();
    const at = css.indexOf(`\n${selector} {`);
    expect(at, `${selector} must exist`).toBeGreaterThan(-1);
    return css.slice(at, css.indexOf('\n}', at));
  };

  it('sticks the masthead above the bar, at the design\'s z-index', () => {
    const mast = block('.wf-mast');
    expect(mast).toContain('position: sticky');
    expect(mast).toContain('top: 0');
    expect(mast).toContain('z-index: 45');
  });

  it('rests the bar on the measured masthead height rather than on the viewport top', () => {
    expect(block('.wf-lens')).toContain('top: var(--wf-mast-h, 128px)');
  });

  it('⚠️ never falls back to `top: 0`, which would hide the bar UNDER the masthead', () => {
    // `.wf-mast` is z-45 and `.wf-lens` is 20, so a bar resting at 0 sits beneath it: the page's
    // only reach and rating controls, and the only way out of a lens that has emptied the plan,
    // become invisible and unclickable. `top: 0` was the CORRECT resting position before M3, which
    // is exactly why it survived the first cut as a fallback.
    const css = readCss();
    expect(css).not.toContain('--wf-mast-h, 0px');
    expect(block('.wf-shell')).toMatch(/--wf-mast-h:\s*\d+px/);
  });

  it('reserves for the whole sticky stack at first paint too, not just the bar', () => {
    // The runtime value is masthead + bar + ring; a fallback still describing the bar alone would
    // under-reserve by the masthead's full height in the one state it exists for.
    expect(block('.wf-shell')).toContain('--wf-lens-reserve: 188px');
    expect(readCss()).toContain('--wf-lens-reserve: 270px');
  });

  it('reserves for the matrix cards and the tab bar, which are the pane\'s primary controls', () => {
    // `.wf-hc` is six focusable cards, any of which can sit above the fold; `.wf-tab`'s roving
    // arrow handler calls `scrollIntoView`. Neither was on the list, and the cost went from 53.5px
    // to ~180px when the masthead started sticking.
    // The pane's own list, which is the LAST `scroll-margin-top` rule in the file — the earlier one
    // is `.wf-door-panel`'s descendant rule for the re-parented components it hosts, none of which
    // carry a `.wf-` class of their own. Sliced back from the declaration to the previous rule's
    // closing brace, so what is searched is that rule's selector list (and the comments interleaved
    // in it) and nothing else.
    const css = readCss();
    const at = css.lastIndexOf('scroll-margin-top: var(--wf-lens-reserve');
    const selectors = css.slice(css.lastIndexOf('\n}', at), at);
    expect(selectors).toContain('.wf-hc,');
    expect(selectors).toContain('.wf-tab,');
    // …and it really is the list, not a comment that happens to name them.
    expect(selectors).toContain('.wf-spot,');
  });

  it('keeps the bar UNDER the masthead in the stacking order', () => {
    // 20 < 45, and the pair is the whole point: a bar that slid over the masthead would cover the
    // origin control and the search affordance the tick line exists to keep reachable.
    expect(block('.wf-lens')).toContain('z-index: 20');
  });

  it('⚠️ no longer claims sticky happens nowhere above the bar', () => {
    // The stale-invariant guard. The claim was true when it was written and M3 falsified it; a
    // comment asserting it must not survive the commit that made it wrong.
    expect(readCss()).not.toContain('`position: sticky` here and nowhere above it');
  });
});

describe('the row-rails stylesheet half (matrix-axis plan §6, D6/D8/D9/D10/D12)', () => {
  const readCss = () => readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
  const block = (selector) => {
    const css = readCss();
    const at = css.indexOf(`\n${selector} {`);
    expect(at, `${selector} must exist`).toBeGreaterThan(-1);
    return css.slice(at, css.indexOf('\n}', at));
  };

  it('sticks the day-tile row under the masthead + lens bar, at the design\'s z-index', () => {
    const dhrow = block('.wf-dhrow');
    expect(dhrow).toContain('position: sticky');
    expect(dhrow).toContain('top: calc(var(--wf-mast-h, 128px) + var(--wf-lens-h, 54px) - 1px)');
    expect(dhrow).toContain('z-index: 15');
    expect(dhrow).toContain('background: linear-gradient(180deg, var(--color-plex-bg)');
  });

  it('sticks the rail under the day-tile row, at the design\'s z-index', () => {
    const rail = block('.wf-rail');
    // Omit `position: sticky` and `top`/`z-index` are inert — every other assertion here would
    // still pass against a rail that never actually sticks.
    expect(rail).toContain('position: sticky');
    expect(rail).toContain('top: calc(var(--wf-mast-h, 128px) + var(--wf-lens-h, 54px) + var(--wf-dh-h, 45px) - 2px)');
    expect(rail).toContain('z-index: 14');
    expect(rail).toContain('background: linear-gradient(180deg, var(--color-plex-bg)');
  });

  it('gives the rows-mode grids the same overflow-safe track string as the base grid', () => {
    // ⚠️ Scoped to the NEW rule's own block, not a whole-file `toContain` — the base `.wf-hstrip`
    // grid (untouched, D2) already carries this exact string, so an unscoped assertion would keep
    // passing even if the new rows-mode rule regressed to a bare `1fr` (the overflow this rule
    // exists to prevent, per its own CSS comment).
    const css = readCss();
    const start = css.indexOf(
      '.wf-hstrip.wf-hstrip-rows .wf-dhrow,\n.wf-hstrip.wf-hstrip-rows .wf-hcards {',
    );
    expect(start, 'the rows-mode grid rule must exist').toBeGreaterThan(-1);
    const rowsGridBlock = css.slice(start, css.indexOf('\n}', start));
    expect(rowsGridBlock).toContain('repeat(var(--dc, 4), minmax(0, 1fr))');
  });

  it('reserves scroll room for a card under the pinned tiles + rail, in full', () => {
    // Existence alone would pass at a truncated string that happened to add up to 0.
    expect(readCss()).toContain(
      'scroll-margin-top: calc(var(--wf-lens-reserve, 188px) + var(--wf-dh-h, 45px) + 17px)',
    );
  });

  it('never falls back either new property to a bare zero', () => {
    const css = readCss();
    expect(css).not.toContain('--wf-lens-h, 0');
    expect(css).not.toContain('--wf-dh-h, 0');
  });

  it('leaves the existing sticky chrome assertions untouched', () => {
    // The pre-existing lens z-index and mast-height fallback, and the base scroll-margin selector
    // list this describe block's own sibling already pins — additive rules must not disturb them.
    expect(block('.wf-lens')).toContain('z-index: 20');
    expect(block('.wf-shell')).toMatch(/--wf-mast-h:\s*\d+px/);
    const css = readCss();
    const at = css.lastIndexOf('scroll-margin-top: var(--wf-lens-reserve');
    const selectors = css.slice(css.lastIndexOf('\n}', at), at);
    expect(selectors).toContain('.wf-hc,');
  });
});
