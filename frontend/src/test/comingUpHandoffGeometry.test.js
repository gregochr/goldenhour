import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * `.wf-cu-handoff` — the Coming up tab's "Now — …  On Plan →" row — must not be wider than the
 * panel that clips it.
 *
 * <p>The row is a direct child of `.wf-cu`, which carries no padding of its own, so it funds its
 * own side inset with a horizontal `margin`. It also needs an explicit `width`, because a
 * `<button>` shrink-wraps even as a block-level flex container (`width: auto` measured 39.6px
 * against a 388px panel) — which is the trap: `width: 100%` resolves against the CONTAINING BLOCK,
 * so the margins are added on top of it and the margin box comes out `2 × inset` wider than
 * `.wf-cu`. `.wf-cu` is `overflow: hidden`, so the excess is not merely off-centre — it is clipped,
 * taking the dashed border's whole right edge with it, and the row reads as running off the screen.
 *
 * <p>This is asserted as TEXT, not through the cascade. `vite.config.js` sets `css: false` and
 * jsdom has no layout engine, so nothing here can measure a box; `heatTokens.test.js` reads
 * `index.css` the same way and for the same reason. What this pins is the RELATIONSHIP — that the
 * width compensates for the margin, both spelling it with one custom property so the two halves
 * cannot drift. What it cannot pin is the resulting geometry: that was measured in Chromium (a
 * symmetric 14px inset and zero overflow at 320/390/430/768px) and belongs to the browser check.
 */
// The path goes through a parameter, never a literal: Vite statically rewrites
// `new URL('<literal>', import.meta.url)` into an asset reference, which is not a file URL and
// makes `fileURLToPath` throw. `heatTokens.test.js` reads the same stylesheet the same way.
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

const ruleBody = (css, selector) => {
  const start = css.indexOf(`${selector} {`);
  if (start === -1) throw new Error(`${selector} is not declared in index.css`);
  return css.slice(start, css.indexOf('}', start));
};

const declaration = (body, prop) => {
  const match = new RegExp(`(?:^|;|\\*/)\\s*${prop}:\\s*([^;]+);`, 'm').exec(body);
  if (!match) throw new Error(`${prop} is not declared`);
  return match[1].trim();
};

describe('.wf-cu-handoff cannot overflow the panel that clips it', () => {
  const body = ruleBody(read('../index.css'), '.wf-cu-handoff');

  it('takes its side inset from one custom property', () => {
    expect(body).toContain('--wf-cu-handoff-inset: 14px;');
  });

  it('spells the horizontal margin with that property, not a second literal', () => {
    // `margin: <top> <sides> <bottom>` — the middle term is the one that widens the margin box.
    expect(declaration(body, 'margin').split(/\s+/)[1]).toBe('var(--wf-cu-handoff-inset)');
  });

  it('subtracts both margins from the width instead of asking for the whole panel', () => {
    const width = declaration(body, 'width');
    expect(width).not.toBe('100%');
    expect(width).toBe('calc(100% - var(--wf-cu-handoff-inset) * 2)');
  });
});
