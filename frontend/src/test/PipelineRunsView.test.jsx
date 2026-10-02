import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
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
  {
    id: 249,
    cycleType: 'INTRADAY',
    status: 'DEGRADED',
    currentPhase: null,
    waitingOn: null,
    triggerTime: T,
    completedAt: '2026-05-26T01:20:00Z',
    durationSeconds: 1200,
    failureReason: '3 of 3 forecast batch submissions failed (510 requests not submitted — '
      + 'near-term inland, near-term coastal, far-term inland)',
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

// A realistic DEGRADED detail: the FORECAST_BATCH_SUBMIT phase row is itself FAILED (its detail
// matching the run's own failureReason), while WAIT and BRIEFING both still ran to COMPLETED —
// the phase timeline a real degraded cycle produces, not MOCK_DETAIL's all-COMPLETED phases with
// only the run swapped out.
const MOCK_DEGRADED_DETAIL = {
  run: MOCK_RUNS[3],
  phases: [
    {
      phase: 'FORECAST_BATCH_SUBMIT',
      sequenceOrder: 1,
      status: 'FAILED',
      startedAt: T,
      completedAt: '2026-05-26T01:00:30Z',
      durationSeconds: 30,
      detail: MOCK_RUNS[3].failureReason,
    },
    {
      phase: 'FORECAST_BATCH_WAIT',
      sequenceOrder: 2,
      status: 'COMPLETED',
      startedAt: '2026-05-26T01:00:30Z',
      completedAt: '2026-05-26T01:05:00Z',
      durationSeconds: 270,
      detail: 'no batches submitted',
    },
    {
      phase: 'BRIEFING',
      sequenceOrder: 3,
      status: 'COMPLETED',
      startedAt: '2026-05-26T01:05:00Z',
      completedAt: '2026-05-26T01:20:00Z',
      durationSeconds: 900,
      detail: null,
    },
  ],
  batches: [],
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

  it('shows a DEGRADED pill and its failureReason in the list row, exactly as a FAILED '
    + 'row shows its own', async () => {
    fetchPipelineRuns.mockResolvedValue(MOCK_RUNS);

    render(
      <PipelineRunsView
        activeRunId={null}
        onSelectRun={() => {}}
        onCloseDetail={() => {}}
      />,
    );

    const row = await screen.findByTestId('pipeline-run-row-249');
    const pill = within(row).getByTestId('status-pill-DEGRADED');
    expect(pill).toBeInTheDocument();
    // A distinct colour from RUNNING's amber — a finished DEGRADED run must not read as "still
    // running" in the list. Asserting the class itself (not merely that the pill exists) fails
    // if STATUS_PILL_CLASSES.DEGRADED is ever deleted and the component falls back to its own
    // generic zinc default, which would also render a status-pill-DEGRADED testid.
    expect(pill.className).toContain('orange');
    expect(pill.className).not.toContain('amber');
    expect(row).toHaveTextContent(
      '3 of 3 forecast batch submissions failed (510 requests not submitted',
    );
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

  it("shows the server's error sentence, not axios's generic message, when the list is refused", async () => {
    fetchPipelineRuns.mockRejectedValue(Object.assign(
      new Error('Request failed with status code 500'),
      { response: { status: 500, data: { error: 'An unexpected error occurred' } } },
    ));

    render(
      <PipelineRunsView activeRunId={null} onSelectRun={() => {}} onCloseDetail={() => {}} />,
    );

    const banner = await screen.findByTestId('pipeline-runs-error');
    expect(banner).toHaveTextContent('An unexpected error occurred');
    expect(banner).not.toHaveTextContent('Request failed with status code 500');
  });

  it("shows the server's error sentence, not axios's generic message, when a run's detail is refused", async () => {
    fetchPipelineRunDetail.mockRejectedValue(Object.assign(
      new Error('Request failed with status code 404'),
      { response: { status: 404, data: { error: 'Pipeline run 42 not found' } } },
    ));

    render(
      <PipelineRunsView activeRunId={42} onSelectRun={() => {}} onCloseDetail={() => {}} />,
    );

    expect(await screen.findByText('Pipeline run 42 not found')).toBeInTheDocument();
    expect(screen.queryByText(/Request failed with status code 404/)).toBeNull();
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

  it('flags the retry batch and shows the RETRY_FAILED phase with its recovery detail', async () => {
    fetchPipelineRunDetail.mockResolvedValue({
      ...MOCK_DETAIL,
      phases: [
        ...MOCK_DETAIL.phases.slice(0, 2),
        {
          phase: 'RETRY_FAILED',
          sequenceOrder: 3,
          status: 'COMPLETED',
          startedAt: '2026-05-26T01:13:00Z',
          completedAt: '2026-05-26T01:14:00Z',
          durationSeconds: 60,
          detail: '1 failed, 1 retried, 1 recovered, 0 still-failed',
        },
        MOCK_DETAIL.phases[2],
      ],
      batches: [
        MOCK_DETAIL.batches[0],
        {
          id: 8,
          jobRunId: 102,
          anthropicBatchId: 'msgbatch_retry',
          status: 'COMPLETED',
          requestCount: 1,
          succeededCount: 1,
          erroredCount: 0,
          submittedAt: '2026-05-26T01:13:00Z',
          endedAt: '2026-05-26T01:14:00Z',
          retry: true,
        },
      ],
    });

    render(
      <PipelineRunsView activeRunId={42} onSelectRun={() => {}} onCloseDetail={() => {}} />,
    );

    await screen.findByTestId('pipeline-run-detail-42');
    // The RETRY_FAILED phase shows in the timeline with its recovery summary.
    expect(screen.getByTestId('pipeline-phase-row-RETRY_FAILED')).toBeInTheDocument();
    expect(
      screen.getByText('1 failed, 1 retried, 1 recovered, 0 still-failed'),
    ).toBeInTheDocument();
    // The retry batch is flagged distinctly; the precursor batch (id 7) is not.
    expect(screen.getByTestId('pipeline-batch-retry-badge-8')).toBeInTheDocument();
    expect(screen.queryByTestId('pipeline-batch-retry-badge-7')).not.toBeInTheDocument();
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

  it('surfaces the failureReason on a DEGRADED detail panel, under its own "Degraded:" '
    + 'label distinct from a FAILED run\'s "Failure:", with the phase timeline showing '
    + 'FORECAST_BATCH_SUBMIT itself FAILED while WAIT and BRIEFING both completed', async () => {
    fetchPipelineRunDetail.mockResolvedValue(MOCK_DEGRADED_DETAIL);

    render(
      <PipelineRunsView
        activeRunId={249}
        onSelectRun={() => {}}
        onCloseDetail={() => {}}
      />,
    );

    await screen.findByTestId('pipeline-run-detail-degraded');
    expect(
      screen.getByText(/Degraded: 3 of 3 forecast batch submissions failed/),
    ).toBeInTheDocument();
    // The DEGRADED pill itself is orange, distinct from RUNNING's amber.
    const pill = screen.getByTestId('status-pill-DEGRADED');
    expect(pill.className).toContain('orange');
    // The FAILED-only block must not also render for a DEGRADED run.
    expect(screen.queryByTestId('pipeline-run-detail-failure')).not.toBeInTheDocument();

    // The phase timeline itself reflects the real shape: SUBMIT failed, WAIT and BRIEFING
    // still completed — proving the cycle really did carry on rather than aborting.
    const submitRow = screen.getByTestId('pipeline-phase-row-FORECAST_BATCH_SUBMIT');
    expect(within(submitRow).getByTestId('status-pill-FAILED')).toBeInTheDocument();
    const waitRow = screen.getByTestId('pipeline-phase-row-FORECAST_BATCH_WAIT');
    expect(within(waitRow).getByTestId('status-pill-COMPLETED')).toBeInTheDocument();
    const briefingRow = screen.getByTestId('pipeline-phase-row-BRIEFING');
    expect(within(briefingRow).getByTestId('status-pill-COMPLETED')).toBeInTheDocument();
  });

  it.each([
    ['FAILED', MOCK_RUNS[2], 41],
    ['RUNNING', MOCK_RUNS[0], 43],
    ['COMPLETED', MOCK_RUNS[1], 42],
  ])('a %s detail panel never renders the DEGRADED-only block', async (status, run, runId) => {
    fetchPipelineRunDetail.mockResolvedValue({ ...MOCK_DETAIL, run });

    render(
      <PipelineRunsView
        activeRunId={runId}
        onSelectRun={() => {}}
        onCloseDetail={() => {}}
      />,
    );

    await screen.findByTestId(`pipeline-run-detail-${runId}`);
    expect(screen.queryByTestId('pipeline-run-detail-degraded')).not.toBeInTheDocument();
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
