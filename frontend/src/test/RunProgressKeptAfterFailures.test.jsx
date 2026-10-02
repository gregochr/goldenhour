import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import ManageView from '../components/ManageView.jsx';

// The Job Runs flow is ManageView (which owns `activeRunId`) -> JobRunsMetricsView -> the panel.
// The defect lived in the middle: JobRunsMetricsView cleared the active run inside the panel's own
// onComplete, so the panel unmounted in the tick it completed and the Retry button for a run with
// failures was never in the DOM. These tests drive the real three-level chain; only the other admin
// views and the network are mocked.

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
const { stub } = vi.hoisted(() => ({ stub: (label) => ({ default: () => <div>{label}</div> }) }));
vi.mock('../components/UserManagementView.jsx', () => stub('Users'));
vi.mock('../components/LocationManagementView.jsx', () => stub('Locations'));
vi.mock('../components/RegionManagementView.jsx', () => stub('Regions'));
vi.mock('../components/TideManagementView.jsx', () => stub('Tides'));
vi.mock('../components/WaitlistManagementView.jsx', () => stub('Waitlist'));
vi.mock('../components/ModelSelectionView.jsx', () => stub('Run Config'));
vi.mock('../components/ModelTestView.jsx', () => stub('Model Test'));
vi.mock('../components/BriefingModelTestView.jsx', () => stub('Briefing Model Test'));
vi.mock('../components/PromptTestView.jsx', () => stub('Prompt Test'));
vi.mock('../components/SchedulerView.jsx', () => stub('Scheduler'));
vi.mock('../components/TravelDaysView.jsx', () => stub('Travel Days'));
vi.mock('../components/SkyRatingEvalView.jsx', () => stub('Sky Eval'));
vi.mock('../components/PipelineRunsView.jsx', () => stub('Pipeline Runs'));
vi.mock('../components/HotTopicSimulation.jsx', () => stub('Hot Topics'));

vi.mock('../api/batchApi', () => ({
  getRegions: vi.fn(), submitScheduledBatch: vi.fn(), submitJfdiBatch: vi.fn(),
}));
vi.mock('../api/metricsApi', () => ({ getJobRuns: vi.fn(), getApiCalls: vi.fn() }));
vi.mock('../api/forecastApi', () => ({
  runVeryShortTermForecast: vi.fn(), runShortTermForecast: vi.fn(), runLongTermForecast: vi.fn(),
  refreshTideData: vi.fn(), backfillTideData: vi.fn(), fetchLocations: vi.fn(),
}));
vi.mock('../api/auroraApi', () => ({ enrichBortle: vi.fn() }));
vi.mock('../api/briefingApi.js', () => ({
  runBriefing: vi.fn(), getDailyBriefing: vi.fn().mockResolvedValue(null),
}));
vi.mock('../api/modelsApi', () => ({ getAvailableModels: vi.fn() }));
vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ isAdmin: true, role: 'ADMIN' }),
}));
vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: null, loading: false }),
}));
vi.mock('../api/runProgressApi', () => ({
  subscribeToRunProgress: vi.fn(),
  retryFailed: vi.fn(),
}));

import { getJobRuns, getApiCalls } from '../api/metricsApi';
import { fetchLocations } from '../api/forecastApi';
import { enrichBortle } from '../api/auroraApi';
import { getAvailableModels } from '../api/modelsApi';
import { getRegions } from '../api/batchApi';
import { subscribeToRunProgress, retryFailed } from '../api/runProgressApi';

/** The newest SSE handlers per run id (the view re-subscribes on re-render, so keep the latest). */
let feeds;

const SUMMARY_WITH_FAILURES = { total: 3, completed: 1, failed: 2, skipped: 0, triaged: 0, inProgress: 0 };
const SUMMARY_CLEAN = { total: 3, completed: 3, failed: 0, skipped: 0, triaged: 0, inProgress: 0 };

const complete = async (runId, payload) => {
  await act(async () => { feeds[runId].complete({ jobRunId: runId, ...payload }); });
};

/** Starts a run the way an admin does: a run button whose response carries the new job run id. */
const startRun = async (runId) => {
  enrichBortle.mockResolvedValueOnce({ jobRunId: runId });
  await act(async () => {
    fireEvent.click(screen.getByTestId('refresh-light-pollution-btn'));
  });
  await screen.findByTestId('run-progress-panel');
};

const renderJobRuns = async () => {
  window.location.hash = '#manage/metrics';
  const view = render(<ManageView onComplete={vi.fn()} />);
  await screen.findByTestId('refresh-light-pollution-btn');
  return view;
};

describe('Job Runs: a finished run with failures keeps its panel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    feeds = {};
    getJobRuns.mockResolvedValue({ data: { content: [] } });
    getApiCalls.mockResolvedValue({ data: [] });
    fetchLocations.mockResolvedValue([]);
    getRegions.mockResolvedValue([]);
    getAvailableModels.mockResolvedValue({ optimisationStrategies: {} });
    subscribeToRunProgress.mockImplementation((id, onTask, onSummary, onComplete) => {
      feeds[id] = { task: onTask, summary: onSummary, complete: onComplete };
      return () => {};
    });
  });

  it('keeps the panel and offers Retry when the run completes with failures, and reloads the list', async () => {
    await renderJobRuns();
    await startRun(41);
    const loadsBefore = getJobRuns.mock.calls.length;

    await complete(41, SUMMARY_WITH_FAILURES);

    expect(screen.getByTestId('run-progress-panel')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry 2 failed' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
    expect(getJobRuns.mock.calls.length).toBeGreaterThan(loadsBefore);
  });

  it('still clears the panel and reloads the list when the run completes with no failures', async () => {
    await renderJobRuns();
    await startRun(41);
    const loadsBefore = getJobRuns.mock.calls.length;

    await complete(41, SUMMARY_CLEAN);

    expect(screen.queryByTestId('run-progress-panel')).toBeNull();
    expect(getJobRuns.mock.calls.length).toBeGreaterThan(loadsBefore);
  });

  it('does not move focus when a clean run clears itself', async () => {
    await renderJobRuns();
    await startRun(41);

    await complete(41, SUMMARY_CLEAN);

    expect(screen.getByTestId('forecast-runs-heading')).not.toHaveFocus();
  });

  it('Dismiss removes the kept panel and puts focus on the Forecast Runs heading', async () => {
    await renderJobRuns();
    await startRun(41);
    await complete(41, SUMMARY_WITH_FAILURES);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Dismiss run progress' }));
    });

    expect(screen.queryByTestId('run-progress-panel')).toBeNull();
    expect(screen.getByTestId('forecast-runs-heading')).toHaveFocus();
  });

  it('offers no Dismiss while the run is still going', async () => {
    await renderJobRuns();
    await startRun(41);

    expect(screen.queryByRole('button', { name: 'Dismiss run progress' })).toBeNull();
  });

  it('does not block starting another run, and the new run replaces the kept panel', async () => {
    await renderJobRuns();
    await startRun(41);
    await complete(41, SUMMARY_WITH_FAILURES);
    expect(screen.getByRole('button', { name: 'Retry 2 failed' })).toBeInTheDocument();

    // c82f849a-era regression: a kept panel must leave every run button usable.
    expect(screen.getByTestId('refresh-light-pollution-btn')).toBeEnabled();
    await startRun(42);

    expect(screen.getAllByTestId('run-progress-panel')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Retry 2 failed' })).toBeNull();
    expect(subscribeToRunProgress).toHaveBeenCalledWith(42, expect.any(Function), expect.any(Function),
      expect.any(Function), expect.any(Function));
    // A fresh panel: nothing of run 41 (its completion, its failures) is carried into run 42.
    expect(screen.getByTestId('run-progress-panel')).toHaveTextContent('0/0');
    expect(screen.getByTestId('run-progress-panel')).not.toHaveTextContent('Complete');
  });

  it('keeps following the active run across a tab switch (the reason the id lives in ManageView)', async () => {
    await renderJobRuns();
    await startRun(41);

    fireEvent.click(screen.getByTestId('manage-tab-models'));
    expect(screen.queryByTestId('run-progress-panel')).toBeNull();
    fireEvent.click(screen.getByTestId('manage-tab-metrics'));

    expect(await screen.findByTestId('run-progress-panel')).toBeInTheDocument();
    expect(subscribeToRunProgress.mock.calls.filter(([id]) => id === 41).length).toBeGreaterThan(1);
  });

  it('follows the retry run by id after a successful retry, and keeps the whole panel', async () => {
    retryFailed.mockResolvedValue({ jobRunId: 77 });
    await renderJobRuns();
    await startRun(41);
    await complete(41, SUMMARY_WITH_FAILURES);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Retry 2 failed' }));
    });

    expect(retryFailed).toHaveBeenCalledWith(41);
    expect(subscribeToRunProgress.mock.calls.some(([id]) => id === 77)).toBe(true);
    const panels = screen.getAllByTestId('run-progress-panel');
    expect(panels).toHaveLength(2);
    // The Retry button that held focus is gone, so focus lands on the retry run's own header.
    expect(within(panels[1]).getByText(/Run Progress/)).toHaveFocus();

    // The retry run finishing cleanly refreshes the list but does not remove the panel: the admin
    // asked for this run and wants to see how it ended.
    const loadsBefore = getJobRuns.mock.calls.length;
    await complete(77, SUMMARY_CLEAN);
    expect(screen.getAllByTestId('run-progress-panel')).toHaveLength(2);
    expect(getJobRuns.mock.calls.length).toBeGreaterThan(loadsBefore);
    expect(within(screen.getAllByTestId('run-progress-panel')[1]).getByText(/Complete/)).toBeInTheDocument();
  });

  it('offers Retry on a retry run that itself finishes with failures, and Dismiss removes both', async () => {
    retryFailed.mockResolvedValue({ jobRunId: 77 });
    await renderJobRuns();
    await startRun(41);
    await complete(41, SUMMARY_WITH_FAILURES);
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Retry 2 failed' }));
    });

    await complete(77, { ...SUMMARY_WITH_FAILURES, failed: 1, completed: 2 });

    expect(screen.getByRole('button', { name: 'Retry 1 failed' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Dismiss run progress' })).toHaveLength(1);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Dismiss run progress' }));
    });
    expect(screen.queryByTestId('run-progress-panel')).toBeNull();
  });
});
