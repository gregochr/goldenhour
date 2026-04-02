import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import BriefingModelTestView from '../components/BriefingModelTestView.jsx';

vi.mock('../api/briefingModelTestApi', () => ({
  runBriefingModelTest: vi.fn(),
  getBriefingModelTestRuns: vi.fn(),
  getBriefingModelTestResults: vi.fn(),
}));

import {
  getBriefingModelTestRuns,
  getBriefingModelTestResults,
} from '../api/briefingModelTestApi';

describe('BriefingModelTestView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getBriefingModelTestRuns.mockResolvedValue({ data: [] });
  });

  it('renders run button', async () => {
    render(<BriefingModelTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-briefing-model-test-btn')).toBeInTheDocument();
    });
  });

  it('renders empty state when no runs', async () => {
    render(<BriefingModelTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('briefing-model-test-empty')).toBeInTheDocument();
    });
  });

  it('shows confirmation dialog when run button clicked', async () => {
    render(<BriefingModelTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-briefing-model-test-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('run-briefing-model-test-btn'));

    expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument();
    expect(screen.getByTestId('confirm-dialog-cancel')).toBeInTheDocument();
    expect(screen.getByTestId('confirm-dialog-confirm')).toBeInTheDocument();
  });

  it('dismisses dialog on cancel', async () => {
    render(<BriefingModelTestView />);
    await waitFor(() => {
      expect(screen.getByTestId('run-briefing-model-test-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('run-briefing-model-test-btn'));
    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'));

    expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument();
  });

  it('renders runs table when runs exist', async () => {
    getBriefingModelTestRuns.mockResolvedValue({
      data: [
        {
          id: 1,
          startedAt: '2026-03-31T10:00:00',
          briefingGeneratedAt: '2026-03-31T08:00:00',
          succeeded: 3,
          failed: 0,
          durationMs: 12000,
          totalCostMicroDollars: 5000,
          exchangeRateGbpPerUsd: 0.79,
        },
      ],
    });

    render(<BriefingModelTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('briefing-model-test-runs-table')).toBeInTheDocument();
    });

    expect(screen.getByText('#1')).toBeInTheDocument();
  });

  it('clicking a run loads results', async () => {
    getBriefingModelTestRuns.mockResolvedValue({
      data: [
        {
          id: 1,
          startedAt: '2026-03-31T10:00:00',
          succeeded: 3,
          failed: 0,
          durationMs: 12000,
          totalCostMicroDollars: 5000,
          exchangeRateGbpPerUsd: 0.79,
        },
      ],
    });

    getBriefingModelTestResults.mockResolvedValue({
      data: [
        {
          id: 1,
          testRunId: 1,
          evaluationModel: 'HAIKU',
          succeeded: true,
          durationMs: 2000,
          inputTokens: 500,
          outputTokens: 100,
          costMicroDollars: 100,
          picksReturned: 2,
          picksValid: 2,
          picksJson: JSON.stringify([
            { rank: 1, headline: 'Go shoot', event: 'e1', region: 'Northumberland', confidence: 'high' },
          ]),
        },
        {
          id: 2,
          testRunId: 1,
          evaluationModel: 'SONNET',
          succeeded: true,
          durationMs: 3000,
          inputTokens: 500,
          outputTokens: 150,
          costMicroDollars: 300,
          picksReturned: 2,
          picksValid: 2,
          picksJson: JSON.stringify([
            { rank: 1, headline: 'Also go', event: 'e1', region: 'Northumberland', confidence: 'high' },
          ]),
        },
        {
          id: 3,
          testRunId: 1,
          evaluationModel: 'OPUS',
          succeeded: true,
          durationMs: 5000,
          inputTokens: 500,
          outputTokens: 200,
          costMicroDollars: 1500,
          picksReturned: 2,
          picksValid: 2,
          picksJson: JSON.stringify([
            { rank: 1, headline: 'Go now', event: 'e1', region: 'Northumberland', confidence: 'high' },
          ]),
        },
      ],
    });

    render(<BriefingModelTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('briefing-run-row-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('briefing-run-row-1'));

    await waitFor(() => {
      expect(screen.getByTestId('briefing-model-test-results')).toBeInTheDocument();
    });

    expect(screen.getByTestId('metric-HAIKU')).toBeInTheDocument();
    expect(screen.getByTestId('metric-SONNET')).toBeInTheDocument();
    expect(screen.getByTestId('metric-OPUS')).toBeInTheDocument();
  });

  it('shows green agreement when all models agree on pick', async () => {
    getBriefingModelTestRuns.mockResolvedValue({
      data: [{ id: 1, startedAt: '2026-03-31T10:00:00', succeeded: 3, failed: 0,
        durationMs: 12000, totalCostMicroDollars: 5000, exchangeRateGbpPerUsd: 0.79 }],
    });

    const sharedPick = JSON.stringify([
      { rank: 1, headline: 'Go', event: 'e1', region: 'North', confidence: 'high' },
    ]);
    getBriefingModelTestResults.mockResolvedValue({
      data: ['HAIKU', 'SONNET', 'OPUS'].map((m, i) => ({
        id: i + 1, testRunId: 1, evaluationModel: m, succeeded: true,
        durationMs: 1000, inputTokens: 500, outputTokens: 100,
        costMicroDollars: 100, picksReturned: 1, picksValid: 1, picksJson: sharedPick,
      })),
    });

    render(<BriefingModelTestView />);
    await waitFor(() => { expect(screen.getByTestId('briefing-run-row-1')).toBeInTheDocument(); });
    fireEvent.click(screen.getByTestId('briefing-run-row-1'));

    await waitFor(() => {
      const pickRank1 = screen.getByTestId('pick-rank-1');
      expect(pickRank1.className).toContain('border-l-green-500');
    });
  });

  it('shows amber agreement when 2/3 models agree', async () => {
    getBriefingModelTestRuns.mockResolvedValue({
      data: [{ id: 1, startedAt: '2026-03-31T10:00:00', succeeded: 3, failed: 0,
        durationMs: 12000, totalCostMicroDollars: 5000, exchangeRateGbpPerUsd: 0.79 }],
    });

    getBriefingModelTestResults.mockResolvedValue({
      data: [
        { id: 1, testRunId: 1, evaluationModel: 'HAIKU', succeeded: true,
          durationMs: 1000, inputTokens: 500, outputTokens: 100,
          costMicroDollars: 100, picksReturned: 1, picksValid: 1,
          picksJson: JSON.stringify([{ rank: 1, event: 'e1', region: 'North', confidence: 'high' }]) },
        { id: 2, testRunId: 1, evaluationModel: 'SONNET', succeeded: true,
          durationMs: 1000, inputTokens: 500, outputTokens: 100,
          costMicroDollars: 100, picksReturned: 1, picksValid: 1,
          picksJson: JSON.stringify([{ rank: 1, event: 'e1', region: 'North', confidence: 'high' }]) },
        { id: 3, testRunId: 1, evaluationModel: 'OPUS', succeeded: true,
          durationMs: 1000, inputTokens: 500, outputTokens: 100,
          costMicroDollars: 100, picksReturned: 1, picksValid: 1,
          picksJson: JSON.stringify([{ rank: 1, event: 'e2', region: 'South', confidence: 'high' }]) },
      ],
    });

    render(<BriefingModelTestView />);
    await waitFor(() => { expect(screen.getByTestId('briefing-run-row-1')).toBeInTheDocument(); });
    fireEvent.click(screen.getByTestId('briefing-run-row-1'));

    await waitFor(() => {
      const pickRank1 = screen.getByTestId('pick-rank-1');
      expect(pickRank1.className).toContain('border-l-amber-500');
    });
  });

  it('shows red disagreement when all models differ', async () => {
    getBriefingModelTestRuns.mockResolvedValue({
      data: [{ id: 1, startedAt: '2026-03-31T10:00:00', succeeded: 3, failed: 0,
        durationMs: 12000, totalCostMicroDollars: 5000, exchangeRateGbpPerUsd: 0.79 }],
    });

    getBriefingModelTestResults.mockResolvedValue({
      data: [
        { id: 1, testRunId: 1, evaluationModel: 'HAIKU', succeeded: true,
          durationMs: 1000, inputTokens: 500, outputTokens: 100,
          costMicroDollars: 100, picksReturned: 1, picksValid: 1,
          picksJson: JSON.stringify([{ rank: 1, event: 'e1', region: 'North', confidence: 'high' }]) },
        { id: 2, testRunId: 1, evaluationModel: 'SONNET', succeeded: true,
          durationMs: 1000, inputTokens: 500, outputTokens: 100,
          costMicroDollars: 100, picksReturned: 1, picksValid: 1,
          picksJson: JSON.stringify([{ rank: 1, event: 'e2', region: 'South', confidence: 'high' }]) },
        { id: 3, testRunId: 1, evaluationModel: 'OPUS', succeeded: true,
          durationMs: 1000, inputTokens: 500, outputTokens: 100,
          costMicroDollars: 100, picksReturned: 1, picksValid: 1,
          picksJson: JSON.stringify([{ rank: 1, event: 'e3', region: 'West', confidence: 'high' }]) },
      ],
    });

    render(<BriefingModelTestView />);
    await waitFor(() => { expect(screen.getByTestId('briefing-run-row-1')).toBeInTheDocument(); });
    fireEvent.click(screen.getByTestId('briefing-run-row-1'));

    await waitFor(() => {
      const pickRank1 = screen.getByTestId('pick-rank-1');
      expect(pickRank1.className).toContain('border-l-red-500');
    });
  });

  it('shows error banner on API failure', async () => {
    getBriefingModelTestRuns.mockRejectedValue(new Error('Network error'));

    render(<BriefingModelTestView />);

    await waitFor(() => {
      expect(screen.getByTestId('briefing-model-test-error')).toBeInTheDocument();
    });
  });
});
