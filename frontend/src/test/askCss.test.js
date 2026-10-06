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

/** Every `@media`/`@supports` block in the Ask section, as text, braces balanced. */
function conditionalBlocks() {
  const blocks = [];
  const re = /@(media|supports)[^{]*\{/g;
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
