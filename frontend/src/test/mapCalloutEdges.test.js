/**
 * Edge behaviour of `utils/mapCallout.js`: which chrome bars bind the band, the exact-fit and
 * flip boundaries of the card anchor, and the dark-sky threshold. Companion to mapCallout.test.js;
 * each case pins an answer the happy-path suite never asks for.
 */
import { describe, it, expect } from 'vitest';
import { anchorCallout, calloutBand, calloutFacts } from '../utils/mapCallout.js';
import { DARK_SKY_THRESHOLD } from '../utils/darkSky.js';

const FRAME = { frameWidth: 400, frameHeight: 600 };
const bar = (over = {}) => ({
  top: 500, bottom: 560, left: 0, right: 400, width: 400, height: 60, ...over,
});

describe('calloutBand — which bars count', () => {
  it('ignores a null entry among real bars', () => {
    expect(calloutBand({ ...FRAME, bars: [null, bar()] }).bot).toBe(492);
  });

  it('ignores a bar of zero width or zero height', () => {
    expect(calloutBand({ ...FRAME, bars: [bar({ width: 0 })] }).bot).toBe(592);
    expect(calloutBand({ ...FRAME, bars: [bar({ height: 0 })] }).bot).toBe(592);
  });

  it('ignores a bar exactly one pixel wide or one pixel tall', () => {
    expect(calloutBand({ ...FRAME, bars: [bar({ width: 1, always: true })] }).bot).toBe(592);
    expect(calloutBand({ ...FRAME, bars: [bar({ height: 1 })] }).bot).toBe(592);
  });

  it('counts a bar just over one pixel in both dimensions', () => {
    expect(calloutBand({ ...FRAME, bars: [bar({ width: 2, always: true, height: 2 })] }).bot).toBe(492);
  });

  it('ignores a non-array bars value', () => {
    expect(calloutBand({ ...FRAME, bars: 'footer' })).toEqual({ top: 8, bot: 592 });
    expect(calloutBand({ ...FRAME, bars: null })).toEqual({ top: 8, bot: 592 });
  });
});

describe('calloutBand — an overlapOnly bar and the card span', () => {
  const strip = bar({
    left: 100, right: 250, width: 150, height: 50, overlapOnly: true,
  });
  const bot = (cardSpan) => calloutBand({ ...FRAME, bars: [strip], cardSpan }).bot;

  it('ignores the bar when the card lies wholly to its left', () => {
    expect(bot({ left: 0, right: 99 })).toBe(592);
  });

  it('ignores the bar when the card only touches its left edge', () => {
    expect(bot({ left: 0, right: 100 })).toBe(592);
  });

  it('counts the bar when the card overlaps its left edge by a pixel', () => {
    expect(bot({ left: 0, right: 101 })).toBe(492);
  });

  it('ignores the bar when the card lies wholly to its right', () => {
    expect(bot({ left: 251, right: 380 })).toBe(592);
  });

  it('ignores an overlapOnly bar whose own edges are not numbers', () => {
    const edgeless = { ...strip, left: undefined, right: undefined };
    expect(calloutBand({ ...FRAME, bars: [edgeless], cardSpan: { left: 0, right: 400 } }).bot).toBe(592);
  });
});

describe('anchorCallout — fit and flip boundaries', () => {
  const band = { top: 0, bot: 200 };
  const anchor = (point, over = {}) => anchorCallout({
    point, cardWidth: 100, cardHeight: 78, frameWidth: 400, band, ...over,
  });

  it('places the card below when it fits exactly against the band floor', () => {
    expect(anchor({ x: 200, y: 100 })).toEqual({ left: 150, top: 122, below: true });
  });

  it('places the card above when it overshoots the band floor by a pixel', () => {
    expect(anchor({ x: 200, y: 101 })).toEqual({ left: 150, top: 1, below: false });
  });

  it('keeps the card above when it ends exactly on the band ceiling', () => {
    // 130 - 22 - 100 = 8 = band.top: it fits, so it does not flip below.
    const result = anchorCallout({
      point: { x: 200, y: 130 },
      cardWidth: 100,
      cardHeight: 100,
      frameWidth: 400,
      band: { top: 8, bot: 200 },
    });
    expect(result).toEqual({ left: 150, top: 8, below: false });
  });

  it('flips below, and measures the card from below the marker, when above would leave the band', () => {
    const result = anchorCallout({
      point: { x: 200, y: 60 },
      cardWidth: 100,
      cardHeight: 100,
      frameWidth: 400,
      band: { top: 8, bot: 150 },
    });
    // Flipped: top = 60 + 22 = 82, then clamped to the last position that keeps the card in the band (50).
    expect(result).toEqual({ left: 150, top: 50, below: true });
  });
});

describe('calloutFacts — the dark-sky threshold', () => {
  const dark = (bortleClass) => calloutFacts({ driveMinutes: null, bortleClass })[0];

  it('labels a sky exactly at the threshold dark', () => {
    expect(dark(DARK_SKY_THRESHOLD)).toEqual({
      key: 'dark', label: 'Dark sky', value: `${DARK_SKY_THRESHOLD} · dark`,
    });
  });

  it('labels a sky one class brighter than the threshold with its bare class', () => {
    expect(dark(DARK_SKY_THRESHOLD + 1)).toEqual({
      key: 'dark', label: 'Dark sky', value: `${DARK_SKY_THRESHOLD + 1}`,
    });
  });

  it('labels a sky one class darker than the threshold dark', () => {
    expect(dark(DARK_SKY_THRESHOLD - 1).value).toBe(`${DARK_SKY_THRESHOLD - 1} · dark`);
  });
});
