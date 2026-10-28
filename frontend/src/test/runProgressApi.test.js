import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../utils/createEventSource.js', () => ({ default: vi.fn(() => () => {}) }));

import createEventSource from '../utils/createEventSource.js';
import { subscribeToRunProgress } from '../api/runProgressApi.js';

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
