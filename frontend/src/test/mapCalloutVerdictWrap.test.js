import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * `index.css` — which of the callout's headline lines may wrap (review C4).
 *
 * <p>The card and its verdict row are fixed px; text enlarged on its own (a minimum font size, or
 * Firefox's Zoom Text Only) is not. "Couldn’t load — trying again" at `white-space: nowrap` overran
 * the phone card's 220px row from about 111% and was clipped behind the body's horizontal scroll.
 * The unscored lines carry no pill fill, so they may wrap; the rated pill must not. jsdom lays
 * nothing out, so the rules themselves are asserted against the REAL stylesheet, comments stripped —
 * `mapPanelInkCascade.test.jsx`'s own method — and what they do to a glyph is the browser's to show.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** `index.css` with comments stripped, so a value quoted in prose can never satisfy a test. */
function css() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`)
    .toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/** The body of the FIRST rule whose selector is exactly `selector`. */
function ruleBody(selector) {
  const source = css();
  const re = new RegExp(`(^|})\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'm');
  const m = re.exec(source);
  expect(m, `no rule found for "${selector}"`).not.toBeNull();
  return m[2];
}

/** The declared value of `prop` inside `body`, trimmed. */
function declared(body, prop) {
  const m = new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`, 'm').exec(body);
  return m ? m[1].trim() : null;
}

describe('the callout verdict row — which headline lines may wrap', () => {
  it('lets the unscored lines wrap, so enlarged text wraps rather than being clipped', () => {
    expect(declared(ruleBody('.wf-callout-verdict-score.unscored'), 'white-space')).toBe('normal');
  });

  it('keeps the rated pill on one line — a filled capsule broken in two reads as two badges', () => {
    expect(declared(ruleBody('.wf-callout-verdict-score'), 'white-space')).toBe('nowrap');
  });
});
