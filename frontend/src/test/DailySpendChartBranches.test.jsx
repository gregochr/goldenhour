import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import DailySpendChart from '../components/runs/cost/DailySpendChart.jsx';
import { costResponse, costDay } from './runsCostTimingFixtures.js';

const measured = vi.hoisted(() => ({ width: 0 }));
vi.mock('../hooks/useMeasuredWidth.js', () => ({
  default: () => [null, measured.width],
}));

const props = (over = {}) => ({
  data: costResponse(),
  isolate: null,
  onIsolate: vi.fn(),
  range: 14,
  onRangeChange: vi.fn(),
  onOpenDay: vi.fn(),
  ...over,
});

beforeEach(() => { measured.width = 0; });

describe('DailySpendChart branches', () => {
  it('draws to the measured width when the card has one, else to the 800 fallback', () => {
    measured.width = 500;
    const { unmount } = render(<DailySpendChart {...props()} />);
    expect(screen.getByTestId('runs-cost-svg')).toHaveAttribute('viewBox', '0 0 500 260');
    unmount();
    measured.width = 0;
    render(<DailySpendChart {...props()} />);
    expect(screen.getByTestId('runs-cost-svg')).toHaveAttribute('viewBox', '0 0 800 260');
  });

  it('clicking the already isolated chip clears the isolation, clicking another isolates it', () => {
    const onIsolate = vi.fn();
    render(<DailySpendChart {...props({ isolate: 'SKY_EVAL', onIsolate })} />);
    fireEvent.click(screen.getByTestId('runs-cost-iso-sky_eval'));
    expect(onIsolate).toHaveBeenLastCalledWith(null);
    fireEvent.click(screen.getByTestId('runs-cost-iso-ask_ready'));
    expect(onIsolate).toHaveBeenLastCalledWith('ASK_READY');
    fireEvent.click(screen.getByTestId('runs-cost-iso-all'));
    expect(onIsolate).toHaveBeenLastCalledWith(null);
  });

  it('draws no bars and says so when there are no days at all', () => {
    const data = costResponse({ days: [] });
    render(<DailySpendChart {...props({ data })} />);
    expect(screen.getByTestId('runs-cost-empty')).toHaveTextContent('No spend in the last 14 days.');
    expect(screen.queryAllByRole('button', { name: /Open this day/ })).toHaveLength(0);
  });

  it('copes with a day that has no byType breakdown (no bar segments, a total-only tooltip)', () => {
    const noBreakdown = { date: '2026-11-01', totalGbp: 1.5, costComplete: false };
    const data = costResponse({ rangeDays: 1, days: [noBreakdown] });
    render(<DailySpendChart {...props({ data, range: 14 })} />);
    expect(screen.getByTestId('runs-cost-bar-2026-11-01')).toBeEmptyDOMElement();
    fireEvent.mouseEnter(screen.getByTestId('runs-cost-day-2026-11-01'));
    expect(screen.getByTestId('runs-cost-tooltip')).toHaveTextContent('Total');
    expect(screen.getByTestId('runs-cost-tooltip')).toHaveTextContent('so far');
  });

  it('labels a small-spend axis in pence-sized pounds (two decimals) and a mid-size one with one decimal', () => {
    const small = costResponse({
      rangeDays: 2,
      days: [costDay('2026-10-31', { OTHER: 0.04 }), costDay('2026-11-01', { OTHER: 0.06 })],
    });
    const { unmount } = render(<DailySpendChart {...props({ data: small })} />);
    expect(screen.getByTestId('runs-cost-svg')).toHaveTextContent('£0.02');
    unmount();
    const mid = costResponse({
      rangeDays: 2,
      days: [costDay('2026-10-31', { OTHER: 3 }), costDay('2026-11-01', { OTHER: 9 })],
    });
    render(<DailySpendChart {...props({ data: mid })} />);
    expect(screen.getByTestId('runs-cost-svg')).toHaveTextContent('£2.5');
    expect(screen.getByTestId('runs-cost-svg')).toHaveTextContent('£10');
  });
});
