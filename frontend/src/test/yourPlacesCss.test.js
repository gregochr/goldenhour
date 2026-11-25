import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * Your places on a phone (screen-spec.md section 4: one 34px line), asserted as text because jsdom has no layout and
 * the stylesheet is not loaded. The heading is visually hidden on a phone only, and stays a real heading.
 */
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');
const css = read('../index.css');

/** The body of the phone media query that holds this selector. */
const phoneRule = (selector) => {
  const at = css.indexOf(selector.includes('{') ? selector : `${selector} {`);
  expect(at, `${selector} exists`).toBeGreaterThan(-1);
  const open = css.lastIndexOf('@media', at);
  expect(css.slice(open, at)).toContain('(max-width: 639px)');
  return css.slice(at, css.indexOf('}', at));
};

describe('Your places heading on a phone', () => {
  it('is visually hidden by the sr-only box, in the phone query only', () => {
    const body = phoneRule('.wf-yp > .wf-fav-section-h');
    for (const declaration of ['position: absolute', 'width: 1px', 'height: 1px', 'overflow: hidden', 'clip: rect(0, 0, 0, 0)']) {
      expect(body).toContain(declaration);
    }
    expect(body).not.toContain('display: none');
  });

  it('leaves the desktop heading alone: no rule outside the phone query touches it', () => {
    const matches = [...css.matchAll(/\.wf-yp > \.wf-fav-section-h/g)];
    expect(matches).toHaveLength(1);
  });

  it('trims the row to its chip plus a 6px gap on a phone', () => {
    expect(phoneRule('.wf-yp-row { scroll-snap-type: x mandatory;').length).toBeGreaterThan(0);
    expect(css).toContain('.wf-yp-row { scroll-snap-type: x mandatory; padding: 0 1px 6px; }');
  });
});
