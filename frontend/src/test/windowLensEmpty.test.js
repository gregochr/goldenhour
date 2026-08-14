import { describe, it, expect } from 'vitest';
import { buildLensEmptyState } from '../utils/windowLensEmpty.js';
import { gateSpotsByReach } from '../utils/reachLens.js';
import { gateSpotsByRating } from '../utils/ratingLens.js';

/** A spot as `buildWindowSpots` emits one. */
function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    rating: 4,
    driveMinutes: 30,
    ...overrides,
  };
}

/**
 * The descriptor as `buildWindowCards` derives it — both gates run here rather than being asserted
 * from a hand-written `spots: []`.
 *
 * <p>That is the point of the harness: a fixture that simply asserts "spots is empty" can be right
 * about a state the real pipeline never produces. Running the actual gates means every case below
 * is one the card can reach.
 */
function emptyState({ allSpots, tierId = 'any', limitMinutes = null, floorId = 'any', minRating = null }) {
  const spots = gateSpotsByRating(gateSpotsByReach(allSpots, limitMinutes), minRating);
  return buildLensEmptyState({ allSpots, spots, tierId, limitMinutes, floorId, minRating });
}

describe('windowLensEmpty — when there is anything to say at all', () => {
  it('says nothing while the window still has spots to draw', () => {
    expect(emptyState({ allSpots: [spot()] })).toBeNull();
  });

  it('says nothing about a window that had no spots to begin with', () => {
    // A card the lens never touched. A line here would blame a filter for an empty forecast, and
    // there would be nothing for any action to bring back.
    expect(emptyState({ allSpots: [] })).toBeNull();
  });
});

describe('windowLensEmpty — the headline names only what is gating', () => {
  it('names the reach threshold when reach alone emptied it', () => {
    const state = emptyState({
      allSpots: [spot({ driveMinutes: 120 })], tierId: '45', limitMinutes: 45,
    });
    expect(state.headline).toBe('Nothing within 45 min in this window.');
  });

  it('names the floor when the floor alone emptied it', () => {
    const state = emptyState({ allSpots: [spot({ rating: 2 })], floorId: '4', minRating: 4 });
    expect(state.headline).toBe('Nothing at 4★+ in this window.');
  });

  it('names both when both are set, floor first, as the handoff writes it', () => {
    const state = emptyState({
      allSpots: [spot({ rating: 2, driveMinutes: 120 })],
      tierId: '45',
      limitMinutes: 45,
      floorId: '4',
      minRating: 4,
    });
    expect(state.headline).toBe('Nothing at 4★+ within 45 min in this window.');
  });
});

describe('windowLensEmpty — the body states the population, never just "nothing"', () => {
  it('counts what is beyond the tier when reach alone gated', () => {
    // Plan §5a's own sentence, unchanged: the useful fact is not that the window is empty but that
    // widening would fill it.
    const state = emptyState({
      allSpots: [spot({ key: 'a', driveMinutes: 120 }), spot({ key: 'b', driveMinutes: 200 })],
      tierId: '45',
      limitMinutes: 45,
    });
    expect(state.body).toBe('2 spots are further out.');
  });

  it('agrees with itself for one spot', () => {
    const state = emptyState({
      allSpots: [spot({ driveMinutes: 120 })], tierId: '45', limitMinutes: 45,
    });
    expect(state.body).toBe('1 spot is further out.');
  });

  it('names the nearest qualifying spot when the floor is met further out', () => {
    // The handoff's own sentence, with real counts and a real distance: what the reader wants is
    // not "nothing" but "eleven, and the nearest is an hour away".
    const state = emptyState({
      allSpots: [
        spot({ key: 'a', rating: 4, driveMinutes: 120 }),
        spot({ key: 'b', rating: 5, driveMinutes: 66 }),
        spot({ key: 'c', rating: 2, driveMinutes: 10 }),
      ],
      tierId: '45',
      limitMinutes: 45,
      floorId: '4',
      minRating: 4,
    });
    expect(state.body).toBe('2 spots here are rated 4★+ — the nearest is 1h 6min away.');
  });

  it('says a window has not been rated yet rather than that none of it is good enough', () => {
    // At T+3 and beyond the batch has usually scored nothing. "none rated 4★+" over a window nobody
    // looked at says the forecast was poor when there is no forecast — the same distinction the
    // AWAITING verdict exists to draw.
    const state = emptyState({
      allSpots: [spot({ key: 'a', rating: null }), spot({ key: 'b', rating: null })],
      floorId: '4',
      minRating: 4,
    });
    expect(state.body).toBe('Nothing in this window has been rated yet.');
  });

  it('counts the pool the floor acted on when the window is rated but not well enough', () => {
    const state = emptyState({
      allSpots: [spot({ key: 'a', rating: 2 }), spot({ key: 'b', rating: 3 })],
      floorId: '4',
      minRating: 4,
    });
    expect(state.body).toBe('2 spots here, none rated 4★+.');
  });

  it('says both when everything is too far AND below the floor', () => {
    const state = emptyState({
      allSpots: [spot({ rating: 2, driveMinutes: 200 })],
      tierId: '45',
      limitMinutes: 45,
      floorId: '4',
      minRating: 4,
    });
    expect(state.body).toBe('1 spot here is further out, and none is rated 4★+.');
  });
});

describe('windowLensEmpty — every action offered actually works', () => {
  it('skips a step that would change nothing and names the one that does', () => {
    // The handoff says "the next reach step up". Taken literally that ships a control that widens
    // the lens and leaves the card as empty — a demo control §6 bans, by a longer route. The
    // nearest step that PASSES is what gets named.
    const state = emptyState({
      allSpots: [spot({ driveMinutes: 140 })], tierId: '45', limitMinutes: 45,
    });
    expect(state.actions).toEqual([{ kind: 'reach', id: '150', label: 'Try 2h 30min' }]);
  });

  it('offers nothing at all when no single loosening would help', () => {
    // Unrated AND two hours out. Widening reach leaves the 4★ floor to fail it; dropping the floor
    // leaves the 45-minute tier to. Each candidate is tested ALONE, so neither is offered — the
    // honest answer is a sentence and no buttons, not a button that changes nothing.
    const state = emptyState({
      allSpots: [spot({ rating: null, driveMinutes: 200 })],
      tierId: '45',
      limitMinutes: 45,
      floorId: '4',
      minRating: 4,
    });
    expect(state.actions).toEqual([]);
  });

  it('offers the floor step when dropping it alone would fill the card', () => {
    const state = emptyState({
      allSpots: [spot({ rating: 3, driveMinutes: 10 })], floorId: '4', minRating: 4,
    });
    expect(state.actions).toEqual([{ kind: 'rating', id: '3', label: 'Drop to 3★+' }]);
  });

  it('offers both, and only then says "Or"', () => {
    // A lone rating action reads as an afterthought if it opens with "Or"; a second one reads as a
    // separate instruction if it does not.
    const state = emptyState({
      allSpots: [
        spot({ key: 'a', rating: 4, driveMinutes: 80 }),
        spot({ key: 'b', rating: 3, driveMinutes: 10 }),
      ],
      tierId: '45',
      limitMinutes: 45,
      floorId: '4',
      minRating: 4,
    });
    expect(state.actions).toEqual([
      { kind: 'reach', id: '90', label: 'Try 1h 30min' },
      { kind: 'rating', id: '3', label: 'Or drop to 3★+' },
    ]);
  });

  it('walks the floor ladder all the way to "any rating" when the nearer step is no use', () => {
    const state = emptyState({
      allSpots: [spot({ rating: 1, driveMinutes: 10 })], floorId: '4', minRating: 4,
    });
    expect(state.actions).toEqual([{ kind: 'rating', id: 'any', label: 'Drop to any rating' }]);
  });

  it('offers a LITE reader no reach action, with no role anywhere in the path', () => {
    // `useReachLens` pins a locked user to "Any", so there is no wider tier to walk to. The gate is
    // the ladder itself rather than a role test — which is why nothing role-shaped reaches here.
    const state = emptyState({
      allSpots: [spot({ rating: 2, driveMinutes: 200 })],
      tierId: 'any',
      limitMinutes: null,
      floorId: '4',
      minRating: 4,
    });
    expect(state.actions.some((a) => a.kind === 'reach')).toBe(false);
    // 3★+ would not reach a 2★ spot either, so the ladder walks past it to the step that does.
    expect(state.actions).toEqual([{ kind: 'rating', id: 'any', label: 'Drop to any rating' }]);
  });

  it('names no reach step when the bar is already on the widest tier', () => {
    const state = emptyState({
      allSpots: [spot({ rating: null })], tierId: 'any', limitMinutes: null, floorId: '3', minRating: 3,
    });
    // The floor step is still offered, and correctly: an unrated spot passes "Any rating".
    expect(state.actions).toEqual([{ kind: 'rating', id: 'any', label: 'Drop to any rating' }]);
  });
});
