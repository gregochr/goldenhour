import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import JobRunsMetricsView from '../components/JobRunsMetricsView.jsx';

// ── Mocks ────────────────────────────────────────────────────────────────

vi.mock('../api/batchApi', () => ({
  getRegions: vi.fn(),
  submitScheduledBatch: vi.fn(),
  submitJfdiBatch: vi.fn(),
}));

vi.mock('../api/metricsApi', () => ({
  getJobRuns: vi.fn(),
  getApiCalls: vi.fn(),
}));

vi.mock('../api/forecastApi', () => ({
  runVeryShortTermForecast: vi.fn(),
  runShortTermForecast: vi.fn(),
  runLongTermForecast: vi.fn(),
  refreshTideData: vi.fn(),
  backfillTideData: vi.fn(),
  fetchLocations: vi.fn(),
}));

vi.mock('../api/auroraApi', () => ({
  enrichBortle: vi.fn(),
}));

vi.mock('../api/briefingApi.js', () => ({
  runBriefing: vi.fn(),
}));

vi.mock('../api/modelsApi', () => ({
  getAvailableModels: vi.fn(),
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ isAdmin: true, role: 'ADMIN' }),
}));

vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: null, loading: false }),
}));

import { getRegions, submitScheduledBatch, submitJfdiBatch } from '../api/batchApi';
import { getJobRuns, getApiCalls } from '../api/metricsApi';
import { fetchLocations } from '../api/forecastApi';
import { getAvailableModels } from '../api/modelsApi';

const MOCK_REGIONS = [
  { id: 1, name: 'Cumbria', locationCount: 5 },
  { id: 2, name: 'Ayrshire', locationCount: 3 },
  { id: 3, name: 'Borders', locationCount: 2 },
];

const noop = () => {};

function setupDefaultMocks() {
  getRegions.mockResolvedValue(MOCK_REGIONS);
  getJobRuns.mockResolvedValue({ data: { content: [] } });
  getApiCalls.mockResolvedValue({ data: [] });
  fetchLocations.mockResolvedValue([]);
  getAvailableModels.mockResolvedValue({ optimisationStrategies: {} });
}

function renderView() {
  return render(
    <JobRunsMetricsView
      activeRunId={null}
      onActiveRunChange={noop}
      onActiveRunClear={noop}
    />,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  setupDefaultMocks();
});

// ── Batch buttons render ──────────────────────────────────────────────────

describe('Batch Evaluation buttons', () => {
  it('renders both batch buttons', async () => {
    renderView();
    await waitFor(() => {
      expect(screen.getByTestId('run-scheduled-batch-btn')).toBeInTheDocument();
      expect(screen.getByTestId('run-jfdi-batch-btn')).toBeInTheDocument();
    });
  });

  it('scheduled button shows correct label', async () => {
    renderView();
    await waitFor(() => {
      expect(screen.getByTestId('run-scheduled-batch-btn')).toHaveTextContent('Run Scheduled Batch');
    });
  });

  it('JFDI button shows correct label', async () => {
    renderView();
    await waitFor(() => {
      expect(screen.getByTestId('run-jfdi-batch-btn')).toHaveTextContent('Run JFDI Batch');
    });
  });
});

// ── Dialog opening ────────────────────────────────────────────────────────

describe('Batch dialog', () => {
  it('opens scheduled dialog with all regions pre-selected', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    expect(screen.getByText('Run Scheduled Batch')).toBeInTheDocument();
    expect(screen.getByText(/triage and stability gates/)).toBeInTheDocument();
    // All 3 regions should be checked
    expect(screen.getByTestId('batch-region-toggle-1').querySelector('input')).toBeChecked();
    expect(screen.getByTestId('batch-region-toggle-2').querySelector('input')).toBeChecked();
    expect(screen.getByTestId('batch-region-toggle-3').querySelector('input')).toBeChecked();
  });

  it('opens JFDI dialog with correct description', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-jfdi-batch-btn'));
    fireEvent.click(screen.getByTestId('run-jfdi-batch-btn'));

    expect(screen.getByText('Run JFDI Batch')).toBeInTheDocument();
    expect(screen.getByText(/all dates T\+0 to T\+3/)).toBeInTheDocument();
  });

  it('confirm button shows region count', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    expect(screen.getByTestId('confirm-dialog-confirm')).toHaveTextContent('Submit (3 regions)');
  });

  it('shows total location count in "All regions" label', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    // 5 + 3 + 2 = 10 locations
    expect(screen.getByTestId('batch-region-toggle-all')).toHaveTextContent('All regions (10 locations)');
  });

  it('shows summary text with selected count', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    expect(screen.getByText('3 of 3 regions selected')).toBeInTheDocument();
  });
});

// ── Region toggling ───────────────────────────────────────────────────────

describe('Region toggle', () => {
  it('unchecking a region reduces selected count', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    // Uncheck region 1
    fireEvent.click(screen.getByTestId('batch-region-toggle-1').querySelector('input'));

    expect(screen.getByTestId('batch-region-toggle-1').querySelector('input')).not.toBeChecked();
    expect(screen.getByText('2 of 3 regions selected')).toBeInTheDocument();
    expect(screen.getByTestId('confirm-dialog-confirm')).toHaveTextContent('Submit (2 regions)');
  });

  it('"All regions" toggle deselects all when all are selected', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    // All checked initially — clicking "All" should uncheck all
    fireEvent.click(screen.getByTestId('batch-region-toggle-all').querySelector('input'));

    expect(screen.getByTestId('batch-region-toggle-1').querySelector('input')).not.toBeChecked();
    expect(screen.getByTestId('batch-region-toggle-2').querySelector('input')).not.toBeChecked();
    expect(screen.getByTestId('batch-region-toggle-3').querySelector('input')).not.toBeChecked();
    expect(screen.getByText('0 of 3 regions selected')).toBeInTheDocument();
  });

  it('"All regions" toggle selects all when none are selected', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    // Deselect all first
    fireEvent.click(screen.getByTestId('batch-region-toggle-all').querySelector('input'));
    // Now select all again
    fireEvent.click(screen.getByTestId('batch-region-toggle-all').querySelector('input'));

    expect(screen.getByTestId('batch-region-toggle-1').querySelector('input')).toBeChecked();
    expect(screen.getByTestId('batch-region-toggle-2').querySelector('input')).toBeChecked();
    expect(screen.getByTestId('batch-region-toggle-3').querySelector('input')).toBeChecked();
    expect(screen.getByText('3 of 3 regions selected')).toBeInTheDocument();
  });

  it('confirm button shows singular "region" for single selection', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    // Deselect all, then select just one
    fireEvent.click(screen.getByTestId('batch-region-toggle-all').querySelector('input'));
    fireEvent.click(screen.getByTestId('batch-region-toggle-2').querySelector('input'));

    expect(screen.getByTestId('confirm-dialog-confirm')).toHaveTextContent('Submit (1 region)');
  });

  it('re-checking a region adds it back', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    // Uncheck then re-check region 2
    fireEvent.click(screen.getByTestId('batch-region-toggle-2').querySelector('input'));
    expect(screen.getByTestId('batch-region-toggle-2').querySelector('input')).not.toBeChecked();
    fireEvent.click(screen.getByTestId('batch-region-toggle-2').querySelector('input'));
    expect(screen.getByTestId('batch-region-toggle-2').querySelector('input')).toBeChecked();
    expect(screen.getByText('3 of 3 regions selected')).toBeInTheDocument();
  });
});

// ── Submission ────────────────────────────────────────────────────────────

describe('Batch submission', () => {
  it('scheduled batch sends null regionIds when all regions selected', async () => {
    submitScheduledBatch.mockResolvedValue({ batchId: 'batch-001', requestCount: 42 });
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));

    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    });

    await waitFor(() => {
      expect(submitScheduledBatch).toHaveBeenCalledWith(null);
    });
  });

  it('scheduled batch sends specific regionIds when subset selected', async () => {
    submitScheduledBatch.mockResolvedValue({ batchId: 'batch-002', requestCount: 10 });
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));

    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));
    // Uncheck region 3
    fireEvent.click(screen.getByTestId('batch-region-toggle-3').querySelector('input'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    });

    await waitFor(() => {
      expect(submitScheduledBatch).toHaveBeenCalledTimes(1);
      const passedIds = submitScheduledBatch.mock.calls[0][0];
      expect(passedIds).toHaveLength(2);
      expect(passedIds).toContain(1);
      expect(passedIds).toContain(2);
    });
  });

  it('JFDI batch calls submitJfdiBatch with null when all selected', async () => {
    submitJfdiBatch.mockResolvedValue({ batchId: 'jfdi-001', requestCount: 80 });
    renderView();
    await waitFor(() => screen.getByTestId('run-jfdi-batch-btn'));

    fireEvent.click(screen.getByTestId('run-jfdi-batch-btn'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    });

    await waitFor(() => {
      expect(submitJfdiBatch).toHaveBeenCalledWith(null);
    });
    expect(submitScheduledBatch).not.toHaveBeenCalled();
  });

  it('JFDI batch sends specific regionIds when subset selected', async () => {
    submitJfdiBatch.mockResolvedValue({ batchId: 'jfdi-002', requestCount: 20 });
    renderView();
    await waitFor(() => screen.getByTestId('run-jfdi-batch-btn'));

    fireEvent.click(screen.getByTestId('run-jfdi-batch-btn'));
    // Select only region 2
    fireEvent.click(screen.getByTestId('batch-region-toggle-all').querySelector('input'));
    fireEvent.click(screen.getByTestId('batch-region-toggle-2').querySelector('input'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    });

    await waitFor(() => {
      expect(submitJfdiBatch).toHaveBeenCalledWith([2]);
    });
  });

  it('shows success message with request count and batch ID', async () => {
    submitScheduledBatch.mockResolvedValue({ batchId: 'batch-success', requestCount: 15 });
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));

    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    });

    await waitFor(() => {
      expect(screen.getByText(/Scheduled batch submitted: 15 request\(s\), batch batch-success/)).toBeInTheDocument();
    });
  });

  it('shows 409 conflict error message', async () => {
    submitScheduledBatch.mockRejectedValue({ response: { status: 409 } });
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));

    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    });

    await waitFor(() => {
      expect(screen.getByText('A batch is already in progress. Wait for it to complete.')).toBeInTheDocument();
    });
  });

  it('shows generic error message for non-409 errors', async () => {
    submitJfdiBatch.mockRejectedValue({ response: { status: 500 } });
    renderView();
    await waitFor(() => screen.getByTestId('run-jfdi-batch-btn'));

    fireEvent.click(screen.getByTestId('run-jfdi-batch-btn'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    });

    await waitFor(() => {
      expect(screen.getByText('JFDI batch submission failed. Check the logs.')).toBeInTheDocument();
    });
  });

  it('closes dialog on cancel without submitting', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));

    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));
    expect(screen.getByText('Run Scheduled Batch')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'));

    expect(screen.queryByText(/triage and stability gates/)).not.toBeInTheDocument();
    expect(submitScheduledBatch).not.toHaveBeenCalled();
  });

  it('reloads job runs after successful submission', async () => {
    submitScheduledBatch.mockResolvedValue({ batchId: 'batch-reload', requestCount: 5 });
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));

    // Clear initial calls from mount
    getJobRuns.mockClear();

    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    });

    await waitFor(() => {
      expect(getJobRuns).toHaveBeenCalled();
    });
  });
});

// ── Region display ────────────────────────────────────────────────────────

describe('Region display in dialog', () => {
  it('shows each region name and location count', async () => {
    renderView();
    await waitFor(() => screen.getByTestId('run-scheduled-batch-btn'));
    fireEvent.click(screen.getByTestId('run-scheduled-batch-btn'));

    expect(screen.getByTestId('batch-region-toggle-1')).toHaveTextContent('Cumbria (5)');
    expect(screen.getByTestId('batch-region-toggle-2')).toHaveTextContent('Ayrshire (3)');
    expect(screen.getByTestId('batch-region-toggle-3')).toHaveTextContent('Borders (2)');
  });

  it('loads regions on mount from API', async () => {
    renderView();
    await waitFor(() => {
      expect(getRegions).toHaveBeenCalledTimes(1);
    });
  });
});
