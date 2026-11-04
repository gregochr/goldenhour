import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import useRunCalendar from '../hooks/useRunCalendar.js';
import useRunCalls from '../hooks/useRunCalls.js';
import useSchedulerActions from '../hooks/useSchedulerActions.js';
import { fetchRunCalendar, fetchRunCalls } from '../api/runsApi.js';
import { pauseJob, resumeJob, triggerJob } from '../api/schedulerApi.js';

vi.mock('../api/runsApi.js', () => ({
  fetchRunCalendar: vi.fn(),
  fetchRunCalls: vi.fn(),
}));
vi.mock('../api/schedulerApi.js', () => ({
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  triggerJob: vi.fn(),
}));

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('useRunCalendar — closed calendar and unexplained failures', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetches nothing and reports not loading while the calendar is closed', () => {
    const { result } = renderHook(() => useRunCalendar('2026-10', false));

    expect(fetchRunCalendar).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
    expect(result.current.data).toBeNull();
    expect(result.current.error).toBeNull();
  });

  it('uses a generic message when the month fails with no message at all', async () => {
    fetchRunCalendar.mockRejectedValue({});

    const { result } = renderHook(() => useRunCalendar('2026-10', true));

    await waitFor(() => expect(result.current.error).toBe('Failed to load the month'));
    expect(result.current.loading).toBe(false);
    expect(result.current.data).toBeNull();
  });
});

describe('useRunCalls — unexplained and stale failures', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses a generic message when the calls fail with no message at all', async () => {
    fetchRunCalls.mockRejectedValue({});

    const { result } = renderHook(() => useRunCalls('job-1'));

    await waitFor(() => expect(result.current.error).toBe('Failed to load the calls'));
    expect(result.current.loading).toBe(false);
  });

  it('drops a failure that lands after the row was closed', async () => {
    const pending = deferred();
    fetchRunCalls.mockReturnValue(pending.promise);
    const { result, rerender } = renderHook(({ id }) => useRunCalls(id), { initialProps: { id: 'job-1' } });
    expect(result.current.loading).toBe(true);

    rerender({ id: null });
    await act(async () => {
      pending.reject(new Error('late failure'));
      await pending.promise.catch(() => {});
    });

    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
  });
});

describe('useSchedulerActions — double presses', () => {
  const job = { jobKey: 'briefing', displayName: 'Daily briefing' };

  beforeEach(() => vi.clearAllMocks());

  it('a second Run now while the first call is in flight never reaches the server', async () => {
    const pending = deferred();
    triggerJob.mockReturnValue(pending.promise);
    const { result } = renderHook(() => useSchedulerActions(vi.fn()));

    let first;
    act(() => { first = result.current.runNow(job); });
    await act(async () => { await result.current.runNow(job); });

    expect(triggerJob).toHaveBeenCalledTimes(1);
    expect(result.current.stateOf('briefing').pending).toBe(true);
    await act(async () => { pending.resolve(); await first; });
    expect(result.current.stateOf('briefing').triggered).toBe(true);
  });

  it('a second Pause while the first is in flight never reaches the server, and Resume is guarded the same way', async () => {
    const pending = deferred();
    pauseJob.mockReturnValue(pending.promise);
    resumeJob.mockReturnValue(pending.promise);
    const reload = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useSchedulerActions(reload));

    let first;
    act(() => { first = result.current.pause(job); });
    await act(async () => {
      await result.current.pause(job);
      await result.current.resume(job);
    });

    expect(pauseJob).toHaveBeenCalledTimes(1);
    expect(resumeJob).not.toHaveBeenCalled();
    expect(result.current.stateOf('briefing').busy).toBe(true);
    await act(async () => { pending.resolve(); await first; });
    expect(reload).toHaveBeenCalledTimes(1);
    expect(result.current.stateOf('briefing').busy).toBe(false);
  });
});
