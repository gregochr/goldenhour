/**
 * `.wf-rated-badge` in the real stylesheet (outcome-rating-plan.md §2 #6, O4b). jsdom applies no
 * stylesheet, so the rule is read from `index.css` — the same device `WindowControlEarlier.test.jsx`
 * uses. Pins the spec's numbers (the prototype's `.pin .mine`) and, separately, that the overhang
 * the label placer reserves (`BADGE_OVERHANG_X/Y` in `utils/mapLabels.js`) is the CSS's own — the two
 * are one fact written twice, and a change to one must fail here.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { BADGE_OVERHANG_X, BADGE_OVERHANG_Y } from '../utils/mapLabels.js';

const raw = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
const css = raw.replace(/\/\*[\s\S]*?\*\//g, '');

const bodyOf = (selector) => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\:]/g, '\\$&');
  const match = css.match(new RegExp(`(?:^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`));
  return match ? match[1] : null;
};
const decl = (body, prop) => {
  const m = body?.match(new RegExp(`(?:^|;|\\s)${prop}\\s*:\\s*([^;]+);`));
  return m ? m[1].trim() : null;
};

describe('.wf-rated-badge', () => {
  const body = bodyOf('.wf-rated-badge');

  it('exists', () => {
    expect(body).not.toBeNull();
  });

  it('is the prototype\'s .pin .mine: mono 9px / 700, gold on #0e0b09, 1px border, radius 6, padding 0 3px, line-height 14px', () => {
    expect(decl(body, 'position')).toBe('absolute');
    expect(decl(body, 'font-family')).toBe('var(--font-mono)');
    expect(decl(body, 'font-size')).toBe('9px');
    expect(decl(body, 'font-weight')).toBe('700');
    expect(decl(body, 'color')).toBe('var(--color-star)');
    expect(decl(body, 'background')).toBe('#0e0b09');
    expect(decl(body, 'border')).toBe('1px solid var(--color-plex-border-light)');
    expect(decl(body, 'border-radius')).toBe('6px');
    expect(decl(body, 'padding')).toBe('0 3px');
    expect(decl(body, 'line-height')).toBe('14px');
  });

  it('hangs 7px above and 9px beside its host — the overhang the label placer reserves', () => {
    expect(decl(body, 'top')).toBe(`-${BADGE_OVERHANG_Y}px`);
    expect(decl(body, 'right')).toBe(`-${BADGE_OVERHANG_X}px`);
  });

  it('never takes a press from its host button', () => {
    expect(decl(body, 'pointer-events')).toBe('none');
  });

  it('is not part of the Ask cascade: no data-ask / data-tide selector names it', () => {
    expect(css).not.toMatch(/\[data-(ask|tide)[^\]]*\][^{]*\.wf-rated-badge/);
    expect(css).not.toMatch(/\.wf-rated-badge[^{]*\[data-(ask|tide)/);
  });
});
