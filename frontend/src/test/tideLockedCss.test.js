import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The tide lock's stylesheet rules, read as text (jsdom applies no stylesheet, so a component test
 * cannot see them). Comments are stripped so prose naming a selector can never satisfy a check.
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of the first rule whose selector list has `selector` as one entry, or null. */
function ruleBody(selector) {
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  let m = rule.exec(css);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) return m[2];
    m = rule.exec(css);
  }
  return null;
}

describe('tide lock CSS pins', () => {
  it('a disabled segment button carries no opacity of its own, so .wf-lens-locked cannot double-dim', () => {
    const body = ruleBody('.wf-seg-btn:disabled');
    expect(body).not.toBeNull();
    expect(body).not.toMatch(/opacity/);
  });

  it('the teaser pill and the spark never shrink, and the kicker gives way first', () => {
    expect(ruleBody('.wf-tide-strip-locked > .wf-lens-pro')).toMatch(/flex:\s*none/);
    expect(ruleBody('.wf-tide-strip-spark')).toMatch(/flex:\s*none/);
    const kicker = ruleBody('.wf-tide-strip-locked > .wf-tide-strip-kicker');
    expect(kicker).toMatch(/min-width:\s*0/);
    expect(kicker).toMatch(/overflow:\s*hidden/);
    expect(kicker).toMatch(/text-overflow:\s*ellipsis/);
  });

  it('the spark is softened: opacity .55 and a 1.2px blur', () => {
    const body = ruleBody('.wf-tide-strip-spark');
    expect(body).toMatch(/opacity:\s*\.55/);
    expect(body).toMatch(/blur\(1\.2px\)/);
  });
});

describe('tide upsell CSS pins (F2)', () => {
  it('the zero-height anchor cancels its flex gap so opening does not shift the stack', () => {
    const body = ruleBody('.wf-tide-upsell-anchor');
    expect(body).toMatch(/height:\s*0/);
    expect(body).toMatch(/margin-bottom:\s*-6px/);
  });

  it('the open teaser keeps a visible border in forced colours', () => {
    expect(css).toMatch(/forced-colors:\s*active\)\s*\{\s*\.wf-tide-strip-locked\[aria-expanded="true"\]\s*\{\s*border-color:\s*Highlight/);
  });
});
