import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import usePagination from '../hooks/usePagination.js';

describe('usePagination', () => {
  it('slices items correctly for page 1', () => {
    const items = Array.from({ length: 25 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    expect(result.current.pageItems).toEqual(items.slice(0, 10));
    expect(result.current.page).toBe(1);
    expect(result.current.totalPages).toBe(3);
  });

  it('navigates to page 2 with correct slice', () => {
    const items = Array.from({ length: 25 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    act(() => result.current.nextPage());

    expect(result.current.page).toBe(2);
    expect(result.current.pageItems).toEqual(items.slice(10, 20));
  });

  it('navigates to page 3 with correct slice', () => {
    const items = Array.from({ length: 25 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    act(() => result.current.setPage(3));

    expect(result.current.page).toBe(3);
    expect(result.current.pageItems).toEqual(items.slice(20, 25));
  });

  it('prevPage navigates backwards', () => {
    const items = Array.from({ length: 25 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    act(() => result.current.setPage(3));
    act(() => result.current.prevPage());

    expect(result.current.page).toBe(2);
  });

  it('firstPage navigates to page 1', () => {
    const items = Array.from({ length: 25 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    act(() => result.current.setPage(3));
    expect(result.current.page).toBe(3);

    act(() => result.current.firstPage());
    expect(result.current.page).toBe(1);
    expect(result.current.pageItems).toEqual(items.slice(0, 10));
  });

  it('lastPage navigates to the last page', () => {
    const items = Array.from({ length: 25 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    act(() => result.current.lastPage());

    expect(result.current.page).toBe(3);
    expect(result.current.pageItems).toEqual(items.slice(20, 25));
  });

  it('does not go below page 1', () => {
    const items = Array.from({ length: 5 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    act(() => result.current.prevPage());

    expect(result.current.page).toBe(1);
  });

  it('does not go above totalPages', () => {
    const items = Array.from({ length: 25 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    act(() => result.current.setPage(100));

    expect(result.current.page).toBe(3);
  });

  it('resets to page 1 when items change', () => {
    let items = Array.from({ length: 25 }, (_, i) => i);
    const { result, rerender } = renderHook(({ items: i }) => usePagination(i, 10), {
      initialProps: { items },
    });

    act(() => result.current.setPage(3));
    expect(result.current.page).toBe(3);

    // Simulate filter reducing items
    items = Array.from({ length: 8 }, (_, i) => i);
    rerender({ items });

    expect(result.current.page).toBe(1);
  });

  it('handles empty array', () => {
    const { result } = renderHook(() => usePagination([], 10));

    expect(result.current.pageItems).toEqual([]);
    expect(result.current.page).toBe(1);
    expect(result.current.totalPages).toBe(1);
  });

  it('handles items fewer than page size (single page)', () => {
    const items = [1, 2, 3];
    const { result } = renderHook(() => usePagination(items, 10));

    expect(result.current.pageItems).toEqual([1, 2, 3]);
    expect(result.current.page).toBe(1);
    expect(result.current.totalPages).toBe(1);
  });

  it('setPageSize recalculates totalPages and resets page', () => {
    const items = Array.from({ length: 30 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    expect(result.current.totalPages).toBe(3);

    act(() => result.current.setPage(3));
    expect(result.current.page).toBe(3);

    act(() => result.current.setPageSize(25));

    expect(result.current.pageSize).toBe(25);
    expect(result.current.totalPages).toBe(2);
    expect(result.current.page).toBe(1);
  });

  it('setPageSize to 50 shows all items on one page', () => {
    const items = Array.from({ length: 30 }, (_, i) => i);
    const { result } = renderHook(() => usePagination(items, 10));

    act(() => result.current.setPageSize(50));

    expect(result.current.totalPages).toBe(1);
    expect(result.current.pageItems).toEqual(items);
  });
});
