import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * "The days ahead" lanes, and the slim empty slots and best-location name that ship with them, asserted as TEXT —
 * {@code vite.config.js} sets {@code css: false} and jsdom has no layout engine, so nothing here can measure a box
 * ({@code heatTokens.test.js} reads {@code index.css} the same way). What is pinned is the part a later edit could
 * quietly break (days-ahead-lanes-plan.md):
 *
 * <ul>
 *   <li>the lanes grid is a NON-sticky row of the same grid as the card rows — never inside the sticky, measured
 *       {@code .wf-dhrow} — and never takes the {@code grid-column: var(--c)} rule, because on a lane {@code --c}
 *       is the colour;</li>
 *   <li>the fade lives on a {@code ::before} layer, so it can never mask the text or the focus ring;</li>
 *   <li>the phone rules live in {@code @media (max-width: 639px)} only (CLAUDE.md "phone-only work stays
 *       phone-only"), and the empty-slot rules are scoped to the desktop card rows;</li>
 *   <li>forced colours keep a real edge, a swatch border and a focus outline.</li>
 * </ul>
 *
 * <p>The geometry itself (lanes aligned with the date chips, no overflow, "Not forecast" not spilling) is a browser
 * measurement.
 */
// The path goes through a parameter, never a literal: Vite statically rewrites
// `new URL('<literal>', import.meta.url)` into an asset reference, which is not a file URL.
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');
const css = read('../index.css');

/** The text of the first rule with exactly this selector (the one before the next `}`). */
const ruleBody = (selector) => {
  const start = css.indexOf(`\n${selector} {`);
  if (start === -1) throw new Error(`${selector} is not declared in index.css`);
  return css.slice(start, css.indexOf('}', start));
};

/** The body of the first `@media` block whose query is exactly this, brace-matched. */
const mediaBlock = (query, from = 0) => {
  const start = css.indexOf(`@media ${query} {`, from);
  if (start === -1) throw new Error(`@media ${query} not found after ${from}`);
  let depth = 0;
  for (let i = css.indexOf('{', start); i < css.length; i += 1) {
    if (css[i] === '{') depth += 1;
    if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) return css.slice(start, i + 1);
    }
  }
  throw new Error('unbalanced');
};

const lanesStart = css.indexOf('/* "The days ahead" lanes (days-ahead-lanes-plan.md');
const lanesEnd = css.indexOf('/* An empty cell says why it is empty', lanesStart);
const lanesCss = css.slice(lanesStart, lanesEnd);
const phone = mediaBlock('(max-width: 639px)', lanesStart);
const forced = mediaBlock('(forced-colors: active)', lanesStart);
const outsidePhone = lanesCss.replace(phone, '').replace(/\/\*[\s\S]*?\*\//g, '');

describe('the days-ahead lanes stylesheet', () => {
  it('lays the lanes out in the same grid as the day and card rows, never sticky, never on the --c column rule', () => {
    const grid = css.indexOf(
      '.wf-hstrip.wf-hstrip-rows .wf-dhrow,\n.wf-hstrip.wf-hstrip-rows .wf-lanes,\n.wf-hstrip.wf-hstrip-rows .wf-hcards {',
    );
    expect(grid, 'the rows-mode grid rule must name .wf-lanes').toBeGreaterThan(-1);
    const body = css.slice(grid, css.indexOf('\n}', grid));
    expect(body).toContain('display: grid;');
    expect(body).toContain('grid-template-columns: repeat(var(--dc, 4), minmax(0, 1fr));');
    expect(body).toContain('gap: 8px;');
    // The `--c` column rule names the date chips and the cards, not the lanes (on a lane `--c` is the colour).
    expect(css).toContain('.wf-hstrip.wf-hstrip-rows .wf-dhrow > *,\n.wf-hstrip.wf-hstrip-rows .wf-hcards > * {');
    expect(css).not.toContain('.wf-lanes > *');
    expect(ruleBody('.wf-hstrip.wf-hstrip-rows .wf-lanes')).not.toMatch(/position:\s*sticky/);
    expect(ruleBody('.wf-hstrip.wf-hstrip-rows .wf-lanes')).not.toContain('z-index');
  });

  it('spaces the lanes 5px apart and 3px under the flex gap, and packs rows by the model not by `dense`', () => {
    const body = ruleBody('.wf-hstrip.wf-hstrip-rows .wf-lanes');
    expect(body).toContain('row-gap: 5px;');
    expect(body).toContain('margin-top: 3px;');
    expect(outsidePhone).not.toContain('grid-auto-flow');
  });

  it('draws a lane as a 28px flex row with the handoff\'s padding, radius and wrapping', () => {
    const body = ruleBody('.wf-lane');
    for (const decl of [
      'display: flex;', 'align-items: center;', 'gap: 8px;', 'flex-wrap: wrap;', 'row-gap: 0;',
      'min-height: 28px;', 'padding: 4px 10px;', 'border-radius: 6px;', 'line-height: 1.35;',
    ]) {
      expect(body).toContain(decl);
    }
    expect(body).toContain('isolation: isolate;');
  });

  it('puts the fill, border and fade on a ::before layer, so the text and the focus ring are never masked', () => {
    expect(ruleBody('.wf-lane').replace(/\/\*[\s\S]*?\*\//g, '')).not.toContain('mask');
    expect(ruleBody('.wf-lane')).not.toContain('border: 1px');
    const layer = ruleBody('.wf-lane::before');
    expect(layer).toContain('position: absolute;');
    expect(layer).toContain('inset: 0;');
    expect(layer).toContain('z-index: -1;');
    expect(layer).toContain('border: 1px solid var(--color-plex-border-light);');
    expect(layer).toContain('background: #1f1a17;');
    const fade = ruleBody(".wf-lane[data-ends-after='true']::before");
    expect(fade).toContain('border-right-color: transparent;');
    expect(fade).toContain('mask-image: linear-gradient(90deg, #000 calc(100% - 36px), rgba(0, 0, 0, 0.25));');
    expect(fade).toContain('border-top-right-radius: 0;');
  });

  it('gives a multi-day lane the topic colour as the handoff\'s 14% to 5% fade, and the aurora its own ground', () => {
    const multi = ruleBody(".wf-lane[data-span='multi']::before");
    expect(multi).toContain('color-mix(in srgb, var(--c) 14%, transparent)');
    expect(multi).toContain('color-mix(in srgb, var(--c) 5%, transparent)');
    const aurora = ruleBody(".wf-lane[data-topic='AURORA']::before");
    expect(aurora).toContain('background: #241f33;');
    expect(aurora).toContain('border-color: #4a3f6e;');
  });

  it('marks a lane that began earlier with a 2px dotted secondary-ink left edge and square left corners', () => {
    const body = ruleBody(".wf-lane[data-starts-before='true']::before");
    expect(body).toContain('border-left: 2px dotted var(--color-plex-text-secondary);');
    expect(body).toContain('border-top-left-radius: 0;');
    expect(body).toContain('border-bottom-left-radius: 0;');
  });

  it('places a lane from its own --ls / --le / --lr custom properties, in the desktop rows mode only', () => {
    const body = ruleBody('.wf-hstrip.wf-hstrip-rows .wf-lanes > .wf-lane-item');
    expect(body).toContain('grid-column: var(--ls) / var(--le);');
    expect(body).toContain('grid-row: var(--lr);');
    expect(outsidePhone).not.toContain('.wf-lanecell .wf-lane-item');
  });

  it('keeps the hover and focus border on the muted ink and the focus ring gold, on the button itself', () => {
    expect(ruleBody('.wf-lane:hover::before,\n.wf-lane:focus-visible::before'))
      .toContain('border-color: var(--color-plex-text-muted);');
    const ring = ruleBody('.wf-lane:focus-visible');
    expect(ring).toContain('outline: 2px solid var(--color-plex-gold);');
    // The hover rule comes BEFORE the edge rules, so it cannot repaint the ends-after lane's transparent right edge.
    expect(css.indexOf('.wf-lane:hover::before,'))
      .toBeLessThan(css.indexOf(".wf-lane[data-ends-after='true']::before {"));
  });

  it('sets the name, the PEAK tag and the detail in the handoff\'s type, the detail never truncated', () => {
    const name = ruleBody('.wf-lane-name');
    expect(name).toContain('font-family: var(--font-sans);');
    expect(name).toContain('font-size: 12.5px;');
    expect(name).toContain('font-weight: 600;');
    const val = ruleBody('.wf-lane-val');
    expect(val).toContain('font-family: var(--font-mono);');
    expect(val).toContain('font-size: 10.5px;');
    expect(val).toContain('font-weight: 400;');
    expect(val).toContain('color: var(--color-plex-text-secondary);');
    expect(val).not.toContain('nowrap');
    expect(val).not.toContain('ellipsis');
    expect(ruleBody('.wf-lane-sw')).toContain('width: 8px;');
    expect(ruleBody('.wf-lane-sw')).toContain('height: 8px;');
    expect(ruleBody('.wf-lane-sw')).toContain('border-radius: 2px;');
    expect(ruleBody('.wf-lane-sw')).toContain('background: var(--c);');
    expect(ruleBody('.wf-lane-peak')).toContain('text-transform: uppercase;');
  });

  it('gives a focused lane the cards\' scroll margin so the sticky date row never covers it', () => {
    expect(ruleBody('.wf-hstrip.wf-hstrip-rows .wf-lane')).toContain('scroll-margin-top: calc(');
  });

  it('keeps the phone rules inside the phone media block and nothing else', () => {
    expect(phone).toContain('.wf-hstrip > .wf-lanecell { grid-column: 1 / -1; }');
    expect(phone).toContain('.wf-lanecell .wf-lanes {');
    expect(outsidePhone).not.toContain('.wf-lanecell { grid-column');
    expect(outsidePhone).not.toContain('wf-lanecell');
  });

  it('keeps forced colours: a real edge, the dotted and faded edges stated, a swatch border, a focus outline', () => {
    expect(forced).toContain('.wf-lane::before { border-color: CanvasText; }');
    expect(forced).toContain(".wf-lane[data-starts-before='true']::before { border-left-color: CanvasText; }");
    expect(forced).toContain(".wf-lane[data-ends-after='true']::before { border-right-color: CanvasText; }");
    expect(forced).toContain('.wf-lane-sw { border: 1px solid CanvasText; }');
    expect(forced).toContain('.wf-lane:focus-visible { outline: 2px solid Highlight; }');
  });

  it('no longer carries the pills, the WX box, the key or the door', () => {
    expect(css).not.toMatch(/\.wf-dt(row|cell)?\b/);
    expect(css).not.toMatch(/\.wf-dt-/);
  });
});

describe('the slim empty slots', () => {
  it('slims the gap cell in the desktop card rows only, keeping the dashed border and the secondary ink', () => {
    const body = ruleBody('.wf-hstrip-rows .wf-hcards > .wf-hgap');
    expect(body).toContain('min-height: 0;');
    expect(body).toContain('align-self: start;');
    expect(body).toContain('padding: 12px 10px;');
    expect(ruleBody('.wf-hstrip-rows .wf-hcards > .wf-hgap span')).toContain('font-size: 11px;');
    // The base rule keeps the dashed border and the secondary ink (muted fails AA).
    const base = ruleBody('.wf-hgap');
    expect(base).toContain('border: 1px dashed var(--color-plex-border);');
    expect(ruleBody('.wf-hgap span')).toContain('color: var(--color-plex-text-secondary);');
  });

  it('drops the empty slot\'s 110px minimum on the phone only, so it takes its partner card\'s height', () => {
    // The phone's day grid is the `.wf-hstrip` that holds "The phone transpose"; find that block, not the first one.
    const transpose = css.indexOf('The phone transpose');
    const block = mediaBlock('(max-width: 639px)', css.lastIndexOf('@media', transpose));
    expect(block).toContain('The phone transpose');
    expect(block).toContain('.wf-hstrip > .wf-hgap { min-height: 0; }');
    // Stretch is the point: the slot must not be top-aligned on the phone.
    expect(block.match(/\.wf-hstrip > \.wf-hgap \{[^}]*\}/)[0]).not.toContain('align-self');
    // Nowhere outside that block (desktop and tablet keep the base minimum).
    expect(css.replace(block, '')).not.toContain('.wf-hstrip > .wf-hgap {');
    // The base rule still carries the 110px minimum the phone rule overrides.
    expect(ruleBody('.wf-hgap')).toContain('min-height: 110px;');
  });

  it('slims the away card in the desktop card rows only and hides its sun chip only there', () => {
    const body = ruleBody('.wf-hstrip-rows .wf-hcards > .wf-hc-away');
    expect(body).toContain('align-self: start;');
    expect(body).toContain('min-height: 0;');
    expect(body).toContain('padding: 9px 10px;');
    expect(ruleBody('.wf-hstrip-rows .wf-hcards > .wf-hc-away .wf-hc-top')).toContain('display: none;');
    expect(ruleBody('.wf-hstrip-rows .wf-hcards > .wf-hc-away .wf-hc-pls')).toContain('column-gap: 8px;');
    // The phone has no rails and needs the chip: the base `.wf-hc-top` rule must not hide it.
    expect(ruleBody('.wf-hc-top')).not.toContain('display: none');
  });

  it('stretches the away card to the row only when a rated card sits beside it, on desktop and tablet', () => {
    const selector = '.wf-hstrip-rows .wf-hcards:has(> .wf-hc:not(.wf-hc-away)) > .wf-hc-away';
    expect(ruleBody(selector)).toContain('align-self: stretch;');
    // The all-away row keeps the slim rule.
    expect(ruleBody('.wf-hstrip-rows .wf-hcards > .wf-hc-away')).toContain('align-self: start;');
    // Desktop / tablet only: it is declared in no phone block.
    let from = 0;
    while (css.indexOf('@media (max-width: 639px) {', from) !== -1) {
      const block = mediaBlock('(max-width: 639px)', from);
      expect(block).not.toContain(selector);
      from = css.indexOf(block, from) + block.length;
    }
  });
});

describe('the best-location name', () => {
  it('is mono 10.5px, 400, secondary ink', () => {
    const body = ruleBody('.wf-hc-best');
    expect(body).toContain('font-family: var(--font-mono);');
    expect(body).toContain('font-size: 10.5px;');
    expect(body).toContain('font-weight: 400;');
    expect(body).toContain('color: var(--color-plex-text-secondary);');
  });

  it('keeps `.none` and `.beyond` on their own rules', () => {
    expect(ruleBody('.wf-hc-best.none')).toContain('color: var(--color-plex-text-secondary);');
    expect(ruleBody('.wf-hc-best.beyond')).toContain('font-size: 10px;');
  });
});
