import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The stand-down key, label and footer count, asserted against the real `index.css` (big-seas-plan.md
 * F3). jsdom lays nothing out, so what this pins is the MECHANISM: the three tokens exist with the
 * design's values, the swatch is 20x9, and under `forced-colors: active` all the swatches keep a
 * border and the two hatched ones stay distinguishable from each other (dashed vs dotted) because
 * backgrounds and box-shadows are dropped there.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

function readCss() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/** Every top-level `{selector, bodyInner}` block, an at-rule carrying its nested rules verbatim. */
function topLevelBlocks(css) {
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
        blocks.push({
          selector: css.slice(selectorStart, blockStart).trim(),
          bodyInner: css.slice(blockStart + 1, i),
        });
        selectorStart = i + 1;
      }
    }
  }
  return blocks;
}

/** Declarations of the last rule whose selector list holds `selector`, optionally inside a media query. */
function ruleFor(selector, mediaNeedle = null) {
  const blocks = topLevelBlocks(readCss());
  const scope = mediaNeedle
    ? blocks.filter((b) => b.selector.startsWith('@media') && b.selector.includes(mediaNeedle))
      .flatMap((b) => topLevelBlocks(b.bodyInner))
    : blocks.filter((b) => !b.selector.startsWith('@'));
  const hits = scope.filter((b) => b.selector.split(',').map((s) => s.trim()).includes(selector));
  return hits.length ? hits.at(-1).bodyInner : null;
}

describe('the stand-down tokens (big-seas plan F3)', () => {
  it.each([
    ['--color-standdown-ink', '#D3DAE8'],
    ['--color-standdown-ink-2', '#B8C5DC'],
    ['--color-standdown-count', '#C9D2E4'],
  ])('%s is %s in @theme', (token, value) => {
    const theme = topLevelBlocks(readCss()).find((b) => b.selector.endsWith('@theme'));
    expect(theme.bodyInner).toMatch(new RegExp(`${token}:\\s*${value}`, 'i'));
  });

  it('the label, cause line and footer count read the tokens, with no raw hex', () => {
    expect(ruleFor('.wf-maplab-region.wf-maplab-standdown')).toContain('var(--color-standdown-ink)');
    expect(ruleFor('.wf-maplab-standdown-cause')).toContain('var(--color-standdown-ink-2)');
    expect(ruleFor('.wf-map-counts-standdown')).toContain('var(--color-standdown-count)');
  });
});

describe('the stand-down swatch', () => {
  it('is 20x9', () => {
    const body = ruleFor('.wf-legend-swatch-standdown');
    expect(body).toMatch(/width:\s*20px/);
    expect(body).toMatch(/height:\s*9px/);
  });

  it('forced colours: every swatch keeps a border, and the two hatched ones differ in style', () => {
    expect(ruleFor('.wf-legend-swatch', 'forced-colors')).toContain('border: 1px solid CanvasText');
    const hatch = ruleFor('.wf-legend-swatch-hatch', 'forced-colors');
    const stood = ruleFor('.wf-legend-swatch-standdown', 'forced-colors');
    expect(hatch).toContain('border-style: dashed');
    expect(stood).toContain('border-style: dotted');
    // Both keep their own colours so the stripes survive; the base border above is not overridden.
    expect(hatch).toContain('forced-color-adjust: none');
    expect(stood).toContain('forced-color-adjust: none');
  });

  it('the stand-down rule sits after the base swatch, so its 20x9 wins the cascade', () => {
    const css = readCss();
    expect(css.indexOf('.wf-legend-swatch-standdown {'))
      .toBeGreaterThan(css.indexOf('.wf-legend-swatch {'));
  });
});
