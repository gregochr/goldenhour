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
