import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SkyRatingEvalView from '../components/SkyRatingEvalView.jsx';

vi.mock('../api/skyRatingEvalApi', () => ({
  runSkyRatingEval: vi.fn(),
  getSkyRatingEvalRuns: vi.fn(),
  getSkyRatingEvalRun: vi.fn(),
  getSkyRatingEvalTrend: vi.fn(),
}));

// Recharts' ResponsiveContainer needs a sized parent in jsdom; stub it to render children.
vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    ResponsiveContainer: ({ children }) => <div style={{ width: 400, height: 150 }}>{children}</div>,
  };
});

import {
  runSkyRatingEval, getSkyRatingEvalRuns, getSkyRatingEvalTrend,
} from '../api/skyRatingEvalApi';

const TREND = [
  {
    runId: 1, runTimestamp: '2026-06-20T03:00:00', model: 'SONNET', gitCommitHash: 'abc1234',
    fixtureName: 'strong-clearing-canvas', expectedMin: 4, expectedMax: 5,
    avgRating: 4.0, avgFierySky: 55, avgGoldenHour: 60, runs: 8, passes: 8,
  },
  {
    runId: 1, runTimestamp: '2026-06-20T03:00:00', model: 'SONNET', gitCommitHash: 'abc1234',
    fixtureName: 'flat-grey-overcast', expectedMin: 1, expectedMax: 2,
    avgRating: 1.0, avgFierySky: 5, avgGoldenHour: 8, runs: 8, passes: 8,
  },
];

const RUNS = [
  {
    id: 1, runTimestamp: '2026-06-20T03:00:00', model: 'SONNET', triggerSource: 'SCHEDULED',
    status: 'COMPLETED', passRate: 1.0, belowMisses: 0, aboveMisses: 0,
    fixtureCount: 6, costMicroDollars: 300000, gitCommitHash: 'abc1234', gitBranch: 'main', gitDirty: false,
  },
];

describe('SkyRatingEvalView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getSkyRatingEvalRuns.mockResolvedValue({ data: RUNS });
    getSkyRatingEvalTrend.mockResolvedValue({ data: TREND });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders controls and a per-fixture drift chart for each fixture', async () => {
    render(<SkyRatingEvalView />);
    await waitFor(() => {
      expect(screen.getByTestId('sky-eval-run-btn')).toBeInTheDocument();
    });
    expect(screen.getByTestId('sky-eval-model-SONNET')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-chart-strong-clearing-canvas')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-chart-flat-grey-overcast')).toBeInTheDocument();
  });

  it('shows the run pass rate and direction-bucketed misses in the runs table', async () => {
    render(<SkyRatingEvalView />);
    const row = await screen.findByTestId('sky-eval-run-1');
    expect(row).toHaveTextContent('100%');
    expect(row).toHaveTextContent('0 DOWN');
    expect(row).toHaveTextContent('0 UP');
  });

  it('toggles the per-fixture metric between rating and sub-scores', async () => {
    render(<SkyRatingEvalView />);
    const toggle = await screen.findByTestId('sky-eval-metric-subscores');
    fireEvent.click(toggle);
    // one caption per fixture chart switches to the sub-score legend
    expect(screen.getAllByText(/fiery \(orange\) \/ golden \(amber\)/).length).toBeGreaterThan(0);
  });

  it('triggers a run via the confirm dialog', async () => {
    runSkyRatingEval.mockResolvedValue({
      data: { ...RUNS[0], id: 2, status: 'RUNNING' },
    });
    getSkyRatingEvalRuns.mockResolvedValue({ data: RUNS });
    render(<SkyRatingEvalView />);
    fireEvent.click(await screen.findByTestId('sky-eval-run-btn'));
    // confirm dialog appears with the cost estimate
    const confirm = await screen.findByText('Run Eval');
    fireEvent.click(confirm);
    await waitFor(() => {
      expect(runSkyRatingEval).toHaveBeenCalledWith('SONNET', 8);
    });
  });

  it('shows an empty-state when there are no completed runs', async () => {
    getSkyRatingEvalRuns.mockResolvedValue({ data: [] });
    getSkyRatingEvalTrend.mockResolvedValue({ data: [] });
    render(<SkyRatingEvalView />);
    expect(await screen.findByTestId('sky-eval-empty')).toBeInTheDocument();
  });
});
