import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The Ask upsell's stylesheet rules (docs/engineering/ask-upsell-plan.md §3.1, §4), read as text: jsdom
 * applies no stylesheet. The WHOLE file is searched, because the tide upsell's shared rules sit well before
 * the Ask block that `askCss.test.js`'s `ruleBody` starts from. Comments are stripped so prose naming a
 * selector can never satisfy a check.
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** Every rule as { selectors: string[], body }, at-rule wrappers flattened (their inner rules still match). */
function rules() {
  const out = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  let m = re.exec(css);
  while (m !== null) {
    out.push({ selectors: m[1].split(',').map((s) => s.trim()), body: m[2] });
    m = re.exec(css);
  }
  return out;
}
const ALL = rules();
const ruleNaming = (selector) => ALL.find((r) => r.selectors.includes(selector));

describe('the Ask upsell shares the tide upsell\'s look through selector lists', () => {
  it.each([
    ['.wf-tide-upsell', '.wf-ask-upsell'],
    ['.wf-tide-upsell-heading', '.wf-ask-upsell-heading'],
    ['.wf-tide-upsell-heading:focus', '.wf-ask-upsell-heading:focus'],
    ['.wf-tide-upsell-body', '.wf-ask-upsell-body'],
    ['.wf-tide-upsell-preview', '.wf-ask-upsell-preview'],
    ['.wf-tide-upsell-dismiss', '.wf-ask-upsell-dismiss'],
    ['.wf-tide-upsell-dismiss:hover', '.wf-ask-upsell-dismiss:hover'],
    ['.wf-tide-upsell-dismiss:focus-visible', '.wf-ask-upsell-dismiss:focus-visible'],
    ['.wf-tide-upsell-inline', '.wf-ask-upsell-inline'],
  ])('%s and %s are one rule', (tide, askClass) => {
    const rule = ALL.find((r) => r.selectors.includes(tide) && r.selectors.includes(askClass));
    expect(rule, `${tide} + ${askClass}`).toBeDefined();
  });

  it('keeps the tide popover\'s positioning in a rule that names no Ask class', () => {
    const positioned = ALL.find((r) => r.selectors.length === 1 && r.selectors[0] === '.wf-tide-upsell');
    expect(positioned).toBeDefined();
    expect(positioned.body).toMatch(/position:\s*absolute/);
    expect(positioned.body).toMatch(/bottom:\s*0/);
  });

  it('never names an Ask class in the tide popover\'s anchor rule (it positions inside .wf-map-chrome-bl)', () => {
    const anchor = ruleNaming('.wf-tide-upsell-anchor');
    expect(anchor).toBeDefined();
    expect(anchor.selectors).toEqual(['.wf-tide-upsell-anchor']);
  });
});

describe('the Ask upsell popover\'s placement and stacking', () => {
  const field = () => ruleNaming('.wf-ask-upsell-pop[data-placement="field"]');
  const bar = () => ruleNaming('.wf-ask-upsell-pop[data-placement="bar"]');
  const zOf = (rule) => Number(/z-index:\s*(\d+)/.exec(rule.body)?.[1]);

  it('hangs the field\'s popover from the tab row, right-aligned, 320px, clamped to the column', () => {
    expect(field().body).toMatch(/position:\s*absolute/);
    expect(field().body).toMatch(/top:\s*100%/);
    expect(field().body).toMatch(/right:\s*var\(--wf-gutter\)/);
    expect(field().body).toMatch(/width:\s*320px/);
    expect(field().body).toMatch(/max-width:\s*calc\(100% - 16px\)/);
    expect(ruleNaming('.wf-tabrow').body).toMatch(/position:\s*relative/);
  });

  it('fixes the bar\'s popover at the bar\'s own sides, above it, at the bar\'s own tier', () => {
    expect(bar().body).toMatch(/position:\s*fixed/);
    expect(bar().body).toMatch(/left:\s*calc\(10px \+ var\(--safe-l\)\)/);
    expect(bar().body).toMatch(/right:\s*calc\(10px \+ var\(--safe-r\)\)/);
    expect(bar().body).toMatch(/bottom:\s*calc\(10px \+ var\(--safe-b\) \+ 48px \+ 8px\)/);
    expect(zOf(bar())).toBe(zOf(ruleNaming('.wf-ask-bar')));
  });

  it('keeps every Ask upsell z-index above 0 and below Modal\'s z-50 and the sheet scrim', () => {
    const open = ruleNaming('.wf-tabrow[data-ask-upsell="open"]');
    expect(open).toBeDefined();
    for (const rule of [field(), bar(), open]) {
      expect(zOf(rule)).toBeGreaterThanOrEqual(1);
      expect(zOf(rule)).toBeLessThan(50);
    }
  });

  it('raises the tab row only while the popover is open, so it paints over the Map\'s own stacking context', () => {
    expect(ruleNaming('.wf-tabrow').body).not.toMatch(/z-index/);
    expect(zOf(ruleNaming('.wf-tabrow[data-ask-upsell="open"]'))).toBeGreaterThan(0);
  });

  it('keeps the raised tab row below the masthead\'s z-index 45, so it never ties with .wf-mast', () => {
    const mast = zOf(ruleNaming('.wf-mast'));
    expect(mast).toBe(45);
    expect(zOf(ruleNaming('.wf-tabrow[data-ask-upsell="open"]'))).toBeLessThan(mast);
  });

  it('gives the sketch CanvasText fills and strokes in forced colours, so it does not vanish', () => {
    expect(ruleNaming('.wf-ask-upsell-preview rect').body).toMatch(/fill:\s*CanvasText/);
    expect(ruleNaming('.wf-ask-upsell-preview circle').body).toMatch(/stroke:\s*CanvasText/);
  });

  it('draws no focus ring on the programmatic-focus root', () => {
    expect(ruleNaming('.wf-ask-upsell-pop:focus').body).toMatch(/outline:\s*none/);
  });
});

describe('the retired lock rules are gone', () => {
  it.each([
    '.wf-askf-lockrow',
    '.wf-askf-lockwrap',
    '.wf-ask-bar-inner',
    '.wf-ask-bar[data-locked]:disabled',
    '.wf-lens-locked .wf-askf:disabled',
  ])('%s', (selector) => {
    expect(ruleNaming(selector)).toBeUndefined();
  });
});
