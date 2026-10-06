import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The dock's stylesheet, read as text (F2). jsdom loads no stylesheet and has no layout, so what the
 * shell's tests cannot see — the row the dock makes of the shell, the sticky column, the container
 * query that collapses the field — is pinned here, the way `askCss.test.js` pins the safety note.
 * Cheap and narrow on purpose: whether it LOOKS right is a browser check.
 */
const raw = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
const css = raw.replace(/\/\*[\s\S]*?\*\//g, '');
const shellSource = readFileSync(resolve(process.cwd(), 'src/components/WindowFirstShell.jsx'), 'utf8');

/** Returns the text between the braces opened at `open` (the index of a `{`). */
function blockFrom(text, open) {
  let depth = 1;
  let i = open + 1;
  while (depth > 0 && i < text.length) {
    if (text[i] === '{') depth += 1;
    else if (text[i] === '}') depth -= 1;
    i += 1;
  }
  return text.slice(open + 1, i - 1);
}

/** The body of the first top-level-ish rule whose selector list has `selector` as an entry. */
function ruleBody(selector, scope = css) {
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  let m = rule.exec(scope);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) return m[2];
    m = rule.exec(scope);
  }
  return null;
}

/** The inner text of every at-rule whose prelude is exactly `prelude`. */
function atRules(prelude) {
  const found = [];
  let from = 0;
  const needle = `@${prelude}`;
  for (;;) {
    const at = css.indexOf(needle, from);
    if (at === -1) return found;
    const open = css.indexOf('{', at);
    if (css.slice(at, open).trim() === needle) found.push(blockFrom(css, open));
    from = open + 1;
  }
}

describe('the tab row: the four-tab collapse is a CONTAINER query', () => {
  it('makes the tab row an inline-size container with a name', () => {
    expect(ruleBody('.wf-tabrow')).toMatch(/container:\s*wf-tabrow\s*\/\s*inline-size/);
  });

  it('collapses the field to 34px under 720px of COLUMN width, with four tabs', () => {
    const [block] = atRules('container wf-tabrow (max-width: 719px)');
    expect(block).toBeTruthy();
    expect(ruleBody('.wf-tabrow[data-tab-count="4"] .wf-askf[data-width]', block)).toMatch(/width:\s*34px/);
    // The label is clipped, never `display: none`, so the prompt stays in the accessible name; the
    // key cap (aria-hidden) has no room and is the one thing that is hidden.
    const label = ruleBody('.wf-tabrow[data-tab-count="4"] .wf-askf-q', block);
    expect(label).toMatch(/clip:\s*rect/);
    expect(label).not.toMatch(/display:\s*none/);
    expect(ruleBody('.wf-tabrow[data-tab-count="4"] .wf-askf-kbd', block)).toMatch(/display:\s*none/);
  });

  it('⚠️ is NOT a viewport query any more: the dock narrows the column, not the window', () => {
    // A viewport rule for the collapse would not fire at 1024px with the dock open (a ~632px column).
    // Scanned over EVERY `@media` block, whatever its prelude says, so a regression written as
    // `(max-width:719px)`, `(width < 720px)` or `screen and (…)` cannot slip past an exact-match.
    const media = [...css.matchAll(/@media[^{]*\{/g)].map((m) => blockFrom(css, m.index + m[0].length - 1));
    expect(media.length, 'the scan must find the stylesheet\'s media blocks at all').toBeGreaterThan(10);
    const naming = media.filter((block) => block.includes('wf-askf') && block.includes('data-tab-count'));
    expect(naming).toEqual([]);
  });

  it('steps the 340px form down to 260px before it collapses, and the collapse outranks the step', () => {
    const [step] = atRules('container wf-tabrow (max-width: 799px)');
    expect(step).toBeTruthy();
    expect(ruleBody('.wf-tabrow[data-tab-count="4"] .wf-askf[data-width="340"]', step)).toMatch(/width:\s*260px/);
    // Source order AND specificity: the 34px rule comes later and names `[data-width]` like the step.
    expect(css.indexOf('(max-width: 719px)', css.indexOf('@container wf-tabrow')))
      .toBeGreaterThan(css.indexOf('(max-width: 799px)'));
  });
});

describe('the shell column and the dock', () => {
  it('makes the docked root a ROW, centred as a pair', () => {
    const body = ruleBody('.wf-shell--docked');
    expect(body).toMatch(/display:\s*flex/);
    expect(body).toMatch(/flex-direction:\s*row/);
    expect(body).toMatch(/justify-content:\s*center/);
  });

  it('lets the column take what the dock leaves, and shrink below its content', () => {
    expect(ruleBody('.wf-shell--docked > .wf-shell-col')).toMatch(/flex:\s*1 1 0%/);
    expect(ruleBody('.wf-shell-col')).toMatch(/min-width:\s*0/);
  });

  it('⚠️ writes no 1080 of its own: the column\'s cap is the shell\'s one WRAP_MAX_WIDTH, set inline', () => {
    expect(ruleBody('.wf-shell--docked > .wf-shell-col')).not.toMatch(/max-width/);
    // The inline cap is the constant the two wrappers use, so the three can never disagree.
    expect(shellSource).toMatch(/style=\{askDockShown \? \{ maxWidth: WRAP_MAX_WIDTH \} : undefined\}/);
    expect(shellSource.match(/style=\{\{ maxWidth: WRAP_MAX_WIDTH \}\}/g).length).toBeGreaterThanOrEqual(2);
  });

  it('is 380px, and 360px in the narrower band', () => {
    expect(ruleBody('.wf-ask-dock')).toMatch(/--wf-dock-w:\s*380px/);
    expect(ruleBody('.wf-ask-dock[data-band="desktop"]')).toMatch(/--wf-dock-w:\s*360px/);
    expect(ruleBody('.wf-ask-dock-inner')).toMatch(/width:\s*var\(--wf-dock-w\)/);
  });

  it('sticks to the viewport on Plan and Coming up, to the input row at its foot', () => {
    const body = ruleBody('.wf-ask-dock[data-sticky]');
    expect(body).toMatch(/position:\s*sticky/);
    expect(body).toMatch(/align-self:\s*flex-start/);
    // 100dvh less its own distance from the viewport top until it sticks, which `AskDock` publishes…
    expect(body).toMatch(/height:\s*calc\(100dvh - var\(--safe-b\) - var\(--wf-dock-top, 0px\)\)/);
    // …and the safe insets: it sticks BELOW the status bar and its foot clears the home indicator
    // (`viewport-fit=cover`; an iPad at 1024px is a docked width).
    expect(body).toMatch(/top:\s*var\(--safe-t\)/);
  });

  it('is the frame\'s own height on the Map, and does not stick', () => {
    const body = ruleBody('.wf-ask-dock:not([data-sticky])');
    expect(body).toMatch(/align-self:\s*stretch/);
    expect(body).toMatch(/min-height:\s*0/);
    expect(body).not.toMatch(/position:\s*sticky/);
  });

  it('keeps its input row at the foot: a flex column whose scroller shrinks and the row does not', () => {
    expect(ruleBody('.wf-ask-dock-inner')).toMatch(/flex-direction:\s*column/);
    expect(ruleBody('.wf-ask-dock-scroll')).toMatch(/flex:\s*1 1 auto/);
    expect(ruleBody('.wf-ask-dock-scroll')).toMatch(/overflow-y:\s*auto/);
    expect(ruleBody('.wf-ask-in')).toMatch(/flex:\s*none/);
  });

  it('animates its width in, and not at all for a reader who asked for less motion', () => {
    expect(ruleBody('.wf-ask-dock')).toMatch(/animation:\s*wf-ask-dock-in/);
    const [reduced] = atRules('media (prefers-reduced-motion: reduce)')
      .filter((block) => block.includes('.wf-ask-dock'));
    expect(ruleBody('.wf-ask-dock', reduced)).toMatch(/animation:\s*none/);
  });

  it('draws its edge on the inner box, so a dock at width 0 draws nothing', () => {
    expect(ruleBody('.wf-ask-dock-inner')).toMatch(/border-left:/);
    expect(ruleBody('.wf-ask-dock')).not.toMatch(/border/);
  });

  it('keeps the question field at 16px in the dock too: an iPad at 1024px is a docked width', () => {
    expect(ruleBody('.wf-ask-in-field')).toMatch(/font-size:\s*16px/);
  });
});
