import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll,
} from 'vitest';

/**
 * The Map tab's full-frame chrome z-ladder (map-tab-v2-plan.md §3 P7), asserted against the REAL
 * `index.css` — the same slicer `basemapDressCascade.test.jsx`/`movementToneCascade.test.jsx` use,
 * for the same reason: the suite runs with `css: false` (`vite.config.js`), so nothing else in it
 * resolves what a class actually paints, and a swapped or dropped `z-index` would leave every
 * component test (which only checks the class NAME reached the right element) green.
 *
 * <h2>Scope: what the ladder can assert TODAY</h2>
 *
 * <p>The design bundle's full ladder is heat 410 / selection ring 415 / labels 420 / chrome 1100 /
 * callout 1350 / tooltip 1400 / menus 1500. Three tiers are built as of P9 — CHROME (the window
 * control's `.wf-map-chrome-tl`, the Heat/Pins + Filters cluster's `.wf-map-chrome-tr`, and the
 * counts footer's `.wf-map-counts-footer`), CALLOUT (`.wf-selmk`/`.wf-callout`, map-tab-v2-plan.md
 * §3 P9) and MENUS (`.wf-win-menu`, `.wf-filters-panel`) — so this file asserts every relation the
 * plan states in one sentence: "menus must beat the callout", which is itself only meaningful once
 * the callout beats chrome (the ordering it exists to sit above). The tooltip tier is the one
 * still without a selector here.
 *
 * <p>⚠️ The ring/callout do NOT sit at the bundle's own 415/1350 relative to labels — they are
 * ABOVE this app's real label pane (650, a Leaflet pane set in JS, not a class this slicer can
 * reach) rather than below it, a documented divergence (`MapCallout.jsx`'s own class doc) from a
 * bundle that assumed no real Leaflet marker ever painted under its label layer at all. What this
 * file CAN and does assert is the callout's position relative to chrome and menus, both of which
 * are real CSS classes.
 *
 * <p>⚠️ The heat FIELD's own pane (`MapHeatLayer.jsx`'s `HEAT_PANE_Z`) is deliberately NOT
 * renumbered to the bundle's 410 by this phase — it is pinned at 350 for a Leaflet-pane-ordering
 * reason unrelated to this ladder (between Leaflet's own tile pane at 200 and its marker pane), and
 * 350 already sits far enough below 1100 that the relation this file cares about is untouched by
 * whichever literal it carries. Re-tuning it to exactly 410 is a P8/P9 question, not P7's.
 *
 * <p>⚠️ LABELS shipped in P8 at 650, not the bundle's 420 (PR #733 review — the bundle's prototype
 * assumed a heat view with no markers ever rendering below the label layer; this app fades
 * Leaflet's real markers back to full opacity and interactivity past the zoom handover, in
 * Leaflet's own built-in `markerPane` at z600, so labels have to clear THAT pane, not merely the
 * heat canvas). That z-index is set directly in JS on the Leaflet pane (`MapLabels.jsx`'s
 * `LABEL_PANE_Z`) rather than through a CSS rule on a class, so — like the heat field's own pane
 * above — it has nothing for this file's slicer to extract and stays untested HERE by construction;
 * `MapLabels.test.jsx` pins the numeric value and its relation to Leaflet's marker/popup panes
 * directly against the rendered pane element instead.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/**
 * Every rule in `index.css` whose selector contains {@code needle}, in source order — the same
 * brace-depth-aware slicer as the sibling cascade files, so a rule inside an `@media` block cannot
 * be silently re-associated with the wrong selector.
 *
 * @param {string} needle substring the selector must contain
 * @returns {string} the concatenated rules, ready to inject
 */
function sliceRules(needle) {
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
        if (selector.includes(needle)) rules.push(`${selector} ${css.slice(blockStart, i + 1)}`);
        selectorStart = i + 1;
      }
    }
  }
  return rules.join('\n');
}

const CHROME_CLASSES = ['wf-map-chrome-tl', 'wf-map-chrome-tr', 'wf-map-counts-footer'];
const CALLOUT_CLASSES = ['wf-selmk', 'wf-callout'];
const MENU_CLASSES = ['wf-win-menu', 'wf-filters-panel'];

let styleEl;
beforeAll(() => {
  const slice = [...CHROME_CLASSES, ...CALLOUT_CLASSES, ...MENU_CLASSES]
    .map((cls) => sliceRules(`.${cls}`)).join('\n');
  // Fail loudly rather than silently injecting nothing — a no-match extraction would leave every
  // probe element's z-index as `auto` and the ">" assertions below would pass for the wrong
  // reason (`auto` compares as `NaN` against a number, and `NaN > NaN` is false — so this guard
  // is what stands between a real pass and a silently-skipped one).
  for (const cls of [...CHROME_CLASSES, ...CALLOUT_CLASSES, ...MENU_CLASSES]) {
    expect(slice, `no rule found for .${cls} in index.css`).toContain(`.${cls} {`);
  }
  styleEl = document.createElement('style');
  styleEl.textContent = slice;
  document.head.appendChild(styleEl);
});
afterAll(() => styleEl?.remove());

function zIndexOf(className) {
  const el = document.createElement('div');
  el.className = className;
  document.body.appendChild(el);
  const z = Number(getComputedStyle(el).zIndex);
  el.remove();
  return z;
}

describe('the Map tab\'s full-frame chrome z-ladder (map-tab-v2-plan.md §3 P7)', () => {
  it.each(CHROME_CLASSES)('.%s sits at the chrome tier (1100)', (cls) => {
    expect(zIndexOf(cls)).toBe(1100);
  });

  it.each(MENU_CLASSES)('.%s sits at the menus tier (1500)', (cls) => {
    expect(zIndexOf(cls)).toBe(1500);
  });

  it.each(MENU_CLASSES)('.%s (menus) beats every chrome chip — a menu must never paint under a sibling', (menuCls) => {
    const menuZ = zIndexOf(menuCls);
    for (const chromeCls of CHROME_CLASSES) {
      expect(menuZ).toBeGreaterThan(zIndexOf(chromeCls));
    }
  });

  it('the window control\'s dropdown and the filters panel share one tier, so neither wins over the other by accident', () => {
    expect(zIndexOf('wf-win-menu')).toBe(zIndexOf('wf-filters-panel'));
  });

  it.each(CALLOUT_CLASSES)('.%s (the selection ring/callout) beats every chrome chip (map-tab-v2-plan.md §3 P9)', (calloutCls) => {
    const calloutZ = zIndexOf(calloutCls);
    for (const chromeCls of CHROME_CLASSES) {
      expect(calloutZ).toBeGreaterThan(zIndexOf(chromeCls));
    }
  });

  it.each(MENU_CLASSES)('.%s (menus) beats the callout — a menu must never paint under a card behind it', (menuCls) => {
    const menuZ = zIndexOf(menuCls);
    for (const calloutCls of CALLOUT_CLASSES) {
      expect(menuZ).toBeGreaterThan(zIndexOf(calloutCls));
    }
  });

  it('the callout card sits above its own selection ring', () => {
    expect(zIndexOf('wf-callout')).toBeGreaterThan(zIndexOf('wf-selmk'));
  });
});
