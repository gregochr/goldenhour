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
 * The chrome model: the lens bar is the pane's ONE sticky element and it anchors at the top of the
 * viewport. What is pinned here is the mechanism rather than the appearance — the two custom
 * properties, that the reservation is the bar plus a focus ring and nothing else, that the sentinel
 * comes and goes with the bar it watches, and that the stuck class is driven by the observer rather
 * than by a scroll position anybody guessed.
 *
 * <h2>⚠️ M3's model was the opposite of this, and it never actually ran</h2>
 *
 * <p>M3 made `.wf-mast` sticky as well and rested the bar on its measured height, so this file used
 * to pin the reverse of nearly every assertion below: a sticky masthead, `top: var(--wf-mast-h)`, a
 * summed reservation, an observer inset by the masthead. None of it happened in a browser. A sticky
 * element cannot travel outside its own containing block, and the masthead's is
 * `WindowFirstShell`'s `WRAP_MAX_WIDTH` wrapper — masthead, tab bar, tab rule, about 46px taller
 * than the band itself. Measured in Chromium at 1280×800: the band pins for those 46px and is then
 * carried off the top with the page (`bottom: -397` by 600px of scroll), while the bar went on
 * sticking a masthead's height down the viewport with matrix cards scrolling through the naked band
 * above it.
 *
 * <p>That is why the guards below are written as they are. The `.wf-mast` one asserts an ABSENCE,
 * which is normally a weak shape; here it is the only shape that works, because the defect was a
 * rule that looked correct in the stylesheet and did nothing on the page.
 *
 * <p>⚠️ <b>Every pixel below is a fixture, not a browser measurement.</b> jsdom lays nothing out and
 * evaluates no CSS: `getBoundingClientRect` answers zero for everything, which is why the heights
 * are stubbed by class. What that buys is the arithmetic and the wiring; what it cannot see is
 * where the bar actually lands, which is a browser claim and is verified in one.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

/**
 * The stylesheet readers, shared by the two text-reading describes below.
 *
 * <p>⚠️ Resolved from the runner's cwd (the `frontend` package root), NOT from `import.meta.url`.
 * MEASURED, not reasoned: in this file `new URL('../index.css', import.meta.url)` evaluates to
 * `http://localhost:3000/src/index.css`, which `readFileSync` rejects with "The URL must be of
 * scheme file". Two sibling guards (`mastheadColours.test.js`, `MastheadTickLine.test.jsx`) use the
 * URL form and are green, so whatever the cause it is not uniform across this suite — which is why
 * this note records the observation rather than a diagnosis, and why the cwd form stays.
 *
 * <p>Declared once at module scope rather than per describe: they were copied verbatim into both,
 * six-line doc comment included, and two copies of a comment-stripping helper diverge.
 */
const readCss = () => readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');

/** One rule's text, comments and all, from its selector to its closing brace. */
const block = (selector) => {
  const css = readCss();
  const at = css.indexOf(`\n${selector} {`);
  expect(at, `${selector} must exist`).toBeGreaterThan(-1);
  return css.slice(at, css.indexOf('\n}', at));
};

/**
 * Declarations only — required by every ABSENCE assertion below, and not a tidying. `.wf-mast`'s own
 * comment explains at length why `position: sticky` is gone from it and what a phase restoring it
 * has to do; a bare `not.toContain('position: sticky')` over the raw block fails on that
 * explanation. Stripping comments is what lets a guard say "this rule does not stick" without also
 * forbidding the file from saying why — and it is what stops a comment quoting a declaration from
 * standing in for the declaration itself.
 */
const decls = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '');

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
  it('⚠️ reserves for the BAR alone — the masthead is not pinned and is not counted', () => {
    // 54 + 6 (the focus ring's outline plus its offset), and NOT 118 + 54 + 6. The masthead's
    // height was in this sum for as long as `.wf-mast` carried a `position: sticky`; that stick was
    // trapped in a containing block barely taller than itself, so the term reserved against chrome
    // that had already scrolled away and dropped every focused card 118px further down than it
    // needed to go.
    renderShell();

    expect(screen.getByTestId('window-first-shell').style.getPropertyValue('--wf-lens-reserve'))
      .toBe('60px');
  });

  it('⚠️ publishes no masthead height at all — nothing above the bar is pinned', () => {
    // The property is deleted rather than left unread: three rules derived a sticky `top` or a
    // reservation from it, and a live measurement of an element that does not pin is exactly what
    // invites the fourth. `index.css`'s `.wf-mast` carries the checklist for putting it back.
    renderShell();

    expect(screen.getByTestId('window-first-shell').style.getPropertyValue('--wf-mast-h')).toBe('');
  });

  it('drops the reservation when the bar goes', () => {
    // The bar is unmounted on Coming up. With no bar there is nothing on the pane to reserve
    // against at all, and the stylesheet's own fallback is the right answer — which only works if
    // the property is REMOVED rather than frozen at its last value.
    renderShell();
    fireEvent.click(screen.getByTestId('window-first-tab-coming-up'));
    // The bar's removal is a layout change, which is what the observer on the SHELL exists to
    // catch — the hook looks the bar up on each callback rather than watching it, precisely so a
    // removal is observable. jsdom fires nothing on its own, so the callback is driven here.
    act(() => resizeCallbacks.forEach((cb) => cb()));

    const shell = screen.getByTestId('window-first-shell');
    expect(screen.queryByTestId('window-first-lens')).toBeNull();
    expect(shell.style.getPropertyValue('--wf-lens-reserve')).toBe('');
  });

  it('⚠️ publishes --wf-lens-h at the bar\'s OWN height (matrix-axis plan D11(a))', () => {
    // The whole of the row-tile rail's own sticky `top` calc — the bar's height alone, without the
    // focus ring's allowance that `--wf-lens-reserve` carries. The two are the same measurement and
    // must stay two properties: a rail inset by the ring would leave a 6px gap under the bar.
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

  it('⚠️ insets the observer by ZERO, because the bar rests at the viewport top', () => {
    // The inverse of what M3 pinned here. The bar's resting line WAS the masthead's bottom edge, on
    // a model where the masthead pinned; it never did, and the bar anchors at `top: 0` now, so the
    // line is the viewport's own top. An inset of the masthead's 118px would hold the shadow back
    // until the reader had scrolled a masthead's height past the point the bar actually stuck.
    renderShell();

    const last = intersections[intersections.length - 1];
    expect(last.options.rootMargin).toBe('-0px 0px 0px 0px');
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
   * Read as text, because `css: false` and jsdom resolves no `var()`. These are the claims the
   * JavaScript above cannot reach: that the masthead is NOT sticky, that the bar anchors at the
   * viewport's own top edge, that the two row rails no longer add a masthead term to their sticky
   * `top`, and that every fallback literal moved with the variable it stands in for.
   */

  it('⚠️ does NOT stick the masthead — the rule never worked and must not come back alone', () => {
    // An absence, deliberately. `position: sticky; top: 0` sat here from M3 until the anchoring fix
    // and did nothing but hold the band for the tab bar's ~46px: a sticky element cannot leave its
    // containing block, and this one's holds the masthead, the tab bar and the tab rule and nothing
    // else. Re-adding the stick alone would put a pinned band over a bar resting at `top: 0`.
    const mast = decls(block('.wf-mast'));
    expect(mast).not.toContain('position: sticky');
    expect(mast).not.toContain('top:');
  });

  it('⚠️ KEEPS the masthead a stacking context, or the admin health panel escapes every dialog',
    () => {
      // The regression the first cut of the anchoring fix shipped, found by adversarial review and
      // reproduced in Chromium. `HealthIndicator` renders inside this band and its panel is
      // `position: fixed; z-index: 9999`; that only ever composited below a dialog because a
      // positioned, non-auto-`z-index` masthead was a ceiling over its own subtree. Strip the pair
      // and nothing between the band and the root makes a context, so the panel paints over the
      // search dialog's scrim — hit-testable, while an `aria-modal` dialog is open.
      //
      // Both halves are asserted because either alone is useless: `z-index` is inert on a static
      // box, and `position: relative` with no z-index creates no context. `isolation: isolate` is
      // deliberately NOT accepted here — it would make the context at `z-auto`, below `.wf-lens`'s
      // 20, putting the panel under the bar it drops across.
      const mast = decls(block('.wf-mast'));
      expect(mast).toContain('position: relative');
      expect(mast).toMatch(/z-index:\s*45\b/);
    });

  it('⚠️ actually STICKS the bar — the declaration everything else here is inert without', () => {
    // This file asserted `top`, `z-index` and two absences on `.wf-lens` and never once that it is
    // sticky at all. Drop `position: sticky` and every one of those still passes while the bar
    // scrolls away with the matrix, taking the page's only reach and rating controls and the only
    // exit from an emptied lens with it — and the stuck shadow still fires, because
    // `useStuckSentinel` watches a sentinel rather than the bar, so it would sit mid-page under a
    // permanent drop shadow. `.wf-dhrow` and `.wf-rail` both carry this assertion for exactly that
    // reason (`.wf-rail`'s own note spells it out); the element they are numbered against did not.
    expect(decls(block('.wf-lens'))).toContain('position: sticky');
  });

  it('anchors the bar at the top of the viewport, not below a masthead that never pinned', () => {
    // The literal is the point. `top: var(--wf-mast-h, …)` hung the bar a masthead's height down
    // the viewport with matrix cards scrolling through the band above it — the "floating" defect.
    //
    // ⚠️ It reads `env(safe-area-inset-top, 0px)` rather than the bare `top: 0` this asserted until
    // safe-area handling landed, and that is the SAME anchor, not a relaxation of it. The `0px`
    // fallback makes it exactly `top: 0` on every surface reporting no inset — which is all of them
    // but a notched iOS device with the status-bar opt-in — and on one that does report an inset,
    // the viewport's top edge is under the status bar and this is where the bar's top edge belongs.
    // A sticky element sticks to its SCROLLPORT, so the app root's own safe-area padding cannot
    // reach it: this bar is the one piece of Plan chrome that has to name the inset itself. What
    // must never come back is a top derived from chrome that does not pin, and the second
    // assertion still guards exactly that.
    expect(decls(block('.wf-lens'))).toContain('top: env(safe-area-inset-top, 0px)');
    expect(decls(block('.wf-lens'))).not.toContain('--wf-mast-h');
  });

  it('⚠️ publishes no --wf-mast-h anywhere, so no rule can reserve against unpinned chrome', () => {
    // Three rules derived a sticky `top` or a `scroll-margin-top` from it and all three were wrong
    // by the masthead's full height. Scoped to DECLARATIONS and `var()` reads: the comments that
    // explain the removal name the property, and must be free to.
    const css = decls(readCss());
    expect(css).not.toMatch(/--wf-mast-h:/);
    expect(css).not.toContain('var(--wf-mast-h');
  });

  it('reserves for the bar and the focus ring at first paint too, and nothing else', () => {
    // The runtime value is bar + ring; a fallback still carrying M3's masthead term would drop a
    // focused card 128px further than it needs to go in the one state it exists for. 98 = the bar's
    // WRAPPED 92px + the ring, not its resting 53.5 — see the declaration's own note for why the
    // resting basis was an under-reservation every phase since M1 had carried.
    expect(block('.wf-shell')).toContain('--wf-lens-reserve: 98px');
  });

  it('⚠️ scopes the phone value to the phone, not merely to the file', () => {
    // A whole-file `toContain` was the first shape here and it cannot see a breakpoint sweep: move
    // this declaration into the adjacent `max-width: 899px` block and the string is still present,
    // the test still passes, and every phone falls back to the desktop 98px against a bar that is
    // 130-153.5px tall there. 160 = the stacked bar's 153.5px with a non-default tier's two marks,
    // plus the ring — the state a returning reader gets on the FIRST paint, since `useReachLens`
    // restores the tier from `localStorage` in a lazy initialiser.
    const css = readCss();
    const at = css.indexOf('@media (max-width: 639px) {');
    expect(at, 'the phone media query must exist').toBeGreaterThan(-1);
    const phoneShell = css.slice(at, css.indexOf('\n  }', at));
    expect(phoneShell).toContain('--wf-lens-reserve: 160px');
  });

  it('⚠️ keeps EVERY fallback site equal to the declaration, whatever the site count', () => {
    // Not a count. `toHaveLength(3)` was the first shape and fails both ways: a fourth legitimate
    // consumer reds the build with a message naming no rule, and DELETING `.wf-door-panel`'s rule
    // while documenting the removal in a comment that quotes the string keeps the count at three —
    // which is precisely how every other removal in this change is recorded, and precisely the site
    // whose drift the file already records once (missed at M3, corrected at M5). Read from
    // declarations only, so a comment quoting the string can neither satisfy nor break it.
    const fallbacks = [...decls(readCss()).matchAll(/var\(--wf-lens-reserve,\s*([^)]+)\)/g)]
      .map((m) => m[1].trim());
    expect(fallbacks.length, 'the fallback sites must exist, or this proves nothing')
      .toBeGreaterThan(0);
    fallbacks.forEach((value) => expect(value).toBe('98px'));
  });

  it('reserves for the matrix cards and the tab bar, which are the pane\'s primary controls', () => {
    // `.wf-hc` is six focusable cards, any of which can sit above the fold; `.wf-tab`'s roving
    // arrow handler calls `scrollIntoView`. Neither was on the list when the reservation was a
    // literal.
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

  it('keeps the bar above the pane it scrolls over', () => {
    // 20 beats the spot strip's focused card (3) and its edge fades (2), the only things on the
    // pane that raise themselves, and the tiles (15) and rails (14) are numbered against it. Its
    // old CEILING — the masthead's 45 — is gone with the masthead's `position`, since `z-index` is
    // inert on a static box.
    expect(block('.wf-lens')).toContain('z-index: 20');
  });

  it('⚠️ keeps the masthead BELOW every dialog, which is what its 45 is for now', () => {
    // The stale-invariant guard used to live here as two absence checks on exact prose sentences the
    // same commit deleted — unfailable by construction, and so protecting nothing (the standards'
    // own rule). The falsifiable claim in its place is the ordering the band's stacking context
    // exists to impose: 45 must stay under `Modal`'s Tailwind `z-50`, `.wf-peek`'s 60 and
    // `MapOverlay`'s 200, or clamping the health panel to it stops being a fix. `.wf-peek` is the
    // one of the three declared in this stylesheet, so it is the one that can drift here.
    const peek = decls(block('.wf-peek'));
    const mastZ = Number(decls(block('.wf-mast')).match(/z-index:\s*(\d+)/)[1]);
    const peekZ = Number(peek.match(/z-index:\s*(\d+)/)[1]);
    expect(mastZ).toBeLessThan(peekZ);
    expect(mastZ).toBeLessThan(50);
  });
});

describe('the row-rails stylesheet half (matrix-axis plan §6, D6/D8/D9/D10/D12)', () => {
  it('sticks the day-tile row directly under the lens bar, at the design\'s z-index', () => {
    // ⚠️ `--wf-lens-h` ALONE. The masthead term hung this row a masthead's height below the bar it
    // is supposed to sit against, with cards scrolling through the gap — the same defect the bar
    // itself had, one level down.
    const dhrow = block('.wf-dhrow');
    expect(dhrow).toContain('position: sticky');
    expect(dhrow).toContain('top: calc(var(--wf-lens-h, 54px) - 1px)');
    expect(dhrow).toContain('z-index: 15');
    expect(dhrow).toContain('background: linear-gradient(180deg, var(--color-plex-bg)');
  });

  it('sticks the rail under the day-tile row, at the design\'s z-index', () => {
    const rail = block('.wf-rail');
    // Omit `position: sticky` and `top`/`z-index` are inert — every other assertion here would
    // still pass against a rail that never actually sticks.
    expect(rail).toContain('position: sticky');
    expect(rail).toContain('top: calc(var(--wf-lens-h, 54px) + var(--wf-dh-h, 45px) - 2px)');
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
    // Existence alone would pass at a truncated string that happened to add up to 0. The
    // reservation's own fallback came back from 188 to 60 with the masthead's dead sticky; this
    // site has to move with the declaration or it under-reserves at first paint.
    expect(readCss()).toContain(
      'scroll-margin-top: calc(var(--wf-lens-reserve, 98px) + var(--wf-dh-h, 45px) + 17px)',
    );
  });

  it('never falls back either new property to a bare zero', () => {
    const css = readCss();
    expect(css).not.toContain('--wf-lens-h, 0');
    expect(css).not.toContain('--wf-dh-h, 0');
  });

  it('leaves the existing sticky chrome assertions untouched', () => {
    // The pre-existing lens z-index and the reservation's own declaration, plus the base
    // scroll-margin selector list this describe block's own sibling already pins — additive rules
    // must not disturb them. (It was the `--wf-mast-h` declaration here until that property was
    // deleted; the reservation is the one this describe's rules actually build on.)
    expect(decls(block('.wf-lens'))).toContain('z-index: 20');
    expect(block('.wf-shell')).toMatch(/--wf-lens-reserve:\s*\d+px/);
    const css = readCss();
    const at = css.lastIndexOf('scroll-margin-top: var(--wf-lens-reserve');
    const selectors = css.slice(css.lastIndexOf('\n}', at), at);
    expect(selectors).toContain('.wf-hc,');
  });
});
