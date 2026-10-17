import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import RunProgressPanel from '../components/RunProgressPanel.jsx';
import {
  TWO_FAILURES, NO_FAILURES, task, completeEvent, summaryEvent, createFeeds,
} from './runProgressFixtures.js';

// A run can fail as a whole, not only task by task: the server then says why in the completion
// payload's `reason`. A run that failed before it had any task reports total 0 and failed 0, which
// the panel used to read as a clean run and clear — the admin was never told it had failed.

vi.mock('../api/runProgressApi', () => ({
  subscribeToRunProgress: vi.fn(),
  retryFailed: vi.fn(),
}));

import { subscribeToRunProgress } from '../api/runProgressApi';

const OPEN_METEO = 'Weather data (Open-Meteo) could not be fetched; nothing was updated.';
const UNEXPECTED = 'The run stopped unexpectedly. See the server log.';

let feed;

const playRun = async (jobRunId, tasks, options = {}, { complete = true } = {}) => {
  await act(async () => {
    tasks.forEach((t) => feed.feeds[jobRunId].onTask(t));
    feed.feeds[jobRunId].onSummary(summaryEvent(jobRunId, tasks));
    if (complete) feed.feeds[jobRunId].onComplete(completeEvent(jobRunId, tasks, options));
  });
};

const renderPanel = (props = {}) => {
  const handlers = { onAutoClear: vi.fn(), onDismiss: vi.fn(), onComplete: vi.fn() };
  render(<RunProgressPanel jobRunId={5} {...handlers} {...props} />);
  return handlers;
};

describe('RunProgressPanel run-level reason', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    feed = createFeeds();
    subscribeToRunProgress.mockImplementation(feed.subscribe);
  });

  it('shows the reason as one alert line in a kept run with failed places, beside Retry and Dismiss', async () => {
    const handlers = renderPanel();

    await playRun(5, TWO_FAILURES, { reason: OPEN_METEO });

    expect(screen.getByRole('alert')).toHaveTextContent(OPEN_METEO);
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Retry 2 failed' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
    expect(handlers.onAutoClear).not.toHaveBeenCalled();
  });

  it('shows no reason line for a clean run, and the clean run still clears itself', async () => {
    const handlers = renderPanel();

    await playRun(5, NO_FAILURES);

    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByTestId('run-progress-reason')).toBeNull();
    expect(handlers.onAutoClear).toHaveBeenCalledTimes(1);
  });

  it('shows no reason line for a run with failed places but no run-level reason', async () => {
    renderPanel();

    await playRun(5, TWO_FAILURES);

    expect(screen.queryByTestId('run-progress-reason')).toBeNull();
    expect(screen.getByRole('button', { name: 'Retry 2 failed' })).toBeInTheDocument();
  });

  describe('a run that failed before it had any task (total 0, failed 0, status FAILED)', () => {
    it('is KEPT with its reason and Dismiss, and is not auto-cleared', async () => {
      const handlers = renderPanel();

      await playRun(5, [], { reason: UNEXPECTED });

      expect(handlers.onAutoClear).not.toHaveBeenCalled();
      expect(handlers.onComplete).toHaveBeenCalledWith(expect.objectContaining({
        status: 'FAILED', total: 0, failed: 0, reason: UNEXPECTED,
      }));
      expect(screen.getByRole('alert')).toHaveTextContent(UNEXPECTED);
      expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
    });

    it('offers no Retry: there is no failed place to retry', async () => {
      renderPanel();

      await playRun(5, [], { reason: UNEXPECTED });

      expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
    });

    it('is kept on its FAILED status alone, whatever the reason says', async () => {
      const handlers = renderPanel();

      await act(async () => {
        feed.feeds[5].onComplete({ ...completeEvent(5, []), status: 'FAILED', reason: null });
      });

      expect(handlers.onAutoClear).not.toHaveBeenCalled();
      expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
    });
  });

  describe('the keep/clear decision reads the status and the reason as well as the failed count', () => {
    it('keeps a PARTIAL run even when it reports no failed tasks, finished and dismissible, with no Retry', async () => {
      const handlers = renderPanel();

      await act(async () => {
        feed.feeds[5].onComplete({ ...completeEvent(5, NO_FAILURES), status: 'PARTIAL' });
      });

      expect(handlers.onAutoClear).not.toHaveBeenCalled();
      expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Complete)');
      expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
    });

    it('keeps a run that carries a reason even when its status and failed count read clean', async () => {
      const handlers = renderPanel();

      await act(async () => {
        feed.feeds[5].onComplete({ ...completeEvent(5, NO_FAILURES), reason: UNEXPECTED });
      });

      expect(handlers.onAutoClear).not.toHaveBeenCalled();
      expect(screen.getByRole('alert')).toHaveTextContent(UNEXPECTED);
    });

    it('still clears a COMPLETE run with no failures and no reason', async () => {
      const handlers = renderPanel();

      await act(async () => {
        feed.feeds[5].onComplete({ ...completeEvent(5, NO_FAILURES), status: 'COMPLETE', reason: null });
      });

      expect(handlers.onAutoClear).toHaveBeenCalledTimes(1);
    });

    it('still clears a replayed completion that predates the reason field entirely', async () => {
      const handlers = renderPanel();
      const { reason, ...legacy } = completeEvent(5, NO_FAILURES);
      expect(reason).toBeNull();

      await act(async () => { feed.feeds[5].onComplete(legacy); });

      expect(handlers.onAutoClear).toHaveBeenCalledTimes(1);
      expect(screen.queryByRole('alert')).toBeNull();
    });
  });

  describe('the header of a finished run', () => {
    it('reads "(Failed)", not "(Complete)", when the run failed, and omits the 0/0 count of a zero-task run', async () => {
      renderPanel();

      await playRun(5, [], { reason: UNEXPECTED, durationMs: 1500 });

      const header = screen.getByTestId('run-progress-status');
      expect(header).toHaveTextContent('(Failed)');
      expect(header).not.toHaveTextContent('Complete');
      expect(screen.getByText('1.5s')).toBeInTheDocument();
      expect(screen.queryByText(/0\/0/)).toBeNull();
    });

    it('reads "(Failed)" for a run whose every task failed, and still shows its count', async () => {
      renderPanel();

      await playRun(5, [task('a|b', 'A', 'FAILED'), task('c|d', 'C', 'FAILED')]);

      expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Failed)');
      expect(screen.getByText(/2\/2/)).toBeInTheDocument();
    });

    it('reads "(Completed with failures)" for a PARTIAL run with no reason, the banner\'s own rule', async () => {
      renderPanel();

      await playRun(5, TWO_FAILURES);

      expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Completed with failures)');
    });

    it('reads "(Completed with failures)" for a run whose other places were all triaged', async () => {
      renderPanel();

      await playRun(5, [task('a|b', 'A', 'TRIAGED'), task('c|d', 'C', 'FAILED')]);

      expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Completed with failures)');
    });

    it('keeps "(Complete)" for a COMPLETE run kept for some other reason, with its count', async () => {
      renderPanel();

      await act(async () => {
        feed.feeds[5].onComplete({ ...completeEvent(5, NO_FAILURES), reason: UNEXPECTED });
      });

      expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Complete)');
      expect(screen.getByText(/3\/3/)).toBeInTheDocument(); // 2 complete + 1 skipped of 3
    });

    it('still shows "0/0" while a zero-task run has not completed', async () => {
      renderPanel();

      await act(async () => { feed.feeds[5].onSummary(summaryEvent(5, [])); });

      expect(screen.getByText(/0\/0/)).toBeInTheDocument();
    });
  });

  describe('a replayed completion after a remount', () => {
    it('shows the reason again when the server replays run-complete to the new subscription', async () => {
      const first = render(<RunProgressPanel jobRunId={5} onAutoClear={vi.fn()} />);
      await playRun(5, [], { reason: OPEN_METEO });
      expect(screen.getByRole('alert')).toHaveTextContent(OPEN_METEO);
      first.unmount();
      expect(screen.queryByRole('alert')).toBeNull();

      render(<RunProgressPanel jobRunId={5} onAutoClear={vi.fn()} onDismiss={vi.fn()} />);
      await playRun(5, [], { reason: OPEN_METEO });

      expect(feed.subscriptions).toEqual([5, 5]);
      expect(screen.getByRole('alert')).toHaveTextContent(OPEN_METEO);
      expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Failed)');
      expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
    });
  });
});
