import { describe, it, expect } from 'vitest';
import {
  SPARKLINE_AXIS_Y, SPARKLINE_MARKER_X, SPARKLINE_PERIOD, SPARKLINE_SAMPLE_STEP, SPARKLINE_VIEW_W,
  tideWavePath,
} from '../utils/comingUpSparkline.js';

/** The path, built independently from the exported constants. */
function expectedPath(amplitude, isLow) {
  const pieces = [];
  for (let x = 0; x <= SPARKLINE_VIEW_W; x += SPARKLINE_SAMPLE_STEP) {
    const c = Math.cos(((2 * Math.PI) * (x - SPARKLINE_MARKER_X)) / SPARKLINE_PERIOD);
    const y = SPARKLINE_AXIS_Y + (isLow ? amplitude * c : -amplitude * c);
    pieces.push(`${x === 0 ? 'M' : 'L'}${x} ${y.toFixed(2)}`);
  }
  return pieces.join('');
}

describe('comingUpSparkline edges', () => {
  it('draws the high-water wave: one move, then lines, every sample at its cosine', () => {
    const d = tideWavePath(7, false);
    expect(d).toBe(expectedPath(7, false));
    expect(d.startsWith('M0 ')).toBe(true);
    expect(d.match(/M/g)).toHaveLength(1);
    expect(d.match(/L/g)).toHaveLength(SPARKLINE_VIEW_W / SPARKLINE_SAMPLE_STEP);
  });

  it('draws the low-water wave as the mirror image', () => {
    expect(tideWavePath(7, true)).toBe(expectedPath(7, true));
  });

  it('puts the marker column at the wave\'s peak or trough', () => {
    const high = tideWavePath(5, false);
    const low = tideWavePath(5, true);
    // x = 41 is not a sample (step 2), so check the neighbours straddle the extreme at x = 40 and 42.
    expect(high).toContain('L40 ');
    expect(Number(high.match(/L42 ([\d.]+)/)[1])).toBeLessThan(SPARKLINE_AXIS_Y);
    expect(Number(low.match(/L42 ([\d.]+)/)[1])).toBeGreaterThan(SPARKLINE_AXIS_Y);
  });
});
