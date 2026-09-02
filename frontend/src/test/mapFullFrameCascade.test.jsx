import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll,
} from 'vitest';

/**
 * `.wf-body.wf-body--map`'s specificity contest against the phone media query's own plain
 * `.wf-body` rule (map-tab-v2-plan.md §3 P7, adversarial review real finding #1) — asserted
 * against the REAL `index.css`, the same slicer technique as `basemapDressCascade.test.jsx` /
 * `mapChromeZLadderCascade.test.jsx`: the suite runs with `css: false` (`vite.config.js`), so
 * nothing else in it resolves what a class actually paints.
 *
 * <h2>The bug this pins</h2>
 *
 * <p>`.wf-body--map { padding: 0 }` and the phone media query's `.wf-body { padding: 12px … }`
 * are both plain class selectors — equal specificity (0,1,0) — and the phone rule sits LATER in
 * source order. Equal specificity resolves by source order, so on a phone the padded rule won
 * regardless of the map-tab class being present, and the "full-bleed" map carried a padded band
 * around it on every real device this was tried on — invisible on desktop, where no media query
 * competes. The fix is the compound selector `.wf-body.wf-body--map` (specificity 0,2,0), which
 * beats both the plain desktop rule and the phone override UNCONDITIONALLY, independent of source
 * order — robust against either rule being reordered later.
 *
 * <h2>Why the `@media` wrapper is stripped rather than injected verbatim</h2>
 *
 * <p>Verified directly (a throwaway probe, not a guess): jsdom's `window.matchMedia` is the
 * suite's own dumb polyfill (`test/setup.js`, installed because jsdom provides no real one) that
 * returns `matches: false` for every query regardless of `window.innerWidth`, and the style
 * engine's own `@media` handling agrees with it — a `@media (max-width: 639px)` block's
 * declarations never applied in this environment, at `innerWidth` 1024 OR 375. So injecting the
 * phone rule wrapped in its real `@media` block would prove nothing: the block would just never
 * match, and the "compound selector wins" assertion below would pass whether or not the FIX did
 * anything, because the only OTHER competing declaration in play would already be excluded.
 *
 * <p>What jsdom DOES resolve correctly (proven the same way) is selector specificity and source
 * order between ORDINARY, unconditional rules. So this file reproduces the state a real phone
 * reaches once its media query is true — the phone's `.wf-body` declaration, unconditional, at the
 * same relative source position — by extracting just the SELECTOR + DECLARATION from inside the
 * real `@media` block and discarding the wrapper. The wrapper's condition is real-browser-only, on
 * the far side of a claim CLAUDE.md already draws ("a CSS claim is a browser claim"); the
 * specificity contest on the far side of that condition is exactly what jsdom CAN prove, and is
 * the thing this bug was actually about.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');
const REAL_CSS = readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * Every TOP-LEVEL rule in {@code source} whose selector is EXACTLY {@code wanted} (not merely
 * contains it — `.wf-body` and `.wf-body.wf-body--map` each contain the substring `.wf-body`, and
 * this file needs the two told apart precisely, not concatenated together), in source order.
 * Brace-depth aware, the same technique as the sibling cascade files' own substring slicers (depth
 * returns to 0 only at a rule's own closing brace, so a selector nested inside an `@media` block is
 * never matched by a call against the whole file — which is exactly why `extractMediaInner` below
 * exists, to hand this function the INSIDE of a media block as its own top-level source text).
 *
 * @param {string} wanted the exact selector text to match
 * @param {string} [source] CSS text to scan; defaults to the real `index.css`
 * @returns {string} the concatenated rules, ready to inject
 */
function sliceExactTopLevelRule(wanted, source = REAL_CSS) {
  const rules = [];
  let selectorStart = 0;
  let depth = 0;
  let blockStart = -1;
  for (let i = 0; i < source.length; i += 1) {
    if (source[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        const selector = source.slice(selectorStart, blockStart).trim();
        if (selector === wanted) rules.push(`${selector} ${source.slice(blockStart, i + 1)}`);
        selectorStart = i + 1;
      }
    }
  }
  return rules.join('\n');
}

/**
 * The INNER text of the first top-level `@media (...)` block whose content contains
 * {@code innerNeedle} — the wrapper (`@media (...) { … }`) stripped off, leaving plain rules that
 * {@link sliceExactTopLevelRule} can scan as if they were unconditional top-level rules (see the
 * file header for why the wrapper is discarded rather than kept).
 *
 * @param {string} innerNeedle substring the block's inner text must contain
 * @returns {?string} the block's inner text, or null if no matching block was found
 */
function extractMediaInner(innerNeedle) {
  let selectorStart = 0;
  let depth = 0;
  let blockStart = -1;
  for (let i = 0; i < REAL_CSS.length; i += 1) {
    if (REAL_CSS[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (REAL_CSS[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        const selector = REAL_CSS.slice(selectorStart, blockStart).trim();
        const inner = REAL_CSS.slice(blockStart + 1, i);
        if (selector.startsWith('@media') && inner.includes(innerNeedle)) return inner;
        selectorStart = i + 1;
      }
    }
  }
  return null;
}

/**
 * `var(--wf-gutter)` substituted for its own real value (18px, `.wf-shell`'s own declaration) —
 * NOT a simplification of what is under test. jsdom's `cssstyle` cannot parse a `var()` token
 * inside a shorthand `padding` value at all (verified directly: `padding: 14px var(--x) 20px`
 * computes to `0` on every side, as if the whole declaration were invalid) — so injecting the
 * real rules verbatim would make EVERY competing declaration except the literal `padding: 0`
 * silently drop out, and the "compound selector wins" assertion below would pass whether or not
 * specificity had anything to do with it. Substituting the resolved literal keeps the exact
 * selectors, the exact specificity, and the exact source order under test — the only thing this
 * changes is making the shorthand parseable at all, which is what lets jsdom run the cascade
 * contest instead of silently skipping every side of it.
 */
function withLiteralGutter(css) {
  return css.replaceAll('var(--wf-gutter)', '18px');
}

let styleEl;
beforeAll(() => {
  const desktopRule = withLiteralGutter(sliceExactTopLevelRule('.wf-body'));
  const compoundRule = withLiteralGutter(sliceExactTopLevelRule('.wf-body.wf-body--map'));
  const phoneInner = extractMediaInner('.wf-body {');
  expect(phoneInner, 'no @media (max-width) block containing .wf-body found in index.css').toBeTruthy();
  const phoneRule = withLiteralGutter(sliceExactTopLevelRule('.wf-body', phoneInner));

  // Fail loudly rather than silently injecting nothing — a no-match extraction would leave every
  // assertion below passing for the wrong reason (no rule at all means no padding either way).
  expect(desktopRule, 'no top-level .wf-body rule found in index.css').toContain('.wf-body {');
  expect(compoundRule, 'no .wf-body.wf-body--map rule found in index.css').toContain('.wf-body.wf-body--map {');
  expect(compoundRule).toContain('padding: 0');
  expect(phoneRule, 'no .wf-body rule found inside the phone @media block').toContain('.wf-body {');
  expect(phoneRule).toContain('padding: 12px');
  // And the two `.wf-body` declarations must actually differ, or the test proves nothing about
  // which one wins.
  expect(desktopRule).not.toBe(phoneRule);

  // Source order matters here — this is the exact order the real file carries: the plain desktop
  // rule, then the compound map-tab rule, then the phone override (its `@media` wrapper discarded,
  // per the file header — the wrapper's condition is a real-browser-only claim; the specificity
  // contest on the far side of it is what this file proves). Reversing it would silently turn this
  // into a test of source order winning rather than of specificity winning.
  styleEl = document.createElement('style');
  styleEl.textContent = [desktopRule, compoundRule, phoneRule].join('\n');
  document.head.appendChild(styleEl);
});
afterAll(() => styleEl?.remove());

describe('.wf-body.wf-body--map beats the phone media query\'s plain .wf-body (map-tab-v2-plan.md §3 P7, real finding #1)', () => {
  it('resolves padding to 0 even where the phone rule (equal specificity, later in source) would otherwise win', () => {
    const el = document.createElement('div');
    el.className = 'wf-body wf-body--map';
    document.body.appendChild(el);
    expect(getComputedStyle(el).padding).toBe('0px');
    el.remove();
  });

  it('an element carrying ONLY the plain .wf-body class still gets the phone padding — proving the phone rule is genuinely in the cascade and really does win there, not merely absent', () => {
    const el = document.createElement('div');
    el.className = 'wf-body';
    document.body.appendChild(el);
    // 12px 18px 18px — the phone override, correctly beating the desktop rule by SOURCE ORDER
    // alone (equal specificity, later declaration wins) once the map-tab class is not present to
    // out-specify it. This is the control: it proves the phone rule is a live, winning competitor
    // in this cascade — so the FIRST test's `padding: 0px` is the compound selector's specificity
    // actually beating it, not the phone rule being silently absent either way.
    const { paddingTop, paddingRight, paddingBottom, paddingLeft } = getComputedStyle(el);
    expect(paddingTop).toBe('12px');
    expect(paddingRight).toBe('18px');
    expect(paddingBottom).toBe('18px');
    expect(paddingLeft).toBe('18px');
    el.remove();
  });
});

/**
 * Re-pinned onto the flex chain (adversarial review — the `calc(100dvh - …)` height this file used
 * to assert leaked TWICE, most recently 16px of page scroll surviving with every banner
 * suppressed: an inter-element margin/gap a `ResizeObserver` on element BOXES cannot see. Replaced
 * outright with a flex column `App.jsx` recasts the whole page into on the Map tab — no height is
 * computed anywhere in the chain any more, so this rule's OWN job shrinks to "grow to fill
 * whatever the flex parent hands down, and never more than that". A text assertion on the real
 * rule's source, following this file's own established technique: jsdom's `cssstyle` can resolve
 * `flex`/`min-height`/`display` as bare literals, but there is no live flex LAYOUT for
 * `getComputedStyle` to resolve a real pixel height against in a document with none of the
 * ancestor chain's actual dimensions — the browser-verified claim ("does the map actually fill the
 * screen") stays the orchestrator's, as CLAUDE.md's UI cadence already requires for any CSS claim.
 */
describe('.wf-body.wf-body--map grows via flex, and computes no height of its own', () => {
  it('is a flex:1/min-height:0 flex column, with overflow:hidden and zero padding', () => {
    const compoundRule = sliceExactTopLevelRule('.wf-body.wf-body--map');
    expect(compoundRule).toContain('display: flex');
    expect(compoundRule).toContain('flex-direction: column');
    expect(compoundRule).toContain('flex: 1');
    expect(compoundRule).toContain('min-height: 0');
    expect(compoundRule).toContain('overflow: hidden');
    expect(compoundRule).toContain('padding: 0');
  });

  it('carries no computed height at all — the whole point of the recast', () => {
    const compoundRule = sliceExactTopLevelRule('.wf-body.wf-body--map');
    // Excludes `min-height:`, which the rule legitimately carries — this is checking for a bare
    // `height:` declaration (the deleted `calc(100dvh - …)`), not merely the substring "height:".
    expect(compoundRule).not.toMatch(/[^-]height:/);
    expect(compoundRule).not.toContain('calc(');
    expect(compoundRule).not.toContain('--wf-tabbar-h');
    expect(compoundRule).not.toContain('--wf-banner-h');
  });
});
