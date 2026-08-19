import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll,
} from 'vitest';

/**
 * Which rule wins on a faded marker — asserted against the REAL `index.css` and the REAL
 * `leaflet.css`, because this defect is a specificity tie and nothing else can see it.
 *
 * <h2>The defect, and why it survived a green suite</h2>
 *
 * <p>D8 says a marker below 20% opacity must not be a click target: an invisible medallion that
 * swallows the drag meant for the map is the overload the heat field exists to remove, wearing a
 * disguise. `MapHeatLayer` writes `pointer-events: none` on the marker PANE, which §4.5 specifies —
 * and which does not work, because Leaflet's own stylesheet carries
 * `.leaflet-marker-icon.leaflet-interactive { pointer-events: auto }` and a child re-enabling the
 * property beats an ancestor disabling it. So the layer also adds a class whose rule reaches the
 * children.
 *
 * <p><b>And the first cut of that rule lost too.</b> `.wf-markers-inert .leaflet-marker-icon` is two
 * classes; so is `.leaflet-marker-icon.leaflet-interactive`. A tie goes to source order, and
 * Leaflet's sheet is loaded after ours — so the computed value was still `auto`. Every unit test
 * passed (they assert the class is applied, which it was), and it was caught by measuring a real
 * medallion in a browser at zoom 8. This file is that measurement, kept.
 *
 * <h2>What it can and cannot prove</h2>
 *
 * <p>jsdom resolves specificity and source order correctly, which is exactly the axis this defect
 * lives on. It does not resolve `var()`, and it does not lay anything out — so nothing here says
 * whether the marker is visible, only whether it is clickable. That half is the browser's, and was
 * measured in the commit that added this file.
 */

const INDEX_CSS = resolve(process.cwd(), 'src/index.css');
const LEAFLET_CSS = resolve(process.cwd(), 'node_modules/leaflet/dist/leaflet.css');

/**
 * Every rule in a stylesheet whose selector mentions {@code needle}, in source order.
 *
 * <p>Sliced rather than injected whole for the reason `regionChipVerdictColour.test.jsx` records:
 * `index.css` opens with `@import "tailwindcss"` and `@theme`, which jsdom's parser drops silently,
 * taking real rules with them. Brace-depth aware, so a rule inside an `@media` block cannot be
 * re-associated with the wrong selector.
 *
 * @param {string} path   stylesheet to read
 * @param {string} needle substring the selector must contain
 * @returns {string} the concatenated rules, ready to inject
 */
function slice(path, needle) {
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

let ours;
let theirs;
beforeAll(() => {
  const appRules = slice(INDEX_CSS, 'wf-markers-inert');
  const leafletRules = slice(LEAFLET_CSS, 'leaflet-marker-icon');
  // Both sides have to be present or every assertion below passes for the wrong reason: with no
  // Leaflet rule there is nothing to out-specify, and with no rule of ours there is nothing to win.
  expect(appRules, 'no .wf-markers-inert rules were extracted from index.css').not.toBe('');
  expect(leafletRules).toContain('.leaflet-marker-icon.leaflet-interactive');
  expect(leafletRules).toContain('pointer-events: auto');

  // ⚠️ OURS FIRST, then Leaflet's — the real load order (`MapView.jsx` imports
  // `leaflet/dist/leaflet.css`, which lands after the app's own sheet). Reverse these two lines and
  // the tie resolves the other way and this file stops describing the browser.
  ours = document.createElement('style');
  ours.textContent = appRules;
  document.head.appendChild(ours);
  theirs = document.createElement('style');
  theirs.textContent = leafletRules;
  document.head.appendChild(theirs);
});
afterAll(() => { ours?.remove(); theirs?.remove(); });

/** A marker icon as Leaflet actually builds one, inside a pane in the given state. */
function markerIn(paneClass) {
  const pane = document.createElement('div');
  pane.className = `leaflet-pane leaflet-marker-pane ${paneClass}`.trim();
  const icon = document.createElement('div');
  icon.className = 'leaflet-marker-icon leaflet-zoom-animated leaflet-interactive';
  pane.appendChild(icon);
  document.body.appendChild(pane);
  return { pane, icon };
}

describe('the faded marker’s pointer gate', () => {
  it('leaves an ordinary marker clickable, which is Leaflet’s own rule', () => {
    // The control. Without it a rule that disabled every marker everywhere would pass the test below
    // and break the map.
    const { pane, icon } = markerIn('');
    expect(getComputedStyle(icon).pointerEvents).toBe('auto');
    pane.remove();
  });

  it('removes the target from a marker inside an inert pane, out-specifying Leaflet', () => {
    // ⚠️ THE regression. Drop the `.leaflet-interactive` half of our selector and this returns
    // 'auto' — two classes against two, later sheet wins — while every other test stays green and
    // an invisible medallion keeps eating the drag.
    const { pane, icon } = markerIn('wf-markers-inert');
    expect(getComputedStyle(icon).pointerEvents).toBe('none');
    pane.remove();
  });

  it('is not achieved by hiding the marker, which would take it out of the a11y tree', () => {
    // The rule the class exists to keep. `visibility: hidden` closes the pointer hole too — and
    // closes the only route a keyboard or screen-reader user has to a location on this tab, at the
    // zoom it opens at. Whatever the rule does, it must not do that.
    const { pane, icon } = markerIn('wf-markers-inert');
    expect(getComputedStyle(icon).visibility).not.toBe('hidden');
    expect(getComputedStyle(icon).display).not.toBe('none');
    pane.remove();
  });
});
