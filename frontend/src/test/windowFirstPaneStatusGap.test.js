import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The Plan pane has TWO always-mounted live-region children of `.wf-body`, which is a flex column
 * carrying its own `gap: 10px` (index.css): the conflict slot (`window-first-conflict-slot`, the
 * pane's FIRST child) and the pending/empty status wrapper (`window-first-pane-status`, mounted
 * later, after the heat strip). Review round 1 (finding 1) caught the status wrapper's half of
 * this — an always-mounted but usually EMPTY flex child still receives a flex gap on both sides of
 * it, so the wrapper quietly doubled the strip→doors spacing from 10px to 20px in the
 * loaded-with-cards state — the state almost every reader sees. The conflict slot carries the
 * identical defect and had it since before the status wrapper existed: being empty whenever there
 * is no page-level conflict, it added a 10px gap AFTER itself, pushing every reader's first visible
 * pane content (the safety line, the heat strip, or the pending/empty status line) 23px from the
 * pane's top instead of 13px.
 *
 * <p>The two cancelling rules use OPPOSITE margin properties, but the direction is a NAMING
 * convention, not a structural requirement — a review round 2 finding corrected the first cut of
 * this file, which claimed otherwise. An empty flex item is 0px tall and flex margins never
 * collapse, so its next sibling lands at exactly the same pixel whichever side of the empty item
 * carries the cancelling margin: `.wf-pane-status:empty { margin-top: -10px }` and a hypothetical
 * `.wf-conflict-slot:empty { margin-top: -10px }` would produce an IDENTICAL layout to the
 * `margin-bottom` this file actually declares. `margin-top` reads naturally for the status wrapper
 * ("cancel the gap before this element") and `margin-bottom` for the conflict slot, which is the
 * pane's first child and therefore has no gap before it to name — but either slot could use either
 * property with no visible difference. What IS load-bearing is that each rule declares EXACTLY ONE
 * cancelling margin: a rule carrying both `margin-top` and `margin-bottom` would cancel two gaps
 * instead of one, collapsing a real adjacent gap to zero — that is the actual defect the tests
 * below guard against, not a swapped property name.
 *
 * <p>This is a CSS-level pin, not a layout one: jsdom does not compute flex geometry, so nothing
 * here can measure the resulting pixel gap in a browser (`WindowFirstShellSticky.test.jsx`'s own
 * header makes the same point about this suite generally). What IS checkable from the stylesheet
 * text alone is the invariant that matters — each `:empty` rule declares a magnitude equal and
 * opposite to `.wf-body`'s own gap, and declares no second, opposite-direction margin — and that
 * the three rules travel together, which `index.css`'s own comments on both `:empty` rules ask for
 * explicitly. A future edit to `.wf-body`'s gap that forgets a sibling rule, or a sibling rule that
 * gains a second margin property, is exactly the drift this test exists to catch.
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

describe('the Plan pane\'s always-mounted live-region slots cancel the flex gap they would otherwise add when empty', () => {
  it('cancels exactly one gap after the status wrapper', () => {
    const bodyGap = block('.wf-body').match(/gap:\s*(-?[\d.]+)px/);
    const statusRule = block('.wf-pane-status:empty');
    const statusMargin = statusRule.match(/margin-top:\s*(-?[\d.]+)px/);

    expect(bodyGap, '.wf-body must declare a pixel gap').not.toBeNull();
    expect(statusMargin, '.wf-pane-status:empty must declare a pixel margin-top').not.toBeNull();

    const gapPx = Number(bodyGap[1]);
    const marginPx = Number(statusMargin[1]);
    expect(gapPx).toBeGreaterThan(0);
    expect(marginPx).toBe(-gapPx);

    // Which side names the cancelled gap is a convention (see the header comment) — but a rule
    // declaring BOTH margin-top and margin-bottom would cancel two gaps instead of one, which IS a
    // visible defect. A mutant that added a margin-bottom here fails this.
    expect(statusRule).not.toMatch(/margin-bottom/);
  });

  it('cancels exactly one gap after the conflict slot', () => {
    const bodyGap = block('.wf-body').match(/gap:\s*(-?[\d.]+)px/);
    const conflictRule = block('.wf-conflict-slot:empty');
    const conflictMargin = conflictRule.match(/margin-bottom:\s*(-?[\d.]+)px/);

    expect(bodyGap, '.wf-body must declare a pixel gap').not.toBeNull();
    expect(conflictMargin, '.wf-conflict-slot:empty must declare a pixel margin-bottom').not.toBeNull();

    const gapPx = Number(bodyGap[1]);
    const marginPx = Number(conflictMargin[1]);
    expect(gapPx).toBeGreaterThan(0);
    expect(marginPx).toBe(-gapPx);

    // Which side names the cancelled gap is a convention (see the header comment) — but a rule
    // declaring BOTH margin-top and margin-bottom would cancel two gaps instead of one, which IS a
    // visible defect. A mutant that added a margin-top here fails this.
    expect(conflictRule).not.toMatch(/margin-top/);
  });
});
