/**
 * The Map tab's centred empty state — what the REAL stylesheet makes of it.
 *
 * <p>jsdom lays nothing out, but it does resolve the cascade: specificity, inheritance and which
 * rule wins. So this file injects a SLICE of `index.css` and asks what each probe element computes,
 * the same technique `mapChromeZLadderCascade.test.jsx` uses for the rest of the ladder.
 *
 * <h2>Why this file exists — a defect only a browser found</h2>
 *
 * <p>The label is a full-bleed `inset: 0` overlay laid across the entire map, so it must never
 * swallow a pan. The first cut set `pointer-events: none` on the wrapper and a comment claimed the
 * inner chip "inherits it". Measured in a browser it did not: the chip reuses `.wf-map-key` for its
 * look, and `.wf-map-key` deliberately sets `pointer-events: auto` — correct in its home, where the
 * toolbar is click-through and its controls must stay clickable. Borrowing the class borrowed that
 * override. `elementFromPoint` at the chip's centre returned the chip rather than the map: a dead
 * zone dead-centre on the one screen whose only remaining job is being panned away from.
 *
 * <p>A green suite, clean lint and a successful build had all passed over it. The assertions below
 * pin the cascade winner, so the override cannot quietly lose to `.wf-map-key` again.
 */
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll,
} from 'vitest';

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/**
 * Every top-level rule whose selector contains {@code needle}, comments stripped first. The same
 * brace-depth walk `mapChromeZLadderCascade.test.jsx` uses — a regex over `{…}` would mis-split any
 * rule containing a nested block.
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

let styleEl;
beforeAll(() => {
  // `.wf-map-key` MUST be in the slice: it is the rule that sets `pointer-events: auto`, and the
  // whole point is proving the empty state's override beats it. Leave it out and the chip would
  // compute `none` by plain inheritance, and the test would pass for the wrong reason.
  const slice = ['.wf-map-empty', '.wf-map-key', '.wf-land', '.wf-map-chrome-tr'].map(sliceRules).join('\n');
  for (const needle of ['.wf-map-empty {', '.wf-map-empty .wf-map-key {', '.wf-map-key', 'pointer-events: auto', '.wf-land {']) {
    expect(slice, `no rule found for ${needle} in index.css`).toContain(needle);
  }
  styleEl = document.createElement('style');
  styleEl.textContent = slice;
  document.head.appendChild(styleEl);
});
afterAll(() => styleEl?.remove());

/** Mounts `<div class=outer><div class=inner/></div>` and returns both computed styles. */
function probe(outerClass, innerClass) {
  const outer = document.createElement('div');
  outer.className = outerClass;
  const inner = document.createElement('div');
  inner.className = innerClass;
  outer.appendChild(inner);
  document.body.appendChild(outer);
  const result = { outer: getComputedStyle(outer), inner: getComputedStyle(inner) };
  const snapshot = {
    outerPE: result.outer.pointerEvents,
    innerPE: result.inner.pointerEvents,
    outerPosition: result.outer.position,
    outerZ: Number(result.outer.zIndex),
  };
  outer.remove();
  return snapshot;
}

function zIndexOf(className) {
  const el = document.createElement('div');
  el.className = className;
  document.body.appendChild(el);
  const z = Number(getComputedStyle(el).zIndex);
  el.remove();
  return z;
}

describe('the centred empty state is click-through', () => {
  it('the full-bleed wrapper does not take pointer events', () => {
    expect(probe('wf-map-empty', 'wf-map-key').outerPE).toBe('none');
  });

  it('and neither does the chip inside it — its own override beats `.wf-map-key`\'s `auto`', () => {
    // ⚠️ The defect. Inheritance alone does NOT give the chip `none`: `.wf-map-key` sets `auto`
    // explicitly, and an explicit value always beats an inherited one.
    expect(probe('wf-map-empty', 'wf-map-key').innerPE).toBe('none');
  });

  it('control: a `.wf-map-key` OUTSIDE the empty state keeps its `auto`', () => {
    // The override is scoped. The toolbar's own keys must stay clickable, so a fix that simply
    // flipped `.wf-map-key` itself to `none` would pass the test above and break every toolbar
    // control — this is what tells the two fixes apart.
    expect(probe('wf-map-toolbar-row', 'wf-map-key').innerPE).toBe('auto');
  });
});

describe('the centred empty state\'s rung on the chrome z-ladder', () => {
  it('is absolutely positioned over the frame', () => {
    expect(probe('wf-map-empty', 'wf-map-key').outerPosition).toBe('absolute');
  });

  it('paints above Leaflet — its panes top out at 700 and its controls at 800', () => {
    expect(zIndexOf('wf-map-empty')).toBeGreaterThan(800);
  });

  it('paints BELOW the landing card, so the card is never covered by it', () => {
    expect(zIndexOf('wf-map-empty')).toBeLessThan(zIndexOf('wf-land'));
  });

  it('paints BELOW the chrome corners, so a panel or popover always covers it', () => {
    expect(zIndexOf('wf-map-empty')).toBeLessThan(zIndexOf('wf-map-chrome-tr'));
  });
});
