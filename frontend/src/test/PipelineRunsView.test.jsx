import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import PipelineRunsView from '../components/PipelineRunsView.jsx';

vi.mock('../api/pipelineRunApi', () => ({
  fetchPipelineRuns: vi.fn(),
  fetchPipelineRunDetail: vi.fn(),
}));

// DispositionBreakdown does its own API calls; mock to a stub so the test
// stays focused on the pipeline run view's rendering.
vi.mock('../components/DispositionBreakdown.jsx', () => ({
  __esModule: true,
  default: ({ jobRunId }) => (
    <div data-testid={`mock-disposition-${jobRunId}`}>disposition for {jobRunId}</div>
  ),
}));

import {
  fetchPipelineRuns,
  fetchPipelineRunDetail,
} from '../api/pipelineRunApi';

const T = '2026-05-26T01:00:00Z';

const MOCK_RUNS = [
  {
    id: 43,
    cycleType: 'NIGHTLY',
    status: 'RUNNING',
    currentPhase: 'FORECAST_BATCH_WAIT',
    waitingOn: 'forecast batch set (2 of 4 complete)',
    triggerTime: T,
    completedAt: null,
    durationSeconds: null,
    failureReason: null,
  },
  {
    id: 42,
    cycleType: 'NIGHTLY',
    status: 'COMPLETED',
    currentPhase: null,
    waitingOn: null,
    triggerTime: T,
    completedAt: '2026-05-26T01:15:00Z',
    durationSeconds: 900,
    failureReason: null,
  },
  {
    id: 41,
    cycleType: 'NIGHTLY',
    status: 'FAILED',
    currentPhase: null,
    waitingOn: null,
    triggerTime: T,
    completedAt: '2026-05-26T02:30:00Z',
    durationSeconds: 5400,
    failureReason: 'Safety timeout: Batch set did not reach terminal status within PT90M',
  },
];

const MOCK_DETAIL = {
  run: MOCK_RUNS[1],
  phases: [
    {
      phase: 'FORECAST_BATCH_SUBMIT',
      sequenceOrder: 1,
      status: 'COMPLETED',
      startedAt: T,
      completedAt: '2026-05-26T01:01:00Z',
      durationSeconds: 60,
      detail: null,
    },
    {
      phase: 'FORECAST_BATCH_WAIT',
      sequenceOrder: 2,
      status: 'COMPLETED',
      startedAt: '2026-05-26T01:01:00Z',
      completedAt: '2026-05-26T01:13:00Z',
      durationSeconds: 720,
      detail: '4 of 4 batches reached a terminal status',
    },
    {
      phase: 'BRIEFING',
      sequenceOrder: 3,
      status: 'COMPLETED',
      startedAt: '2026-05-26T01:13:00Z',
      completedAt: '2026-05-26T01:15:00Z',
      durationSeconds: 120,
      detail: null,
    },
  ],
  batches: [
    {
      id: 7,
      jobRunId: 101,
      anthropicBatchId: 'msgbatch_abc',
      status: 'COMPLETED',
      requestCount: 50,
      succeededCount: 48,
      erroredCount: 2,
      submittedAt: T,
      endedAt: '2026-05-26T01:13:00Z',
    },
  ],
};

describe('PipelineRunsView', () => {
  beforeEach(() => {
    fetchPipelineRuns.mockReset();
    fetchPipelineRunDetail.mockReset();
  });

  it('renders recent runs with correct status pills and durations', async () => {
    fetchPipelineRuns.mockResolvedValue(MOCK_RUNS);

    render(
      <PipelineRunsView
        activeRunId={null}
        onSelectRun={() => {}}
        onCloseDetail={() => {}}
      />,
    );

    await screen.findByTestId('pipeline-runs-table');

    expect(screen.getByTestId('pipeline-run-row-43')).toBeInTheDocument();
    expect(screen.getByTestId('pipeline-run-row-42')).toBeInTheDocument();
    expect(screen.getByTestId('pipeline-run-row-41')).toBeInTheDocument();

    // Live run surfaces waitingOn in the row.
    expect(
      screen.getByText('forecast batch set (2 of 4 complete)'),
    ).toBeInTheDocument();

    // Failed run surfaces the failure reason (safety timeout prefix preserved).
    expect(
      screen.getByText(/Safety timeout: Batch set did not reach/),
    ).toBeInTheDocument();
  });

  it('shows empty state when there are no runs', async () => {
    fetchPipelineRuns.mockResolvedValue([]);

    render(
      <PipelineRunsView
        activeRunId={null}
        onSelectRun={() => {}}
        onCloseDetail={() => {}}
      />,
    );

    await screen.findByTestId('pipeline-runs-empty');
  });

  it('calls onSelectRun when a row is clicked', async () => {
    fetchPipelineRuns.mockResolvedValue(MOCK_RUNS);
    const onSelectRun = vi.fn();

    render(
      <PipelineRunsView
        activeRunId={null}
        onSelectRun={onSelectRun}
        onCloseDetail={() => {}}
      />,
    );

    const row = await screen.findByTestId('pipeline-run-row-42');
    fireEvent.click(row);
    expect(onSelectRun).toHaveBeenCalledWith(42);
  });

  it('renders the detail panel with phase timeline + batches when activeRunId is set', async () => {
    fetchPipelineRunDetail.mockResolvedValue(MOCK_DETAIL);

    render(
      <PipelineRunsView
        activeRunId={42}
        onSelectRun={() => {}}
        onCloseDetail={() => {}}
      />,
    );

    await screen.findByTestId('pipeline-run-detail-42');
    expect(screen.getByTestId('pipeline-phases-table')).toBeInTheDocument();
    expect(screen.getByTestId('pipeline-phase-row-FORECAST_BATCH_SUBMIT'))
      .toBeInTheDocument();
    expect(screen.getByTestId('pipeline-phase-row-FORECAST_BATCH_WAIT'))
      .toBeInTheDocument();
    expect(screen.getByTestId('pipeline-phase-row-BRIEFING')).toBeInTheDocument();
    expect(screen.getByTestId('pipeline-batches-table')).toBeInTheDocument();
    expect(screen.getByTestId('pipeline-batch-row-7')).toBeInTheDocument();
    // Wait-phase detail (final waiting_on) is rendered.
    expect(
      screen.getByText('4 of 4 batches reached a terminal status'),
    ).toBeInTheDocument();
  });

  it('expands a batch row to show the disposition breakdown', async () => {
    fetchPipelineRunDetail.mockResolvedValue(MOCK_DETAIL);

    render(
      <PipelineRunsView
        activeRunId={42}
        onSelectRun={() => {}}
        onCloseDetail={() => {}}
      />,
    );

    const toggle = await screen.findByTestId('pipeline-batch-toggle-7');
    fireEvent.click(toggle);
    await waitFor(() =>
      expect(screen.getByTestId('mock-disposition-101')).toBeInTheDocument(),
    );
  });

  it('surfaces waitingOn prominently on a live detail panel', async () => {
    fetchPipelineRunDetail.mockResolvedValue({
      ...MOCK_DETAIL,
      run: MOCK_RUNS[0],
    });

    render(
      <PipelineRunsView
        activeRunId={43}
        onSelectRun={() => {}}
        onCloseDetail={() => {}}
      />,
    );

    await screen.findByTestId('pipeline-run-detail-waiting');
    expect(
      screen.getByText(/Waiting on: forecast batch set \(2 of 4 complete\)/),
    ).toBeInTheDocument();
  });

  it('surfaces a failure reason on a FAILED detail panel', async () => {
    fetchPipelineRunDetail.mockResolvedValue({
      ...MOCK_DETAIL,
      run: MOCK_RUNS[2],
    });

    render(
      <PipelineRunsView
        activeRunId={41}
        onSelectRun={() => {}}
        onCloseDetail={() => {}}
      />,
    );

    await screen.findByTestId('pipeline-run-detail-failure');
    expect(
      screen.getByText(/Failure: Safety timeout: Batch set did not reach/),
    ).toBeInTheDocument();
  });

  it('does NOT render the cross-run comparison when comparison is absent (nightly)', async () => {
    fetchPipelineRunDetail.mockResolvedValue(MOCK_DETAIL);

    render(
      <PipelineRunsView activeRunId={42} onSelectRun={() => {}} onCloseDetail={() => {}} />,
    );

    await screen.findByTestId('pipeline-run-detail-42');
    expect(screen.queryByTestId('cross-run-comparison')).not.toBeInTheDocument();
  });

  it('renders the intraday-vs-nightly comparison with a "plan changed" verdict', async () => {
    const intradayDetail = {
      run: {
        ...MOCK_RUNS[1],
        id: 50,
        cycleType: 'INTRADAY',
      },
      phases: [
        {
          phase: 'STABILITY_RECLASSIFY',
          sequenceOrder: 1,
          status: 'COMPLETED',
          startedAt: T,
          completedAt: '2026-05-26T14:00:30Z',
          durationSeconds: 30,
          detail: '8 considered, 5 settled-skipped, 3 unsettled-evaluated',
        },
      ],
      batches: [],
      comparison: {
        baselineRunId: 42,
        baselineTriggerTime: T,
        diffs: [
          {
            rank: 1,
            changed: true,
            changedDimensions: ['REGION', 'RATING'],
            intraday: {
              headline: 'Coast tonight',
              region: 'North Yorkshire Coast',
              eventDate: '2026-05-26',
              eventType: 'sunset',
              confidence: 'HIGH',
              claudeAverageRating: 4.2,
            },
            nightly: {
              headline: 'Hills tonight',
              region: 'Northumberland',
              eventDate: '2026-05-26',
              eventType: 'sunset',
              confidence: 'MEDIUM',
              claudeAverageRating: 3.1,
            },
          },
          {
            rank: 2,
            changed: false,
            changedDimensions: [],
            intraday: {
              region: 'Lake District',
              eventDate: '2026-05-27',
              eventType: 'sunrise',
              claudeAverageRating: 3.5,
            },
            nightly: {
              region: 'Lake District',
              eventDate: '2026-05-27',
              eventType: 'sunrise',
              claudeAverageRating: 3.5,
            },
          },
        ],
      },
    };
    fetchPipelineRunDetail.mockResolvedValue(intradayDetail);

    render(
      <PipelineRunsView activeRunId={50} onSelectRun={() => {}} onCloseDetail={() => {}} />,
    );

    await screen.findByTestId('pipeline-run-detail-50');
    expect(screen.getByTestId('cross-run-comparison')).toBeInTheDocument();
    // A change in either plan flips the headline verdict to "plan changed".
    expect(screen.getByTestId('cross-run-verdict')).toHaveTextContent('plan changed');
    // Plan A shows the changed dimensions; Plan B is unchanged.
    expect(screen.getByTestId('cross-run-changed-1')).toHaveTextContent('region, rating');
    expect(screen.getByTestId('cross-run-diff-2')).toBeInTheDocument();
    expect(screen.queryByTestId('cross-run-changed-2')).not.toBeInTheDocument();
    // Both sides of the Plan A slot are shown.
    expect(screen.getByText('North Yorkshire Coast')).toBeInTheDocument();
    expect(screen.getByText('Northumberland')).toBeInTheDocument();
    // And the reclassify phase's cost-gate detail is visible in the timeline.
    expect(
      screen.getByText('8 considered, 5 settled-skipped, 3 unsettled-evaluated'),
    ).toBeInTheDocument();
  });
});
