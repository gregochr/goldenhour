import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, afterEach } from 'vitest';

/**
 * The big-seas line's stylesheet contract, asserted against the REAL `index.css`
 * (big-seas plan F1): its width on desktop and phone, the tokens, and the two offsets that used to
 * assume a 36px control (`.wf-win-panel`).
 *
 * <p>jsdom implements no layout and resolves no `var()`, so what is asserted is the mechanism: the
 * declared values, and the arithmetic of `calc(var(--wf-chrome-tl-h, Npx) + Mpx)` evaluated here
 * with and without the variable set. "Sits below the line when it shows, unchanged when it doesn't"
 * is that arithmetic; where the browser puts the pixels is a browser claim.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** `index.css` with comments stripped — they carry both braces and the class names. */
function readCss() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/** Every top-level `{selector, bodyInner}` block in source order (an `@media` carries its rules). */
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
 * The rules whose selector list contains `selector` exactly, concatenated in source order.
 * `phone: false` keeps top-level rules only; `phone: true` also unwraps the `max-width: 639px`
 * media blocks, which is the cascade once that query matches.
 */
function rulesFor(selector, { phone }) {
  const out = [];
  const take = (b) => {
    if (b.selector.split(',').map((s) => s.trim()).includes(selector)) {
      out.push(`${b.selector} {${b.bodyInner}}`);
    }
  };
  for (const block of sliceTopLevelBlocks(readCss())) {
    if (!block.selector.startsWith('@')) take(block);
    else if (phone && block.selector.includes('max-width: 639px')) {
      sliceTopLevelBlocks(block.bodyInner).forEach(take);
    }
  }
  return out.join('\n');
}

/** The value of `prop` in the LAST rule for `selector` (media included when `phone`). */
function declOf(selector, prop, { phone = false } = {}) {
  const slice = rulesFor(selector, { phone });
  expect(slice, `no rule for ${selector}`).not.toBe('');
  const found = [...slice.matchAll(new RegExp(`(?:^|[;{\\s])${prop}\\s*:\\s*([^;}]+)`, 'g'))];
  expect(found.length, `no ${prop} on ${selector}`).toBeGreaterThan(0);
  return found[found.length - 1][1].trim();
}

/**
 * Evaluates a `calc()` of px terms and one `100%` against a frame of `frame` px, resolving
 * `var(--wf-chrome-tl-h, Npx)` to `h` when given, else to its fallback N.
 */
function evalCalc(value, { h = null, frame = 1000 } = {}) {
  const inner = value.replace(/^calc\(/, '').replace(/\)$/, '');
  const resolved = inner.replace(/var\(--wf-chrome-tl-h,\s*(\d+)px\)/g, (_, fb) => `${h ?? fb}px`);
  const tokens = resolved.replace(/100%/g, `${frame}px`).match(/[+-]?\s*\d+(?:\.\d+)?px/g);
  return tokens.reduce((sum, t) => sum + parseFloat(t.replace(/\s+/g, '').replace('px', '')), 0);
}

/** A plain `Npx` or a calc, as a number. */
function px(value, opts) {
  return value.startsWith('calc(') ? evalCalc(value, opts) : parseFloat(value);
}

let cleanups = [];
afterEach(() => {
  cleanups.forEach((fn) => fn());
  cleanups = [];
});

function computedFor(slice, className) {
  const style = document.createElement('style');
  style.textContent = slice;
  document.head.appendChild(style);
  const el = document.createElement('div');
  el.className = className;
  document.body.appendChild(el);
  cleanups.push(() => { style.remove(); el.remove(); });
  return getComputedStyle(el);
}

describe('the big-seas line: width', () => {
  it('is bounded at the control\'s 504px on desktop, so the label obstacle does not widen', () => {
    const style = computedFor(rulesFor('.wf-big-seas', { phone: false }), 'wf-big-seas');
    expect(style.maxWidth).toBe('504px');
    expect(style.width).toBe('100%');
    expect(style.display).toBe('flex');
  });

  it('runs the control\'s full width on a phone: the 504px bound is released', () => {
    const style = computedFor(rulesFor('.wf-big-seas', { phone: true }), 'wf-big-seas');
    expect(style.maxWidth).toBe('none');
    expect(style.width).toBe('100%');
  });

  it('wraps its text but never its badge or its action', () => {
    const badge = rulesFor('.wf-big-seas-badge', { phone: true });
    expect(computedFor(badge, 'wf-big-seas-badge').whiteSpace).toBe('nowrap');
    const go = rulesFor('.wf-big-seas-go', { phone: true });
    expect(computedFor(go, 'wf-big-seas-go').whiteSpace).toBe('nowrap');
    const text = rulesFor('.wf-big-seas-text', { phone: true });
    expect(computedFor(text, 'wf-big-seas-text').minWidth).toBe('0px');
  });

  it('sits inside the chrome group (not a new positioned layer): no position or z-index of its own', () => {
    const body = rulesFor('.wf-big-seas', { phone: true });
    expect(body).not.toMatch(/position\s*:/);
    expect(body).not.toMatch(/z-index\s*:/);
  });
});

describe('the big-seas tokens', () => {
  const css = readCss();

  it('adds the two tide tints beside --color-tide, in the --color-* convention', () => {
    expect(css).toMatch(/--color-tide:\s*#6FA8B0;/);
    expect(css).toMatch(/--color-tide-light:\s*#8FC0C7;/);
    expect(css).toMatch(/--color-tide-pale:\s*#A9D6DC;/);
  });

  it('never draws the storm-surge amber in the big-seas rules', () => {
    const blocks = sliceTopLevelBlocks(css).filter((b) => /wf-big-seas|wf-win-row-bigseas/.test(b.selector));
    expect(blocks.length).toBeGreaterThan(0);
    for (const b of blocks) expect(b.bodyInner.toLowerCase()).not.toContain('#f59e0b');
  });
});

describe('the offsets that assumed a 36px control (the window panel)', () => {
  const CASES = [
    // [selector, phone?, fallback control height, old px constant, air under the chrome (8 top + gap)]
    ['.wf-win-panel', false, 36, 50, 14],
    ['.wf-win-panel', true, 40, 54, 14],
  ];

  it.each(CASES)('%s (phone: %s) is its old constant when the variable is unset', (sel, phone, fb, old) => {
    const top = declOf(sel, 'top', { phone });
    expect(top).toContain(`var(--wf-chrome-tl-h, ${fb}px)`);
    expect(evalCalc(top, { h: null })).toBe(old);
  });

  it.each(CASES)('%s (phone: %s) sits below the chrome group when the line makes it taller', (sel, phone, fb, old) => {
    const top = declOf(sel, 'top', { phone });
    const grown = 94; // control + the 6px gap + a two-line big-seas row, as the observer would publish
    // `.wf-map-chrome-tl` is at `top: 8px`, so its bottom edge is 8 + grown.
    expect(evalCalc(top, { h: grown })).toBe(8 + grown + (old - 8 - fb));
    expect(evalCalc(top, { h: grown })).toBeGreaterThanOrEqual(8 + grown);
  });

  it.each(CASES)('%s (phone: %s) keeps its bottom edge where it was: max-height shrinks by what top grew', (sel, phone, fb) => {
    const top = declOf(sel, 'top', { phone });
    const maxHeight = declOf(sel, 'max-height', { phone });
    const bottomEdge = (h) => evalCalc(top, { h, frame: 1000 }) + evalCalc(maxHeight, { h, frame: 1000 });
    expect(maxHeight).toContain(`var(--wf-chrome-tl-h, ${fb}px)`);
    expect(bottomEdge(94)).toBe(bottomEdge(null));
  });

  it('the phone "scored places" toast sits below the line: 62px when unset, below the chrome when set', () => {
    const top = declOf('.wf-map-tab .wf-map-scored-legend', 'top', { phone: true });
    expect(top).toBe('calc(var(--wf-chrome-tl-h, 40px) + 22px)');
    expect(evalCalc(top, { h: null })).toBe(62);
    expect(evalCalc(top, { h: 94 })).toBeGreaterThanOrEqual(8 + 94);
  });

  it.each(['.wf-map-peek', '.wf-map-peek-open'])(
    '%s clamps under the line: 64px of the frame when unset, the chrome height + 20px when set',
    (sel) => {
      const maxHeight = declOf(sel, 'max-height');
      expect(maxHeight).toBe('calc(100% - var(--wf-chrome-tl-h, 44px) - 20px)');
      expect(evalCalc(maxHeight, { h: null, frame: 1000 })).toBe(1000 - 64);
      expect(evalCalc(maxHeight, { h: 150, frame: 1000 })).toBe(1000 - 170);
    },
  );

  it('keeps the plain-px values for everything else in those rules', () => {
    expect(px(declOf('.wf-win-panel', 'left'))).toBe(60);
  });
});
