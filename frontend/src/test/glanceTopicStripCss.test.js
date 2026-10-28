import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * The day header's topic pills, asserted as TEXT — {@code vite.config.js} sets {@code css: false} and jsdom has no
 * layout engine, so nothing here can measure a box ({@code heatTokens.test.js} reads {@code index.css} the same way).
 * What is pinned is the part a later edit could quietly break (glance-topic-strip-plan.md):
 *
 * <ul>
 *   <li>the topic row is a NON-sticky row of the same grid as the card rows — never inside the sticky, measured
 *       {@code .wf-dhrow};</li>
 *   <li>the pill's solid and dashed states, the topic colour only through {@code --c};</li>
 *   <li>every desktop / phone span pair has exactly one half hidden per media query, and the phone rules live in
 *       {@code @media (max-width: 639px)} only (CLAUDE.md "phone-only work stays phone-only");</li>
 *   <li>forced colours keep a swatch border, a dashed continuation pill and a real focus outline.</li>
 * </ul>
 *
 * <p>The geometry itself (no overflow at 375px, no ellipsis on a phone name) is a browser measurement.
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

const stripStart = css.indexOf('The day header\'s topic pills (glance-topic-strip-plan.md');
const stripEnd = css.indexOf('/* An empty cell says why it is empty', stripStart);
const stripCss = css.slice(stripStart, stripEnd);
const phone = mediaBlock('(max-width: 639px)', stripStart);
const outsidePhone = stripCss.replace(phone, '').replace(/\/\*[\s\S]*?\*\//g, '');

describe('the day header\'s topic pills stylesheet', () => {
  it('lays the topic row out in the same grid as the day and card rows, and never makes it sticky', () => {
    const grid = css.indexOf(
      '.wf-hstrip.wf-hstrip-rows .wf-dhrow,\n.wf-hstrip.wf-hstrip-rows .wf-dtrow,\n.wf-hstrip.wf-hstrip-rows .wf-hcards {',
    );
    expect(grid, 'the rows-mode grid rule must name .wf-dtrow').toBeGreaterThan(-1);
    const body = css.slice(grid, css.indexOf('\n}', grid));
    expect(body).toContain('repeat(var(--dc, 4), minmax(0, 1fr))');
    expect(body).toContain('gap: 8px;');
    expect(ruleBody('.wf-dtrow')).not.toMatch(/position:\s*sticky/);
    expect(ruleBody('.wf-dtrow')).not.toContain('z-index');
    // The sticky, measured row is left exactly as it was: it holds the date chips only.
    expect(ruleBody('.wf-dhrow')).toContain('position: sticky;');
    expect(css).toContain('.wf-hstrip.wf-hstrip-rows .wf-dtrow > *,');
  });

  it('declares the pill geometry from the handoff', () => {
    const body = ruleBody('.wf-dt-pill');
    expect(body).toContain('grid-template-columns: auto 1fr auto;');
    expect(body).toContain('column-gap: 8px;');
    expect(body).toContain('padding: 6px 10px;');
    expect(body).toContain('border-radius: 8px;');
    expect(body).toContain('color-mix(in oklch, var(--c) 8%, transparent)');
    expect(body).toContain('1px solid color-mix(in oklch, var(--c) 22%, transparent)');
    expect(ruleBody('.wf-dt-sw')).toContain('width: 8px;');
    expect(ruleBody('.wf-dt-sw')).toContain('height: 8px;');
    expect(ruleBody('.wf-dt-sw')).toContain('border-radius: 2px;');
    expect(ruleBody('.wf-dt-val')).toContain('grid-column: 2 / -1;');
  });

  it('draws a continuation pill dashed, transparent, with a lighter name', () => {
    const body = ruleBody(".wf-dt-pill[data-continuation='true']");
    expect(body).toContain('border-style: dashed;');
    expect(body).toContain('background: transparent;');
    expect(body).toContain('color-mix(in srgb, var(--color-plex-text) 28%, transparent)');
    const name = ruleBody(".wf-dt-pill[data-continuation='true'] .wf-dt-name");
    expect(name).toContain('font-weight: 500;');
    expect(name).toContain('color: var(--color-plex-text-secondary);');
  });

  it('takes the border to the topic colour at 50% on hover and focus', () => {
    expect(ruleBody('.wf-dt-pill:hover,\n.wf-dt-pill:focus-visible'))
      .toContain('color-mix(in oklch, var(--c) 50%, transparent)');
  });

  it('never states a topic colour as a literal — only through --c', () => {
    expect(stripCss.replace(/\/\*[\s\S]*?\*\//g, '')).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(stripCss).toContain('var(--c)');
  });

  it('puts every phone-only rule inside the phone query, and none outside it', () => {
    expect(phone).toContain('.wf-hstrip > .wf-dtcell { grid-column: 1 / -1; }');
    expect(phone).toContain('.wf-dt-pill { min-height: 24px; }');
    expect(outsidePhone).not.toContain('min-height: 24px');
    expect(outsidePhone).not.toContain('.wf-dtcell { grid-column: 1 / -1; }');
  });

  it('has no short-form or tag-hiding rules anywhere, so the phone shows what desktop shows', () => {
    expect(css).not.toMatch(/\.wf-dt-(nl|ns|vl|vs)\b/);
    expect(phone).not.toContain('.wf-dt-tags');
    expect(outsidePhone).not.toMatch(/\.wf-dt-tags \{[^}]*display: none/);
  });

  it('keeps the badge text mixed toward the ink for contrast, and the WX box on ink-2', () => {
    expect(ruleBody('.wf-dt-badge')).toContain(
      'color: color-mix(in oklch, var(--c) 70%, var(--color-plex-text));',
    );
    // `.wf-dt-wx` also ends the shared type rule above; its own rule is the one that opens with the padding.
    const start = css.indexOf('\n.wf-dt-wx {\n  padding');
    expect(start).toBeGreaterThan(-1);
    const wx = css.slice(start, css.indexOf('}', start));
    expect(wx).toContain('color: var(--color-plex-text-secondary);');
    expect(wx).toContain('border-radius: 3px;');
    expect(wx).toContain('padding: 0 4px;');
  });

  it('gives forced colours a real edge, a dashed continuation, a swatch border and a hover cue', () => {
    const forced = mediaBlock('(forced-colors: active)', stripStart);
    expect(forced).toContain('.wf-dt-pill { border-color: CanvasText; }');
    expect(forced).toContain("border-style: dashed; border-color: CanvasText;");
    expect(forced).toContain('.wf-dt-sw { border: 1px solid CanvasText; }');
    expect(forced).toContain('.wf-dt-pill:hover { outline: 1px solid Highlight; }');
    expect(forced).toContain('outline: 2px solid Highlight;');
  });

  it('gives focus-visible a real outline on a pill and the door', () => {
    expect(css).toContain('.wf-dt-pill:focus-visible,\n.wf-dt-door:focus-visible {');
    expect(ruleBody('.wf-dt-pill:focus-visible,\n.wf-dt-door:focus-visible'))
      .toContain('outline: 2px solid var(--color-plex-gold);');
  });

  it('draws the key marks as the handoff does: a 2x12 bar and a dotted left border', () => {
    expect(ruleBody('.wf-dt-key-solid')).toContain('width: 2px;');
    expect(ruleBody('.wf-dt-key-solid')).toContain('height: 12px;');
    expect(ruleBody('.wf-dt-key-dashed')).toContain('border-left: 2px dotted');
    expect(ruleBody('.wf-dt-key-dashed')).toContain('height: 12px;');
  });

  it('leaves no rule of the retired Coming up block in the stylesheet', () => {
    expect(css).not.toContain('wf-cu-fd');
  });
});
