/**
 * `openMapDoor` (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 1) — `WindowFirstShell.jsx`'s
 * `openMapTab` wrapper has no caller yet (no door UI ships until D3/D4), so this is the "test-only
 * caller" the plan's own text promises D2 delivers: a pure function, callable directly with plain
 * `vi.fn()` collaborators, no component render required.
 */
import { describe, it, expect, vi } from 'vitest';
import { openMapDoor } from '../utils/mapDoors.js';

const DOOR = { date: '2026-09-05', targetType: 'SUNSET', region: 'Lake District', locationName: 'Keswick View' };

function collaborators(overrides = {}) {
  return {
    openOverPopup: vi.fn(),
    openWindow: vi.fn(),
    onOpenMapTab: vi.fn(),
    ratingLens: { minRating: 4 },
    reachLens: { tier: { limitMinutes: 150 } },
    door: DOOR,
    ...overrides,
  };
}

describe('openMapDoor — closes, then moves, in order', () => {
  it('calls openOverPopup(null), then openWindow(null), then onOpenMapTab — in that order', () => {
    const c = collaborators();
    openMapDoor(c);

    expect(c.openOverPopup).toHaveBeenCalledTimes(1);
    expect(c.openOverPopup).toHaveBeenCalledWith(null);
    expect(c.openWindow).toHaveBeenCalledTimes(1);
    expect(c.openWindow).toHaveBeenCalledWith(null);
    expect(c.onOpenMapTab).toHaveBeenCalledTimes(1);

    const order = [c.openOverPopup, c.openWindow, c.onOpenMapTab]
      .map((fn) => fn.mock.invocationCallOrder[0]);
    expect(order).toEqual([...order].sort((a, b) => a - b));
  });

  it('still performs the close even when onOpenMapTab is undefined (nothing to map)', () => {
    const c = collaborators({ onOpenMapTab: undefined });
    expect(() => openMapDoor(c)).not.toThrow();
    expect(c.openOverPopup).toHaveBeenCalledWith(null);
    expect(c.openWindow).toHaveBeenCalledWith(null);
  });
});

describe('openMapDoor — the lens merge is read live, and overwrites the door\'s own fields', () => {
  it('reads ratingLens.minRating and reachLens.tier.limitMinutes onto the payload', () => {
    const c = collaborators();
    openMapDoor(c);
    expect(c.onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      minRating: 4, limitMinutes: 150,
    }));
  });

  it('OVERWRITES a minRating/limitMinutes already present on the door object — the lens wins, '
      + 'never a value the caller pre-baked', () => {
    const c = collaborators({ door: { ...DOOR, minRating: 1, limitMinutes: 45 } });
    openMapDoor(c);
    expect(c.onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      minRating: 4, limitMinutes: 150,
    }));
  });

  it('passes every other door field through unchanged', () => {
    const c = collaborators();
    openMapDoor(c);
    expect(c.onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      date: '2026-09-05', targetType: 'SUNSET', region: 'Lake District', locationName: 'Keswick View',
    }));
  });

  it('defaults minRating to null — never undefined — when ratingLens itself is null (Any)', () => {
    const c = collaborators({ ratingLens: null });
    openMapDoor(c);
    const payload = c.onOpenMapTab.mock.calls[0][0];
    expect(payload.minRating).toBeNull();
  });

  it('defaults limitMinutes to null when reachLens itself is null', () => {
    const c = collaborators({ reachLens: null });
    openMapDoor(c);
    const payload = c.onOpenMapTab.mock.calls[0][0];
    expect(payload.limitMinutes).toBeNull();
  });

  it('defaults limitMinutes to null when reachLens.tier is null (Any tier, distinct from a '
      + 'missing lens)', () => {
    const c = collaborators({ reachLens: { tier: null } });
    openMapDoor(c);
    const payload = c.onOpenMapTab.mock.calls[0][0];
    expect(payload.limitMinutes).toBeNull();
  });

  it('defaults minRating to null when ratingLens.minRating is itself null (the Any lens, not a '
      + 'missing lens object)', () => {
    const c = collaborators({ ratingLens: { minRating: null } });
    openMapDoor(c);
    const payload = c.onOpenMapTab.mock.calls[0][0];
    expect(payload.minRating).toBeNull();
  });
});
