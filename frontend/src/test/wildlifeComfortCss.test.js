import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * `index.css` — the rules a wildlife hide's comfort forecast stands on.
 *
 * <p>jsdom lays nothing out and applies no stylesheet, so the structural facts that make these
 * surfaces right are asserted against the REAL stylesheet with comments stripped
 * (`mapCalloutVerdictWrap.test.js`'s own method): the new button's focus ring is inset (the callout
 * body clips an outset one), the caption is never uppercased (it sits inside an accessible name), the
 * phone rules cannot leak onto the frozen overlay popup's table, and the popup's hidden header wins by
 * specificity rather than by file order. What the rules do to a pixel is the browser's to show.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** `index.css` with comments stripped and whitespace collapsed, so prose can never satisfy a test. */
function css() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`)
    .toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\s+/g, ' ');
}

/** The body of the FIRST top-level-or-nested rule whose selector LIST is exactly `selector`. */
function ruleBody(selector, source = css()) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`(^|[};]) ?${escaped} ?\\{([^}]*)\\}`).exec(source);
  expect(m, `no rule found for "${selector}"`).not.toBeNull();
  return m[2];
}

/** The declared value of `prop` inside `body`, trimmed, or null. */
function declared(body, prop) {
  const m = new RegExp(`(?:^|;) ?${prop} ?: ?([^;]+)`).exec(body);
  return m ? m[1].trim() : null;
}

/** Every `@media (max-width: 639px) { … }` body in the sheet (brace-matched). */
function phoneMediaBlocks() {
  const source = css();
  const blocks = [];
  const opener = '@media (max-width: 639px) {';
  let from = 0;
  for (;;) {
    const at = source.indexOf(opener, from);
    if (at === -1) return blocks;
    let depth = 1;
    let i = at + opener.length;
    while (i < source.length && depth > 0) {
      if (source[i] === '{') depth += 1;
      if (source[i] === '}') depth -= 1;
      i += 1;
    }
    blocks.push(source.slice(at + opener.length, i - 1));
    from = i;
  }
}

/** [ids, classes+attributes+pseudo-classes, elements] — enough CSS specificity for these selectors. */
function specificity(selector) {
  const classes = (selector.match(/\.[\w-]+|\[[^\]]+\]|:(?!:)[\w-]+/g) || []).length;
  const elements = (selector.replace(/\.[\w-]+|\[[^\]]+\]|:(?!:)[\w-]+/g, ' ').match(/(?:^|\s)[a-z][a-z0-9]*/gi) || []).length;
  return [0, classes, elements];
}
const greater = (a, b) => (a[0] - b[0]) || (a[1] - b[1]) || (a[2] - b[2]);

describe('the hide\'s callout button', () => {
  it('draws its focus ring INSET — the callout body clips an outset ring left and right', () => {
    const body = ruleBody('.wf-callout-comfort:focus-visible');
    expect(declared(body, 'outline')).toContain('2px solid');
    expect(declared(body, 'outline-offset')).toBe('-2px');
  });

  it('never uppercases its caption by CSS — it sits inside the button\'s accessible name', () => {
    expect(declared(ruleBody('.wf-callout-comfort-k'), 'text-transform')).toBeNull();
  });

  it('sets its caption and its verdict label in the secondary text token, not the ~3.5:1 muted one', () => {
    expect(declared(ruleBody('.wf-callout-comfort-k'), 'color')).toBe('var(--color-plex-text-secondary)');
    expect(declared(ruleBody('.wf-callout-verdict-comfort'), 'color')).toBe('var(--color-plex-text-secondary)');
  });

  it('leaves the muted line sky locations share exactly as it was', () => {
    expect(declared(ruleBody('.wf-callout-verdict-score.unscored'), 'color')).toBe('var(--color-plex-text-muted)');
  });
});

describe('the hourly table', () => {
  it('aligns cells to the baseline, what the popup\'s old display:table-cell divs had', () => {
    expect(declared(ruleBody('.wf-hourly th, .wf-hourly td'), 'vertical-align')).toBe('baseline');
  });

  it('zeroes the last column\'s trailing padding on the HEADER cell too, so it cannot set the width', () => {
    expect(declared(ruleBody('.wf-hourly th:last-child, .wf-hourly td:last-child'), 'padding-right')).toBe('0');
  });

  it('collapses the popup\'s hidden header by SPECIFICITY, not by coming later in the file', () => {
    const visible = specificity('.wf-hourly-head th');
    const hidden = specificity('.wf-hourly .wf-hourly-head-off th');
    expect(greater(hidden, visible)).toBeGreaterThan(0);
    // And the rule exists under exactly that selector, declaring the collapse.
    expect(declared(ruleBody('.wf-hourly .wf-hourly-head-off th'), 'padding')).toBe('0');
  });

  it('sets the sheet\'s visible column headers in the secondary token', () => {
    expect(declared(ruleBody('.wf-hourly-head th'), 'color')).toBe('var(--color-plex-text-secondary)');
  });

  it('caps the sheet\'s table width so its columns read as one row', () => {
    expect(declared(ruleBody('.wf-loc-hourly .wf-hourly'), 'max-width')).toBe('28rem');
  });

  it('gives the sheet\'s one scroller a visible focus style when it is focusable', () => {
    const body = ruleBody('.wf-loc-rows[tabindex]:focus-visible');
    expect(declared(body, 'outline')).toContain('2px solid');
    expect(declared(body, 'outline-offset')).toBe('-2px');
  });
});

describe('the phone rules for the sheet\'s table', () => {
  const blocks = () => phoneMediaBlocks().filter((b) => b.includes('.wf-loc-hourly'));

  it('exist in exactly one max-width: 639px block — the arm\'s single breakpoint', () => {
    expect(blocks()).toHaveLength(1);
  });

  it('are every one scoped under `.wf-loc-hourly`, so they can never reach the frozen overlay popup', () => {
    const block = blocks()[0];
    const selectorLists = block.split('}').map((r) => r.split('{')[0].trim()).filter(Boolean);
    expect(selectorLists.length).toBeGreaterThan(0);
    for (const list of selectorLists) {
      for (const selector of list.split(',').map((x) => x.trim())) {
        expect(selector, `unscoped phone selector "${selector}"`).toMatch(/^\.wf-loc-hourly /);
      }
    }
  });

  it('hide the glyphs, swap to the short headers, and zero the header cell\'s trailing padding too', () => {
    const block = blocks()[0];
    expect(block).toContain('.wf-loc-hourly .wf-wx-icon { display: none; }');
    expect(block).toContain('.wf-loc-hourly .wf-hourly-h-long { display: none; }');
    expect(block).toContain('.wf-loc-hourly .wf-hourly-h-short { display: inline; }');
    expect(block).toContain('.wf-loc-hourly .wf-hourly th:last-child, .wf-loc-hourly .wf-hourly td:last-child { padding-right: 0; }');
  });

  it('keep the short header form hidden everywhere else', () => {
    expect(declared(ruleBody('.wf-hourly-h-short'), 'display')).toBe('none');
  });
});
