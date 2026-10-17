import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, beforeEach, afterAll, afterEach, vi,
} from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import WindowFirstHeatStrip from '../components/WindowFirstHeatStrip.jsx';

/**
 * The Spread row's wrapper, label and count columns — which declaration wins, asserted against the
 * REAL `index.css` (docs/engineering/spread-row-plan.md S2).
 *
 * <h2>Why a cascade test, and what it can and cannot prove</h2>
 *
 * <p>The suite runs with `css: false`, so `WindowFirstHeatStrip.test.jsx` sees structure and no
 * stylesheet. This row is built on a specificity trap its own CSS block documents: the value grid's
 * `.wf-hc-pls > span` is (0,1,1) and reaches the wrapper but NOT the wrapper's two children, which
 * therefore need `display: flex; align-items: center; min-width: 0` re-stated or the histogram sits
 * at the LEFT of its line in the stacked state; and every override of those children has to be
 * written higher still. A rule that quietly loses is a green suite and a broken card.
 *
 * <p>jsdom resolves SPECIFICITY and does not resolve `var()` (it returns the declaration text), so
 * "the nested cell's `min-height` is `var(--wf-hc-well)`" is the strongest thing to say about the
 * single row-height figure here — and the same string on the wrapper, the value cell and the well
 * is what proves the three read ONE token. What the token evaluates to, whether `@container
 * wf-card` fires at 205px, where the histogram's right edge lands, and the contrast of the counts
 * are browser claims, measured in plan §7's fixture. jsdom does not evaluate `@container`, so those
 * blocks are pinned as TEXT below.
 */

vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => null),
  };
});
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

const CSS_PATH = resolve(process.cwd(), 'src/index.css');
const TODAY = '2026-08-04';

/** The index.css text with comments stripped (they hold both braces and the class names). */
function readCss() {
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/**
 * Every TOP-LEVEL rule (no at-rules) whose selector mentions a Spread-row surface, in source order.
 * Brace-depth aware, so a rule inside an `@media` or `@container` block is never re-associated with
 * the wrong selector — those blocks are skipped here and pinned as text.
 */
function spreadRowRules(css) {
  const rules = [];
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
        const selector = css.slice(selectorStart, blockStart).trim();
        if (!selector.startsWith('@')
          && /\.wf-hc-(pls|sprow|sk|pv|k|hist|hb)\b/.test(selector)) {
          rules.push(`${selector} ${css.slice(blockStart, i + 1)}`);
        }
        selectorStart = i + 1;
      }
    }
  }
  return rules.join('\n');
}

/** The text of the block whose header contains `header`, braces included. */
function blockNamed(css, header) {
  const at = css.indexOf(header);
  expect(at, `${header} must exist`).toBeGreaterThan(-1);
  const open = css.indexOf('{', at);
  let depth = 0;
  for (let i = open; i < css.length; i += 1) {
    if (css[i] === '{') depth += 1;
    else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) return css.slice(at, i + 1);
    }
  }
  throw new Error(`${header} never closes`);
}

let styleEl;
beforeAll(() => {
  const slice = spreadRowRules(readCss());
  // Named per surface, so a slicer that stops matching one cannot hide behind another's rules: an
  // empty injection would leave every property at its default and pass the "not the grid's 20px"
  // assertions for the wrong reason.
  expect(slice).toContain('.wf-hc-pls > span');
  expect(slice).toContain('.wf-hc-sprow');
  expect(slice).toContain('.wf-hc-sk');
  expect(slice).toContain('.wf-hc-hist .wf-hc-hb');
  styleEl = document.createElement('style');
  styleEl.textContent = slice;
  document.head.appendChild(styleEl);
});
afterAll(() => styleEl?.remove());

let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  cleanup();
});

const spot = (rating, index) => ({
  key: String(index + 1),
  locationId: index + 1,
  locationName: `Spot ${index + 1}`,
  regionName: 'Northumberland & Tyneside',
  rating,
  driveMinutes: 40,
  solarEventTime: `${TODAY}T20:11:00`,
});

function card(ratings) {
  const pool = ratings.map(spot);
  return {
    key: `${TODAY}:SUNSET`,
    date: TODAY,
    targetType: 'SUNSET',
    dow: 'Tue',
    sunrise: false,
    label: 'Tonight Sunset',
    time: '21:11',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: 4,
    pickKind: null,
    away: false,
    confidence: 'high',
    pool,
    reachMeasured: true,
    reachLimitMinutes: 45,
    bestReach: pool[0],
    badges: [],
  };
}

async function renderCard(ratings) {
  const cards = [card(ratings)];
  const pointSets = new Map([[cards[0].key, [{
    id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, rid: 'Northumberland & Tyneside', r: [4],
  }]]]);
  await act(async () => {
    render(
      <WindowFirstHeatStrip
        spots={[{
          id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, regionName: 'Northumberland & Tyneside',
          rid: 'Northumberland & Tyneside', skySubject: true, bortleClass: 3, scores: [4],
        }]}
        todayStr={TODAY}
        onOpenWindow={vi.fn()}
        cards={cards}
        pointSets={pointSets}
      />,
    );
  });
}

const style = (el) => window.getComputedStyle(el);
const BARS = [3, 3, 3, 3, 5, 5];
const BELOW_GATE = [4, null, null, null];

describe('the Spread row wrapper — what beats what', () => {
  it('lets the wrapper beat the grid\'s 20px child minimum with the ONE row-height token', async () => {
    await renderCard(BARS);
    const row = style(screen.getByTestId('wf-heat-spread-row'));
    // `.wf-hc-pls > span` is (0,1,1) and sets `min-height: 20px` on this very element (a direct
    // child of the grid); `.wf-hc-pls > .wf-hc-sprow` is (0,2,0). The bare `.wf-hc-sprow` would
    // lose, and the row would reserve 20px instead of the well's height.
    expect(row.minHeight).toBe('var(--wf-hc-well)');
    expect(row.display).toBe('flex');
    expect(row.justifyContent).toBe('space-between');
    expect(row.gap).toBe('8px');
    // The wrapper spans BOTH grid columns — the whole point of it: left in the auto-placed column it
    // would widen the shared `auto` track the rating pill sits in.
    expect(row.gridColumn).toBe('1 / -1');
  });

  it('gives the nested value cell back the flex container the grid rule no longer reaches', async () => {
    await renderCard(BARS);
    const value = style(screen.getByTestId('wf-heat-spread-value'));
    // The cell keeps `justify-content: flex-end` (the base `.wf-hc-pv` rule) ONLY while it is a
    // flex container: without `display: flex` re-stated for it, the histogram sits at the LEFT of
    // its own line in the stacked state.
    expect(value.display).toBe('flex');
    expect(value.justifyContent).toBe('flex-end');
    expect(value.alignItems).toBe('center');
    expect(value.minWidth).toBe('0px');
    expect(value.overflow).toBe('hidden');
  });

  it('reserves the well\'s height in the value cell in BOTH states, so neighbours across the gate align', async () => {
    await renderCard(BARS);
    expect(style(screen.getByTestId('wf-heat-spread-value')).minHeight).toBe('var(--wf-hc-well)');
    cleanup();
    await renderCard(BELOW_GATE);
    // The text state is one 10px line; without this the cell would be ~15px shorter than a
    // bars-mode neighbour's and the two Best rows would part in a stacked row.
    expect(screen.getByTestId('wf-heat-spread-note')).toBeInTheDocument();
    expect(style(screen.getByTestId('wf-heat-spread-value')).minHeight).toBe('var(--wf-hc-well)');
  });

  it('reads the SAME token for the wrapper, the value cell and the well, so the three cannot drift', async () => {
    await renderCard(BARS);
    const figures = [
      style(screen.getByTestId('wf-heat-spread-row')).minHeight,
      style(screen.getByTestId('wf-heat-spread-value')).minHeight,
      style(screen.getByTestId('wf-heat-spread')).height,
    ];
    expect(figures).toEqual(['var(--wf-hc-well)', 'var(--wf-hc-well)', 'var(--wf-hc-well)']);
    // And it is DEFINED exactly once, from the column it holds.
    const css = readCss();
    expect(css.match(/--wf-hc-well:/g)).toHaveLength(1);
    expect(css.match(/--wf-hc-col:/g)).toHaveLength(1);
    expect(css).toContain('--wf-hc-well: calc(var(--wf-hc-col) + 3px);');
    expect(css).toContain('--wf-hc-col: 25px;');
  });

  it('reserves the label\'s second line whether or not a qualifier is drawn, and keeps the label\'s column flex', async () => {
    await renderCard(BARS);
    const label = style(screen.getByTestId('wf-heat-spread-label'));
    // (0,3,0) `.wf-hc-pls > .wf-hc-sprow > .wf-hc-sk` over the children's shared (0,2,1): losing it
    // would put the label back to a centred ROW with its qualifier beside it.
    expect(label.minHeight).toBe('21px');
    expect(label.flexDirection).toBe('column');
    expect(label.alignItems).toBe('flex-start');
    expect(label.justifyContent).toBe('center');
    expect(label.display).toBe('flex');
    // Never shrinks: the value cell gives way (it clips inside itself), the label does not wrap.
    expect(label.flexShrink).toBe('0');
  });

  it('keeps the label at the same reserved height with no qualifier at all', async () => {
    // An unmeasured pool under a finite tier draws no qualifier — and the label line's height must
    // not depend on that, or two stacked neighbours that disagree would put their Best rows apart.
    const cards = [{
      ...card(BARS), pool: card(BARS).pool.map((s, i) => (i === 0 ? { ...s, driveMinutes: null } : s)),
    }];
    await act(async () => {
      render(
        <WindowFirstHeatStrip
          spots={[{
            id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, regionName: 'N', rid: 'N',
            skySubject: true, bortleClass: 3, scores: [4],
          }]}
          todayStr={TODAY}
          onOpenWindow={vi.fn()}
          cards={cards}
          pointSets={new Map([[cards[0].key, []]])}
        />,
      );
    });
    expect(screen.queryByTestId('wf-heat-spread-qualifier')).toBeNull();
    expect(style(screen.getByTestId('wf-heat-spread-label')).minHeight).toBe('21px');
  });
});

describe('the count columns — what beats what', () => {
  it('sizes every column from the card\'s own width variable, and holds the 25px column token', async () => {
    await renderCard(BARS);
    const column = screen.getAllByTestId('wf-heat-spread-count')[0].parentElement;
    expect(style(column).width).toBe('var(--cw, 9px)');
    expect(style(column).height).toBe('var(--wf-hc-col)');
    expect(style(column).flexDirection).toBe('column');
    expect(style(column).justifyContent).toBe('flex-end');
  });

  it('gives the shared bar rule\'s 7px to the hatched bar too — its own rule sets no width', async () => {
    await renderCard([5, 5, 5, 5, 5, null, null]);
    expect(style(screen.getByTestId('wf-heat-spread-bar-unrated')).width).toBe('7px');
    expect(style(screen.getAllByTestId('wf-heat-spread-bar')[0]).width).toBe('7px');
  });

  it('puts the wider gap on the hatched COLUMN and on nothing else', async () => {
    await renderCard([5, 5, 5, 5, 5, null, null]);
    const bar = screen.getByTestId('wf-heat-spread-bar-unrated');
    expect(style(bar.parentElement).marginRight).toBe('4px');
    expect(style(bar).marginRight).not.toBe('4px');
    const bandColumn = screen.getAllByTestId('wf-heat-spread-bar')[0].parentElement;
    expect(style(bandColumn).marginRight).not.toBe('4px');
  });

  it('gives the edges back on a four-character card only: 2px of the hatched gap, and no side padding', async () => {
    // Plan §6 Q4's remedy, applied after the §7 fixture measured the stacked line at 112px (not the
    // plan's 116) — without it a `1.2k` remainder's leading "1" is clipped and reads ".2k".
    const thousands = Array.from({ length: 1200 }, () => 3).concat(Array.from({ length: 600 }, () => null));
    await renderCard(thousands);
    const hist = screen.getByTestId('wf-heat-spread');
    expect(hist).toHaveClass('sm');
    const unratedColumn = screen.getByTestId('wf-heat-spread-bar-unrated').parentElement;
    expect(style(unratedColumn).marginRight).toBe('2px');
    cleanup();
    await renderCard([5, 5, 5, 5, 5, null, null]);
    expect(screen.getByTestId('wf-heat-spread')).not.toHaveClass('sm');
    expect(style(screen.getByTestId('wf-heat-spread-bar-unrated').parentElement).marginRight).toBe('4px');
    expect(blockNamed(readCss(), '.wf-hc-hist.sm {')).toContain('padding-inline: 0;');
  });

  it('quietens a ZERO count by weight alone — same secondary ink, 400 against 600', async () => {
    await renderCard(BARS);
    const counts = Object.fromEntries(
      screen.getAllByTestId('wf-heat-spread-count').map((el) => [el.getAttribute('data-star'), style(el)]),
    );
    // 3★ holds four (600); 1★ holds none (400). Both the SAME token: the muted ink composites to
    // ~3.55:1 on the well, under AA for 9px text, so the distinction is the weight and never the ink.
    expect(counts[3].fontWeight).toBe('600');
    expect(counts[1].fontWeight).toBe('400');
    expect(counts[1].color).toBe(counts[3].color);
    expect(counts[1].color).toBe('var(--color-plex-text-secondary)');
    expect(counts[3].fontSize).toBe('9px');
    expect(counts[3].fontStyle).toBe('normal');
  });
});

describe('the stylesheet half jsdom cannot evaluate — pinned as text', () => {
  const css = readCss();

  it('makes the card a named inline-size container, the only thing the stacked state keys on', () => {
    const rule = blockNamed(css, '\n.wf-hc {');
    expect(rule).toContain('container-type: inline-size;');
    expect(rule).toContain('container-name: wf-card;');
  });

  it('stacks below a 205px card: the row wraps and the value cell takes its own full-width line', () => {
    const query = blockNamed(css, '@container wf-card (max-width: 205px)');
    expect(query).toMatch(/\.wf-hc-pls > \.wf-hc-sprow \{[^}]*flex-wrap: wrap;/);
    expect(query).toMatch(/\.wf-hc-pls > \.wf-hc-sprow > \.wf-hc-pv \{[^}]*flex-basis: 100%;/);
    expect(query).toMatch(/\.wf-hc-pls > \.wf-hc-sprow > \.wf-hc-pv \{[^}]*margin-left: auto;/);
  });

  it('declares the stacked threshold exactly once, at the plan\'s 205px', () => {
    expect(css.match(/@container wf-card/g)).toHaveLength(1);
  });

  it('draws the qualifier at the label\'s size, tighter tracking, never wrapped', () => {
    const rule = blockNamed(css, '.wf-hc-sk small {');
    expect(rule).toContain('font-size: 8.5px;');
    expect(rule).toContain('letter-spacing: 0.06em;');
    expect(rule).toContain('white-space: nowrap;');
  });

  it('shrinks the counts to 7.5px only under .sm', () => {
    const rule = blockNamed(css, '.wf-hc-hist.sm em {');
    expect(rule).toContain('font-size: 7.5px;');
    expect(rule).toContain('letter-spacing: -0.1em;');
    expect(blockNamed(css, '.wf-hc-hist em {')).toContain('font-size: 9px;');
  });
});
