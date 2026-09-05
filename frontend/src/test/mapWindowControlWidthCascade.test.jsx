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
 * <p>jsdom resolves specificity but implements no layout — `getBoundingClientRect` answers zero for
 * everything — so nothing here measures a pill, and the px figures above are browser measurements
 * this file takes on trust. What it pins is the mechanism: that the pill's width is a FIXED length
 * rather than a cap (a revert to `max-width` reads back as `auto`), that it cannot shrink below it,
 * that the label carries the `flex` + `overflow` pair that lets it absorb the slack and clip, and
 * that the phone rule hands the width back.
 *
 * <p>⚠️ It also cannot see `box-sizing`. That comes from Tailwind's preflight via
 * `@import "tailwindcss"` on line 1, which the slicer below never resolves — `index.css` itself
 * contains no `box-sizing` declaration at all. So "262px + 2×32 + 2×4 = 334px outer" is a browser
 * measurement, not something asserted here, and an earlier revision of this file that did assert
 * the arithmetic was removed at review: given the pill-width and stepper-width assertions it
 * reduced to `pill === 262` a second time, while its comment claimed it guarded the total.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** The four classes the control's geometry lives on. */
const NEEDLES = ['.wf-win-pill', '.wf-win-label', '.wf-win-control', '.wf-win-step'];

/** The one media query this stylesheet gives the window control. */
const PHONE_QUERY = 'max-width: 639px';

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
  const rules = [];
  for (const block of sliceTopLevelBlocks(readCss())) {
    if (block.selector.startsWith('@')) {
      if (viewport !== 'phone' || !block.selector.includes(PHONE_QUERY)) continue;
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

/** Injects `slice`, builds `className`, and hands back its computed style. */
function computedStyleFor(slice, className) {
  const style = document.createElement('style');
  style.textContent = slice;
  document.head.appendChild(style);
  const el = document.createElement('div');
  el.className = className;
  document.body.appendChild(el);
  cleanupFns.push(() => { style.remove(); el.remove(); });
  return getComputedStyle(el);
}

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
      if (nested.length && !block.selector.includes(PHONE_QUERY)) {
        stray.push(`${block.selector} → ${nested.map((r) => r.selector).join(', ')}`);
      }
    }
    expect(stray, `window-control rules found inside an at-rule this file ignores:\n${stray.join('\n')}`)
      .toEqual([]);
  });
});

describe('the window control is one width on the desktop, whatever the event says', () => {
  it('the pill takes a fixed width rather than a cap a short label can undercut', () => {
    // 262px so the control totals the dropdown's own 334px — see the rule's comment; a revert to
    // `max-width` leaves `width` at `auto` and the pill content-sized again.
    expect(computedStyleFor(extractRules(PILL, 'desktop'), 'wf-win-pill').width).toBe('262px');
  });

  it('the pill cannot shrink below that width when its own container is tight', () => {
    // The pill is itself a flex item, so without this the fixed width is only a basis and the
    // "constant 334px" claim would hold by arithmetic rather than by construction.
    expect(computedStyleFor(extractRules(PILL, 'desktop'), 'wf-win-pill').flexShrink).toBe('0');
  });

  it('the label absorbs the slack the fixed width leaves', () => {
    expect(computedStyleFor(extractRules(LABEL, 'desktop'), 'wf-win-label').flexGrow).toBe('1');
  });

  it('the label clips rather than pushing the caret out of the pill', () => {
    // ⚠️ `overflow: hidden` is the load-bearing half, NOT a `min-width: 0` — an item that is
    // already a scroll container has an automatic minimum of zero (CSS Sizing 3 §5.1), so a
    // `min-width: 0` here would be a no-op. One was added and removed at adversarial review;
    // measured in Chromium, clipping and the caret's position are identical either way.
    const style = computedStyleFor(extractRules(LABEL, 'desktop'), 'wf-win-label');
    expect(style.overflow).toBe('hidden');
    expect(style.textOverflow).toBe('ellipsis');
  });

  it('the steppers keep the 32px the design gives them', () => {
    // The other half of the 334px total. Named here so a change to it has to face this file.
    expect(computedStyleFor(extractRules(['.wf-win-step'], 'desktop'), 'wf-win-step').width).toBe('32px');
  });
});

describe('the phone rule hands the width back (map-tab-v2-plan.md §3 P12)', () => {
  // ⚠️ Only the declarations this change introduced are asserted here. The phone pill's
  // `min-height: 40px`, `flex: 1` and `justify-content: center` are already pinned by
  // `mapPhoneChromeCascade.test.jsx` ("the window pill grows to 40px and fills the full-width
  // control on the phone"); restating them would be two suites owning one claim.
  it('the pill drops the desktop fixed width', () => {
    expect(computedStyleFor(extractRules(PILL, 'phone'), 'wf-win-pill').width).toBe('auto');
  });

  it('the label stops growing, so there is free space left to centre', () => {
    // Dropping this reset is an invisible regression: `justify-content: center` becomes a no-op
    // the moment a child absorbs the free space, and the phone pill silently takes the desktop
    // treatment — content pushed to both edges — on a bar that asked to be centred.
    expect(computedStyleFor(extractRules(LABEL, 'phone'), 'wf-win-label').flexGrow).toBe('0');
  });
});
