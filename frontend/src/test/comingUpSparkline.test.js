import { describe, it, expect } from 'vitest';
import {
  SPARKLINE_MIN_AMPLITUDE, SPARKLINE_MAX_AMPLITUDE, SPARKLINE_AXIS_Y, SPARKLINE_VIEW_W,
  SPARKLINE_MARKER_X,
  tideSparklineAmplitude, tideWavePath, tideMarkerY,
} from '../utils/comingUpSparkline.js';

describe('tideSparklineAmplitude', () => {
  it('reads the floor amplitude for a run exactly at its port average (delta 0)', () => {
    expect(tideSparklineAmplitude(0)).toBe(SPARKLINE_MIN_AMPLITUDE);
  });

  it('grows with how far the range sits above the port average', () => {
    // 3 + 1.9 * 3.5 = 9.65
    expect(tideSparklineAmplitude(1.9)).toBeCloseTo(9.65, 5);
  });

  it('never exceeds the cap, however large the run', () => {
    expect(tideSparklineAmplitude(12)).toBe(SPARKLINE_MAX_AMPLITUDE);
  });

  it('never drops below the floor for a below-average delta — clamped, not just capped above', () => {
    // 3 + (-1.3) * 3.5 = -1.55 unclamped — the floor is what turns that into a flat, honest wave
    // instead of a negative amplitude, which would invert the wave and put the marker on the wrong
    // side of the axis (see the function's own doc for why that matters).
    expect(tideSparklineAmplitude(-1.3)).toBe(SPARKLINE_MIN_AMPLITUDE);
  });

  it('never goes negative even deep below average — the floor, not merely "less negative"', () => {
    expect(tideSparklineAmplitude(-100)).toBe(SPARKLINE_MIN_AMPLITUDE);
    expect(tideSparklineAmplitude(-100)).toBeGreaterThanOrEqual(0);
  });
});

describe('tideWavePath', () => {
  it('starts with an absolute moveto at x=0', () => {
    expect(tideWavePath(5, false)).toMatch(/^M0 /);
  });

  it('samples every 2 units across the full 104-unit viewBox', () => {
    const segments = tideWavePath(5, false).split(/(?=[ML])/).filter(Boolean);
    // 0, 2, 4, …, 104 inclusive — 53 samples.
    expect(segments).toHaveLength(SPARKLINE_VIEW_W / 2 + 1);
  });

  /**
   * Parses `M0 12.00L2 11.15L4 …` into an x → y map, rather than string-matching individual
   * segments (fragile: `L2` is a substring-unsafe prefix to search for against `L20`, `L24`, …).
   */
  function pointsOf(path) {
    return new Map(
      path.match(/[ML]-?\d+(\.\d+)? -?\d+(\.\d+)?/g)
        .map((seg) => seg.slice(1).split(' ').map(Number)),
    );
  }

  it('a LOW-water wave is the exact vertical mirror of a HIGH-water wave at the same amplitude', () => {
    const high = pointsOf(tideWavePath(5, false));
    const low = pointsOf(tideWavePath(5, true));
    expect(high.size).toBeGreaterThan(0);
    // Mirrored about the axis (y=12): high(x) + low(x) = 24 at every sampled x.
    for (const [x, y] of high) {
      expect(y + low.get(x)).toBeCloseTo(2 * SPARKLINE_AXIS_Y, 5);
    }
  });

  it('the wave itself moves the SAME direction as tideMarkerY, not just symmetrically with itself', () => {
    // The mirror test above only proves HIGH and LOW are consistent with EACH OTHER — a mutation
    // that swapped the isLow branches in tideWavePath alone (leaving tideMarkerY untouched) would
    // still pass it, and every tideMarkerY test in isolation, while drawing a wave whose extremum
    // near the marker x no longer sits on the same side of the axis as the marker circle and its
    // dashed lead-line. SPARKLINE_MARKER_X (41) is odd and the path samples only even x, so the
    // nearest sample (40) is checked for AGREEMENT IN SIGN with tideMarkerY's offset from the axis —
    // cos is within ~0.5% of its x=41 extremum there, so the sign can never disagree unless the
    // functions themselves disagree.
    const nearestSampledX = SPARKLINE_MARKER_X - 1;
    for (const isLow of [false, true]) {
      const y = pointsOf(tideWavePath(6, isLow)).get(nearestSampledX);
      const wavePointOffset = y - SPARKLINE_AXIS_Y;
      const markerOffset = tideMarkerY(6, isLow) - SPARKLINE_AXIS_Y;
      expect(Math.sign(wavePointOffset)).toBe(Math.sign(markerOffset));
    }
  });
});

describe('tideMarkerY', () => {
  it('sits above the axis for a marked HIGH water', () => {
    expect(tideMarkerY(6, false)).toBeLessThan(SPARKLINE_AXIS_Y);
  });

  it('sits below the axis for a marked LOW water — the inversion the phase field drives', () => {
    expect(tideMarkerY(6, true)).toBeGreaterThan(SPARKLINE_AXIS_Y);
  });

  it('is exactly the axis plus or minus the amplitude, nothing else', () => {
    expect(tideMarkerY(6, false)).toBe(SPARKLINE_AXIS_Y - 6);
    expect(tideMarkerY(6, true)).toBe(SPARKLINE_AXIS_Y + 6);
  });
});
