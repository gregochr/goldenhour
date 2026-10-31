import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll, afterEach,
} from 'vitest';

/**
 * What the topic dim does to a chip or a pin, asserted against the REAL `index.css`
 * (hot-topics-by-day-plan §3 M1 "Dim"; the technique and its limits are `askMapCascade.test.jsx`'s).
 *
 * <p>The dim is a specificity question in three places: it must NOT override Ask's .25 fade, it must
 * beat the coastal-miss .72, and it must not touch a big-seas chip, an Ask pick or the selected
 * place. jsdom resolves specificity and source order; it does not match {@code :hover} or
 * {@code :focus-visible} or lay anything out, so the hover/focus restores are pinned as exclusions in
 * the selector rather than by a hover jsdom cannot make.
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

let style;
beforeAll(() => {
  const rules = [
    ...sliceRules('wf-maplab-chip'), ...sliceRules('wf-maplab-pick'),
    ...sliceRules('wf-topics'), ...sliceRules('wf-tch'),
    ...sliceRules('wf-map-chrome-tl'), ...sliceRules('wf-win-control'), ...sliceRules('wf-big-seas'),
  ].join('\n');
  expect(rules).toContain('data-topic-selected');
  style = document.createElement('style');
  style.textContent = rules;
  document.head.appendChild(style);
});
afterAll(() => style.remove());
afterEach(() => { document.body.innerHTML = ''; });

/** A mark inside the label layer inside the pane, as the app renders it. */
function mark(tag, className, attrs = {}, { selectedTopic = true } = {}) {
  const pane = document.createElement('div');
  pane.className = 'wf-map-tab';
  if (selectedTopic) pane.setAttribute('data-topic-selected', 'true');
  const layer = document.createElement('div');
  layer.className = 'wf-maplab-layer';
  const el = document.createElement(tag);
  el.className = className;
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  layer.appendChild(el);
  pane.appendChild(layer);
  document.body.appendChild(pane);
  return el;
}
const opacityOf = (el) => getComputedStyle(el).opacity;

describe.each([['chip', 'wf-maplab-chip']])('an ordinary %s', (_, className) => {
  it('steps back to .5 while a topic is selected, and not otherwise', () => {
    expect(opacityOf(mark('button', className))).toBe('0.5');
    expect(opacityOf(mark('button', className, {}, { selectedTopic: false }))).not.toBe('0.5');
  });

  it('⚠️ a tide MISS dims to .5 as well - the dim beats the .72', () => {
    expect(opacityOf(mark('button', className, { 'data-tide': 'miss' }, { selectedTopic: false }))).toBe('0.72');
    expect(opacityOf(mark('button', className, { 'data-tide': 'miss' }))).toBe('0.5');
  });

  it('⚠️ Ask\'s fade wins when both apply: .25, not .5', () => {
    expect(opacityOf(mark('button', className, { 'data-ask': 'fade' }))).toBe('0.25');
    expect(opacityOf(mark('button', className, { 'data-ask': 'fade', 'data-tide': 'miss' }))).toBe('0.25');
  });

  it('⚠️ never dims a place the selected topic rings (data-topic-ring); Ask\'s fade still wins on it', () => {
    expect(opacityOf(mark('button', className, { 'data-topic-ring': 'true' }))).not.toBe('0.5');
    expect(opacityOf(mark('button', className, { 'data-topic-ring': 'true', 'data-ask': 'fade' }))).toBe('0.25');
    // And an ordinary one beside it still reads .5.
    expect(opacityOf(mark('button', className))).toBe('0.5');
  });

  it('never dims an Ask pick', () => {
    expect(opacityOf(mark('button', className, { 'data-ask': 'pick' }))).not.toBe('0.5');
  });

  it('is not dimmed when it is the selected place', () => {
    expect(opacityOf(mark('button', className, { 'data-selected': 'true' }))).not.toBe('0.5');
  });

  it('names hover and keyboard focus as exclusions in the selector, so they restore it', () => {
    const dim = sliceRules('data-topic-selected').filter((r) => r.includes(className) && r.includes('opacity: 0.5'));
    expect(dim.length).toBeGreaterThan(0);
    for (const rule of dim) {
      expect(rule).toContain(':not(:hover)');
      expect(rule).toContain(':not(:focus-visible)');
    }
  });
});

describe('a big-seas chip', () => {
  it('never dims: it is the ringed answer on a big-seas day', () => {
    expect(opacityOf(mark('button', 'wf-maplab-chip wf-bsc'))).not.toBe('0.5');
  });
});

describe('the chips row', () => {
  it('is pointer-events: none, with the chips auto (a wrapped row must not swallow map drags)', () => {
    const row = document.createElement('div');
    row.className = 'wf-topics';
    const chip = document.createElement('button');
    chip.className = 'wf-tch';
    row.appendChild(chip);
    document.body.appendChild(row);
    expect(getComputedStyle(row).pointerEvents).toBe('none');
    expect(getComputedStyle(chip).pointerEvents).toBe('auto');
  });

  it('hides the "where" text only inside the phone media query', () => {
    const css = readFileSync(INDEX_CSS, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const phone = /@media \(max-width: 639px\) \{([^@]*?\.wf-tch-w \{ display: none; \}[^@]*?)\}\s*\n/.exec(css);
    expect(phone, 'a (max-width: 639px) block hiding .wf-tch-w').not.toBeNull();
    // And the base rule for it does not hide anything outside that query.
    const baseWhere = sliceRules('.wf-tch-w').filter((r) => r.startsWith('.wf-tch-w '));
    expect(baseWhere.join('\n')).not.toMatch(/display:\s*none/);
  });

  it('keeps a real outline on the selected chip in forced colours', () => {
    const css = readFileSync(INDEX_CSS, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    expect(css).toMatch(/@media \(forced-colors: active\) \{\s*\.wf-tch\[aria-pressed='true'\] \{ outline: 2px solid Highlight; \}/);
  });
});

describe('hit-testing in the top-left chrome group', () => {
  /** The group as the app renders it: the control (with a dropdown inside), the big-seas line, the chips. */
  function group() {
    const pane = document.createElement('div');
    pane.className = 'wf-map-tab';
    pane.innerHTML = `
      <div class="wf-map-chrome-tl" id="g">
        <div class="wf-win-control" id="control"><button id="pill"></button><div id="menu"></div></div>
        <div class="wf-big-seas" id="seas"><button id="go"></button></div>
        <div class="wf-topics" id="row"><button class="wf-tch" id="chip"></button><p class="wf-topics-sky" id="sky"></p></div>
      </div>`;
    document.body.appendChild(pane);
    return (id) => getComputedStyle(document.getElementById(id)).pointerEvents;
  }

  it('⚠️ the GROUP lets presses through to the map: its gaps and empty space are not hit-testable', () => {
    expect(group()('g')).toBe('none');
  });

  it('the controls opt back in: the window control (and what it contains), the big-seas line and the chips', () => {
    const pe = group();
    expect(pe('control')).toBe('auto');
    expect(pe('pill')).toBe('auto');
    expect(pe('menu')).toBe('auto');
    expect(pe('seas')).toBe('auto');
    expect(pe('go')).toBe('auto');
    expect(pe('chip')).toBe('auto');
  });

  it('the chips row itself, and the sky-wide line in it, stay transparent to presses', () => {
    const pe = group();
    expect(pe('row')).toBe('none');
    expect(pe('sky')).toBe('none');
  });
});

describe('a stood-down place label', () => {
  const STOOD = 'wf-maplab-chip wf-maplab-stood';

  it('steps back with the other chips while a topic is selected, and is kept by the same exclusions', () => {
    expect(opacityOf(mark('button', STOOD))).toBe('0.5');
    expect(opacityOf(mark('button', STOOD, { 'data-ask': 'fade' }))).toBe('0.25');
    expect(opacityOf(mark('button', STOOD, { 'data-selected': 'true' }))).not.toBe('0.5');
  });
});
