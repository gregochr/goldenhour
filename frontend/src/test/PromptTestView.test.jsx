import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import PromptTestView from '../components/PromptTestView.jsx';

vi.mock('../api/promptTestApi', () => ({
  runPromptTest: vi.fn(),
  replayPromptTest: vi.fn(),
  getPromptTestRun: vi.fn(),
  getPromptTestRuns: vi.fn(),
  getPromptTestResults: vi.fn(),
  getGitInfo: vi.fn(),
}));

vi.mock('../api/forecastApi', () => ({
  fetchLocations: vi.fn(),
}));

vi.mock('../api/modelsApi', () => ({
  getAvailableModels: vi.fn(),
}));

import { runPromptTest, getPromptTestRun, getPromptTestRuns, getPromptTestResults, getGitInfo } from '../api/promptTestApi';
import { fetchLocations } from '../api/forecastApi';
import { getAvailableModels } from '../api/modelsApi';

describe('PromptTestView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    getPromptTestRuns.mockResolvedValue({ data: [] });
    getGitInfo.mockResolvedValue({
      data: { available: true, commitHash: 'abc1234567890', commitAbbrev: 'abc1234', dirty: false, branch: 'main', commitDate: '2026-03-01T10:00' },
    });
    fetchLocations.mockResolvedValue([
      { id: 1, name: 'Durham', enabled: true, locationType: ['LANDSCAPE'] },
      { id: 2, name: 'Bird Reserve', enabled: true, locationType: ['WILDLIFE'] },
      { id: 3, name: 'Bamburgh', enabled: true, locationType: ['SEASCAPE'] },
    ]);
    getAvailableModels.mockResolvedValue({
      available: [
        { name: 'HAIKU', version: '4.5' },
        { name: 'SONNET', version: '4.5' },
        { name: 'OPUS', version: '4.6' },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders run button, model radio buttons, and run type radio buttons', async () => {
    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-prompt-test-btn')).toBeInTheDocument();
    });
    expect(screen.getByTestId('model-radio-HAIKU')).toBeInTheDocument();
    expect(screen.getByTestId('model-radio-SONNET')).toBeInTheDocument();
    expect(screen.getByTestId('model-radio-OPUS')).toBeInTheDocument();
    expect(screen.getByTestId('run-type-radio-VERY_SHORT_TERM')).toBeInTheDocument();
    expect(screen.getByTestId('run-type-radio-SHORT_TERM')).toBeInTheDocument();
    expect(screen.getByTestId('run-type-radio-LONG_TERM')).toBeInTheDocument();
  });

  it('defaults to HAIKU model and VERY_SHORT_TERM run type', async () => {
    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('model-radio-HAIKU')).toBeChecked();
    });
    expect(screen.getByTestId('model-radio-SONNET')).not.toBeChecked();
    expect(screen.getByTestId('run-type-radio-VERY_SHORT_TERM')).toBeChecked();
    expect(screen.getByTestId('run-type-radio-SHORT_TERM')).not.toBeChecked();
    expect(screen.getByTestId('run-type-radio-LONG_TERM')).not.toBeChecked();
  });

  it('shows build info section with git details', async () => {
    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('build-info')).toBeInTheDocument();
    });
    expect(screen.getByTestId('build-info').textContent).toContain('abc1234');
    expect(screen.getByTestId('build-info').textContent).toContain('main');
  });

  it('hides build info when git is unavailable', async () => {
    getGitInfo.mockResolvedValue({
      data: { available: false, commitHash: '', commitAbbrev: '', dirty: false, branch: '', commitDate: '' },
    });

    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-prompt-test-btn')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('build-info')).not.toBeInTheDocument();
  });

  it('shows colour location count (excludes WILDLIFE)', async () => {
    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByText('2 colour locations')).toBeInTheDocument();
    });
  });

  it('shows confirmation dialog when run button clicked', async () => {
    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-prompt-test-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('run-prompt-test-btn'));

    const dialog = screen.getByTestId('confirm-dialog');
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText('Run Prompt Test')).toBeInTheDocument();
    expect(screen.getByTestId('confirm-dialog-cancel')).toBeInTheDocument();
    expect(screen.getByTestId('confirm-dialog-confirm')).toBeInTheDocument();
  });

  it('dismisses dialog on cancel', async () => {
    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-prompt-test-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('run-prompt-test-btn'));
    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'));

    expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument();
  });

  it('displays test runs table when runs exist', async () => {
    getPromptTestRuns.mockResolvedValue({
      data: [
        {
          id: 1,
          startedAt: '2026-03-01T10:00:00',
          completedAt: '2026-03-01T10:05:00',
          targetDate: '2026-03-01',
          targetType: 'SUNSET',
          evaluationModel: 'HAIKU',
          locationsCount: 12,
          succeeded: 12,
          failed: 0,
          durationMs: 15000,
          totalCostPence: 600,
          gitCommitHash: 'abc1234567890',
          gitBranch: 'main',
          gitDirty: false,
        },
      ],
    });

    render(<PromptTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('prompt-test-runs-table')).toBeInTheDocument();
    });

    expect(screen.getByText('SUNSET')).toBeInTheDocument();
    // HAIKU appears in both the radio label and the run row model badge
    expect(screen.getAllByText(/HAIKU/).length).toBeGreaterThanOrEqual(2);
  });

  it('shows in-progress indicator for run without completedAt', async () => {
    getPromptTestRuns.mockResolvedValue({
      data: [
        {
          id: 1,
          startedAt: '2026-03-01T10:00:00',
          completedAt: null,
          targetDate: '2026-03-01',
          targetType: 'SUNSET',
          evaluationModel: 'HAIKU',
          locationsCount: 5,
          succeeded: 2,
          failed: 0,
          durationMs: null,
        },
      ],
    });

    render(<PromptTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('run-progress-1')).toBeInTheDocument();
    });
    expect(screen.getByTestId('run-progress-1').textContent).toContain('2/5');
  });

  it('shows error state on API failure', async () => {
    getPromptTestRuns.mockRejectedValue(new Error('Network error'));

    render(<PromptTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('prompt-test-error')).toBeInTheDocument();
    });
  });

  it('allows selecting a different model', async () => {
    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('model-radio-HAIKU')).toBeChecked();
    });

    fireEvent.click(screen.getByTestId('model-radio-SONNET'));
    expect(screen.getByTestId('model-radio-SONNET')).toBeChecked();
    expect(screen.getByTestId('model-radio-HAIKU')).not.toBeChecked();
  });

  it('allows selecting a different run type', async () => {
    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-type-radio-VERY_SHORT_TERM')).toBeChecked();
    });

    fireEvent.click(screen.getByTestId('run-type-radio-LONG_TERM'));
    expect(screen.getByTestId('run-type-radio-LONG_TERM')).toBeChecked();
    expect(screen.getByTestId('run-type-radio-VERY_SHORT_TERM')).not.toBeChecked();
  });

  it('shows comparison checkboxes in run rows', async () => {
    getPromptTestRuns.mockResolvedValue({
      data: [
        { id: 1, startedAt: '2026-03-01T10:00:00', completedAt: '2026-03-01T10:05:00', targetDate: '2026-03-01', targetType: 'SUNSET', evaluationModel: 'HAIKU', locationsCount: 2, succeeded: 2, failed: 0, durationMs: 5000 },
        { id: 2, startedAt: '2026-03-02T10:00:00', completedAt: '2026-03-02T10:05:00', targetDate: '2026-03-02', targetType: 'SUNSET', evaluationModel: 'HAIKU', locationsCount: 2, succeeded: 2, failed: 0, durationMs: 5000 },
      ],
    });

    render(<PromptTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('compare-checkbox-1')).toBeInTheDocument();
      expect(screen.getByTestId('compare-checkbox-2')).toBeInTheDocument();
    });
  });

  it('shows comparison panel when 2 runs are checked', async () => {
    getPromptTestRuns.mockResolvedValue({
      data: [
        { id: 1, startedAt: '2026-03-01T10:00:00', completedAt: '2026-03-01T10:05:00', targetDate: '2026-03-01', targetType: 'SUNSET', evaluationModel: 'HAIKU', locationsCount: 2, succeeded: 2, failed: 0, durationMs: 5000, gitCommitHash: 'aaa1111', gitBranch: 'main', gitDirty: false },
        { id: 2, startedAt: '2026-03-02T10:00:00', completedAt: '2026-03-02T10:05:00', targetDate: '2026-03-02', targetType: 'SUNSET', evaluationModel: 'HAIKU', locationsCount: 2, succeeded: 2, failed: 0, durationMs: 5000, gitCommitHash: 'bbb2222', gitBranch: 'main', gitDirty: false },
      ],
    });
    getPromptTestResults.mockResolvedValue({
      data: [
        { id: 10, locationId: 1, locationName: 'Durham', succeeded: true, rating: 4, fierySkyPotential: 65, goldenHourPotential: 70 },
      ],
    });

    render(<PromptTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('compare-checkbox-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('compare-checkbox-1'));
    fireEvent.click(screen.getByTestId('compare-checkbox-2'));

    await waitFor(() => {
      expect(screen.getByTestId('comparison-panel')).toBeInTheDocument();
    });
  });

  it('shows replay button on each run row', async () => {
    getPromptTestRuns.mockResolvedValue({
      data: [
        { id: 1, startedAt: '2026-03-01T10:00:00', completedAt: '2026-03-01T10:05:00', targetDate: '2026-03-01', targetType: 'SUNSET', evaluationModel: 'HAIKU', locationsCount: 2, succeeded: 2, failed: 0, durationMs: 5000 },
      ],
    });

    render(<PromptTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('replay-btn-1')).toBeInTheDocument();
    });
  });

  it('shows model versions next to model radio buttons', async () => {
    render(<PromptTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('model-radio-HAIKU')).toBeInTheDocument();
    });

    // Model versions should appear as text near the radio buttons
    await waitFor(() => {
      expect(screen.getAllByText('4.5').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('4.6').length).toBeGreaterThanOrEqual(1);
    });
  });
});
