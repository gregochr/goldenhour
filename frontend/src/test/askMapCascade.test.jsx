import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll, afterEach,
} from 'vitest';

/**
 * Which opacity wins on a faded chip or pin — asserted against the REAL `index.css`, because the
 * defect it guards is a specificity tie and nothing else can see it (`markerInertCascade.test.jsx`'s
 * own reason, and its technique).
 *
 * <h2>The tie</h2>
 * <p>A coastal MISS is dimmed to `.72` by `.wf-maplab-chip[data-tide='miss']` (specificity 0,2,0), and
 * Ask PhotoCast's fade is `.25`. A fade written at the same specificity wins or loses on SOURCE ORDER,
 * so the first draft, which sat after the tide rule, passed — and would have stopped passing the day
 * either block moved. The shipped fade is one class deeper (`.wf-maplab-layer` in front), so it wins
 * on specificity, and this file pins that it does, with a real MISS in the middle of it.
 *
 * <h2>What it can and cannot prove</h2>
 * <p>jsdom resolves specificity and source order; it does not match `:hover` and does not lay anything
 * out. So the hover restore is pinned as the rule it is (a selector exists at higher specificity than
 * the fade, declaring opacity 1) rather than by a hover that jsdom cannot make, and the selected
 * restore — an attribute — is pinned by computed style. Whether a pale-gold ring reads against a map is
 * the browser's, and is recorded as not seen.
 */

const INDEX_CSS = resolve(process.cwd(), 'src/index.css');

/** Every top-level rule whose selector mentions {@code needle}, comments stripped, brace-depth aware. */
function sliceRules(needle) {
  expect(existsSync(INDEX_CSS), `index.css not found at ${INDEX_CSS} — run vitest from frontend/`).toBe(true);
  const css = readFileSync(INDEX_CSS, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
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
  return rules;
}
const slice = (needle) => sliceRules(needle).join('\n');

let style;
beforeAll(() => {
  const rules = `${slice('wf-maplab-chip')}\n${slice('wf-maplab-pick')}\n${slice('wf-pin')}`;
  expect(rules).toContain("[data-ask='fade']");
  expect(rules).toContain("[data-tide='miss']");
  style = document.createElement('style');
  style.textContent = rules;
  document.head.appendChild(style);
});
afterAll(() => style.remove());
afterEach(() => { document.body.innerHTML = ''; });

/** A mark inside the label layer, as the app renders it, with the given data attributes. */
function mark(tag, className, attrs) {
  const layer = document.createElement('div');
  layer.className = 'wf-maplab-layer';
  const el = document.createElement(tag);
  el.className = className;
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  layer.appendChild(el);
  document.body.appendChild(layer);
  return el;
}
const opacityOf = (el) => getComputedStyle(el).opacity;

const KINDS = [
  ['chip', 'wf-maplab-chip'],
  ['pin', 'wf-pin'],
];

describe.each(KINDS)('a faded %s', (_, className) => {
  it('is .25', () => {
    expect(opacityOf(mark('button', className, { 'data-ask': 'fade' }))).toBe('0.25');
  });

  it('⚠️ is still .25 when it is also a tide MISS — the fade beats the .72', () => {
    // The premise, asserted: without the fade the same mark IS .72, so a pass below is not vacuous.
    expect(opacityOf(mark('button', className, { 'data-tide': 'miss' }))).toBe('0.72');
    expect(opacityOf(mark('button', className, { 'data-tide': 'miss', 'data-ask': 'fade' }))).toBe('0.25');
  });

  it('is restored to full strength when it is the selected location', () => {
    expect(opacityOf(mark('button', className, { 'data-ask': 'fade', 'data-selected': 'true' }))).toBe('1');
    expect(opacityOf(mark('button', className, {
      'data-ask': 'fade', 'data-tide': 'miss', 'data-selected': 'true',
    }))).toBe('1');
  });

  it('has hover and keyboard-focus rules that restore it to full strength, one class deeper than the fade', () => {
    const rules = sliceRules(className);
    const restore = rules.filter((rule) => rule.includes(`${className}[data-ask='fade']:hover`)
      || rule.includes(`${className}[data-ask='fade']:focus-visible`));
    expect(restore.length).toBeGreaterThan(0);
    for (const rule of restore) {
      expect(rule).toMatch(/opacity:\s*1\s*;/);
      // Same `.wf-maplab-layer` prefix as the fade, plus a pseudo-class: strictly higher specificity.
      expect(rule).toContain('.wf-maplab-layer');
    }
    expect(restore.join('\n')).toContain(':hover');
    expect(restore.join('\n')).toContain(':focus-visible');
  });
});

describe('an ordinary mark', () => {
  it('is untouched by the fade rules while no picks are showing (no data-ask)', () => {
    expect(opacityOf(mark('button', 'wf-maplab-chip', {}))).not.toBe('0.25');
    expect(opacityOf(mark('button', 'wf-pin', {}))).not.toBe('0.25');
  });
});

describe('a pick paints above, and keeps a focus ring of its own', () => {
  it('has a z-index, the chosen one a higher one, and a faded chip has none', () => {
    const pick = mark('button', 'wf-maplab-chip wf-maplab-pick', { 'data-ask': 'pick' });
    const chosen = mark('button', 'wf-maplab-chip wf-maplab-pick', { 'data-ask': 'pick', 'data-ask-selected': 'true' });
    const faded = mark('button', 'wf-maplab-chip', { 'data-ask': 'fade' });

    expect(Number(getComputedStyle(pick).zIndex)).toBeGreaterThan(0);
    expect(Number(getComputedStyle(chosen).zIndex)).toBeGreaterThan(Number(getComputedStyle(pick).zIndex));
    expect(['', 'auto']).toContain(getComputedStyle(faded).zIndex);
  });

  it('⚠️ writes the focus ring OUTSIDE the plate (the base ring is inside it, over the chosen pick\'s own ring)', () => {
    const [chip] = sliceRules('.wf-maplab-chip.wf-maplab-pick:focus-visible');
    expect(chip).toMatch(/outline-offset:\s*2px/);
    expect(sliceRules('.wf-maplab-chip:focus-visible').join('')).toMatch(/outline-offset:\s*-2px/);
  });

  it('⚠️ gives the pin a focus ring one class deeper than the chosen halo, which is also an outline', () => {
    const rules = sliceRules('.wf-pin');
    const halo = rules.find((r) => r.startsWith(".wf-pin[data-ask='pick'][data-ask-selected='true']"));
    const ring = rules.find((r) => r.includes(".wf-pin[data-ask='pick']:focus-visible"));
    expect(halo).toMatch(/outline:/);
    expect(ring).toMatch(/outline:\s*2px solid var\(--color-plex-gold\)/);
    expect(ring).toContain('.wf-maplab-layer');
  });
});

describe('a pick', () => {
  it('is never faded, and the bare circle hides its words', () => {
    const pick = mark('button', 'wf-maplab-chip wf-maplab-pick', { 'data-ask': 'pick', 'data-compact': 'true' });
    for (const cls of ['wf-maplab-pick-n', 'wf-maplab-pick-w', 'wf-maplab-pick-r']) {
      const word = document.createElement('span');
      word.className = cls;
      pick.appendChild(word);
    }

    expect(opacityOf(pick)).not.toBe('0.25');
    for (const word of pick.querySelectorAll('span')) expect(getComputedStyle(word).display).toBe('none');
  });

  it('keeps its words when it is a chip, not the bare circle', () => {
    const pick = mark('button', 'wf-maplab-chip wf-maplab-pick', { 'data-ask': 'pick' });
    const word = document.createElement('span');
    word.className = 'wf-maplab-pick-w';
    pick.appendChild(word);

    expect(getComputedStyle(word).display).not.toBe('none');
  });
});

describe('the fade wins on SPECIFICITY, not on where its block happens to sit', () => {
  // The same rules, the fade's FIRST — the order in which a tie would go to the tide rule. If the fade
  // only won because it was written last, this is the version of the stylesheet that loses.
  let reordered;
  beforeAll(() => {
    style.remove();
    const rules = [...sliceRules('wf-maplab-chip'), ...sliceRules('wf-maplab-pick'), ...sliceRules('wf-pin')];
    const fade = rules.filter((rule) => rule.includes("data-ask='fade'"));
    const rest = rules.filter((rule) => !rule.includes("data-ask='fade'"));
    reordered = document.createElement('style');
    reordered.textContent = [...fade, ...rest].join('\n');
    document.head.appendChild(reordered);
  });
  afterAll(() => {
    reordered.remove();
    document.head.appendChild(style);
  });

  it.each(KINDS)('a faded, tide-MISS %s is still .25 with the fade rules placed before the tide rules', (_, className) => {
    expect(reordered.textContent.indexOf("data-ask='fade'"))
      .toBeLessThan(reordered.textContent.indexOf("data-tide='miss'"));
    expect(opacityOf(mark('button', className, { 'data-tide': 'miss', 'data-ask': 'fade' }))).toBe('0.25');
  });
});
