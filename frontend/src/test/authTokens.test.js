import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of a top-level block, from its opening line to its matching brace. */
function blockBody(opener) {
  const start = css.indexOf(opener);
  expect(start, `${opener} must still be findable in index.css`).toBeGreaterThan(-1);
  const open = css.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < css.length; i += 1) {
    if (css[i] === '{') depth += 1;
    if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) return css.slice(open + 1, i);
    }
  }
  throw new Error(`${opener} is never closed`);
}

const channel = (v) => {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};
const luminance = (hex) => {
  const n = parseInt(hex.slice(1), 16);
  return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255);
};
/** WCAG 2.x contrast ratio of two #rrggbb colours. */
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

const SURFACE = '#221A15';
const PAGE = '#181210';

describe('auth status tokens', () => {
  const staticBlock = blockBody('@theme static');

  it('--color-plex-error is declared inside the `@theme static` block, with its exact value', () => {
    // Outside it, Tailwind 4 prunes a theme variable no scanned file names, and the first thing
    // to name it would be a class the scanner cannot see (.field-error is an @apply).
    expect(staticBlock).toMatch(/--color-plex-error:\s*#F07A62;/i);
  });

  it('--color-plex-ok is declared inside the `@theme static` block, with its exact value', () => {
    expect(staticBlock).toMatch(/--color-plex-ok:\s*#8AAE72;/i);
  });

  it('neither token is also declared in the plain @theme block (one home, so one place to prune)', () => {
    const plain = blockBody('@theme {');
    expect(plain).not.toMatch(/--color-plex-error/);
    expect(plain).not.toMatch(/--color-plex-ok/);
  });

  it('--color-plex-ok is a separate declaration from --color-verdict-go, though they share a value', () => {
    // Verdict colours mean a forecast verdict; a met password rule is not one. The two must stay
    // independently retunable, so ok must not be written as var(--color-verdict-go).
    expect(staticBlock).not.toMatch(/--color-plex-ok:\s*var\(/);
    expect(css).toMatch(/--color-verdict-go:\s*#8AAE72;/i);
  });

  it('clears 4.5:1 for 12px text on the card and the page background (figures from the plan)', () => {
    const errorOnSurface = contrast('#F07A62', SURFACE);
    const errorOnPage = contrast('#F07A62', PAGE);
    const okOnSurface = contrast('#8AAE72', SURFACE);

    expect(errorOnSurface).toBeGreaterThanOrEqual(4.5);
    expect(errorOnPage).toBeGreaterThanOrEqual(4.5);
    expect(okOnSurface).toBeGreaterThanOrEqual(4.5);
    // The plan recorded 6.25, 6.77 and 6.83; a retuned token that quietly loses margin shows here.
    expect(errorOnSurface).toBeCloseTo(6.25, 1);
    expect(errorOnPage).toBeCloseTo(6.77, 1);
    expect(okOnSurface).toBeCloseTo(6.83, 1);
  });

  it('the contrast function itself is right: black on white is 21:1, a colour on itself is 1:1', () => {
    expect(contrast('#000000', '#FFFFFF')).toBeCloseTo(21, 5);
    expect(contrast('#F07A62', '#F07A62')).toBeCloseTo(1, 5);
  });

  it('the surfaces measured against are the live theme values', () => {
    expect(css).toMatch(/--color-plex-surface:\s*#221A15;/i);
    expect(css).toMatch(/--color-plex-bg:\s*#181210;/i);
  });
});
