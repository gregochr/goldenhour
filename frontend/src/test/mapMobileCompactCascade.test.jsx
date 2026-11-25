import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, afterEach,
} from 'vitest';

/**
 * The phone masthead chrome (map-mobile-compact-plan.md C1, README §1–§3 and §6), asserted against the
 * REAL `index.css` with the slicer `mapPhoneChromeCascade.test.jsx` documents — a rule inside
 * `@media (max-width: 639px)` is lifted out with its wrapper stripped, which is "what wins once the
 * query matches". jsdom evaluates no media query, so the PHONE slice and the BASE slice (top-level
 * rules only) are injected separately: what the phone changes is the difference, and what desktop
 * keeps is the base alone.
 *
 * <p>(The phone tab bar's hit-area tests lived here until the tabs went, one-screen-plan.md O3.)
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

function sliceTopLevelBlocks(css) {
  const blocks = [];
  let depth = 0;
  let selectorStart = 0;
  let blockStart = -1;
  for (let i = 0; i < css.length; i += 1) {
    if (css[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        blocks.push({
          selector: css.slice(selectorStart, blockStart).trim(),
          bodyInner: css.slice(blockStart + 1, i),
        });
        selectorStart = i + 1;
      }
    }
  }
  return blocks;
}

/**
 * Rules whose selector contains any needle, in source order.
 *
 * @param {string[]} needles substrings, any one of which selects a rule
 * @param {'phone'|'base'} arm `base` keeps top-level rules only; `phone` adds the rules of every
 *        `@media (max-width: 639px)` block (wrapper stripped), at their source position
 */
function rules(needles, arm) {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH}`).toBe(true);
  const css = readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const out = [];
  const pick = (selector, body) => {
    if (needles.some((n) => selector.includes(n))) out.push(`${selector} {${body}}`);
  };
  for (const block of sliceTopLevelBlocks(css)) {
    if (block.selector.startsWith('@media')) {
      if (arm === 'phone' && block.selector.includes('(max-width: 639px)')) {
        sliceTopLevelBlocks(block.bodyInner).forEach((n) => pick(n.selector, n.bodyInner));
      }
    } else {
      pick(block.selector, block.bodyInner);
    }
  }
  return out.join('\n');
}

const cleanups = [];
function inject(slice) {
  const el = document.createElement('style');
  el.textContent = slice;
  document.head.appendChild(el);
  cleanups.push(() => el.remove());
}
afterEach(() => {
  while (cleanups.length) cleanups.pop()();
  document.body.innerHTML = '';
});

describe('the masthead line (README §1)', () => {
  it('is one 34px row with an 8px top inset, on the phone only', () => {
    inject(rules(['.wf-mast'], 'phone'));
    document.body.innerHTML = '<div class="wf-mast-row" id="r"></div>';
    expect(getComputedStyle(document.getElementById('r')).height).toBe('34px');
    // `padding: 8px var(--wf-gutter) 0`, read as text for the same reason as the tab bar's.
    expect(rules(['.wf-mast'], 'phone')).toMatch(/\.wf-mast \{ padding: 8px var\(--wf-gutter\) 0; \}/);
  });

  it('draws the ⋯ box at 30px with a 40px hit area, rows at 44px, a 210px popover', () => {
    const css = rules(['.wf-mast-menu'], 'phone');
    expect(css).toMatch(/\.wf-mast-menu-btn \{[^}]*width: 30px;[^}]*height: 30px;[^}]*border: 1px solid var\(--wf-mast-edge\);[^}]*border-radius: 8px/);
    // 30 + 5 on each side = 40.
    expect(css).toMatch(/\.wf-mast-menu-btn::before \{[^}]*inset: -5px/);
    expect(css).toMatch(/\.wf-mast-menu-row,\s*\.wf-mast-menu-status \{[^}]*min-height: 44px/);
    expect(css).toMatch(/\.wf-mast-menu-pop \{[^}]*width: 210px[^}]*background: #1E1712/);
  });

  it('hides the menu\'s status strip when the app\'s node renders nothing, and tones by data-status', () => {
    const css = rules(['.wf-mast-menu-status-wrap', '.wf-mast-dot-disc', '.wf-mast-status-word'], 'phone');
    expect(css).toMatch(/\.wf-mast-menu-status-wrap:empty \{ display: none; \}/);
    for (const s of ['UP', 'DEGRADED', 'DOWN']) {
      expect(css).toContain(`.wf-mast-dot-disc[data-status='${s}']`);
      expect(css).toContain(`.wf-mast-status-word[data-status='${s}']`);
    }
  });

  it('has no light-time rules at all: the phone masthead draws no times (screen-spec.md P4)', () => {
    const css = readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    expect(css).not.toContain('.wf-mast-times');
    expect(css).not.toContain('.wf-mast-time ');
    expect(css).not.toContain('.wf-mast-arrow');
  });

  it('does not draw the empty banner block above the masthead on a phone, and leaves it alone elsewhere', () => {
    expect(rules(['.wf-banners'], 'phone')).toMatch(/\.wf-banners:not\(:has\(:not\(:empty\)\)\) \{ display: none; \}/);
    expect(rules(['.wf-banners'], 'base')).toBe('');
  });

  it('draws ⌕ Find as a 30px box in the brand row and the nudge as a slim line', () => {
    const css = rules(['.wf-mast-find', '.wf-tick-phone'], 'phone');
    expect(css).toMatch(/\.wf-mast-find \{[^}]*height: 30px[^}]*border: 1px solid var\(--wf-mast-edge\)/);
    expect(css).toMatch(/\.wf-mast-find:focus-visible \{[^}]*box-shadow: inset/);
    expect(css).toMatch(/\.wf-tick-phone \{[^}]*padding: 2px 0 6px/);
  });

  it('draws the compact origin row at 36px with a 28px group and a 32×28 ⌂', () => {
    const css = rules(['.wf-tick[data-compact'], 'phone');
    expect(css).toMatch(/\.wf-tick\[data-compact='true'\] \{[^}]*height: 36px/);
    expect(css).toMatch(/\.wf-tick-group \{[^}]*height: 28px/);
    expect(css).toMatch(/\.wf-tick-home \{[^}]*min-width: 32px[^}]*height: 28px/);
  });

  it('draws the collapsed lens as one 36px line: the summary, no vertical padding, a zero-height spacer, phone only', () => {
    const css = rules(['.wf-lens-summary', '.wf-lens.wf-lens-collapsed', '.wf-lens-spacer'], 'phone');
    expect(css).toMatch(/\.wf-lens-summary \{[^}]*min-height: 35px/);
    expect(css).toMatch(/\.wf-lens\.wf-lens-collapsed \{ padding-top: 0; padding-bottom: 0; \}/);
    expect(css).toMatch(/\.wf-lens-spacer \{ height: 0; \}/);
    expect(css).toMatch(/\.wf-lens-summary\[hidden\] \{ display: none; \}/);
    expect(rules(['.wf-lens-summary', '.wf-lens-spacer', '.wf-lens-collapsed'], 'base')).toBe('');
  });

  it('keeps the lens body wrapper at EVERY width as display: contents, so desktop lays out as before and no chip remounts', () => {
    const base = rules(['.wf-lens-body'], 'base');
    expect(base).toMatch(/\.wf-lens-body \{ display: contents; \}/);
    expect(base).toMatch(/\.wf-lens-body\[hidden\] \{ display: none; \}/);
  });

  it('⚠️ keeps every phone-only rule out of the base stylesheet, so desktop and tablet cannot see it', () => {
    const base = rules(['.wf-mast-row', '.wf-mast-find', '.wf-tick-phone', '.wf-mast-dot', '.wf-tick[data-compact'], 'base');
    expect(base).toBe('');
    // …and the desktop masthead padding is still the base 14px.
    expect(rules(['.wf-mast'], 'base')).toMatch(/\.wf-mast \{[^}]*padding: 14px var\(--wf-gutter\) 0;/);
  });
});

describe('the ☰ menu is drawn at EVERY width (one-screen-plan.md D14)', () => {
  it('lives in the base stylesheet, not the phone media block: the button, the popover, the catcher and the rows', () => {
    const base = rules(['.wf-mast-menu'], 'base');
    ['.wf-mast-menu {', '.wf-mast-menu-btn {', '.wf-mast-menu-pop {', '.wf-mast-menu-catcher {', '.wf-mast-menu-row'].forEach((needle) => {
      expect(base).toContain(needle);
    });
  });
});

describe('the unset nudge is red on EVERY width (README §6)', () => {
  const NEEDLES = ['.wf-tick-group', '.wf-tick-nudge'];

  it('lives in the base stylesheet, not a media block, so it applies at every width', () => {
    const base = rules(NEEDLES, 'base');
    expect(base).toMatch(/\.wf-tick-group\[data-unset='true'\]/);
    expect(base).toMatch(/\.wf-tick-group\[data-unset='true'\] \.wf-tick-nudge \{ color: #EE8064; \}/);
  });

  function unsetGroup() {
    document.body.innerHTML = `
      <span class="wf-tick-group" id="g" data-away="false" data-unset="true">
        <button class="wf-tick-origin wf-tick-nudge" id="n"><span class="wf-tick-place" id="p">Set</span></button>
      </span>`;
    return {
      group: document.getElementById('g'),
      nudge: document.getElementById('n'),
      place: document.getElementById('p'),
    };
  }

  it.each(['base', 'phone'])('paints the group border and fill, and the label and pin ink (%s)', (arm) => {
    inject(rules(NEEDLES, arm));
    const { group, nudge, place } = unsetGroup();
    const g = getComputedStyle(group);
    expect(g.borderTopColor).toBe('rgba(200, 69, 47, 0.75)');
    expect(g.backgroundColor).toBe('rgba(200, 69, 47, 0.12)');
    expect(getComputedStyle(nudge).color).toBe('rgb(238, 128, 100)');
    // The place text inherits it rather than keeping its own primary ink.
    expect(getComputedStyle(place).color).toBe(getComputedStyle(nudge).color);
  });

  it('brightens on hover: border .95, text #F49A84, fill unchanged', () => {
    const css = rules(NEEDLES, 'base');
    expect(css).toMatch(
      /\.wf-tick-group\[data-unset='true'\]:hover \{[^}]*border-color: rgba\(200, 69, 47, 0\.95\)[^}]*background: rgba\(200, 69, 47, 0\.12\)/,
    );
    expect(css).toMatch(/\.wf-tick-group\[data-unset='true'\]:hover \.wf-tick-nudge \{ color: #F49A84; \}/);
  });

  it('does not touch a group that is not unset', () => {
    inject(rules(NEEDLES, 'base'));
    document.body.innerHTML = '<span class="wf-tick-group" id="g" data-away="false"></span>';
    const g = getComputedStyle(document.getElementById('g'));
    expect(g.borderTopColor).not.toBe('rgba(200, 69, 47, 0.75)');
    expect(g.backgroundColor).not.toBe('rgba(200, 69, 47, 0.12)');
  });

  it('has dropped the coral-only label rule', () => {
    expect(rules(['.wf-tick-nudge'], 'base')).not.toMatch(/var\(--color-plex-coral\)/);
  });
});
