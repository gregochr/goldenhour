import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SkyRatingEvalView, {
  modelClasses, modelColour, costPerCall, orderModelsPresent, accuracyDomainFloor,
} from '../components/SkyRatingEvalView.jsx';

vi.mock('../api/modelsApi', () => ({
  getAvailableModels: vi.fn(),
}));

vi.mock('../api/skyRatingEvalApi', () => ({
  runSkyRatingEval: vi.fn(),
  getSkyRatingEvalRuns: vi.fn(),
  getSkyRatingEvalRun: vi.fn(),
  getSkyRatingEvalTrend: vi.fn(),
  getSkyRatingEvalSchedule: vi.fn(),
  setSkyRatingEvalScheduled: vi.fn(),
}));

// Recharts draws nothing in jsdom (no layout), so the chart parts are stubbed to expose what the view
// asks them to draw: each LineChart carries its data, each Line its dataKey and stroke. Legend entries
// are derived from the Lines, so a Line that is not rendered has no legend entry either.
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }) => <div>{children}</div>,
  LineChart: ({ data, children }) => (
    <div data-testid="line-chart" data-points={JSON.stringify(data)}>{children}</div>
  ),
  Line: ({ dataKey, stroke }) => <span data-testid={`line-${dataKey}`} data-stroke={stroke} />,
  XAxis: () => null,
  YAxis: ({ domain }) => <span data-testid="y-axis" data-domain={JSON.stringify(domain)} />,
  Tooltip: () => null,
  ReferenceArea: () => null,
  CartesianGrid: () => null,
  Legend: () => null,
}));

import {
  runSkyRatingEval, getSkyRatingEvalRuns, getSkyRatingEvalTrend,
  getSkyRatingEvalSchedule, setSkyRatingEvalScheduled,
} from '../api/skyRatingEvalApi';
import { getAvailableModels } from '../api/modelsApi';

const AVAILABLE = {
  available: [
    { name: 'HAIKU', version: '4.5' },
    { name: 'HAIKU_55', version: '5.5' },
    { name: 'SONNET', version: '4.6' },
    { name: 'SONNET_55', version: '5.5' },
    { name: 'OPUS', version: '4.6' },
  ],
};

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

const SCHEDULE = AVAILABLE.available.map((m) => ({ model: m.name, scheduled: true }));

/** Completed runs for three models on one day, so the accuracy chart has three lines. */
const THREE_MODEL_RUNS = [
  { ...RUNS[0], id: 3, model: 'OPUS', passRate: 1.0 },
  { ...RUNS[0], id: 2, model: 'SONNET', passRate: 0.9 },
  { ...RUNS[0], id: 1, model: 'HAIKU', passRate: 0.7 },
];

const HIDDEN_KEY = 'skyEval.chartHidden';

describe('SkyRatingEvalView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    getSkyRatingEvalSchedule.mockResolvedValue({ data: SCHEDULE });
    getSkyRatingEvalRuns.mockResolvedValue({ data: RUNS });
    getSkyRatingEvalTrend.mockResolvedValue({ data: TREND });
    getAvailableModels.mockResolvedValue(AVAILABLE);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders controls and a per-fixture drift chart for each fixture', async () => {
    render(<SkyRatingEvalView />);
    // Wait on a chart, not on the run button. The button and the model picker sit ABOVE the
    // component's `loading ?` gate, so they are on screen from the first paint and waiting for one
    // says nothing about whether the runs/trend fetch has landed. The charts are what that fetch
    // produces, so they are the only honest signal that it has.
    expect(await screen.findByTestId('sky-eval-chart-strong-clearing-canvas')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-chart-flat-grey-overcast')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-run-btn')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-model-SONNET')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-model-HAIKU_55').closest('label')).toHaveTextContent('Haiku 5.5');
    expect(screen.getByTestId('sky-eval-model-SONNET_55')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-model-SONNET_55').closest('label')).toHaveTextContent('Sonnet 5.5');
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

  it('flags a completed run that had failed evaluations, with the reason on hover', async () => {
    const partialMessage = '3 of 48 evaluations failed and count as non-passes (MAX_TOKENS=1, REFUSAL=2)';
    getSkyRatingEvalRuns.mockResolvedValue({
      data: [{ ...RUNS[0], id: 7, errorMessage: partialMessage }, { ...RUNS[0], id: 8 }],
    });
    render(<SkyRatingEvalView />);

    const flag = await screen.findByTestId('sky-eval-partial-7');
    expect(flag).toHaveAttribute('title', partialMessage);
    expect(screen.queryByTestId('sky-eval-partial-8')).not.toBeInTheDocument();
  });

  it('selecting Sonnet 5.5 sends SONNET_55 and the dialog names it', async () => {
    runSkyRatingEval.mockResolvedValue({
      data: { ...RUNS[0], id: 3, model: 'SONNET_55', status: 'RUNNING' },
    });
    render(<SkyRatingEvalView />);
    fireEvent.click(await screen.findByTestId('sky-eval-model-SONNET_55'));
    fireEvent.click(await screen.findByTestId('sky-eval-run-btn'));

    expect(await screen.findByText(/with Sonnet 5\.5\./)).toBeInTheDocument();
    fireEvent.click(await screen.findByText('Run Eval'));
    await waitFor(() => {
      expect(runSkyRatingEval).toHaveBeenCalledWith('SONNET_55', 8);
    });
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

  it('charts per-model accuracy and filters the per-fixture view by selected model', async () => {
    const multiRuns = [
      { id: 3, runTimestamp: '2026-06-27T03:00:00', model: 'OPUS', triggerSource: 'SCHEDULED',
        status: 'COMPLETED', passRate: 1.0, belowMisses: 0, aboveMisses: 0, fixtureCount: 6,
        costMicroDollars: 500000, gitCommitHash: 'abc1234', gitBranch: 'main', gitDirty: false },
      { id: 2, runTimestamp: '2026-06-27T03:00:00', model: 'SONNET', triggerSource: 'SCHEDULED',
        status: 'COMPLETED', passRate: 0.9, belowMisses: 1, aboveMisses: 0, fixtureCount: 6,
        costMicroDollars: 300000, gitCommitHash: 'abc1234', gitBranch: 'main', gitDirty: false },
      { id: 1, runTimestamp: '2026-06-27T03:00:00', model: 'HAIKU', triggerSource: 'SCHEDULED',
        status: 'COMPLETED', passRate: 0.7, belowMisses: 2, aboveMisses: 1, fixtureCount: 6,
        costMicroDollars: 60000, gitCommitHash: 'abc1234', gitBranch: 'main', gitDirty: false },
    ];
    // SONNET has both fixtures; OPUS has only strong-clearing-canvas.
    const multiTrend = [
      { runId: 2, runTimestamp: '2026-06-27T03:00:00', model: 'SONNET', gitCommitHash: 'abc1234',
        fixtureName: 'strong-clearing-canvas', expectedMin: 4, expectedMax: 5,
        avgRating: 4, avgFierySky: 55, avgGoldenHour: 60, runs: 8, passes: 8 },
      { runId: 2, runTimestamp: '2026-06-27T03:00:00', model: 'SONNET', gitCommitHash: 'abc1234',
        fixtureName: 'flat-grey-overcast', expectedMin: 1, expectedMax: 2,
        avgRating: 1, avgFierySky: 5, avgGoldenHour: 8, runs: 8, passes: 8 },
      { runId: 3, runTimestamp: '2026-06-27T03:00:00', model: 'OPUS', gitCommitHash: 'abc1234',
        fixtureName: 'strong-clearing-canvas', expectedMin: 4, expectedMax: 5,
        avgRating: 5, avgFierySky: 70, avgGoldenHour: 75, runs: 8, passes: 8 },
    ];
    getSkyRatingEvalRuns.mockResolvedValue({ data: multiRuns });
    getSkyRatingEvalTrend.mockResolvedValue({ data: multiTrend });

    render(<SkyRatingEvalView />);

    expect(await screen.findByText(/Model accuracy over time/)).toBeInTheDocument();

    // default model SONNET → both fixtures shown
    expect(screen.getByTestId('sky-eval-chart-strong-clearing-canvas')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-chart-flat-grey-overcast')).toBeInTheDocument();

    // switch to OPUS → only the fixture OPUS has trend data for remains
    fireEvent.click(screen.getByTestId('sky-eval-model-OPUS'));
    expect(screen.getByTestId('sky-eval-chart-strong-clearing-canvas')).toBeInTheDocument();
    expect(screen.queryByTestId('sky-eval-chart-flat-grey-overcast')).not.toBeInTheDocument();
  });

  it('renders the model radios from the fetched list, in its order, with an unknown model styled by family', async () => {
    getAvailableModels.mockResolvedValue({
      available: [{ name: 'HAIKU_60', version: '6.0' }, { name: 'SONNET', version: '4.6' }, { name: 'MYSTERY', version: '1' }],
    });
    render(<SkyRatingEvalView />);

    const future = await screen.findByTestId('sky-eval-model-HAIKU_60');
    expect(future.closest('label')).toHaveTextContent('HAIKU_60');
    expect(future.closest('label').querySelector('span')).toHaveClass('text-blue-300');
    expect(screen.getByTestId('sky-eval-model-MYSTERY').closest('label').querySelector('span')).toHaveClass('text-amber-300');
    expect(screen.queryByTestId('sky-eval-model-OPUS')).not.toBeInTheDocument();
    const names = screen.getAllByRole('radio').map((r) => r.value);
    expect(names).toEqual(['HAIKU_60', 'SONNET', 'MYSTERY']);
  });

  it('shows the error but still renders the runs table when the models fetch fails', async () => {
    getAvailableModels.mockRejectedValue(Object.assign(
      new Error('Request failed with status code 403'),
      { response: { status: 403, data: { error: 'Models are admin only' } } },
    ));
    render(<SkyRatingEvalView />);

    expect(await screen.findByTestId('sky-eval-error')).toHaveTextContent('Models are admin only');
    expect(await screen.findByTestId('sky-eval-run-1')).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-chart-strong-clearing-canvas')).toBeInTheDocument();
  });

  it('confirm dialog never shows NaN for a model without a cost entry', async () => {
    getAvailableModels.mockResolvedValue({ available: [{ name: 'HAIKU_60', version: '6.0' }] });
    render(<SkyRatingEvalView />);
    fireEvent.click(await screen.findByTestId('sky-eval-model-HAIKU_60'));
    fireEvent.click(screen.getByTestId('sky-eval-run-btn'));

    const cost = await screen.findByText(/Estimated cost/);
    expect(cost).not.toHaveTextContent('NaN');
    expect(cost).toHaveTextContent('$0.384');
  });

  it('keeps a retired model that is absent from the fetched list on the accuracy chart', async () => {
    getAvailableModels.mockResolvedValue({ available: [{ name: 'SONNET', version: '4.6' }] });
    getSkyRatingEvalRuns.mockResolvedValue({
      data: [{ ...RUNS[0], id: 5, model: 'OPUS_ET' }, RUNS[0]],
    });
    render(<SkyRatingEvalView />);
    expect(await screen.findByText(/Model accuracy over time/)).toBeInTheDocument();
    expect(screen.getByTestId('sky-eval-run-5')).toHaveTextContent('Opus (ET)');
  });

  describe('Weekly run row', () => {
    it('renders one checkbox per model, ticked from the schedule, under its own label', async () => {
      getSkyRatingEvalSchedule.mockResolvedValue({
        data: SCHEDULE.map((r) => (r.model === 'OPUS' ? { ...r, scheduled: false } : r)),
      });
      render(<SkyRatingEvalView />);

      await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-SONNET')).toBeChecked());
      expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeChecked();
      expect(screen.getAllByRole('checkbox').filter((c) => c.dataset.testid.startsWith('sky-eval-schedule-')))
        .toHaveLength(5);
      expect(screen.getByTestId('sky-eval-schedule-row')).toHaveTextContent('Weekly run');
      expect(screen.getByTestId('sky-eval-schedule-row')).toHaveTextContent('weekly when the scheduler job is resumed');
    });

    it('PUTs the toggled model and value, and applies the response row', async () => {
      setSkyRatingEvalScheduled.mockResolvedValue({ data: { model: 'OPUS', scheduled: false } });
      render(<SkyRatingEvalView />);
      await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

      fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));

      await waitFor(() => expect(setSkyRatingEvalScheduled).toHaveBeenCalledWith('OPUS', false));
      await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeChecked());
      expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeDisabled();
    });

    it('disables the checkbox while its request is in flight', async () => {
      let resolve;
      setSkyRatingEvalScheduled.mockReturnValue(new Promise((r) => { resolve = r; }));
      render(<SkyRatingEvalView />);
      await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

      fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));

      await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeDisabled());
      expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeChecked();
      resolve({ data: { model: 'OPUS', scheduled: false } });
      await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeDisabled());
    });

    it('reverts and shows the banner when the PUT fails', async () => {
      setSkyRatingEvalScheduled.mockRejectedValue(Object.assign(
        new Error('Request failed with status code 500'),
        { response: { status: 500, data: { error: 'Could not save the schedule' } } },
      ));
      render(<SkyRatingEvalView />);
      await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

      fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));

      expect(await screen.findByTestId('sky-eval-error')).toHaveTextContent('Could not save the schedule');
      expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked();
      expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeDisabled();
    });

    it('applies only the toggled row, so out-of-order responses cannot undo each other', async () => {
      const pending = {};
      setSkyRatingEvalScheduled.mockImplementation((model) => new Promise((r) => { pending[model] = r; }));
      render(<SkyRatingEvalView />);
      await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).toBeChecked());

      fireEvent.click(screen.getByTestId('sky-eval-schedule-OPUS'));
      fireEvent.click(screen.getByTestId('sky-eval-schedule-HAIKU'));
      // The second request answers first, then the first.
      pending.HAIKU({ data: { model: 'HAIKU', scheduled: false } });
      pending.OPUS({ data: { model: 'OPUS', scheduled: false } });

      await waitFor(() => expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeDisabled());
      expect(screen.getByTestId('sky-eval-schedule-OPUS')).not.toBeChecked();
      expect(screen.getByTestId('sky-eval-schedule-HAIKU')).not.toBeChecked();
      expect(screen.getByTestId('sky-eval-schedule-SONNET')).toBeChecked();
    });

    it('says the weekly run will be skipped when no model is ticked', async () => {
      getSkyRatingEvalSchedule.mockResolvedValue({ data: SCHEDULE.map((r) => ({ ...r, scheduled: false })) });
      render(<SkyRatingEvalView />);

      expect(await screen.findByTestId('sky-eval-schedule-none')).toHaveTextContent('skipped');
    });

    it('shows the error and disables the row when the schedule fails to load, never ticking by guess', async () => {
      getSkyRatingEvalSchedule.mockRejectedValue(Object.assign(
        new Error('Request failed with status code 500'),
        { response: { status: 500, data: { error: 'Schedule unavailable' } } },
      ));
      render(<SkyRatingEvalView />);

      expect(await screen.findByTestId('sky-eval-schedule-error')).toHaveTextContent('Schedule unavailable');
      const box = screen.getByTestId('sky-eval-schedule-SONNET');
      expect(box).toBeDisabled();
      expect(box).not.toBeChecked();
      expect(screen.queryByTestId('sky-eval-schedule-none')).not.toBeInTheDocument();
    });

    it('leaves the Run now radios driving the manual run and the drift panel', async () => {
      render(<SkyRatingEvalView />);
      fireEvent.click(await screen.findByTestId('sky-eval-model-HAIKU'));

      expect(screen.getByTestId('sky-eval-model-HAIKU')).toBeChecked();
      expect(screen.getAllByRole('radio')).toHaveLength(5);
      expect(await screen.findByTestId('sky-eval-empty')).toHaveTextContent('Haiku');
      expect(setSkyRatingEvalScheduled).not.toHaveBeenCalled();
    });
  });

  describe('Show on chart row', () => {
    const accuracyPoints = () => JSON.parse(screen.getAllByTestId('line-chart')[0].dataset.points);

    beforeEach(() => {
      getSkyRatingEvalRuns.mockResolvedValue({ data: THREE_MODEL_RUNS });
    });

    it('offers one checkbox per model that has completed runs, all ticked by default', async () => {
      render(<SkyRatingEvalView />);

      expect(await screen.findByTestId('sky-eval-show-HAIKU')).toBeChecked();
      expect(screen.getByTestId('sky-eval-show-SONNET')).toBeChecked();
      expect(screen.getByTestId('sky-eval-show-OPUS')).toBeChecked();
      expect(screen.queryByTestId('sky-eval-show-HAIKU_55')).not.toBeInTheDocument();
      expect(screen.getByTestId('sky-eval-chart-row')).toHaveTextContent('Show on chart');
    });

    it('unticking a model removes its line (and so its legend entry)', async () => {
      render(<SkyRatingEvalView />);
      await screen.findByText(/Model accuracy over time/);
      expect(screen.getByTestId('line-OPUS')).toBeInTheDocument();

      fireEvent.click(screen.getByTestId('sky-eval-show-OPUS'));

      expect(screen.queryByTestId('line-OPUS')).not.toBeInTheDocument();
      expect(screen.getByTestId('line-SONNET')).toBeInTheDocument();
      expect(screen.getByTestId('line-HAIKU')).toBeInTheDocument();
      expect(screen.getByTestId('sky-eval-show-OPUS')).not.toBeChecked();
    });

    it('keeps each remaining line the colour of its model', async () => {
      render(<SkyRatingEvalView />);
      await screen.findByText(/Model accuracy over time/);
      expect(screen.getByTestId('line-OPUS')).toHaveAttribute('data-stroke', modelColour('OPUS', 2));

      fireEvent.click(screen.getByTestId('sky-eval-show-HAIKU'));

      expect(screen.queryByTestId('line-HAIKU')).not.toBeInTheDocument();
      expect(screen.getByTestId('line-SONNET')).toHaveAttribute('data-stroke', modelColour('SONNET', 1));
      expect(screen.getByTestId('line-OPUS')).toHaveAttribute('data-stroke', modelColour('OPUS', 2));
    });

    it('remembers the hidden set in localStorage', async () => {
      render(<SkyRatingEvalView />);
      fireEvent.click(await screen.findByTestId('sky-eval-show-OPUS'));

      expect(JSON.parse(window.localStorage.getItem(HIDDEN_KEY))).toEqual(['OPUS']);

      fireEvent.click(screen.getByTestId('sky-eval-show-OPUS'));
      expect(JSON.parse(window.localStorage.getItem(HIDDEN_KEY))).toEqual([]);
    });

    it('restores the hidden set from localStorage and shows a model it has never heard of', async () => {
      window.localStorage.setItem(HIDDEN_KEY, JSON.stringify(['OPUS']));
      render(<SkyRatingEvalView />);

      expect(await screen.findByTestId('sky-eval-show-OPUS')).not.toBeChecked();
      expect(screen.getByTestId('sky-eval-show-SONNET')).toBeChecked();
      expect(screen.getByTestId('sky-eval-show-HAIKU')).toBeChecked();
    });

    it('ignores a garbled stored value', async () => {
      window.localStorage.setItem(HIDDEN_KEY, '{not json');
      render(<SkyRatingEvalView />);

      expect(await screen.findByTestId('sky-eval-show-OPUS')).toBeChecked();
    });

    it('still renders and toggles when storage throws', async () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
      render(<SkyRatingEvalView />);

      const box = await screen.findByTestId('sky-eval-show-OPUS');
      expect(box).toBeChecked();
      fireEvent.click(box);
      expect(screen.getByTestId('sky-eval-show-OPUS')).not.toBeChecked();
    });

    it('shows a "No models selected" note in place of the chart when every model is unticked', async () => {
      render(<SkyRatingEvalView />);
      for (const m of ['HAIKU', 'SONNET', 'OPUS']) {
        fireEvent.click(await screen.findByTestId(`sky-eval-show-${m}`));
      }

      expect(screen.getByTestId('sky-eval-chart-none')).toHaveTextContent('No models selected');
      expect(screen.getByText(/Model accuracy over time/)).toBeInTheDocument();
    });

    it('computes the Y-axis floor from the visible models only', async () => {
      getSkyRatingEvalRuns.mockResolvedValue({
        data: [{ ...RUNS[0], id: 3, model: 'OPUS', passRate: 1.0 }, { ...RUNS[0], id: 1, model: 'HAIKU', passRate: 0.5 }],
      });
      render(<SkyRatingEvalView />);
      await screen.findByText(/Model accuracy over time/);
      expect(screen.getAllByTestId('y-axis')[0]).toHaveAttribute('data-domain', '[40,100]');

      fireEvent.click(screen.getByTestId('sky-eval-show-HAIKU'));

      expect(screen.getAllByTestId('y-axis')[0]).toHaveAttribute('data-domain', '[90,100]');
    });

    it('drops a day with no visible value so no empty slot remains', async () => {
      getSkyRatingEvalRuns.mockResolvedValue({
        data: [
          { ...RUNS[0], id: 9, model: 'OPUS', runTimestamp: '2026-06-27T03:00:00', passRate: 1.0 },
          { ...RUNS[0], id: 8, model: 'SONNET', runTimestamp: '2026-06-20T03:00:00', passRate: 0.9 },
        ],
      });
      render(<SkyRatingEvalView />);
      await screen.findByText(/Model accuracy over time/);
      expect(accuracyPoints()).toHaveLength(2);

      fireEvent.click(screen.getByTestId('sky-eval-show-OPUS'));

      expect(accuracyPoints()).toHaveLength(1);
      expect(accuracyPoints()[0].SONNET).toBe(90);
    });
  });

  describe('pure helpers', () => {
    it('modelClasses decides by family prefix', () => {
      expect(modelClasses('HAIKU_60').badge).toBe('bg-blue-900/30 text-blue-300');
      expect(modelClasses('SONNET_ET').text).toBe('text-purple-300');
      expect(modelClasses('OPUS').text).toBe('text-amber-300');
      expect(modelClasses(undefined).text).toBe('text-amber-300');
    });

    it('modelColour keeps known colours and cycles a fallback palette for unknown models', () => {
      expect(modelColour('OPUS', 0)).toBe('#fbbf24');
      const a = modelColour('NEW_A', 0);
      expect(a).toMatch(/^#[0-9a-f]{6}$/);
      expect(modelColour('NEW_B', 1)).not.toBe(a);
      expect(modelColour('NEW_C', 6)).toBe(a);
    });

    it('costPerCall falls back to the highest known rate', () => {
      expect(costPerCall('HAIKU')).toBe(0.002);
      expect(costPerCall('HAIKU_60')).toBe(0.008);
      expect(Number.isNaN(costPerCall(undefined))).toBe(false);
    });

    it('orderModelsPresent uses distinct completed models, fetched order first, then alphabetical', () => {
      const runs = [
        { status: 'COMPLETED', model: 'OPUS' },
        { status: 'COMPLETED', model: 'ZETA_OLD' },
        { status: 'COMPLETED', model: 'ALPHA_OLD' },
        { status: 'COMPLETED', model: 'HAIKU' },
        { status: 'COMPLETED', model: 'HAIKU' },
        { status: 'FAILED', model: 'SONNET' },
      ];
      expect(orderModelsPresent(runs, ['HAIKU', 'SONNET', 'OPUS']))
        .toEqual(['HAIKU', 'OPUS', 'ALPHA_OLD', 'ZETA_OLD']);
      expect(orderModelsPresent(runs, [])).toEqual(['ALPHA_OLD', 'HAIKU', 'OPUS', 'ZETA_OLD']);
    });

    it('accuracyDomainFloor is min less 5, floored to ten, clamped at zero', () => {
      expect(accuracyDomainFloor([84, 92, 100])).toBe(70);
      expect(accuracyDomainFloor([95, 100])).toBe(90);
      expect(accuracyDomainFloor([3, 100])).toBe(0);
      expect(accuracyDomainFloor([])).toBe(0);
    });
  });

  it("shows the server's error sentence, not axios's generic message, when the load is refused", async () => {
    getSkyRatingEvalRuns.mockRejectedValue(Object.assign(
      new Error('Request failed with status code 502'),
      { response: { status: 502, data: { error: 'Upstream API error: 503' } } },
    ));

    render(<SkyRatingEvalView />);

    const banner = await screen.findByTestId('sky-eval-error');
    expect(banner).toHaveTextContent('Upstream API error: 503');
    expect(banner).not.toHaveTextContent('Request failed with status code 502');
  });
});
