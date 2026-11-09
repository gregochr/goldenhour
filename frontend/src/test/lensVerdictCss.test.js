import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * CSS-text pins for the lens bar's verdict line (lens-verdict-line-plan.md). jsdom evaluates no
 * stylesheet, so what the component sets (a modifier class, a button class) is only half the
 * behaviour; these read `index.css` for the rules that give those classes their meaning, in the same
 * way `windowFirstPaneStatusGap.test.js` does for the pane gap.
 */

const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');

/** The declarations of the first rule that starts with this exact selector line. */
const ruleBody = (selector) => {
  const at = css.indexOf(`\n${selector} {`);
  expect(at, `${selector} must exist`).toBeGreaterThan(-1);
  return css.slice(css.indexOf('{', at), css.indexOf('}', at) + 1);
};

describe('the verdict line stylesheet', () => {
  it('paints the lead amber, only on the verdict modifiers, after the ink rule it overrides', () => {
    const ink = css.indexOf('\n.wf-lens-count b {');
    const amber = css.indexOf('\n.wf-lens-count--verdict b,\n.wf-lens-res--verdict b {');
    expect(ink).toBeGreaterThan(-1);
    expect(amber).toBeGreaterThan(ink);
    expect(ruleBody('.wf-lens-count--verdict b,\n.wf-lens-res--verdict b')).toContain('var(--color-badge-maybe)');
    // The ordinary count keeps its ink.
    expect(ruleBody('.wf-lens-count b')).toContain('var(--color-plex-text)');
  });

  it('never lets the button shrink or wrap, and gives it the 24px target floor at every width', () => {
    const body = ruleBody('.wf-lens-verdict-act');
    expect(body).toContain('flex: none');
    expect(body).toContain('white-space: nowrap');
    expect(body).toContain('display: inline-flex');
    expect(body).toContain('min-height: 24px');
    expect(body).toContain('font-size: inherit');
  });

  it('zeroes scroll-margin-top for the button, which lives INSIDE the sticky bar it would otherwise reserve against', () => {
    expect(css).toContain('.wf-lens .wf-lens-verdict-act { scroll-margin-top: 0; }');
  });

  it('puts the desktop verdict on its own full-width row below 1024px, wrapping, and keeps the ellipsis above', () => {
    const at = css.indexOf('@media (max-width: 1023px) {\n  .wf-lens-res--verdict {');
    expect(at).toBeGreaterThan(-1);
    const block = css.slice(at, css.indexOf('\n}\n', at));
    expect(block).toContain('flex: 1 1 100%');
    expect(block).toContain('justify-content: flex-start');
    expect(block).toContain('white-space: normal');
    // The base ellipsis rule is untouched, so >=1024px still ellipsises.
    expect(ruleBody('.wf-lens-res > span')).toContain('text-overflow: ellipsis');
  });

  it('keeps the phone 26px floor, declared after the verdict base rule so it wins at equal specificity', () => {
    const base = css.indexOf('\n.wf-lens-verdict-act {');
    const phone = css.indexOf('  .wf-clash-act { min-height: 26px; }');
    expect(base).toBeGreaterThan(-1);
    expect(phone).toBeGreaterThan(base);
  });
});
