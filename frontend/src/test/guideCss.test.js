import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The guide panel's stylesheet contract (docs/engineering/user-guide-plan.md, G2), read as text the way
 * `askDockCss.test.js` reads the dock's. jsdom loads no stylesheet and has no layout, so what the
 * component tests cannot see is pinned here; whether it LOOKS right is a browser check.
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of the first rule whose selector list has `selector` as an entry. */
function ruleBody(selector) {
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  let m = rule.exec(css);
  while (m !== null) {
    if (m[1].split(',').some((entry) => entry.trim() === selector)) return m[2];
    m = rule.exec(css);
  }
  return null;
}

describe('the docked guide column', () => {
  it('is 440px by default (the wide band, from 1180px) and 380px in the desktop band (1024-1179px)', () => {
    expect(ruleBody('.wf-guide-dock')).toMatch(/--wf-dock-w:\s*440px/);
    expect(ruleBody('.wf-guide-dock[data-band="desktop"]')).toMatch(/--wf-dock-w:\s*380px/);
  });

  it('takes its width from that variable and does not flex', () => {
    const body = ruleBody('.wf-guide-dock');
    expect(body).toMatch(/flex:\s*none/);
    expect(body).toMatch(/width:\s*var\(--wf-dock-w\)/);
  });

  it('sticks to the viewport on Plan and Coming up, and is the frame\'s height on the Map', () => {
    expect(ruleBody('.wf-guide-dock[data-sticky]')).toMatch(/position:\s*sticky/);
    expect(ruleBody('.wf-guide-dock:not([data-sticky])')).toMatch(/align-self:\s*stretch/);
  });
});

describe('the stacked guide dialog', () => {
  it('sits on z 70: above Modal (50) and the peek (60), below BottomSheet (9999)', () => {
    const body = ruleBody('.wf-guide-layer');
    expect(body).toMatch(/position:\s*fixed/);
    expect(body).toMatch(/z-index:\s*70\b/);
  });

  it('is a right-hand panel no wider than the viewport', () => {
    const body = ruleBody('.wf-guide-panel');
    expect(body).toMatch(/right:\s*0/);
    expect(body).toMatch(/width:\s*min\(440px,\s*100vw\)/);
  });
});

describe('the sheet presentation', () => {
  it('lets a flush BottomSheet scroller hold the frame: no padding, no overflow, a flex column', () => {
    const body = ruleBody('.app-safe-sheet [data-testid="bottom-sheet-scroller"][data-flush]');
    expect(body).toMatch(/display:\s*flex/);
    expect(body).toMatch(/flex-direction:\s*column/);
    expect(body).toMatch(/overflow:\s*hidden/);
    expect(body).toMatch(/padding:\s*0/);
  });

  it('fills the scroller with the frame', () => {
    const body = ruleBody('.wf-guide-frame');
    expect(body).toMatch(/flex:\s*1 1 auto/);
    expect(body).toMatch(/border:\s*0/);
  });
});
