import React from 'react';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import WindowProseSlot from '../components/WindowProseSlot.jsx';
import WindowSheetDialog from '../components/WindowSheetDialog.jsx';

/**
 * Which declaration wins on a movement mark, and how tall the prose slot is — both asserted against
 * the REAL `index.css`.
 *
 * <p>Every other test in this suite runs with `css: false` (`vite.config.js`), so jsdom loads no
 * stylesheet and `getComputedStyle` answers from inline styles alone. The whole movement channel's
 * direction signal is a CSS rule keyed on `data-tone`, so without this file the attribute is pinned
 * (three component tests do that) and what it *resolves to* is not — and deleting
 * `.wf-prose-k b[data-tone="down"]` would paint a falling region in plain ink, or worse, leave
 * `.wf-prose-k b`'s `--color-plex-text` winning, with a green suite. This project has shipped
 * exactly that class of defect twice (P2, P4c).
 *
 * <h2>What this file can and cannot prove</h2>
 *
 * <p>The technique's limit: jsdom
 * resolves SPECIFICITY but does not resolve `var()`, so these assertions prove which declaration
 * won and prove nothing about what the token evaluates to. A token pruned to the empty string would
 * pass here. That half is a browser claim — and for these two it is a settled one, since
 * `--color-badge-go` and `--color-badge-poor` live in `@theme static`, which Tailwind v4 does not
 * prune.
 *
 * <p>The tone rules are a genuine specificity contest and not a formality: `.wf-prose-k b`
 * (0,1,1) sets `color: var(--color-plex-text)` and the tone rules (0,2,1) must beat it. Get the
 * order or the specificity wrong and every movement figure in the popup renders as ordinary ink.
 *
 * <p>⚠️ M2 moved the region band's figures into the popup's prose slot and added a SECOND render
 * site — the dialog header's own movement chip (`.wf-wsh-l2 b`). Both are sliced and both are
 * asserted, because two sites with one channel is exactly where a rule gets added to one and
 * forgotten on the other.
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
        // ⚠️ `.wf-hc-mv` used to be an arm here. M1 deleted the per-card movement chip along with
        // the whole class, so the arm matched nothing — and the guard below could not tell, because
        // it checked only one surface's rules and the two tone attributes, which the change line
        // still supplies. A dead clause in a slicer whose entire job is to fail loudly on an empty
        // slice is exactly the thing that file exists not to have. `assertSliceIsIntact` now names
        // every surface separately for that reason.
        if (/\.wf-prose\b/.test(selector) || /\.wf-prose-k\b/.test(selector)
          || /\.wf-wsh-l2\b/.test(selector)
          || /\.wf-hstrip-change\b/.test(selector)) {
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
  expect(slice).toContain('.wf-prose-k b');
  expect(slice).toContain('[data-tone="up"]');
  expect(slice).toContain('[data-tone="down"]');
  // Named per surface, so an extractor arm that stops matching cannot hide behind a sibling's
  // rules — which is how the deleted `.wf-hc-mv` arm went unnoticed.
  expect(slice).toContain('.wf-wsh-l2 b');
  expect(slice).toContain('.wf-hstrip-change b');
  // The prose slot's own declaration, sliced for the height test below.
  expect(slice).toContain('min-height');
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
afterEach(cleanup);

const GO = 'var(--color-badge-go)';
const POOR = 'var(--color-badge-poor)';
const PLAIN = 'var(--color-plex-text)';

function renderProseWith(meanRatingDelta) {
  render(
    <WindowProseSlot
      row={{
        name: 'Northumberland & Tyneside',
        verdict: 'WORTH_IT',
        summary: 'A clean eastern horizon.',
        bestRating: 5,
        meanRatingDelta,
      }}
      picked
      below={4}
    />,
  );
  return screen.getByTestId('wf-prose-moved-mark');
}

describe('the movement mark takes its direction\'s hue, and beats the base rule to do it', () => {
  it('paints a rise with the lifted go token', () => {
    expect(getComputedStyle(renderProseWith(0.6)).color).toBe(GO);
  });

  it('paints a fall with the lifted poor token', () => {
    // The specificity contest that matters: `.wf-prose-k b` sets plain ink and is earlier in the
    // file, so a tone rule that lost would leave a falling region indistinguishable from a rising
    // one — colour being the only channel the glyph does not already carry.
    expect(getComputedStyle(renderProseWith(-0.3)).color).toBe(POOR);
  });

  it('leaves a MEASURED zero in plain ink, borrowing neither direction', () => {
    // "Did not move" is the absence of a direction. Tinting it either way would assert one.
    expect(getComputedStyle(renderProseWith(0)).color).toBe(PLAIN);
  });
});

/**
 * The popup HEADER's own movement chip — the second render site of one channel.
 *
 * <p>Rendered for real rather than asserted from the slice, because a rule that is merely PRESENT
 * proves nothing about which declaration wins: that is the "test that cannot fail" this file's own
 * header warns about. The dialog is given an empty catalogue, so nothing paints and no canvas
 * context is needed.
 */
function renderHeaderWith(delta) {
  render(
    <WindowSheetDialog
      card={{
        key: '2026-08-04:SUNSET',
        date: '2026-08-04',
        targetType: 'SUNSET',
        kicker: 'Tonight',
        when: 'Sunset',
        time: '21:11',
        verdict: 'WORTH_IT',
        verdictLabel: 'Worth it',
        bestRating: 5,
        confidence: 'high',
        movement: { regionName: 'Coast', delta },
        spots: [],
        allSpots: [],
        pool: [],
        rows: [],
        allBadges: [],
      }}
      index={0}
      total={6}
      field={{
        eventSummary: null,
        spots: [],
        points: [],
        windows: [],
        series: new Map(),
        reachById: new Map(),
        lens: {},
        onSelectRegion: () => {},
        selectedRegion: null,
        singleRegionScope: false,
        origin: null,
      }}
      topicIndex={new Map()}
      scopeNames={[]}
      todayStr="2026-08-04"
      onClose={() => {}}
    />,
  );
  return screen.getByTestId('window-sheet-moved-mark');
}

describe('the popup header carries the same channel, and the same contest', () => {
  it('paints a rise with the lifted go token', () => {
    expect(getComputedStyle(renderHeaderWith(0.6)).color).toBe(GO);
  });

  it('paints a fall with the lifted poor token', () => {
    expect(getComputedStyle(renderHeaderWith(-0.3)).color).toBe(POOR);
  });

  it('leaves a MEASURED zero in plain ink here too', () => {
    expect(getComputedStyle(renderHeaderWith(0)).color).toBe(PLAIN);
  });
});

/**
 * ⚠️ The prose slot's fixed height, which is the whole reason the element exists.
 *
 * <p>Picking a region is meant to swap words and repaint the field, not move furniture — so if the
 * box changed height between states, the tide row and the ranked locations below would jump on
 * every pick and every clear. Every other test in the suite runs with `css: false` and could only
 * assert that the same NODE is rendered; this file already injects a slice of the real stylesheet,
 * so it can assert the declaration itself.
 *
 * <p>What it cannot do is resolve the media query: jsdom evaluates none, so the phone's
 * `min-height: 0` is a browser claim. What it pins is that the desktop floor is one value and that
 * <b>all three of the states the plan names</b> get it.
 */
describe('the prose slot holds one height in every state', () => {
  const row = (overrides = {}) => ({
    name: 'Northumberland & Tyneside',
    verdict: 'WORTH_IT',
    summary: 'A clean eastern horizon.',
    glossHeadline: null,
    glossDetail: null,
    bestRating: 5,
    ...overrides,
  });

  it.each([
    ['picked', { picked: true, below: 4, row: row({ meanRatingDelta: 0.5 }) }],
    ['unpicked with gloss', { picked: false, row: row({ glossHeadline: 'Cirrus canvas', glossDetail: 'Thin high cloud at 40%.' }) }],
    ['unpicked with no prose at all', { picked: false, row: row({ summary: null }) }],
  ])('is 124px %s', (_label, props) => {
    render(<WindowProseSlot {...props} row={props.row ?? row()} />);
    expect(getComputedStyle(screen.getByTestId('wf-prose')).minHeight).toBe('124px');
  });
});
