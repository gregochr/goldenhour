import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll, beforeEach, afterEach,
} from 'vitest';

/**
 * Which `filter` a tile's warm-dress class resolves to, and which background wins on the
 * attribution control — both asserted against the REAL `index.css`.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>`.wf-basemap-warm`/`.wf-basemap-ref` (map-tab-v2-plan.md §3 P3) are applied to the two Esri
 * `TileLayer`s in `MapView.jsx` via the `className` prop — Leaflet's `GridLayer` adds
 * `options.className` straight onto each tile `<img>`. `MapViewBasemapDress.test.jsx` mocks
 * `react-leaflet` (per the suite's own convention — see `MapViewHeat.test.jsx`) and can only pin
 * that the CLASS NAME reaches the right `TileLayer`; the rest of the suite runs with `css: false`
 * (`vite.config.js`), so nothing anywhere asserts what that class actually PAINTS. A typo in the
 * filter value, or the two classes' declarations swapped, would leave every component test green.
 *
 * <p>The attribution restyle has the same gap from the other direction: its whole point is
 * `!important` beating Leaflet's own compound-selector rule (§3 P3, index.css comment), and
 * nothing renders Leaflet's real stylesheet anywhere else in the suite — so stripping the
 * `!important` (which the plan's own text tempts a later session to try: "!important-free if the
 * cascade allows") would leave every component test green while the map's attribution control
 * silently went back to Leaflet's white background.
 *
 * <h2>What this file can and cannot prove</h2>
 *
 * <p>jsdom resolves specificity and source order but does not resolve `var()` — moot for the
 * filters, since both declarations are bare literals (`saturate(.5) sepia(.32) brightness(.9)
 * contrast(1.08)` and `saturate(.35) sepia(.3) brightness(1.02)`), copied verbatim from
 * `docs/design/map-tab-v2/README.md`'s "The basemap" section, and moot for the attribution
 * background, which is also a bare literal. What it cannot prove in either case is the rendered
 * PIXEL result — that is a browser claim, made separately by the coordinator's browser
 * verification pass for this phase.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');
const LEAFLET_CSS_PATH = resolve(process.cwd(), 'node_modules/leaflet/dist/leaflet.css');

/**
 * Collapses whitespace runs to a single space and trims — so a comparison pins the VALUES a
 * declaration carries, not the exact source formatting.
 *
 * <p>The suite's formatter is free to reflow a `filter`/`background-color` declaration's internal
 * spacing on an unrelated pass; without this, a purely cosmetic reformat that changed nothing
 * semantically would fail an exact-string assertion here for the wrong reason.
 *
 * @param {string} s a CSS value or declaration as returned by `getComputedStyle`
 * @returns {string} the same value with whitespace normalised
 */
function normalise(s) {
  return s.replace(/\s+/g, ' ').trim();
}

/**
 * Every rule in `index.css` whose selector mentions the basemap-dress classes, in source order.
 *
 * <p>Comments are stripped first — they contain both braces and the class names, in the very
 * blocks being extracted — and the scan is brace-depth aware so a rule inside an `@media` block
 * cannot be silently re-associated with the wrong selector. The same slicer, for the same reasons,
 * as `movementToneCascade.test.jsx`/`mapChipFlipCascade.test.jsx`.
 *
 * @returns {string} the concatenated rules, ready to inject
 */
function extractBasemapRules() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
  const css = readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const rules = [];
  let selectorStart = 0;
  let depth = 0;
  let blockStart = -1;
  for (let i = 0; i < css.length; i += 1) {
    if (css[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        const selector = css.slice(selectorStart, blockStart).trim();
        if (/\.wf-basemap-warm\b/.test(selector) || /\.wf-basemap-ref\b/.test(selector)) {
          rules.push(`${selector} ${css.slice(blockStart, i + 1)}`);
        }
        selectorStart = i + 1;
      }
    }
  }
  return rules.join('\n');
}

/**
 * Fail loudly if the slice no longer contains the rules under test.
 *
 * <p>A no-match extraction that quietly injected "" would leave every probe element with no
 * `filter` at all and pass a "resolves to nothing" test for the wrong reason.
 *
 * @param {string} slice extracted rule text
 */
function assertSliceIsIntact(slice) {
  expect(slice, 'no basemap-dress rules were extracted from index.css').not.toBe('');
  expect(slice).toContain('.wf-basemap-warm {');
  expect(slice).toContain('.wf-basemap-ref {');
  expect(slice).toContain('saturate');
}

let styleEl;
beforeAll(() => {
  const slice = extractBasemapRules();
  assertSliceIsIntact(slice);
  styleEl = document.createElement('style');
  styleEl.textContent = slice;
  document.head.appendChild(styleEl);
});
afterAll(() => styleEl?.remove());

/** A bare probe element carrying one basemap-dress class — no component needed; the class is the
 * whole surface under test, exactly as it reaches a Leaflet tile `<img>`. */
function probe(className) {
  const el = document.createElement('div');
  el.className = className;
  document.body.appendChild(el);
  return el;
}

describe('the basemap-dress classes resolve their filter, not merely carry the name', () => {
  it('.wf-basemap-warm resolves the base tile filter verbatim from the design README', () => {
    const el = probe('wf-basemap-warm');
    expect(normalise(getComputedStyle(el).filter)).toBe(normalise('saturate(.5) sepia(.32) brightness(.9) contrast(1.08)'));
    el.remove();
  });

  it('.wf-basemap-ref resolves the reference (place-name) tile filter verbatim', () => {
    const el = probe('wf-basemap-ref');
    expect(normalise(getComputedStyle(el).filter)).toBe(normalise('saturate(.35) sepia(.3) brightness(1.02)'));
    el.remove();
  });

  it('the two filters are not accidentally identical — a copy-paste that painted both tiles the same', () => {
    const warm = probe('wf-basemap-warm');
    const ref = probe('wf-basemap-ref');
    expect(getComputedStyle(warm).filter).not.toBe(getComputedStyle(ref).filter);
    warm.remove();
    ref.remove();
  });

  it('an element with neither class carries no filter at all', () => {
    const el = probe('');
    expect(getComputedStyle(el).filter).toBe('none');
    el.remove();
  });
});

/**
 * Every rule in a stylesheet whose selector contains {@code needle}, in source order — the same
 * slicer as `markerInertCascade.test.jsx`, for the same reason: a specificity contest against
 * Leaflet's own sheet needs BOTH sides sliced from their real sources, not paraphrased.
 *
 * @param {string} path   stylesheet to read
 * @param {string} needle substring the selector must contain
 * @returns {string} the concatenated rules, ready to inject
 */
function sliceFile(path, needle) {
  expect(existsSync(path), `stylesheet not found at ${path} — run vitest from frontend/`).toBe(true);
  const css = readFileSync(path, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const rules = [];
  let selectorStart = 0;
  let depth = 0;
  let blockStart = -1;
  for (let i = 0; i < css.length; i += 1) {
    if (css[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        const selector = css.slice(selectorStart, blockStart).trim();
        if (selector.includes(needle)) rules.push(`${selector} ${css.slice(blockStart, i + 1)}`);
        selectorStart = i + 1;
      }
    }
  }
  return rules.join('\n');
}

/**
 * The attribution restyle's whole reason for existing: it must out-specify Leaflet's own
 * `.leaflet-container .leaflet-control-attribution` rule (two classes, 0,0,2,0) with a plain
 * `.leaflet-control-attribution` (0,0,1,0) — which `!important` is the only thing that can do,
 * regardless of which stylesheet loads second (index.css's own comment on the rule). The plan's
 * P3 text explicitly asks for "`!important`-free if the cascade allows" — this file exists so a
 * later session that tries stripping it finds out here, not in production.
 */
describe('the attribution restyle out-specifies Leaflet\'s own rule, and needs !important to do it', () => {
  let ours;
  let theirs;
  let container;

  beforeAll(() => {
    const appRules = sliceFile(CSS_PATH, 'leaflet-control-attribution');
    const leafletRules = sliceFile(LEAFLET_CSS_PATH, 'leaflet-control-attribution');
    // Both sides have to be present or every assertion below passes for the wrong reason: with no
    // Leaflet rule there is nothing to out-specify, and with no rule of ours there is nothing to
    // win with.
    expect(appRules, 'no .leaflet-control-attribution rules were extracted from index.css').not.toBe('');
    expect(appRules).toContain('!important');
    expect(appRules).toContain('rgba(20, 16, 13, 0.62)');
    expect(leafletRules, 'no .leaflet-control-attribution rules were extracted from leaflet.css').not.toBe('');
    expect(leafletRules).toContain('.leaflet-container .leaflet-control-attribution');
    expect(leafletRules).toContain('rgba(255, 255, 255, 0.8)');

    // ⚠️ OURS FIRST, then Leaflet's — the real load order (`MapView.jsx` imports
    // `leaflet/dist/leaflet.css`, which lands after the app's own sheet — `main.jsx` imports
    // `index.css` directly, before `App` transitively reaches `MapView.jsx`). The same order
    // `markerInertCascade.test.jsx` uses, and for the same reason: reverse these two and a
    // genuine regression (the `!important` stripped) would still pass on a source-order tie that
    // does not exist in the real app.
    ours = document.createElement('style');
    ours.textContent = appRules;
    document.head.appendChild(ours);
    theirs = document.createElement('style');
    theirs.textContent = leafletRules;
    document.head.appendChild(theirs);
  });
  afterAll(() => { ours?.remove(); theirs?.remove(); });

  beforeEach(() => {
    // Leaflet's own rule is scoped through a descendant selector
    // (`.leaflet-container .leaflet-control-attribution`), so the DOM nesting has to be real for
    // it to match at all — a bare `.leaflet-control-attribution` div would never see that rule and
    // this test would pass for a reason that says nothing about the actual contest.
    container = document.createElement('div');
    container.className = 'leaflet-container';
    const attribution = document.createElement('div');
    attribution.className = 'leaflet-control-attribution';
    container.appendChild(attribution);
    document.body.appendChild(container);
  });
  afterEach(() => container?.remove());

  it('wins the background over Leaflet\'s own compound selector — the exact case !important protects', () => {
    const attribution = container.querySelector('.leaflet-control-attribution');
    expect(getComputedStyle(attribution).backgroundColor).toBe('rgba(20, 16, 13, 0.62)');
    // The control, asserted explicitly rather than only by omission: Leaflet's own value, so a
    // slice that extracted nothing from one side (both '') could not pass the line above for the
    // wrong reason and still fail this one for the right one.
    expect(getComputedStyle(attribution).backgroundColor).not.toBe('rgba(255, 255, 255, 0.8)');
  });
});
