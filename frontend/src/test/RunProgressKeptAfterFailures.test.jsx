import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import ManageView from '../components/ManageView.jsx';
import {
  TWO_FAILURES, NO_FAILURES, ONE_FAILURE, RETRY_ACCEPTED, task, summaryEvent, completeEvent, createFeeds,
} from './runProgressFixtures.js';

// The Job Runs flow is ManageView (which owns `activeRunId`) -> JobRunsMetricsView -> the panel.
// The defect lived in the middle: JobRunsMetricsView cleared the active run inside the panel's own
// onComplete, so the panel unmounted in the tick it completed and the Retry button for a run with
// failures was never in the DOM. These tests drive the real three-level chain; only the other admin
// views, the network and the SSE feed are mocked. The feed plays events the way the server does,
// from fixtures that mirror RunProgressTracker field for field (runProgressFixtures.js).

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn() }));
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
vi.mock('../api/briefingApi.js', () => ({ runBriefing: vi.fn(), getDailyBriefing: vi.fn() }));
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

import { getWaitlist } from '../api/waitlistApi.js';
import { getJobRuns, getApiCalls } from '../api/metricsApi';
import { fetchLocations } from '../api/forecastApi';
import { enrichBortle } from '../api/auroraApi';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAvailableModels } from '../api/modelsApi';
import { getRegions } from '../api/batchApi';
import { subscribeToRunProgress, retryFailed } from '../api/runProgressApi';

let feed;
/** Promises held open by a test, settled in afterEach so a failed assertion cannot leak one. */
let held;
const hold = () => {
  let resolve;
  const promise = new Promise((res) => { resolve = res; });
  held.push({ resolve });
  return { promise, resolve };
};

/** Plays a finished run into whichever panel is subscribed to it, as the server does (live or replay). */
const playFinished = async (runId, tasks) => {
  await act(async () => {
    tasks.forEach((t) => feed.feeds[runId].onTask(t));
    feed.feeds[runId].onSummary(summaryEvent(runId, tasks));
    feed.feeds[runId].onComplete(completeEvent(runId, tasks));
  });
};

/** Plays rows and a summary but not the completion: the run is still going. */
const playRunning = async (runId, tasks) => {
  await act(async () => {
    tasks.forEach((t) => feed.feeds[runId].onTask(t));
    feed.feeds[runId].onSummary(summaryEvent(runId, tasks));
  });
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

const goToTab = async (tab) => {
  await act(async () => { fireEvent.click(screen.getByTestId(`manage-tab-${tab}`)); });
};

const retryButton = (n = 2) => screen.getByRole('button', { name: `Retry ${n} failed` });
const dismissButton = () => screen.getByRole('button', { name: 'Dismiss run progress' });
const press = async (button) => { await act(async () => { fireEvent.click(button); }); };

/** How many times the runs grid was (re)loaded across `action`. */
const reloadsDuring = async (action) => {
  const before = getJobRuns.mock.calls.length;
  await action();
  return getJobRuns.mock.calls.length - before;
};

describe('Job Runs: a finished run with failures keeps its panel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    held = [];
    feed = createFeeds();
    window.location.hash = '';
    getWaitlist.mockResolvedValue([]);
    getJobRuns.mockResolvedValue({ data: { content: [] } });
    getApiCalls.mockResolvedValue({ data: [] });
    fetchLocations.mockResolvedValue([]);
    getRegions.mockResolvedValue([]);
    getDailyBriefing.mockResolvedValue(null);
    getAvailableModels.mockResolvedValue({ optimisationStrategies: {} });
    subscribeToRunProgress.mockImplementation(feed.subscribe);
  });
  afterEach(async () => {
    await act(async () => { held.forEach((h) => h.resolve({ jobRunId: 999 })); });
  });

  describe('completion', () => {
    it('keeps the panel, its failed rows, Retry and Dismiss, and reloads the list exactly once', async () => {
      await renderJobRuns();
      await startRun(41);

      const reloads = await reloadsDuring(() => playFinished(41, TWO_FAILURES));

      expect(reloads).toBe(1);
      const panel = screen.getByRole('region', { name: 'Run progress' });
      expect(within(panel).getByRole('button', { name: 'Retry 2 failed' })).toBeInTheDocument();
      expect(within(panel).getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
      for (const place of ['East Fell', 'West Fell']) {
        const row = within(panel).getByText(place).closest('[data-testid="run-progress-row"]');
        expect(row).toHaveTextContent('Failed');
        expect(row).toHaveTextContent(`Weather data fetch failed for ${place} SUNSET`);
      }
    });

    it('clears the panel and reloads the list exactly once when the run completes with no failures', async () => {
      await renderJobRuns();
      await startRun(41);

      const reloads = await reloadsDuring(() => playFinished(41, NO_FAILURES));

      expect(reloads).toBe(1);
      expect(screen.queryByTestId('run-progress-panel')).toBeNull();
    });

    it('keeps the panel for exactly one failure', async () => {
      await renderJobRuns();
      await startRun(41);

      await playFinished(41, ONE_FAILURE);

      expect(retryButton(1)).toBeInTheDocument();
    });

    it('does not move focus when a clean run clears itself', async () => {
      await renderJobRuns();
      await startRun(41);

      await playFinished(41, NO_FAILURES);

      expect(screen.getByRole('heading', { name: 'Forecast Runs' })).not.toHaveFocus();
    });
  });

  describe('Dismiss', () => {
    it('removes the kept panel and puts focus on the Forecast Runs heading', async () => {
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);

      await press(dismissButton());

      expect(screen.queryByTestId('run-progress-panel')).toBeNull();
      expect(screen.getByRole('heading', { name: 'Forecast Runs' })).toHaveFocus();
    });

    it('is not offered while the run is still going', async () => {
      await renderJobRuns();
      await startRun(41);

      expect(screen.queryByRole('button', { name: 'Dismiss run progress' })).toBeNull();
    });
  });

  describe('starting another run', () => {
    it('does not block the run buttons, and the new run REPLACES the kept panel, rows and all', async () => {
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);
      expect(screen.getByText('East Fell')).toBeInTheDocument();

      // The run buttons are gated on in-flight requests only, never on a kept panel (the id's home
      // in ManageView, lifted there by c82f849a, has nothing to do with the buttons).
      expect(screen.getByTestId('refresh-light-pollution-btn')).toBeEnabled();
      await startRun(42);
      await playRunning(42, [task('new|a', 'Fresh Crag', 'EVALUATING')]);

      const panels = screen.getAllByTestId('run-progress-panel');
      expect(panels).toHaveLength(1);
      expect(within(panels[0]).getByText('Fresh Crag')).toBeInTheDocument();
      // Run 41's rows, failure and buttons are gone, not carried into the new run's panel.
      expect(screen.queryByText('East Fell')).toBeNull();
      expect(screen.queryByText('West Fell')).toBeNull();
      expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Dismiss run progress' })).toBeNull();
      expect(feed.subscriptions).toEqual([41, 42]);
    });
  });

  describe('leaving the Job Runs tab', () => {
    it('re-subscribes to the same run on return: the id lives in ManageView (lifted there by c82f849a)', async () => {
      await renderJobRuns();
      await startRun(41);

      await goToTab('models');
      expect(screen.queryByTestId('run-progress-panel')).toBeNull();
      await goToTab('metrics');

      expect(await screen.findByTestId('run-progress-panel')).toBeInTheDocument();
      expect(feed.subscriptions).toEqual([41, 41]);
    });

    it('keeps a run that finished WITH failures while away: the server replays run-complete, Retry and Dismiss are there', async () => {
      await renderJobRuns();
      await startRun(41);
      await goToTab('models');
      // The run finishes while the tab is unmounted; on return the server replays what happened.
      await goToTab('metrics');
      await screen.findByTestId('run-progress-panel');

      const reloads = await reloadsDuring(() => playFinished(41, TWO_FAILURES));

      expect(reloads).toBe(1);
      expect(retryButton()).toBeInTheDocument();
      expect(dismissButton()).toBeInTheDocument();
      expect(screen.getByText('East Fell').closest('[data-testid="run-progress-row"]')).toHaveTextContent('Failed');
    });

    it('clears a run that finished cleanly while away, and reloads the list once', async () => {
      await renderJobRuns();
      await startRun(41);
      await goToTab('models');
      await goToTab('metrics');
      await screen.findByTestId('run-progress-panel');

      const reloads = await reloadsDuring(() => playFinished(41, NO_FAILURES));

      expect(reloads).toBe(1);
      expect(screen.queryByTestId('run-progress-panel')).toBeNull();
    });

    it('shows one plain line and Dismiss, and no Retry, when the server no longer holds the run', async () => {
      await renderJobRuns();
      await startRun(41);
      await goToTab('models');
      await goToTab('metrics');
      await screen.findByTestId('run-progress-panel');

      await act(async () => { feed.feeds[41].onExpired({ jobRunId: 41 }); });

      expect(screen.getByTestId('run-progress-expired').textContent)
        .toBe("This run's progress is no longer available.");
      expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
      await press(dismissButton());
      expect(screen.queryByTestId('run-progress-panel')).toBeNull();
    });
  });

  describe('a retry the server accepted', () => {
    it('becomes THE active run: one panel, following the retry, list reloaded once, focus on its header', async () => {
      retryFailed.mockResolvedValue({ status: 'Retry run started', runType: 'SHORT_TERM', jobRunId: 77 });
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);

      const reloads = await reloadsDuring(() => press(retryButton()));

      expect(retryFailed).toHaveBeenCalledWith(41);
      expect(reloads).toBe(1);
      expect(feed.subscriptions).toEqual([41, 77]);
      const panels = screen.getAllByTestId('run-progress-panel');
      expect(panels).toHaveLength(1);
      expect(screen.queryByText('East Fell')).toBeNull();
      expect(screen.getByText(/^Run Progress/)).toHaveFocus();
      expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
    });

    it('survives a tab switch like any run: the panel that returns follows the retry run, not the first', async () => {
      retryFailed.mockResolvedValue({ jobRunId: 77 });
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);
      await press(retryButton());

      await goToTab('models');
      await goToTab('metrics');

      await screen.findByTestId('run-progress-panel');
      expect(feed.subscriptions).toEqual([41, 77, 77]);
      expect(screen.getAllByTestId('run-progress-panel')).toHaveLength(1);
      // The promoted panel took focus once, on promotion; coming back to the tab does not steal it.
      expect(screen.getByText(/^Run Progress/)).not.toHaveFocus();
    });

    it('is kept with its own Retry and Dismiss when the retry run itself ends with failures', async () => {
      retryFailed.mockResolvedValue({ jobRunId: 77 });
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);
      await press(retryButton());

      await playFinished(77, [task('west|c', 'West Fell', 'FAILED'), task('hill|a', 'Test Hill', 'COMPLETE')]);

      expect(retryButton(1)).toBeInTheDocument();
      expect(dismissButton()).toBeInTheDocument();
      expect(screen.getAllByTestId('run-progress-panel')).toHaveLength(1);
    });

    it('clears when the retry run finishes cleanly, like any other run', async () => {
      retryFailed.mockResolvedValue({ jobRunId: 77 });
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);
      await press(retryButton());

      await playFinished(77, NO_FAILURES);

      expect(screen.queryByTestId('run-progress-panel')).toBeNull();
    });

    it('keeps the panel, says "Retry started." and reloads the list once when the answer names no run', async () => {
      retryFailed.mockResolvedValue({ status: 'Retry run started' });
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);

      const reloads = await reloadsDuring(() => press(retryButton()));

      expect(reloads).toBe(1);
      expect(screen.getByRole('status').textContent).toBe('Retry started.');
      expect(screen.getAllByTestId('run-progress-panel')).toHaveLength(1);
      expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
      expect(feed.subscriptions).toEqual([41]);
    });

    it('says what was started on the promoted panel, which holds only the retried slots', async () => {
      retryFailed.mockResolvedValue(RETRY_ACCEPTED);
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);
      await press(retryButton());

      // The retry run (id 6 in the server's answer) holds exactly the two slots it was given.
      await playRunning(6, [task('east|b', 'East Fell', 'EVALUATING'), task('hill|a', 'Test Hill', 'EVALUATING')]);

      const panel = screen.getByRole('region', { name: 'Run progress' });
      expect(within(panel).getByTestId('retry-run-note').textContent).toBe(
        'Retrying 2 slots. Left out: West Fell 2026-10-03 sunrise (The place is disabled or no longer exists.)');
      expect(within(panel).getAllByTestId('run-progress-row')).toHaveLength(2);
      expect(feed.subscriptions).toEqual([41, 6]);
    });

    it('shows no "Retrying" line on a panel that is not a promoted retry run', async () => {
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);

      expect(screen.queryByTestId('retry-run-note')).toBeNull();
    });

    it('cannot be orphaned by Dismiss while the retry request is out', async () => {
      const pending = hold();
      retryFailed.mockReturnValue(pending.promise);
      await renderJobRuns();
      await startRun(41);
      await playFinished(41, TWO_FAILURES);
      await press(retryButton());

      await press(dismissButton());
      expect(screen.getByTestId('run-progress-panel')).toBeInTheDocument();

      await act(async () => { pending.resolve({ jobRunId: 77 }); });
      expect(feed.subscriptions).toEqual([41, 77]);
      expect(screen.getAllByTestId('run-progress-panel')).toHaveLength(1);
    });
  });
});
