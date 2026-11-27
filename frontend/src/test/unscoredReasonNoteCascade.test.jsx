import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The reason note replaces the "Not scored" word (unscored-reason-card-plan.md C2). jsdom evaluates no layout, so this
 * pins the REAL rule in `index.css`: it must hide the verdict word only when it is AWAITING and only when a note sits
 * beside it. Losing either half would hide "Poor" or "Worth it" over a reason note, or leave "Not scored" crushing the
 * note to nothing at every phone width (measured: 136px wanted, 70 to 175px available).
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');

function ruleFor(selectorFragment) {
  const at = css.indexOf(selectorFragment);
  if (at < 0) return null;
  const open = css.indexOf('{', at);
  return { selector: css.slice(css.lastIndexOf('}', at) + 1, open).trim(), body: css.slice(open + 1, css.indexOf('}', open)) };
}

describe('the reason note replaces the Not scored word', () => {
  it('hides only the AWAITING verdict word, and only beside a note', () => {
    const rule = ruleFor('.wf-hc-pv:has(.wf-hc-note) .wf-hc-vd[data-verdict="AWAITING"]');
    expect(rule).not.toBeNull();
    expect(rule.selector.endsWith('.wf-hc-pv:has(.wf-hc-note) .wf-hc-vd[data-verdict="AWAITING"]')).toBe(true);
    expect(rule.body).toMatch(/display:\s*none/);
  });

  it('is not an unconditional rule on any verdict word', () => {
    expect(css).not.toMatch(/\.wf-hc-vd\s*\{[^}]*display:\s*none/);
    expect(css).not.toMatch(/\.wf-hc-vd\[data-verdict="(STAND_DOWN|WORTH_IT|MAYBE|UNRATED)"\][^{]*\{[^}]*display:\s*none/);
  });
});
