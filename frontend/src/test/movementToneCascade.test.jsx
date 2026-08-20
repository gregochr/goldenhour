import React from 'react';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import WindowRegionBand from '../components/WindowRegionBand.jsx';

/**
 * Which declaration wins on a movement mark — asserted against the REAL `index.css`.
 *
 * <p>Every other test in this suite runs with `css: false` (`vite.config.js`), so jsdom loads no
 * stylesheet and `getComputedStyle` answers from inline styles alone. The whole movement channel's
 * direction signal is a CSS rule keyed on `data-tone`, so without this file the attribute is pinned
 * (three component tests do that) and what it *resolves to* is not — and deleting
 * `.wf-rband-fig b[data-tone="down"]` would paint a falling region in plain ink, or worse, leave
 * `.wf-rband-fig b`'s `--color-plex-text` winning, with a green suite. This project has shipped
 * exactly that class of defect twice (P2, P4c).
 *
 * <h2>What this file can and cannot prove</h2>
 *
 * <p>The technique and its limits are `regionChipVerdictColour.test.jsx`'s, unchanged: jsdom
 * resolves SPECIFICITY but does not resolve `var()`, so these assertions prove which declaration
 * won and prove nothing about what the token evaluates to. A token pruned to the empty string would
 * pass here. That half is a browser claim — and for these two it is a settled one, since
 * `--color-badge-go` and `--color-badge-poor` live in `@theme static`, which Tailwind v4 does not
 * prune.
 *
 * <p>The tone rules are a genuine specificity contest and not a formality: `.wf-rband-fig b`
 * (0,1,1) sets `color: var(--color-plex-text)` and the tone rules (0,2,1) must beat it. Get the
 * order or the specificity wrong and every movement figure in the band renders as ordinary ink.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/**
 * Every rule in `index.css` whose selector mentions a movement surface, in source order.
 *
 * <p>Comments are stripped first — they contain both braces and the class names, in the very blocks
 * being extracted — and the scan is brace-depth aware so a rule inside an `@media` block cannot be
 * silently re-associated with the wrong selector.
 *
 * @returns {string} the concatenated rules, ready to inject
 */
function extractMovementRules() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
  const css = readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const rules = [];
  let selectorStart = 0;
  let depth = 0;
  let blockStart = -1;
  for (let i = 0; i < css.length; i += 1) {
    if (css[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        const selector = css.slice(selectorStart, blockStart).trim();
        // ⚠️ `.wf-hc-mv` used to be a third arm here. M1 deleted the per-card movement chip along
        // with the whole class, so the arm matched nothing — and the guard below could not tell,
        // because it checks only the region band's rules and the two tone attributes, which the
        // change line still supplies. A dead clause in a slicer whose entire job is to fail loudly
        // on an empty slice is exactly the thing that file exists not to have.
        if (/\.wf-rband-fig\b/.test(selector) || /\.wf-hstrip-change\b/.test(selector)) {
          rules.push(`${selector} ${css.slice(blockStart, i + 1)}`);
        }
        selectorStart = i + 1;
      }
    }
  }
  return rules.join('\n');
}

/**
 * Fail loudly if the slice no longer contains the rules under test.
 *
 * <p>Both sides have to be present for any assertion to mean anything: the base rule is what the
 * tone rules must out-specify. A no-match extraction that quietly injected "" would leave every
 * mark at its inherited colour and pass the "not plain ink" assertions for the wrong reason —
 * which is the whole failure mode this guard exists for.
 *
 * @param {string} slice extracted rule text
 */
function assertSliceIsIntact(slice) {
  expect(slice, 'no movement rules were extracted from index.css').not.toBe('');
  expect(slice).toContain('.wf-rband-fig b');
  expect(slice).toContain('[data-tone="up"]');
  expect(slice).toContain('[data-tone="down"]');
  // Named per surface, so an extractor arm that stops matching cannot hide behind a sibling's
  // rules — which is how the deleted `.wf-hc-mv` arm went unnoticed.
  expect(slice).toContain('.wf-hstrip-change b');
}

let styleEl;
beforeAll(() => {
  const slice = extractMovementRules();
  assertSliceIsIntact(slice);
  styleEl = document.createElement('style');
  styleEl.textContent = slice;
  document.head.appendChild(styleEl);
});
afterAll(() => styleEl?.remove());

const GO = 'var(--color-badge-go)';
const POOR = 'var(--color-badge-poor)';
const PLAIN = 'var(--color-plex-text)';

function renderBandWith(meanRatingDelta) {
  render(
    <WindowRegionBand
      row={{
        name: 'Northumberland & Tyneside',
        verdict: 'WORTH_IT',
        summary: 'A clean eastern horizon.',
        bestRating: 5,
        meanRatingDelta,
      }}
      windowKey="2026-08-04:SUNSET"
      windows={[]}
      series={new Map()}
      filters={[]}
      atFloor={null}
      withinTier={null}
    />,
  );
  return screen.getByTestId('wf-region-band-mark');
}

describe('the movement mark takes its direction\'s hue, and beats the base rule to do it', () => {
  it('paints a rise with the lifted go token', () => {
    expect(getComputedStyle(renderBandWith(0.6)).color).toBe(GO);
  });

  it('paints a fall with the lifted poor token', () => {
    // The specificity contest that matters: `.wf-rband-fig b` sets plain ink and is earlier in the
    // file, so a tone rule that lost would leave a falling region indistinguishable from a rising
    // one — colour being the only channel the glyph does not already carry.
    expect(getComputedStyle(renderBandWith(-0.3)).color).toBe(POOR);
  });

  it('leaves a MEASURED zero in plain ink, borrowing neither direction', () => {
    // "Did not move" is the absence of a direction. Tinting it either way would assert one.
    expect(getComputedStyle(renderBandWith(0)).color).toBe(PLAIN);
  });
});
