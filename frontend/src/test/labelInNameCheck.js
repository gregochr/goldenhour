import axe from 'axe-core';
import { vi } from 'vitest';

/** The glyph box axe's icon-ligature probe draws into; any consistent size works for the stub. */
const GLYPH_PX = 30;

/**
 * A canvas 2D context good enough for axe's icon-ligature probe, which jsdom cannot provide: every
 * glyph is {@code GLYPH_PX} wide and paints solid pixels, so no text is ever mistaken for an icon
 * ligature (which axe would drop from the visible text).
 */
function stubCanvasContext() {
  return {
    canvas: {},
    font: '',
    textAlign: '',
    textBaseline: '',
    measureText: (text) => ({ width: GLYPH_PX * text.length }),
    fillText: () => {},
    clearRect: () => {},
    getImageData: (x, y, w, h) => ({ data: new Uint8ClampedArray(w * h * 4).fill(1) }),
  };
}

/**
 * Runs axe's own WCAG 2.5.3 rule ({@code label-content-name-mismatch}) on one element in jsdom.
 *
 * <p>axe compares the element's VISIBLE text (aria-hidden children included, sibling text nodes
 * glued with no separator) with its accessible name, so a chip whose spans abut ("Ridge4") fails
 * even when the name starts with the visible words. This is the same engine the Playwright a11y scan
 * runs, so a regression fails here in jsdom, not only in that scan. jsdom has no canvas, so the
 * ligature probe gets a stub context for the run.
 *
 * @param {Element} element the button or link under test
 * @returns {Promise<{violations: number, passes: number, incomplete: number}>} the rule's counts
 */
export async function labelInNameResult(element) {
  const spy = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(stubCanvasContext);
  try {
    const results = await axe.run(element, {
      runOnly: { type: 'rule', values: ['label-content-name-mismatch'] },
    });
    const count = (list) => list.reduce((n, rule) => n + rule.nodes.length, 0);
    return {
      violations: count(results.violations),
      passes: count(results.passes),
      incomplete: count(results.incomplete),
    };
  } finally {
    spy.mockRestore();
  }
}
