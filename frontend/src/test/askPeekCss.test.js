import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { PEEK_HEIGHT } from '../utils/askPeek.js';

/**
 * The phone peek sheet's Ask heights, read from `index.css` as text (jsdom applies no layout, so a
 * component test cannot see a height; the sibling files `askCss.test.js` and `mapCalloutClampCascade`
 * pin declarations the same way). The numbers live in `utils/askPeek.js` AND in the stylesheet — this is
 * what holds them together — and the clamp that keeps the window pill clear is asserted on the rule that
 * carries it. Whether a browser honours them is a browser claim.
 */

const CSS = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of every rule whose selector list has exactly `selector` as one entry, in source order. */
function bodies(selector) {
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  const out = [];
  let m = rule.exec(CSS);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) out.push(m[2]);
    m = rule.exec(CSS);
  }
  return out;
}

/** The value of `prop` in the LAST rule for `selector` that declares it, or null. */
function declared(selector, prop) {
  let value = null;
  for (const body of bodies(selector)) {
    const m = new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`).exec(body);
    if (m) value = m[1].trim();
  }
  return value;
}

describe('the sheet\'s heights (utils/askPeek.js ⇄ index.css)', () => {
  it.each([
    ['.wf-map-peek', 'plain'],
    ['.wf-map-peek-open', 'open'],
    ['.wf-map-peek[data-ask=\'collapsed\']', 'collapsed'],
    ['.wf-map-peek[data-ask=\'minimised\']', 'minimised'],
    ['.wf-map-peek[data-ask=\'section\']', 'openWithAsk'],
    ['.wf-map-peek[data-ask=\'expanded\']', 'ask'],
  ])('%s is PEEK_HEIGHT.%s', (selector, key) => {
    expect(declared(selector, 'height'), `no height rule for ${selector}`).toBe(`${PEEK_HEIGHT[key]}px`);
  });

  it('the Ask states outrank the pre-Ask open rule by specificity, not by being later', () => {
    // `.wf-map-peek-open` is one class; the `data-ask` rules are a class and an attribute. Were the
    // section state ever written as a bare class it would tie, and source order would decide.
    expect(bodies('.wf-map-peek-open')).toHaveLength(1);
    for (const state of ['collapsed', 'minimised', 'section', 'expanded']) {
      expect(bodies(`.wf-map-peek[data-ask='${state}']`), state).toHaveLength(1);
    }
  });
});

describe('the clamp — the window pill is never covered (calc(100% - 64px))', () => {
  it('is on the BASE rule, so every height above inherits it — 126, 112, 408 and 470 included', () => {
    expect(declared('.wf-map-peek', 'max-height')).toBe('calc(100% - 64px)');
    expect(declared('.wf-map-peek-open', 'max-height')).toBe('calc(100% - 64px)');
  });

  it('is never re-declared (and so never loosened) by a state rule', () => {
    for (const state of ['collapsed', 'minimised', 'section', 'expanded']) {
      expect(declared(`.wf-map-peek[data-ask='${state}']`, 'max-height'), state).toBeNull();
    }
  });

  it('is the figure MapLabels\' obstacle clamps to as well — the window pill\'s 10px + 44px + 10px', () => {
    const labels = readFileSync(resolve(process.cwd(), 'src/components/map/MapLabels.jsx'), 'utf8');
    expect(labels).toMatch(/PEEK_PILL_CLEARANCE = 64;/);
    expect(declared('.wf-map-peek', 'max-height')).toContain('64px');
  });
});

describe('the transition — .28s cubic-bezier(.3,.7,.2,1), and none under reduced motion', () => {
  it('is the README\'s .28s curve on the base rule (the mock\'s own CSS says .26s; the README is the stated figure)', () => {
    // The FIRST `.wf-map-peek` rule: the reduced-motion override below it is a second one.
    expect(bodies('.wf-map-peek')[0]).toMatch(/transition:\s*height \.28s cubic-bezier\(\.3, \.7, \.2, 1\);/);
  });

  it('is switched off for prefers-reduced-motion', () => {
    const m = /@media \(prefers-reduced-motion: reduce\)\s*\{\s*\.wf-map-peek\s*\{\s*transition:\s*none;\s*\}\s*\}/.exec(CSS);
    expect(m).not.toBeNull();
  });
});

describe('the row and the line', () => {
  it('the Ask row is 44px with an 8px margin (52px), and the minimised line is one 44px target', () => {
    expect(declared('.wf-map-peek-ask-entry', 'height')).toBe('44px');
    expect(declared('.wf-map-peek-ask-row', 'margin')).toBe('0 10px 8px');
    expect(declared('.wf-map-peek-mini', 'min-height')).toBe('44px');
  });

  it('the minimised sheet holds its handle, the row and the line — every figure read from the stylesheet', () => {
    const px = (value) => Number.parseInt(value, 10);
    const handle = px(declared('.wf-map-peek-hdl', 'height'));
    const row = px(declared('.wf-map-peek-ask-entry', 'height')) + px(declared('.wf-map-peek-ask-row', 'margin').split(' ')[2]);
    const line = px(declared('.wf-map-peek-mini', 'min-height'));
    // 14 + 52 + 44 = 110, plus the sheet's 1px top border: it fits within the 112 with a pixel to spare.
    expect(handle + row + line + 1).toBeLessThanOrEqual(PEEK_HEIGHT.minimised);
    expect(handle + row + line).toBe(110);
  });

  it('the collapsed sheet holds its handle, the row and the buttons — the row\'s 52px is what the 126 adds to the 74', () => {
    const px = (value) => Number.parseInt(value, 10);
    const handle = px(declared('.wf-map-peek-hdl', 'height'));
    const row = px(declared('.wf-map-peek-ask-entry', 'height')) + px(declared('.wf-map-peek-ask-row', 'margin').split(' ')[2]);
    const buttons = px(declared('.wf-map-peek-btn', 'height')) + 10; // the row's own padding-bottom: 10px
    expect(handle + row + buttons + 1).toBeLessThanOrEqual(PEEK_HEIGHT.collapsed);
  });

  it('the field keeps the 16px text iOS needs (it zooms the page on anything smaller)', () => {
    expect(declared('.wf-ask-in-field', 'font-size')).toBe('16px');
  });

  it('the answer scrolls under the row, which does not', () => {
    expect(declared('.wf-map-peek-ask-body', 'overflow-y')).toBe('auto');
    expect(declared('.wf-map-peek-ask-body', 'flex')).toBe('1 1 auto');
    expect(declared('.wf-map-peek-ask-row', 'flex')).toBe('none');
  });

  it('the Ask node is a programmatic focus target with no ring — and the entry, the ✕ and the line have one', () => {
    expect(declared('.wf-map-peek-ask:focus', 'outline')).toBe('none');
    expect(CSS).toMatch(/\.wf-map-peek-ask-entry:focus-visible,[\s\S]*?\{[^}]*outline:\s*2px solid/);
  });
});

describe('the details the review pinned', () => {
  it('the minimised line\'s focus ring is drawn INSIDE it: it is the last thing in a sheet that ends at 111 of 112px', () => {
    expect(declared('.wf-map-peek-mini:focus-visible', 'outline-offset')).toBe('-2px');
  });

  it('the ✕ lines up with the field, not with a "Next question" row that may stand above it', () => {
    expect(declared('.wf-map-peek-ask-x', 'align-self')).toBe('flex-end');
  });

  it('the field inside the row takes 8px of side padding: at 320px the 16px placeholder (183px measured) needs it', () => {
    expect(declared('.wf-map-peek-ask-field .wf-ask-in-field', 'padding')).toBe('0 8px');
    // …and keeps the 16px text iOS needs, from the sheet's own rule.
    expect(declared('.wf-ask-in-field', 'font-size')).toBe('16px');
  });

  it('a refusal\'s sentence wraps to two lines inside the row rather than being cut to one', () => {
    expect(declared(".wf-map-peek-ask-q[data-error='true']", 'white-space')).toBe('normal');
    expect(declared(".wf-map-peek-ask-q[data-error='true']", '-webkit-line-clamp')).toBe('2');
  });
});

describe('--psh — state-driven, with no 74 literal left to drift from it', () => {
  it('keeps 74px as the DEFAULT only (the sheet with no Ask row); MapView writes the live one inline', () => {
    expect(declared('.wf-map-tab', '--psh')).toBe('74px');
  });

  it('the Leaflet corner\'s padding and the bottom-left chrome\'s floor both read the property', () => {
    expect(declared('.wf-map-tab .leaflet-bottom.leaflet-right', 'padding-bottom')).toBe('var(--psh, 74px)');
    expect(declared('.wf-map-tab .wf-map-chrome-bl', 'bottom')).toBe('calc(var(--psh, 74px) + 27px + 8px)');
  });

  it('the empty-state chip\'s lift (written earlier) already read it', () => {
    expect(CSS).toContain('calc(var(--psh, 0px) + 52px)');
  });
});
