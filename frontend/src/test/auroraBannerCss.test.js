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

    it('lights only the fraction the severity names, by its width, with a glow in the accent', () => {
      const body = ruleBody('.wf-aurora-strip::after');
      expect(declares(body, 'width', 'var(--fill)')).toBe(true);
      expect(declares(body, 'left', '0')).toBe(true);
      expect(declares(body, 'box-shadow', '0 0 10px var(--acc)')).toBe(true);
    });

    it('never clips the edge: a clip-path would clip its own glow, which was the B1 defect', () => {
      expect(ruleBody('.wf-aurora-strip::after')).not.toMatch(/clip-path/);
      expect(block).not.toMatch(/clip-path/);
    });

    it('stretches the ramp back across the whole strip, once, so a narrower edge is not a squeezed gradient', () => {
      const body = ruleBody('.wf-aurora-strip::after');
      expect(declares(body, 'background-size', 'calc(100% / var(--fill-ratio)) 100%')).toBe(true);
      expect(declares(body, 'background-repeat', 'no-repeat')).toBe(true);
      // `right: 0` would pin the right edge and make the width a no-op.
      expect(body).not.toMatch(/(^|[;{\s])right:/);
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

  describe('the viewline label (B3)', () => {
    it('sits in the slot\'s top-left corner as 8.5px mono 600 amber, nowrap, with the spec\'s shadow', () => {
      const body = ruleBody('.wf-aurora-vk');
      expect(body).not.toBeNull();
      expect(declares(body, 'position', 'absolute')).toBe(true);
      expect(declares(body, 'top', '4px')).toBe(true);
      expect(declares(body, 'left', '5px')).toBe(true);
      expect(declares(body, 'font-family', 'var(--font-mono)')).toBe(true);
      expect(declares(body, 'font-size', '8.5px')).toBe(true);
      expect(declares(body, 'font-weight', '600')).toBe(true);
      expect(declares(body, 'color', '#E0A542')).toBe(true);
      expect(declares(body, 'white-space', 'nowrap')).toBe(true);
      expect(declares(body, 'text-shadow', '0 1px 3px #000')).toBe(true);
    });

    it('is invisible to the pointer, so the card underneath keeps every press and hover', () => {
      expect(declares(ruleBody('.wf-aurora-vk'), 'pointer-events', 'none')).toBe(true);
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

    it('gives the expand toggle the dismiss button\'s focus ring, forced-colours ring and idiom', () => {
      const focus = ruleBody('.wf-aurora-tog:focus-visible');
      expect(focus).not.toBeNull();
      expect(focus).toContain('outline: 2px solid var(--acc-ink)');
      // The forced-colours list is the only rule block that names Highlight beside the dismiss.
      const forced = /@media \(forced-colors: active\)\s*\{[^@]*?outline-color:\s*Highlight/.exec(block);
      expect(forced).not.toBeNull();
      expect(forced[0]).toContain('.wf-aurora-x:focus-visible');
      expect(forced[0]).toContain('.wf-aurora-tog:focus-visible');
      // A 44px target, shared with the dismiss, so the two never drift apart.
      const body = ruleBody('.wf-aurora-tog');
      expect(declares(body, 'width', '44px')).toBe(true);
      expect(declares(body, 'height', '44px')).toBe(true);
    });

    it('seats the toggle in the strip and, beside the dismiss, in the card\'s corner', () => {
      const strip = ruleBody('.wf-aurora-tog.wf-aurora-tog-strip');
      expect(strip).not.toBeNull();
      expect(declares(strip, 'align-self', 'center')).toBe(true);
      const actions = ruleBody('.wf-aurora-actions');
      expect(actions).not.toBeNull();
      expect(declares(actions, 'align-self', 'flex-start')).toBe(true);
      // The wrapper takes the dismiss's old corner offset; the two buttons inside carry none of their own.
      expect(declares(actions, 'margin', '-4px -8px 0 0')).toBe(true);
      expect(declares(ruleBody('.wf-aurora-actions .wf-aurora-x'), 'margin', '0')).toBe(true);
      expect(declares(ruleBody('.wf-aurora-actions .wf-aurora-tog'), 'margin', '0')).toBe(true);
    });

    it('widens the strip only inside a 640px-and-up media query, so the phone strip is unchanged', () => {
      const wide = /@media \(min-width: 640px\)\s*\{\s*\.wf-aurora-strip:not\(\.wf-aurora-strip--lite\)\s*\{([^{}]*)\}\s*\}/.exec(block);
      // Not the Lite banner's strip, which is out of scope for the collapse.
      expect(wide).not.toBeNull();
      expect(declares(wide[1], 'padding', '0 8px 0 16px')).toBe(true);
      // The base rule keeps the phone's padding.
      expect(declares(ruleBody('.wf-aurora-strip'), 'padding', '0 6px 0 12px')).toBe(true);
    });

    it('keeps the dot, the lit bars and the viewline label their own colour under forced colours', () => {
      const m = /@media \(forced-colors: active\)\s*\{[\s\S]*?forced-color-adjust:\s*none;/.exec(block);
      expect(m).not.toBeNull();
      expect(m[0]).toContain('.wf-aurora-dot');
      expect(m[0]).toContain(".wf-aurora-bar[data-lit='true']");
      // The viewline label is amber text over a dark canvas that forced colours does not repaint: left
      // to the system's CanvasText it could be black on near-black.
      expect(m[0]).toContain('.wf-aurora-vk');
    });
  });

  it('leaves no trace of the old pulse animation or the old banner classes anywhere in src', () => {
    const offenders = sourceFiles(SRC)
      .filter((f) => !f.endsWith('auroraBannerCss.test.js'))
      .filter((f) => /aurora-pulse|aurora-banner-simulated|AURORA_A_STYLE|PULSE_STYLE|SIM_BORDER_STYLE/.test(readFileSync(f, 'utf8')));
    expect(offenders).toEqual([]);
  });
});
