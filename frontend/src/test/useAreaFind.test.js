import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));

import { fetchAreaDirectory, fetchAreaOutlook } from '../api/areasApi.js';
import useAreaFind from '../hooks/useAreaFind.js';

/**
 * The lazy area fetch behind Find (find-and-favourites-plan.md F2): nothing until it is wanted, the directory once,
 * the outlook each time Find opens, and a failure of either leaves the rest of Find alone.
 */
describe('useAreaFind', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    fetchAreaDirectory.mockResolvedValue([{ id: 1, name: 'Wall' }]);
    fetchAreaOutlook.mockResolvedValue([{ areaId: 1, windows: [{ date: 'd' }] }, { areaId: 2 }]);
  });

  it('requests nothing while inactive', () => {
    const { result } = renderHook(() => useAreaFind(false));

    expect(fetchAreaDirectory).not.toHaveBeenCalled();
    expect(fetchAreaOutlook).not.toHaveBeenCalled();
    expect(result.current).toEqual({ directory: null, outlook: null });
  });

  it('fetches the directory once and the outlook on every opening, keying the outlook by area id', async () => {
    const { result, rerender } = renderHook(({ active }) => useAreaFind(active), { initialProps: { active: true } });

    await waitFor(() => expect(result.current.outlook).not.toBeNull());
    expect(result.current.directory).toEqual([{ id: 1, name: 'Wall' }]);
    // A row with no windows reads as an empty list, never undefined.
    expect(result.current.outlook.get(1)).toEqual([{ date: 'd' }]);
    expect(result.current.outlook.get(2)).toEqual([]);

    rerender({ active: false });
    rerender({ active: true });

    await waitFor(() => expect(fetchAreaOutlook).toHaveBeenCalledTimes(2));
    expect(fetchAreaDirectory).toHaveBeenCalledTimes(1);
  });

  it('keeps what it had when a read fails or answers something that is not a list', async () => {
    fetchAreaDirectory.mockRejectedValue(new Error('network'));
    fetchAreaOutlook.mockResolvedValue({ not: 'a list' });

    const { result } = renderHook(() => useAreaFind(true));

    await waitFor(() => expect(fetchAreaOutlook).toHaveBeenCalled());
    expect(result.current).toEqual({ directory: null, outlook: null });
  });

  it('a read that lands after the dialog is gone is ignored', async () => {
    let resolve;
    fetchAreaOutlook.mockReturnValue(new Promise((r) => { resolve = r; }));
    const { result, unmount } = renderHook(() => useAreaFind(true));
    await waitFor(() => expect(result.current.directory).not.toBeNull());

    unmount();
    resolve([{ areaId: 1, windows: [] }]);
    await Promise.resolve();

    expect(result.current.outlook).toBeNull();
  });

  it('a failed outlook read leaves the outlook absent', async () => {
    fetchAreaOutlook.mockRejectedValue(new Error('403'));

    const { result } = renderHook(() => useAreaFind(true));

    await waitFor(() => expect(result.current.directory).not.toBeNull());
    expect(result.current.outlook).toBeNull();
  });
});
