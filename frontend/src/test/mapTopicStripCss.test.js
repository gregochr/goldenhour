import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * The four-day strip's stylesheet, asserted as TEXT ({@code vite.config.js} sets {@code css: false} and
 * jsdom has no layout engine; {@code comingUpFourDaysCss.test.js} does the same for the lanes). What is
 * pinned is what a later edit could quietly break:
 *
 * <ul>
 *   <li>nothing in the stylesheet reads {@code data-topic-strip} or lifts the counts footer from CSS
 *       (the footer is lifted by measurement in MapView; the landing card that once capped itself
 *       above the stack was removed by map-landing-nocard-plan.md P1);</li>
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

const stripStart = css.indexOf('.wf-topic-strip {');
const stripEnd = css.indexOf('.wf-map-counts-footer {', stripStart);
const stripCss = css.slice(stripStart, stripEnd);

describe('the four-day strip stylesheet: clearing the stack', () => {
  it('does NOT lift the counts footer from CSS: MapView moves it by measurement, only where it overlaps', () => {
    expect(css).not.toMatch(/data-topic-strip\][^{]*wf-map-counts-footer/);
    expect(css).not.toContain('--wf-chrome-bl-h, -8px');
  });

  it('reads data-topic-strip nowhere: its one reader was the landing card, now removed', () => {
    expect(css).not.toContain('data-topic-strip');
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

describe('the four-day strip stylesheet: the phone sheet variant (M4)', () => {
  it('declares every .wf-topic-strip-sheet rule inside a max-width: 639px query, with the 70px label column', () => {
    const outside = css.replace(/@media \(max-width: 639px\) \{[\s\S]*?\n\}\n/g, '');
    expect(outside).not.toContain('wf-topic-strip-sheet');
    const start = css.indexOf('.wf-topic-strip.wf-topic-strip-sheet {');
    expect(start).toBeGreaterThan(-1);
    expect(css.slice(css.lastIndexOf('@media', start), start)).toContain('(max-width: 639px)');
    expect(css).toContain('grid-template-columns: 70px repeat(8, minmax(0, 1fr));');
  });
});

describe('the four-day strip stylesheet: the sheet variant touch metrics (M4)', () => {
  const phone = mediaBlock('(max-width: 639px)', css.indexOf('.wf-topic-strip.wf-topic-strip-sheet {') - 200);
  const rule = (sel) => {
    const at = phone.indexOf(`${sel} {`);
    if (at === -1) throw new Error(`${sel} not in the sheet block`);
    return phone.slice(at, phone.indexOf('}', at));
  };

  it('uses 32px rows, 24px bars (4px margins) and 32px labels, with a clipped, ellipsised name', () => {
    expect(rule('.wf-topic-strip-sheet .wf-tl-grid')).toContain('grid-auto-rows: 32px');
    expect(rule('.wf-topic-strip-sheet .wf-tl-grid')).toContain('row-gap: 4px');
    expect(rule('.wf-topic-strip-sheet .wf-tl-b')).toContain('margin: 4px 2px');
    expect(rule('.wf-topic-strip-sheet .wf-tl-b.wf-tl-tall')).toContain('height: 24px');
    expect(rule('.wf-topic-strip-sheet .wf-tl-n')).toContain('height: 32px');
    expect(rule('.wf-topic-strip-sheet .wf-tl-n')).toContain('overflow: hidden');
    expect(rule('.wf-topic-strip-sheet .wf-tl-n')).toContain('min-width: 0');
    expect(rule('.wf-topic-strip-sheet .wf-tl-n > span:last-child')).toContain('text-overflow: ellipsis');
  });

  it('makes the HW tick a 44x16 label band above the bar row, clamped inside the day columns', () => {
    const tick = rule('.wf-topic-strip-sheet .wf-tl-hw button');
    expect(tick).toContain('height: 16px');
    expect(tick).toContain('width: 44px');
    expect(tick).toContain('left: clamp(0px, calc(var(--x) - 22px), calc(100% - 44px))');
    expect(rule('.wf-topic-strip-sheet .wf-tl-hw')).toContain('height: 44px');
    expect(rule('.wf-topic-strip-sheet .wf-tl-hw::before')).toContain('pointer-events: none');
  });
});
