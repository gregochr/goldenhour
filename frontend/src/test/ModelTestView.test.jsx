import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import ModelTestView from '../components/ModelTestView.jsx';

// Mock the API module
vi.mock('../api/modelTestApi', () => ({
  runModelTest: vi.fn(),
  runModelTestForLocation: vi.fn(),
  rerunModelTest: vi.fn(),
  getModelTestRuns: vi.fn(),
  getModelTestResults: vi.fn(),
}));

import { getModelTestRuns } from '../api/modelTestApi';

describe('ModelTestView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getModelTestRuns.mockResolvedValue({ data: [] });
  });

  it('renders run button', async () => {
    render(<ModelTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-model-test-btn')).toBeInTheDocument();
    });
  });

  it('shows confirmation dialog when run button clicked', async () => {
    render(<ModelTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-model-test-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('run-model-test-btn'));

    const dialog = screen.getByTestId('confirm-dialog');
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText('Run Model Test')).toBeInTheDocument();
    expect(screen.getByTestId('confirm-dialog-cancel')).toBeInTheDocument();
    expect(screen.getByTestId('confirm-dialog-confirm')).toBeInTheDocument();
  });

  it('dismisses dialog on cancel', async () => {
    render(<ModelTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-model-test-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('run-model-test-btn'));
    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'));

    expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument();
  });

  it('displays test runs table when runs exist', async () => {
    getModelTestRuns.mockResolvedValue({
      data: [
        {
          id: 1,
          targetDate: '2026-03-01',
          targetType: 'SUNSET',
          regionsCount: 2,
          succeeded: 6,
          failed: 0,
          durationMs: 15000,
          totalCostPence: 300,
        },
      ],
    });

    render(<ModelTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('model-test-runs-table')).toBeInTheDocument();
    });

    expect(screen.getByText('SUNSET')).toBeInTheDocument();
  });

  it('shows error state on API failure', async () => {
    getModelTestRuns.mockRejectedValue(new Error('Network error'));

    render(<ModelTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('model-test-error')).toBeInTheDocument();
    });
  });
});
