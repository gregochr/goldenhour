import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { PEEK_HEIGHT } from '../utils/askPeek.js';

/**
 * The phone peek sheet's heights (three since C2: 74 at rest, 356 a section, 470 Ask) and the four-button
 * row, read from `index.css` as text (jsdom applies no layout, so a
 * component test cannot see a height; the sibling files `askCss.test.js` and `mapCalloutClampCascade`
 * pin declarations the same way). The numbers live in `utils/askPeek.js` AND in the stylesheet — this is
 * what holds them together — and the clamp that keeps the window pill clear is asserted on the rule that
 * carries it. Whether a browser honours them is a browser claim.
 */

const CSS = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of every rule whose selector list has exactly `selector` as one entry, in source order. */
function bodies(selector) {
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  const out = [];
  let m = rule.exec(CSS);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) out.push(m[2]);
    m = rule.exec(CSS);
  }
  return out;
}

/** The value of `prop` in the LAST rule for `selector` that declares it, or null. */
function declared(selector, prop) {
  let value = null;
  for (const body of bodies(selector)) {
    const m = new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`).exec(body);
    if (m) value = m[1].trim();
  }
  return value;
}

describe('the sheet\'s heights (utils/askPeek.js ⇄ index.css)', () => {
  it.each([
    ['.wf-map-peek', 'resting'],
    ['.wf-map-peek-open', 'open'],
    ['.wf-map-peek[data-ask=\'expanded\']', 'ask'],
  ])('%s is PEEK_HEIGHT.%s', (selector, key) => {
    expect(declared(selector, 'height'), `no height rule for ${selector}`).toBe(`${PEEK_HEIGHT[key]}px`);
  });

  it('has no height rule left for the Ask row\'s old states: the sheet rests at 74 and a section is 356 whether or not Ask is on', () => {
    for (const state of ['collapsed', 'minimised', 'section']) {
      expect(bodies(`.wf-map-peek[data-ask='${state}']`), state).toHaveLength(0);
    }
  });

  it('the Ask section outranks the pre-Ask open rule by specificity, not by being later', () => {
    // `.wf-map-peek-open` is one class; the `data-ask` rule is a class and an attribute. Were the
    // section state ever written as a bare class it would tie, and source order would decide.
    expect(bodies('.wf-map-peek-open')).toHaveLength(1);
    expect(bodies('.wf-map-peek[data-ask=\'expanded\']')).toHaveLength(1);
  });
});

describe('the clamp — the window pill (and the big-seas line under it) is never covered', () => {
  it('is on the BASE rule, so every height above inherits it — 356 and 470 included', () => {
    expect(declared('.wf-map-peek', 'max-height')).toBe('calc(100% - var(--wf-chrome-tl-h, 44px) - 20px)');
    expect(declared('.wf-map-peek-open', 'max-height')).toBe('calc(100% - var(--wf-chrome-tl-h, 44px) - 20px)');
  });

  it('is never re-declared (and so never loosened) by a state rule', () => {
    expect(declared('.wf-map-peek[data-ask=\'expanded\']', 'max-height')).toBeNull();
  });

  it('is the figure MapLabels\' obstacle clamps to as well — the window pill\'s 10px + 44px + 10px', () => {
    const labels = readFileSync(resolve(process.cwd(), 'src/components/map/MapLabels.jsx'), 'utf8');
    expect(labels).toMatch(/PEEK_PILL_CLEARANCE = 64;/);
    // 44px fallback + 20px = the 64px MapLabels clamps to, until the big-seas line makes the chrome taller.
    expect(declared('.wf-map-peek', 'max-height')).toBe('calc(100% - var(--wf-chrome-tl-h, 44px) - 20px)');
  });
});

describe('the transition — .28s cubic-bezier(.3,.7,.2,1), and none under reduced motion', () => {
  it('is the README\'s .28s curve on the base rule (the mock\'s own CSS says .26s; the README is the stated figure)', () => {
    // The FIRST `.wf-map-peek` rule: the reduced-motion override below it is a second one.
    expect(bodies('.wf-map-peek')[0]).toMatch(/transition:\s*height \.28s cubic-bezier\(\.3, \.7, \.2, 1\);/);
  });

  it('is switched off for prefers-reduced-motion', () => {
    const m = /@media \(prefers-reduced-motion: reduce\)\s*\{\s*\.wf-map-peek\s*\{\s*transition:\s*none;\s*\}\s*\}/.exec(CSS);
    expect(m).not.toBeNull();
  });
});

describe('the four-button row (map-mobile-compact-plan.md C2, README §4)', () => {
  const px = (value) => Number.parseInt(value, 10);

  it('is gap 5px, padding 0 8px 10px', () => {
    expect(declared('.wf-map-peek-row', 'gap')).toBe('5px');
    expect(declared('.wf-map-peek-row', 'padding')).toBe('0 8px 10px');
  });

  it('Ask and Layers are the two SQUARE buttons: 44px wide, never growing or shrinking, glyph centred', () => {
    expect(declared('.wf-map-peek-btn-ask', 'flex')).toBe('0 0 44px');
    expect(declared('.wf-map-peek-btn-lay', 'flex')).toBe('0 0 44px');
    expect(declared('.wf-map-peek-btn-ask', 'align-items')).toBe('center');
    expect(declared('.wf-map-peek-btn-lay', 'align-items')).toBe('center');
    // The two wide ones share what is left: Other windows and Tide are `flex: 1`.
    expect(declared('.wf-map-peek-btn', 'flex')).toBe('1');
  });

  it('keys are 8px mono at .08em; values 12px / 600; the Tide value 11px', () => {
    expect(declared('.wf-map-peek-k', 'font-size')).toBe('8px');
    expect(declared('.wf-map-peek-k', 'letter-spacing')).toBe('.08em');
    expect(declared('.wf-map-peek-v', 'font-size')).toBe('12px');
    expect(declared('.wf-map-peek-v', 'font-weight')).toBe('600');
    expect(declared('.wf-map-peek-btn-tide .wf-map-peek-v', 'font-size')).toBe('11px');
  });

  it('the verdict never cuts and the day text may (README Verify 5, still true at 12px)', () => {
    expect(declared('.wf-map-peek-v-verdict', 'flex')).toBe('none');
    expect(declared('.wf-map-peek-v-day', 'text-overflow')).toBe('ellipsis');
    expect(declared('.wf-map-peek-v-day', 'min-width')).toBe('0');
  });

  it('the Ask glyph is #EBD9A8 (the segment-active token) and a locked one is muted, a disabled one dimmed', () => {
    expect(declared('.wf-map-peek-btn-ask .wf-map-peek-v', 'color')).toBe('var(--color-segment-active)');
    expect(declared(".wf-map-peek-btn-ask[data-locked='true'] .wf-map-peek-v", 'color')).toBe('var(--color-plex-text-muted)');
    expect(declared('.wf-map-peek-btn-ask:disabled', 'opacity')).toBe('.5');
  });

  it('S4: a refusal puts a small coral dot on the Ask button', () => {
    expect(declared('.wf-map-peek-btn-ask', 'position')).toBe('relative');
    expect(declared(".wf-map-peek-btn-ask[data-error='true']::after", 'border-radius')).toBe('50%');
    expect(declared(".wf-map-peek-btn-ask[data-error='true']::after", 'background')).toBe('var(--color-badge-poor)');
  });

  it('the resting sheet holds its handle, the buttons and the border at 74: 14 + 46 + 10 + 1', () => {
    const handle = px(declared('.wf-map-peek-hdl', 'height'));
    const buttons = px(declared('.wf-map-peek-btn', 'height')) + px(declared('.wf-map-peek-row', 'padding').split(' ')[2]);
    expect(handle + buttons + 1).toBeLessThanOrEqual(PEEK_HEIGHT.resting);
  });

  it('the minimised sheet holds its handle and the 44px line row with its 10px margin at 74: 14 + 44 + 10 + 1', () => {
    const handle = px(declared('.wf-map-peek-hdl', 'height'));
    const line = px(declared('.wf-map-peek-mini-row', 'min-height')) + px(declared('.wf-map-peek-mini-row', 'margin').split(' ')[2]);
    expect(handle + line + 1).toBeLessThanOrEqual(PEEK_HEIGHT.resting);
    expect(declared('.wf-map-peek-mini', 'min-height')).toBe('44px');
  });

  it('retires the Ask ENTRY ROW: no entry, prompt or arrow rule is left to drift', () => {
    for (const selector of [
      '.wf-map-peek-ask-entry', '.wf-map-peek-ask-q', '.wf-map-peek-ask-go', ".wf-map-peek-ask-q[data-error='true']",
    ]) {
      expect(bodies(selector), selector).toHaveLength(0);
    }
  });

  it('the field keeps the 16px text iOS needs (it zooms the page on anything smaller)', () => {
    expect(declared('.wf-ask-in-field', 'font-size')).toBe('16px');
  });

  it('the answer scrolls under the field row, which does not', () => {
    expect(declared('.wf-map-peek-ask-body', 'overflow-y')).toBe('auto');
    expect(declared('.wf-map-peek-ask-body', 'flex')).toBe('1 1 auto');
    expect(declared('.wf-map-peek-ask-row', 'flex')).toBe('none');
  });

  it('the Ask node is a programmatic focus target with no ring — and the ✕ and the line have one', () => {
    expect(declared('.wf-map-peek-ask:focus', 'outline')).toBe('none');
    expect(CSS).toMatch(/\.wf-map-peek-ask-x:focus-visible\s*\{[^}]*outline:\s*2px solid/);
    expect(declared('.wf-map-peek-mini:focus-visible', 'outline')).toBe('2px solid var(--color-plex-gold)');
  });
});

// docs/engineering/phone-pick-callout-plan.md §3 P2: the Layers door beside the minimised line.
describe('the minimised row and the Layers door', () => {
  const px = (value) => Number.parseInt(value, 10);

  it('the ROW owns the line\'s 8px side margin (the buttons\' own), its 10px below and its 44px; the line\'s button carries none', () => {
    expect(declared('.wf-map-peek-mini-row', 'margin')).toBe('0 8px 10px');
    expect(declared('.wf-map-peek-mini-row', 'min-height')).toBe('44px');
    expect(declared('.wf-map-peek-mini-row', 'display')).toBe('flex');
    expect(declared('.wf-map-peek-mini', 'margin')).toBe('0');
    expect(declared('.wf-map-peek-mini', 'min-height')).toBe('44px');
  });

  it('the line takes the room the door leaves and may shrink below its content, so a long name ellipsises and never pushes the door out', () => {
    expect(declared('.wf-map-peek-mini', 'flex')).toBe('1');
    expect(declared('.wf-map-peek-mini', 'min-width')).toBe('0');
    expect(declared('.wf-map-peek-mini-name', 'min-width')).toBe('0');
    expect(declared('.wf-map-peek-mini-name', 'text-overflow')).toBe('ellipsis');
  });

  it('the door is a 44 x 44px, border-box, never-shrinking slot — the Layers button in miniature, no taller than the line', () => {
    expect(declared('.wf-map-peek-mini-lay', 'width')).toBe('44px');
    expect(declared('.wf-map-peek-mini-lay', 'width')).toBe(declared('.wf-map-peek-btn-lay', 'flex').split(' ').at(-1));
    expect(declared('.wf-map-peek-mini-lay', 'height')).toBe('44px');
    expect(declared('.wf-map-peek-mini-lay', 'box-sizing')).toBe('border-box');
    expect(declared('.wf-map-peek-mini-lay', 'flex')).toBe('none');
    expect(px(declared('.wf-map-peek-mini-lay', 'height'))).toBeLessThanOrEqual(px(declared('.wf-map-peek-mini-row', 'min-height')));
  });

  it('the door wears the Ask ✕\'s border and radius, so it reads as a button and not as part of the line', () => {
    expect(declared('.wf-map-peek-mini-lay', 'border')).toBe(declared('.wf-map-peek-ask-x', 'border'));
    expect(declared('.wf-map-peek-mini-lay', 'border')).toBe('1px solid var(--color-plex-border-light)');
    expect(declared('.wf-map-peek-mini-lay', 'border-radius')).toBe(declared('.wf-map-peek-ask-x', 'border-radius'));
  });

  it('the door\'s focus ring is drawn INSIDE it and is an outline, which forced-colours keeps', () => {
    expect(declared('.wf-map-peek-mini-lay:focus-visible', 'outline')).toBe('2px solid var(--color-plex-gold)');
    expect(declared('.wf-map-peek-mini-lay:focus-visible', 'outline-offset')).toBe('-2px');
  });

  it('the spot\'s time is hidden below 400px — the pill above already says it — and shown at 400px and up', () => {
    const hidden = /@media \(max-width: 399px\)\s*\{\s*\.wf-map-peek-mini-when\s*\{\s*display:\s*none;\s*\}\s*\}/;
    expect(hidden.exec(CSS)).not.toBeNull();
    // …and nowhere outside that block (`declared` cannot tell a media rule from a bare one).
    expect(CSS.replace(hidden, '')).not.toMatch(/\.wf-map-peek-mini-when\s*\{[^}]*display\s*:\s*none/);
  });
});

describe('the details the review pinned', () => {
  it('the minimised line\'s focus ring is drawn INSIDE it, clear of the sheet\'s rounded edge and the frame\'s clip', () => {
    expect(declared('.wf-map-peek-mini:focus-visible', 'outline-offset')).toBe('-2px');
  });

  it('the ✕ lines up with the field, not with a "Next question" row that may stand above it', () => {
    expect(declared('.wf-map-peek-ask-x', 'align-self')).toBe('flex-end');
  });

  it('the field inside the row takes 8px of side padding: at 320px the 16px placeholder (183px measured) needs it', () => {
    expect(declared('.wf-map-peek-ask-field .wf-ask-in-field', 'padding')).toBe('0 8px');
    // …and keeps the 16px text iOS needs, from the sheet's own rule.
    expect(declared('.wf-ask-in-field', 'font-size')).toBe('16px');
  });

});

describe('--psh — one figure since C2, with no other literal left to drift from it', () => {
  it('keeps 74px as the DEFAULT (and now the only resting height); MapView still writes it inline', () => {
    expect(declared('.wf-map-tab', '--psh')).toBe('74px');
    expect(declared('.wf-map-tab', '--psh')).toBe(`${PEEK_HEIGHT.resting}px`);
  });

  it('the Leaflet corner\'s padding and the bottom-left chrome\'s floor both read the property', () => {
    expect(declared('.wf-map-tab .leaflet-bottom.leaflet-right', 'padding-bottom')).toBe('var(--psh, 74px)');
    expect(declared('.wf-map-tab .wf-map-chrome-bl', 'bottom')).toBe('calc(var(--psh, 74px) + 14px + 8px)');
  });

  it('the empty-state chip\'s lift (written earlier) already read it', () => {
    expect(CSS).toContain('calc(var(--psh, 0px) + 52px)');
  });
});
