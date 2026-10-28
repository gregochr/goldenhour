import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, within } from '@testing-library/react';
import RunProgressPanel from '../components/RunProgressPanel.jsx';
import {
  TWO_FAILURES, NO_FAILURES, KEY_REJECTED_NOTHING_TRIAGED, KEY_REJECTED_TRIAGED, KEY_REJECTED_AFTER_ONE, KEY_REJECTED_RUN, KEY_REJECTED_PLACE,
  NOT_ATTEMPTED_PLACE, summaryEvent, completeEvent, createFeeds,
} from './runProgressFixtures.js';

// A run whose Claude API key is rejected stops: the places not yet evaluated are not attempted and
// the server says so in the completion payload (`reason`). The panel keeps the
// run on screen with the reason and the per-place phrases, and offers only Dismiss.

vi.mock('../api/runProgressApi', () => ({
  subscribeToRunProgress: vi.fn(),
}));

import { subscribeToRunProgress } from '../api/runProgressApi';

let feed;

const playRun = async (jobRunId, tasks, options = {}) => {
  await act(async () => {
    tasks.forEach((t) => feed.feeds[jobRunId].onTask(t));
    feed.feeds[jobRunId].onSummary(summaryEvent(jobRunId, tasks));
    feed.feeds[jobRunId].onComplete(completeEvent(jobRunId, tasks, options));
  });
};

const renderPanel = () => {
  const handlers = { onAutoClear: vi.fn(), onDismiss: vi.fn(), onComplete: vi.fn() };
  render(<RunProgressPanel jobRunId={5} {...handlers} />);
  return handlers;
};

describe('RunProgressPanel for a run stopped on a rejected API key', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    feed = createFeeds();
    subscribeToRunProgress.mockImplementation(feed.subscribe);
  });

  it('nothing triaged, nothing completed (status FAILED): no Retry control, the run reason and Dismiss', async () => {
    const handlers = renderPanel();

    await playRun(5, KEY_REJECTED_NOTHING_TRIAGED, { reason: KEY_REJECTED_RUN });

    expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
    expect(screen.getByTestId('run-progress-reason')).toHaveTextContent(KEY_REJECTED_RUN);
    expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
    expect(handlers.onAutoClear).not.toHaveBeenCalled();
  });

  it('nothing triaged: renders the per-place phrases: the place that hit the rejection and those never attempted', async () => {
    renderPanel();

    await playRun(5, KEY_REJECTED_NOTHING_TRIAGED, { reason: KEY_REJECTED_RUN });

    const rows = screen.getAllByTestId('run-progress-row');
    expect(rows).toHaveLength(3);
    expect(within(rows.find((r) => r.textContent.includes('Test Hill'))).getByText(KEY_REJECTED_PLACE))
      .toBeInTheDocument();
    ['East Fell', 'West Fell'].forEach((name) => {
      expect(within(rows.find((r) => r.textContent.includes(name))).getByText(NOT_ATTEMPTED_PLACE))
        .toBeInTheDocument();
    });
  });

  it('nothing triaged, nothing completed: the header reads (Failed) and nothing is counted complete', async () => {
    renderPanel();

    await playRun(5, KEY_REJECTED_NOTHING_TRIAGED, { reason: KEY_REJECTED_RUN });

    expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Failed)');
    expect(screen.getByTestId('run-progress-status')).not.toHaveTextContent('Stopped');
    expect(screen.getByText('3 failed')).toBeInTheDocument();
    expect(screen.queryByText(/complete$/)).toBeNull();
  });

  it('a run stopped after a place had completed is kept, reads (Stopped early) and shows no Retry', async () => {
    renderPanel();

    await playRun(5, KEY_REJECTED_AFTER_ONE, { reason: KEY_REJECTED_RUN });

    expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Stopped early)');
    expect(screen.getByText('1 complete')).toBeInTheDocument();
    expect(screen.getByText('2 failed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
  });

  describe('the shape production sends: places triaged, so the status is PARTIAL rather than FAILED', () => {
    const playReal = () => playRun(5, KEY_REJECTED_TRIAGED, { reason: KEY_REJECTED_RUN });

    it('reads (Stopped early), not (Complete) or (Failed)', async () => {
      renderPanel();

      await playReal();

      expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Stopped early)');
    });

    it('counts 1 triaged, 2 failed and 1 skipped, and no place complete', async () => {
      renderPanel();

      await playReal();

      expect(screen.getByText('1 triaged')).toBeInTheDocument();
      expect(screen.getByText('2 failed')).toBeInTheDocument();
      expect(screen.getByText('1 skipped')).toBeInTheDocument();
      expect(screen.queryByText(/\d+ complete$/)).toBeNull();
    });

    it('shows the reason and Dismiss, and no Retry', async () => {
      const handlers = renderPanel();

      await playReal();

      expect(screen.getByTestId('run-progress-reason')).toHaveTextContent(KEY_REJECTED_RUN);
      expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
      expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
      expect(handlers.onAutoClear).not.toHaveBeenCalled();
    });
  });

  describe('the status word of a finished run', () => {
    it('is (Completed with failures) for failed places with no run reason', async () => {
      renderPanel();
      await playRun(5, TWO_FAILURES);
      expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Completed with failures)');
    });

    it('is (Complete) for a run in which everything finished cleanly', async () => {
      renderPanel();
      await playRun(5, NO_FAILURES);
      expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Complete)');
    });
  });
});
