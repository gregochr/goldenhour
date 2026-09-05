import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, afterEach,
} from 'vitest';

/**
 * The Map tab window control holds ONE width, so the `‹ ›` steppers do not move as the reader
 * steps through events — asserted against the real `index.css`.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>`.wf-win-pill` was sized by its content under a `max-width: 260px` cap, and its content is
 * three variable-width parts: the kind chip (Sunrise / Sunset / Astro / Aurora), the day label
 * (`Today` → `Wednesday night`), and a time that is absent whenever a row's `time` is empty
 * (`mapEvents.js`'s unscored beyond-briefing filler sets `time: ''`, and a served row takes
 * `served.time || ''`). Measured in Chromium against this stylesheet's own loaded fonts, the pill
 * ranged **115.41px** ("No forecast") to **227.52px** ("Aurora · Wednesday night · 21:04") — so `›`
 * travelled up to **112px** between one click and the next, and stepping through the forecast meant
 * chasing the button with the mouse. Stepping is the whole use the pair is put to, which is what
 * makes this a defect rather than a cosmetic wobble.
 *
 * <h2>What this file can and cannot prove</h2>
 *
 * <h2>⚠️ The MECHANISM changed at map-landing L2; the invariant did not</h2>
 *
 * <p>#773 held the width constant with a fixed `width: 262px` on the pill, derived from the
 * dropdown's 334px so the two shared both edges. That worked while the pill held three parts. L2
 * adds a verdict cell and, on two windows in the whole forecast, a pick medallion — measured at up
 * to **417.47px** of reachable content — so a 262px pill would ellipse the day label, which is the
 * one thing the design says must never truncate.
 *
 * <p>The width is now a property of the FRAME rather than of the content, which is the same
 * guarantee by a different route: `.wf-map-tab .wf-map-chrome-tl` bounds the group on the right
 * (clear of the Regions / Heat-Pins / Filters cluster), `.wf-win-control` caps it at 504px, and the
 * pill fills whatever is left. At any given viewport every event renders the same width, so `›`
 * still does not move as the reader steps — and the dropdown still shares both edges, because the
 * cap is on the GROUP and the menu is `width: 100%` of it.
 *
 * <p>jsdom resolves specificity but implements no layout — `getBoundingClientRect` answers zero for
 * everything — so nothing here measures a pill, and the px figures above are browser measurements
 * this file takes on trust. What it pins is the mechanism: that the group is bounded and capped,
 * that the pill fills rather than content-sizes, that the label carries the `flex` + `overflow` pair
 * that lets it absorb the slack and clip, and that the phone rule hands the width back.
 *
 * <p>⚠️ It also cannot see `box-sizing`. That comes from Tailwind's preflight via
 * `@import "tailwindcss"` on line 1, which the slicer below never resolves — `index.css` itself
 * contains no `box-sizing` declaration at all. So "262px + 2×32 + 2×4 = 334px outer" is a browser
 * measurement, not something asserted here, and an earlier revision of this file that did assert
 * the arithmetic was removed at review: given the pill-width and stepper-width assertions it
 * reduced to `pill === 262` a second time, while its comment claimed it guarded the total.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** The classes the control's geometry lives on — the chrome box included, since L2 the bound that
 *  makes "the pill fills" mean "the pill is constant" lives there. */
const NEEDLES = ['.wf-win-pill', '.wf-win-label', '.wf-win-control', '.wf-win-step', '.wf-map-chrome-tl'];

/**
 * The media queries this stylesheet gives the window control, widest breakpoint first.
 *
 * <p>L2 added two beyond the original phone one, because the design's yield order needs two
 * intermediate steps: the medallion's WORDS are withdrawn once the group can no longer hold the
 * widest content (811), and the medallion goes entirely once the day label would start to clip
 * (389). `extractRules` concatenates every query at or below the requested width, in source order,
 * which is what the cascade resolves to.
 */
const QUERIES = ['max-width: 811px', 'max-width: 639px', 'max-width: 389px'];

/** Widths at which each viewport name resolves — only queries at or above the width apply. */
const VIEWPORT_WIDTH = { desktop: 1280, tablet: 700, phone: 390, tiny: 320 };

/** `index.css` with comments stripped — they carry both braces and the class names. */
function readCss() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/**
 * Every top-level block in `css` — `{selector, bodyInner}` pairs in source order, an at-rule block
 * carrying its nested rules verbatim and unparsed. The brace-depth scanner the sibling cascade
 * files share, factored so it can be re-applied to a nested block's own inner text.
 *
 * @param {string} css
 * @returns {Array<{selector: string, bodyInner: string}>}
 */
function sliceTopLevelBlocks(css) {
  const blocks = [];
  let depth = 0;
  let selectorStart = 0;
  let blockStart = -1;
  for (let i = 0; i < css.length; i += 1) {
    if (css[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        blocks.push({
          selector: css.slice(selectorStart, blockStart).trim(),
          bodyInner: css.slice(blockStart + 1, i),
        });
        selectorStart = i + 1;
      }
    }
  }
  return blocks;
}

/**
 * The rules matching `needles`, for ONE viewport.
 *
 * <p>⚠️ The media split is the point of this helper and is why it is not
 * `mapPhoneChromeCascade.test.jsx`'s own extractor: that one concatenates the base rule and its
 * `@media` override together, so the phone declaration always wins and a DESKTOP claim cannot be
 * expressed with it at all. Here `'desktop'` takes top-level rules only and `'phone'` adds the
 * `max-width: 639px` overrides in source order, which is what the cascade resolves to once that
 * query matches. The condition itself is discarded either way: jsdom does not evaluate `@media`
 * (this suite's `matchMedia` stub always answers `matches: false`, `src/test/setup.js`), so "does
 * this query match a 390px viewport" stays a browser claim — the same split every sibling file
 * draws for `var()`.
 *
 * <p>⚠️ **This models the cascade; it does not guarantee it.** Two shapes would silently break it:
 * a rule inside `@layer` (which this file DOES contain, at `@layer base` / `@layer components`)
 * would be dropped, since only `@media` is recursed into — and Tailwind v4 gives layered rules
 * lower priority than unlayered ones regardless of specificity, which flat source-order
 * concatenation cannot represent at all; and any other matching query (`@media (min-width: …)`,
 * `@supports`, `@container`, a narrower `max-width`) would be dropped too. Neither is live today,
 * and `the slicer sees every rule there is` below is what keeps that true rather than a comment
 * anyone has to remember to re-check.
 *
 * @param {string[]} needles selector substrings to match (any one)
 * @param {'desktop'|'phone'} viewport which cascade to resolve
 * @returns {string} the concatenated rules, ready to inject
 */
function extractRules(needles, viewport) {
  const matches = (selector) => needles.some((needle) => selector.includes(needle));
  const width = VIEWPORT_WIDTH[viewport];
  expect(width, `unknown viewport "${viewport}"`).toBeTypeOf('number');
  /** Which of `QUERIES` a viewport of `width` actually matches. */
  const applies = (selector) => QUERIES.some((q) => selector.includes(q)
    && width <= Number(q.match(/(\d+)/)[1]));
  const rules = [];
  for (const block of sliceTopLevelBlocks(readCss())) {
    if (block.selector.startsWith('@')) {
      if (!applies(block.selector)) continue;
      for (const nested of sliceTopLevelBlocks(block.bodyInner)) {
        if (matches(nested.selector)) rules.push(`${nested.selector} {${nested.bodyInner}}`);
      }
    } else if (matches(block.selector)) {
      rules.push(`${block.selector} {${block.bodyInner}}`);
    }
  }
  return rules.join('\n');
}

let cleanupFns = [];
afterEach(() => {
  for (const fn of cleanupFns) fn();
  cleanupFns = [];
});

/**
 * Injects `slice`, builds `className` under any `ancestors`, and hands back its computed style.
 *
 * <p>The ancestor chain is not optional decoration: since L2 the two declarations that bound the
 * control are written as descendant selectors scoped to the tab (`.wf-map-tab .wf-win-control`,
 * `.wf-map-tab .wf-map-chrome-tl`) so they cannot reach the frozen Plan-tab overlay. A single
 * element carrying both class names does not match a descendant selector, so a test written that
 * way reads the BASE rule and passes while asserting nothing about the scoped one — the same shape
 * `mapPhoneChromeCascade.test.jsx`'s own helper takes for the same reason.
 */
function computedStyleFor(slice, className, ancestors = []) {
  const style = document.createElement('style');
  style.textContent = slice;
  document.head.appendChild(style);
  const nodes = [...ancestors, className].map((cls) => {
    const el = document.createElement('div');
    el.className = cls;
    return el;
  });
  for (let i = 0; i < nodes.length - 1; i += 1) nodes[i].appendChild(nodes[i + 1]);
  document.body.appendChild(nodes[0]);
  cleanupFns.push(() => { style.remove(); nodes[0].remove(); });
  return getComputedStyle(nodes[nodes.length - 1]);
}

/** The tab root, the ancestor both of L2's scoped bounds hang off. */
const TAB = ['wf-map-tab'];

const PILL = ['.wf-win-pill'];
const LABEL = ['.wf-win-label'];

describe('the slicer sees every rule there is', () => {
  // Without this, the two describes below assert a cascade that merely HAPPENS to be complete.
  // A future `.wf-win-pill` rule inside `@layer components`, `@supports`, or any query other than
  // the phone one would be dropped in silence and the desktop assertions would keep passing while
  // describing a stylesheet the browser no longer resolves that way.
  it('no rule for these classes hides anywhere the desktop/phone split does not look', () => {
    const stray = [];
    for (const block of sliceTopLevelBlocks(readCss())) {
      if (!block.selector.startsWith('@')) continue;
      const nested = sliceTopLevelBlocks(block.bodyInner)
        .filter((r) => NEEDLES.some((needle) => r.selector.includes(needle)));
      if (nested.length && !QUERIES.some((q) => block.selector.includes(q))) {
        stray.push(`${block.selector} → ${nested.map((r) => r.selector).join(', ')}`);
      }
    }
    expect(stray, `window-control rules found inside an at-rule this file ignores:\n${stray.join('\n')}`)
      .toEqual([]);
  });
});

describe('the window control is one width per frame, whatever the event says', () => {
  // ⚠️ These assert the CHAIN, not a list of values. The old file's second test existed so the
  // claim held "by construction instead of by arithmetic that a later layout change could quietly
  // invalidate", and L2's first rewrite dropped it for three value assertions — every one of which
  // stayed true while the invariant was false (measured: `›` travelled 182.58px). Each test below
  // names the link it holds and the failure that link's absence produces.

  it('link 1 — the group has a DECLARED width, so the box hugs a constant', () => {
    // Without this the shrink-to-fit box hugs the CONTENT instead, the pill is content-sized, and
    // the steppers travel with the event. Measured at 182.58px when this was only a `max-width`.
    const style = computedStyleFor(extractRules(['.wf-win-control'], 'desktop'), 'wf-win-control');
    expect(style.width).toBe('504px');
    expect(style.maxWidth).toBe('100%');
  });

  it('link 2 — the pill FILLS that width rather than taking its content\'s', () => {
    expect(computedStyleFor(extractRules(PILL, 'desktop'), 'wf-win-pill').flexGrow).toBe('1');
  });

  it('link 3 — the pill can actually shrink, which needs min-width AND overflow', () => {
    // ⚠️ The pill is NOT a scroll container by default, so its automatic minimum is its min-content
    // width and `flex-shrink` never engages — it overflows instead, under the right-hand cluster
    // where it goes dead to clicks. #773 measured `min-width: 0` as a no-op on `.wf-win-label`,
    // which IS a scroll container; that finding does not transfer to this element.
    const style = computedStyleFor(extractRules(PILL, 'desktop'), 'wf-win-pill');
    expect(style.minWidth).toBe('0px');
    expect(style.overflow).toBe('hidden');
  });

  it('link 4 — the steppers never shrink, so they cannot move under compression', () => {
    // The direct successor of the assertion #773 wrote and L2's first rewrite deleted. Drop this and
    // the steppers become content-sized under pressure and `›` moves as the reader steps, with every
    // other test in this file still green.
    const style = computedStyleFor(extractRules(['.wf-win-step'], 'desktop'), 'wf-win-step');
    expect(style.flexShrink).toBe('0');
    expect(style.width).toBe('32px');
  });

  it('link 5 — the day label grows but never shrinks, so it is the last thing to yield', () => {
    // The design's own constraint: "the day label never truncates … the medallion words and the
    // region yield instead". `flex: 1` (a 0 base) made it the FIRST to yield, all the way to zero.
    const style = computedStyleFor(extractRules(LABEL, 'desktop'), 'wf-win-label');
    expect(style.flexGrow).toBe('1');
    expect(style.flexShrink).toBe('0');
  });

  it('link 6 — the region line yields first, by cap and ellipsis', () => {
    const cell = computedStyleFor(extractRules(['.wf-win-verdict'], 'desktop'), 'wf-win-verdict');
    // The cell itself must be shrinkable for its child's cap to matter.
    expect(cell.minWidth).toBe('0px');
    const region = computedStyleFor(
      extractRules(['.wf-win-verdict'], 'desktop'), 'wf-win-verdict-region', ['wf-win-verdict'],
    );
    expect(region.maxWidth).toBe('112px');
    expect(region.textOverflow).toBe('ellipsis');
  });

  it('the box is BOUNDED but not STRETCHED — the difference is a dead strip over the map', () => {
    // ⚠️ `right` beside the existing `left` stretches this absolutely-positioned block to
    // `100% − 308px`: a transparent div at z-index 1100 across the top of the map that swallows
    // every drag begun in it, and a label-placement obstacle (`MapLabels.jsx`) three times its
    // licensed size. `max-width` bounds it while leaving it shrink-to-fit.
    const style = computedStyleFor(
      extractRules(['.wf-map-chrome-tl'], 'desktop'), 'wf-map-chrome-tl', TAB,
    );
    expect(style.maxWidth).toBe('calc(100% - 308px)');
    expect(style.right).toBe('auto');
  });

  it('the tab-scoped bounds do NOT reach a control outside the tab (the frozen overlay)', () => {
    // ⚠️ The ancestor chain makes the descendant selector match; it does not prove the selector IS
    // scoped. Dropping `.wf-map-tab ` from either rule leaves every other assertion in this file
    // green while the frozen Plan-tab overlay inherits a bound written for the tab.
    const chrome = computedStyleFor(
      extractRules(['.wf-map-chrome-tl'], 'desktop'), 'wf-map-chrome-tl',
    );
    expect(chrome.maxWidth).toBe('none');
  });

  it('the menu shares both edges with the control, at every frame', () => {
    // ⚠️ No `min-width`. `min-width` beats `max-width` (CSS 2.1 §10.4), so one here disables both
    // this rule's own `max-width: calc(100vw - 32px)` and the phone rule's `max-width: none` —
    // measured, the dropdown rendered 22px past the right edge of a 320px viewport.
    const style = computedStyleFor(extractRules(['.wf-win-menu'], 'desktop'), 'wf-win-menu');
    expect(style.width).toBe('100%');
    // `auto` is jsdom's reading for "never declared", which is the assertion: no `min-width` at all.
    expect(style.minWidth).toBe('auto');
  });
});

describe('the yield order the design specifies, breakpoint by breakpoint', () => {
  it('at 811px and below the medallion words are hidden VISUALLY, not removed', () => {
    // ⚠️ `display: none` takes an element out of the accessibility tree, and the glyph beside it is
    // `aria-hidden` — so the pick would vanish from the pill's accessible name entirely while a
    // sighted reader still saw ◎/○. This project's own standards name that as a defect.
    const style = computedStyleFor(
      extractRules(['.wf-win-pick-words'], 'tablet'), 'wf-win-pick-words', TAB,
    );
    expect(style.display).not.toBe('none');
    expect(style.position).toBe('absolute');
    expect(style.clipPath).toBe('inset(50%)');
  });

  it('at 639px and below the region line goes and the word stays', () => {
    const rules = extractRules(['.wf-win-verdict'], 'phone');
    const region = computedStyleFor(rules, 'wf-win-verdict-region', ['wf-map-tab', 'wf-win-verdict']);
    const word = computedStyleFor(rules, 'wf-win-verdict-word', ['wf-map-tab', 'wf-win-verdict']);

    expect(region.display).toBe('none');
    // The verdict WORD is never withdrawn — a phone reader still gets the answer.
    expect(word.display).not.toBe('none');
  });

  it('at 389px and below the medallion leaves the layout, so the day label keeps its space', () => {
    const style = computedStyleFor(
      extractRules(['.wf-win-pick'], 'tiny'), 'wf-win-pick', ['wf-map-tab', 'wf-win-pill'],
    );
    expect(style.display).toBe('none');
  });

  it('the medallion is still present at 390px, the frame the plan measures', () => {
    const style = computedStyleFor(
      extractRules(['.wf-win-pick'], 'phone'), 'wf-win-pick', ['wf-map-tab', 'wf-win-pill'],
    );
    expect(style.display).not.toBe('none');
  });
});

describe('the phone rule hands the width back (map-tab-v2-plan.md §3 P12)', () => {
  // ⚠️ Only the declarations this change introduced are asserted here. The phone pill's
  // `min-height: 40px`, `flex: 1` and `justify-content: center` are already pinned by
  // `mapPhoneChromeCascade.test.jsx` ("the window pill grows to 40px and fills the full-width
  // control on the phone"); restating them would be two suites owning one claim.
  it('the group drops the desktop cap and the chrome box drops its bound', () => {
    // The nav cluster has moved to the bottom bar down here, so the top-left box owns the full
    // width and the control fills the row.
    expect(computedStyleFor(extractRules(['.wf-win-control'], 'phone'), 'wf-win-control', TAB).maxWidth)
      .toBe('none');
    expect(computedStyleFor(extractRules(['.wf-map-chrome-tl'], 'phone'), 'wf-map-chrome-tl', TAB).right)
      .toBe('8px');
  });

  it('the label stops growing, so there is free space left to centre', () => {
    // Dropping this reset is an invisible regression: `justify-content: center` becomes a no-op
    // the moment a child absorbs the free space, and the phone pill silently takes the desktop
    // treatment — content pushed to both edges — on a bar that asked to be centred.
    expect(computedStyleFor(extractRules(LABEL, 'phone'), 'wf-win-label').flexGrow).toBe('0');
  });
});
