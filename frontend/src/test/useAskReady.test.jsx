import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { deferred, readyResponse } from './askFixtures.js';

vi.mock('../api/askApi.js', () => ({ getReady: vi.fn() }));
import { getReady } from '../api/askApi.js';
import useAskReady from '../hooks/useAskReady.js';

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
});

describe('useAskReady', () => {
  it('lists nothing while the first fetch is in flight', () => {
    getReady.mockReturnValue(deferred().promise);

    const { result } = renderHook(() => useAskReady('all', '2026-10-05T05:02:11'));

    expect(result.current).toMatchObject({ questions: [], loading: true, failed: false });
  });

  it('lists the served questions for the scope', async () => {
    getReady.mockResolvedValue(readyResponse());

    const { result } = renderHook(() => useAskReady('all', '2026-10-05T05:02:11'));

    await waitFor(() => expect(result.current.questions).toHaveLength(4));
    expect(result.current).toMatchObject({ loading: false, failed: false });
    expect(getReady).toHaveBeenCalledWith('all');
  });

  it('is keyed on the scope: a different scope refetches and never shows the old scope’s list', async () => {
    const next = deferred();
    getReady.mockResolvedValueOnce(readyResponse()).mockReturnValueOnce(next.promise);
    const { result, rerender } = renderHook(({ scope }) => useAskReady(scope, 'g1'), {
      initialProps: { scope: 'all' },
    });
    await waitFor(() => expect(result.current.questions).toHaveLength(4));

    rerender({ scope: 3 });

    // The region's questions have not arrived: the national list must not stand in for them.
    expect(result.current.questions).toEqual([]);
    expect(result.current.loading).toBe(true);
    await act(async () => { next.resolve({ scope: '3', questions: readyResponse().questions.slice(0, 1) }); });
    expect(result.current.questions.map((q) => q.id)).toEqual(['BEST_NEXT']);
    expect(getReady).toHaveBeenLastCalledWith(3);
  });

  it('refetches when the briefing is rebuilt, and shows nothing stale while it does', async () => {
    const rebuilt = deferred();
    getReady.mockResolvedValueOnce(readyResponse()).mockReturnValueOnce(rebuilt.promise);
    const { result, rerender } = renderHook(({ at }) => useAskReady('all', at), {
      initialProps: { at: '2026-10-05T05:02:11' },
    });
    await waitFor(() => expect(result.current.questions).toHaveLength(4));

    rerender({ at: '2026-10-05T17:03:40' });

    expect(getReady).toHaveBeenCalledTimes(2);
    expect(result.current.questions).toEqual([]);
    await act(async () => { rebuilt.resolve({ scope: 'all', questions: [] }); });
    expect(result.current).toMatchObject({ questions: [], loading: false });
  });

  it('does not refetch for a re-render with the same scope and the same briefing', async () => {
    getReady.mockResolvedValue(readyResponse());
    const { result, rerender } = renderHook(() => useAskReady('all', 'g1'));
    await waitFor(() => expect(result.current.questions).toHaveLength(4));

    rerender();
    rerender();

    expect(getReady).toHaveBeenCalledTimes(1);
  });

  it('lists nothing, and says it failed, when the fetch fails — a stale list is not a fallback', async () => {
    getReady.mockRejectedValue(Object.assign(new Error('off'), { status: 404 }));

    const { result } = renderHook(() => useAskReady('all', 'g1'));

    await waitFor(() => expect(result.current.failed).toBe(true));
    expect(result.current.questions).toEqual([]);
    expect(result.current.error).toMatchObject({ status: 404 });
  });

  it('lists nothing for a body with no question list', async () => {
    getReady.mockResolvedValue({ scope: 'all' });

    const { result } = renderHook(() => useAskReady('all', 'g1'));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.questions).toEqual([]);
    expect(result.current.failed).toBe(false);
  });

  it('forgets its list while hidden: shown again under the SAME key it never serves the old one', async () => {
    const again = deferred();
    getReady.mockResolvedValueOnce(readyResponse()).mockReturnValueOnce(again.promise);
    const { result, rerender } = renderHook(({ enabled }) => useAskReady('all', 'g1', { enabled }), {
      initialProps: { enabled: true },
    });
    await waitFor(() => expect(result.current.questions).toHaveLength(4));

    rerender({ enabled: false });
    expect(result.current.questions).toEqual([]);
    rerender({ enabled: true });

    // The fetch is out; the list held before the surface was hidden is hours old by now.
    expect(getReady).toHaveBeenCalledTimes(2);
    expect(result.current.questions).toEqual([]);
    expect(result.current.loading).toBe(true);
    await act(async () => { again.resolve(readyResponse()); });
    expect(result.current.questions).toHaveLength(4);
  });

  it('fetches nothing for a hidden surface', () => {
    const { result } = renderHook(() => useAskReady('all', 'g1', { enabled: false }));

    expect(getReady).not.toHaveBeenCalled();
    expect(result.current).toMatchObject({ questions: [], loading: false, failed: false });
  });

  it('drops a superseded fetch when it lands (settled in an awaited act)', async () => {
    const old = deferred();
    const fresh = deferred();
    getReady.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    const { result, rerender } = renderHook(({ at }) => useAskReady('all', at), {
      initialProps: { at: 'g1' },
    });
    rerender({ at: 'g2' });

    await act(async () => { fresh.resolve({ scope: 'all', questions: readyResponse().questions.slice(0, 1) }); });
    await act(async () => { old.resolve(readyResponse()); });

    expect(result.current.questions).toHaveLength(1);
  });

  it('a superseded fetch’s failure cannot blank the newest answer', async () => {
    const old = deferred();
    const fresh = deferred();
    getReady.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    const { result, rerender } = renderHook(({ at }) => useAskReady('all', at), {
      initialProps: { at: 'g1' },
    });
    rerender({ at: 'g2' });
    await act(async () => { fresh.resolve(readyResponse()); });

    await act(async () => { old.reject(new Error('late')); });

    expect(result.current.questions).toHaveLength(4);
    expect(result.current.failed).toBe(false);
  });

  it('writes nothing to storage', async () => {
    const write = vi.spyOn(Storage.prototype, 'setItem');
    getReady.mockResolvedValue(readyResponse());
    const { result } = renderHook(() => useAskReady('all', 'g1'));
    await waitFor(() => expect(result.current.questions).toHaveLength(4));

    expect(write).not.toHaveBeenCalled();
    write.mockRestore();
  });
});
