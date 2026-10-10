import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Ask's stylesheet, read as text. jsdom applies no stylesheet here, so a component test cannot see a
 * rule that hides the safety note at some width or for some role; these pins read `index.css` itself.
 * They are cheap and narrow on purpose — the mock-fidelity of the rest is a browser check.
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
// Comments stripped, so prose that names a selector can never satisfy (or fail) a rule check.
const askBlock = css
  .slice(css.indexOf('Ask PhotoCast — the conversation'))
  .replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of the first rule whose selector list has `selector` as one of its entries, or null. */
function ruleBody(selector) {
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  let m = rule.exec(askBlock);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) return m[2];
    m = rule.exec(askBlock);
  }
  return null;
}

/** Every `@media`/`@supports`/`@container` block in the Ask section, as text, braces balanced. */
function conditionalBlocks() {
  const blocks = [];
  const re = /@(media|supports|container)[^{]*\{/g;
  let m = re.exec(askBlock);
  while (m !== null) {
    let depth = 1;
    let i = re.lastIndex;
    while (depth > 0 && i < askBlock.length) {
      if (askBlock[i] === '{') depth += 1;
      else if (askBlock[i] === '}') depth -= 1;
      i += 1;
    }
    blocks.push(askBlock.slice(m.index, i));
    re.lastIndex = i;
    m = re.exec(askBlock);
  }
  return blocks;
}

describe('the Ask stylesheet — the safety note is never hidden', () => {
  it('finds the Ask block and the safety rule at all', () => {
    expect(css).toContain('Ask PhotoCast — the conversation');
    expect(askBlock.length).toBeGreaterThan(1000);
    expect(ruleBody('.wf-ask-evc-safety')).not.toBeNull();
  });

  it('puts the safety note in no media or supports query: no width, no capability drops it', () => {
    const naming = conditionalBlocks().filter((b) => b.includes('wf-ask-evc-safety'));

    expect(naming).toEqual([]);
  });

  it('never hides the safety note, its card or the answer around it', () => {
    for (const selector of ['.wf-ask-evc-safety', '.wf-ask-evc', '.wf-ask-live', '.wf-ask-cards']) {
      const body = ruleBody(selector);
      expect(body, selector).not.toBeNull();
      expect(body, selector).not.toMatch(/display:\s*none|visibility:\s*hidden|opacity:\s*0\b/);
    }
  });

  it('clips nothing off the note: no max-height, no overflow:hidden, no line clamp', () => {
    expect(ruleBody('.wf-ask-evc-safety')).not.toMatch(/max-height|overflow:\s*hidden|line-clamp/);
  });
});

describe('the Ask stylesheet — a pick card’s own row can be pressed', () => {
  it('lifts the actions row above the button’s stretched hit area', () => {
    // The button's ::after covers the whole card; a sibling that is not positioned and raised would
    // paint beneath it and never receive a press.
    const row = ruleBody('.wf-ask-pick-act');

    expect(row).toMatch(/position:\s*relative/);
    expect(row).toMatch(/z-index:\s*1/);
  });
});

describe('the Ask entry stylesheet (F1b) — what jsdom cannot see', () => {
  const modalSource = readFileSync(resolve(process.cwd(), 'src/components/shared/Modal.jsx'), 'utf8');
  const sheetSource = readFileSync(resolve(process.cwd(), 'src/components/BottomSheet.jsx'), 'utf8');

  it('draws the phone bar BELOW Modal\'s z-index, so a dialog covers it and never the reverse', () => {
    const bar = Number(/z-index:\s*(\d+)/.exec(ruleBody('.wf-ask-bar') ?? '')?.[1]);
    const modal = Number(/\bz-(\d+)\b/.exec(modalSource)?.[1]);
    expect(Number.isFinite(bar)).toBe(true);
    expect(Number.isFinite(modal)).toBe(true);
    expect(bar).toBeLessThan(modal);
  });

  it('and below the tall sheet\'s scrim, so the scrim covers the bar it opened from', () => {
    const bar = Number(/z-index:\s*(\d+)/.exec(ruleBody('.wf-ask-bar') ?? '')?.[1]);
    const scrim = Number(/bottom-sheet-overlay[\s\S]*?zIndex:\s*(\d+)/.exec(sheetSource)?.[1]);
    expect(Number.isFinite(scrim)).toBe(true);
    expect(bar).toBeLessThan(scrim);
  });

  it('fixes the bar 10px above the bottom plus the home-indicator inset, 48px tall', () => {
    const body = ruleBody('.wf-ask-bar');
    expect(body).toMatch(/position:\s*fixed/);
    expect(body).toMatch(/bottom:\s*calc\(10px \+ var\(--safe-b\)\)/);
    expect(body).toMatch(/height:\s*48px/);
  });

  it('reserves the bar\'s 58px at the END of the page, after the footer, only while the bar is drawn', () => {
    const body = ruleBody('.app-safe:has(.wf-ask-bar-on)');
    expect(body).not.toBeNull();
    expect(body).toMatch(/padding-bottom:\s*calc\(var\(--safe-b\) \+ 58px\)/);
  });

  it('sets the question field at 16px or more, because iOS Safari zooms on anything smaller', () => {
    const size = Number(/font-size:\s*(\d+(?:\.\d+)?)px/.exec(ruleBody('.wf-ask-in-field') ?? '')?.[1]);
    expect(size).toBeGreaterThanOrEqual(16);
  });

  it('collapses the four-tab field by CLIPPING its label, never display:none, so the name survives', () => {
    const block = conditionalBlocks().find((b) => b.includes('wf-askf-q'));
    expect(block).toBeDefined();
    // The label's own rule, not the block: the block also hides the key cap (aria-hidden, no room in
    // a 34px button), which IS `display: none` and is not the name.
    const label = /\.wf-askf-q\s*\{([^}]*)\}/.exec(block);
    expect(label).not.toBeNull();
    expect(label[1]).toMatch(/clip:\s*rect\(0, 0, 0, 0\)/);
    expect(label[1]).not.toMatch(/display:\s*none/);
  });

  it('keeps the tab list a shrinking flex item beside the field, so it still scrolls', () => {
    expect(ruleBody('.wf-tabrow')).toMatch(/display:\s*flex/);
    expect(ruleBody('.wf-tabrow > .wf-tabs')).toMatch(/min-width:\s*0/);
  });

  it('makes the tall sheet a flex column whose scroller shrinks, off the data-size BottomSheet sets', () => {
    const sheet = ruleBody('.app-safe-sheet[data-size="tall"]');
    expect(sheet).toMatch(/display:\s*flex/);
    expect(sheet).toMatch(/flex-direction:\s*column/);
    expect(ruleBody('.app-safe-sheet[data-size="tall"] > [data-testid="bottom-sheet-scroller"]'))
      .toMatch(/min-height:\s*0/);
    expect(sheetSource).toContain('data-size={tall ?');
    expect(sheetSource).toContain('bottom-sheet-scroller');
  });

  it('keeps the viewport 58px (+ a ring) clear of the bar for FOCUS, not only at the end of the page', () => {
    // SC 2.4.11: a focused control near the bottom of the viewport scrolls only to the edge, under the bar.
    const rule = /html:has\(\.wf-ask-bar-on\)\s*\{([^}]*)\}/.exec(askBlock);
    expect(rule).not.toBeNull();
    expect(rule[1]).toMatch(/scroll-padding-bottom:\s*calc\(var\(--safe-b\) \+ 58px \+ 8px\)/);
  });

  it('does not dim the locked field\'s words: its placeholder is the only reason typing is off', () => {
    const body = ruleBody('.wf-ask-in-field[aria-disabled="true"]');
    expect(body).not.toBeNull();
    expect(body).not.toMatch(/opacity/);
    expect(body).toMatch(/border-style:\s*dashed/);
  });

  it('draws the question field\'s edge at 3:1 or better (the bone ink at .4 alpha), not border-light', () => {
    expect(ruleBody('.wf-ask-in-field')).toMatch(/border:\s*1px solid rgba\(242, 231, 211, \.4\)/);
  });

  it('holds the field\'s width where it is not drawn, as a box nobody can reach', () => {
    const body = ruleBody('.wf-askf-ghost');
    expect(body).toMatch(/visibility:\s*hidden/);
    expect(body).toMatch(/pointer-events:\s*none/);
  });
});

describe('the Ask stylesheet — the thread (T4)', () => {
  it('stacks the earlier exchanges as a plain column with no list marker, in no media query', () => {
    expect(ruleBody('.wf-ask-thread')).toMatch(/flex-direction:\s*column/);
    expect(ruleBody('.wf-ask-thread')).toMatch(/list-style:\s*none/);
    expect(ruleBody('.wf-ask-thread-x')).toMatch(/flex-direction:\s*column/);
    const naming = conditionalBlocks().filter((b) => b.includes('wf-ask-thread'));
    expect(naming).toEqual([]);
  });

  it('sets an earlier summary in the muted secondary ink, a size under the live answer’s, so the live one reads as current', () => {
    const earlier = ruleBody('.wf-ask-thread-sum');
    expect(earlier).toMatch(/color:\s*var\(--color-plex-text-secondary\)/);
    expect(Number(/font-size:\s*(\d+)px/.exec(earlier)[1])).toBeLessThan(
      Number(/font-size:\s*(\d+)px/.exec(ruleBody('.wf-ask-sum'))[1]),
    );
  });

  it('never hides the history or the reset line', () => {
    for (const selector of ['.wf-ask-thread', '.wf-ask-thread-x', '.wf-ask-thread-sum', '.wf-ask-thread-reset']) {
      expect(ruleBody(selector)).not.toMatch(/display:\s*none|visibility:\s*hidden/);
    }
  });
});
