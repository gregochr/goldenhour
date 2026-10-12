import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { auroraRampGradientCss } from '../utils/auroraRamp.js';

/**
 * The aurora banner's stylesheet, read as text. jsdom applies no stylesheet, so a component test
 * cannot see the keyframes, the reduced-motion rule, the strip's ramp edge or which bar picks which
 * colour; these pins read `index.css` itself (the `askCss.test.js` approach). Narrow on purpose —
 * how it looks is a browser check (docs/engineering/aurora-banner-plan.md §8).
 */
const SRC = resolve(process.cwd(), 'src');
const css = readFileSync(join(SRC, 'index.css'), 'utf8');
const startAt = css.indexOf('Aurora alert banner (docs/engineering/aurora-banner-plan.md B1)');
// The banner's block, header comment to its end marker (the Ask block after it has tests that slice
// to the end of the file, so this block sits before it): comments stripped, so prose that names a
// selector or a hex can never satisfy (or fail) a rule check.
const endAt = css.indexOf('end Aurora alert banner', startAt);
const block = css.slice(css.lastIndexOf('/*', startAt), css.indexOf('*/', endAt) + 2)
  .replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * The bodies of every rule whose selector list has `selector` as one entry, joined — a selector the
 * stylesheet shares with its sibling (`.wf-aurora-card, .wf-aurora-strip`) and also styles alone
 * reads as one declaration set, which is how the cascade sees it. Null when no rule names it.
 */
function ruleBody(selector) {
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  const bodies = [];
  let m = rule.exec(block);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) bodies.push(m[2]);
    m = rule.exec(block);
  }
  return bodies.length > 0 ? bodies.join('\n') : null;
}

/** True when `body` declares `prop` with exactly `value` — not as a `min-`/`max-`/`line-` cousin of it. */
function declares(body, prop, value) {
  const escaped = value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[;{\\s])${prop}:\\s*${escaped}\\s*(;|$)`).test(body);
}

/** Every `.js`/`.jsx`/`.css` file under `dir`: absolute paths. */
function sourceFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return /\.(jsx?|css)$/.test(name) ? [full] : [];
  });
}

describe('the aurora banner stylesheet', () => {
  it('finds its own block at all', () => {
    expect(startAt).toBeGreaterThan(0);
    expect(endAt).toBeGreaterThan(startAt);
    expect(block.length).toBeGreaterThan(1000);
    expect(ruleBody('.wf-aurora-card')).not.toBeNull();
    expect(ruleBody('.wf-aurora-strip')).not.toBeNull();
  });

  describe('breathe', () => {
    it('defines the keyframes exactly once, as the spec writes them', () => {
      expect(css.match(/@keyframes breathe\b/g)).toHaveLength(1);
      expect(block).toMatch(
        /@keyframes breathe\s*\{\s*0%,\s*100%\s*\{\s*box-shadow:\s*0 0 0 0 transparent;\s*\}\s*50%\s*\{\s*box-shadow:\s*0 0 22px -4px color-mix\(in srgb, var\(--acc\) 45%, transparent\);\s*\}\s*\}/,
      );
    });

    it('runs for 3.2s ease-in-out forever on the breathing class', () => {
      expect(block).toMatch(/\.wf-aurora-breathe\s*\{\s*animation:\s*breathe 3\.2s ease-in-out infinite;\s*\}/);
    });

    it('switches the animation off under prefers-reduced-motion, naming the breathing class', () => {
      const m = /@media \(prefers-reduced-motion: reduce\)\s*\{\s*\.wf-aurora-breathe\s*\{\s*animation:\s*none;\s*\}\s*\}/.exec(block);
      expect(m).not.toBeNull();
    });

    it('puts the reduced-motion rule AFTER the animation it overrides: equal specificity, so order decides', () => {
      const animates = block.search(/\.wf-aurora-breathe\s*\{\s*animation:\s*breathe/);
      const reduces = block.indexOf('@media (prefers-reduced-motion: reduce)');
      expect(animates).toBeGreaterThan(-1);
      expect(reduces).toBeGreaterThan(animates);
    });
  });

  describe('the strip\'s ramp edge', () => {
    it('is the ramp module\'s own gradient, #1F7F78 teal included, and never the bundle\'s #22867E', () => {
      const body = ruleBody('.wf-aurora-strip::after');
      expect(body).not.toBeNull();
      expect(body).toContain(`background: ${auroraRampGradientCss()};`);
      expect(body).toContain('#1F7F78');
      expect(block.toUpperCase()).not.toContain('#22867E');
    });

    it('lights only the fraction the severity names, with a glow in the accent', () => {
      const body = ruleBody('.wf-aurora-strip::after');
      expect(body).toContain('clip-path: inset(0 calc(100% - var(--fill)) 0 0)');
      expect(body).toContain('box-shadow: 0 0 10px var(--acc)');
    });
  });

  describe('the meter', () => {
    it.each([0, 1, 2, 3, 4, 5, 6, 7, 8])('bar %i takes its colour from --bar-%i and no other', (k) => {
      const body = ruleBody(`.wf-aurora-bar[data-k='${k}']`);
      expect(body).not.toBeNull();
      expect(body.trim()).toBe(`--c: var(--bar-${k});`);
    });

    it('paints a lit bar in its own colour and an unlit one in the faint bone', () => {
      expect(ruleBody(".wf-aurora-bar[data-lit='true']")).toContain('background: var(--c)');
      expect(ruleBody('.wf-aurora-bar')).toContain('background: rgba(242, 231, 211, 0.08)');
    });

    it('glows a bar the component marks for it, 5px, in the bar\'s own colour', () => {
      expect(ruleBody(".wf-aurora-bar[data-glow='true']")).toContain('box-shadow: 0 0 5px var(--c)');
    });

    it('stacks bottom-up', () => {
      expect(ruleBody('.wf-aurora-meter')).toContain('flex-direction: column-reverse');
    });
  });

  describe('the thumbnail slot (B2)', () => {
    // jsdom applies no stylesheet, so "the slot is 96×118 in both states" is read off the CSS: the
    // wrapper fixes the size for the pending state AND the field, and the canvas is exactly that size
    // and block-level, so the swap shifts nothing.
    it('fixes the slot at 96×118, clipped, whichever state is inside', () => {
      const body = ruleBody('.wf-aurora-thumb');
      expect(declares(body, 'width', '96px')).toBe(true);
      expect(declares(body, 'height', '118px')).toBe(true);
      expect(declares(body, 'overflow', 'hidden')).toBe(true);
    });

    it('sizes the field canvas to the slot and takes it out of the inline baseline', () => {
      const body = ruleBody('.wf-aurora-field');
      expect(declares(body, 'width', '96px')).toBe(true);
      expect(declares(body, 'height', '118px')).toBe(true);
      expect(declares(body, 'display', 'block')).toBe(true);
    });

    it('lets the strip\'s best name shrink and ellipsise while the rating never does', () => {
      expect(declares(ruleBody('.wf-aurora-strip-l2-best'), 'display', 'flex')).toBe(true);
      const name = ruleBody('.wf-aurora-strip-name');
      expect(declares(name, 'min-width', '0')).toBe(true);
      expect(declares(name, 'overflow', 'hidden')).toBe(true);
      expect(declares(name, 'text-overflow', 'ellipsis')).toBe(true);
      expect(ruleBody('.wf-aurora-strip-l2-best > .wf-aurora-stars')).toContain('flex: none');
    });

    it('sets the best site\'s stars in the severity ink', () => {
      expect(ruleBody('.wf-aurora-stars')).toContain('color: var(--acc-ink)');
    });
  });

  describe('no stray ramp colour', () => {
    it('types each ramp stop once at most, in the strip\'s edge gradient, so every other colour is a property', () => {
      for (const hex of ['#384262', '#2C5470', '#1F7F78', '#3AC884', '#76FFA0']) {
        expect(block.toUpperCase().split(hex).length - 1).toBe(1);
      }
    });

    it('never sets text in the accent itself: a ramp colour is a fill, and text takes the ink', () => {
      expect(block).not.toMatch(/(^|[;{\s])color:\s*var\(--acc\)/);
      expect(ruleBody('.wf-aurora-pill')).toContain('color: var(--acc-ink)');
      expect(ruleBody('.wf-aurora-cta')).toContain('color: var(--acc-ink)');
    });

    it('sets no text in the .42 muted bone, which is 3.5:1 on the surface', () => {
      expect(block).not.toContain('--color-plex-text-muted');
    });
  });

  describe('geometry', () => {
    it('keeps the thumbnail slot 96 by 118 whatever it holds, and positioned for a label over it', () => {
      const body = ruleBody('.wf-aurora-thumb');
      expect(declares(body, 'width', '96px')).toBe(true);
      expect(declares(body, 'height', '118px')).toBe(true);
      expect(declares(body, 'position', 'relative')).toBe(true);
    });

    it('holds the strip to the spec\'s 56px minimum height', () => {
      expect(declares(ruleBody('.wf-aurora-strip'), 'min-height', '56px')).toBe(true);
    });

    it('makes the dismiss button a 44px target on both layouts, the strip\'s override changing neither side', () => {
      const body = ruleBody('.wf-aurora-x');
      expect(declares(body, 'width', '44px')).toBe(true);
      expect(declares(body, 'height', '44px')).toBe(true);
      const strip = ruleBody('.wf-aurora-x.wf-aurora-x-strip');
      expect(strip).not.toMatch(/(^|[;{\s])(width|height):/);
      expect(declares(strip, 'margin', '0')).toBe(true);
    });
  });

  describe('treatments the component only names by class or attribute', () => {
    it('hatches a simulated alert with a dashed border, on both layouts', () => {
      for (const layout of ['.wf-aurora-card', '.wf-aurora-strip']) {
        const body = ruleBody(`${layout}.wf-aurora-simulated`);
        expect(body).toContain('repeating-linear-gradient');
        expect(declares(body, 'border-style', 'dashed')).toBe(true);
      }
    });

    it('gives the card, the strip and the dismiss button a visible focus ring in the ink colour', () => {
      for (const target of ['.wf-aurora-card', '.wf-aurora-strip', '.wf-aurora-x']) {
        const body = ruleBody(`${target}:focus-visible`);
        expect(body).not.toBeNull();
        expect(body).toContain('outline: 2px solid var(--acc-ink)');
      }
    });

    it('keeps the dot and the lit bars their own colour under forced colours', () => {
      const m = /@media \(forced-colors: active\)\s*\{[\s\S]*?forced-color-adjust:\s*none;/.exec(block);
      expect(m).not.toBeNull();
      expect(m[0]).toContain('.wf-aurora-dot');
      expect(m[0]).toContain(".wf-aurora-bar[data-lit='true']");
    });
  });

  it('leaves no trace of the old pulse animation or the old banner classes anywhere in src', () => {
    const offenders = sourceFiles(SRC)
      .filter((f) => !f.endsWith('auroraBannerCss.test.js'))
      .filter((f) => /aurora-pulse|aurora-banner-simulated|AURORA_A_STYLE|PULSE_STYLE|SIM_BORDER_STYLE/.test(readFileSync(f, 'utf8')));
    expect(offenders).toEqual([]);
  });
});
