import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The map view's stylesheet rules (`docs/engineering/one-screen-plan.md` D10, D11, D12), asserted against the REAL
 * `index.css` (the suite runs with `css: false`, so nothing else in it resolves what a class paints; the same
 * source-level technique as `mapPanelInkCascade.test.jsx`).
 *
 * <p>What breaks if these fail: a transform on the view or its box makes `getBoundingClientRect` and the pane's
 * ResizeObserver disagree about the map's size (the 0x0 / mid-transform race the view's whole mount design avoids);
 * a missing `[hidden]` rule shows a closed view; the wrong z-index puts the view over a dialog or under the Ask bar;
 * a phone view that is not `100dvh` leaves a band under the browser chrome.
 */
const SOURCE = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of the first rule whose selector is exactly {@code selector}, outside any media query. */
function topLevelRule(selector) {
  let depth = 0;
  let selectorStart = 0;
  let blockStart = -1;
  for (let i = 0; i < SOURCE.length; i += 1) {
    if (SOURCE[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (SOURCE[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        if (SOURCE.slice(selectorStart, blockStart).trim() === selector) return SOURCE.slice(blockStart + 1, i);
        selectorStart = i + 1;
      }
    }
  }
  return null;
}

/** The declared value of a property in a rule body. */
const declared = (body, prop) => new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`, 'm').exec(body)?.[1].trim() ?? null;

describe('the map view\'s rules', () => {
  const view = topLevelRule('.wf-map-screen');
  const box = topLevelRule('.wf-map-screen-box');

  it('is fixed, above the Ask bar and the masthead band, and below Modal (z-50)', () => {
    expect(declared(view, 'position')).toBe('fixed');
    const z = Number(declared(view, 'z-index'));
    expect(z).toBeGreaterThan(45);
    expect(z).toBeLessThan(50);
  });

  it('starts below the session-expiry notice when it is pinned over the page, on every width', () => {
    expect(declared(view, 'padding-top')).toBe('var(--wf-session-banner-h, 0px)');
  });

  it('ends at the dock column\'s edge, through the measured custom property', () => {
    expect(declared(view, 'right')).toBe('var(--wf-map-right, 0px)');
  });

  it('is closed by the hidden attribute, not by a class, visibility or opacity', () => {
    expect(declared(topLevelRule('.wf-map-screen[hidden]'), 'display')).toBe('none');
    expect(view).not.toMatch(/visibility|pointer-events/);
  });

  it('never carries a transform, on the view or its box, and animates opacity only', () => {
    expect(view).not.toMatch(/transform|translate|scale/);
    expect(box).not.toMatch(/transform|translate|scale/);
    const keyframes = /@keyframes wf-map-screen-in\s*\{([\s\S]*?)\}\s*\}/.exec(SOURCE)?.[1] ?? '';
    expect(keyframes).toMatch(/opacity/);
    expect(keyframes).not.toMatch(/transform|translate|scale/);
  });

  it('carries the map\'s passing ink, as the Map tab\'s panel did', () => {
    expect(declared(view, '--color-plex-text-muted')).toBe('var(--color-panel-ink)');
  });

  it('is the whole dynamic viewport on a phone, with no scrim and no inset', () => {
    const phone = /@media \(max-width: 639px\)\s*\{\s*\/\* The phone: the whole dynamic viewport[\s\S]*?\n\}/.exec(SOURCE)
      ?? /@media \(max-width: 639px\)\s*\{\s*\.wf-map-screen \{[\s\S]*?\n\}/.exec(SOURCE);
    expect(phone).not.toBeNull();
    expect(phone[0]).toMatch(/\.wf-map-screen\s*\{[^}]*height:\s*100dvh/);
    expect(phone[0]).toMatch(/\.wf-map-screen-scrim\s*\{[^}]*display:\s*none/);
    expect(phone[0]).toMatch(/\.wf-map-screen-box\s*\{[^}]*margin:\s*0/);
  });

  it('insets the box 18px over the scrim on a tablet and desktop', () => {
    expect(declared(box, 'margin')).toBe('18px');
    expect(topLevelRule('.wf-map-screen-scrim')).toMatch(/background/);
  });

  it('reaches the window control from its header slot, on the phone too', () => {
    expect(SOURCE).toMatch(/:is\(\.wf-map-tab, \.wf-map-screen-head\) \.wf-win-control/);
  });
});
