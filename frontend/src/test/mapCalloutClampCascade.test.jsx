import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The callout's three-line clamp and its ceiling, asserted against the REAL `index.css` — increment
 * §1 (`docs/design/map-tab-v2/INCREMENT_sheet_and_tide.md`).
 *
 * <h2>Why a source-level test and not a rendered one</h2>
 *
 * <p>jsdom implements neither `-webkit-line-clamp` nor layout, so `MapCallout.test.jsx` can only
 * assert the DOM nesting (the caption is a sibling of the clamped box, not a descendant). A review
 * lens pointed out that leaves the increment's two load-bearing CSS rules unpinned: moving the clamp
 * onto the button, or adding a `display: block` to the clamped span, are the exact failures the
 * increment records ("which is how the clamp died once already") and neither would fail a test.
 *
 * <p>So this file uses the slicer technique the repo already has (`mapPanelInkCascade`,
 * `mapFullFrameCascade`) and pins the declarations themselves. It proves the rules are WRITTEN as
 * intended, not that Chrome honours them — the browser half is verified by measurement and recorded
 * in the commit (clamp held at 56px client / 150px scroll on a 396-character narrative).
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** `index.css` with comments stripped, so a value quoted in prose can never satisfy a test. */
function css() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`)
    .toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/** Every rule body whose selector is exactly `selector`, in source order. */
function bodies(selector) {
  const re = new RegExp(
    `(^|})\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'gm',
  );
  const out = [];
  let m;
  const source = css();
  while ((m = re.exec(source)) !== null) out.push(m[2]);
  return out;
}

/** The declared value of `prop` across every rule for `selector` — last wins, as the cascade does. */
function declared(selector, prop) {
  const all = bodies(selector);
  expect(all.length, `no rule found for "${selector}"`).toBeGreaterThan(0);
  let value = null;
  for (const body of all) {
    const m = new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`, 'm').exec(body);
    if (m) value = m[1].trim();
  }
  return value;
}

describe('the callout clamp — increment §1', () => {
  it('clamps the INNER span, and gives it the box the clamp requires', () => {
    // `-webkit-line-clamp` is inert outside `display: -webkit-box` with a vertical box-orient, and
    // needs `overflow: hidden` to actually hide the overflow. All four must be on one element.
    expect(declared('.wf-callout-reason-text', '-webkit-line-clamp')).toBe('3');
    expect(declared('.wf-callout-reason-text', 'display')).toBe('-webkit-box');
    expect(declared('.wf-callout-reason-text', '-webkit-box-orient')).toBe('vertical');
    expect(declared('.wf-callout-reason-text', 'overflow')).toBe('hidden');
  });

  it('does NOT clamp the button — that would take the caption with it', () => {
    // The increment's own note: putting the clamp on the button clamps `Four days here ›` away
    // along with the prose, and dies to any later `display: block` in the same stylesheet.
    expect(declared('.wf-callout-reason', '-webkit-line-clamp')).toBeNull();
    expect(declared('.wf-callout-reason', 'display')).toBe('block');
  });

  it('keeps the prose selectable — a button is user-select:none in Firefox', () => {
    expect(declared('.wf-callout-reason-text', 'user-select')).toBe('text');
  });

  it('keeps the card itself unclipped, so the tail is not cut off its own point', () => {
    // `.wf-callout-tail` is absolutely positioned OUTSIDE the card box (top/bottom -6px), so any
    // overflow value but `visible` clips the pointer away.
    expect(declared('.wf-callout', 'overflow')).toBe('visible');
    expect(declared('.wf-callout-tail', 'position')).toBe('absolute');
  });

  it('puts the ceiling’s squeeze on the ALWAYS-rendered body, never a conditional child', () => {
    // ⚠️ The regression three review lenses caught. The scroll briefly lived on
    // `.wf-callout-strip`, which is only in the DOM while the strip is open — so a collapsed card
    // at its ceiling had nothing able to shrink, painted past its own plate, and reported the
    // clamped height to the placer. `.wf-callout-body` is unconditional.
    expect(declared('.wf-callout-body', 'overflow-y')).toBe('auto');
    expect(declared('.wf-callout-body', 'flex')).toBe('1 1 auto');
    expect(declared('.wf-callout-body', 'min-height')).toBe('0');
    // Every other direct child is rigid, so the squeeze lands on the body alone.
    expect(declared('.wf-callout > *', 'flex')).toBe('none');
    // And the strip does NOT claim the squeeze — that is what made it conditional-dependent.
    expect(declared('.wf-callout-strip', 'flex')).toBeNull();
  });
});
