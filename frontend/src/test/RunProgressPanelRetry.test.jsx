import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import RunProgressPanel from '../components/RunProgressPanel.jsx';
import {
  TWO_FAILURES, NO_FAILURES, ONE_FAILURE, completeEvent, summaryEvent, createFeeds,
} from './runProgressFixtures.js';

// Since #977 the backend answers retry-failed with 404 and an empty body when none of a run's
// failed places can be run again. The panel used to swallow every failure, so the admin pressed
// "Retry failed" and saw nothing happen. The retry request is mocked at the api-module boundary here;
// RunProgressPanelRetryHttp.test.jsx joins the real retryFailed to this panel.

vi.mock('../api/runProgressApi', () => ({
  subscribeToRunProgress: vi.fn(),
  retryFailed: vi.fn(),
}));

import { subscribeToRunProgress, retryFailed } from '../api/runProgressApi';

const NOTHING = "Nothing to retry: this run's failed places can no longer be run again.";
const GENERIC = 'Could not start the retry.';
const STARTED_UNNAMED = 'Retry started.';
const EXPIRED = "This run's progress is no longer available.";

/** The server's refusal as the shared axios client rejects with it. */
const refusal = (status, data = null) => Object.assign(new Error(`Request failed with status code ${status}`), {
  response: { status, data },
});

let feed;
/** Promises the test is holding open; settled in afterEach so a failed assertion cannot leak one. */
let held;
const hold = () => {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  const entry = { promise, resolve, reject };
  held.push(entry);
  return entry;
};

/** Plays a run into the panel the way the server does: task updates, run-summary, run-complete. */
const playRun = async (feedHandlers, jobRunId, tasks, { complete = true } = {}) => {
  await act(async () => {
    tasks.forEach((t) => feedHandlers.onTask(t));
    feedHandlers.onSummary(summaryEvent(jobRunId, tasks));
    if (complete) feedHandlers.onComplete(completeEvent(jobRunId, tasks));
  });
};

const renderFinishedRun = async (props = {}, tasks = TWO_FAILURES, jobRunId = 5) => {
  const view = render(<RunProgressPanel jobRunId={jobRunId} {...props} />);
  await playRun(feed.feeds[jobRunId], jobRunId, tasks);
  return view;
};

const retryButton = (n = 2) => screen.getByRole('button', { name: `Retry ${n} failed` });
const press = async (button = retryButton()) => {
  await act(async () => { fireEvent.click(button); });
};

describe('RunProgressPanel retry', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    held = [];
    feed = createFeeds();
    subscribeToRunProgress.mockImplementation(feed.subscribe);
  });
  afterEach(async () => {
    await act(async () => {
      held.forEach((h) => h.resolve({ jobRunId: 999 }));
    });
  });

  describe('the failure line', () => {
    it('says there is nothing to retry when the backend answers 404 with no body', async () => {
      retryFailed.mockRejectedValue(refusal(404));
      await renderFinishedRun();

      await press();

      expect(screen.getByRole('alert')).toHaveTextContent(NOTHING, { normalizeWhitespace: true });
      expect(screen.getByRole('alert').textContent).toBe(NOTHING);
    });

    it('shows the server\'s own sentence, whole, for any other refusal that has one', async () => {
      retryFailed.mockRejectedValue(refusal(400, { error: 'Run 5 is still in progress' }));
      await renderFinishedRun();

      await press();

      expect(screen.getByRole('alert').textContent).toBe('Run 5 is still in progress');
      expect(screen.queryByText(NOTHING)).toBeNull();
    });

    it('shows the generic line when a refusal says nothing usable', async () => {
      retryFailed.mockRejectedValue(refusal(500));
      await renderFinishedRun();

      await press();

      expect(screen.getByRole('alert').textContent).toBe(GENERIC);
    });

    it('shows the generic line when the request never got an answer', async () => {
      retryFailed.mockRejectedValue(new Error('Network Error'));
      await renderFinishedRun();

      await press();

      expect(screen.getByRole('alert').textContent).toBe(GENERIC);
    });

    it('shows no line before any press', async () => {
      await renderFinishedRun();

      expect(screen.queryByRole('alert')).toBeNull();
    });

    it('removes the line while the next attempt is out, and a repeat failure is a NEW alert', async () => {
      retryFailed.mockRejectedValueOnce(refusal(404));
      await renderFinishedRun();
      await press();
      const firstAlert = screen.getByRole('alert');

      const second = hold();
      retryFailed.mockReturnValueOnce(second.promise);
      await press();
      expect(screen.queryByRole('alert')).toBeNull();
      expect(firstAlert).not.toBeInTheDocument();

      await act(async () => { second.reject(refusal(404)); });
      const secondAlert = screen.getByRole('alert');
      expect(secondAlert.textContent).toBe(NOTHING);
      // A different element: the alert was cleared and remounted, so it is announced again.
      expect(secondAlert).not.toBe(firstAlert);
    });
  });

  describe('the button', () => {
    it('stays enabled, focused and un-busy after a refusal', async () => {
      retryFailed.mockRejectedValue(refusal(404));
      await renderFinishedRun();
      const button = retryButton();
      button.focus();

      await press(button);

      expect(button).toBeEnabled();
      expect(button).not.toHaveAttribute('aria-disabled');
      expect(button).toHaveTextContent('Retry 2 failed');
      expect(button).toHaveFocus();
    });

    it('is aria-disabled, never disabled, while the request is out', async () => {
      const pending = hold();
      retryFailed.mockReturnValue(pending.promise);
      await renderFinishedRun();

      await press();

      // A focused button that becomes `disabled` loses focus to <body> in a real browser (jsdom does
      // not model that, so the attribute is what is pinned).
      const busy = screen.getByRole('button', { name: 'Retrying...' });
      expect(busy).toHaveAttribute('aria-disabled', 'true');
      expect(busy).not.toHaveAttribute('disabled');
    });

    it('calls the API once when pressed twice inside one act, before any re-render', async () => {
      const pending = hold();
      retryFailed.mockReturnValue(pending.promise);
      await renderFinishedRun();
      const button = retryButton();

      // One act: both clicks reach the handler against the SAME render's state, so only the ref
      // guard can stop the second.
      await act(async () => {
        fireEvent.click(button);
        fireEvent.click(button);
      });

      expect(retryFailed).toHaveBeenCalledTimes(1);
    });

    it('announces that the retry is starting only while the request is out', async () => {
      const pending = hold();
      retryFailed.mockReturnValue(pending.promise);
      await renderFinishedRun();
      expect(screen.queryByRole('status')).toBeNull();

      await press();
      expect(screen.getByRole('status').textContent).toBe('Starting retry…');

      await act(async () => { pending.reject(refusal(404)); });
      expect(screen.queryByRole('status')).toBeNull();
    });
  });

  describe('Dismiss', () => {
    it('is inert and aria-disabled while the retry request is out, so the run it names has a home', async () => {
      const pending = hold();
      retryFailed.mockReturnValue(pending.promise);
      const onDismiss = vi.fn();
      await renderFinishedRun({ onDismiss });
      await press();

      const dismiss = screen.getByRole('button', { name: 'Dismiss run progress' });
      expect(dismiss).toHaveAttribute('aria-disabled', 'true');
      await act(async () => { fireEvent.click(dismiss); });
      expect(onDismiss).not.toHaveBeenCalled();

      await act(async () => { pending.reject(refusal(404)); });
      expect(dismiss).not.toHaveAttribute('aria-disabled');
      await act(async () => { fireEvent.click(dismiss); });
      expect(onDismiss).toHaveBeenCalledTimes(1);
    });

    it('is not offered while the run is still going', async () => {
      render(<RunProgressPanel jobRunId={5} onDismiss={vi.fn()} />);
      await playRun(feed.feeds[5], 5, TWO_FAILURES, { complete: false });

      expect(screen.queryByRole('button', { name: 'Dismiss run progress' })).toBeNull();
    });
  });

  describe('a retry the server accepted', () => {
    it('hands the retry run\'s id and the server\'s answer to the parent and does not mount a second panel', async () => {
      const answer = { status: 'Retry run started', runType: 'SHORT_TERM', jobRunId: 77 };
      retryFailed.mockResolvedValue(answer);
      const onRetryStarted = vi.fn();
      await renderFinishedRun({ onRetryStarted });

      await press();

      expect(retryFailed).toHaveBeenCalledWith(5);
      expect(onRetryStarted).toHaveBeenCalledExactlyOnceWith(77, answer);
      expect(screen.getAllByTestId('run-progress-panel')).toHaveLength(1);
    });

    it('says "Retry started." and offers no second press when the answer names no run', async () => {
      const answer = { status: 'Retry run started' };
      retryFailed.mockResolvedValue(answer);
      const onRetryStarted = vi.fn();
      await renderFinishedRun({ onRetryStarted });

      await press();

      expect(screen.getByRole('status').textContent).toBe(STARTED_UNNAMED);
      expect(screen.queryByRole('alert')).toBeNull();
      expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
      expect(onRetryStarted).toHaveBeenCalledExactlyOnceWith(undefined, answer);
    });
  });
});

describe('RunProgressPanel completion', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    held = [];
    feed = createFeeds();
    subscribeToRunProgress.mockImplementation(feed.subscribe);
  });

  it('keeps the panel for exactly one failure, and passes the payload to onComplete', async () => {
    const onComplete = vi.fn();
    const onAutoClear = vi.fn();
    await renderFinishedRun({ onComplete, onAutoClear }, ONE_FAILURE);

    expect(onComplete).toHaveBeenCalledExactlyOnceWith(completeEvent(5, ONE_FAILURE));
    expect(onAutoClear).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Retry 1 failed' })).toBeInTheDocument();
  });

  it('auto-clears for zero failures, and still passes the payload to onComplete', async () => {
    const onComplete = vi.fn();
    const onAutoClear = vi.fn();
    await renderFinishedRun({ onComplete, onAutoClear }, NO_FAILURES);

    expect(onComplete).toHaveBeenCalledExactlyOnceWith(completeEvent(5, NO_FAILURES));
    expect(onAutoClear).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
  });

  it('treats a payload with neither a failed count nor a failing status as no failures: it auto-clears and offers no Retry', async () => {
    const onAutoClear = vi.fn();
    render(<RunProgressPanel jobRunId={5} onAutoClear={onAutoClear} />);
    // A hand-built payload (the server always sends both fields): the keep/clear decision reads the
    // status as well as the count, so the fixture's own derived PARTIAL has to go too for this
    // payload to say nothing at all about failure.
    const { failed, status, ...withoutFailed } = completeEvent(5, TWO_FAILURES);
    expect(failed).toBe(2);
    expect(status).toBe('PARTIAL');

    await act(async () => { feed.feeds[5].onComplete(withoutFailed); });

    expect(onAutoClear).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
  });

  it('keeps a PARTIAL payload that is missing its failed count: no auto-clear, Dismiss shown, no Retry', async () => {
    const onAutoClear = vi.fn();
    render(<RunProgressPanel jobRunId={5} onAutoClear={onAutoClear} onDismiss={vi.fn()} />);
    const { failed, ...withoutFailed } = completeEvent(5, TWO_FAILURES);
    expect(failed).toBe(2);
    expect(withoutFailed.status).toBe('PARTIAL');

    await act(async () => { feed.feeds[5].onComplete(withoutFailed); });

    expect(onAutoClear).not.toHaveBeenCalled();
    expect(screen.getByTestId('run-progress-status')).toHaveTextContent('(Complete)');
    expect(screen.getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
  });

  it('shows the failed rows, by name and status, in the kept panel after the real event sequence', async () => {
    await renderFinishedRun();

    const panel = screen.getByRole('region', { name: 'Run progress' });
    for (const place of ['East Fell', 'West Fell']) {
      const row = within(panel).getByText(place).closest('[data-testid="run-progress-row"]');
      expect(row).toHaveTextContent('Failed');
      expect(row).toHaveTextContent(`Weather data fetch failed for ${place} SUNSET`);
    }
    expect(within(panel).getByText('Test Hill').closest('[data-testid="run-progress-row"]'))
      .toHaveTextContent('Complete');
  });

  it('shows the status word in a stronger colour than the muted header', async () => {
    await renderFinishedRun();

    const status = screen.getByTestId('run-progress-status');
    expect(status.textContent).toBe('(Completed with failures)');
    expect(status).toHaveClass('text-plex-text-secondary');
    expect(status).not.toHaveClass('text-plex-text-muted');
  });

  it('subscribes once however many times the parent re-renders, and keeps the true duration', async () => {
    const view = render(<RunProgressPanel jobRunId={5} onComplete={() => {}} onAutoClear={() => {}} />);
    await playRun(feed.feeds[5], 5, TWO_FAILURES);
    const duration = () => screen.getByTestId('run-progress-panel').textContent.match(/(\d+\.\d)s \|/)[1];
    expect(duration()).toBe('2.1');

    for (let i = 0; i < 4; i += 1) {
      // New inline callbacks each time, as JobRunsMetricsView passes them.
      view.rerender(<RunProgressPanel jobRunId={5} onComplete={() => {}} onAutoClear={() => {}} />);
    }
    // A replayed summary after completion (a late re-subscribe) must not overwrite the duration.
    await act(async () => { feed.feeds[5].onSummary(summaryEvent(5, TWO_FAILURES, { elapsedMs: 987000 })); });

    expect(feed.subscriptions).toEqual([5]);
    expect(duration()).toBe('2.1');
  });

  it('calls the callbacks the parent passed LAST, not the ones it had at subscribe time', async () => {
    const first = vi.fn();
    const second = vi.fn();
    const view = render(<RunProgressPanel jobRunId={5} onComplete={first} />);
    view.rerender(<RunProgressPanel jobRunId={5} onComplete={second} />);

    await playRun(feed.feeds[5], 5, TWO_FAILURES);

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('on run-expired shows one plain line and Dismiss, and no Retry', async () => {
    const onDismiss = vi.fn();
    const onComplete = vi.fn();
    render(<RunProgressPanel jobRunId={5} onDismiss={onDismiss} onComplete={onComplete} />);

    await act(async () => { feed.feeds[5].onExpired({ jobRunId: 5 }); });

    expect(screen.getByTestId('run-progress-expired').textContent).toBe(EXPIRED);
    expect(screen.queryByRole('button', { name: /^Retry/ })).toBeNull();
    expect(onComplete).not.toHaveBeenCalled();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Dismiss run progress' })); });
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('puts Retry and Dismiss in one wrapping row and the alert line below the row', async () => {
    retryFailed.mockRejectedValue(refusal(404));
    await renderFinishedRun({ onDismiss: vi.fn() });
    const row = retryButton().parentElement;
    expect(row).toHaveClass('flex', 'flex-wrap', 'gap-2');
    expect(within(row).getByRole('button', { name: 'Dismiss run progress' })).toBeInTheDocument();

    await press();

    const alert = screen.getByRole('alert');
    expect(row.contains(alert)).toBe(false);
    expect(row.compareDocumentPosition(alert) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('is a named region and focuses its header on mount when promoted', async () => {
    render(<RunProgressPanel jobRunId={5} focusOnMount />);

    expect(screen.getByRole('region', { name: 'Run progress' })).toBeInTheDocument();
    expect(screen.getByText(/^Run Progress/)).toHaveFocus();
  });
});
