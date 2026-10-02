import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import RunProgressPanel from '../components/RunProgressPanel.jsx';
import {
  TWO_FAILURES, KEY_REJECTED_NOTHING_TRIAGED, KEY_REJECTED_RUN, LIGHT_POLLUTION_ONE_FAILURE,
  LIGHT_POLLUTION_COMPLETE_OPTIONS, RETRY_ACCEPTED, summaryEvent, completeEvent, createFeeds,
} from './runProgressFixtures.js';

// When Retry is offered, what the endpoint answers, and which line stands where Retry would be. The
// server's completion payload carries `retryable` and, since the retry became "exactly the failed
// slots", `retryBlockedReason` (API_KEY_REJECTED, LIGHT_POLLUTION, NOT_FORECAST_SLOTS): the same answer
// the retry endpoint reads, so the panel never offers a retry the server would refuse.

vi.mock('../api/runProgressApi', () => ({
  subscribeToRunProgress: vi.fn(),
  retryFailed: vi.fn(),
}));

import { subscribeToRunProgress, retryFailed } from '../api/runProgressApi';

const KEY_LINE = 'Retry is not offered: fix the API key, then start the run again.';
const LIGHT_POLLUTION_LINE = 'Retry is not offered: press Refresh Light Pollution again.';
const GENERIC_LINE = 'Retry is not offered for this run.';

/** The server's refusal as the shared axios client rejects with it. */
const refusal = (status, data = null) => Object.assign(new Error(`Request failed with status code ${status}`), {
  response: { status, data },
});

let feed;

const playRun = async (jobRunId, tasks, options = {}) => {
  await act(async () => {
    tasks.forEach((t) => feed.feeds[jobRunId].onTask(t));
    feed.feeds[jobRunId].onSummary(summaryEvent(jobRunId, tasks));
    feed.feeds[jobRunId].onComplete(completeEvent(jobRunId, tasks, options));
  });
};

describe('RunProgressPanel: what Retry offers and why it is withheld', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    feed = createFeeds();
    subscribeToRunProgress.mockImplementation(feed.subscribe);
  });

  describe('a light-pollution run with a failed place', () => {
    it('offers no Retry button and says to press Refresh Light Pollution again, in the Retry button\'s row beside Dismiss', async () => {
      render(<RunProgressPanel jobRunId={5} onDismiss={vi.fn()} />);

      await playRun(5, LIGHT_POLLUTION_ONE_FAILURE, LIGHT_POLLUTION_COMPLETE_OPTIONS);

      expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
      const line = screen.getByTestId('retry-not-offered');
      expect(line.textContent).toBe(LIGHT_POLLUTION_LINE);
      expect(within(line.parentElement).getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
    });

    it('is kept on screen (it has a failed place) and does not name the API key', async () => {
      const onAutoClear = vi.fn();
      render(<RunProgressPanel jobRunId={5} onAutoClear={onAutoClear} />);

      await playRun(5, LIGHT_POLLUTION_ONE_FAILURE, LIGHT_POLLUTION_COMPLETE_OPTIONS);

      expect(onAutoClear).not.toHaveBeenCalled();
      expect(screen.queryByText(/API key/)).toBeNull();
    });
  });

  describe('a run stopped on a rejected API key', () => {
    it('keeps its exact line when the payload names API_KEY_REJECTED', async () => {
      render(<RunProgressPanel jobRunId={5} />);

      await playRun(5, KEY_REJECTED_NOTHING_TRIAGED, {
        reason: KEY_REJECTED_RUN, retryable: false, retryBlockedReason: 'API_KEY_REJECTED',
      });

      expect(screen.getByTestId('retry-not-offered').textContent).toBe(KEY_LINE);
      expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
    });

    it('keeps its exact line for the earlier payload shape: retryable false, no reason named', async () => {
      render(<RunProgressPanel jobRunId={5} />);
      const earlier = completeEvent(5, KEY_REJECTED_NOTHING_TRIAGED, { reason: KEY_REJECTED_RUN, retryable: false });
      delete earlier.retryBlockedReason;

      await act(async () => {
        KEY_REJECTED_NOTHING_TRIAGED.forEach((t) => feed.feeds[5].onTask(t));
        feed.feeds[5].onSummary(summaryEvent(5, KEY_REJECTED_NOTHING_TRIAGED));
        feed.feeds[5].onComplete(earlier);
      });

      expect(screen.getByTestId('retry-not-offered').textContent).toBe(KEY_LINE);
    });
  });

  describe('other reasons', () => {
    it('words NOT_FORECAST_SLOTS, and any reason it does not know, as the generic line', async () => {
      const { unmount } = render(<RunProgressPanel jobRunId={5} />);
      await playRun(5, TWO_FAILURES, { retryable: false, retryBlockedReason: 'NOT_FORECAST_SLOTS' });
      expect(screen.getByTestId('retry-not-offered').textContent).toBe(GENERIC_LINE);
      unmount();

      render(<RunProgressPanel jobRunId={6} />);
      await playRun(6, TWO_FAILURES, { retryable: false, retryBlockedReason: 'SOMETHING_NEW' });
      expect(screen.getByTestId('retry-not-offered').textContent).toBe(GENERIC_LINE);
      expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
    });

    it('offers Retry, and no line, for a retryable run', async () => {
      render(<RunProgressPanel jobRunId={5} />);

      await playRun(5, TWO_FAILURES);

      expect(screen.getByRole('button', { name: 'Retry 2 failed' })).toBeInTheDocument();
      expect(screen.queryByTestId('retry-not-offered')).toBeNull();
    });
  });

  describe('pressing Retry', () => {
    it('hands the parent the retry run\'s id AND the server\'s whole answer', async () => {
      retryFailed.mockResolvedValue(RETRY_ACCEPTED);
      const onRetryStarted = vi.fn();
      render(<RunProgressPanel jobRunId={5} onRetryStarted={onRetryStarted} />);
      await playRun(5, TWO_FAILURES);

      await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Retry 2 failed' })); });

      expect(retryFailed).toHaveBeenCalledWith(5);
      expect(onRetryStarted).toHaveBeenCalledWith(6, RETRY_ACCEPTED);
    });

    it('shows the server\'s own sentence, whole, for a 409 refusal, through the existing error line', async () => {
      retryFailed.mockRejectedValue(refusal(409, {
        error: 'This run was stopped because Claude rejected the API key. Fix the key, then start the run again.',
      }));
      render(<RunProgressPanel jobRunId={5} />);
      await playRun(5, TWO_FAILURES);

      await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Retry 2 failed' })); });

      expect(screen.getByTestId('retry-failed-error').textContent).toBe(
        'This run was stopped because Claude rejected the API key. Fix the key, then start the run again.');
    });

    it('shows the light-pollution 409 sentence the same way', async () => {
      retryFailed.mockRejectedValue(refusal(409, {
        error: 'Light-pollution failures are retried by pressing Refresh Light Pollution again.',
      }));
      render(<RunProgressPanel jobRunId={5} />);
      await playRun(5, TWO_FAILURES);

      await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Retry 2 failed' })); });

      expect(screen.getByTestId('retry-failed-error').textContent)
        .toBe('Light-pollution failures are retried by pressing Refresh Light Pollution again.');
    });
  });

  describe('a promoted retry run', () => {
    it('shows what was started under the header when given a startedNote', () => {
      render(<RunProgressPanel jobRunId={6} startedNote="Retrying 2 slots." />);

      expect(screen.getByTestId('retry-run-note').textContent).toBe('Retrying 2 slots.');
    });
  });
});
