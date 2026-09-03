import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, afterEach,
} from 'vitest';

/**
 * The Map tab's PHONE chrome (map-tab-v2-plan.md §3 P12, `docs/design/map-tab-v2/README.md`
 * "Responsive" table), asserted against the REAL `index.css` — the same slicer family as
 * `mapChromeZLadderCascade.test.jsx`/`mapChipFlipCascade.test.jsx`, extended to reach INSIDE
 * `@media` blocks.
 *
 * <h2>Why this slicer is not the sibling files' slicer</h2>
 *
 * <p>The existing brace-depth scanner only ever inspects TOP-LEVEL blocks: for a rule nested
 * inside `@media (max-width: 639px) { .foo { ... } }`, the depth-0→1→0 transition it tracks fires
 * on the `@media` block's OWN braces, and the "selector" it reads back is the literal text
 * `@media (max-width: 639px)` — which never contains a class needle, so the whole block (and every
 * rule inside it) is silently skipped. That is fine for the sibling files, which slice rules that
 * live at the top level. Every rule this file cares about lives one level deeper.
 *
 * <p>{@link sliceTopLevelBlocks} is the same scanner, factored so it can be applied a SECOND time
 * to a media block's own inner text — recursion, not a new algorithm. The media condition itself
 * is discarded on purpose: jsdom does not evaluate `@media` (this suite's `matchMedia` stub always
 * answers `matches: false`, `src/test/setup.js`), so "does this query match a 390px viewport" stays
 * a browser claim exactly as the sibling files' own doc comments say for `var()`. What IS testable,
 * and what this file tests, is "which declaration wins once the query is satisfied" — the same
 * split the jsdom-cascade technique draws everywhere else, applied one level deeper.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/**
 * Every TOP-LEVEL block in `css` — `{selector, bodyInner}` pairs, in source order. A block whose
 * selector is an `@media` rule carries its nested rules verbatim in `bodyInner`, unparsed; the
 * caller decides whether to recurse.
 *
 * @param {string} css
 * @returns {Array<{selector: string, bodyInner: string}>}
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

/**
 * Every rule in `index.css` whose selector contains ANY of {@code needles} — top-level OR one
 * level inside an `@media` block, the latter with its media wrapper stripped (see the class doc).
 * Concatenated in SOURCE order, which is what makes the result behaviourally equivalent to "what
 * the cascade resolves to once the query matches": a media-scoped rule cascades exactly like an
 * unconditional one at the same source position once its condition is true.
 *
 * <p>⚠️ Takes every needle in ONE pass rather than letting a caller concatenate two separate
 * single-needle calls — a rule that matches BOTH needles (`.wf-filters-seg-btn, .wf-filters-chip-
 * btn { min-height: 32px }` matches both `.wf-filters-seg-btn` and `.wf-filters-chip-btn`) would
 * otherwise be extracted and injected TWICE, the second copy landing after a later single-needle
 * override and silently winning the cascade for the wrong reason — caught by this file's own first
 * run, which read `.wf-filters-chip-btn` as 32px instead of its real 30px.
 *
 * @param {string|string[]} needles substring(s) the selector must contain at least one of
 * @returns {string} the concatenated rules, ready to inject
 */
function extractRulesIncludingMedia(needles) {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
  const list = Array.isArray(needles) ? needles : [needles];
  const matches = (selector) => list.some((needle) => selector.includes(needle));
  const css = readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const rules = [];
  for (const block of sliceTopLevelBlocks(css)) {
    if (block.selector.startsWith('@media')) {
      for (const nested of sliceTopLevelBlocks(block.bodyInner)) {
        if (matches(nested.selector)) {
          rules.push(`${nested.selector} {${nested.bodyInner}}`);
        }
      }
    } else if (matches(block.selector)) {
      rules.push(`${block.selector} {${block.bodyInner}}`);
    }
  }
  return rules.join('\n');
}

/** Injects `slice` into a fresh `<style>` tag; returns a cleanup function. */
function inject(slice) {
  const el = document.createElement('style');
  el.textContent = slice;
  document.head.appendChild(el);
  return () => el.remove();
}

let cleanupFns = [];
afterEach(() => {
  for (const fn of cleanupFns) fn();
  cleanupFns = [];
});

/**
 * Builds `className` on a real element, optionally nested under one or more ancestor class names
 * (outermost first) — the phone chrome rules are all `.wf-map-tab .foo` descendant selectors, so
 * the ancestor has to exist for them to match at all.
 *
 * @param {string} className the element under test
 * @param {string[]} [ancestors] ancestor class names, outermost first
 * @returns {CSSStyleDeclaration}
 */
function computedStyleFor(className, ancestors = []) {
  const nodes = [...ancestors, className].map((cls) => {
    const el = document.createElement('div');
    el.className = cls;
    return el;
  });
  for (let i = 0; i < nodes.length - 1; i += 1) nodes[i].appendChild(nodes[i + 1]);
  document.body.appendChild(nodes[0]);
  cleanupFns.push(() => nodes[0].remove());
  return getComputedStyle(nodes[nodes.length - 1]);
}

describe('phone touch targets — the README minima (30px chips · 32px segments · 40px mobile pills)', () => {
  it('a chip button (.wf-filters-chip-btn) sits at 30px, its segment sibling at 32px', () => {
    const slice = extractRulesIncludingMedia(['.wf-filters-chip-btn', '.wf-filters-seg-btn']);
    expect(slice).toContain('min-height: 30px');
    expect(slice).toContain('min-height: 32px');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('wf-filters-chip-btn').minHeight).toBe('30px');
      expect(computedStyleFor('wf-filters-seg-btn').minHeight).toBe('32px');
    } finally {
      cleanup();
    }
  });

  it('the window pill grows to 40px and fills the full-width control on the phone', () => {
    const slice = extractRulesIncludingMedia('.wf-win-pill');
    expect(slice).toContain('min-height: 40px');
    expect(slice).toContain('flex: 1');
    const cleanup = inject(slice);
    try {
      const style = computedStyleFor('wf-win-pill');
      expect(style.minHeight).toBe('40px');
      expect(style.flexGrow).toBe('1');
      expect(style.justifyContent).toBe('center');
    } finally {
      cleanup();
    }
  });

  it('the window steppers grow to a 40px-tall touch target on the phone, staying 36px wide', () => {
    const slice = extractRulesIncludingMedia('.wf-win-step');
    const cleanup = inject(slice);
    try {
      const style = computedStyleFor('wf-win-step');
      expect(style.width).toBe('36px');
      expect(style.height).toBe('40px');
    } finally {
      cleanup();
    }
  });

  it('the Regions and Filters chips both reach 40px on the phone (the same pill family as the window control)', () => {
    const slice = extractRulesIncludingMedia(['.wf-jump-chip', '.wf-filters-chip']);
    expect(slice).toContain('.wf-jump-chip');
    expect(slice).toContain('.wf-filters-chip');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('wf-jump-chip').minHeight).toBe('40px');
      expect(computedStyleFor('wf-filters-chip').minHeight).toBe('40px');
    } finally {
      cleanup();
    }
  });
});

describe('the phone chrome re-arrangement is scoped to `.wf-map-tab` (map-tab-v2-plan.md §3 P12)', () => {
  it('the window control box spans the frame edge-to-edge, replacing its desktop left-clearance', () => {
    const slice = extractRulesIncludingMedia('.wf-map-chrome-tl');
    expect(slice).toContain('.wf-map-tab .wf-map-chrome-tl');
    const cleanup = inject(slice);
    try {
      const style = computedStyleFor('wf-map-chrome-tl', ['wf-map-tab']);
      expect(style.left).toBe('8px');
      expect(style.right).toBe('8px');
    } finally {
      cleanup();
    }
  });

  it('is a NO-OP without the `.wf-map-tab` ancestor — the overlay must not pick this up', () => {
    const slice = extractRulesIncludingMedia('.wf-map-chrome-tl');
    const cleanup = inject(slice);
    try {
      const style = computedStyleFor('wf-map-chrome-tl');
      // The base (non-media, non-`.wf-map-tab`) rule sets `left: 60px` and no `right` at all —
      // this pins that the descendant-scoped mobile rule cannot reach an element with no
      // `.wf-map-tab` ancestor, which is exactly the overlay's own shape (`MapView.jsx` never
      // applies that class on its `overlayMode` mount).
      expect(style.left).toBe('60px');
      expect(style.right).toBe('auto');
    } finally {
      cleanup();
    }
  });

  it('Regions / Heat-Pins / Filters becomes a bottom bar — row direction, space-between, off the bottom edge', () => {
    const slice = extractRulesIncludingMedia('.wf-map-chrome-tr');
    const cleanup = inject(slice);
    try {
      const style = computedStyleFor('wf-map-chrome-tr', ['wf-map-tab']);
      expect(style.flexDirection).toBe('row');
      expect(style.justifyContent).toBe('space-between');
      expect(style.bottom).toBe('8px');
    } finally {
      cleanup();
    }
  });

  it('the bar\'s three segments share it equally (flex: 1)', () => {
    const slice = extractRulesIncludingMedia('.wf-map-chrome-tr');
    const cleanup = inject(slice);
    try {
      // `.wf-map-chrome-tr > *` needs a REAL child, not merely the descendant chain
      // `computedStyleFor` builds (which nests one level per name, not siblings under one parent).
      const root = document.createElement('div');
      root.className = 'wf-map-tab';
      const bar = document.createElement('div');
      bar.className = 'wf-map-chrome-tr';
      const segment = document.createElement('div');
      bar.appendChild(segment);
      root.appendChild(bar);
      document.body.appendChild(root);
      cleanupFns.push(() => root.remove());
      expect(getComputedStyle(segment).flexGrow).toBe('1');
    } finally {
      cleanup();
    }
  });

  it('the counts footer\'s second line (the area note) is dropped, the footer itself lifted clear of the bar', () => {
    const slice = extractRulesIncludingMedia(['.wf-map-counts-footer', '.wf-map-counts-second']);
    expect(slice).toContain('.wf-map-tab .wf-map-counts-second');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('wf-map-counts-footer', ['wf-map-tab']).bottom).toBe('60px');
      expect(computedStyleFor('wf-map-counts-second', ['wf-map-tab']).display).toBe('none');
    } finally {
      cleanup();
    }
  });

  it('the second line stays shown without the `.wf-map-tab` ancestor', () => {
    const slice = extractRulesIncludingMedia('.wf-map-counts-second');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('wf-map-counts-second').display).not.toBe('none');
    } finally {
      cleanup();
    }
  });

  it('Leaflet\'s native zoom control is hidden under `.wf-map-tab` — pinch takes over', () => {
    const slice = extractRulesIncludingMedia('.leaflet-control-zoom');
    expect(slice).toContain('.wf-map-tab .leaflet-control-zoom');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('leaflet-control-zoom', ['wf-map-tab']).display).toBe('none');
    } finally {
      cleanup();
    }
  });

  it('⚠️ stays visible without `.wf-map-tab` — the Plan-tab overlay\'s own zoom control must survive this rule', () => {
    const slice = extractRulesIncludingMedia('.leaflet-control-zoom');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('leaflet-control-zoom').display).not.toBe('none');
    } finally {
      cleanup();
    }
  });

  it('the Heat/Pins cluster stays a CENTRED COLUMN on the phone, not a row (review finding)', () => {
    // An earlier cut switched this to `flex-direction: row`, which meant fitting the segmented
    // toggle AND the ramp key side by side in one ~125px bar column instead of stacking them —
    // the opposite of what a cramped column needs. The fix keeps the desktop's column direction
    // (no override) and only re-centres the cross-axis alignment.
    const slice = extractRulesIncludingMedia('.wf-map-toolbar-cluster');
    const cleanup = inject(slice);
    try {
      const style = computedStyleFor('wf-map-toolbar-cluster', ['wf-map-tab']);
      expect(style.flexDirection).not.toBe('row');
      expect(style.alignItems).toBe('center');
      expect(style.justifyContent).toBe('center');
    } finally {
      cleanup();
    }
  });

  it('⚠️ the ramp key STAYS on the phone — hiding it here AND the Legend below left no colour key at all', () => {
    const slice = extractRulesIncludingMedia('.wf-map-key');
    const cleanup = inject(slice);
    try {
      const style = computedStyleFor('wf-map-key', ['wf-map-tab', 'wf-map-toolbar-cluster']);
      expect(style.display).not.toBe('none');
      // Shrunk to fit its ~125px column (one narrow mono line), not hidden.
      expect(style.fontSize).toBe('8px');
      expect(computedStyleFor('wf-map-key-ramp', ['wf-map-tab', 'wf-map-toolbar-cluster']).width).toBe('26px');
    } finally {
      cleanup();
    }
  });

  it('stays at its full desktop size without the `.wf-map-tab` ancestor', () => {
    const slice = extractRulesIncludingMedia('.wf-map-key');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('wf-map-key').fontSize).toBe('10px');
    } finally {
      cleanup();
    }
  });

  it('⚠️ BLOCKING (adversarial review): `.map-home-control` (⌂) is hidden alongside the zoom control — it was invisible AND untappable under the new bar', () => {
    const slice = extractRulesIncludingMedia('.map-home-control');
    expect(slice).toContain('.wf-map-tab .map-home-control');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('map-home-control', ['wf-map-tab']).display).toBe('none');
    } finally {
      cleanup();
    }
  });

  it('⌂ stays visible without `.wf-map-tab` — desktop/tablet and the overlay must keep it', () => {
    const slice = extractRulesIncludingMedia('.map-home-control');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('map-home-control').display).not.toBe('none');
    } finally {
      cleanup();
    }
  });

  it('the bottom-left chrome (LITE upsell) clears the bar rather than being hidden — a monetisation surface, not decoration', () => {
    // Geometry, not layout: jsdom computes no real box, so this pins the ARITHMETIC the CSS
    // comment documents rather than a measured rect. `.wf-map-chrome-bl`'s own `bottom` offset
    // must stay `>=` the bar's `bottom` offset plus the bar's own DOCUMENTED assumed height (the
    // Heat/Pins cluster stacked two rows, its own tallest column) plus a clearance gap — the same
    // inequality a real device's rendered heights have to satisfy. Re-tuning either constant only
    // has to keep this test green, not hit an exact 76px.
    const slice = extractRulesIncludingMedia(['.wf-map-chrome-bl', '.wf-map-chrome-tr']);
    const cleanup = inject(slice);
    try {
      const barBottom = parseFloat(computedStyleFor('wf-map-chrome-tr', ['wf-map-tab']).bottom);
      const blBottom = parseFloat(computedStyleFor('wf-map-chrome-bl', ['wf-map-tab']).bottom);
      const ASSUMED_BAR_HEIGHT = 48; // documented in index.css's own `.wf-map-chrome-bl` comment
      const CLEARANCE_GAP = 8;
      expect(blBottom).toBeGreaterThanOrEqual(barBottom + ASSUMED_BAR_HEIGHT + CLEARANCE_GAP);
    } finally {
      cleanup();
    }
  });

  it('the bottom-left chrome sits at its desktop offset (8px) without `.wf-map-tab` — the overlay is untouched', () => {
    const slice = extractRulesIncludingMedia('.wf-map-chrome-bl');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('wf-map-chrome-bl').bottom).toBe('8px');
    } finally {
      cleanup();
    }
  });

  it('⚠️ the window dropdown\'s full-width fix needs `max-width: none` too — `width: auto` alone left the base rule\'s cap standing', () => {
    const slice = extractRulesIncludingMedia('.wf-win-menu');
    expect(slice).toContain('max-width: none');
    const cleanup = inject(slice);
    try {
      expect(computedStyleFor('wf-win-menu').maxWidth).toBe('none');
    } finally {
      cleanup();
    }
  });
});
