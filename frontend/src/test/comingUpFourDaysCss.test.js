import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * The day tiles' stylesheet, asserted as TEXT — {@code vite.config.js} sets {@code css: false} and
 * jsdom has no layout engine, so nothing here can measure a box ({@code heatTokens.test.js} reads
 * {@code index.css} the same way). What is pinned is the part a later edit could quietly break
 * (coming-up-glance-plan T1):
 *
 * <ul>
 *   <li>the tiles are always four equal columns, and the phone changes only sizes inside
 *       {@code @media (max-width: 639px)}, so desktop and tablet are provably untouched (CLAUDE.md
 *       "phone-only work stays phone-only");</li>
 *   <li>every desktop / phone span pair has exactly one half hidden per media query;</li>
 *   <li>the topic colour is only ever read from {@code --c}, never a literal hex;</li>
 *   <li>forced colours keep a swatch border, a dashed continuation chip and a real focus outline.</li>
 * </ul>
 *
 * <p>The geometry itself (no overflow at 375px, no ellipsis on a phone name) is a browser
 * measurement and belongs to the browser check.
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

const tilesStart = css.indexOf('The "Next four days" day tiles (coming-up-glance-plan T1');
const tilesEnd = css.indexOf('.wf-cu-head {', tilesStart);
const tilesCss = css.slice(tilesStart, tilesEnd);
const phone = mediaBlock('(max-width: 639px)', tilesStart);
const outsidePhone = tilesCss.replace(phone, '').replace(/\/\*[\s\S]*?\*\//g, '');

describe('the "Next four days" day tiles stylesheet', () => {
  it('declares the tiles as four equal columns, with the README\'s desktop and phone gaps', () => {
    expect(ruleBody('.wf-cu-fd-tiles')).toContain('grid-template-columns: repeat(4, minmax(0, 1fr));');
    expect(ruleBody('.wf-cu-fd-tiles')).toContain('gap: 8px;');
    expect(phone).toContain('.wf-cu-fd-tiles { gap: 5px; }');
    // No horizontal scroll, and the column count never changes.
    expect(tilesCss).not.toContain('overflow-x');
    expect(phone).not.toContain('grid-template-columns');
  });

  it('is a block-level container that funds its own inset with a margin and sets no width', () => {
    const body = ruleBody('.wf-cu-fd');
    expect(body).toContain('margin: 14px 14px 0;');
    expect(body).toContain('padding: 12px 14px;');
    expect(body).toContain('border-radius: 10px;');
    expect(body).not.toMatch(/\bwidth:/);
  });

  it('sets the tile and chip geometry from the README', () => {
    expect(ruleBody('.wf-cu-fd-tile')).toContain('padding: 8px 10px 10px;');
    expect(ruleBody('.wf-cu-fd-tile')).toContain('border-radius: 9px;');
    expect(ruleBody('.wf-cu-fd-chip')).toContain('padding: 4px 7px;');
    expect(ruleBody('.wf-cu-fd-chip')).toContain('border-radius: 5px;');
    expect(ruleBody('.wf-cu-fd-chip')).toContain('color-mix(in oklch, var(--c) 13%, transparent)');
    expect(ruleBody('.wf-cu-fd-sw')).toContain('width: 7px;');
    expect(ruleBody('.wf-cu-fd-sw')).toContain('border-radius: 2px;');
  });

  it('puts every phone-only size inside the phone query, and none outside it', () => {
    expect(phone).toContain('.wf-cu-fd-tile { padding: 6px 5px 7px; }');
    expect(phone).toContain('.wf-cu-fd-dnum { font-size: 16px; }');
    expect(phone).toMatch(/\.wf-cu-fd-chip \{[^}]*padding: 3px 5px;/);
    expect(phone).toContain('font-size: 10.5px;');
    expect(phone).toContain('font-size: 8.5px;');
    // Outside the query, nothing declares a phone value.
    expect(outsidePhone).not.toContain('padding: 6px 5px 7px');
    expect(outsidePhone).not.toContain('padding: 3px 5px');
    expect(outsidePhone).not.toContain('font-size: 16px');
  });

  it('shows one half of each desktop / phone pair per media query', () => {
    // Names, values and the relative label: the phone half is hidden by default and shown on the phone.
    for (const [desktop, short] of [['nl', 'ns'], ['vl', 'vs'], ['rel-l', 'rel-s']]) {
      expect(outsidePhone).toContain(`.wf-cu-fd-${short} { display: none; }`);
      expect(phone).toContain(`.wf-cu-fd-${desktop} { display: none; }`);
      expect(phone).toContain(`.wf-cu-fd-${short} { display: inline; }`);
    }
  });

  it('hides the suffix and the badge and WX boxes on the phone only, and stacks the relative label', () => {
    expect(phone).toContain('.wf-cu-fd-count { display: none; }');
    expect(phone).toMatch(/\.wf-cu-fd-badge,\s*\.wf-cu-fd-wx \{ display: none; \}/);
    expect(outsidePhone).not.toMatch(/\.wf-cu-fd-(badge|wx|count) \{[^}]*display: none/);
    expect(phone).toMatch(/\.wf-cu-fd-rel \{[^}]*font-size: 9px;[^}]*text-align: left;/);
    expect(phone).toMatch(/\.wf-cu-fd-dh \{ flex-direction: column; align-items: flex-start;/);
  });

  it('never hides a desktop half (nl, vl, rel-l) outside the phone query', () => {
    for (const half of ['nl', 'vl', 'rel-l']) {
      expect(outsidePhone, half).not.toMatch(new RegExp(`\\.wf-cu-fd-${half}\\b[^{]*\\{[^}]*display: none`));
    }
  });

  it('keeps the phone value on one line, flush, and the wrap and overflow overrides phone-only', () => {
    expect(phone).toMatch(/\.wf-cu-fd-val \{[^}]*padding-left: 0;[^}]*white-space: nowrap;/);
    expect(phone).toMatch(/\.wf-cu-fd-val \{[^}]*overflow: visible;/);
    expect(outsidePhone).not.toContain('overflow: visible');
    expect(outsidePhone).not.toContain('white-space: normal');
  });

  it('keeps an ellipsis safety net on the phone name, for an unknown type\'s full-label fallback', () => {
    expect(phone).toMatch(/\.wf-cu-fd-name \{[^}]*overflow: hidden;[^}]*text-overflow: ellipsis;/);
  });

  it('gives the phone 24px tap targets for the chip and the At a Glance link', () => {
    expect(phone).toMatch(/\.wf-cu-fd-chip \{[^}]*min-height: 24px;/);
    expect(phone).toMatch(/\.wf-cu-fd-plan \{[^}]*min-height: 24px;/);
  });

  it('keeps the badge text mixed toward the ink for contrast, and the WX text on ink-2', () => {
    expect(ruleBody('.wf-cu-fd-badge')).toContain(
      'color: color-mix(in oklch, var(--c) 70%, var(--color-plex-text));',
    );
    expect(ruleBody('.wf-cu-fd-wx')).toContain('color: var(--fd-ink-2);');
  });

  it('gives forced colours a hover cue', () => {
    expect(mediaBlock('(forced-colors: active)', tilesStart))
      .toContain('.wf-cu-fd-chip:hover { outline: 1px solid Highlight; }');
  });

  it('never states a topic colour as a literal — only through --c', () => {
    expect(tilesCss).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(tilesCss).toContain('var(--c)');
  });

  it('draws a continuation chip dashed and transparent', () => {
    const body = ruleBody(".wf-cu-fd-chip[data-continuation='true']");
    expect(body).toContain('border-style: dashed;');
    expect(body).toContain('background: transparent;');
    expect(body).toContain('color-mix(in oklch, var(--c) 45%, transparent)');
  });

  it('raises the border and background on hover and focus', () => {
    const hover = ruleBody('.wf-cu-fd-chip:hover');
    expect(hover).toContain('border-color: var(--c);');
    expect(hover).toContain('color-mix(in oklch, var(--c) 20%, transparent)');
  });

  it('gives focus-visible a real outline, and keeps the forced-colours cues', () => {
    // The outline rule is the one whose LAST selector is the plan link's.
    expect(ruleBody('.wf-cu-fd-plan:focus-visible')).toContain('outline: 2px solid var(--color-plex-gold);');
    expect(css).toContain('.wf-cu-fd-chip:focus-visible,\n.wf-cu-fd-plan:focus-visible {');
    const forced = mediaBlock('(forced-colors: active)', tilesStart);
    expect(forced).toContain('.wf-cu-fd-sw { border: 1px solid CanvasText; }');
    expect(forced).toContain("border-style: dashed; border-color: CanvasText;");
    expect(forced).toContain('outline: 2px solid Highlight;');
    expect(forced).toContain(".wf-cu-fd-tile[data-today='true'] { border-width: 2px; }");
  });

  it('leaves no rule of the retired lanes in the stylesheet', () => {
    for (const retired of ['.wf-cu-fd-grid', '.wf-cu-fd-bar', '.wf-cu-fd-dot', '.wf-cu-fd-jump',
      '.wf-cu-fd-now', '.wf-cu-fd-seg', '.wf-cu-fd-bt', '.wf-cu-fd-pk', '.wf-cu-handoff']) {
      expect(css, retired).not.toContain(retired);
    }
  });

  it('insets the emphasis outline so the popup\'s scroll box cannot clip it', () => {
    expect(ruleBody(".wf-trow[data-emphasis='true']")).toContain('outline-offset: -2px;');
  });
});

describe('the "In next 4 days" tag stylesheet (coming-up-glance-plan T2)', () => {
  it('styles the tag per the README', () => {
    const body = ruleBody('.wf-cu-next4');
    expect(body).toContain('font-size: 8.5px;');
    expect(body).toContain('letter-spacing: 0.08em;');
    expect(body).toContain('text-transform: uppercase;');
    expect(body).toContain('padding: 1px 5px;');
    expect(body).toContain('border-radius: 3px;');
    expect(body).toContain('color-mix(in oklch, #6FA8B0 22%, transparent)');
    expect(body).toContain('1px solid color-mix(in oklch, #6FA8B0 55%, transparent)');
  });

  it('puts the title on its own line only inside the phone media query', () => {
    const start = css.indexOf('\n.wf-cu-next4 {');
    const phoneBlock = mediaBlock('(max-width: 639px)', start);
    expect(phoneBlock).toContain('.wf-cu-nm { flex: 1 1 calc(100% - 28px); min-width: 0; }');
    expect(css.replace(phoneBlock, '')).not.toContain('flex: 1 1 calc(100% - 28px)');
  });
});
