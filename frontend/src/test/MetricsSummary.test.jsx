import { describe, it, expect } from 'vitest';
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

describe('MetricsSummary', () => {
  it('shows empty message when no runs', () => {
    render(<MetricsSummary runs={[]} apiCalls={[]} />);
    expect(screen.getByText('No job runs available')).toBeInTheDocument();
  });

  it('defaults to Last 7 Days view', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} />);
    // Last 7 Days should be active (gold background)
    expect(screen.getByTestId('summary-range-7d')).toHaveClass('bg-plex-gold');
  });

  it('filters out runs older than 7 days in default view', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} />);
    // Run 3 is 8 days old, so only runs 1 and 2 count
    expect(screen.getByText('2')).toBeInTheDocument(); // total runs
    // 100 + 200 = 300 succeeded, 5 + 10 = 15 failed
    expect(screen.getByText(/300 succeeded, 15 failed/)).toBeInTheDocument();
  });

  it('switching to Today shows only today runs', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} />);
    fireEvent.click(screen.getByTestId('summary-range-today'));

    // Only run 1 is today
    expect(screen.getByText('100 succeeded, 5 failed')).toBeInTheDocument();
  });

  it('shows no-runs message when Today has no data', () => {
    const oldRuns = MOCK_RUNS.map((r) => ({
      ...r,
      startedAt: `${yesterdayStr}T12:00:00Z`,
    }));
    render(<MetricsSummary runs={oldRuns} apiCalls={MOCK_API_CALLS} />);
    fireEvent.click(screen.getByTestId('summary-range-today'));

    expect(screen.getByText('No job runs today')).toBeInTheDocument();
  });

  it('shows runs grouped by type', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} />);
    // In 7-day view: run 1 = SHORT_TERM, run 2 = LONG_TERM
    expect(screen.getByText('SHORT_TERM: 1')).toBeInTheDocument();
    expect(screen.getByText('LONG_TERM: 1')).toBeInTheDocument();
  });

  it('shows slowest service from filtered API calls', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} />);
    // In 7-day view: ANTHROPIC calls from runs 1 and 2 (2000ms, 3000ms avg=2500ms)
    expect(screen.getByText('ANTHROPIC')).toBeInTheDocument();
  });

  it('shows cost in GBP with USD below', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} />);
    // Should show GBP cost (primary) and USD (secondary)
    expect(screen.getByText(/\$/)).toBeInTheDocument();
    expect(screen.getByText(/£/)).toBeInTheDocument();
  });

  it('combines micro-dollar and legacy pence costs', () => {
    // Run 1 has micro-dollars, run 2 has only legacy pence
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
    render(<MetricsSummary runs={mixedRuns} apiCalls={[]} />);
    // micro-dollars: 100000µ$ × 0.80 = £0.08, legacy: 5000/1000 = £5.00
    // combined = £5.08
    expect(screen.getByText('£5.08')).toBeInTheDocument();
    expect(screen.getByText(/Token-based \+ legacy/)).toBeInTheDocument();
    expect(screen.getByText(/token-based only/)).toBeInTheDocument();
  });

  it('toggles between Today and 7 Days', () => {
    render(<MetricsSummary runs={MOCK_RUNS} apiCalls={MOCK_API_CALLS} />);

    // Switch to today
    fireEvent.click(screen.getByTestId('summary-range-today'));
    expect(screen.getByTestId('summary-range-today')).toHaveClass('bg-plex-gold');

    // Switch back to 7d
    fireEvent.click(screen.getByTestId('summary-range-7d'));
    expect(screen.getByTestId('summary-range-7d')).toHaveClass('bg-plex-gold');
  });
});
