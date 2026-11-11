import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import DailySpendChart from '../components/runs/cost/DailySpendChart.jsx';
import CostChangeTable from '../components/runs/cost/CostChangeTable.jsx';
import RunsCostView from '../components/runs/cost/RunsCostView.jsx';
import {
  allTimeBody, allTimeHeadline, bucketSeries, sinceLabel,
} from '../utils/runsCost.js';
import { costAllTime, costDays, costResponse } from './runsCostTimingFixtures.js';

// The Cost view's 7, 90 and All time ranges (cost-chart-long-ranges-plan.md): the chart's labels and bar geometry, the
// all-time verdict, the all-time table and its bucketed sparkline, and the phone's short period labels.

const measured = vi.hoisted(() => ({ width: 0 }));
vi.mock('../hooks/useMeasuredWidth.js', () => ({ default: () => [null, measured.width] }));
const mobile = vi.hoisted(() => ({ value: false }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mobile.value }));

const props = (over = {}) => ({
  data: costResponse(),
  selected: null,
  onSelect: vi.fn(),
  range: 14,
  onRangeChange: vi.fn(),
  onOpenDay: vi.fn(),
  ...over,
});

const bars = () => [...document.querySelectorAll('[data-testid^="runs-cost-bar-"]')];
const barRects = () => [...document.querySelectorAll('[data-testid^="runs-cost-bar-"] rect')];
const labelTexts = () => [...screen.getByTestId('runs-cost-svg').querySelectorAll('text')]
  .map((t) => t.textContent).filter((t) => /^\d+ [A-Z][a-z]{2}/.test(t));

beforeEach(() => {
  measured.width = 800;
  mobile.value = false;
});

describe('the period buttons', () => {
  it('offers 7, 14, 30, 60, 90 days and All time', () => {
    render(<DailySpendChart {...props()} />);
    const group = screen.getByTestId('runs-cost-range');
    expect(within(group).getAllByRole('button').map((b) => b.textContent))
      .toEqual(['7 days', '14 days', '30 days', '60 days', '90 days', 'All time']);
  });

  it('passes a number for a day count and the word for All time', () => {
    const onRangeChange = vi.fn();
    render(<DailySpendChart {...props({ onRangeChange })} />);
    fireEvent.click(screen.getByTestId('runs-cost-range-7'));
    expect(onRangeChange).toHaveBeenLastCalledWith(7);
    fireEvent.click(screen.getByTestId('runs-cost-range-90'));
    expect(onRangeChange).toHaveBeenLastCalledWith(90);
    fireEvent.click(screen.getByTestId('runs-cost-range-all'));
    expect(onRangeChange).toHaveBeenLastCalledWith('all');
  });

  it('marks All time pressed', () => {
    render(<DailySpendChart {...props({ range: 'all', data: costAllTime(30) })} />);
    expect(screen.getByTestId('runs-cost-range-all')).toHaveAttribute('aria-pressed', 'true');
  });

  it('shortens the labels on a phone but keeps the long accessible names', () => {
    mobile.value = true;
    render(<DailySpendChart {...props()} />);
    const group = screen.getByTestId('runs-cost-range');
    expect(within(group).getAllByRole('button').map((b) => b.textContent))
      .toEqual(['7d', '14d', '30d', '60d', '90d', 'All']);
    expect(within(group).getByRole('button', { name: '7 days' })).toBeInTheDocument();
    expect(within(group).getByRole('button', { name: 'All time' })).toBeInTheDocument();
  });
});

describe('DailySpendChart at 7 days', () => {
  it('labels every bar, caps the bar width and centres it in its slot', () => {
    const data = costResponse({ rangeDays: 7, days: costDays(7, '2026-11-01', { OVERNIGHT: 1 }) });
    render(<DailySpendChart {...props({ data, range: 7 })} />);
    const rects = barRects();
    expect(rects).toHaveLength(7);
    expect(rects.every((r) => Number(r.getAttribute('width')) === 40)).toBe(true);
    const slot = (800 - 46 - 8) / 7;
    expect(Number(rects[0].getAttribute('x'))).toBeCloseTo(46 + (slot - 40) / 2, 5);
    expect(labelTexts()).toHaveLength(7);
  });

  it('averages the 7 days before the first bar, since 14 days are served', () => {
    const days = costDays(7, '2026-11-01', {});
    days.forEach((d, i) => {
      if (i < 7) {
        d.byType.OVERNIGHT = 7;
        d.totalGbp = 7;
      }
    });
    const data = costResponse({ rangeDays: 7, days });
    render(<DailySpendChart {...props({ data, range: 7 })} />);
    const points = screen.getByTestId('runs-cost-average').getAttribute('points').split(' ');
    expect(points).toHaveLength(7);
    const y = (p) => Number(p.split(',')[1]);
    // The first bar's average still includes the spending days before it (higher on the chart), the last has none.
    expect(y(points[0])).toBeLessThan(y(points[6]));
  });
});

describe('DailySpendChart at 90 days and all time', () => {
  it('draws 90 bars with thinned labels', () => {
    const data = costResponse({ rangeDays: 90, days: costDays(90, '2026-11-01', { OVERNIGHT: 1 }) });
    render(<DailySpendChart {...props({ data, range: 90 })} />);
    const rects = barRects();
    expect(rects).toHaveLength(90);
    expect(rects.every((r) => Number(r.getAttribute('width')) >= 1)).toBe(true);
    expect(labelTexts().length).toBeLessThanOrEqual(12);
    expect(labelTexts().length).toBeGreaterThan(4);
  });

  it('draws every day of a 400-day history with touching bars at least 1px wide and an average point per bar', () => {
    render(<DailySpendChart {...props({ data: costAllTime(400), range: 'all' })} />);
    const rects = barRects();
    expect(bars()).toHaveLength(400);
    expect(rects.every((r) => Number(r.getAttribute('width')) >= 1)).toBe(true);
    const slot = (800 - 46 - 8) / 400;
    const firstX = Number(bars()[0].querySelector('rect').getAttribute('x'));
    const secondX = Number(bars()[1].querySelector('rect').getAttribute('x'));
    expect(secondX - firstX).toBeCloseTo(slot, 5);
    expect(screen.getByTestId('runs-cost-average').getAttribute('points').split(' ')).toHaveLength(400);
  });

  it('says "since <date>" in the accessible name and adds the year to labels outside this year', () => {
    render(<DailySpendChart {...props({ data: costAllTime(400), range: 'all' })} />);
    expect(screen.getByTestId('runs-cost-svg')).toHaveAttribute('aria-label', 'Daily spend by type since 28 Sept 2025');
    expect(labelTexts().some((t) => / 2025$/.test(t))).toBe(true);
  });

  it('says there is no spend since the first day when nothing was spent', () => {
    const data = costAllTime(3, { days: costDays(2, '2026-11-01', {}).slice(1) });
    render(<DailySpendChart {...props({ data, range: 'all' })} />);
    expect(screen.getByTestId('runs-cost-empty')).toHaveTextContent('No spend since 30 Oct 2026.');
  });
});

describe('all-time wording', () => {
  it('words the first day with its year', () => {
    expect(sinceLabel('2026-03-03')).toBe('3 Mar 2026');
  });

  it('heads the verdict with the spend since the first day, and says so when there is none', () => {
    expect(allTimeHeadline(costAllTime(400))).toBe('£600.00 spent since 28 Sept 2025.');
    const none = costAllTime(10, { totals: { ...costAllTime(10).totals, lastGbp: 0 } });
    expect(allTimeHeadline(none)).toBe('No spend recorded since 23 Oct 2026.');
  });

  it('tells the daily average and the biggest type', () => {
    const text = allTimeBody(costAllTime(400)).map((r) => r.text).join('');
    expect(text).toBe('£1.50 a day on average over 400 days. Sky eval is the biggest, at £400.00.');
    expect(allTimeBody(costAllTime(2, { types: [] })).map((r) => r.text).join(''))
      .toBe('£1.50 a day on average over 2 days.');
  });

  it('shows no up or down claim on the verdict card', () => {
    render(<RunsCostView cost={{ data: costAllTime(400), loading: false, error: null, refresh: vi.fn() }}
      range="all" onRangeChange={vi.fn()} selected={null} onSelect={vi.fn()} onOpenDay={vi.fn()} />);
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('£600.00 spent since 28 Sept 2025.');
    expect(screen.getByTestId('runs-cost-body')).toHaveTextContent('Sky eval is the biggest');
    expect(screen.getByTestId('runs-cost-headline').textContent).not.toMatch(/Yes|No\./);
  });
});

describe('bucketSeries', () => {
  it('returns a short series as is and sums a long one into equal buckets', () => {
    const short = [1, 2, 3];
    expect(bucketSeries(short, 65)).toBe(short);
    const buckets = bucketSeries(Array.from({ length: 400 }, () => 1), 65);
    expect(buckets.length).toBeLessThanOrEqual(65);
    expect(buckets.reduce((a, b) => a + b, 0)).toBe(400);
    expect(bucketSeries([1, 1, 1, 1, 1], 2)).toEqual([3, 2]);
  });
});

describe('CostChangeTable in all time', () => {
  const renderAll = (data = costAllTime(400)) => render(
    <CostChangeTable data={data} selected={null} onSelect={vi.fn()} />,
  );

  it('shows only Type, Spend and the daily sparkline, and a since subline', () => {
    renderAll();
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent))
      .toEqual(['Type', 'Spend', 'Daily, 400 days']);
    expect(screen.getByTestId('runs-cost-table-card'))
      .toHaveTextContent('since 28 Sept 2025 · click rows to pick types in the chart');
    expect(screen.queryByTestId('runs-cost-row-sky_eval-change')).toBeNull();
    expect(screen.queryByTestId('runs-cost-row-sky_eval-why')).toBeNull();
    expect(screen.getByTestId('runs-cost-row-sky_eval-last')).toHaveTextContent('£400.00');
  });

  it('totals the spend and the dollars, with no change cell', () => {
    renderAll();
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('£600.00');
    expect(screen.getByTestId('runs-cost-total-usd')).toHaveTextContent('$800');
    expect(screen.queryByTestId('runs-cost-total-change')).toBeNull();
  });

  it('buckets a 400-day sparkline to bars that fit and fades nothing', () => {
    renderAll();
    const rects = [...screen.getByTestId('runs-cost-spark-sky_eval').querySelectorAll('rect')];
    expect(rects.length).toBeLessThanOrEqual(65);
    expect(rects.every((r) => r.getAttribute('opacity') === '1')).toBe(true);
    const last = rects[rects.length - 1];
    expect(Number(last.getAttribute('x')) + Number(last.getAttribute('width'))).toBeLessThanOrEqual(130.001);
  });

  it('toggles a row into the selection and flags a type whose cost is still accruing', () => {
    const data = costAllTime(30);
    data.types[0].costComplete = false;
    data.totals.costComplete = false;
    const onSelect = vi.fn();
    render(<CostChangeTable data={data} selected="SKY_EVAL" onSelect={onSelect} />);
    fireEvent.click(screen.getByTestId('runs-cost-row-sky_eval-toggle'));
    expect(onSelect).toHaveBeenCalledWith(null);
    fireEvent.click(screen.getByTestId('runs-cost-row-overnight-toggle'));
    expect(onSelect).toHaveBeenCalledWith('OVERNIGHT,SKY_EVAL');
    expect(screen.getByTestId('runs-cost-row-sky_eval-toggle')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-cost-row-overnight-toggle')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('runs-cost-row-sky_eval-last')).toHaveTextContent('so far');
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('so far');
  });

  it('keeps the two-period sparkline faint half for a numeric range', () => {
    render(<CostChangeTable data={costResponse()} selected={null} onSelect={vi.fn()} />);
    const rects = [...screen.getByTestId('runs-cost-spark-ask_ready').querySelectorAll('rect')];
    expect(rects).toHaveLength(28);
    expect(rects[0].getAttribute('opacity')).toBe('0.35');
    expect(rects[27].getAttribute('opacity')).toBe('1');
  });
});
