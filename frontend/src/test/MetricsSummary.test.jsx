import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MetricsSummary from '../components/MetricsSummary.jsx';

/**
 * The clock is frozen, and the fixtures are built on the UK calendar rather than on the runner's.
 *
 * <p>Both used to come from a live `new Date()` + `toLocaleDateString()` with no zone, which is two
 * hazards at once. The card now filters "today" on `Europe/London`, so a machine-zone fixture is
 * asking a different question from the code; and a wall-clock fixture makes the suite's result a
 * property of when it runs — this repo has been bitten from both directions, once by a `Clock.fixed`
 * that landed on the real today and let a mutation survive, once by a "Today" fixture that went red
 * overnight and blocked an unrelated PR.
 *
 * <p>16:00 UK on 13 August 2026, i.e. BST — deliberately NOT an instant where the UK and UTC
 * calendars agree by accident, and far from the midnight boundary so the ±8-day fixtures below stay
 * unambiguous.
 */
const FROZEN_NOW = '2026-08-13T15:00:00Z';
const todayStr = '2026-08-13';
const yesterdayStr = '2026-08-12';
const eightDaysAgo = '2026-08-05T15:00:00Z';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(FROZEN_NOW));
});

afterEach(() => {
  vi.useRealTimers();
});

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

  it('ignores legacy pence-only runs; total reflects only micro-dollar costs', () => {
    const mixedRuns = [
      {
        id: 1, runType: 'SHORT_TERM',
        startedAt: `${todayStr}T06:00:00Z`, durationMs: 5000,
        succeeded: 100, failed: 0,
        totalCostMicroDollars: 100000,
        exchangeRateGbpPerUsd: 0.80,
      },
      {
        // Legacy run with no micro-dollar cost — contributes nothing to the total.
        id: 2, runType: 'SHORT_TERM',
        startedAt: `${yesterdayStr}T18:00:00Z`, durationMs: 3000,
        succeeded: 50, failed: 0,
        totalCostMicroDollars: 0,
        exchangeRateGbpPerUsd: null,
      },
    ];
    render(<MetricsSummary runs={mixedRuns} apiCalls={[]} range="7d" onRangeChange={noop} />);
    // Only run 1 counts: 100000 µ$ = $0.10 × 0.80 = £0.08.
    expect(screen.getByText('£0.08')).toBeInTheDocument();
    expect(screen.getByText(/Token-based pricing \(actual usage\)/)).toBeInTheDocument();
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
        totalCostMicroDollars: 100000,
        exchangeRateGbpPerUsd: 0.80,
      },
      {
        id: 2, runType: 'SCHEDULED_BATCH',
        startedAt: `${todayStr}T07:00:00Z`, durationMs: 60000,
        succeeded: 80, failed: 2,
        totalCostMicroDollars: 200000,
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
