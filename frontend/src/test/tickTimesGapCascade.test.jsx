import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, afterEach,
} from 'vitest';

/**
 * The masthead tick line's light-times gap, per tier, asserted against the REAL `index.css`.
 *
 * <h2>Why a cascade test, and why this one resolves the tiers itself</h2>
 *
 * <p>The plan-matrix prototype runs three gaps — 16px desktop, 12px iPad, 9px phone — and the code
 * reaches them with THREE rules that only work as a set: the unscoped base `.wf-tick-times` (the
 * desktop value, and what every width inherits), an override in the phone query, and an override in
 * the iPad block. The iPad band is correct only because its override comes AFTER the base in source
 * order; move it earlier and the base wins, and the iPad band silently draws the desktop gap. That is
 * how this drifted in the first place — the base once held the iPad value, and iPad was right only by
 * inheriting it. Nothing but a test pinned to the cascade can catch the next version of that.
 *
 * <p>jsdom does not evaluate `@media` (the suite's `matchMedia` stub always answers `false`), so the
 * sibling `*Cascade.test.jsx` files test "which declaration wins once the query is satisfied". A
 * plain concatenation of every media-scoped rule would collapse all three tiers into whichever rule
 * is last, so this file goes one step further: for each width it keeps only the rules whose media
 * condition matches that width, injects them in source order, and reads what the cascade resolves.
 * The condition parser handles only `min-width`/`max-width` and THROWS on anything else — a rule
 * scoped by a feature it cannot evaluate must fail this test loudly, never be silently mis-scoped.
 *
 * <p>⚠️ It reads `gap`, not `columnGap`. jsdom does not expand the `gap` shorthand: a rule declaring
 * `gap: 16px` reports `columnGap: 'normal'` (measured). Reading `columnGap` here would see `'normal'`
 * at every tier — the three values this file exists to tell apart would read identically.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');
const SELECTOR = '.wf-tick-times';

/**
 * Every top-level block in `css`, in source order — the brace-depth scanner the sibling cascade
 * files use. A block whose selector is an `@media` rule carries its nested rules unparsed in
 * `bodyInner`, for the caller to scan again.
 *
 * @param {string} css
 * @returns {Array<{selector: string, bodyInner: string}>}
 */
function sliceTopLevelBlocks(css) {
  const blocks = [];
  let depth = 0;
  let selectorStart = 0;
  let blockStart = -1;
  for (let i = 0; i < css.length; i += 1) {
    if (css[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        blocks.push({ selector: css.slice(selectorStart, blockStart).trim(), bodyInner: css.slice(blockStart + 1, i) });
        selectorStart = i + 1;
      }
    }
  }
  return blocks;
}

/** True when a selector LIST names exactly `.wf-tick-times` — not `.wf-tick-times-foo`, not a descendant. */
const namesTickTimes = (selector) => selector.split(',').some((part) => part.trim() === SELECTOR);

/**
 * Every `.wf-tick-times` rule in `index.css`, in source order, with the media condition it sits in
 * (`null` when unscoped). Comments are stripped first so a selector quoted in prose is never read as
 * a rule.
 *
 * @returns {Array<{media: ?string, css: string}>}
 */
function tickTimesRules() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
  const css = readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const rules = [];
  sliceTopLevelBlocks(css).forEach((block) => {
    if (block.selector.startsWith('@media')) {
      sliceTopLevelBlocks(block.bodyInner).forEach((inner) => {
        if (namesTickTimes(inner.selector)) rules.push({ media: block.selector, css: `${inner.selector}{${inner.bodyInner}}` });
      });
    } else if (namesTickTimes(block.selector)) {
      rules.push({ media: null, css: `${block.selector}{${block.bodyInner}}` });
    }
  });
  return rules;
}

/**
 * Whether a media condition holds at `width`. Only `min-width`/`max-width` joined by `and` are
 * understood; anything else throws, so an unevaluable scope fails the test instead of being guessed.
 *
 * @param {?string} media the `@media ...` prelude, or null for an unscoped rule
 * @param {number} width viewport width in px
 * @returns {boolean}
 */
function mediaMatches(media, width) {
  if (media == null) return true;
  const condition = media.replace(/^@media\s*/, '');
  const leftover = condition.replace(/\((min|max)-width:\s*\d+px\)/g, '').replace(/\band\b/g, '').trim();
  if (leftover !== '') throw new Error(`Cannot evaluate media condition "${media}" — extend mediaMatches`);
  const min = condition.match(/min-width:\s*(\d+)px/);
  const max = condition.match(/max-width:\s*(\d+)px/);
  if (min && width < Number(min[1])) return false;
  if (max && width > Number(max[1])) return false;
  return true;
}

let injected = [];
afterEach(() => {
  injected.forEach((node) => node.remove());
  injected = [];
});

/**
 * The gap the cascade resolves for `.wf-tick-times` at `width`, using only the rules in force there.
 *
 * @param {number} width
 * @returns {string}
 */
function gapAt(width) {
  const style = document.createElement('style');
  style.textContent = tickTimesRules().filter((rule) => mediaMatches(rule.media, width)).map((rule) => rule.css).join('\n');
  const el = document.createElement('span');
  el.className = 'wf-tick-times';
  document.head.appendChild(style);
  document.body.appendChild(el);
  injected.push(style, el);
  return getComputedStyle(el).gap;
}

describe('the masthead light-times gap follows the plan-matrix prototype at every tier', () => {
  // Each band is tested at both of its edges and inside it, so a breakpoint moved by one pixel, or
  // an override that stops winning, fails the specific case that names it.
  it.each([
    [390, '9px', 'phone'],
    [639, '9px', 'phone, at its upper edge'],
    [640, '12px', 'iPad, at its lower edge — one pixel above the phone query'],
    [834, '12px', 'iPad, the handoff\'s own iPad frame'],
    [1023, '12px', 'iPad, at its upper edge'],
    [1024, '16px', 'desktop, at its lower edge — one pixel above the iPad block'],
    [1280, '16px', 'desktop'],
  ])('%ipx resolves to %s (%s)', (width, expected) => {
    expect(gapAt(width)).toBe(expected);
  });

  it('the iPad band does not inherit the desktop gap', () => {
    // The regression this file exists for: before 2026-09-14 the base held 12px and the iPad band was
    // right only by inheriting it. With the base now at 16px, a missing or out-of-order iPad override
    // would put the desktop value on every iPad — this is that failure, stated as a negative.
    expect(gapAt(834)).not.toBe(gapAt(1280));
  });

  it('reads every rule it resolves, rather than finding none and trivially agreeing', () => {
    // A slicer that matched nothing would inject an empty stylesheet, and every width would read the
    // same default — a vacuous pass. Three rules exist: the unscoped base, the phone override and the
    // iPad override. Pinning that they are all found is what makes the per-width cases above mean
    // something.
    const rules = tickTimesRules();
    expect(rules.filter((rule) => rule.media == null)).toHaveLength(1);
    expect(rules.filter((rule) => rule.media != null)).toHaveLength(2);
  });
});
