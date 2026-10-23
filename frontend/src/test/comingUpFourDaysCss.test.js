import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * The lanes' stylesheet, asserted as TEXT — {@code vite.config.js} sets {@code css: false} and jsdom
 * has no layout engine, so nothing here can measure a box ({@code heatTokens.test.js} reads
 * {@code index.css} the same way). It replaces the old handoff row's geometry test: that row was a
 * {@code <button>} that shrink-wrapped and needed its width pinned against its own margin, and the
 * lanes are a block-level {@code <section>}, so the trap is gone — what is pinned now is the part a
 * later edit could quietly break:
 *
 * <ul>
 *   <li>the desktop grid is label + four days + a jump column, and the phone grid is the same shape
 *       with its narrower columns (hot-topics-by-day README §1);</li>
 *   <li>every phone-only rule lives inside {@code @media (max-width: 639px)}, so desktop and tablet
 *       are provably untouched (CLAUDE.md "phone-only work stays phone-only");</li>
 *   <li>the topic colour is only ever read from {@code --c}, never a literal hex.</li>
 * </ul>
 *
 * <p>The geometry itself (a symmetric inset, no overflow at 390px) is a browser measurement and
 * belongs to the browser check.
 */
// The path goes through a parameter, never a literal: Vite statically rewrites
// `new URL('<literal>', import.meta.url)` into an asset reference, which is not a file URL.
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');
const css = read('../index.css');

/** The text of the first rule with exactly this selector (the one before the next `}`). */
const ruleBody = (selector) => {
  const start = css.indexOf(`\n${selector} {`);
  if (start === -1) throw new Error(`${selector} is not declared in index.css`);
  return css.slice(start, css.indexOf('}', start));
};

/** The body of the first `@media` block whose query is exactly this, brace-matched. */
const mediaBlock = (query, from = 0) => {
  const start = css.indexOf(`@media ${query} {`, from);
  if (start === -1) throw new Error(`@media ${query} not found after ${from}`);
  let depth = 0;
  for (let i = css.indexOf('{', start); i < css.length; i += 1) {
    if (css[i] === '{') depth += 1;
    if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) return css.slice(start, i + 1);
    }
  }
  throw new Error('unbalanced');
};

const lanesStart = css.indexOf('The "Next four days" lanes (hot-topics-by-day-plan C1');
const lanesEnd = css.indexOf('.wf-cu-head {', lanesStart);
const lanesCss = css.slice(lanesStart, lanesEnd);

describe('the "Next four days" lanes stylesheet', () => {
  it('declares the desktop grid as label, four days and a 24px jump column', () => {
    expect(ruleBody('.wf-cu-fd-grid'))
      .toContain('grid-template-columns: 118px repeat(4, minmax(0, 1fr)) 24px;');
  });

  it('declares the phone grid as 76px label, four days and a 24px jump column, in the phone query only', () => {
    const phone = mediaBlock('(max-width: 639px)', lanesStart);
    expect(phone).toContain('.wf-cu-fd-grid { grid-template-columns: 76px repeat(4, minmax(0, 1fr)) 24px; }');
    // The jump column stays a 24px target on the phone: no narrower width is declared there.
    expect(phone).not.toMatch(/\.wf-cu-fd-jump \{[^}]*width/);
    // Outside the query, the desktop columns are the only ones declared.
    const outside = lanesCss.replace(phone, '').replace(/\/\*[\s\S]*?\*\//g, '');
    expect(outside).not.toContain('76px');
  });

  it('hides the bar text and shows the short name only inside the phone query', () => {
    const phone = mediaBlock('(max-width: 639px)', lanesStart);
    expect(phone).toContain('.wf-cu-fd-bt { display: none; }');
    expect(phone).toContain('.wf-cu-fd-ns { display: inline; }');
    expect(phone).toContain('.wf-cu-fd-nl { display: none; }');
    expect(phone).toContain('.wf-cu-fd-pk { font-size: 9px; }');
    // The default hides the short name, so desktop reads the full one.
    expect(lanesCss).toContain('.wf-cu-fd-ns { display: none; }');
  });

  it('never states a topic colour as a literal — only through --c', () => {
    // The lanes block has hexes in no declaration at all: its colours are tokens, rgba washes and --c.
    expect(lanesCss).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(lanesCss).toContain('var(--c)');
  });

  it('gives bars and jump buttons a visible border in forced-colors mode', () => {
    const forced = mediaBlock('(forced-colors: active)', lanesStart);
    expect(forced).toContain('.wf-cu-fd-bar');
    expect(forced).toContain('border-color: CanvasText;');
  });

  it('keeps every target at least 24x24: the dot button, the jump button and the bars', () => {
    expect(ruleBody('.wf-cu-fd-dot')).toMatch(/width: 24px;[\s\S]*height: 24px;/);
    const jump = ruleBody('.wf-cu-fd-jump');
    expect(jump).toContain('width: 24px;');
    expect(jump).toContain('height: 24px;');
    expect(ruleBody('.wf-cu-fd-bar')).toContain('height: 24px;');
  });

  it('places the static items in CSS: day headers in row 1, columns 2–5, label in column 1, jump in column 6', () => {
    expect(ruleBody('.wf-cu-fd-day')).toContain('grid-row: 1;');
    for (const [col, track] of [['0', 2], ['1', 3], ['2', 4], ['3', 5]]) {
      expect(lanesCss).toContain(`.wf-cu-fd-day[data-col='${col}'] { grid-column: ${track}; }`);
    }
    expect(ruleBody('.wf-cu-fd-n')).toContain('grid-column: 1;');
    expect(ruleBody('.wf-cu-fd-jump')).toContain('grid-column: 6;');
    expect(ruleBody('.wf-cu-fd-now')).toContain('grid-column: 2;');
  });

  it('keeps the today shade out of hit-testing', () => {
    expect(ruleBody('.wf-cu-fd-now')).toContain('pointer-events: none;');
  });

  it('gives an open bar a dashed right edge, squared off and butting the jump button', () => {
    const open = ruleBody(".wf-cu-fd-bar[data-open='true']");
    expect(open).toContain('margin-right: 0;');
    expect(open).toContain('border-right-style: dashed;');
    expect(open).toContain('border-radius: 5px 0 0 5px;');
  });

  it('lets the bar text give way before the peak, and the label names ellipsise', () => {
    const text = ruleBody('.wf-cu-fd-bt');
    expect(text).toContain('flex: 0 1 auto;');
    expect(text).toContain('text-overflow: ellipsis;');
    expect(ruleBody('.wf-cu-fd-pk')).toContain('flex: none;');
    expect(lanesCss).toMatch(/\.wf-cu-fd-nl,\s*\.wf-cu-fd-ns \{[^}]*text-overflow: ellipsis;/);
  });

  it('sets the day date in the sans face at 700 / 15px, as the design does', () => {
    const date = ruleBody('.wf-cu-fd-day b');
    expect(date).toContain('font-family: var(--font-sans);');
    expect(date).toContain('font-weight: 700;');
    expect(date).toContain('font-size: 15px;');
  });

  it('keeps the forced-colours cues: a dot border and a heavier today header', () => {
    const forced = mediaBlock('(forced-colors: active)', lanesStart);
    expect(forced).toContain('.wf-cu-fd-dv { border: 1px solid CanvasText; }');
    expect(forced).toContain(".wf-cu-fd-day[data-today='true'] { border-width: 2px; }");
  });

  it('insets the emphasis outline so the popup\'s scroll box cannot clip it', () => {
    expect(ruleBody(".wf-trow[data-emphasis='true']")).toContain('outline-offset: -2px;');
  });

  it('is a block-level container that funds its own inset with a margin and sets no width', () => {
    const body = ruleBody('.wf-cu-fd');
    expect(body).toContain('margin: 14px 14px 0;');
    expect(body).not.toMatch(/\bwidth:/);
  });

  it('leaves the retired handoff row\'s selectors out of the stylesheet', () => {
    expect(css).not.toContain('.wf-cu-handoff');
  });
});
