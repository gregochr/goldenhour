import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import TideRangeBar, { tidePosition } from '../components/data/TideRangeBar.jsx';

const FULL = { minLow: -1.5, avgLow: -1.2, avgHigh: 1.4, maxHigh: 1.8, p95High: 1.78 };

describe('tidePosition', () => {
  it('maps -3.5 m to 0%, 0 m to 50% and +3.5 m to 100%', () => {
    expect(tidePosition(-3.5)).toBe(0);
    expect(tidePosition(0)).toBe(50);
    expect(tidePosition(3.5)).toBe(100);
    expect(tidePosition(1.75)).toBeCloseTo(75);
  });

  it('clamps outside the scale to the track', () => {
    expect(tidePosition(-9)).toBe(0);
    expect(tidePosition(12)).toBe(100);
  });

  it('gives null for a missing or non-numeric value, never 0', () => {
    expect(tidePosition(null)).toBeNull();
    expect(tidePosition(undefined)).toBeNull();
    expect(tidePosition('')).toBeNull();
    expect(tidePosition('abc')).toBeNull();
  });
});

describe('TideRangeBar', () => {
  it('positions the bands, the zero line and the P95 tick on the fixed scale', () => {
    render(<TideRangeBar {...FULL} />);
    const full = screen.getByTestId('tide-range-bar-full');
    expect(parseFloat(full.style.left)).toBeCloseTo(((-1.5 + 3.5) / 7) * 100);
    expect(parseFloat(full.style.width)).toBeCloseTo(((1.8 - -1.5) / 7) * 100);
    const avg = screen.getByTestId('tide-range-bar-avg');
    expect(parseFloat(avg.style.left)).toBeCloseTo(((-1.2 + 3.5) / 7) * 100);
    expect(parseFloat(avg.style.width)).toBeCloseTo(((1.4 - -1.2) / 7) * 100);
    expect(screen.getByTestId('tide-range-bar-zero').style.left).toBe('50%');
    expect(parseFloat(screen.getByTestId('tide-range-bar-p95').style.left)).toBeCloseTo(((1.78 + 3.5) / 7) * 100);
  });

  it('uses the spec colours: light band, darker band, amber tick', () => {
    render(<TideRangeBar {...FULL} />);
    expect(screen.getByTestId('tide-range-bar-full').className).toContain('bg-tide/[.22]');
    expect(screen.getByTestId('tide-range-bar-avg').className).toContain('bg-tide/[.62]');
    expect(screen.getByTestId('tide-range-bar-p95').className).toContain('bg-verdict-marginal');
  });

  it('clamps a band that runs off the scale to the track edges', () => {
    render(<TideRangeBar minLow={-6} avgLow={-5} avgHigh={5} maxHigh={7} p95High={9} />);
    const full = screen.getByTestId('tide-range-bar-full');
    expect(full.style.left).toBe('0%');
    expect(full.style.width).toBe('100%');
    expect(screen.getByTestId('tide-range-bar-p95').style.left).toBe('100%');
  });

  it('drops a band whose endpoint is null, and the tick when P95 is null', () => {
    render(<TideRangeBar minLow={null} avgLow={-1.2} avgHigh={1.4} maxHigh={1.8} p95High={null} />);
    expect(screen.queryByTestId('tide-range-bar-full')).not.toBeInTheDocument();
    expect(screen.getByTestId('tide-range-bar-avg')).toBeInTheDocument();
    expect(screen.queryByTestId('tide-range-bar-p95')).not.toBeInTheDocument();
    expect(screen.getByTestId('tide-range-bar-zero')).toBeInTheDocument();
  });

  it('draws the large variant taller, with thicker bands and a longer tick', () => {
    render(<TideRangeBar {...FULL} size="lg" />);
    expect(screen.getByTestId('tide-range-bar').className).toContain('h-[30px]');
    expect(screen.getByTestId('tide-range-bar-full').className).toContain('h-3');
    expect(screen.getByTestId('tide-range-bar-p95').className).toContain('h-[22px]');
  });

  it('draws the default variant with 8px bands and a 14px tick', () => {
    render(<TideRangeBar {...FULL} />);
    expect(screen.getByTestId('tide-range-bar-full').className).toContain('h-2');
    expect(screen.getByTestId('tide-range-bar-p95').className).toContain('h-3.5');
  });

  it('reads the numbers in its accessible name', () => {
    render(<TideRangeBar {...FULL} />);
    expect(screen.getByRole('img')).toHaveAccessibleName(
      'Tide range: lowest low -1.50 m to highest high 1.80 m; average low -1.20 m to average high 1.40 m; P95 high 1.78 m',
    );
  });

  it('leaves out of its name whatever is missing, and says so when everything is', () => {
    const { rerender } = render(<TideRangeBar {...FULL} p95High={null} />);
    expect(screen.getByRole('img').getAttribute('aria-label')).not.toContain('P95');
    rerender(<TideRangeBar />);
    expect(screen.getByRole('img')).toHaveAccessibleName('Tide range: no data');
  });
});
