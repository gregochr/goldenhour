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

/**
 * The Map tab's own dy ladder (map-tab-v2-plan.md §3 P8, `docs/design/map-tab-v2/README.md` §6) —
 * a SECOND, longer-reaching ladder for a bigger canvas than the Plan popup's field map, not a
 * replacement for {@link NUDGES}. The two callers keep their own numbers: Plan thumbnails and the
 * popup field map pass no {@code options} at all and so see {@link NUDGES} exactly as before;
 * {@code MapLabels} passes this one explicitly. One module, two ladders.
 */
export const MAP_NUDGES = [0, -14, 14, -26, 26, -38, 38];

/**
 * The Map tab's horizontal fallback gap, in px — half the box width plus this, either side of the
 * anchor (`docs/design/map-tab-v2/README.md` §6: {@code dx ∈ [0, -w/2-9, +w/2+9]}). Exported so a
 * caller (or a test) can derive the exact same offsets {@link mapDxOffsets} does without
 * duplicating the constant.
 */
export const MAP_DX_GAP = 9;

/**
 * The Map tab's horizontal offsets for a box of width {@code w}: centred, then flush to the left
 * and to the right, each with {@link MAP_DX_GAP} of clear air beyond the box's own half-width. A
 * function rather than a fixed array because the offset is sized to the LABEL — a wide region name
 * and a narrow star chip fall back to different distances.
 *
 * @param {number} w the candidate box's measured width, in px
 * @returns {number[]} {@code [0, -half, +half]}, where {@code half = round(w/2) + MAP_DX_GAP}
 */
export function mapDxOffsets(w) {
  const half = Math.round(w / 2) + MAP_DX_GAP;
  return [0, -half, half];
}

/** Horizontal collision padding between a candidate box and an already-placed one, in px. */
export const COLLISION_PAD_X = 3;
/** @see COLLISION_PAD_X */
export const COLLISION_PAD_Y = 2;

/** How far a candidate box must sit inside the frame on every side, in px. */
export const EDGE_INSET = 1;

/**
 * Finds the first {@code (dy, dx)} rung — vertical first, then horizontal within it — that places
 * a {@code size}-shaped label at {@code anchor} inside the frame and clear of everything in
 * {@code placed}.
 *
 * <p>Greedy and first-fit: the caller hands anchors over in priority order, and an earlier label
 * that fits keeps its space regardless of what a later one would have preferred. A dropped label
 * is the deliberate failure mode — never stacked, never shrunk, because an unreadable name is
 * worse than a missing one.
 *
 * <p>{@code options.dx}/{@code options.dy} are both optional and both default to the ORIGINAL
 * single-dimension behaviour ({@link NUDGES}, dx always {@code 0}) — a caller that omits
 * {@code options} entirely (every Plan-tab caller, today) sees byte-identical output to before
 * this parameter existed. The Map tab is the one caller that passes {@link MAP_NUDGES} and
 * {@link mapDxOffsets} (`docs/design/map-tab-v2/README.md` §6): for each {@code dy} rung, every
 * {@code dx} offset is tried before moving to the next rung — matching the bundle's own nested
 * loop order, so a label prefers staying at its own height over drifting sideways.
 *
 * @param {{x: number, y: number}} anchor the label's centre point, in frame px
 * @param {{w: number, h: number}} size the measured label element
 * @param {Array<{x: number, y: number, w: number, h: number}>} placed every box already committed
 *        this pass — never mutated; the caller pushes the returned box on acceptance
 * @param {number} frameW frame width, in px
 * @param {number} frameH frame height, in px
 * @param {object} [options]
 * @param {number[]} [options.dy] the vertical ladder to walk. Defaults to {@link NUDGES}.
 * @param {Function} [options.dx] {@code (w) => number[]}, the horizontal offsets to try at each
 *        rung. Defaults to {@code () => [0]} — no horizontal fallback at all.
 * @returns {{x: number, y: number, w: number, h: number}|null} the accepted top-left box, or
 *          {@code null} when every rung on every offset is blocked
 */
export function placeWithNudges(anchor, size, placed, frameW, frameH, options = {}) {
  const { w, h } = size;
  const dyLadder = options.dy ?? NUDGES;
  const dxFor = options.dx ?? (() => [0]);
  const overlaps = (a, b) => a.x < b.x + b.w + COLLISION_PAD_X
    && b.x < a.x + a.w + COLLISION_PAD_X
    && a.y < b.y + b.h + COLLISION_PAD_Y
    && b.y < a.y + a.h + COLLISION_PAD_Y;

  for (const dy of dyLadder) {
    for (const dx of dxFor(w)) {
      const box = {
        x: anchor.x - w / 2 + dx, y: anchor.y - h / 2 + dy, w, h,
      };
      if (box.x < EDGE_INSET || box.y < EDGE_INSET) continue;
      if (box.x + box.w > frameW - EDGE_INSET) continue;
      if (box.y + box.h > frameH - EDGE_INSET) continue;
      if (placed.some((other) => overlaps(box, other))) continue;
      return box;
    }
  }
  return null;
}

/**
 * Turns a list of DOM rects into placer-shaped obstacle boxes, relative to a container rect and
 * padded outward on every side (`docs/design/map-tab-v2/README.md` §6: "the obstacle list is
 * seeded with the overlay chrome … each padded 5px" — the bundle's own {@code chromeBoxes}).
 *
 * <p>A label hidden under a chrome pill has been dropped anyway; padding just means it takes a few
 * more pixels of clear air with it, so a name never sits pixel-adjacent to a control's edge.
 *
 * @param {Array<{left: number, top: number, width: number, height: number}>} rects
 *        {@code DOMRect}-shaped obstacles, in VIEWPORT coordinates (whatever {@code
 *        getBoundingClientRect} returns) — a plain object with those four fields works too
 * @param {{left: number, top: number}} containerRect the frame's own rect, in the SAME coordinate
 *        space, subtracted out so the result is in frame-relative px (matching every anchor
 *        {@link placeWithNudges} is called with)
 * @param {number} [pad] px of clearance added on every side. Defaults to 5.
 * @returns {Array<{x: number, y: number, w: number, h: number}>}
 */
export function seedObstacles(rects, containerRect, pad = 5) {
  return rects.map((r) => ({
    x: r.left - containerRect.left - pad,
    y: r.top - containerRect.top - pad,
    w: r.width + pad * 2,
    h: r.height + pad * 2,
  }));
}
