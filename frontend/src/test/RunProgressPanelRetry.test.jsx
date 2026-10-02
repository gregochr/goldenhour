import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import RunProgressPanel from '../components/RunProgressPanel.jsx';

// Since #977 the backend answers retry-failed with 404 and an empty body when none of a run's
// failed places can be run again. The panel used to swallow every failure, so the admin pressed
// "Retry failed" and saw nothing happen.

vi.mock('../api/runProgressApi', () => ({
  subscribeToRunProgress: vi.fn(),
  retryFailed: vi.fn(),
}));

import { subscribeToRunProgress, retryFailed } from '../api/runProgressApi';

const NOTHING = "Nothing to retry: this run's failed places can no longer be run again.";
const GENERIC = 'Could not start the retry.';

/** The server's refusal as retryFailed rejects with it. */
const refusal = (status, data = null) => Object.assign(new Error(`HTTP ${status}`), {
  status, response: { status, data },
});

let handlers;

/** Renders the panel and drives its SSE feed to a finished run with two failures. */
const renderFinishedRun = async (jobRunId = 5) => {
  const view = render(<RunProgressPanel jobRunId={jobRunId} />);
  await act(async () => {
    handlers.complete({ total: 3, completed: 1, failed: 2, skipped: 0, triaged: 0, inProgress: 0 });
  });
  return view;
};

const press = async () => {
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: /Retry 2 failed/ }));
  });
};

describe('RunProgressPanel retry failure', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    subscribeToRunProgress.mockImplementation((_id, onTask, onSummary, onComplete) => {
      handlers = { summary: onSummary, complete: onComplete };
      return () => {};
    });
  });

  it('says there is nothing to retry when the backend answers 404 with no body', async () => {
    retryFailed.mockRejectedValue(refusal(404));
    await renderFinishedRun();

    await press();

    // role="alert" is a live region, which takes its announcement from its content rather than
    // from an accessible name, so the content is what is asserted.
    expect(screen.getByRole('alert')).toHaveTextContent(NOTHING);
  });

  it('shows the server\'s own sentence for any other refusal that has one', async () => {
    retryFailed.mockRejectedValue(refusal(400, { error: 'Run 5 is still in progress' }));
    await renderFinishedRun();

    await press();

    expect(screen.getByRole('alert')).toHaveTextContent('Run 5 is still in progress');
    expect(screen.queryByText(NOTHING)).toBeNull();
  });

  it('shows the generic line when a refusal says nothing usable', async () => {
    retryFailed.mockRejectedValue(refusal(500));
    await renderFinishedRun();

    await press();

    expect(screen.getByRole('alert')).toHaveTextContent(GENERIC);
  });

  it('shows the generic line, not the transport message, when the request never got an answer', async () => {
    retryFailed.mockRejectedValue(new Error('Could not reach the server.'));
    await renderFinishedRun();

    await press();

    expect(screen.getByRole('alert')).toHaveTextContent(GENERIC);
    expect(screen.queryByText('Could not reach the server.')).toBeNull();
  });

  it('leaves the button enabled and focused, and not in its retrying state, after a failure', async () => {
    retryFailed.mockRejectedValue(refusal(404));
    await renderFinishedRun();
    const button = screen.getByRole('button', { name: /Retry 2 failed/ });
    button.focus();

    await press();

    expect(button).toBeEnabled();
    expect(button).not.toHaveAttribute('aria-disabled');
    expect(button).toHaveTextContent('Retry 2 failed');
    expect(button).toHaveFocus();
  });

  it('shows no line before any press', async () => {
    await renderFinishedRun();

    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('clears the line the moment the button is pressed again, before the next answer arrives', async () => {
    retryFailed.mockRejectedValueOnce(refusal(404));
    await renderFinishedRun();
    await press();
    expect(screen.getByRole('alert')).toBeInTheDocument();

    let settle;
    retryFailed.mockReturnValueOnce(new Promise((resolve) => { settle = resolve; }));
    await press();

    expect(screen.queryByRole('alert')).toBeNull();
    // aria-disabled, never `disabled`: a focused button that becomes disabled loses focus to
    // <body> in a real browser (jsdom does not model that, so the attribute is what is pinned).
    const busy = screen.getByRole('button', { name: 'Retrying...' });
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    expect(busy).not.toHaveAttribute('disabled');
    await act(async () => { settle({ jobRunId: 9 }); });
  });

  it('refuses a second press while the first is still out', async () => {
    let settle;
    retryFailed.mockReturnValue(new Promise((resolve) => { settle = resolve; }));
    await renderFinishedRun();
    await press();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Retrying...' }));
    });

    expect(retryFailed).toHaveBeenCalledTimes(1);
    await act(async () => { settle({ jobRunId: 9 }); });
  });

  it('clears the line when a later retry succeeds', async () => {
    retryFailed.mockRejectedValueOnce(refusal(404)).mockResolvedValueOnce({ jobRunId: 9 });
    await renderFinishedRun();
    await press();
    expect(screen.getByRole('alert')).toHaveTextContent(NOTHING);

    await press();

    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByTestId('retry-failed-btn')).toBeNull();
  });

  it('shows the line again when the same failure repeats', async () => {
    retryFailed.mockRejectedValue(refusal(404));
    await renderFinishedRun();
    await press();
    await press();

    expect(screen.getByRole('alert')).toHaveTextContent(NOTHING);
  });

  it('does not carry the line over to a different run', async () => {
    retryFailed.mockRejectedValue(refusal(404));
    const { rerender } = await renderFinishedRun(5);
    await press();
    expect(screen.getByRole('alert')).toBeInTheDocument();

    rerender(<RunProgressPanel jobRunId={6} />);

    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('does not show a failure that lands after the panel has moved to another run', async () => {
    let reject;
    retryFailed.mockReturnValue(new Promise((_, r) => { reject = r; }));
    const { rerender } = await renderFinishedRun(5);
    await press();

    rerender(<RunProgressPanel jobRunId={6} />);
    await act(async () => { reject(refusal(404)); });

    expect(screen.queryByRole('alert')).toBeNull();
  });
});
