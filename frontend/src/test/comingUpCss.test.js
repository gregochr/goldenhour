import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The Coming up stylesheet, read as text, for the one thing jsdom cannot measure: whether the
 * day-chip row fits a phone. A bare `1fr` track is `minmax(auto, 1fr)`, so a card holding
 * unwrappable content grew its column to the content's min-content and overhung the entry, where
 * `.wf-cu`'s `overflow: hidden` clipped the last chip (measured in Chromium at 390px and 360px).
 * The pins read `index.css` itself with comments stripped, so prose naming a selector cannot satisfy
 * or fail a rule check. The real fit is a browser check.
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '');

/** Every rule body whose selector list names `selector` exactly, in source order. */
function ruleBodies(selector) {
  const bodies = [];
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  let m = rule.exec(css);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) bodies.push(m[2]);
    m = rule.exec(css);
  }
  return bodies;
}

describe('the Coming up entry grid', () => {
  it('uses minmax(0, 1fr) for the card track in the base rule and both phone overrides', () => {
    const bodies = ruleBodies('.wf-cu-ent').filter((b) => b.includes('grid-template-columns'));
    expect(bodies).toHaveLength(3);
    for (const body of bodies) {
      expect(body).toMatch(/grid-template-columns:\s*\d+px minmax\(0, 1fr\)/);
      expect(body).not.toMatch(/\d+px 1fr/);
    }
  });

  it('lets the card shrink below its content, so the track is the only thing that sizes it', () => {
    const body = ruleBodies('.wf-cu-card').find((b) => b.includes('display: block'));
    expect(body).toMatch(/min-width:\s*0/);
  });
});

describe('the day-chip row', () => {
  it('wraps, so a long run folds onto a second line instead of forcing the card wider', () => {
    const [body] = ruleBodies('.wf-cu-days-group');
    expect(body).toMatch(/flex-wrap:\s*wrap/);
    expect(body).toMatch(/row-gap:\s*6px/);
  });

  it('keeps a dimmed peak’s muted number strictly more specific than the peak’s gold', () => {
    const muted = css.match(/\.wf-cu-day:disabled\[data-peak\] \.wf-cu-day-dn/);
    expect(muted).not.toBeNull();
  });
});
