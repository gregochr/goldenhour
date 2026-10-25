import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The window pill's "is better" line, asserted against the real `index.css`
 * (`docs/engineering/map-landing-nocard-plan.md` P2, D1).
 *
 * <p>jsdom lays nothing out, so this pins the MECHANISM the browser measurements rest on (a 504px control
 * the line is paid out of, a one-frame join with the pill, the tuning that keeps the verdict word on the
 * pill), plus one absence: ⚠️ <b>there is no `@media` rule hiding the line</b>. The owner chose "show only
 * if it fits" (2026-10-25): the clip was content-driven at every width and the Map frame is not the
 * viewport (`<main>`'s `sm:px-4`, the 1080px cap, the Ask dock), so `WindowControl` withholds the line by
 * measuring (`WindowControlBetter.test.jsx`), and a width query would only paper over that.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** `index.css` with comments stripped — they carry both braces and the class names. */
function readCss() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/** The declarations of the rule whose selector is exactly {@code selector} (first match, any depth). */
function declarations(css, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`(?:^|[}\\s;])${escaped}\\s*\\{([^}]*)\\}`).exec(css);
  expect(match, `no rule for ${selector}`).not.toBeNull();
  return match[1].replace(/\s+/g, ' ').trim();
}

/** Every `@media … { … }` block, nested rules included, by brace depth. */
function mediaBlocks(css) {
  const out = [];
  let from = 0;
  for (;;) {
    const at = css.indexOf('@media', from);
    if (at < 0) return out;
    const open = css.indexOf('{', at);
    let depth = 0;
    let end = open;
    for (; end < css.length; end += 1) {
      if (css[end] === '{') depth += 1;
      else if (css[end] === '}') {
        depth -= 1;
        if (depth === 0) break;
      }
    }
    out.push(css.slice(at, end + 1));
    from = end + 1;
  }
}

describe('the "is better" line — one frame with the pill', () => {
  const css = readCss();

  it('is a flex sibling that stretches to the control\'s row, never shrinks, and cancels the control\'s gap', () => {
    const body = declarations(css, '.wf-win-better');
    expect(body).toContain('display: flex');
    expect(body).toContain('align-self: stretch');
    expect(body).toContain('flex-shrink: 0');
    expect(body).toContain('margin-left: -4px');
  });

  it('sets the design\'s type: mono 10px, .02em, nowrap, the Worth-it ink, a pointer', () => {
    const body = declarations(css, '.wf-win-better');
    expect(body).toContain('font-family: var(--font-mono)');
    expect(body).toContain('font-size: 10px');
    expect(body).toContain('letter-spacing: 0.02em');
    expect(body).toContain('white-space: nowrap');
    expect(body).toContain('color: var(--color-badge-go)');
    expect(body).toContain('cursor: pointer');
  });

  it('is the right-hand half of the frame: its own separator on the left, rounded corners on the right', () => {
    const body = declarations(css, '.wf-win-better');
    expect(body).toContain('border-left: 1px solid var(--color-plex-border)');
    expect(body).toContain('border-radius: 0 10px 10px 0');
    expect(body).toContain('padding: 0 10px 0 8px');
  });

  it('hovers to the lighter ink with an underline and keeps the pill\'s focus ring', () => {
    expect(declarations(css, '.wf-win-better:hover')).toMatch(/color: var\(--color-badge-go-hover\).*text-decoration: underline/);
    expect(declarations(css, '.wf-win-better:focus-visible')).toContain('outline: 2px solid var(--color-plex-gold)');
    expect(declarations(css, '.wf-win-pill:focus-visible')).toContain('outline: 2px solid var(--color-plex-gold)');
    expect(css).toContain('--color-badge-go-hover: #CFE3C2');
  });

  it('wears the active window\'s tint, without the pill\'s inset bar', () => {
    for (const tier of ['WORTH_IT', 'MAYBE', 'STAND_DOWN']) {
      const line = declarations(css, `.wf-win-better[data-tier="${tier}"]`);
      expect(line).toContain('--wf-better-tint: color-mix(');
      expect(line).not.toContain('box-shadow');
      expect(declarations(css, `.wf-win-pill[data-tier="${tier}"]`)).toContain('box-shadow: inset 3px 0 0');
    }
  });

  it('tints top, right and bottom only: no tier rule touches the left separator', () => {
    const tinted = declarations(css, '.wf-win-better[data-tier]');
    expect(tinted).toContain('border-top-color: var(--wf-better-tint)');
    expect(tinted).toContain('border-right-color: var(--wf-better-tint)');
    expect(tinted).toContain('border-bottom-color: var(--wf-better-tint)');
    expect(tinted).not.toMatch(/border-left|border-color/);
    for (const tier of ['WORTH_IT', 'MAYBE', 'STAND_DOWN']) {
      expect(declarations(css, `.wf-win-better[data-tier="${tier}"]`)).not.toMatch(/border/);
    }
    expect(declarations(css, '.wf-win-better')).toContain('border-left: 1px solid var(--color-plex-border)');
  });

  it('squares the pill\'s right edge while the line shows, and withdraws the medallion words visually', () => {
    const pill = declarations(css, '.wf-win-control[data-better] .wf-win-pill');
    expect(pill).toContain('border-top-right-radius: 0');
    expect(pill).toContain('border-bottom-right-radius: 0');
    expect(pill).toContain('border-right-width: 0');
    const words = declarations(css, '.wf-win-control[data-better] .wf-win-pick-words');
    expect(words).toContain('clip-path: inset(50%)');
    expect(words).toContain('position: absolute');
    expect(words).not.toContain('display: none');
  });
});

describe('the pill\'s tuning while the line shows', () => {
  const css = readCss();

  it('takes the ≤389px recipe: gap 4, side padding 8', () => {
    const pill = declarations(css, '.wf-win-control[data-better] .wf-win-pill');
    expect(pill).toContain('gap: 4px');
    expect(pill).toContain('padding-left: 8px');
    expect(pill).toContain('padding-right: 8px');
  });

  it('drops the verdict cell\'s separator and lets the region line ellipsise at the squeezed cell', () => {
    const cell = declarations(css, '.wf-win-control[data-better] .wf-win-verdict');
    expect(cell).toContain('padding-left: 0');
    expect(cell).toContain('border-left: none');
    expect(declarations(css, '.wf-win-control[data-better] .wf-win-verdict-region')).toContain('max-width: 100%');
  });
});

describe('no width query hides the line', () => {
  const css = readCss();

  it('has no @media block that names the line (the fit is measured, not guessed)', () => {
    const naming = mediaBlocks(css).filter((block) => block.includes('wf-win-better')
      || block.includes('data-better') || block.includes('wf-win-better-why'));
    expect(naming).toEqual([]);
  });

  it('has no rule turning the line off with display: none anywhere', () => {
    expect(css).not.toMatch(/\.wf-win-better[^{]*\{[^}]*display:\s*none/);
  });

  it('has no .wf-shell--docked rule for it either (the fit check covers the Ask dock)', () => {
    expect(css).not.toMatch(/wf-shell--docked[^{]*wf-win-better/);
  });
});
