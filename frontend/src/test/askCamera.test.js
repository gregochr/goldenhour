import { describe, it, expect, afterEach, vi } from 'vitest';
import {
  ASK_BASE_PADDING, ASK_FIT_MAX_ZOOM, ASK_HEAT_DIM, ASK_PADDING_MAX_FRACTION, ASK_SELECT_ZOOM,
  clampPadding, fitPadding, NO_INSET, prefersReducedMotion, selectOffset,
} from '../utils/askCamera.js';

/**
 * Ask PhotoCast's camera arithmetic (F3), on plain numbers. The Leaflet half — that these figures
 * keep every pick inside a 400px-tall frame — is `AskCameraController.test.jsx`'s, on a real map.
 */

const FRAME = { x: 800, y: 400 };

describe('the constants are the design\'s and the plan\'s', () => {
  it('flies to 10.5, fits no closer than 10, dims the heat to 0.36 and caps padding at 60%', () => {
    expect(ASK_SELECT_ZOOM).toBe(10.5);
    expect(ASK_FIT_MAX_ZOOM).toBe(10);
    expect(ASK_HEAT_DIM).toBe(0.36);
    expect(ASK_PADDING_MAX_FRACTION).toBe(0.6);
  });
});

describe('clampPadding', () => {
  it('leaves padding that is within 60% of the frame on both axes exactly as it was', () => {
    const pad = {
      top: 80, right: 60, bottom: 60, left: 60,
    };
    expect(clampPadding(FRAME, pad)).toEqual(pad);
  });

  it('keeps the proportions of an axis that is over, scaling the pair to exactly 60% of the frame', () => {
    const out = clampPadding(FRAME, {
      top: 0, right: 0, bottom: 490, left: 0,
    });
    expect(out.bottom).toBeCloseTo(240, 9);
    const both = clampPadding(FRAME, {
      top: 100, right: 0, bottom: 400, left: 0,
    });
    // 500 asked, 240 allowed: both scale by 240/500 and keep their 1:4 ratio.
    expect(both.top).toBeCloseTo(48, 9);
    expect(both.bottom).toBeCloseTo(192, 9);
    expect(both.top + both.bottom).toBeCloseTo(FRAME.y * 0.6, 9);
  });

  it('clamps each axis on its own — a tall frame is not narrowed because a wide one is crowded', () => {
    const out = clampPadding({ x: 300, y: 1000 }, {
      top: 80, right: 380, bottom: 60, left: 60,
    });
    expect(out.left + out.right).toBeCloseTo(180, 9);
    expect(out.top).toBe(80);
    expect(out.bottom).toBe(60);
  });

  it('a hidden map (no size yet) gets no padding at all, never a negative one', () => {
    const zero = {
      top: 0, right: 0, bottom: 0, left: 0,
    };
    expect(clampPadding({ x: 0, y: 0 }, ASK_BASE_PADDING)).toEqual(zero);
    expect(clampPadding({ x: 800, y: 0 }, ASK_BASE_PADDING)).toEqual(zero);
    expect(clampPadding(undefined, ASK_BASE_PADDING)).toEqual(zero);
  });

  it('treats a missing, negative or non-finite side as 0', () => {
    expect(clampPadding(FRAME, {
      top: -10, right: NaN, bottom: undefined, left: Infinity,
    })).toEqual({
      top: 0, right: 0, bottom: 0, left: 0,
    });
  });

  it('honours a different fraction', () => {
    const out = clampPadding(FRAME, { bottom: 300, top: 0 }, 0.25);
    expect(out.bottom).toBeCloseTo(100, 9);
  });
});

describe('fitPadding', () => {
  it('is the chrome\'s clearance alone with no covering surface — zero beyond the base on a desktop dock', () => {
    expect(fitPadding({ x: 1200, y: 900 }, NO_INSET)).toEqual(ASK_BASE_PADDING);
    expect(fitPadding({ x: 1200, y: 900 })).toEqual(ASK_BASE_PADDING);
  });

  it('adds what a surface covers, then clamps the sum to the frame', () => {
    const out = fitPadding(FRAME, {
      top: 0, right: 0, bottom: 490, left: 0,
    });
    expect(out.top + out.bottom).toBeLessThanOrEqual(FRAME.y * 0.6 + 1e-9);
    // Proportional: the 490px surface still takes the larger share of the vertical budget.
    expect(out.bottom).toBeGreaterThan(out.top);
  });

  it('never returns a negative figure whatever it is given', () => {
    const out = fitPadding({ x: 50, y: 50 }, {
      top: 900, right: 900, bottom: 900, left: 900,
    });
    for (const side of Object.values(out)) expect(side).toBeGreaterThanOrEqual(0);
  });
});

describe('selectOffset', () => {
  it('is zero on a bare map — a selected pick is centred', () => {
    expect(selectOffset(FRAME, NO_INSET)).toEqual({ dx: 0, dy: 0 });
  });

  it('pushes the centre toward a covered side by half of what covers it', () => {
    expect(selectOffset({ x: 1000, y: 800 }, {
      top: 0, right: 300, bottom: 0, left: 0,
    })).toEqual({ dx: 150, dy: 0 });
    expect(selectOffset({ x: 1000, y: 800 }, {
      top: 0, right: 0, bottom: 200, left: 0,
    })).toEqual({ dx: 0, dy: 100 });
    expect(selectOffset({ x: 1000, y: 800 }, {
      top: 100, right: 0, bottom: 0, left: 60,
    })).toEqual({ dx: -30, dy: -50 });
  });

  it('uses the same 60% clamp, so a surface bigger than the frame cannot throw the pick off it', () => {
    const { dy } = selectOffset(FRAME, {
      top: 0, right: 0, bottom: 2000, left: 0,
    });
    expect(dy).toBeCloseTo(FRAME.y * 0.6 / 2, 9);
  });
});

describe('prefersReducedMotion', () => {
  const original = window.matchMedia;
  afterEach(() => { window.matchMedia = original; });

  it('is true only when the system asks for reduced motion', () => {
    window.matchMedia = vi.fn().mockReturnValue({ matches: true });
    expect(prefersReducedMotion()).toBe(true);
    expect(window.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
    window.matchMedia = vi.fn().mockReturnValue({ matches: false });
    expect(prefersReducedMotion()).toBe(false);
  });

  it('is false where matchMedia does not exist', () => {
    window.matchMedia = undefined;
    expect(prefersReducedMotion()).toBe(false);
  });
});
