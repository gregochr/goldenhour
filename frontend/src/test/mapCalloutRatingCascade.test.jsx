import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The rating block's rules, asserted against the REAL `index.css` (outcome-rating-plan.md §3 O4a
 * "a `mapCalloutRatingCascade.test.jsx` pinning the block's rules against the real stylesheet";
 * `docs/design/outcome-rating/README.md` §3, §6).
 *
 * <p>jsdom lays nothing out, so `OutcomeRatingBlock.test.jsx` can only assert the DOM and the
 * classes. The sizes, the tokens, the Lite dimming and the focus ring are CSS, and each is one edit
 * away from silently changing: a token that is not referenced by a scanned file compiles to the empty
 * string (the `--color-plex-panel` incident `index.css` records), a lock written as `pointer-events:
 * none` would swallow the tap the spec wants answered. This file pins the declarations themselves.
 * It proves the rules are WRITTEN as intended, not that Chrome honours them — the layout half is the
 * plan's §7 measurement, in headless Chromium over the compiled stylesheet.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** `index.css` with comments stripped, so a value quoted in prose can never satisfy a test. */
function css() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`)
    .toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

const escapeRe = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Every rule body whose selector LIST contains exactly `selector`, outside any at-rule. */
function bodies(selector, source = css()) {
  const re = /([^{}]+)\{([^}]*)\}/g;
  const out = [];
  let m;
  while ((m = re.exec(source)) !== null) {
    // An at-rule's prelude is not a selector, and its nested rules fall inside its match.
    if (m[1].trim().startsWith('@')) continue;
    const list = m[1].split(',').map((part) => part.trim());
    if (list.includes(selector)) out.push(m[2]);
  }
  return out;
}

/** The declared value of `prop` across every rule for `selector` — last wins, as the cascade does. */
function declared(selector, prop, source = css()) {
  const all = bodies(selector, source);
  expect(all.length, `no rule found for "${selector}"`).toBeGreaterThan(0);
  let value = null;
  for (const body of all) {
    const m = new RegExp(`(?:^|;)\\s*${escapeRe(prop)}\\s*:\\s*([^;]+)`, 'm').exec(body);
    if (m) value = m[1].trim();
  }
  return value;
}

/** The CSS inside `@media (max-width: 639px) { … }` blocks, concatenated. */
function phoneMedia() {
  const source = css();
  const out = [];
  const re = /@media \(max-width: 639px\)\s*\{/g;
  while (re.exec(source) !== null) {
    let depth = 1;
    let i = re.lastIndex;
    while (i < source.length && depth > 0) {
      if (source[i] === '{') depth += 1;
      if (source[i] === '}') depth -= 1;
      i += 1;
    }
    out.push(source.slice(re.lastIndex, i - 1));
  }
  return out.join('\n');
}

describe('the rating block — tokens', () => {
  it('names the three colours the stylesheet lacked, in the STATIC theme block (nothing scanned references them)', () => {
    const source = css();
    // The static block starts at `@theme static {` and ends at its closing brace.
    const start = source.indexOf('@theme static {');
    expect(start).toBeGreaterThan(-1);
    let depth = 0;
    let end = start;
    for (let i = source.indexOf('{', start); i < source.length; i += 1) {
      if (source[i] === '{') depth += 1;
      if (source[i] === '}') { depth -= 1; if (depth === 0) { end = i; break; } }
    }
    const block = source.slice(start, end);
    expect(block).toMatch(/--color-star:\s*#E5A00D/i);
    expect(block).toMatch(/--color-star-off:\s*#4A3A2E/i);
    expect(block).toMatch(/--color-star-selected-bg:\s*rgba\(229,\s*160,\s*13,\s*0\.12\)/);
  });

  it('draws every colour from a token — no raw hex, no rgba literal — in the block\'s own rules', () => {
    const selectors = [
      '.wf-rate', '.wf-rate-q', '.wf-rate-q b', '.wf-rate-meta', '.wf-rate-grid', '.wf-rate-star',
      '.wf-rate-glyph', '.wf-rate-n', '.wf-rate-star:hover', '.wf-rate-star[data-selected]',
      '.wf-rate-star[data-selected] .wf-rate-glyph', '.wf-rate-ns', '.wf-rate-ns:hover',
      '.wf-rate-ns[data-selected]', '.wf-rate-done', '.wf-rate-you', '.wf-rate-glyphs',
      '.wf-rate-on', '.wf-rate-off', '.wf-rate-numeral', '.wf-rate-change', '.wf-rate-change:hover',
      '.wf-rate-status', '.wf-rate-note', '.wf-rate-status i',
    ];
    for (const selector of selectors) {
      expect(bodies(selector).length, `no rule for ${selector}`).toBeGreaterThan(0);
      for (const body of bodies(selector)) {
        expect(body, `${selector} carries a raw colour`).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i);
      }
    }
  });

  it('uses the spec\'s ink, border and surface tokens', () => {
    expect(declared('.wf-rate', 'border-top')).toBe('1px solid var(--color-plex-border)');
    expect(declared('.wf-rate-star', 'background')).toBe('var(--color-plex-panel)');
    expect(declared('.wf-rate-star', 'border')).toBe('1px solid var(--color-plex-border-light)');
    expect(declared('.wf-rate-star:hover', 'border-color')).toBe('var(--color-plex-text-muted)');
    expect(declared('.wf-rate-ns', 'border')).toBe('1px solid var(--color-plex-border)');
    expect(declared('.wf-rate-change', 'border')).toBe('1px solid var(--color-plex-border-light)');
    expect(declared('.wf-rate-status i', 'background')).toBe('var(--color-verdict-marginal)');
    expect(declared('.wf-rate-on', 'color')).toBe('var(--color-star)');
    expect(declared('.wf-rate-off', 'color')).toBe('var(--color-star-off)');
  });
});

describe('the rating block — sizes (spec §3)', () => {
  it('is a five-column grid with 5px gaps and shrinkable columns', () => {
    expect(declared('.wf-rate-grid', 'display')).toBe('grid');
    expect(declared('.wf-rate-grid', 'grid-template-columns')).toBe('repeat(5, minmax(0, 1fr))');
    expect(declared('.wf-rate-grid', 'gap')).toBe('5px');
  });

  it('gives a star a 44px target, a 9px radius and a numeral over a glyph', () => {
    expect(declared('.wf-rate-star', 'min-height')).toBe('44px');
    expect(declared('.wf-rate-star', 'border-radius')).toBe('9px');
    expect(declared('.wf-rate-star', 'flex-direction')).toBe('column');
    expect(declared('.wf-rate-glyph', 'font-size')).toBe('16px');
    expect(declared('.wf-rate-n', 'font-size')).toBe('10.5px');
    expect(declared('.wf-rate-n', 'font-weight')).toBe('600');
  });

  it('gives "Didn\'t see it" and Change 40px targets', () => {
    expect(declared('.wf-rate-ns', 'min-height')).toBe('40px');
    expect(declared('.wf-rate-ns', 'font-size')).toBe('12.5px');
    expect(declared('.wf-rate-ns', 'border-radius')).toBe('9px');
    expect(declared('.wf-rate-change', 'min-height')).toBe('40px');
    expect(declared('.wf-rate-change', 'padding')).toBe('0 12px');
    expect(declared('.wf-rate-change', 'border-radius')).toBe('8px');
  });

  it('lays the container out as the spec does: 9px under the rule, a 7px column', () => {
    expect(declared('.wf-rate', 'padding-top')).toBe('9px');
    expect(declared('.wf-rate', 'display')).toBe('flex');
    expect(declared('.wf-rate', 'flex-direction')).toBe('column');
    expect(declared('.wf-rate', 'gap')).toBe('7px');
  });

  it('sets the meta in 9.5px mono, right-aligned and shrinkable (so it wraps instead of pushing the question)', () => {
    expect(declared('.wf-rate-meta', 'font-family')).toBe('var(--font-mono)');
    expect(declared('.wf-rate-meta', 'font-size')).toBe('9.5px');
    expect(declared('.wf-rate-meta', 'text-align')).toBe('right');
    expect(declared('.wf-rate-meta', 'min-width')).toBe('0');
    expect(declared('.wf-rate-q b', 'white-space')).toBe('nowrap');
  });

  it('draws the 8px amber dot and nothing else amber', () => {
    expect(declared('.wf-rate-status i', 'width')).toBe('8px');
    expect(declared('.wf-rate-status i', 'height')).toBe('8px');
    expect(declared('.wf-rate-status i', 'border-radius')).toBe('50%');
    const stylesheet = css();
    const amberUses = stylesheet.match(/\.wf-rate[^{]*\{[^}]*--color-verdict-marginal[^}]*\}/g) ?? [];
    expect(amberUses).toHaveLength(1);
  });

  it('closes the grid\'s gap to 4px on the phone, so a 266px card\'s cell reaches 44px (plan §7)', () => {
    const phone = phoneMedia();
    expect(declared('.wf-rate-grid', 'gap', phone)).toBe('4px');
  });
});

describe('the rating block — saving, selected and locked', () => {
  it('selected is gold-bordered with the tinted fill; the glyph goes gold', () => {
    expect(declared('.wf-rate-star[data-selected]', 'border-color')).toBe('var(--color-star)');
    expect(declared('.wf-rate-star[data-selected]', 'background')).toBe('var(--color-star-selected-bg)');
    expect(declared('.wf-rate-star[data-selected] .wf-rate-glyph', 'color')).toBe('var(--color-star)');
    expect(declared('.wf-rate-ns[data-selected]', 'border-color')).toBe('var(--color-star)');
    expect(declared('.wf-rate-ns[data-selected]', 'background')).toBe('var(--color-star-selected-bg)');
  });

  it('saving dims the grid and "Didn\'t see it" to .55', () => {
    expect(declared('.wf-rate-grid.is-saving', 'opacity')).toBe('0.55');
    expect(declared('.wf-rate-ns.is-saving', 'opacity')).toBe('0.55');
  });

  it('the Lite lock dims the STARS to .45 and gives "Didn\'t see it" no dimming at all (screenshot 08)', () => {
    expect(declared('.wf-rate-grid.is-locked', 'opacity')).toBe('0.45');
    // No rule dims the not-seen button for a lock — its opacity is never declared outside saving.
    const dimmingRules = css().match(/\.wf-rate-ns[^{,]*\{[^}]*opacity[^}]*\}/g) ?? [];
    expect(dimmingRules.every((rule) => rule.includes('is-saving'))).toBe(true);
  });

  it('never locks with pointer-events: none or cursor: not-allowed — a Lite tap must be able to answer', () => {
    for (const selector of ['.wf-rate-grid', '.wf-rate-grid.is-locked', '.wf-rate-star', '.wf-rate-ns']) {
      for (const body of bodies(selector)) {
        expect(body).not.toMatch(/pointer-events\s*:\s*none/);
      }
    }
  });

  it('collapses the status line when empty WITHOUT leaving the accessibility tree, and cancels the row gap', () => {
    // `display: none` would drop the live region from the tree, and text arriving in a region that was
    // not there a moment earlier is announced by almost nobody.
    expect(declared('.wf-rate-status:empty', 'display')).toBeNull();
    expect(declared('.wf-rate-status:empty', 'height')).toBe('0');
    expect(declared('.wf-rate-status:empty', 'overflow')).toBe('hidden');
    expect(declared('.wf-rate-status:empty', 'margin-top')).toBe('-7px');
    expect(declared('.wf-rate-status', 'gap')).toBe('8px');
    expect(declared('.wf-rate-status', 'font-size')).toBe('12px');
  });
});

describe('the rating block — high contrast and ink', () => {
  it('marks the selected choice under forced-colors with a system colour and a heavier border', () => {
    const forced = css().slice(css().indexOf('@media (forced-colors: active) {\n  .wf-rate-star[data-selected]'));
    expect(forced).toMatch(/\.wf-rate-star\[data-selected\],\s*\.wf-rate-ns\[data-selected\]\s*\{[^}]*border-color:\s*Highlight/);
    expect(forced).toMatch(/border-width:\s*3px/);
  });

  it('puts the Lite line — the only explanation for the dimmed controls — on the secondary ink', () => {
    expect(declared('.wf-rate-note', 'color')).toBe('var(--color-plex-text-secondary)');
    expect(declared('.wf-rate-meta', 'color')).toBe('var(--color-plex-text-muted)');
  });
});

describe('the rating block — focus', () => {
  it('rings every control INSET: `.wf-callout-body` scrolls, so an outset ring would be clipped at the first and last column', () => {
    for (const selector of ['.wf-rate-star:focus-visible', '.wf-rate-ns:focus-visible', '.wf-rate-change:focus-visible']) {
      expect(declared(selector, 'outline')).toBe('2px solid var(--color-plex-gold)');
      expect(declared(selector, 'outline-offset')).toBe('-2px');
    }
  });
});
