import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import RunProgressPanel from '../components/RunProgressPanel.jsx';
import {
  TWO_FAILURES, KEY_REJECTED_NOTHING_TRIAGED, KEY_REJECTED_RUN, LIGHT_POLLUTION_ONE_FAILURE,
  LIGHT_POLLUTION_COMPLETE_OPTIONS, RETRY_ACCEPTED, task, summaryEvent, completeEvent, createFeeds,
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

    /**
     * Presses Retry on a run whose server answers 409, and checks what the admin is left with: the
     * server's sentence once, announced as an alert, and a button that is Retry again, not "Retrying...".
     */
    const expect409Shown = async (tasks, buttonName, sentence) => {
      retryFailed.mockRejectedValue(refusal(409, { error: sentence }));
      render(<RunProgressPanel jobRunId={5} />);
      await playRun(5, tasks);

      await act(async () => { fireEvent.click(screen.getByRole('button', { name: buttonName })); });

      const alerts = screen.getAllByRole('alert').filter((a) => a.textContent === sentence);
      expect(alerts).toHaveLength(1);
      expect(alerts[0]).toBe(screen.getByTestId('retry-failed-error'));
      expect(screen.getAllByText(sentence)).toHaveLength(1);
      expect(screen.queryByRole('button', { name: 'Retrying...' })).toBeNull();
      expect(screen.getByRole('button', { name: buttonName })).not.toHaveAttribute('aria-disabled');
    };

    it('shows the server\'s own sentence, whole, for a 409 refusal, through the existing error line', async () => {
      await expect409Shown(TWO_FAILURES, 'Retry 2 failed',
        'This run was stopped because Claude rejected the API key. Fix the key, then start the run again.');
    });

    it('shows the light-pollution 409 sentence the same way, for a light-pollution run whose payload '
      + 'still offered Retry (one from before the server withdrew it)', async () => {
      // completeEvent defaults to retryable: true, as a payload from before `retryBlockedReason` existed.
      await expect409Shown(LIGHT_POLLUTION_ONE_FAILURE, 'Retry 1 failed',
        'Light-pollution failures are retried by pressing Refresh Light Pollution again.');
    });

    it('shows the "already retried" 409 sentence, naming the first retry\'s run, the same way (a second '
      + 'admin\'s panel, opened after the retry started)', async () => {
      await expect409Shown(TWO_FAILURES, 'Retry 2 failed',
        'This run has already been retried as run 8. Retry that run\'s failures instead.');
    });

    it('shows the "still going" 409 sentence the same way', async () => {
      await expect409Shown(TWO_FAILURES, 'Retry 2 failed',
        'This run is still going. Retry is offered when it has finished.');
    });
  });

  describe('task updates that arrive out of order', () => {
    const update = async (t) => { await act(async () => { feed.feeds[5].onTask(t); }); };

    it.each(['COMPLETE', 'FAILED', 'SKIPPED', 'TRIAGED'])(
      'a row that has finished as %s is not moved back to Pending by a stale update', async (finished) => {
        render(<RunProgressPanel jobRunId={5} />);

        await update(task('hill|a', 'Test Hill', finished));
        await update(task('hill|a', 'Test Hill', 'PENDING'));

        const row = screen.getByTestId('run-progress-row');
        expect(row.textContent).not.toMatch(/Pending/);
      });

    it.each([['FETCHING_WEATHER', 'Weather'], ['EVALUATING', 'Evaluating']])(
      'a FAILED row is not moved back to %s by a stale update', async (earlier, badge) => {
        render(<RunProgressPanel jobRunId={5} />);

        await update(task('hill|a', 'Test Hill', 'FAILED'));
        await update(task('hill|a', 'Test Hill', earlier));

        const row = screen.getByTestId('run-progress-row');
        expect(within(row).getByText('Failed')).toBeInTheDocument();
        expect(within(row).queryByText(badge)).toBeNull();
      });

    it('still moves an unfinished row forward, and a pending row to a phase', async () => {
      render(<RunProgressPanel jobRunId={5} />);

      await update(task('hill|a', 'Test Hill', 'PENDING'));
      await update(task('hill|a', 'Test Hill', 'EVALUATING'));
      expect(screen.getByTestId('run-progress-row').textContent).not.toMatch(/Pending/);
      await update(task('hill|a', 'Test Hill', 'COMPLETE'));

      expect(screen.getByTestId('run-progress-row')).toHaveTextContent('Complete');
    });
  });

  describe('a promoted retry run', () => {
    it('shows what was started under the header when given a startedNote', () => {
      render(<RunProgressPanel jobRunId={6} startedNote="Retrying 2 slots." />);

      expect(screen.getByTestId('retry-run-note').textContent).toBe('Retrying 2 slots.');
    });
  });
});
