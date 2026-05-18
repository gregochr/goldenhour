import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import JobRunsGrid from '../components/JobRunsGrid.jsx';

const noop = () => {};

function baseRun(overrides = {}) {
  return {
    id: 42,
    runType: 'SCHEDULED_BATCH',
    startedAt: '2026-05-18T02:05:30Z',
    completedAt: '2026-05-18T02:14:00Z',
    durationMs: 510000,
    succeeded: 29,
    failed: 0,
    appVersion: 'v2.11.16',
    notes: 'Anthropic batch: msgbatch_01CNDdRgMZQKXjZyFSXQqZTg',
    ...overrides,
  };
}

describe('JobRunsGrid SCHEDULED_BATCH summary line', () => {
  it('renders the batch summary line when batchSummary is present', () => {
    const run = baseRun({
      batchSummary: {
        horizonRange: 'T+1',
        eventTypes: ['SUNRISE'],
        evaluationModel: 'HAIKU',
        locationCount: 29,
        regionCount: 7,
        extendedThinking: false,
      },
    });

    render(<JobRunsGrid runs={[run]} onLoadMore={noop} />);

    const summary = screen.getByTestId('batch-summary-42');
    expect(summary.textContent).toContain('T+1');
    expect(summary.textContent).toContain('SUNRISE');
    expect(summary.textContent).toContain('29 locations');
    expect(summary.textContent).toContain('HAIKU');
    expect(summary.textContent).toContain('7 regions');
    expect(summary.textContent).not.toContain('(ET)');
  });

  it('joins multiple event types with " / "', () => {
    const run = baseRun({
      batchSummary: {
        horizonRange: 'T to T+2',
        eventTypes: ['SUNRISE', 'SUNSET'],
        evaluationModel: 'SONNET',
        locationCount: 14,
        regionCount: 5,
        extendedThinking: false,
      },
    });

    render(<JobRunsGrid runs={[run]} onLoadMore={noop} />);

    const summary = screen.getByTestId('batch-summary-42');
    expect(summary.textContent).toContain('T to T+2');
    expect(summary.textContent).toContain('SUNRISE / SUNSET');
  });

  it('appends "(ET)" when extendedThinking is true', () => {
    const run = baseRun({
      batchSummary: {
        horizonRange: 'T+1',
        eventTypes: ['SUNRISE'],
        evaluationModel: 'SONNET_ET',
        locationCount: 10,
        regionCount: 3,
        extendedThinking: true,
      },
    });

    render(<JobRunsGrid runs={[run]} onLoadMore={noop} />);

    const summary = screen.getByTestId('batch-summary-42');
    expect(summary.textContent).toContain('SONNET_ET (ET)');
  });

  it('shows "No detail available" for a completed batch with no summary', () => {
    const run = baseRun({ batchSummary: undefined });

    render(<JobRunsGrid runs={[run]} onLoadMore={noop} />);

    expect(screen.getByTestId('batch-summary-missing-42')).toHaveTextContent('No detail available');
  });

  it('does not render the summary line for non-batch run types', () => {
    const run = baseRun({
      runType: 'TIDE',
      notes: null,
      batchSummary: undefined,
    });

    render(<JobRunsGrid runs={[run]} onLoadMore={noop} />);

    expect(screen.queryByTestId('batch-summary-42')).not.toBeInTheDocument();
    expect(screen.queryByTestId('batch-summary-missing-42')).not.toBeInTheDocument();
  });

  it('does not render the missing-detail placeholder while the batch is still in progress', () => {
    const run = baseRun({
      completedAt: null,
      durationMs: null,
      succeeded: 5,
      batchSummary: undefined,
    });

    render(<JobRunsGrid runs={[run]} onLoadMore={noop} />);

    expect(screen.queryByTestId('batch-summary-missing-42')).not.toBeInTheDocument();
  });
});
