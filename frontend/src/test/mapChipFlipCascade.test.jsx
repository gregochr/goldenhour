import React from 'react';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll, beforeEach, afterEach, vi,
} from 'vitest';
import {
  act, cleanup, render, screen,
} from '@testing-library/react';
import WindowRowFieldMap from '../components/WindowRowFieldMap.jsx';
import { land, load } from '../utils/heatField.js';

/**
 * Which way a FLIPPED field-map chip lays its content out — asserted against the real `index.css`.
 *
 * <p>The placer flips a chip whose name will not fit to the right of its point, putting the box's
 * RIGHT edge at the anchor — so the 5px marker must move to the right end to stay on the projected
 * point. That move is pure CSS (`.wf-mchip[data-flip='true'] { flex-direction: row-reverse }`),
 * and the rest of the suite runs with `css: false`, so `WindowRowFieldMap.test.jsx` pins the flip
 * DECISION (the attribute, the box's left edge) while nothing pinned what the attribute resolves
 * to. The port shipped exactly that hole: the bundle's `.loc.flip` rule was never carried over,
 * every flipped chip kept its marker at the LEFT end — one chip-width west of the location it
 * names — and an east-coast chip rendered on the Lake District's heat with a green suite.
 *
 * <h2>What this file can and cannot prove</h2>
 *
 * <p>jsdom resolves SPECIFICITY but implements no layout, so these assertions prove which
 * declaration won and nothing about geometry: that the mirrored paddings keep the two states'
 * measured widths identical (which the placer depends on — it measures before it decides to flip)
 * is arithmetic the rule's own symmetry carries, and the marker landing on the projected pixel is
 * a browser claim.
 *
 * <p>⚠️ So is the divider's SIDE. cssstyle drops a shorthand it cannot fully reify, and
 * `border-left: 1px solid var(--color-plex-border)` carries a `var()` — the whole declaration
 * vanishes from the computed style, on both the base rule and the flip rule, so a border-side
 * assertion here reads 'none' for a reason that has nothing to do with the cascade (measured: both
 * sides answered 'none' with the rules demonstrably injected, while the sibling longhands from the
 * same blocks resolved). The paddings are the divider-mirroring claim this file CAN carry, and
 * they come from the same two declarations.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/**
 * Every rule in `index.css` whose selector mentions the chip layer, in source order.
 *
 * <p>Comments are stripped first — they contain both braces and the class names, in the very
 * blocks being extracted — and the scan is brace-depth aware so a rule inside an `@media` block
 * cannot be silently re-associated with the wrong selector. The same slicer, for the same reasons,
 * as `movementToneCascade.test.jsx`.
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
        // One arm on purpose: a hyphen is a word boundary, so `\.wf-mchip\b` already matches the
        // `-m`/`-n`/`-r` children (and correctly excludes `.wf-mchips`, since `s` is a word
        // character). A second `-r` arm could never match anything this one misses — the sibling
        // slicer's history comment records what a dead arm costs.
        if (/\.wf-mchip\b/.test(selector)) {
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
 * <p>Both sides have to be present for the contest to mean anything: the base `.wf-mchip` rule is
 * what the flip rule must out-specify. A no-match extraction that quietly injected "" would leave
 * every chip at jsdom's defaults and pass a "not row-reverse" assertion for the wrong reason.
 *
 * @param {string} slice extracted rule text
 */
function assertSliceIsIntact(slice) {
  expect(slice, 'no chip rules were extracted from index.css').not.toBe('');
  expect(slice).toContain('.wf-mchip {');
  expect(slice).toContain("[data-flip='true']");
  expect(slice).toContain('row-reverse');
  expect(slice).toContain('.wf-mchip-r');
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
afterEach(cleanup);

// The same kernel-boundary stub as `WindowRowFieldMap.test.jsx`, for the same reason: the flip is
// a function of the projection, and the stub's 10× linear map makes every trigger checkable by
// hand — Ladybower (lng 24) anchors at x = 240 on a 400px frame, so a 200px chip cannot fit
// rightward and must flip; Bamburgh (lng 4) anchors at x = 40, where a 50px chip fits and must not.
vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => ([lng, lat]) => [lng * 10, lat * 10]),
  };
});

vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

const TODAY = '2026-08-04';
const KEY = '2026-08-04:SUNSET';

function spot(overrides = {}) {
  return {
    id: 1, name: 'Bamburgh', lat: 6, lng: 4, regionName: 'Coast', rid: 'Coast', scores: [4],
    ...overrides,
  };
}

// Region centroids well clear of the chips' own points, exactly as the placement suite builds
// them — with one spot per region the label and the chip land on the same pixel and the placer
// drops the chip: correctly, and uselessly for a test about the flipped state.
const CHIP_SPOTS = [
  spot({ id: 1, name: 'Bamburgh', lng: 4, lat: 6 }),
  spot({ id: 3, name: 'Craster', lng: 4, lat: 26 }),
  spot({ id: 2, name: 'Ladybower', lng: 24, lat: 6, regionName: 'Dales', rid: 'Dales' }),
  spot({ id: 4, name: 'Malham', lng: 24, lat: 26, regionName: 'Dales', rid: 'Dales' }),
];
const REGIONS = ['Coast', 'Dales'];
const POINTS = [{ lat: 6, lng: 4, rid: 'Coast', r: [4] }];

let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  land.mockImplementation(() => ({ type: 'FeatureCollection', features: [] }));
  load.mockImplementation(() => Promise.resolve({ type: 'FeatureCollection', features: [] }));
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  vi.clearAllMocks();
});

/** Gives every element a measurable content box; jsdom reports 0 for all of them. */
async function withMeasuredMap(px, run) {
  const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
  Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => px });
  try {
    // `return await`, not `return run()` — the bare return hands the promise back and `finally`
    // restores the real descriptor before the render inside has run.
    return await run();
  } finally {
    if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
    else delete Element.prototype.clientWidth;
  }
}

/** Stubs the two box measurements the greedy pass reads, at the size a case needs. */
async function withChipBoxes(width, height, run) {
  const w = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  const h = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => width });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => height });
  try {
    return await run();
  } finally {
    if (w) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', w);
    else delete HTMLElement.prototype.offsetWidth;
    if (h) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', h);
    else delete HTMLElement.prototype.offsetHeight;
  }
}

async function renderMap(props = {}) {
  await act(async () => {
    render(
      <WindowRowFieldMap
        windowKey={KEY}
        date={TODAY}
        confidence="high"
        spots={CHIP_SPOTS}
        points={POINTS}
        bestRating={4}
        regionNames={REGIONS}
        selectedRegion={null}
        todayStr={TODAY}
        onSelectRegion={() => {}}
        {...props}
      />,
    );
  });
}

describe('a flipped chip mirrors its content so the marker stays on the point', () => {
  it('⚠️ resolves to row-reverse — the rule whose absence pointed a Teesside chip at the Lakes', async () => {
    await withMeasuredMap(400, async () => {
      await withChipBoxes(200, 14, async () => {
        await renderMap({ chips: [{ key: '2', locationId: 2, locationName: 'Ladybower', rating: 4 }] });
      });
    });
    const chip = screen.getByTestId('wf-row-map-chip');
    // The precondition, pinned by the placement suite and re-asserted here so a change to the flip
    // trigger cannot turn the cascade assertion below into a test of the unflipped state.
    expect(chip).toHaveAttribute('data-flip', 'true');
    expect(getComputedStyle(chip).flexDirection).toBe('row-reverse');
    // ⚠️ The OTHER half of "marker at the right end": `row-reverse` puts it there only because the
    // marker is the FIRST child. Reorder the chip's children and both the cascade and the placement
    // suite stay green while `CHIP_OFFSET`'s geometry (anchor − 5.5 assumes marker first) points
    // every chip one chip-width wrong — the exact defect class this file exists to prevent.
    expect(chip.firstElementChild).toHaveClass('wf-mchip-m');
  });

  it('mirrors the rating divider with it, keeping the hairline between rating and name', async () => {
    await withMeasuredMap(400, async () => {
      await withChipBoxes(200, 14, async () => {
        await renderMap({ chips: [{ key: '2', locationId: 2, locationName: 'Ladybower', rating: 4 }] });
      });
    });
    const rating = screen.getByTestId('wf-row-map-chip').querySelector('.wf-mchip-r');
    const style = getComputedStyle(rating);
    // Side-for-side, so the measured width is byte-identical between the two states — the placer
    // measures the chip BEFORE it decides to flip, and commits the box it measured. The border's
    // side is asserted through nothing: see the class comment — cssstyle drops the var()-carrying
    // shorthand, so that half of the mirror is a browser claim.
    expect(style.paddingRight).toBe('5px');
    expect(style.paddingLeft).toBe('0px');
  });

  it('leaves an unflipped chip in document order, marker at the left end', async () => {
    await withMeasuredMap(400, async () => {
      await withChipBoxes(50, 14, async () => {
        await renderMap({ chips: [{ key: '1', locationId: 1, locationName: 'Bamburgh', rating: 4 }] });
      });
    });
    const chip = screen.getByTestId('wf-row-map-chip');
    expect(chip).not.toHaveAttribute('data-flip');
    // `.toBe('row')` would be the stronger claim, but jsdom answers '' for a longhand no rule
    // sets — both mean "the flip rule did not reach it", which is the claim under test.
    expect(getComputedStyle(chip).flexDirection).not.toBe('row-reverse');
    // Marker first in DOM order here too — one fact, asserted in both states, so a child reorder
    // cannot hide behind whichever state a future test happens to exercise.
    expect(chip.firstElementChild).toHaveClass('wf-mchip-m');
    // The base rule's padding, proving the flip rule's `padding-left: 0` did not leak here — the
    // borders stay a browser claim (class comment).
    const rating = getComputedStyle(chip.querySelector('.wf-mchip-r'));
    expect(rating.paddingLeft).toBe('5px');
  });
});
