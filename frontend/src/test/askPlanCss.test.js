import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The F5 stylesheet block, read as text (jsdom applies no stylesheet: `askCss.test.js` says why). What is
 * pinned is what a screenshot cannot cheaply regress — the mock's measurements and colours, the tokens
 * they map to, and the two facts that make the Plan-card highlight a different mark from the open card.
 * Mock fidelity beyond that is a browser check.
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
// From the opening of the block's own heading comment, so the strip below removes it whole.
const start = css.lastIndexOf('/*', css.indexOf('PLAN THIS, AND THE PLAN-CARD HIGHLIGHT (F5'));
const block = css.slice(start).replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of the first rule in the F5 block whose selector list contains `selector` exactly. */
function ruleBody(selector) {
  const rule = /([^{}@]+)\{([^{}]*)\}/g;
  let m = rule.exec(block);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) return m[2];
    m = rule.exec(block);
  }
  return null;
}

/** Every rule body in the F5 block whose selector list contains `selector` exactly. */
function ruleBodies(selector) {
  const rule = /([^{}@]+)\{([^{}]*)\}/g;
  const bodies = [];
  let m = rule.exec(block);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) bodies.push(m[2]);
    m = rule.exec(block);
  }
  return bodies;
}

describe('the F5 block exists and is the last of the stylesheet’s Ask blocks', () => {
  it('is found, and carries every rule below', () => {
    expect(css).toContain('PLAN THIS, AND THE PLAN-CARD HIGHLIGHT (F5');
    for (const selector of ['.wf-ask-act-plan', '.wf-ask-plan', '.wf-ask-plh-nm', '.wf-ask-figs', '.wf-ask-open', '.wf-hc-ask']) {
      expect(ruleBody(selector), selector).not.toBeNull();
    }
  });
});

describe('"Plan this ›" (the card row’s button)', () => {
  it('has the spec’s gold edge at .5 and the primary ink token (#EBD9A8 is --color-segment-active)', () => {
    const body = ruleBody('.wf-ask-act-plan');

    expect(body).toMatch(/border-color:\s*rgba\(201,\s*162,\s*75,\s*\.5\)/);
    expect(body).toMatch(/color:\s*var\(--color-segment-active\)/);
    expect(css).toMatch(/--color-segment-active:\s*#EBD9A8/i);
  });
});

describe('the plan view, against the mock’s `.plh`, `.figs` and `.acts`', () => {
  it('sets the spot name in Newsreader at 22px / 600 / 1.1', () => {
    const body = ruleBody('.wf-ask-plh-nm');

    expect(body).toMatch(/font-family:\s*var\(--font-serif\)/);
    expect(body).toMatch(/font-size:\s*22px/);
    expect(body).toMatch(/font-weight:\s*600/);
    expect(body).toMatch(/line-height:\s*1\.1/);
    expect(css).toMatch(/--font-serif:\s*'Newsreader'/);
  });

  it('draws the four figures as a 2×2 with one-pixel rules, in the panel and border tokens', () => {
    const grid = ruleBody('.wf-ask-figs');
    const cell = ruleBody('.wf-ask-fig');

    expect(grid).toMatch(/grid-template-columns:\s*1fr 1fr/);
    expect(grid).toMatch(/gap:\s*1px/);
    expect(grid).toMatch(/border-radius:\s*10px/);
    expect(grid).toMatch(/background:\s*var\(--color-plex-border\)/);
    expect(cell).toMatch(/padding:\s*8px 10px/);
    expect(cell).toMatch(/background:\s*var\(--color-plex-panel\)/);
  });

  it('prints the label at 9px mono uppercase and the value at 15px / 600 mono', () => {
    expect(ruleBody('.wf-ask-fig dt')).toMatch(/font-size:\s*9px/);
    expect(ruleBody('.wf-ask-fig dt')).toMatch(/text-transform:\s*uppercase/);
    expect(ruleBody('.wf-ask-fig dd')).toMatch(/font-size:\s*15px/);
    expect(ruleBody('.wf-ask-fig dd')).toMatch(/font-weight:\s*600/);
    expect(ruleBody('.wf-ask-fig dd')).toMatch(/font-family:\s*var\(--font-mono\)/);
  });

  it('gives "Open in Plan ›" the primary style (the removed "Add to Coming up" carried it): 44px, gold wash and edge', () => {
    const body = ruleBody('.wf-ask-open');

    expect(body).toMatch(/min-height:\s*44px/);
    expect(body).toMatch(/border-radius:\s*10px/);
    expect(body).toMatch(/background:\s*rgba\(201,\s*162,\s*75,\s*\.18\)/);
    expect(body).toMatch(/border:\s*1px solid rgba\(201,\s*162,\s*75,\s*\.6\)/);
    expect(body).toMatch(/color:\s*var\(--color-segment-active\)/);
    expect(body).toMatch(/font-weight:\s*600/);
  });
});

describe('the Plan-card highlight is a DIFFERENT mark from the open card', () => {
  // The card is a `div` marked `data-control` (a11y-heat-card-plan.md); `:where()` keeps it (0,3,1), as `button.…` was.
  const highlight = "div.wf-hc.wf-hc:where([data-control])[data-ask-highlight='true']";
  // The forced-colours focus arm: the card's own outline is the ring, via the button's `:focus-visible`.
  const focusedCard = "div.wf-hc.wf-hc:where([data-control]):where(:has(> .wf-hc-hit:focus-visible))[data-ask-highlight='true']";
  const hitRing = "div.wf-hc[data-ask-highlight='true']:where([data-control]) > .wf-hc-hit:focus-visible";

  it('carries the spec’s border (.7) and wash (.08)', () => {
    const body = ruleBody(highlight);

    expect(body).toMatch(/border-color:\s*rgba\(201,\s*162,\s*75,\s*\.7\)/);
    expect(body).toMatch(/linear-gradient\(rgba\(201,\s*162,\s*75,\s*\.08\)/);
  });

  it('adds a second, inset pixel of the same gold — which the open card’s rule does not have', () => {
    expect(ruleBody(highlight)).toMatch(/box-shadow:\s*inset 0 0 0 1px rgba\(201,\s*162,\s*75,\s*\.7\)/);
    // The open card, in the stylesheet above this block: border .62, and NO ring (`box-shadow: none`).
    const openAt = css.indexOf('.wf-hc.on,\ndiv.wf-hc:where([data-control]).on:hover,');
    expect(openAt, 'the open card\'s border rule must be found, or the slice below reads from -1').toBeGreaterThanOrEqual(0);
    const open = css.slice(openAt);
    expect(open.slice(0, 400)).toMatch(/border-color:\s*rgba\(201,\s*162,\s*75,\s*0?\.62\)/);
    expect(open.slice(0, 400)).toMatch(/box-shadow:\s*none/);
  });

  it('keeps a hover arm at the same strength, so a pointer on the card does not take the mark off', () => {
    expect(ruleBody(`${highlight}:hover`)).toMatch(/box-shadow:\s*inset 0 0 0 1px/);
  });

  it('beats the most specific competitor — the doubled class — and so survives a best-and-open card', () => {
    // `div.wf-hc:where([data-control]).best.on:hover` is (0,4,1); `div.wf-hc.wf-hc:where(…)[data-…]:hover` is
    // (0,4,1) and comes LATER in the file, which is what wins the tie. Both must be FOUND: `indexOf` is -1
    // for a missing selector, and `greaterThan(-1)` passes for anything.
    const competitor = css.indexOf('div.wf-hc:where([data-control]).best.on:hover');
    const mark = css.indexOf(highlight);
    expect(competitor).toBeGreaterThanOrEqual(0);
    expect(mark).toBeGreaterThanOrEqual(0);
    expect(mark).toBeGreaterThan(competitor);
  });

  it('draws the rank circle in the pick colour, in the top row’s flow — never hung over the card edge', () => {
    // Two rules name `.wf-hc-ask` (the circle, and the forced-colours edge): read the one that draws it.
    const body = ruleBodies('.wf-hc-ask').find((b) => /background/.test(b));

    expect(body).toMatch(/flex:\s*none/);
    expect(body).toMatch(/border-radius:\s*50%/);
    expect(body).toMatch(/background:\s*var\(--color-home\)/);
    expect(body).toMatch(/color:\s*var\(--color-plex-bg\)/);
    // Measured in a browser: an overhanging circle met the sun word's top edge. Nothing positions it now.
    expect(body).not.toMatch(/position:\s*absolute|top:\s*-/);
  });

  it('survives forced colours, where the box-shadow and the wash are stripped', () => {
    expect(block).toMatch(/@media \(forced-colors: active\)\s*\{\s*div\.wf-hc\.wf-hc:where\(\[data-control\]\)\[data-ask-highlight='true'\]\s*\{[^}]*outline:\s*2px solid Highlight/);
  });

  it('keeps the card’s own focus ring distinguishable from that outline in forced colours', () => {
    // The mark is an inset outline; focus must not take the same one, or focusing the card changes nothing.
    expect(ruleBody(focusedCard)).toMatch(/outline-offset:\s*2px/);
  });

  it('draws ONE ring when that card is focused in forced colours: the hit button\'s own is off, and `none` rather than `transparent`', () => {
    // A transparent outline is repainted in a system colour by forced colours, which is the double ring.
    expect(ruleBody(hitRing)).toMatch(/outline:\s*none/);
  });

  it('gives the 2×2 cells and the rank circles an edge of their own where forced colours strip the fill', () => {
    expect(ruleBody('.wf-ask-fig')).toMatch(/outline|border|gap/);
    const forced = block.slice(block.lastIndexOf('@media (forced-colors: active)'));
    expect(forced).toMatch(/\.wf-ask-fig\s*\{[^}]*outline:\s*1px solid CanvasText/);
    expect(forced).toMatch(/\.wf-hc-ask\s*\{[^}]*border:\s*1px solid CanvasText/);
  });
});
