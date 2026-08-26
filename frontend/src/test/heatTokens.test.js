import { describe, it, expect, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { setMode, rampHex } from '../utils/scoreRamp.js';

/**
 * The `--color-heat-1..5` tokens against the ramp they sample, pinned as text rather than through
 * the cascade (jsdom does not resolve `var()`, and these live in index.css's STATIC block for
 * exactly the reason this file's own comment gives — see it before "fixing" a mismatch here by
 * moving the tokens back to the plain block).
 *
 * <p>2★ and 4★ are interpolated points, not `STOPS_TEMP` entries — `#4C6677` and `#DF6229` do not
 * appear in that list — so the only honest way to verify them is to ask the ramp for the colour it
 * actually renders at those scores, not to grep the stop table.
 */
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

const cssToken = (css, name) => {
  const match = new RegExp(`--${name}:\\s*(#[0-9A-Fa-f]{3,8})\\s*;`).exec(css);
  if (!match) throw new Error(`--${name} is not declared as a hex literal in index.css`);
  return match[1].toUpperCase();
};

describe('--color-heat-1..5 vs the temperature ramp they sample', () => {
  afterEach(() => {
    setMode('verdict');
  });

  const css = read('../index.css');

  it.each([1, 2, 3, 4, 5])('matches rampHex(%d) in temp mode', (score) => {
    setMode('temp');
    expect(cssToken(css, `color-heat-${score}`)).toBe(rampHex(score));
  });
});
