import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { post: vi.fn() } }));
vi.mock('../utils/createEventSource.js', () => ({ default: vi.fn(() => () => {}) }));

import apiClient from '../api/axiosClient.js';
import createEventSource from '../utils/createEventSource.js';
import { retryFailed, subscribeToRunProgress } from '../api/runProgressApi.js';

// retryFailed goes through the shared axios client, so the 401-refresh interceptor applies and a
// refusal is natively the shape utils/apiError.js reads. These pin the call and that the rejection
// is passed through untouched; the panel tests join this to the real panel.

describe('retryFailed', () => {
  beforeEach(() => {
    apiClient.post.mockReset();
  });

  it('posts to the run\'s retry-failed route and returns the response body', async () => {
    apiClient.post.mockResolvedValue({
      data: { status: 'Retry run started', runType: 'SHORT_TERM', jobRunId: 8 },
    });

    await expect(retryFailed(7)).resolves.toEqual({
      status: 'Retry run started', runType: 'SHORT_TERM', jobRunId: 8,
    });
    expect(apiClient.post).toHaveBeenCalledWith('/api/forecast/run/7/retry-failed');
  });

  it('rejects with the axios error itself, status and body intact', async () => {
    const refusal = Object.assign(new Error('Request failed with status code 400'), {
      response: { status: 400, data: { error: 'Run 7 is still in progress' } },
    });
    apiClient.post.mockRejectedValue(refusal);

    await expect(retryFailed(7)).rejects.toBe(refusal);
  });
});

describe('subscribeToRunProgress', () => {
  beforeEach(() => {
    createEventSource.mockClear();
  });

  it('listens for run-expired as well as the three progress events, and closes on either end', () => {
    const [onTask, onSummary, onComplete, onError, onExpired] = [vi.fn(), vi.fn(), vi.fn(), vi.fn(), vi.fn()];

    subscribeToRunProgress(7, onTask, onSummary, onComplete, onError, onExpired);

    expect(createEventSource).toHaveBeenCalledWith(
      '/api/forecast/run/7/progress',
      {},
      {
        'task-update': onTask,
        'run-summary': onSummary,
        'run-complete': onComplete,
        'run-expired': onExpired,
      },
      { onError, closeOn: ['run-complete', 'run-expired'] },
    );
  });
});
