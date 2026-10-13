import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * `index.css` — the location sheet's rows keep their height and the list scrolls.
 *
 * <p>`.wf-loc-row` clips at `overflow: hidden`, which makes a flex item's automatic minimum height 0,
 * so without `flex-shrink: 0` the `.wf-loc-rows` column squeezed every row to fit the sheet and cut
 * an open row's prose off at its bottom edge. jsdom lays nothing out, so this pins the declarations
 * against the REAL stylesheet with comments stripped (`wildlifeComfortCss.test.js`'s own method);
 * what they do to a pixel is the browser's to show.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** `index.css` with comments stripped and whitespace collapsed, so prose can never satisfy a test. */
function css() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`)
    .toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\s+/g, ' ');
}

/** The body of the FIRST rule whose selector LIST is exactly `selector`. */
function ruleBody(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`(^|[};]) ?${escaped} ?\\{([^}]*)\\}`).exec(css());
  expect(m, `no rule found for "${selector}"`).not.toBeNull();
  return m[2];
}

/** The declared value of `prop` inside `body`, trimmed, or null. */
function declared(body, prop) {
  const m = new RegExp(`(?:^|;) ?${prop} ?: ?([^;]+)`).exec(body);
  return m ? m[1].trim() : null;
}

describe('location sheet rows', () => {
  it('a row never shrinks below its content, although it clips', () => {
    const row = ruleBody('.wf-loc-row');
    expect(declared(row, 'overflow')).toBe('hidden');
    expect(declared(row, 'flex-shrink')).toBe('0');
  });

  it('the list is the scroller, a flex column', () => {
    const rows = ruleBody('.wf-loc-rows');
    expect(declared(rows, 'overflow-y')).toBe('auto');
    expect(declared(rows, 'display')).toBe('flex');
    expect(declared(rows, 'flex-direction')).toBe('column');
  });
});
