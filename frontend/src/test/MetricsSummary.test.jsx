import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MetricsSummary from '../components/MetricsSummary.jsx';

const now = new Date();
const todayStr = now.toLocaleDateString('en-CA');
const yesterdayStr = new Date(now.getTime() - 86400000).toLocaleDateString('en-CA');
const eightDaysAgo = new Date(now.getTime() - 8 * 86400000).toISOString();

const MOCK_RUNS = [
  {
    id: 1,
    jobName: 'SHORT_TERM',
    runType: 'SHORT_TERM',
    startedAt: `${todayStr}T06:00:00Z`,
    durationMs: 5000,
    succeeded: 100,
    failed: 5,
    totalCostMicroDollars: 50000,
    totalCostPence: 0,
    exchangeRateGbpPerUsd: 0.79,
  },
  {
    id: 2,
    jobName: 'LONG_TERM',
    runType: 'LONG_TERM',
    startedAt: `${yesterdayStr}T18:00:00Z`,
    durationMs: 8000,
    succeeded: 200,
    failed: 10,
    totalCostMicroDollars: 80000,
    totalCostPence: 0,
    exchangeRateGbpPerUsd: 0.79,
  },
  {
    id: 3,
    jobName: 'SHORT_TERM',
    runType: 'SHORT_TERM',
    startedAt: eightDaysAgo,
    durationMs: 4000,
    succeeded: 50,
    failed: 0,
    totalCostMicroDollars: 20000,
    totalCostPence: 0,
    exchangeRateGbpPerUsd: 0.79,
  },
];

const MOCK_API_CALLS = [
  { id: 1, jobRunId: 1, service: 'ANTHROPIC', durationMs: 2000 },
  { id: 2, jobRunId: 1, service: 'OPEN_METEO_FORECAST', durationMs: 500 },
  { id: 3, jobRunId: 2, service: 'ANTHROPIC', durationMs: 3000 },
  { id: 4, jobRunId: 3, service: 'ANTHROPIC', durationMs: 1500 },
];

const noop = () => {};

describe('MetricsSummary', () => {
  it('shows empty message when no runs', () => {
    render(<MetricsSummary runs={[]} apiCalls={[]} range="7d" onRangeChange={noop} />);
    expect(screen.getByText('No job runs available')).toBeInTheDocument();
  });

  it('defaults to Last 7 Days view when range is 7d', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} range="7d" onRangeChange={noop} />);
    expect(screen.getByTestId('summary-range-7d')).toHaveClass('bg-plex-gold');
  });

  it('filters out runs older than 7 days in default view', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} range="7d" onRangeChange={noop} />);
    // Run 3 is 8 days old, so only runs 1 and 2 count
    expect(screen.getByText('2')).toBeInTheDocument(); // total runs
    // 100 + 200 = 300 succeeded, 5 + 10 = 15 failed
    expect(screen.getByText(/300 succeeded, 15 failed/)).toBeInTheDocument();
  });

  it('shows only today runs when range is today', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} range="today" onRangeChange={noop} />);
    // Only run 1 is today
    expect(screen.getByText('100 succeeded, 5 failed')).toBeInTheDocument();
  });

  it('shows no-runs message when Today has no data', () => {
    const oldRuns = MOCK_RUNS.map((r) => ({
      ...r,
      startedAt: `${yesterdayStr}T12:00:00Z`,
    }));
    render(<MetricsSummary runs={oldRuns} apiCalls={MOCK_API_CALLS} range="today" onRangeChange={noop} />);
    expect(screen.getByText('No job runs today')).toBeInTheDocument();
  });

  it('shows runs grouped by type', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} range="7d" onRangeChange={noop} />);
    // In 7-day view: run 1 = SHORT_TERM, run 2 = LONG_TERM
    expect(screen.getByText('SHORT_TERM: 1')).toBeInTheDocument();
    expect(screen.getByText('LONG_TERM: 1')).toBeInTheDocument();
  });

  it('shows slowest service from filtered API calls', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} range="7d" onRangeChange={noop} />);
    expect(screen.getByText('ANTHROPIC')).toBeInTheDocument();
  });

  it('shows cost in GBP with USD below', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} range="7d" onRangeChange={noop} />);
    expect(screen.getByText(/\$/)).toBeInTheDocument();
    expect(screen.getByText(/£/)).toBeInTheDocument();
  });

  it('combines micro-dollar and legacy pence costs', () => {
    const mixedRuns = [
      {
        id: 1, runType: 'SHORT_TERM',
        startedAt: `${todayStr}T06:00:00Z`, durationMs: 5000,
        succeeded: 100, failed: 0,
        totalCostMicroDollars: 100000, totalCostPence: 0,
        exchangeRateGbpPerUsd: 0.80,
      },
      {
        id: 2, runType: 'SHORT_TERM',
        startedAt: `${yesterdayStr}T18:00:00Z`, durationMs: 3000,
        succeeded: 50, failed: 0,
        totalCostMicroDollars: 0, totalCostPence: 5000,
        exchangeRateGbpPerUsd: null,
      },
    ];
    render(<MetricsSummary runs={mixedRuns} apiCalls={[]} range="7d" onRangeChange={noop} />);
    expect(screen.getByText('£5.08')).toBeInTheDocument();
    expect(screen.getByText(/Token-based \+ legacy/)).toBeInTheDocument();
    expect(screen.getByText(/token-based only/)).toBeInTheDocument();
  });

  it('calls onRangeChange when clicking Today', () => {
    const onRangeChange = vi.fn();
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} range="7d" onRangeChange={onRangeChange} />);
    fireEvent.click(screen.getByTestId('summary-range-today'));
    expect(onRangeChange).toHaveBeenCalledWith('today');
  });

  it('calls onRangeChange when clicking Last 7 Days', () => {
    const onRangeChange = vi.fn();
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} range="today" onRangeChange={onRangeChange} />);
    fireEvent.click(screen.getByTestId('summary-range-7d'));
    expect(onRangeChange).toHaveBeenCalledWith('7d');
  });

  it('shows batch vs real-time cost breakdown when both present', () => {
    const runs = [
      {
        id: 1, runType: 'SHORT_TERM',
        startedAt: `${todayStr}T06:00:00Z`, durationMs: 5000,
        succeeded: 50, failed: 0,
        totalCostMicroDollars: 100000, totalCostPence: 0,
        exchangeRateGbpPerUsd: 0.80,
      },
      {
        id: 2, runType: 'SCHEDULED_BATCH',
        startedAt: `${todayStr}T07:00:00Z`, durationMs: 60000,
        succeeded: 80, failed: 2,
        totalCostMicroDollars: 200000, totalCostPence: 0,
        exchangeRateGbpPerUsd: 0.80,
      },
    ];
    render(<MetricsSummary runs={runs} apiCalls={[]} range="today" onRangeChange={noop} />);
    const breakdown = screen.getByTestId('cost-breakdown');
    expect(breakdown).toBeInTheDocument();
    expect(breakdown.textContent).toContain('Real-time');
    expect(breakdown.textContent).toContain('Batch');
  });
});
