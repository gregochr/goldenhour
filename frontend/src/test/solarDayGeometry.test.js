import { describe, it, expect } from 'vitest';
import {
  MINUTES_PER_DAY,
  EDGE_PERCENT,
  toMinutes,
  percentOf,
  edgePin,
} from '../components/chart/solarDayGeometry.js';

/**
 * Tests for the axis shared by the tide and surge charts.
 *
 * These helpers were extracted from TideRunRow so a second chart could plot the same local day.
 * The extraction's real proof is that TideRunRow's own tests still pass unedited; this file pins
 * the contract the surge chart now also depends on.
 */
describe('solarDayGeometry', () => {
  it('parses a clock time to minutes past local midnight', () => {
    expect(toMinutes('00:00')).toBe(0);
    expect(toMinutes('05:44')).toBe(344);
    expect(toMinutes('23:59')).toBe(1439);
  });

  it('maps midnight, noon and the last minute across the axis', () => {
    expect(percentOf('00:00')).toBe(0);
    expect(percentOf('12:00')).toBe(50);
    expect(percentOf('23:59')).toBeCloseTo((1439 / MINUTES_PER_DAY) * 100, 5);
  });

  it('pins a label flush at each edge so it cannot overflow the chart', () => {
    expect(edgePin(1)).toEqual({ left: 0, transform: 'none' });
    expect(edgePin(99)).toEqual({ right: 0, left: 'auto', transform: 'none' });
  });

  it('leaves a mid-axis label centre-transformed', () => {
    expect(edgePin(50)).toEqual({});
    // Exactly on the boundary counts as mid-axis, not edge — the comparison is strict.
    expect(edgePin(EDGE_PERCENT)).toEqual({});
  });
});
