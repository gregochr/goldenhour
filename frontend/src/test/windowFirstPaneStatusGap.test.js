import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The Plan pane's always-mounted `window-first-pane-status` wrapper
 * (`WindowFirstShell.jsx`) sits inside `.wf-body`, which is a flex column carrying its own
 * `gap: 10px` (index.css). Review round 1 (finding 1) caught that an always-mounted but usually
 * EMPTY flex child still receives a flex gap on both sides of it, so the wrapper quietly doubled
 * the strip→doors spacing from 10px to 20px in the loaded-with-cards state — the state almost
 * every reader sees. `.wf-pane-status:empty { margin-top: -10px }` cancels exactly one of those
 * two gaps, so the net spacing is unchanged.
 *
 * <p>This is a CSS-level pin, not a layout one: jsdom does not compute flex geometry, so nothing
 * here can measure the resulting pixel gap in a browser (`WindowFirstShellSticky.test.jsx`'s own
 * header makes the same point about this suite generally). What IS checkable from the stylesheet
 * text alone is the one invariant that matters — the two rules declare equal and opposite
 * magnitudes — and that they travel together, which `index.css`'s own comment on
 * `.wf-pane-status:empty` asks for explicitly. A future edit to `.wf-body`'s gap that forgets the
 * sibling rule is exactly the drift this test exists to catch.
 */

const readCss = () => readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');

/**
 * One rule's text, comments and all, from its selector to its MATCHING closing brace —
 * depth-counted from the selector's own opening `{`, not a search for the next `\n}`.
 *
 * <p>A one-line rule (`.wf-pane-status:empty { margin-top: -10px; }`) closes on the same line it
 * opens, so its own `}` is never preceded by a newline — a `\n}` search walks straight past it
 * and matches whatever the NEXT multi-line block's closing brace happens to be instead. That
 * first version of this helper worked here only because nothing between the two rules declared
 * `margin-top` — a false pass this file exists to end, not to repeat.
 */
const block = (selector) => {
  const css = readCss();
  const at = css.indexOf(`\n${selector} {`);
  expect(at, `${selector} must exist`).toBeGreaterThan(-1);
  const openBrace = css.indexOf('{', at);
  let depth = 0;
  let end = openBrace;
  for (; end < css.length; end += 1) {
    if (css[end] === '{') depth += 1;
    else if (css[end] === '}') {
      depth -= 1;
      if (depth === 0) break;
    }
  }
  return css.slice(at, end + 1);
};

describe('the Plan pane status wrapper cancels the flex gap it would otherwise add when empty', () => {
  it('declares a margin-top equal and opposite to .wf-body\'s own gap', () => {
    const bodyGap = block('.wf-body').match(/gap:\s*(-?[\d.]+)px/);
    const statusMargin = block('.wf-pane-status:empty').match(/margin-top:\s*(-?[\d.]+)px/);

    expect(bodyGap, '.wf-body must declare a pixel gap').not.toBeNull();
    expect(statusMargin, '.wf-pane-status:empty must declare a pixel margin-top').not.toBeNull();

    const gapPx = Number(bodyGap[1]);
    const marginPx = Number(statusMargin[1]);
    expect(gapPx).toBeGreaterThan(0);
    expect(marginPx).toBe(-gapPx);
  });
});
