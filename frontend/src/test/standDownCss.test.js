import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The stand-down key, label and footer count, asserted against the real `index.css` (big-seas-plan.md
 * F3). jsdom lays nothing out, so what this pins is the MECHANISM: the three tokens exist with the
 * design's values, the swatch sets no size of its own (it shares the base 26x13), and under `forced-colors: active` all the swatches keep a
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
    const label = ruleFor('.wf-maplab-region.wf-maplab-standdown');
    const cause = ruleFor('.wf-maplab-standdown-cause');
    const count = ruleFor('.wf-map-counts-standdown');
    expect(label).toContain('var(--color-standdown-ink)');
    expect(cause).toContain('var(--color-standdown-ink-2)');
    expect(count).toContain('var(--color-standdown-count)');
    for (const body of [label, cause, count]) expect(body).not.toMatch(/#[0-9a-f]{3,8}\b/i);
  });
});

describe('the stand-down label (big-seas plan F3)', () => {
  it('is the prototype\'s .sdl: the text shadow, 1.2 line height, 2px gap, an 8.5px cause line', () => {
    const label = ruleFor('.wf-maplab-region.wf-maplab-standdown');
    expect(label).toContain('text-shadow: 0 1px 3px rgba(0, 0, 0, .95), 0 0 9px rgba(0, 0, 0, .85)');
    expect(label).toMatch(/line-height:\s*1\.2/);
    expect(label).toMatch(/gap:\s*2px/);
    expect(ruleFor('.wf-maplab-standdown-cause')).toMatch(/font-size:\s*8\.5px/);
  });

  it('phone: the 90px clip releases the box and truncates only the NAME, never the cause line', () => {
    const box = ruleFor(".wf-maplab-standdown[data-tiny='true']");
    expect(box).toMatch(/max-width:\s*none/);
    expect(box).toMatch(/overflow:\s*visible/);
    const name = ruleFor(".wf-maplab-standdown[data-tiny='true'] .wf-maplab-standdown-name");
    expect(name).toMatch(/max-width:\s*90px/);
    expect(name).toMatch(/overflow:\s*hidden/);
    expect(name).toMatch(/text-overflow:\s*ellipsis/);
    expect(name).toMatch(/display:\s*block/);
  });

  it('the release comes after the generic tiny clip, at the same specificity, so it wins', () => {
    const css = readCss();
    expect(css.indexOf(".wf-maplab-standdown[data-tiny='true'] {"))
      .toBeGreaterThan(css.indexOf(".wf-maplab-region[data-tiny='true'] {"));
  });
});

describe('the stand-down swatch', () => {
  it('sets no width or height of its own, so it shares the base swatch 26x13', () => {
    const body = ruleFor('.wf-legend-swatch-standdown');
    expect(body).not.toMatch(/(^|[\s;{])width:/);
    expect(body).not.toMatch(/(^|[\s;{])height:/);
    expect(body).toContain('repeating-linear-gradient(-45deg');
    expect(ruleFor('.wf-legend-swatch')).toMatch(/width:\s*26px/);
    expect(ruleFor('.wf-legend-swatch')).toMatch(/height:\s*13px/);
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

  it('forced colours: the dotted rule comes after the base swatch rule inside the @media', () => {
    const media = topLevelBlocks(readCss())
      .find((b) => b.selector.startsWith('@media') && b.selector.includes('forced-colors')
        && b.bodyInner.includes('.wf-legend-swatch-standdown'));
    expect(media.bodyInner.indexOf('.wf-legend-swatch-standdown'))
      .toBeGreaterThan(media.bodyInner.indexOf('.wf-legend-swatch {'));
  });

  it('the stand-down rule sits after the base swatch, so its background wins the cascade', () => {
    const css = readCss();
    expect(css.indexOf('.wf-legend-swatch-standdown {'))
      .toBeGreaterThan(css.indexOf('.wf-legend-swatch {'));
  });
});

describe('the stood-down place label (stood-down-place-labels-plan.md)', () => {
  it('reads the stand-down tokens for its name and cause, with no raw hex in either rule', () => {
    const name = ruleFor('.wf-maplab-stood .wf-maplab-chip-n');
    const cause = ruleFor('.wf-maplab-stood-w');
    expect(name).toContain('var(--color-standdown-ink)');
    expect(cause).toContain('var(--color-standdown-ink-2)');
    for (const body of [name, cause]) expect(body).not.toMatch(/#[0-9a-f]{3,8}\b/i);
  });

  it('never quietens the label with opacity (opacity is how the map DIMS a chip)', () => {
    const css = readCss();
    const rules = topLevelBlocks(css).filter((b) => b.selector.includes('wf-maplab-stood'));
    expect(rules.length).toBeGreaterThan(3);
    for (const rule of rules) expect(rule.bodyInner).not.toMatch(/opacity/);
  });

  it('wears a dotted hairline in place of the solid ring, and the hover and selected ring returns', () => {
    expect(ruleFor('.wf-maplab-chip.wf-maplab-stood')).toMatch(/border:\s*1px dotted/);
    for (const sel of ['.wf-maplab-chip.wf-maplab-stood:hover', ".wf-maplab-chip.wf-maplab-stood[data-selected='true']"]) {
      expect(ruleFor(sel)).toContain('inset 0 0 0 1.5px var(--color-home)');
    }
  });

  it('keeps a swatch at least 13px wide, above the base swatch rule\'s specificity', () => {
    expect(ruleFor('.wf-maplab-stood .wf-maplab-stood-sw')).toMatch(/width:\s*(1[3-9]|[2-9]\d)px/);
  });

  it('forced colours: the plate keeps a dotted border', () => {
    expect(ruleFor('.wf-maplab-chip.wf-maplab-stood', 'forced-colors')).toContain('1px dotted CanvasText');
  });

  it('the phone\'s tiny frame clips the name to 90px with an ellipsis', () => {
    const tiny = ruleFor('.wf-maplab-stood[data-tiny=\'true\'] .wf-maplab-chip-n');
    expect(tiny).toContain('max-width: 90px');
    expect(tiny).toContain('text-overflow: ellipsis');
  });
});
