import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchRuns } from '../api/runsApi.js';
import { apiErrorMessage } from '../utils/apiError.js';
import { runRowId } from '../utils/runsView.js';

/** The head page's size, and the page "Load more" asks for. */
export const RUNS_PAGE_SIZE = 20;

/** How often the head is re-fetched: every minute, every 15 seconds while anything is running. */
export const RUNS_POLL_MS = 60 * 1000;
export const RUNS_LIVE_POLL_MS = 15 * 1000;

const EMPTY = { key: null, items: [], nextCursor: null, meta: null, error: null };

/** A list item's start as a number, for ordering; an unparseable instant sorts as the oldest. */
function startedMs(item) {
  const ms = Date.parse(item.startedAt);
  return Number.isNaN(ms) ? 0 : ms;
}

/**
 * Merges a freshly fetched head page over the rows already loaded (plan §5.2, polling): head rows replace their
 * loaded twins by {@code kind:id}, new rows arrive at the top, and the older rows already loaded stay as they were,
 * so a poll neither drops the pages the reader loaded nor duplicates a row. A head that is not a full page is the
 * whole result, so it replaces everything.
 *
 * @param {Array<object>} loaded the rows on screen
 * @param {string|null} loadedCursor the cursor after the last loaded row
 * @param {{items: Array<object>, nextCursor: string|null}} head the fresh head page
 * @returns {{items: Array<object>, nextCursor: string|null}}
 */
export function mergeHead(loaded, loadedCursor, head) {
  if (head.nextCursor == null || head.items.length === 0) return { items: head.items, nextCursor: head.nextCursor };
  const headIds = new Set(head.items.map(runRowId));
  const oldestHead = startedMs(head.items[head.items.length - 1]);
  const tail = loaded.filter((item) => !headIds.has(runRowId(item)) && startedMs(item) <= oldestHead);
  return {
    items: [...head.items, ...tail],
    // With older rows kept, the cursor already points past them; otherwise the head's own is the next page.
    nextCursor: tail.length > 0 ? loadedCursor : head.nextCursor,
  };
}

/**
 * The Runs list: the head page for a range, kind and attention filter, a cursor "Load more", and a poll that merges
 * a fresh head over what is loaded. Everything it serves (status, group, aggregates) is the server's; nothing is
 * merged or derived here (R-D1).
 *
 * <p>A filter change starts a fresh list (rows from the old filter are never shown under the new one) and any
 * response for an old filter is dropped. {@code pollCount} ticks after each successful head fetch, so the open
 * job-run panel can refetch its own item on the same beat. The poll is 60 seconds, 15 while {@code runningCount}
 * is above zero.
 *
 * @param {{range: string, kind: string, attention: boolean}} filters the server's range ({@code TODAY|WEEK|MONTH}),
 *   kind ({@code ALL|CYCLES|…}) and attention flag
 * @returns {object} {items, meta, nextCursor, loading, loadingMore, error, updatedAt, pollCount, loadMore, refresh}
 */
export default function useRunsList({ range, kind, attention }) {
  const filtersKey = `${range}|${kind}|${attention}`;
  const [state, setState] = useState(EMPTY);
  const [loadingMore, setLoadingMore] = useState(false);
  const [updatedAt, setUpdatedAt] = useState(null);
  const [pollCount, setPollCount] = useState(0);
  const liveKey = useRef(filtersKey);
  const moreInFlight = useRef(false);

  const current = state.key === filtersKey ? state : EMPTY;
  const loading = state.key !== filtersKey;

  const loadHead = useCallback(async () => {
    const key = filtersKey;
    try {
      const head = await fetchRuns({ range, kind, attention, size: RUNS_PAGE_SIZE });
      if (liveKey.current !== key) return;
      setState((prev) => {
        const merged = prev.key === key
          ? mergeHead(prev.items, prev.nextCursor, head)
          : { items: head.items, nextCursor: head.nextCursor };
        return { key, items: merged.items, nextCursor: merged.nextCursor, meta: head, error: null };
      });
      setUpdatedAt(new Date());
      setPollCount((count) => count + 1);
    } catch (err) {
      if (liveKey.current !== key) return;
      setState((prev) => ({
        ...(prev.key === key ? prev : EMPTY),
        key,
        error: apiErrorMessage(err, err?.message || 'Failed to load runs'),
      }));
    }
  }, [filtersKey, range, kind, attention]);

  // A filter change starts over; the first load, and each later one, comes through here.
  useEffect(() => {
    liveKey.current = filtersKey;
    moreInFlight.current = false;
    loadHead();
  }, [filtersKey, loadHead]);

  const runningCount = current.meta?.runningCount ?? 0;
  useEffect(() => {
    const timer = setInterval(loadHead, runningCount > 0 ? RUNS_LIVE_POLL_MS : RUNS_POLL_MS);
    return () => clearInterval(timer);
  }, [loadHead, runningCount]);

  const nextCursor = current.nextCursor;
  const loadMore = useCallback(async () => {
    if (!nextCursor || moreInFlight.current) return;
    const key = filtersKey;
    moreInFlight.current = true;
    setLoadingMore(true);
    try {
      const page = await fetchRuns({ range, kind, attention, before: nextCursor, size: RUNS_PAGE_SIZE });
      if (liveKey.current !== key) return;
      setState((prev) => {
        if (prev.key !== key) return prev;
        // A row the head poll already put on screen is never added twice.
        const seen = new Set(prev.items.map(runRowId));
        const fresh = page.items.filter((item) => !seen.has(runRowId(item)));
        return { ...prev, items: [...prev.items, ...fresh], nextCursor: page.nextCursor, error: null };
      });
    } catch (err) {
      if (liveKey.current === key) {
        setState((prev) => (prev.key === key
          ? { ...prev, error: apiErrorMessage(err, err?.message || 'Failed to load more runs') }
          : prev));
      }
    } finally {
      moreInFlight.current = false;
      setLoadingMore(false);
    }
  }, [nextCursor, filtersKey, range, kind, attention]);

  return {
    items: current.items,
    meta: current.meta,
    nextCursor,
    loading,
    loadingMore,
    error: current.error,
    updatedAt,
    pollCount,
    loadMore,
    refresh: loadHead,
  };
}
