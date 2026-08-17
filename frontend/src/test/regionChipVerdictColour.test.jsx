import React from 'react';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { render, screen } from '@testing-library/react';
import WindowFirstDayRail from '../components/WindowFirstDayRail.jsx';
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
  expect(slice).toContain('.summary-region-chip.rail-region-chip[data-peak="maybe"]');
  expect(slice).toContain('.summary-region-chip.rail-region-chip[data-peak="go"]');
  // Both halves of the grouped poor/away selector, named separately. They share one rule, so
  // deleting either half alone leaves the other matching — and `away` is exercised by only one
  // test below, which is not enough to notice its half going missing.
  expect(slice).toContain('.summary-region-chip.rail-region-chip[data-peak="poor"]');
  expect(slice).toContain('.summary-region-chip.rail-region-chip[data-peak="away"]');
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

/** A v2 rail tile carrying one plain chip and one Best-bet chip, so both are on the same verdict. */
function railTile(peak, pickKind) {
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

/** The v1 pill equivalent — same regions, same peak, so any divergence is the arm and not the data. */
function v1Pill(peak, pickKind) {
  return { ...railTile(peak, pickKind), subLabel: null, countLabel: null, confidence: null };
}

const GO = 'var(--color-verdict-go)';
const MARGINAL = 'var(--color-verdict-marginal)';
const ALSO = 'var(--color-pick-also)';

// The dotted underline, which stays with the PICK while the verdict takes the text colour. These
// are literal rgba in the source, so unlike the `color` assertions they pin the actual hue rather
// than which declaration won — a strictly stronger check, and the reason the underline is asserted
// everywhere the text colour is. (A PLAIN chip's underline is deliberately not asserted: it comes
// from the `border-bottom` SHORTHAND on `.summary-region-chip`, whose `var()` jsdom cannot resolve,
// so it reads back as `rgba(0, 0, 0, 0)` here and is a browser claim only.)
const GO_UNDERLINE = 'rgba(138, 174, 114, 0.6)';
const ALSO_UNDERLINE = 'rgba(124, 141, 214, 0.6)';

describe('region chip resting colour — v2 rail', () => {
  it('paints BOTH the picked and the plain chip amber on a maybe tile', () => {
    // The regression, stated exactly: before the v2 rules the picked chip resolved to
    // `var(--color-verdict-go)` here — green, 4px under an amber "Worth it · sunset" verdict line,
    // one tile asserting two verdicts in the only channel that carries meaning.
    render(<WindowFirstDayRail tiles={[railTile('maybe', 'best')]} />);
    const [picked, plain] = screen.getAllByTestId('rail-region-chip');

    expect(getComputedStyle(picked).color).toBe(MARGINAL);
    expect(getComputedStyle(plain).color).toBe(MARGINAL);
    // The underline stays with the pick — see the best-vs-also test below for why that matters.
    expect(getComputedStyle(picked).borderBottomColor).toBe(GO_UNDERLINE);
  });

  it('paints BOTH chips green on a go tile', () => {
    render(<WindowFirstDayRail tiles={[railTile('go', 'best')]} />);
    const [picked, plain] = screen.getAllByTestId('rail-region-chip');

    expect(getComputedStyle(picked).color).toBe(GO);
    expect(getComputedStyle(plain).color).toBe(GO);
    expect(getComputedStyle(picked).borderBottomColor).toBe(GO_UNDERLINE);
  });

  it('gives an Also pick the tile verdict too, not the periwinkle it carries in v1', () => {
    // `also` is a separate rule from `best` in the pick block, so displacing one says nothing
    // about the other. On a maybe tile the v1 hue would read as a third verdict colour entirely.
    render(<WindowFirstDayRail tiles={[railTile('maybe', 'also')]} />);
    const [picked] = screen.getAllByTestId('rail-region-chip');

    expect(getComputedStyle(picked).color).toBe(MARGINAL);
    expect(getComputedStyle(picked).color).not.toBe(ALSO);
    expect(getComputedStyle(picked).borderBottomColor).toBe(ALSO_UNDERLINE);
  });

  it('still tells a Best pick from an Also pick on the SAME tile', () => {
    // The defect this guards was introduced by the first cut of these rules and caught in review:
    // they set `border-bottom-color` as well as `color`, displacing BOTH pick declarations. Since
    // the ◎ is one unbranched glyph and `font-weight: 600` is identical in both pick rules, colour
    // was the ONLY channel separating the two kinds — so the two chips rendered pixel-identical
    // apart from the region name.
    //
    // Reachable in ordinary operation, which is why it is a defect and not a nit: `buildRailTiles`
    // stamps `pickKind` on every peak-tier chip on every tile, while the tile's own pick flag shows
    // just ONE pick per day and names an event rather than a region — so nothing else on the tile
    // disambiguates them. Asserted as a DIFFERENCE rather than against two literals, so it keeps
    // holding if the palette moves.
    render(<WindowFirstDayRail tiles={[{
      ...railTile('maybe', 'best'),
      regions: [
        { regionName: 'Best', shortName: 'Best', targetType: 'SUNSET', pickKind: 'best' },
        { regionName: 'Also', shortName: 'Also', targetType: 'SUNSET', pickKind: 'also' },
      ],
    }]} />);
    const [best, also] = screen.getAllByTestId('rail-region-chip');

    // Same verdict — that half of the change must survive.
    expect(getComputedStyle(best).color).toBe(MARGINAL);
    expect(getComputedStyle(also).color).toBe(MARGINAL);
    // …and still distinguishable.
    expect(getComputedStyle(best).borderBottomColor)
      .not.toBe(getComputedStyle(also).borderBottomColor);
  });

  // ⚠️ These two cover a state the CURRENT builder cannot produce, and say so rather than implying
  // otherwise. `windowFirstRail.js` defines `peak` as `entries.length === 0 ? 'poor' : …` and
  // derives `regions` from those same entries, so a poor tile always has `regions: []`; the away
  // branch hard-codes `regions: []` too. `WindowFirstDayRail` renders chips only under
  // `tile.regions?.length`, so no chip can currently carry either value — the builder's own comment
  // says as much ("a poor tile has no chips, no pick flag and no 'show on map' button").
  //
  // They are kept as a FALL-THROUGH GUARD, because the failure they prevent is silent: if a future
  // builder starts emitting chips on such a tile, an unhandled `peak` sends the chip straight back
  // to `[data-pick="best"]`'s green — a poor day wearing the brightest go-signal on the rail. The
  // fixture reaches the state directly because the guard is the thing under test.
  it('leaves a pick on a poor tile un-tinted rather than falling through to a pick hue', () => {
    render(<WindowFirstDayRail tiles={[railTile('poor', 'best')]} />);
    const [picked] = screen.getAllByTestId('rail-region-chip');

    expect(getComputedStyle(picked).color).not.toBe(GO);
    // MARGINAL, not ALSO. `.not.toBe(ALSO)` was unfalsifiable here — the fixture is `pickKind:
    // 'best'`, so `[data-pick="also"]` never matched and that value was never a candidate. The
    // mutation this DOES catch is `color: inherit` → `var(--color-verdict-marginal)`, which the
    // old pair passed.
    expect(getComputedStyle(picked).color).not.toBe(MARGINAL);
  });

  it('does the same on an away tile — the other half of the grouped selector', () => {
    // Its own test because `poor` and `away` share one rule: with only the poor case written, the
    // `[data-peak="away"]` half could be deleted with the suite green.
    render(<WindowFirstDayRail tiles={[railTile('away', 'best')]} />);
    const [picked] = screen.getAllByTestId('rail-region-chip');

    expect(getComputedStyle(picked).color).not.toBe(GO);
    expect(getComputedStyle(picked).color).not.toBe(MARGINAL);
  });

  it('keeps the ◎ mark and the bold weight, so the pick is not carried by colour alone', () => {
    // The whole change gives up the pick's hue. That is only defensible while the two non-colour
    // channels survive, and they come from the v1 pick rules the v2 rules deliberately override
    // for `color` only — if a later edit collapses those rules, this fails.
    render(<WindowFirstDayRail tiles={[railTile('maybe', 'best')]} />);
    const [picked, plain] = screen.getAllByTestId('rail-region-chip');

    expect(picked).toHaveTextContent('◎');
    expect(plain).not.toHaveTextContent('◎');
    expect(getComputedStyle(picked).fontWeight).toBe('600');
    expect(getComputedStyle(plain).fontWeight).not.toBe('600');
  });
});

describe('region chip resting colour — v1 strip is the frozen control', () => {
  it('still paints a Best chip green on a maybe pill', () => {
    // The blast-radius guard. The rules are shared by class, so the ONLY thing keeping v1 still is
    // that its chips do not carry `rail-region-chip`. This fails the moment that stops being true.
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

  it('never emits the v2 opt-in class, on any peak', () => {
    // Stated over every peak because the class is added unconditionally in the rail and would be
    // added unconditionally here too — a single-fixture check could not distinguish "not added"
    // from "not added on this one tile".
    ['go', 'maybe', 'poor'].forEach((peak) => {
      const { unmount } = render(<BriefingSummaryStrip pills={[v1Pill(peak, 'best')]} />);
      screen.getAllByTestId('summary-region-chip').forEach((chip) => {
        expect(chip.className).not.toContain('rail-region-chip');
      });
      unmount();
    });
  });
});

describe('the opt-in itself', () => {
  it('is present on every v2 chip and is what admits the verdict rules', () => {
    render(<WindowFirstDayRail tiles={[railTile('maybe', 'best')]} />);
    const chips = screen.getAllByTestId('rail-region-chip');

    expect(chips).toHaveLength(2);
    chips.forEach((chip) => {
      expect(chip.className).toContain('summary-region-chip');
      expect(chip.className).toContain('rail-region-chip');
      expect(chip).toHaveAttribute('data-peak', 'maybe');
    });
  });

  it('is load-bearing — stripping it returns the chip to the v1 green', () => {
    // Proves the class is the mechanism rather than a decoration that happens to sit beside one.
    // Without this, every v2 assertion above would also pass if the rules were keyed to something
    // else entirely and v1 were quietly following along.
    render(<WindowFirstDayRail tiles={[railTile('maybe', 'best')]} />);
    const [picked] = screen.getAllByTestId('rail-region-chip');
    expect(getComputedStyle(picked).color).toBe(MARGINAL);

    picked.classList.remove('rail-region-chip');

    expect(getComputedStyle(picked).color).toBe(GO);
  });
});
