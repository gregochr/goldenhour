import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import RunProgressPanel from '../components/RunProgressPanel.jsx';
import { TWO_FAILURES, completeEvent } from './runProgressFixtures.js';

// The REAL retryFailed joined to the REAL panel; only the HTTP client and the EventSource wrapper
// are mocked, so what the shared axios client rejects with is what the panel has to read. A mock at
// the runProgressApi boundary (RunProgressPanelRetry.test.jsx) cannot catch the two disagreeing about
// the error's shape.

vi.mock('../api/axiosClient.js', () => ({ default: { post: vi.fn() } }));
vi.mock('../utils/createEventSource.js', () => ({ default: vi.fn() }));

import apiClient from '../api/axiosClient.js';
import createEventSource from '../utils/createEventSource.js';

const NOTHING = "Nothing to retry: this run's failed places can no longer be run again.";

/** What axios rejects with for a non-2xx answer. */
const axiosRefusal = (status, data) => Object.assign(new Error(`Request failed with status code ${status}`), {
  isAxiosError: true,
  response: { status, data },
});

const renderKeptRun = async (props = {}) => {
  let handlers;
  createEventSource.mockImplementation((path, params, eventHandlers) => {
    handlers = eventHandlers;
    return () => {};
  });
  render(<RunProgressPanel jobRunId={5} {...props} />);
  await act(async () => { handlers['run-complete'](completeEvent(5, TWO_FAILURES)); });
};

const pressRetry = async () => {
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Retry 2 failed' })); });
};

describe('RunProgressPanel with the real retryFailed', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('shows the nothing-to-retry sentence for a 404 with an empty body', async () => {
    apiClient.post.mockRejectedValue(axiosRefusal(404, ''));
    await renderKeptRun();

    await pressRetry();

    expect(apiClient.post).toHaveBeenCalledWith('/api/forecast/run/5/retry-failed');
    expect(screen.getByRole('alert').textContent).toBe(NOTHING);
  });

  it('shows the server\'s sentence for a refusal carrying {error}', async () => {
    apiClient.post.mockRejectedValue(axiosRefusal(400, { error: 'Run 5 is still in progress' }));
    await renderKeptRun();

    await pressRetry();

    expect(screen.getByRole('alert').textContent).toBe('Run 5 is still in progress');
  });

  it('shows the generic line for a 500 whose body is an HTML page', async () => {
    apiClient.post.mockRejectedValue(axiosRefusal(500, '<html><body>Bad gateway</body></html>'));
    await renderKeptRun();

    await pressRetry();

    expect(screen.getByRole('alert').textContent).toBe('Could not start the retry.');
    expect(screen.queryByText(/Bad gateway/)).toBeNull();
  });

  it('shows the generic line when axios never got an answer (no response at all)', async () => {
    apiClient.post.mockRejectedValue(Object.assign(new Error('Network Error'), { isAxiosError: true }));
    await renderKeptRun();

    await pressRetry();

    expect(screen.getByRole('alert').textContent).toBe('Could not start the retry.');
  });

  it('hands the retry run id from a 202 to the parent', async () => {
    const answer = {
      status: 'Retry run started', runType: 'VERY_SHORT_TERM', jobRunId: 77, slots: 2, skipped: [],
    };
    apiClient.post.mockResolvedValue({ data: answer });
    const onRetryStarted = vi.fn();
    await renderKeptRun({ onRetryStarted });

    await pressRetry();

    expect(onRetryStarted).toHaveBeenCalledExactlyOnceWith(77, answer);
  });

  it('treats a 2xx with an empty body as started, not failed', async () => {
    apiClient.post.mockResolvedValue({ data: '' });
    const onRetryStarted = vi.fn();
    await renderKeptRun({ onRetryStarted });

    await pressRetry();

    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('status').textContent).toBe('Retry started.');
    expect(onRetryStarted).toHaveBeenCalledExactlyOnceWith(undefined, '');
  });
});
