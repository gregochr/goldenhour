import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The Plan pane has ONE always-mounted live-region child of `.wf-body`, which is a flex column
 * carrying its own `gap: 10px` (index.css): the pending/empty status wrapper
 * (`window-first-pane-status`, mounted after the heat strip). An always-mounted but usually EMPTY flex
 * child still receives a flex gap on both sides of it, so the wrapper quietly doubled the
 * strip→doors spacing from 10px to 20px in the loaded-with-cards state — the state almost every
 * reader sees (review round 1, finding 1) — until `.wf-pane-status:empty` cancelled one gap.
 *
 * <p>A second slot, the conflict card's (`window-first-conflict-slot`, the pane's FIRST child, with
 * its own `.wf-conflict-slot:empty` rule), carried the identical defect until the card was deleted
 * (lens-verdict-line-plan.md): its live region is the lens bar's status span now, which is not a
 * `.wf-body` child. The test below pins that the slot's rule is gone with it, so the first visible
 * element under the bar sits where it did when there was no conflict.
 *
 * <p>Which margin property cancels the gap is a NAMING convention, not a structural requirement: an
 * empty flex item is 0px tall and flex margins never collapse, so its next sibling lands at the same
 * pixel whichever side carries the margin. What IS load-bearing is that the rule declares EXACTLY ONE
 * cancelling margin: a rule carrying both `margin-top` and `margin-bottom` would cancel two gaps
 * instead of one, collapsing a real adjacent gap to zero.
 *
 * <p>This is a CSS-level pin, not a layout one: jsdom does not compute flex geometry
 * (`WindowFirstShellSticky.test.jsx`'s own header makes the same point about this suite generally).
 * What IS checkable from the stylesheet text alone is that the `:empty` rule declares a magnitude equal
 * and opposite to `.wf-body`'s own gap and declares no second, opposite-direction margin.
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

describe('the Plan pane\'s always-mounted live-region slot cancels the flex gap it would otherwise add when empty', () => {
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

  it('has no conflict-slot rule left, because the slot (and the card it held) is gone', () => {
    // A surviving `.wf-conflict-slot:empty { margin-bottom: -10px }` would cancel a gap that no
    // longer exists and pull the first visible element under the bar 10px too far up.
    expect(readCss()).not.toContain('.wf-conflict-slot');
    // The card's own rules went with it; only the shared action button class survives (the window
    // popup's "see all" action wears it).
    expect(readCss()).not.toMatch(/\.wf-clash\s*[{,]/);
    expect(readCss()).not.toContain('.wf-clash-acts');
    expect(readCss()).toContain('.wf-clash-act {');
  });
});
