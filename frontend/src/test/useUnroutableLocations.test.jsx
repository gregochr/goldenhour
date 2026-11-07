import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';

vi.mock('../api/driveTimesAdminApi', () => ({ fetchUnroutableLocations: vi.fn() }));

import { fetchUnroutableLocations } from '../api/driveTimesAdminApi';
import useUnroutableLocations from '../hooks/useUnroutableLocations.js';

const ROW = { locationId: 7, name: 'Helvellyn' };

/** A promise the test settles by hand, so a read can resolve or reject after the hook has unmounted. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('useUnroutableLocations', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('starts empty and unloaded, then holds the served rows', async () => {
    fetchUnroutableLocations.mockResolvedValue([ROW]);

    const { result } = renderHook(() => useUnroutableLocations());

    expect(result.current).toMatchObject({ rows: [], loaded: false, failed: false });
    await waitFor(() => expect(result.current.loaded).toBe(true));
    expect(result.current).toMatchObject({ rows: [ROW], loaded: true, failed: false });
  });

  it('reports a failed read with no rows', async () => {
    fetchUnroutableLocations.mockRejectedValue(new Error('403'));

    const { result } = renderHook(() => useUnroutableLocations());

    await waitFor(() => expect(result.current.failed).toBe(true));
    expect(result.current).toMatchObject({ rows: [], loaded: true, failed: true });
  });

  it('ignores an answer that lands after unmount', async () => {
    const read = deferred();
    fetchUnroutableLocations.mockReturnValue(read.promise);

    const { result, unmount } = renderHook(() => useUnroutableLocations());
    await waitFor(() => expect(fetchUnroutableLocations).toHaveBeenCalledTimes(1));
    unmount();
    read.resolve([ROW]);
    await read.promise;

    expect(result.current).toMatchObject({ rows: [], loaded: false, failed: false });
  });

  it('ignores a failure that lands after unmount', async () => {
    const read = deferred();
    fetchUnroutableLocations.mockReturnValue(read.promise);

    const { result, unmount } = renderHook(() => useUnroutableLocations());
    await waitFor(() => expect(fetchUnroutableLocations).toHaveBeenCalledTimes(1));
    unmount();
    read.reject(new Error('403'));
    await read.promise.catch(() => {});

    expect(result.current).toMatchObject({ rows: [], loaded: false, failed: false });
  });

  it('re-reads on refetch and replaces the rows with the new answer', async () => {
    fetchUnroutableLocations.mockResolvedValueOnce([ROW]).mockResolvedValueOnce([]);

    const { result } = renderHook(() => useUnroutableLocations());
    await waitFor(() => expect(result.current.rows).toEqual([ROW]));
    act(() => result.current.refetch());

    await waitFor(() => expect(result.current.rows).toEqual([]));
    expect(fetchUnroutableLocations).toHaveBeenCalledTimes(2);
  });
});
