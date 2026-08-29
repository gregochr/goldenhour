/**
 * A greedy, order-dependent label placer, ported verbatim from the design bundle's
 * {@code placeLabels} inner loop (`docs/design/field-geography/plan-tab-v4.js`, lines ~26-41) and
 * reshaped as a pure function so every field-geography consumer phase (G2 thumbnails, G3 popup
 * rings) reuses one proven implementation instead of re-deriving the arithmetic.
 *
 * <p>This is a sibling of {@code WindowRowFieldMap}'s own {@code fits}, not a replacement for it —
 * {@code fits} has no nudge ladder and carries a WCAG 2.5.8 target-separation test that only
 * applies to real controls (chips); this util is for decorative labels that may hunt for clear
 * air. Do not merge them.
 */

/**
 * Vertical offsets tried in order for a candidate label box, in px. The bundle's own ladder.
 *
 * @see placeWithNudges
 */
export const NUDGES = [0, -13, 13, -24, 24, -36, 36];

/** Horizontal collision padding between a candidate box and an already-placed one, in px. */
export const COLLISION_PAD_X = 3;
/** @see COLLISION_PAD_X */
export const COLLISION_PAD_Y = 2;

/** How far a candidate box must sit inside the frame on every side, in px. */
export const EDGE_INSET = 1;

/**
 * Finds the first vertical nudge (see {@link NUDGES}) that places a {@code size}-shaped label at
 * {@code anchor} inside the frame and clear of everything in {@code placed}.
 *
 * <p>Greedy and first-fit: the caller hands anchors over in priority order, and an earlier label
 * that fits keeps its space regardless of what a later one would have preferred. A dropped label
 * is the deliberate failure mode — never stacked, never shrunk, because an unreadable name is
 * worse than a missing one.
 *
 * @param {{x: number, y: number}} anchor the label's centre point, in frame px
 * @param {{w: number, h: number}} size the measured label element
 * @param {Array<{x: number, y: number, w: number, h: number}>} placed every box already committed
 *        this pass — never mutated; the caller pushes the returned box on acceptance
 * @param {number} frameW frame width, in px
 * @param {number} frameH frame height, in px
 * @returns {{x: number, y: number, w: number, h: number}|null} the accepted top-left box, or
 *          {@code null} when every nudge is blocked
 */
export function placeWithNudges(anchor, size, placed, frameW, frameH) {
  const { w, h } = size;
  const overlaps = (a, b) => a.x < b.x + b.w + COLLISION_PAD_X
    && b.x < a.x + a.w + COLLISION_PAD_X
    && a.y < b.y + b.h + COLLISION_PAD_Y
    && b.y < a.y + a.h + COLLISION_PAD_Y;

  for (const dy of NUDGES) {
    const box = {
      x: anchor.x - w / 2, y: anchor.y - h / 2 + dy, w, h,
    };
    if (box.x < EDGE_INSET || box.y < EDGE_INSET) continue;
    if (box.x + box.w > frameW - EDGE_INSET) continue;
    if (box.y + box.h > frameH - EDGE_INSET) continue;
    if (placed.some((other) => overlaps(box, other))) continue;
    return box;
  }
  return null;
}
