/**
 * Ask PhotoCast's map camera maths — pure, no Leaflet and no React
 * (`docs/engineering/ask-photocast-plan.md` §2.7, F3).
 *
 * <h2>Why the padding is clamped, and to what</h2>
 *
 * <p>Leaflet's {@code getBoundsZoom} subtracts the padding from the frame's size and divides what is
 * left by the bounds' own span. Padding that exceeds the frame leaves a NEGATIVE size, so the scale
 * goes negative. The plan calls the result a {@code NaN} zoom; measured against Leaflet 1.9 it is
 * quieter and no better: the {@code NaN} is turned into {@code Infinity} by {@code getScaleZoom}, the
 * zoom cap then pins the fit at maximum zoom, and the picks land OUTSIDE the frame — a camera move to
 * nowhere, with no error. The design mock's 490px bottom pad (an iPhone sheet) is taller than the real
 * phone map frame, and a 400px-tall desktop window is not an exotic case either. So the padding on each
 * axis is scaled down, proportionally, until the pair sums to no more than
 * {@link ASK_PADDING_MAX_FRACTION} of the frame on that axis: whatever the covering surface measures, a
 * usable window is left for the picks.
 *
 * <h2>What the padding is made of</h2>
 *
 * <p>{@link ASK_BASE_PADDING} clears the map's own chrome (the window pill and the Regions bar above,
 * the counts footer below); {@code inset} is what a surface COVERS of the frame, measured by whoever
 * knows it. On the desktop dock the inset is zero — the dock narrows the frame and covers none of it.
 * Nothing in F3 passes a non-zero inset; F4's peek sheet is the first that will.
 */

/**
 * What the heat field's fill is multiplied by while an answer's picks are on the map (the mock's
 * "heat to fillOpacity .08" against its .22, over the real field's own opacity). A factor on the fill
 * inside the draw — never an opacity on the pane, which would dim the coastline and the reach rings
 * that share the canvas.
 */
export const ASK_HEAT_DIM = 0.36;

/** The farthest an answer's fit zooms in (a single pick, or picks that are close together). */
export const ASK_FIT_MAX_ZOOM = 10;

/** The zoom a selected pick is flown to. */
export const ASK_SELECT_ZOOM = 10.5;

/** The fraction of the frame's width or height the padding may take, on each axis. */
export const ASK_PADDING_MAX_FRACTION = 0.6;

/** Clearance for the map's own chrome, in px: the window pill above, the footer below, margins at the sides. */
export const ASK_BASE_PADDING = Object.freeze({
  top: 80, right: 60, bottom: 60, left: 60,
});

/** No covering surface. */
export const NO_INSET = Object.freeze({
  top: 0, right: 0, bottom: 0, left: 0,
});

/** A finite, non-negative number, or 0. */
function px(value) {
  return Number.isFinite(value) && value > 0 ? value : 0;
}

/**
 * Scales {@code padding} so that, on each axis, the two sides sum to at most {@code maxFraction} of
 * the frame. An axis that is already within the cap is untouched; one that is over keeps its
 * proportions. A frame with no size yet (a hidden map) gets no padding at all.
 *
 * @param {{x: number, y: number}} size the frame, as {@code map.getSize()}
 * @param {{top: number, right: number, bottom: number, left: number}} padding
 * @param {number} [maxFraction] defaults to {@link ASK_PADDING_MAX_FRACTION}
 * @returns {{top: number, right: number, bottom: number, left: number}}
 */
export function clampPadding(size, padding, maxFraction = ASK_PADDING_MAX_FRACTION) {
  const out = {
    top: px(padding?.top), right: px(padding?.right), bottom: px(padding?.bottom), left: px(padding?.left),
  };
  if (!(size?.x > 0) || !(size?.y > 0)) return { top: 0, right: 0, bottom: 0, left: 0 };
  const across = out.left + out.right;
  const down = out.top + out.bottom;
  const kx = across > size.x * maxFraction ? (size.x * maxFraction) / across : 1;
  const ky = down > size.y * maxFraction ? (size.y * maxFraction) / down : 1;
  return {
    top: out.top * ky, right: out.right * kx, bottom: out.bottom * ky, left: out.left * kx,
  };
}

/**
 * Clearance below the top of the frame that a fit keeps under the chrome group, in px: the group's
 * own 8px inset plus a 36px margin (so {@link ASK_BASE_PADDING}'s 80 is the 36px window control
 * plus 44).
 */
export const ASK_CHROME_CLEARANCE = 44;

/** The custom property {@code useChromeTlHeight} keeps current: the top-left chrome group's height, px. */
export const CHROME_HEIGHT_VAR = '--wf-chrome-tl-h';

/**
 * The published chrome-group height above {@code el}, in px, or null when none is published.
 *
 * <p>{@code useChromeTlHeight} (in {@code MapView}) sets {@link CHROME_HEIGHT_VAR} on the map frame
 * on every day: the group is taller than its 36px control whenever the big-seas line or the topic
 * chips row shows. Read off the INLINE style of each ancestor, nearest first, so it needs
 * no layout and no computed-style inheritance.
 *
 * @param {?Element} el any element inside the frame, such as the Leaflet container
 * @returns {?number}
 */
export function chromeHeightOf(el) {
  for (let node = el; node; node = node.parentElement) {
    const raw = node.style?.getPropertyValue?.(CHROME_HEIGHT_VAR);
    const h = parseFloat(raw);
    if (Number.isFinite(h) && h > 0) return h;
  }
  return null;
}

/**
 * The padding for fitting an answer's picks: the chrome's clearance plus what a surface covers,
 * clamped to the frame. When the chrome group is taller than its 36px control (the big-seas line is
 * showing) the top clearance grows to clear the whole group, so a pick never lands under the line.
 *
 * @param {{x: number, y: number}} size {@code map.getSize()}
 * @param {?{top: number, right: number, bottom: number, left: number}} [inset] what a surface covers
 * @param {?number} [chromeHeight] the published chrome-group height, or null
 * @returns {{top: number, right: number, bottom: number, left: number}}
 */
export function fitPadding(size, inset = NO_INSET, chromeHeight = null) {
  const chromeTop = chromeHeight > 0
    ? Math.max(ASK_BASE_PADDING.top, chromeHeight + ASK_CHROME_CLEARANCE)
    : ASK_BASE_PADDING.top;
  return clampPadding(size, {
    top: chromeTop + px(inset?.top),
    right: ASK_BASE_PADDING.right + px(inset?.right),
    bottom: ASK_BASE_PADDING.bottom + px(inset?.bottom),
    left: ASK_BASE_PADDING.left + px(inset?.left),
  });
}

/**
 * How far, in px, to push the camera's centre off a selected pick so the pick lands clear of a
 * covering surface: toward the side that is covered, by half of what covers it. Uses the INSET alone
 * — the chrome's clearance is not a covering surface, and a pick is centred on a bare desktop map.
 *
 * @param {{x: number, y: number}} size {@code map.getSize()}
 * @param {?{top: number, right: number, bottom: number, left: number}} [inset]
 * @returns {{dx: number, dy: number}}
 */
export function selectOffset(size, inset = NO_INSET) {
  const pad = clampPadding(size, inset);
  return { dx: (pad.right - pad.left) / 2, dy: (pad.bottom - pad.top) / 2 };
}

/** Whether the reader asked the system for less motion. */
export function prefersReducedMotion() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches === true;
}
