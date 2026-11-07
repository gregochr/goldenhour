import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import useCycleDetail from '../hooks/useCycleDetail.js';
import { fetchPipelineRunDetail } from '../api/pipelineRunApi.js';

vi.mock('../api/pipelineRunApi.js', () => ({ fetchPipelineRunDetail: vi.fn() }));

const finished = (id) => ({ run: { id, status: 'COMPLETED' } });

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('useCycleDetail — edge branches', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reports a 404 as notFound with no error text', async () => {
    fetchPipelineRunDetail.mockRejectedValue({ response: { status: 404 } });

    const { result } = renderHook(() => useCycleDetail(8101));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.notFound).toBe(true);
    expect(result.current.error).toBeNull();
    expect(result.current.detail).toBeNull();
  });

  it('reports any other failure as an error message, not notFound', async () => {
    fetchPipelineRunDetail.mockRejectedValue({ response: { status: 500, data: { error: 'Database down' } } });

    const { result } = renderHook(() => useCycleDetail(8102));

    await waitFor(() => expect(result.current.error).toBe('Database down'));
    expect(result.current.notFound).toBe(false);
    expect(result.current.loading).toBe(false);
  });

  it('refresh() with no cycle id resolves without reading anything', async () => {
    const { result } = renderHook(() => useCycleDetail(null));

    await expect(result.current.refresh()).resolves.toBeUndefined();

    expect(fetchPipelineRunDetail).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
  });

  it('refresh() after the last reader has left resolves without reading again', async () => {
    fetchPipelineRunDetail.mockResolvedValue(finished(8103));
    const { result, unmount } = renderHook(() => useCycleDetail(8103));
    await waitFor(() => expect(result.current.detail).not.toBeNull());
    const { refresh } = result.current;
    unmount();

    await expect(refresh()).resolves.toBeUndefined();

    expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(1);
  });

  it('a response that lands after its reader left does not fill the next reader\'s fresh entry', async () => {
    const first = deferred();
    const second = deferred();
    fetchPipelineRunDetail.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const one = renderHook(() => useCycleDetail(8104));
    await waitFor(() => expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(1));
    one.unmount();
    const two = renderHook(() => useCycleDetail(8104));
    await waitFor(() => expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(2));

    first.resolve(finished(111));
    await first.promise;

    expect(two.result.current.loading).toBe(true);
    expect(two.result.current.detail).toBeNull();
    second.resolve(finished(222));
    await waitFor(() => expect(two.result.current.detail).toEqual(finished(222)));
    two.unmount();
  });

  it('a failure that lands after its reader left does not mark the next reader\'s entry failed', async () => {
    const first = deferred();
    const second = deferred();
    fetchPipelineRunDetail.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const one = renderHook(() => useCycleDetail(8105));
    await waitFor(() => expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(1));
    one.unmount();
    const two = renderHook(() => useCycleDetail(8105));
    await waitFor(() => expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(2));

    first.reject(new Error('stale failure'));
    await first.promise.catch(() => {});

    expect(two.result.current.loading).toBe(true);
    expect(two.result.current.error).toBeNull();
    second.resolve(finished(333));
    await waitFor(() => expect(two.result.current.detail).toEqual(finished(333)));
    two.unmount();
  });
});
