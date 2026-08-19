import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * The masthead's colour literals, pinned against the stylesheet they duplicate.
 *
 * <p>Three of `RULE_COLOURS`' entries are byte-copies of live design tokens, and the component's own
 * doc explains why they stay literals: both tokens sit in index.css's PRUNABLE `@theme` block, and a
 * `linear-gradient` containing one unresolved colour is invalid in its entirety — so
 * `var(--color-plex-coral)` inside the gradient would trade a cosmetic drift for a vanished rule.
 *
 * <p>What that leaves is the drift, which is what this file is for. It reads the stylesheet as text
 * rather than through the cascade because jsdom does not resolve `var()`, and because the failure
 * being caught is a source edit: someone retunes `--color-plex-coral` — a knob the token's own
 * comment already frets about — and the kicker and nudge link move while the sunrise and sunset
 * stops two lines beneath them do not. One band, two corals, and nothing else in the suite notices.
 */

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

const cssToken = (css, name) => {
  const match = new RegExp(`--${name}:\\s*(#[0-9A-Fa-f]{3,8})\\s*;`).exec(css);
  if (!match) throw new Error(`--${name} is not declared as a hex literal in index.css`);
  return match[1].toUpperCase();
};

const jsLiteral = (src, key) => {
  const match = new RegExp(`${key}:\\s*'(#[0-9A-Fa-f]{3,8})'`).exec(src);
  if (!match) throw new Error(`${key} is not a hex literal in MastheadLight.jsx`);
  return match[1].toUpperCase();
};

describe('masthead rule colours vs the tokens they copy', () => {
  const css = read('../index.css');
  const component = read('../components/shared/MastheadLight.jsx');

  it.each([
    ['SUNRISE', 'color-plex-coral'],
    ['SUNSET', 'color-plex-coral'],
    ['SOLAR_NOON', 'color-plex-text'],
  ])('keeps the %s stop equal to --%s', (stop, token) => {
    expect(jsLiteral(component, stop)).toBe(cssToken(css, token));
  });

  it('keeps the amber constant and its Tailwind hover class on one value', () => {
    // Tailwind's scanner reads raw source and cannot follow a template literal, so the hover class
    // has to spell the hex out. The duplication is forced by the toolchain; this is what stops it
    // becoming a divergence — change GOLDEN alone and the link hovers to the old amber.
    const golden = /const GOLDEN = '(#[0-9A-Fa-f]{6})'/.exec(component)[1].toUpperCase();
    const hover = /const GOLDEN_HOVER = 'hover:text-\[(#[0-9A-Fa-f]{6})\]'/.exec(component)[1].toUpperCase();

    expect(hover).toBe(golden);
  });

  it('reads a token that really is declared, so the parse cannot silently pass', () => {
    // If the regexes stopped matching, every assertion above would throw rather than pass — but a
    // future index.css that declares the token via another token would parse to something that is
    // not a hex and this would catch it before the comparisons do.
    expect(cssToken(css, 'color-plex-coral')).toMatch(/^#[0-9A-F]{6}$/);
  });
});
