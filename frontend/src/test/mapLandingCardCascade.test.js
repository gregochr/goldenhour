import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The landing card's geometry, asserted against the real `index.css`
 * (`docs/engineering/map-landing-plan.md` §3 L4).
 *
 * <h2>The defect this pins</h2>
 *
 * <p>`.wf-land-when` shipped as `flex: 1`, which is `1 1 0%` — a zero base size, making the day
 * label the FIRST item in the row to yield and letting it yield all the way to nothing. Measured in
 * headless Chromium against the BUILT stylesheet, the label overflowed its box at 375px (one row)
 * and 320px (both) and ran under the pick medallion. It is the same inversion #773/L2 found on
 * `.wf-win-label`, in a second place, and the design's yield order is explicit: the day is the fact
 * the card exists to state, and the region line is what gives.
 *
 * <p>`flex: 1 0 auto` grows into free space and refuses to shrink. Re-measured after the fix at
 * twelve viewport widths (1440/1280/1024/834/812/768/640/639/430/390/375/320): the day label
 * renders at its full natural width at every one — "Tonight" 48.2px, "Tomorrow" 64.5px, measured
 * INSIDE the card's own inherited font context — and no child of any row, the header, the all-Poor
 * sentence or the quiet pick line spills past its row's content box or the card's edge.
 *
 * <h2>What this file can and cannot prove</h2>
 *
 * <p>jsdom implements no layout, so nothing here measures a card and every px figure above is a
 * browser measurement taken on trust. What it pins is the MECHANISM: that the day label is
 * `flex: 1 0 auto` rather than `1`, that the verdict cell can yield (`min-width: 0`) and its region
 * line ellipses, that the medallion holds its width, and that the pill's word-withdrawal rule does
 * not reach the card. Same split every sibling cascade file draws.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** `index.css` with comments stripped — they carry both braces and the class names. */
function readCss() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/**
 * Every top-level `{selector, bodyInner}` block in source order, an at-rule carrying its nested
 * rules verbatim. The same brace-depth scanner the sibling cascade files each carry — duplicated
 * rather than shared, as they already are, because each file's needles and media split differ.
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

/** The declarations of the LAST top-level rule whose selector list contains `selector` exactly. */
function ruleFor(selector, { inQuery = null } = {}) {
  const blocks = sliceTopLevelBlocks(readCss());
  const scope = inQuery
    ? sliceTopLevelBlocks(
      blocks.filter((b) => b.selector.startsWith('@media') && b.selector.includes(inQuery))
        .map((b) => b.bodyInner).join('\n'),
    )
    : blocks.filter((b) => !b.selector.startsWith('@'));
  const hit = scope.filter(
    (b) => b.selector.split(',').map((s) => s.trim()).includes(selector),
  );
  expect(hit.length, `no rule for \`${selector}\`${inQuery ? ` inside ${inQuery}` : ''}`).toBeGreaterThan(0);
  return hit[hit.length - 1].bodyInner;
}

/** One declaration's value, or null. */
function decl(body, prop) {
  const m = body.match(new RegExp(`(?:^|[;{\\s])${prop}\\s*:\\s*([^;}]+)`));
  return m ? m[1].trim() : null;
}

describe('the landing card holds its day label at every width', () => {
  it('⚠️ `.wf-land-when` is `1 0 auto`, NOT `1` — the whole fix', () => {
    // `flex: 1` is `1 1 0%`. A zero base makes this the first item to yield and lets it yield to
    // nothing, which measured as a real overflow at 375px and 320px.
    const flex = decl(ruleFor('.wf-land-when'), 'flex');

    expect(flex).toBe('1 0 auto');
    expect(flex).not.toBe('1');
  });

  it('the day label clips rather than pushing the verdict out, as a backstop', () => {
    const body = ruleFor('.wf-land-when b');

    expect(decl(body, 'white-space')).toBe('nowrap');
    expect(decl(body, 'overflow')).toBe('hidden');
    expect(decl(body, 'text-overflow')).toBe('ellipsis');
  });

  it('the verdict cell is what yields, and its region line is what ellipses', () => {
    // ⚠️ `min-width: 0` is REQUIRED here and is not decoration: `.wf-land-verdict` is not a scroll
    // container (`overflow` is visible), so without it the automatic minimum is its min-content
    // width and `flex-shrink` never engages — the row would overflow instead of compressing.
    expect(decl(ruleFor('.wf-land-verdict'), 'min-width')).toBe('0');

    const region = ruleFor('.wf-land-verdict-region');
    expect(decl(region, 'max-width')).toBe('120px');
    expect(decl(region, 'overflow')).toBe('hidden');
    expect(decl(region, 'text-overflow')).toBe('ellipsis');
    expect(decl(region, 'white-space')).toBe('nowrap');
  });

  it('the medallion holds its width — it is not the thing that gives', () => {
    expect(decl(ruleFor('.wf-land-pick'), 'flex-shrink')).toBe('0');
  });
});

describe('the landing card sits in the right place in the ladder', () => {
  it('is BELOW the chrome, because the chrome is a stacking context', () => {
    // ⚠️ The number that matters is `< .wf-map-chrome-tl`, and three earlier answers got it wrong
    // by reading declared z-index values instead of asking which stacking context they live in.
    // `.wf-map-chrome-tl` is `position: absolute` with a non-auto z-index, so `.wf-win-menu`'s 1500
    // is LOCAL to it and composites at 1100 — measured in Chromium, a card at 1300 painted over the
    // open dropdown across 328×133px at 1280 (`elementFromPoint` returned `.wf-land`). Comparing
    // the card against the MENU's declared 1500, as an earlier version of this test did, asserts a
    // relationship the DOM has never implemented.
    const z = (sel) => Number(decl(ruleFor(sel), 'z-index'));

    expect(z('.wf-land')).toBe(1050);
    expect(z('.wf-land')).toBeLessThan(z('.wf-map-chrome-tl'));
    expect(z('.wf-land')).toBeLessThan(z('.wf-map-chrome-tr'));
    expect(z('.wf-land')).toBeLessThan(z('.wf-callout'));
    // ...and still above every layer the map paints the field and its labels into.
    expect(z('.wf-land')).toBeGreaterThan(700);
  });

  it('clears the bottom chrome band rather than the frame edge', () => {
    // 112 = the card's own `top: 60` + the Legend chip's `bottom: 8` + its ~44px height. The 76px
    // this shipped with was `top + 16`, which put a capped card 16px up — inside that chip's band.
    expect(decl(ruleFor('.wf-land'), 'max-height')).toBe('calc(100% - 112px)');
    expect(decl(ruleFor('.wf-land', { inQuery: 'max-width: 639px' }), 'max-height'))
      .toBe('calc(100% - 196px)');
  });

  it('insets to the frame on a phone rather than becoming a sheet', () => {
    const body = ruleFor('.wf-land', { inQuery: 'max-width: 639px' });

    expect(decl(body, 'top')).toBe('56px');
    expect(decl(body, 'left')).toBe('8px');
    expect(decl(body, 'right')).toBe('8px');
    // ⚠️ Both releases are load-bearing: the desktop rule sets a fixed 376px AND a
    // `max-width: calc(100% - 24px)` measured from a 12px inset. Leaving either standing under a
    // rule that means "fill the bar" is the trap L2's own phone rule records paying for.
    expect(decl(body, 'width')).toBe('auto');
    expect(decl(body, 'max-width')).toBe('none');
  });
});

describe('the pill\'s word-withdrawal rule does not reach the card', () => {
  it('⚠️ the two withdraw their words at DIFFERENT widths, which is why the classes are split', () => {
    // The pill is under width pressure from 812px; the card, at a fixed 376px, is not until the
    // viewport itself narrows past 400. Reusing `.wf-map-tab .wf-win-pick-words` would have bound
    // the card to the pill's breakpoint — and that selector needs no `.wf-win-pill` ancestor, so it
    // WOULD have reached the card. Same mechanism (visually hidden, so the words stay in the
    // accessible name), two thresholds, two classes.
    const pill = ruleFor('.wf-map-tab .wf-win-pick-words', { inQuery: 'max-width: 811px' });
    const card = ruleFor('.wf-land-pick-words', { inQuery: 'max-width: 400px' });

    expect(decl(pill, 'clip-path')).toBe('inset(50%)');
    expect(decl(card, 'clip-path')).toBe('inset(50%)');
    // ⚠️ Visually hidden, never `display: none` — that would delete the pick from the accessibility
    // tree while a sighted reader kept the glyph, the defect L2 fixed on the pill.
    expect(decl(card, 'display')).toBeNull();
    expect(decl(card, 'position')).toBe('absolute');
  });

  it('the kind chip is not the thing that yields', () => {
    // Measured on the widest reachable row: with the day label pinned and the medallion at
    // `flex-shrink: 0`, the squeeze landed on `.wf-hc-sun` (`overflow: hidden`, shrink 1) and it
    // clipped to `SUNRI` at 320px. It names the event the row is about.
    expect(decl(ruleFor('.wf-land-row .wf-hc-sun'), 'flex-shrink')).toBe('0');
  });

  it('marks the window in force on two channels, and at the sibling\'s own strength', () => {
    // ⚠️ The fill shipped at 0.09 under a comment claiming it was `.wf-win-row.on`'s — which is
    // 0.13 — and there was no second channel at all: measured 1.16:1 against an unselected row,
    // under WCAG 1.4.11's 3:1, and gone entirely under forced-colors.
    const on = ruleFor('.wf-land-row.on');
    expect(decl(on, 'background')).toBe(decl(ruleFor('.wf-win-row.on'), 'background'));
    expect(decl(on, 'border-left-color')).toBe('var(--color-home)');
    // The base rule reserves the rule's width so marking a row cannot shift its own text 2px.
    expect(decl(ruleFor('.wf-land-row'), 'border-left')).toBe('2px solid transparent');
  });

  it('but the two picks DO share one ink and one weight, defined once', () => {
    // The pair is separated on two axes (`--color-badge-go` at 700, `--color-verdict-go` at 600) —
    // a WCAG 1.4.11 decision that must not be made twice. Grouped selectors, one rule each.
    const best = ruleFor('.wf-land-pick[data-pick="best"]');
    const also = ruleFor('.wf-land-pick[data-pick="also"]');

    expect(decl(best, 'color')).toBe('var(--color-badge-go)');
    expect(decl(best, 'font-weight')).toBe('700');
    expect(decl(also, 'color')).toBe('var(--color-verdict-go)');
    expect(decl(also, 'font-weight')).toBe('600');
    // ⚠️ The RING is a `--color-verdict-*`, never a `--color-badge-*`: this file's own token
    // contract forbids a badge ink as a fill or a border.
    expect(decl(best, 'box-shadow')).toContain('--color-verdict-go');
    expect(decl(also, 'box-shadow')).toContain('--color-verdict-go');
    expect(decl(best, 'box-shadow')).not.toContain('--color-badge');
  });

  it('one tier has one ink across the pill and the card', () => {
    const pairs = [['WORTH_IT', 'go'], ['MAYBE', 'maybe'], ['STAND_DOWN', 'poor']];
    for (const [tier, token] of pairs) {
      const body = ruleFor(`.wf-land-verdict[data-tier="${tier}"] .wf-land-verdict-word`);
      expect(decl(body, 'color')).toBe(`var(--color-badge-${token})`);
      // Same rule, same declaration — the pill's selector is grouped into it.
      const blocks = sliceTopLevelBlocks(readCss());
      const grouped = blocks.find(
        (b) => b.selector.includes(`.wf-land-verdict[data-tier="${tier}"]`),
      );
      expect(grouped.selector).toContain(`.wf-win-verdict[data-tier="${tier}"]`);
    }
  });
});
