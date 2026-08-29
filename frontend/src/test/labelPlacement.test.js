import { describe, it, expect } from 'vitest';
import {
  COLLISION_PAD_X, COLLISION_PAD_Y, EDGE_INSET, NUDGES, placeWithNudges,
} from '../utils/labelPlacement.js';

/** The dy=0 candidate box for a given anchor/size, matching the algorithm's own formula. */
const candidateAt = (anchor, size, dy) => ({
  x: anchor.x - size.w / 2,
  y: anchor.y - size.h / 2 + dy,
  w: size.w,
  h: size.h,
});

const ANCHOR = { x: 100, y: 100 };
/** A small height keeps the gap between non-adjacent-in-test rungs comfortably above the 2px
 * vertical pad, so a collider placed at one rung's exact box cannot accidentally also block a
 * neighbouring rung this suite never intended to touch. */
const SIZE = { w: 20, h: 4 };
const FRAME = { w: 300, h: 300 };

describe('labelPlacement — placeWithNudges', () => {
  // This looks like the anti-pattern the plan's own §1.3 warns against (asserting a constant
  // against itself proves nothing), but it is not one here: the ladder-walk test below derives
  // its expectations FROM the imported NUDGES array, so it is order- and value-agnostic — it
  // would pass unchanged if the ladder were reordered or a middle rung's offset changed. This is
  // the one place that actually pins the ladder's real numbers and order; do not delete it as a
  // "cleanup" of the anti-pattern the walk test already covers.
  it('exports the ladder and paddings the prototype used', () => {
    expect(NUDGES).toEqual([0, -13, 13, -24, 24, -36, 36]);
    expect(COLLISION_PAD_X).toBe(3);
    expect(COLLISION_PAD_Y).toBe(2);
    expect(EDGE_INSET).toBe(1);
  });

  it('lands at dy=0 first-fit when nothing blocks it', () => {
    const result = placeWithNudges(ANCHOR, SIZE, [], FRAME.w, FRAME.h);
    expect(result).toEqual(candidateAt(ANCHOR, SIZE, 0));
  });

  it('does not mutate the placed array — the caller pushes the returned box itself', () => {
    const placed = [{
      x: 0, y: 0, w: 5, h: 5,
    }];
    const before = JSON.parse(JSON.stringify(placed));
    const result = placeWithNudges(ANCHOR, SIZE, placed, FRAME.w, FRAME.h);
    expect(placed).toEqual(before);
    expect(placed).toHaveLength(1);
    expect(result).not.toBe(placed[0]);
  });

  it('walks the whole ladder one blocked rung at a time, to the exhausted-ladder null', () => {
    // Each step blocks every rung tried so far with a collider at that rung's EXACT candidate
    // box, then asserts the placer lands on the next rung in NUDGES order — pinning the ladder by
    // forcing the walk, not by asserting the constant against itself.
    NUDGES.forEach((expectedDy, i) => {
      const placed = NUDGES.slice(0, i).map((dy) => candidateAt(ANCHOR, SIZE, dy));
      const result = placeWithNudges(ANCHOR, SIZE, placed, FRAME.w, FRAME.h);
      expect(result).toEqual(candidateAt(ANCHOR, SIZE, expectedDy));
    });
    // One more collider than the ladder has rungs: every candidate is now blocked.
    const allBlocked = NUDGES.map((dy) => candidateAt(ANCHOR, SIZE, dy));
    expect(placeWithNudges(ANCHOR, SIZE, allBlocked, FRAME.w, FRAME.h)).toBeNull();
  });

  it('drops the label when a single box straddles and blocks every rung', () => {
    // `placed` takes arbitrary rects — a blocking box need not be rung-shaped. A tall strip
    // covering the whole vertical span every nudge could land in blocks all seven at once.
    const placed = [{
      x: ANCHOR.x - SIZE.w, y: 0, w: SIZE.w * 2, h: FRAME.h,
    }];
    expect(placeWithNudges(ANCHOR, SIZE, placed, FRAME.w, FRAME.h)).toBeNull();
  });

  describe('frame-edge rejection, both sides of the boundary', () => {
    const SZ = { w: 20, h: 10 };

    it('left edge: x = 1 is accepted', () => {
      const anchor = { x: 11, y: 100 };
      expect(placeWithNudges(anchor, SZ, [], 300, 300)).toEqual({
        x: 1, y: 95, w: 20, h: 10,
      });
    });

    it('left edge: x = 0 is rejected — x never varies with dy, so every rung fails', () => {
      const anchor = { x: 10, y: 100 };
      expect(placeWithNudges(anchor, SZ, [], 300, 300)).toBeNull();
    });

    it('right edge: x + w = frameW - 1 is accepted', () => {
      const anchor = { x: 89, y: 100 };
      expect(placeWithNudges(anchor, SZ, [], 100, 300)).toEqual({
        x: 79, y: 95, w: 20, h: 10,
      });
    });

    it('right edge: x + w = frameW is rejected — every rung fails', () => {
      const anchor = { x: 90, y: 100 };
      expect(placeWithNudges(anchor, SZ, [], 100, 300)).toBeNull();
    });

    it('top edge: y = 1 is accepted at dy=0', () => {
      const anchor = { x: 100, y: 6 };
      expect(placeWithNudges(anchor, SZ, [], 300, 300)).toEqual({
        x: 90, y: 1, w: 20, h: 10,
      });
    });

    it('top edge: y = 0 rejects dy=0 (and dy=-13, further out) — lands at dy=13', () => {
      const anchor = { x: 100, y: 5 };
      expect(placeWithNudges(anchor, SZ, [], 300, 300)).toEqual({
        x: 90, y: 13, w: 20, h: 10,
      });
    });

    it('bottom edge: y + h = frameH - 1 is accepted at dy=0', () => {
      const anchor = { x: 100, y: 94 };
      expect(placeWithNudges(anchor, SZ, [], 300, 100)).toEqual({
        x: 90, y: 89, w: 20, h: 10,
      });
    });

    it('bottom edge: y + h = frameH rejects dy=0 — lands at dy=-13', () => {
      const anchor = { x: 100, y: 95 };
      expect(placeWithNudges(anchor, SZ, [], 300, 100)).toEqual({
        x: 90, y: 77, w: 20, h: 10,
      });
    });
  });

  describe('collision inflation padding, both sides of each band edge', () => {
    // "Gap" means edge-to-edge distance between the dy=0 candidate and one placed box.
    const SZ = { w: 20, h: 10 };
    const anchor = { x: 100, y: 100 };
    const dy0 = candidateAt(anchor, SZ, 0); // {x:90, y:95, w:20, h:10}
    const dyMinus13 = candidateAt(anchor, SZ, -13);

    it('horizontal: a 2px gap collides — dy=0 is skipped for dy=-13', () => {
      const blocker = {
        x: dy0.x - 15 - 2, y: dy0.y, w: 15, h: 10,
      };
      expect(placeWithNudges(anchor, SZ, [blocker], 300, 300)).toEqual(dyMinus13);
    });

    it('horizontal: a 3px gap is clear — dy=0 is accepted (strict <, 3px excluded)', () => {
      const blocker = {
        x: dy0.x - 15 - 3, y: dy0.y, w: 15, h: 10,
      };
      expect(placeWithNudges(anchor, SZ, [blocker], 300, 300)).toEqual(dy0);
    });

    it('vertical: a 1px gap collides — dy=0 is skipped for dy=-13', () => {
      const blocker = {
        x: dy0.x, y: dy0.y + dy0.h + 1, w: 20, h: 10,
      };
      expect(placeWithNudges(anchor, SZ, [blocker], 300, 300)).toEqual(dyMinus13);
    });

    it('vertical: a 2px gap is clear — dy=0 is accepted (strict <, 2px excluded)', () => {
      const blocker = {
        x: dy0.x, y: dy0.y + dy0.h + 2, w: 20, h: 10,
      };
      expect(placeWithNudges(anchor, SZ, [blocker], 300, 300)).toEqual(dy0);
    });

    // The four tests above all place the blocker to the LEFT or BELOW the candidate, which only
    // ever exercises the overlap test's first x-clause and second y-clause — the other two clauses
    // (`b.x < a.x+a.w+PAD_X`, `a.y < b.y+b.h+PAD_Y`) are trivially true in every one of them and a
    // wrong padding constant on either would still pass. Mirror each pair from the opposite side so
    // all four clauses are the deciding one somewhere in the suite.

    it('horizontal (blocker on the RIGHT): a 2px gap collides — dy=0 is skipped for dy=-13', () => {
      const blocker = {
        x: dy0.x + dy0.w + 2, y: dy0.y, w: 15, h: 10,
      };
      expect(placeWithNudges(anchor, SZ, [blocker], 300, 300)).toEqual(dyMinus13);
    });

    it('horizontal (blocker on the RIGHT): a 3px gap is clear — dy=0 is accepted', () => {
      const blocker = {
        x: dy0.x + dy0.w + 3, y: dy0.y, w: 15, h: 10,
      };
      expect(placeWithNudges(anchor, SZ, [blocker], 300, 300)).toEqual(dy0);
    });

    it('vertical (blocker ABOVE): a 1px gap collides — dy=0 and dy=-13 both fall, lands at dy=13', () => {
      // Unlike the below-blocker case, a blocker this close above dy=0 also sits inside dy=-13's
      // own candidate box (they are only 3px apart, see the ladder-walk test) and blocks it too —
      // so the first surviving rung is dy=13, not dy=-13. That is a real, checked consequence of
      // the geometry, not a looser assertion.
      const blocker = {
        x: dy0.x, y: dy0.y - 10 - 1, w: 20, h: 10,
      };
      expect(placeWithNudges(anchor, SZ, [blocker], 300, 300)).toEqual(candidateAt(anchor, SZ, 13));
    });

    it('vertical (blocker ABOVE): a 2px gap is clear — dy=0 is accepted', () => {
      const blocker = {
        x: dy0.x, y: dy0.y - 10 - 2, w: 20, h: 10,
      };
      expect(placeWithNudges(anchor, SZ, [blocker], 300, 300)).toEqual(dy0);
    });
  });
});
