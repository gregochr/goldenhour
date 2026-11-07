import { describe, it, expect } from 'vitest';
import { formatCostGbp, formatCostUsd } from '../utils/formatCost.js';

describe('formatCost edges', () => {
  it('shows exactly one penny as pounds, not pence', () => {
    expect(formatCostGbp(10000, 1)).toBe('£0.0100');
    expect(formatCostGbp(9000, 1)).toBe('0.900p');
  });

  it('shows exactly one cent as dollars, not cents', () => {
    expect(formatCostUsd(10000)).toBe('$0.0100');
    expect(formatCostUsd(9000)).toBe('0.900¢');
  });

  it('shows a dash for none, zero or a missing rate', () => {
    expect(formatCostGbp(null, 0.8)).toBe('—');
    expect(formatCostGbp(0, 0.8)).toBe('—');
    expect(formatCostGbp(5000, null)).toBe('—');
    expect(formatCostGbp(-5, 0.8)).toBe('—');
    expect(formatCostUsd(null)).toBe('—');
    expect(formatCostUsd(0)).toBe('—');
  });
});
