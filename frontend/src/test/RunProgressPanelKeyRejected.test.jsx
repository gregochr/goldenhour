import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, within } from '@testing-library/react';
import RunProgressPanel from '../components/RunProgressPanel.jsx';
import {
  TWO_FAILURES, KEY_REJECTED_TASKS, KEY_REJECTED_AFTER_ONE, KEY_REJECTED_RUN, KEY_REJECTED_PLACE,
  NOT_ATTEMPTED_PLACE, evaluationFailed, summaryEvent, completeEvent, createFeeds,
} from './runProgressFixtures.js';

// A run whose Claude API key is rejected stops: the places not yet evaluated are not attempted and
// the server says so in the completion payload (`reason`, `retryable: false`). Re-running the failed
// places would fail them the same way, so the panel withdraws Retry and says why in one line.

vi.mock('../api/runProgressApi', () => ({
  subscribeToRunProgress: vi.fn(),
  retryFailed: vi.fn(),
}));

import { subscribeToRunProgress } from '../api/runProgressApi';

const NOT_OFFERED = 'Retry is not offered: fix the API key, then start the run again.';

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

  it('shows no Retry button, the one explanatory line, the run reason and Dismiss', async () => {
    const handlers = renderPanel();

    await playRun(5, KEY_REJECTED_TASKS, { reason: KEY_REJECTED_RUN, retryable: false });

    expect(screen.queryByTestId('retry-failed-btn')).toBeNull();
    expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
    expect(screen.getByTestId('retry-not-offered')).toHaveTextContent(NOT_OFFERED);
    expect(screen.getByTestId('run-progress-reason')).toHaveTextContent(KEY_REJECTED_RUN);
    expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
    expect(handlers.onAutoClear).not.toHaveBeenCalled();
  });

  it('puts the explanatory line in the same row as Dismiss, where the Retry button would be', async () => {
    renderPanel();

    await playRun(5, KEY_REJECTED_TASKS, { reason: KEY_REJECTED_RUN, retryable: false });

    const row = screen.getByTestId('retry-not-offered').parentElement;
    expect(within(row).getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
  });

  it('renders the per-place phrases: the place that hit the rejection and those never attempted', async () => {
    renderPanel();

    await playRun(5, KEY_REJECTED_TASKS, { reason: KEY_REJECTED_RUN, retryable: false });

    const rows = screen.getAllByTestId('run-progress-row');
    expect(rows).toHaveLength(3);
    expect(within(rows.find((r) => r.textContent.includes('Test Hill'))).getByText(KEY_REJECTED_PLACE))
      .toBeInTheDocument();
    ['East Fell', 'West Fell'].forEach((name) => {
      expect(within(rows.find((r) => r.textContent.includes(name))).getByText(NOT_ATTEMPTED_PLACE))
        .toBeInTheDocument();
    });
  });

  it('reads as failed when nothing completed: the status word is Failed and nothing is counted complete', async () => {
    renderPanel();

    await playRun(5, KEY_REJECTED_TASKS, { reason: KEY_REJECTED_RUN, retryable: false });

    expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Failed)');
    expect(screen.getByText('3 failed')).toBeInTheDocument();
    expect(screen.queryByText(/complete$/)).toBeNull();
  });

  it('a run stopped after a place had completed is kept, still shows no Retry, and still says why', async () => {
    renderPanel();

    await playRun(5, KEY_REJECTED_AFTER_ONE, { reason: KEY_REJECTED_RUN, retryable: false });

    expect(screen.getByText('1 complete')).toBeInTheDocument();
    expect(screen.getByText('2 failed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
    expect(screen.getByTestId('retry-not-offered')).toHaveTextContent(NOT_OFFERED);
  });

  it('shows Retry and no explanatory line when the payload says retryable: true', async () => {
    renderPanel();

    await playRun(5, TWO_FAILURES, { retryable: true });

    expect(screen.getByRole('button', { name: 'Retry 2 failed' })).toBeInTheDocument();
    expect(screen.queryByTestId('retry-not-offered')).toBeNull();
  });

  it('keeps Retry for a payload that predates the field (retryable absent)', async () => {
    renderPanel();
    const { retryable, ...older } = completeEvent(5, TWO_FAILURES);
    expect(retryable).toBe(true); // the fixture mirrors the server; the older payload simply lacks the key

    await act(async () => {
      TWO_FAILURES.forEach((t) => feed.feeds[5].onTask(t));
      feed.feeds[5].onSummary(summaryEvent(5, TWO_FAILURES));
      feed.feeds[5].onComplete(older);
    });

    expect(older).not.toHaveProperty('retryable');
    expect(screen.getByRole('button', { name: 'Retry 2 failed' })).toBeInTheDocument();
    expect(screen.queryByTestId('retry-not-offered')).toBeNull();
  });

  it('does not show the explanatory line while the run is still going', async () => {
    renderPanel();

    await act(async () => {
      feed.feeds[5].onTask(evaluationFailed('hill|a', 'Test Hill', KEY_REJECTED_PLACE));
      feed.feeds[5].onSummary(summaryEvent(5, KEY_REJECTED_TASKS));
    });

    expect(screen.queryByTestId('retry-not-offered')).toBeNull();
  });
});
