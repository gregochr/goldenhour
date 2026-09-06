import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The window panel's placement, asserted against the real `index.css`
 * (`docs/engineering/map-landing-plan.md` §3 L5).
 *
 * <h2>The defects this pins</h2>
 *
 * <p>The panel first mounted INSIDE `.wf-map-chrome-tl`, on the (correct) reasoning that the chrome
 * is a stacking context so the panel would land above the 1050 landing card for free. Measured in
 * headless Chromium against the built sheet with the real ancestor chain, that cost two defects:
 * the chrome box is ~36px tall, so `max-height` had no percentage basis and fell back to `100vh` —
 * the VIEWPORT, taller than the frame by the whole masthead — and rows ran past the frame's bottom
 * (28px at 1280, 76px at 390) to be cut by `.wf-body--map`'s `overflow: hidden` with no scrollbar to
 * reach them; and the bottom chrome (counts footer, scored-legend chip, Legend chip, phone bar — all
 * 1100 and later in DOM order) painted over the last rows.
 *
 * <p>Separately, the close ✕ borrowed `.wf-land-x`, whose `grid-column: 2` is written for the
 * landing card's TWO-column head. This head has three, so the ✕ auto-placed into the MIDDLE and the
 * verdict word took the right-hand track where the dismiss control belongs.
 *
 * <h2>What this file can and cannot prove</h2>
 *
 * <p>jsdom implements no layout, so nothing here measures a panel and every px figure above is a
 * browser measurement taken on trust. What it pins is the mechanism — and it exists because all
 * three of those claims survived mutation against the behavioural suite, which cannot see a
 * stylesheet at all.
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

/**
 * Where a top-level rule sits in source order — the index of the LAST block whose selector list
 * contains `selector` exactly.
 *
 * <p>⚠️ **This is the half the first cut of this file was missing, and a review lens measured the
 * cost.** Every override the region panel makes is a single class against another single class, so
 * the two have EQUAL specificity and the cascade is decided by position alone. Asserting each
 * rule's own value proves nothing about which one wins: moving `.wf-reg-panel-x` above
 * `.wf-land-x` — one line, the kind of move an alphabetise or a merge makes — left all fifteen
 * assertions green while the ✕ auto-placed back into the middle of the head, which is verbatim the
 * L5 blocking defect this file exists to prevent.
 */
function orderOf(selector) {
  const blocks = sliceTopLevelBlocks(readCss()).filter((b) => !b.selector.startsWith('@'));
  const at = blocks.map((b, i) => [b, i])
    .filter(([b]) => b.selector.split(',').map((x) => x.trim()).includes(selector))
    .map(([, i]) => i);
  expect(at.length, `no rule for \`${selector}\``).toBeGreaterThan(0);
  return at[at.length - 1];
}

/** Asserts `winner` beats `loser` for an element carrying both classes at equal specificity. */
function beats(winner, loser) {
  expect(
    orderOf(winner),
    `\`${winner}\` must come AFTER \`${loser}\` in index.css — equal specificity, so source order `
    + 'is the whole cascade here',
  ).toBeGreaterThan(orderOf(loser));
}

describe('the window panel sits in the frame, not in the chrome box', () => {
  it('is a frame-level block ABOVE the chrome and below the callout', () => {
    // ⚠️ 1150 is the whole point of the move: inside the chrome it needed no z-index (that box is a
    // stacking context) but had no percentage basis for its height; out here it needs one.
    const z = (sel) => Number(decl(ruleFor(sel), 'z-index'));

    expect(z('.wf-win-panel')).toBe(1150);
    expect(z('.wf-win-panel')).toBeGreaterThan(z('.wf-map-chrome-tl'));
    expect(z('.wf-win-panel')).toBeGreaterThan(z('.wf-map-chrome-tr'));
    expect(z('.wf-win-panel')).toBeGreaterThan(z('.wf-land'));
    expect(z('.wf-win-panel')).toBeLessThan(z('.wf-callout'));
  });

  it('measures its height against the FRAME, and clears the bottom chrome band', () => {
    // ⚠️ A percentage, never `vh`. `100vh` is the viewport; the frame sits below the masthead and
    // the tab bar, and the difference is exactly what clipped the rows.
    const maxHeight = decl(ruleFor('.wf-win-panel'), 'max-height');

    expect(maxHeight).toBe('calc(100% - 102px)');
    expect(maxHeight).not.toMatch(/vh/);
  });

  it('shares the control\'s own box, including the bound that clears the right-hand cluster', () => {
    const body = ruleFor('.wf-win-panel');

    expect(decl(body, 'width')).toBe('504px');
    expect(decl(body, 'max-width')).toBe(decl(ruleFor('.wf-map-tab .wf-map-chrome-tl'), 'max-width'));
    expect(decl(body, 'top')).toBe('50px');
    expect(decl(body, 'left')).toBe(decl(ruleFor('.wf-map-chrome-tl'), 'left'));
  });

  it('releases BOTH width bounds on a phone — the release its sibling calls load-bearing', () => {
    // ⚠️ `.wf-win-menu`'s own rule records why: the base `max-width` survives `width: auto`
    // untouched, so the panel measured exactly 16px narrower than the full-width control it hangs
    // from at 639/430/390/375/320 — a visible right-edge jump on the one interaction that swaps
    // the dropdown for the panel.
    const body = ruleFor('.wf-win-panel', { inQuery: 'max-width: 639px' });

    expect(decl(body, 'width')).toBe('auto');
    expect(decl(body, 'max-width')).toBe('none');
    expect(decl(body, 'max-height')).toBe('calc(100% - 194px)');
  });
});

describe('the window panel\'s own rows and controls', () => {
  it('gives the close control its own column, because it borrows a two-column rule', () => {
    // `.wf-land-x` is `grid-column: 2`, written for the card's two-column head; this head has three.
    expect(decl(ruleFor('.wf-win-panel-x'), 'grid-column')).toBe('3');
    expect(decl(ruleFor('.wf-land-x'), 'grid-column')).toBe('2');
    expect(decl(ruleFor('.wf-win-panel-head'), 'grid-template-columns')).toBe('1fr auto auto');
  });

  it('sizes the two right-hand columns FIXED, so they line up down the list', () => {
    // ⚠️ `auto` sized every row independently — the rows are a flex column and each row its own
    // grid — and the verdict column wandered 45px down a ranked list whose purpose is reading
    // values down a column. Measured at 1440/834/390 alike: four distinct x-positions.
    const tracks = decl(ruleFor('.wf-win-panel-row'), 'grid-template-columns');

    expect(tracks).toBe('minmax(0, 1fr) 62px 78px');
    expect(tracks).not.toMatch(/auto/);
    expect(decl(ruleFor('.wf-win-panel-word'), 'justify-self')).toBe('end');
    expect(decl(ruleFor('.wf-win-panel-best'), 'justify-self')).toBe('end');
  });

  it('marks "no answer" with an explicit literal, never the muted token', () => {
    // ⚠️ §6 sets `--color-plex-text-muted` == secondary inside `.wf-body--map`, so reading the token
    // made the no-answer marker the same ink as a real score. `.wf-win-row-unscored` and
    // `.wf-jump-unscored` each record the same flattening and cure it the same way.
    const literal = 'rgba(242, 231, 211, 0.52)';

    expect(decl(ruleFor('.wf-win-panel-unscored'), 'color')).toBe(literal);
    expect(decl(ruleFor('.wf-win-panel-word'), 'color')).toBe(literal);
    expect(decl(ruleFor('.wf-win-panel-verdict[data-tier="AWAITING"]'), 'color')).toBe(literal);
    expect(decl(ruleFor('.wf-win-panel-unscored'), 'color')).not.toMatch(/var\(/);
  });

  it('flips the borrowed row rule, because the drilldown entry is BELOW the list', () => {
    // `.wf-win-landing` is written for a row above the windows; the entry is the popup's last child,
    // so unflipped it drew a hairline against the popup's own bottom edge with 4px of plate under
    // it, and nothing separating it from the listbox above.
    const body = ruleFor('.wf-win-more');

    expect(decl(body, 'border-top')).toBe('1px solid var(--color-plex-border-light)');
    expect(decl(body, 'border-bottom')).toBe('none');
    expect(decl(body, 'margin-top')).toBe('4px');
    expect(decl(body, 'margin-bottom')).toBe('0');
  });
});

/**
 * The REGION panel — the drilldown's second level (map-landing-plan.md §3 L6).
 *
 * <p>Its frame is `.wf-win-panel`'s, verbatim, so everything above already applies to it. What is
 * pinned here is the two places its own head and rows have a DIFFERENT track count from the level
 * above — the exact shape of the L5 blocking defect, one head further along — plus the action row,
 * whose split is a browser measurement this file can only record the mechanism of.
 */
describe('the region panel differs from the level above only where its tracks do', () => {
  it('⚠️ gives the ✕ a FOURTH column, because this head has four and the two it borrows do not', () => {
    // `.wf-land-x` is `grid-column: 2` (the card's two-column head) and `.wf-win-panel-x` is 3 (the
    // window panel's three). This head is back / title / verdict / ✕. Inheriting either seats the
    // dismiss control in the middle of the row, which is what L5 shipped and measured.
    expect(decl(ruleFor('.wf-reg-panel-head'), 'grid-template-columns')).toBe('auto 1fr auto auto');
    expect(decl(ruleFor('.wf-reg-panel-x'), 'grid-column')).toBe('4');
    expect(decl(ruleFor('.wf-land-x'), 'grid-column')).toBe('2');
    expect(decl(ruleFor('.wf-win-panel-x'), 'grid-column')).toBe('3');
  });

  /**
   * ⚠️ **Borrowing a rule borrows ALL of its assumptions.** `.wf-land-x` carries THREE placement
   * declarations, and the first cut of `.wf-reg-panel-x` overrode one — leaving the ✕ start-aligned
   * across a phantom second row while the back arrow was centred in the first, measured 7.9px apart
   * at desktop and 25.1px at 320. Same defect class as the column one above, on the axis nobody
   * re-checked after fixing that one.
   */
  it('...and overrides the row span and the alignment it borrows with the column', () => {
    expect(decl(ruleFor('.wf-land-x'), 'grid-row')).toBe('1 / span 2');
    expect(decl(ruleFor('.wf-land-x'), 'align-self')).toBe('start');
    expect(decl(ruleFor('.wf-reg-panel-x'), 'grid-row')).toBe('1');
    // The head's two outer controls sit on one line as the title wraps beneath them.
    expect(decl(ruleFor('.wf-reg-panel-x'), 'align-self')).toBe('start');
    expect(decl(ruleFor('.wf-reg-back'), 'align-self')).toBe('start');
  });

  it('...and the back arrow the first column, so DOM order and visual order agree', () => {
    expect(decl(ruleFor('.wf-reg-back'), 'grid-column')).toBe('1');
  });

  it('drops to TWO row columns — a location has no verdict word of its own', () => {
    // A per-location verdict would be `mapLabels.verdictWord`'s 3.7/2.8, which §4 #4 refuses. The
    // stars column keeps the level above's 78px so the right-hand edge does not move as the reader
    // steps in and back out (measured identical at all fourteen widths).
    expect(decl(ruleFor('.wf-reg-row'), 'grid-template-columns')).toBe('minmax(0, 1fr) 78px');
    expect(decl(ruleFor('.wf-win-panel-row'), 'grid-template-columns')).toBe('minmax(0, 1fr) 62px 78px');
    expect(decl(ruleFor('.wf-reg-stars'), 'grid-column')).toBe('2');
    expect(decl(ruleFor('.wf-win-panel-best'), 'grid-column')).toBe('3');
  });

  /**
   * ⚠️ **The cascade itself, which asserting values cannot reach.** Each pair below lands on ONE
   * element carrying both classes, at equal specificity — so the winner is decided by source order
   * and nothing else. Measured: with these four assertions absent, moving `.wf-reg-panel-x` one line
   * above `.wf-land-x` kept every other test in this file green and put the ✕ back in the middle of
   * the head.
   */
  it('⚠️ every borrowed rule it overrides comes AFTER the rule it overrides', () => {
    beats('.wf-reg-panel-head', '.wf-win-panel-head');
    beats('.wf-reg-panel-x', '.wf-land-x');
    beats('.wf-reg-panel-x', '.wf-win-panel-x');
    beats('.wf-reg-row', '.wf-win-panel-row');
    beats('.wf-reg-stars', '.wf-win-panel-best');
  });

  /**
   * ⚠️ The panel is a capped scroller and this is its only navigation. Measured with 180px of chrome
   * above the frame: on an iPhone SE the actions sat 163px below the fold, on a 320×568 device 282px,
   * and on a landscape phone 275px — with an overlay scrollbar giving no cue anything was there.
   * L5 had no footer, so this is the first level where it could happen.
   */
  it('⚠️ pins the action row to the bottom of the scroller, over an OPAQUE plate', () => {
    const body = ruleFor('.wf-reg-actions');

    expect(decl(body, 'position')).toBe('sticky');
    expect(decl(body, 'bottom')).toBe('0');
    // A translucent sticky footer would show its own scrolling rows through it, so the plate is
    // composited over the panel's own background rather than left as a bare alpha.
    expect(decl(body, 'background')).toContain('var(--color-plex-panel)');
  });

  it('...including the last-row hairline, which cancels a border the rows rule sets', () => {
    // The last row's `border-bottom` met the gloss's and the actions' `border-top` at the same y,
    // painting 2px where every other separator on the panel is 1px. Pixel-sampled in Chromium.
    expect(decl(ruleFor('.wf-reg-panel .wf-win-panel-rows > :last-child'), 'border-bottom')).toBe('none');
    expect(orderOf('.wf-reg-panel .wf-win-panel-rows > :last-child'))
      .toBeGreaterThan(orderOf('.wf-win-panel-row'));
  });

  it('⚠️ lets the action row WRAP, and gives the long button the larger basis', () => {
    // Measured in Chromium at fourteen widths: under the design's own even `flex: 1` split the
    // `Four days at <full name>` label clipped at 768/700/430/390/375/320 — and printing that name
    // in full is the one thing this phase's spec says twice.
    expect(decl(ruleFor('.wf-reg-actions'), 'flex-wrap')).toBe('wrap');
    expect(decl(ruleFor('.wf-reg-action'), 'flex')).toBe('1 1 150px');
    expect(decl(ruleFor('.wf-reg-action-wide'), 'flex')).toBe('2 1 240px');
    // The ellipsis is the backstop, not the plan — a location name has no upper bound.
    expect(decl(ruleFor('.wf-reg-action'), 'text-overflow')).toBe('ellipsis');
  });

  it('takes the app\'s own action treatment, not the bundle\'s coloured fills', () => {
    // §4 #31: this bundle shipped a 1.24:1 CTA once, and `.wf-callout-actions button` is twenty
    // pixels away on the same map. The primary is weight and ink, no fill.
    expect(decl(ruleFor('.wf-reg-action'), 'background')).toBe(
      decl(ruleFor('.wf-callout-actions button'), 'background'),
    );
    expect(decl(ruleFor('.wf-reg-action'), 'color')).toBe(
      decl(ruleFor('.wf-callout-actions button'), 'color'),
    );
    expect(decl(ruleFor('.wf-reg-action-primary'), 'background')).toBeNull();
    expect(decl(ruleFor('.wf-reg-action-primary'), 'font-weight')).toBe('600');
  });

  it('sets the tide glyph on the app\'s objective-tide token, the one the map chip already uses', () => {
    expect(decl(ruleFor('.wf-reg-tide'), 'color')).toBe('var(--color-tide)');
    expect(decl(ruleFor('.wf-maplab-chip-tw'), 'color')).toBe('var(--color-tide)');
  });

  it('sets the narrative in the map\'s prose voice rather than the figures\' mono', () => {
    expect(decl(ruleFor('.wf-reg-gloss'), 'font-family')).toBe('var(--font-serif)');
    expect(decl(ruleFor('.wf-reg-gloss'), 'font-style')).toBe('italic');
  });
});
