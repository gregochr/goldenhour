import React from 'react';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import BriefingSummaryStrip from '../components/BriefingSummaryStrip.jsx';

/**
 * Which rule wins the cascade on a region chip — asserted against the REAL `index.css`.
 *
 * Every other test in this suite runs with `css: false` (`vite.config.js`), so jsdom loads no
 * stylesheet and `getComputedStyle` answers from inline styles alone. That is why the chip's
 * resting colour has never been testable: it is set entirely in CSS, and the defect this file
 * pins — a Best-bet chip painted `--color-verdict-go` at rest on an amber `maybe` tile, because
 * `.summary-region-chip[data-pick="best"]` is keyed to `data-pick` alone — was invisible to a
 * green suite.
 *
 * <p><b>Scope, since P2:</b> the v2 half of this file went with the day rail (plan D1). The rules it
 * pinned — `.summary-region-chip.rail-region-chip[data-peak=…]` — existed only for that component
 * and are deleted from `index.css`; what remains is the v1 arm, which is the frozen comparison
 * control and must keep resolving to the pick hues below.
 *
 * <h2>What this file can and cannot prove</h2>
 *
 * jsdom resolves SPECIFICITY correctly but does not resolve `var()` — it returns the declaration's
 * raw text. So these tests prove **which declaration won**, which is exactly the defect, and prove
 * nothing about what the token evaluates to. A token pruned to the empty string would pass here and
 * has bitten this project before (P4c). That half is a browser claim, verified by measurement in
 * the commit that added this file, and it is the reason this file does not assert hexes it cannot
 * see.
 *
 * <h2>Why the stylesheet is sliced rather than injected whole</h2>
 *
 * `index.css` opens with `@import "tailwindcss"` and `@theme`, neither of which jsdom's parser
 * understands; injecting the file entire drops rules silently and yields a test that cannot fail —
 * worse than none, because the next reader trusts it. So the chip rules are extracted from the real
 * file and {@link assertSliceIsIntact} fails loudly if the extraction stops matching. A no-match
 * that quietly injects nothing is the failure mode this guard exists for.
 */

// Resolved from the Vitest root (`frontend/`) rather than from `import.meta.url`, which the
// transform does not leave as a `file:` URL. `existsSync` is the guard that keeps this honest: a
// run from some other cwd must fail here, not fall through to an empty slice.
const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/**
 * Every rule in `index.css` whose selector mentions `summary-region-chip`, in source order.
 *
 * Comments are stripped first (they contain both braces and the class name, in the very block being
 * extracted). The scan is brace-depth aware so a rule nested in an `@media`/`@theme` block cannot be
 * silently re-associated with the wrong selector — the chip rules are flat today, and this stops
 * that being an assumption the extractor depends on.
 *
 * @returns {string} the concatenated rules, ready to inject
 */
function extractChipRules() {
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
        if (selector.includes('summary-region-chip')) {
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
 * Both sides of the comparison have to be present for any assertion below to mean anything: the
 * pick rule is what the v2 rule must out-specify, and the v2 rule is what must win. An extraction
 * that silently returned "" would otherwise leave every chip at its inherited colour and pass the
 * v2 assertions for the wrong reason.
 *
 * @param {string} slice extracted rule text
 */
function assertSliceIsIntact(slice) {
  expect(slice, 'no .summary-region-chip rules were extracted from index.css').not.toBe('');
  expect(slice).toContain('.summary-region-chip[data-pick="best"]');
  expect(slice).toContain('.summary-region-chip[data-pick="also"]');
  // ⚠️ The `.rail-region-chip` opt-in rules used to be named here as well, and they are gone with
  // the day rail (P2 of the heat-field plan, D1). They existed only for the v2 rail — a scanning
  // surface where a chip's own pick hue read as its tile's verdict — and v2 now has no region chip
  // at all until P3's region rail. What survives is the v1 side, which was always the frozen
  // control: `BriefingSummaryStrip` never carried the opt-in, so these two rules are exactly what
  // it has always resolved to. If a v2 chip returns, it must earn its own opt-in and its own tests
  // rather than inheriting these.
}

let styleEl;
beforeAll(() => {
  const slice = extractChipRules();
  assertSliceIsIntact(slice);
  styleEl = document.createElement('style');
  styleEl.textContent = slice;
  document.head.appendChild(styleEl);
});
afterAll(() => styleEl?.remove());

/** A summary pill carrying one plain chip and one picked chip, both on the same verdict. */
function pillFor(peak, pickKind) {
  return {
    date: '2026-08-04',
    targetType: 'SUNSET',
    dow: 'Tue',
    dayNum: '4',
    dayLabel: 'Today',
    sunriseTime: '05:15',
    sunsetTime: '21:11',
    peak,
    peakLabel: 'Worth it · sunset',
    pick: null,
    regions: [
      { regionName: 'Picked', shortName: 'Picked', targetType: 'SUNSET', pickKind },
      { regionName: 'Plain', shortName: 'Plain', targetType: 'SUNSET', pickKind: null },
    ],
    ratedCount: 2,
    isAway: false,
  };
}

/** The v1 pill, as `BriefingSummaryStrip` consumes it. */
function v1Pill(peak, pickKind) {
  return { ...pillFor(peak, pickKind), subLabel: null, countLabel: null, confidence: null };
}

const GO = 'var(--color-verdict-go)';
const ALSO = 'var(--color-pick-also)';

// The dotted underline, which stays with the PICK while the verdict takes the text colour. These
// are literal rgba in the source, so unlike the `color` assertions they pin the actual hue rather
// than which declaration won — a strictly stronger check, and the reason the underline is asserted
// everywhere the text colour is. (A PLAIN chip's underline is deliberately not asserted: it comes
// from the `border-bottom` SHORTHAND on `.summary-region-chip`, whose `var()` jsdom cannot resolve,
// so it reads back as `rgba(0, 0, 0, 0)` here and is a browser claim only.)
const GO_UNDERLINE = 'rgba(138, 174, 114, 0.6)';
const ALSO_UNDERLINE = 'rgba(124, 141, 214, 0.6)';

describe('region chip resting colour — v1 strip is the frozen control', () => {
  it('still paints a Best chip green on a maybe pill', () => {
    // v1 is the pilot's frozen comparison control, and this is the assertion that keeps it frozen:
    // the pick hue is keyed to `data-pick` ALONE, which is the shape the v2 rail's own opt-in rules
    // existed to override. Those rules went with the rail (P2, D1); this one must not move with
    // them, because a v1 chip that changed colour would change the thing the pilot measures.
    render(<BriefingSummaryStrip pills={[v1Pill('maybe', 'best')]} />);
    const [picked] = screen.getAllByTestId('summary-region-chip');

    expect(getComputedStyle(picked).color).toBe(GO);
    // Both channels, so a shared-selector edit that moved only v1's underline is caught in the
    // same place its text colour is.
    expect(getComputedStyle(picked).borderBottomColor).toBe(GO_UNDERLINE);
  });

  it('still paints an Also chip periwinkle on a maybe pill', () => {
    render(<BriefingSummaryStrip pills={[v1Pill('maybe', 'also')]} />);
    const [picked] = screen.getAllByTestId('summary-region-chip');

    expect(getComputedStyle(picked).color).toBe(ALSO);
    expect(getComputedStyle(picked).borderBottomColor).toBe(ALSO_UNDERLINE);
  });

  it('never emits a v2 opt-in class, on any peak', () => {
    // Kept after the rail's retirement rather than deleted with it: the class is the mechanism any
    // future v2 chip would use to escape these rules, and a v1 chip that started carrying one would
    // silently take whatever the new arm's rules say. Stated over every peak because a
    // single-fixture check could not distinguish "not added" from "not added on this one tile".
    ['go', 'maybe', 'poor'].forEach((peak) => {
      const { unmount } = render(<BriefingSummaryStrip pills={[v1Pill(peak, 'best')]} />);
      screen.getAllByTestId('summary-region-chip').forEach((chip) => {
        expect(chip.className).not.toContain('rail-region-chip');
      });
      unmount();
    });
  });

  it('still renders the unbranched ◎ for both Best and Also — the shape fix is v2-only', () => {
    // Pins the deliberate scope boundary: v1 is the frozen comparison control (see
    // shared-component-blast-radius), so the WCAG 1.4.1 colour-only gap between a Best and an Also
    // chip is NOT fixed here. This is documented, accepted debt, not an oversight — the ◎/● shape
    // fix lived entirely in the v2 rail's own `rn-mark`, a render path v1 never shared, and went
    // with that component at P2.
    const { unmount: unmountBest } = render(<BriefingSummaryStrip pills={[v1Pill('maybe', 'best')]} />);
    expect(screen.getAllByTestId('summary-region-chip')[0]).toHaveTextContent('◎');
    unmountBest();

    render(<BriefingSummaryStrip pills={[v1Pill('maybe', 'also')]} />);
    const alsoChip = screen.getAllByTestId('summary-region-chip')[0];
    expect(alsoChip).toHaveTextContent('◎');
    expect(alsoChip).not.toHaveTextContent('●');
  });
});
