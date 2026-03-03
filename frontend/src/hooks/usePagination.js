import { useState, useMemo } from 'react';

/**
 * Client-side pagination hook.
 *
 * @param {Array} items - The full list of items to paginate.
 * @param {number} [initialPageSize=10] - Default page size.
 * @returns {object} Pagination state and helpers.
 */
export default function usePagination(items, initialPageSize = 10) {
  const [page, setPageRaw] = useState(1);
  const [pageSize, setPageSizeRaw] = useState(initialPageSize);

  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));

  // Reset to page 1 when items count changes (e.g. filter applied).
  // Uses React's recommended setState-during-render pattern for derived state.
  const [prevLength, setPrevLength] = useState(items.length);
  if (items.length !== prevLength) {
    setPrevLength(items.length);
    setPageRaw(1);
  }

  // Clamp page when totalPages shrinks (e.g. page size increased)
  const effectivePage = Math.min(page, totalPages);

  const pageItems = useMemo(() => {
    const start = (effectivePage - 1) * pageSize;
    return items.slice(start, start + pageSize);
  }, [items, effectivePage, pageSize]);

  function setPage(n) {
    setPageRaw(Math.max(1, Math.min(n, totalPages)));
  }

  function nextPage() {
    setPage(effectivePage + 1);
  }

  function prevPage() {
    setPage(effectivePage - 1);
  }

  function firstPage() {
    setPageRaw(1);
  }

  function lastPage() {
    setPageRaw(totalPages);
  }

  function setPageSize(size) {
    setPageSizeRaw(size);
    setPageRaw(1);
  }

  return {
    pageItems,
    page: effectivePage,
    totalPages,
    setPage,
    nextPage,
    prevPage,
    firstPage,
    lastPage,
    pageSize,
    setPageSize,
  };
}
