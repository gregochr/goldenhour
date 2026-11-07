import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  ASK_BASE_PADDING, NO_INSET, chromeHeightOf, clampPadding, fitPadding, prefersReducedMotion, selectOffset,
} from '../utils/askCamera.js';

const BIG = { x: 2000, y: 2000 };

afterEach(() => {
  vi.unstubAllGlobals();
  delete window.matchMedia;
});

describe('askCamera constants', () => {
  it('holds the base padding and the empty inset exactly, and frozen', () => {
    expect(ASK_BASE_PADDING).toEqual({ top: 80, right: 60, bottom: 60, left: 60 });
    expect(NO_INSET).toEqual({ top: 0, right: 0, bottom: 0, left: 0 });
    expect(Object.isFrozen(ASK_BASE_PADDING)).toBe(true);
    expect(Object.isFrozen(NO_INSET)).toBe(true);
  });
});

describe('clampPadding — edge by edge', () => {
  it('treats a zero or negative side as exactly +0', () => {
    const out = clampPadding(BIG, { top: -0, right: -5, bottom: 0, left: 10 });
    expect(Object.is(out.top, 0)).toBe(true);
    expect(Object.is(out.right, 0)).toBe(true);
    expect(Object.is(out.bottom, 0)).toBe(true);
    expect(out.left).toBe(10);
  });

  it('gives zero padding for a missing padding object', () => {
    expect(clampPadding(BIG, null)).toEqual({ top: 0, right: 0, bottom: 0, left: 0 });
    expect(clampPadding(BIG, undefined)).toEqual({ top: 0, right: 0, bottom: 0, left: 0 });
  });

  it('gives no padding when either dimension is zero, negative or missing', () => {
    const pad = { top: 80, right: 60, bottom: 60, left: 60 };
    const none = { top: 0, right: 0, bottom: 0, left: 0 };
    expect(clampPadding({ x: 0, y: 500 }, pad)).toEqual(none);
    expect(clampPadding({ x: 500, y: 0 }, pad)).toEqual(none);
    expect(clampPadding({ x: -1, y: 500 }, pad)).toEqual(none);
    expect(clampPadding(undefined, pad)).toEqual(none);
    expect(clampPadding(null, pad)).toEqual(none);
  });

  it('leaves an axis alone at exactly the cap and scales one just over it', () => {
    const at = clampPadding({ x: 200, y: 200 }, { top: 60, right: 60, bottom: 60, left: 60 });
    expect(at).toEqual({ top: 60, right: 60, bottom: 60, left: 60 });
    const over = clampPadding({ x: 100, y: 100 }, { top: 40, right: 40, bottom: 40, left: 40 });
    expect(over).toEqual({ top: 30, right: 30, bottom: 30, left: 30 });
  });

  it('honours an explicit maxFraction', () => {
    expect(clampPadding({ x: 100, y: 100 }, { top: 50, right: 50, bottom: 50, left: 50 }, 0.5))
      .toEqual({ top: 25, right: 25, bottom: 25, left: 25 });
  });
});

describe('chromeHeightOf — edge by edge', () => {
  const nodeOf = (value, parent = null) => ({
    style: { getPropertyValue: (name) => (name === '--wf-chrome-tl-h' ? value : '') },
    parentElement: parent,
  });

  it('reads the nearest published positive height', () => {
    expect(chromeHeightOf(nodeOf('52px', nodeOf('90px')))).toBe(52);
  });

  it('skips zero, negative, infinite and unparsable values and keeps walking up', () => {
    expect(chromeHeightOf(nodeOf('0', nodeOf('40')))).toBe(40);
    expect(chromeHeightOf(nodeOf('-5', nodeOf('30')))).toBe(30);
    expect(chromeHeightOf(nodeOf('Infinity', nodeOf('20')))).toBe(20);
    expect(chromeHeightOf(nodeOf('abc', nodeOf('10')))).toBe(10);
    expect(chromeHeightOf(nodeOf('-5'))).toBeNull();
    expect(chromeHeightOf(nodeOf('Infinity'))).toBeNull();
  });

  it('survives ancestors with no style or no getPropertyValue', () => {
    expect(chromeHeightOf({ parentElement: nodeOf('44') })).toBe(44);
    expect(chromeHeightOf({ style: {}, parentElement: nodeOf('45') })).toBe(45);
    expect(chromeHeightOf({ parentElement: null })).toBeNull();
    expect(chromeHeightOf(null)).toBeNull();
  });
});

describe('fitPadding — edge by edge', () => {
  it('takes null and undefined insets as no inset', () => {
    const base = { top: 80, right: 60, bottom: 60, left: 60 };
    expect(fitPadding(BIG, null)).toEqual(base);
    expect(fitPadding(BIG, undefined)).toEqual(base);
    expect(fitPadding(BIG)).toEqual(base);
  });

  it('adds each inset to the base on its own side', () => {
    expect(fitPadding(BIG, { top: 5, right: 7, bottom: 11, left: 13 }))
      .toEqual({ top: 85, right: 67, bottom: 71, left: 73 });
  });

  it('ignores a non-finite chrome height and clears a tall one', () => {
    expect(fitPadding(BIG, NO_INSET, NaN).top).toBe(80);
    expect(fitPadding(BIG, NO_INSET, 36).top).toBe(80);
    expect(fitPadding(BIG, NO_INSET, 100).top).toBe(144);
    expect(fitPadding(BIG, { top: 10, right: 0, bottom: 0, left: 0 }, 100).top).toBe(154);
  });
});

describe('askCamera constants, read from a freshly evaluated module', () => {
  it('evaluates the base padding and the empty inset at load', async () => {
    vi.resetModules();
    const fresh = await import('../utils/askCamera.js');
    expect(fresh.ASK_BASE_PADDING).toEqual({ top: 80, right: 60, bottom: 60, left: 60 });
    expect(fresh.NO_INSET).toEqual({ top: 0, right: 0, bottom: 0, left: 0 });
    expect(fresh.fitPadding({ x: 2000, y: 2000 })).toEqual({ top: 80, right: 60, bottom: 60, left: 60 });
    expect(fresh.selectOffset({ x: 2000, y: 2000 })).toEqual({ dx: 0, dy: 0 });
  });
});

describe('selectOffset', () => {
  it('pushes toward the covered side by half the difference', () => {
    expect(selectOffset(BIG, { top: 0, right: 0, bottom: 200, left: 40 })).toEqual({ dx: -20, dy: 100 });
    expect(selectOffset(BIG, { top: 100, right: 60, bottom: 0, left: 0 })).toEqual({ dx: 30, dy: -50 });
  });
});

describe('prefersReducedMotion', () => {
  it('is true only for a boolean true match', () => {
    window.matchMedia = vi.fn().mockReturnValue({ matches: true });
    expect(prefersReducedMotion()).toBe(true);
    expect(window.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
    window.matchMedia = vi.fn().mockReturnValue({ matches: false });
    expect(prefersReducedMotion()).toBe(false);
    window.matchMedia = vi.fn().mockReturnValue({ matches: 'yes' });
    expect(prefersReducedMotion()).toBe(false);
  });

  it('is false without matchMedia, and without a window', () => {
    delete window.matchMedia;
    expect(prefersReducedMotion()).toBe(false);
    vi.stubGlobal('window', undefined);
    expect(prefersReducedMotion()).toBe(false);
  });
});
