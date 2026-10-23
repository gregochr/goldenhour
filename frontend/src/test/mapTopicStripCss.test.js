import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * The four-day strip's stylesheet, asserted as TEXT ({@code vite.config.js} sets {@code css: false} and
 * jsdom has no layout engine; {@code comingUpFourDaysCss.test.js} does the same for the lanes). What is
 * pinned is what a later edit could quietly break:
 *
 * <ul>
 *   <li>the footer and the landing card clear the whole bottom-left stack, desktop and tablet only,
 *       and AFTER the tide rule they must beat at equal specificity;</li>
 *   <li>forced colours: every bar keeps a 1px border, and the current and selected states are an
 *       {@code outline} (background and box-shadow are removed in that mode);</li>
 *   <li>the colours are tokens, rgba washes and {@code --wf-tc}, never a hex from the design mock.</li>
 * </ul>
 *
 * The geometry itself (440px, no overlap at 768px) is a browser measurement.
 */
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');
const css = read('../index.css').replace(/\/\*[\s\S]*?\*\//g, '');

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

const ruleBody = (selector) => {
  const start = css.indexOf(`\n${selector} {`);
  if (start === -1) throw new Error(`${selector} is not declared in index.css`);
  return css.slice(start, css.indexOf('}', start));
};

const tideRuleAt = css.indexOf('.wf-map-tab.wf-tide-strip-on .wf-map-counts-footer {');
const stripStart = css.indexOf('.wf-topic-strip {');
const stripEnd = css.indexOf('.wf-map-counts-footer {', stripStart);
const stripCss = css.slice(stripStart, stripEnd);

describe('the four-day strip stylesheet: clearing the stack', () => {
  const lift = mediaBlock('(min-width: 640px)', tideRuleAt);

  it('does NOT lift the counts footer from CSS: MapView moves it by measurement, only where it overlaps', () => {
    expect(css).not.toMatch(/data-topic-strip\][^{]*wf-map-counts-footer/);
    expect(css).not.toContain('--wf-chrome-bl-h, -8px');
  });

  it('caps the landing card above the stack; the 36px fallback gives the old -76px when unset', () => {
    expect(lift).toContain('.wf-map-tab[data-topic-strip] .wf-land');
    expect(lift).toContain(
      'max-height: calc(100% - var(--wf-chrome-tl-h, 36px) - 24px - var(--wf-chrome-bl-h, 36px) - 16px);',
    );
    // 24 + 36 + 16 = 76: exactly the card's own base cap.
    expect(css).toContain('max-height: calc(100% - var(--wf-chrome-tl-h, 36px) - 76px);');
  });

  it('declares nothing about the card outside the desktop/tablet query', () => {
    expect(css.replace(lift, '')).not.toContain('[data-topic-strip]');
  });
});

describe('the four-day strip stylesheet: the strip', () => {
  it('is a 440px chrome-tier flex child, not separately positioned, with no max-width', () => {
    const body = ruleBody('.wf-topic-strip');
    expect(body).toContain('width: 440px;');
    expect(body).not.toContain('max-width');
    expect(body).toContain('z-index: 1100;');
    expect(body).toContain('position: relative;');
    expect(body).not.toContain('absolute');
  });

  it('lays the grid out as a 92px label column and eight equal columns, topic rows 24px', () => {
    const body = ruleBody('.wf-tl-grid');
    expect(body).toContain('grid-template-columns: 92px repeat(8, minmax(0, 1fr));');
    expect(body).toContain('grid-auto-rows: 24px;');
  });

  it('keeps the shades out of hit-testing', () => {
    expect(ruleBody('.wf-tl-now')).toContain('pointer-events: none;');
    expect(ruleBody('.wf-tl-past')).toContain('pointer-events: none;');
  });

  it('reads the topic colour only from --wf-tc, and no hex appears anywhere in the block', () => {
    expect(stripCss).toContain('var(--wf-tc)');
    expect(stripCss).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
  });
});

describe('the four-day strip stylesheet: forced colours', () => {
  const forced = mediaBlock('(forced-colors: active)', css.indexOf('.wf-tl-b[data-selected'));

  it('gives every bar a 1px border', () => {
    expect(ruleBody('.wf-tl-b')).toContain('border: 1px solid');
    expect(forced).toContain('.wf-tl-b { border: 1px solid CanvasText; }');
  });

  it('draws the current and selected states as an outline', () => {
    expect(forced).toContain(".wf-tl-b[data-current='true'] { outline: 2px solid Highlight;");
    expect(forced).toContain(".wf-tl-b[data-selected='true'] { outline: 3px double Highlight;");
    expect(forced).toContain(".wf-tl-n[data-selected='true'] { outline: 2px solid Highlight; }");
  });

  it('keeps a focus ring on the current and selected states, which the outlines above would replace', () => {
    // Same specificity and later wins, so each state needs its own :focus-visible rule in this block.
    expect(forced).toContain(".wf-tl-b[data-current='true']:focus-visible");
    expect(forced).toContain(".wf-tl-b[data-selected='true']:focus-visible");
    expect(forced).toContain(".wf-tl-n[data-selected='true']:focus-visible");
    expect(forced).toContain('outline: 3px solid CanvasText; outline-offset: 3px;');
    expect(forced.indexOf(':focus-visible'))
      .toBeGreaterThan(forced.indexOf(".wf-tl-n[data-selected='true'] { outline"));
  });

  it('keeps the current-column shade visible without a background', () => {
    expect(forced).toContain('.wf-tl-now');
    expect(forced).toContain('border-left: 1px dashed CanvasText;');
  });
});
