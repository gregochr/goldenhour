/** Gap between the anchor's top edge and the panel's bottom edge, in px. */
const DEFAULT_GAP = 8;

/** Smallest distance the panel may sit from a viewport edge, in px. */
const DEFAULT_MARGIN = 10;

/**
 * Places a popover above its anchor, flipping its horizontal anchoring at the viewport edge.
 *
 * <p><b>Viewport coordinates, for a {@code position: fixed} panel.</b> {@code getBoundingClientRect}
 * is already viewport-relative, so the returned offsets need no scroll compensation — and must not
 * be given any. The corollary is that these numbers go stale the moment anything scrolls, which is
 * why {@code usePopoverHost} dismisses on scroll rather than trying to follow the anchor.
 *
 * <p><b>Anchored bottom, not top.</b> The panel grows upward from a fixed baseline just above the
 * anchor, so a two-line body and a six-line body both sit the same distance off the chip instead of
 * the taller one drifting down over it.
 *
 * @param {DOMRect} rect   the anchor's bounding box
 * @param {object}  [opts]
 * @param {number}  [opts.width]  panel width in px; omitted leaves the width to CSS
 * @param {number}  [opts.gap]    px between anchor top and panel bottom
 * @param {number}  [opts.margin] minimum px from a viewport edge
 * @returns {{style: Object, alignRight: boolean}} inline style, and which edge it anchored to so a
 *          caret can be drawn on the correct side
 */
export default function computePopoverPlacement(rect, opts = {}) {
  const { width, gap = DEFAULT_GAP, margin = DEFAULT_MARGIN } = opts;

  // Flip when a left-anchored panel of this width would overrun the right edge. With no width
  // given there is nothing to overrun-test against, so it stays left-anchored.
  const alignRight = width != null && rect.left + width + margin > window.innerWidth;

  const style = {
    position: 'fixed',
    bottom: `${window.innerHeight - rect.top + gap}px`,
  };
  if (width != null) {
    style.width = `${width}px`;
  }
  if (alignRight) {
    style.right = `${Math.max(margin, window.innerWidth - rect.right)}px`;
  } else {
    style.left = `${Math.max(margin, rect.left)}px`;
  }
  return { style, alignRight };
}
