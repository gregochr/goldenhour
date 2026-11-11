import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import DailySpendChart from '../components/runs/cost/DailySpendChart.jsx';
import { costResponse, costDay } from './runsCostTimingFixtures.js';

const measured = vi.hoisted(() => ({ width: 0 }));
vi.mock('../hooks/useMeasuredWidth.js', () => ({
  default: () => [null, measured.width],
}));

const props = (over = {}) => ({
  data: costResponse(),
  selected: null,
  onSelect: vi.fn(),
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

  it('a chip toggles its type in the selection (joined, canonical order); All types clears it', () => {
    const onSelect = vi.fn();
    const { rerender } = render(<DailySpendChart {...props({ selected: 'SKY_EVAL', onSelect })} />);
    fireEvent.click(screen.getByTestId('runs-cost-iso-sky_eval'));
    expect(onSelect).toHaveBeenLastCalledWith(null);
    fireEvent.click(screen.getByTestId('runs-cost-iso-ask_ready'));
    expect(onSelect).toHaveBeenLastCalledWith('ASK_READY,SKY_EVAL');
    fireEvent.click(screen.getByTestId('runs-cost-iso-all'));
    expect(onSelect).toHaveBeenLastCalledWith(null);
    rerender(<DailySpendChart {...props({ selected: 'OVERNIGHT,EVENING', onSelect })} />);
    expect(screen.getByTestId('runs-cost-iso-overnight')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-cost-iso-evening')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-cost-iso-afternoon')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('runs-cost-iso-all')).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(screen.getByTestId('runs-cost-iso-afternoon'));
    expect(onSelect).toHaveBeenLastCalledWith('OVERNIGHT,AFTERNOON,EVENING');
  });

  it('writes null, never a seven-entry list, when the last type is picked or the last one removed', () => {
    const onSelect = vi.fn();
    const six = 'OVERNIGHT,AFTERNOON,EVENING,ASK_READY,BRIEFING,SKY_EVAL';
    render(<DailySpendChart {...props({ selected: six, onSelect })} />);
    fireEvent.click(screen.getByTestId('runs-cost-iso-other'));
    expect(onSelect).toHaveBeenLastCalledWith(null);
    onSelect.mockClear();
    cleanup();
    render(<DailySpendChart {...props({ selected: 'EVENING', onSelect })} />);
    fireEvent.click(screen.getByTestId('runs-cost-iso-evening'));
    expect(onSelect).toHaveBeenLastCalledWith(null);
  });

  it('dims the types that are not selected and draws all of them with no selection', () => {
    const { unmount } = render(<DailySpendChart {...props({ selected: 'ASK_READY,OVERNIGHT' })} />);
    const bar = screen.getByTestId('runs-cost-bar-2026-11-01');
    expect(bar.querySelector('[data-type="ASK_READY"]')).toHaveAttribute('opacity', '1');
    expect(bar.querySelector('[data-type="OVERNIGHT"]')).toHaveAttribute('opacity', '1');
    expect(bar.querySelector('[data-type="SKY_EVAL"]')).toHaveAttribute('opacity', '0.12');
    unmount();
    render(<DailySpendChart {...props()} />);
    expect(screen.getByTestId('runs-cost-bar-2026-11-01').querySelector('[data-type="SKY_EVAL"]')).toHaveAttribute('opacity', '1');
  });

  it('totals the selection under the chips, live, with "so far" when a type is still accruing', () => {
    const data = costResponse();
    const { rerender } = render(<DailySpendChart {...props({ data, selected: 'OVERNIGHT,AFTERNOON' })} />);
    const line = screen.getByTestId('runs-cost-selection-total');
    expect(line).toHaveTextContent('Overnight cycle + Afternoon refresh: £61.25 over the last 14 days');
    expect(line).not.toHaveTextContent('so far');
    expect(line).toHaveAttribute('aria-live', 'polite');
    const accruing = { ...data, types: data.types.map((t) => (t.type === 'OVERNIGHT' ? { ...t, costComplete: false } : t)) };
    rerender(<DailySpendChart {...props({ data: accruing, selected: 'OVERNIGHT' })} />);
    expect(screen.getByTestId('runs-cost-selection-total')).toHaveTextContent('Overnight cycle: £38.31 over the last 14 days so far');
    rerender(<DailySpendChart {...props({ data, selected: null })} />);
    expect(screen.queryByTestId('runs-cost-selection-total')).toBeNull();
  });

  it('words the all-time selection total with the first day', () => {
    const data = costResponse({ allTime: true, firstDay: '2026-02-26' });
    render(<DailySpendChart {...props({ data, selected: 'OVERNIGHT', range: 'all' })} />);
    expect(screen.getByTestId('runs-cost-selection-total')).toHaveTextContent('since 26 Feb 2026');
  });

  it('shows a Selected subtotal row in the tooltip only with two or more types selected', () => {
    const { rerender } = render(<DailySpendChart {...props({ selected: 'ASK_READY,OVERNIGHT' })} />);
    fireEvent.mouseEnter(screen.getByTestId('runs-cost-day-2026-11-01'));
    expect(screen.getByTestId('runs-cost-tooltip')).toHaveTextContent('Selected');
    expect(screen.getByTestId('runs-cost-tooltip-selected')).toHaveTextContent('£3.50');
    const heightMulti = Number(screen.getByTestId('runs-cost-tooltip').querySelector('rect').getAttribute('height'));
    rerender(<DailySpendChart {...props({ selected: 'ASK_READY' })} />);
    expect(screen.getByTestId('runs-cost-tooltip')).not.toHaveTextContent('Selected');
    const heightOne = Number(screen.getByTestId('runs-cost-tooltip').querySelector('rect').getAttribute('height'));
    expect(heightMulti).toBe(heightOne + 15);
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
